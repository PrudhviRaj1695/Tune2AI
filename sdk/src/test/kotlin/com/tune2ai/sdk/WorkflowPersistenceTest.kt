package com.tune2ai.sdk

import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.RequestId
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.Failed
import com.tune2ai.sdk.api.Tune2AIEvent.Progress
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.core.RequestController
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.core.tools.ToolCallingEngine
import com.tune2ai.sdk.core.workflow.StepType
import com.tune2ai.sdk.core.workflow.Transition
import com.tune2ai.sdk.core.workflow.WorkflowDefinition
import com.tune2ai.sdk.core.workflow.WorkflowEngine
import com.tune2ai.sdk.core.workflow.WorkflowStep
import com.tune2ai.sdk.services.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowPersistenceTest {
    private fun model(id: String, next: List<Transition> = emptyList()) =
        WorkflowStep(id, StepType.MODEL, prompt = "do $id", transitions = next)

    private fun List<Tune2AIEvent>.error() = (last() as Failed).error
    private fun List<Tune2AIEvent>.stepsSeen() = filterIsInstance<Progress>().mapNotNull { it.detail }.filter { it.startsWith("Step ") }

    /** Virtual-time engine for model-only workflows, with a given workflow [storage]. */
    private suspend fun TestScope.engine(backend: FakeBackend, storage: Storage): WorkflowEngine {
        val manager = modelManager(backend)
        manager.register(fakeModel())
        return WorkflowEngine(RequestController(manager, Router(manager), dispatcher = StandardTestDispatcher(testScheduler)), ToolCallingEngine(emptyList()), storage)
    }

    @Test
    fun checkpointsHoldCompletedStepOutputs() = runTest {
        val storage = InMemoryStorage()
        val engine = engine(FakeBackend(tokens = listOf("x")), storage)
        val def = WorkflowDefinition("two", steps = listOf(model("a", listOf(Transition("b"))), model("b")))
        engine.run(def, requestId = RequestId("r1")).toList()

        val cp = engine.checkpoint("r1")!!
        assertTrue("run recorded as complete", cp.done)
        assertEquals(setOf("a", "b"), cp.variables.keys) // both steps' outputs are persisted
        assertEquals(1, engine.runs().size)
    }

    @Test
    fun resumeRefusesAChangedDefinitionVersion() = runTest {
        val storage = InMemoryStorage()
        val engine = engine(FakeBackend(tokens = listOf("x")), storage)
        val def = WorkflowDefinition("w", version = 1, steps = listOf(model("a")))
        engine.run(def, requestId = RequestId("r1")).toList()

        val events = engine.resume(def.copy(version = 2), RequestId("r1")).toList()
        assertEquals(ErrorCategory.INVALID_REQUEST, events.error().category)
        assertTrue(events.error().message!!.contains("definition changed"))
    }

    @Test
    fun deleteRemovesThePersistedRun() = runTest {
        val storage = InMemoryStorage()
        val engine = engine(FakeBackend(tokens = listOf("x")), storage)
        val def = WorkflowDefinition("w", steps = listOf(model("a")))
        engine.run(def, requestId = RequestId("r1")).toList()

        assertTrue(engine.delete("r1"))
        assertNull(engine.checkpoint("r1"))
        assertFalse(engine.delete("r1")) // already gone
        assertEquals(ErrorCategory.INVALID_REQUEST, engine.resume(def, RequestId("r1")).toList().error().category)
    }

    /** Kill after step 2, resume runs only step 3. Real dispatchers: a model step blocks so the cancel lands mid-run. */
    @Test
    fun resumeContinuesWithoutRerunningCompletedSteps() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val storage = InMemoryStorage()
            val def = WorkflowDefinition(
                "pipeline",
                steps = listOf(model("a", listOf(Transition("b"))), model("b", listOf(Transition("c"))), model("c")),
            )
            val runId = RequestId("run-1")

            // First attempt: steps a and b finish quickly, step c streams slowly so we can cancel during it.
            val backend1 = FakeBackend(tokenDelayMillis = 80).apply {
                script += listOf("ra"); script += listOf("rb"); script += List(100) { "c" }
            }
            val engine1 = scope.realEngine(backend1, storage)
            val job = launch { engine1.run(def, requestId = runId).toList() }
            withTimeout(10_000) { while (engine1.checkpoint(runId.value)?.nextStepId != "c") delay(20) }
            job.cancel(); job.join()
            assertEquals("c", engine1.checkpoint(runId.value)!!.nextStepId) // b done, c pending

            // Resume on a fresh engine over the same storage, with a backend that only answers step c.
            val backend2 = FakeBackend().apply { script += listOf("resumed-c") }
            val engine2 = scope.realEngine(backend2, storage)
            val events = engine2.resume(def, runId).toList()

            assertEquals("resumed-c", events.filterIsInstance<Tune2AIEvent.TextDelta>().joinToString("") { it.text })
            assertEquals("do c", backend2.lastRequest!!.messages.single().content)
            assertTrue("only step c ran on resume", events.stepsSeen().all { it.startsWith("Step c") })
            assertTrue(events.last() is Tune2AIEvent.Completed)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun CoroutineScope.realEngine(backend: FakeBackend, storage: Storage): WorkflowEngine {
        val manager = ModelManager(listOf(backend), InMemoryStorage(), FakeResourceMonitor(), ResourceLimits(minFreeMemoryBytes = GB / 2), this, fileExists = { true })
        manager.register(fakeModel())
        return WorkflowEngine(RequestController(manager, Router(manager), dispatcher = Dispatchers.Default), ToolCallingEngine(emptyList()), storage)
    }
}
