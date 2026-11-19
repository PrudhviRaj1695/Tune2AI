package com.tune2ai.sdk

import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.api.Tool
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.Completed
import com.tune2ai.sdk.api.Tune2AIEvent.Failed
import com.tune2ai.sdk.api.Tune2AIEvent.Progress
import com.tune2ai.sdk.api.Tune2AIEvent.Stage
import com.tune2ai.sdk.api.Tune2AIEvent.ToolCall
import com.tune2ai.sdk.api.Tune2AIEvent.ToolResult
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.core.RequestController
import com.tune2ai.sdk.core.Router
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowTest {
    private val upper = Tool("upper", "Uppercases text.", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""") { args ->
        val text = Regex(""""text"\s*:\s*"(.*?)"""").find(args)?.groupValues?.get(1).orEmpty()
        """{"upper":"${text.uppercase()}"}"""
    }

    /** Virtual-time engine for model-only workflows. Tool steps use a real IO dispatcher, so route those through [realEngine]. */
    private suspend fun TestScope.engine(backend: FakeBackend): Pair<WorkflowEngine, FakeBackend> {
        val manager = modelManager(backend)
        manager.register(fakeModel())
        val controller = RequestController(manager, Router(manager), dispatcher = StandardTestDispatcher(testScheduler))
        return WorkflowEngine(controller, ToolCallingEngine(emptyList())) to backend
    }

    /** Real-dispatcher engine, needed whenever a tool step runs (the tool engine dispatches to [Dispatchers.IO]). */
    private suspend fun CoroutineScope.realEngine(backend: FakeBackend, tools: List<Tool>): WorkflowEngine {
        val manager = ModelManager(listOf(backend), InMemoryStorage(), FakeResourceMonitor(), ResourceLimits(minFreeMemoryBytes = GB / 2), this, fileExists = { true })
        manager.register(fakeModel())
        return WorkflowEngine(RequestController(manager, Router(manager), dispatcher = Dispatchers.Default), ToolCallingEngine(tools))
    }

    private fun List<Tune2AIEvent>.text() = filterIsInstance<Tune2AIEvent.TextDelta>().joinToString("") { it.text }
    private fun List<Tune2AIEvent>.error() = (last() as Failed).error

    @Test
    fun linearRunPassesVariablesModelToToolToResult() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val backend = FakeBackend(tokens = listOf("Hi ", "Ada"))
            val engine = scope.realEngine(backend, tools = listOf(upper))
            val def = WorkflowDefinition(
                id = "greet",
                inputs = listOf("name"),
                steps = listOf(
                    WorkflowStep("ask", StepType.MODEL, prompt = "Greet {name}", output = "greeting", transitions = listOf(Transition("shout"))),
                    WorkflowStep("shout", StepType.TOOL, tool = "upper", arguments = """{"text":"{greeting}"}"""),
                ),
            )
            val events = engine.run(def, inputs = mapOf("name" to "Ada")).toList()

            assertEquals("Greet Ada", backend.lastRequest!!.messages.single().content)
            assertEquals("""{"text":"Hi Ada"}""", events.filterIsInstance<ToolCall>().single().argumentsJson)
            assertEquals("""{"upper":"HI ADA"}""", events.filterIsInstance<ToolResult>().single().resultJson)
            assertEquals("""{"upper":"HI ADA"}""", events.text()) // terminal step output is the run result
            assertTrue(events.last() is Completed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun branchFollowsTheMatchingTransition() = runTest {
        val backend = FakeBackend().apply {
            script += listOf("urgent")                 // classify step decides
            script += listOf("Handled ", "urgently.")  // the urgent branch answers
        }
        val (engine, _) = engine(backend)
        val def = WorkflowDefinition(
            id = "triage",
            inputs = listOf("topic"),
            steps = listOf(
                WorkflowStep(
                    "classify", StepType.MODEL, prompt = "Classify: {topic}", output = "decision",
                    transitions = listOf(Transition("urgent", whenVar = "decision", equals = "urgent"), Transition("normal")),
                ),
                WorkflowStep("urgent", StepType.MODEL, prompt = "URGENT about {topic}"),
                WorkflowStep("normal", StepType.MODEL, prompt = "Normal about {topic}"),
            ),
        )
        val events = engine.run(def, inputs = mapOf("topic" to "fire")).toList()

        assertEquals("Handled urgently.", events.text())
        assertEquals("URGENT about fire", backend.lastRequest!!.messages.single().content) // the normal branch never ran
        assertTrue(events.last() is Completed)
    }

    @Test
    fun invalidDefinitionsFailBeforeAnyStepRuns() = runTest {
        val (engine, backend) = engine(FakeBackend())
        suspend fun reasonOf(def: WorkflowDefinition, inputs: Map<String, String> = emptyMap()): String {
            val events = engine.run(def, inputs).toList()
            assertEquals(ErrorCategory.INVALID_REQUEST, events.error().category)
            return events.error().message!!
        }
        val model = { id: String, t: List<Transition> -> WorkflowStep(id, StepType.MODEL, prompt = "go", transitions = t) }

        assertTrue(reasonOf(WorkflowDefinition("x", steps = listOf(model("a", listOf(Transition("ghost")))))).contains("unknown step 'ghost'"))
        assertTrue(reasonOf(WorkflowDefinition("x", steps = listOf(model("a", emptyList()), model("b", emptyList())))).contains("'b' is unreachable"))
        assertTrue(reasonOf(WorkflowDefinition("x", steps = listOf(model("a", listOf(Transition("b"))), model("b", listOf(Transition("a")))))).contains("cycle"))
        assertTrue(reasonOf(WorkflowDefinition("x", steps = listOf(WorkflowStep("a", StepType.TOOL, tool = "nope")))).contains("unregistered tool 'nope'"))
        assertTrue(reasonOf(WorkflowDefinition("x", steps = listOf(WorkflowStep("a", StepType.MODEL, prompt = "Hi {who}")))).contains("unknown variable '{who}'"))
        assertTrue(reasonOf(WorkflowDefinition("x", inputs = listOf("name"), steps = listOf(model("a", emptyList())))).contains("Missing workflow inputs: [name]"))

        assertEquals("nothing executed", 0, backend.loads)
    }

    @Test
    fun cancellingMidRunStopsTheWorkAndEmitsNoTerminalEvent() = runTest {
        val (engine, _) = engine(FakeBackend(tokens = List(100) { "x" }, tokenDelayMillis = 100))
        val def = WorkflowDefinition("slow", steps = listOf(WorkflowStep("a", StepType.MODEL, prompt = "go")))
        val events = mutableListOf<Tune2AIEvent>()
        val job = launch { engine.run(def, options = RequestOptions(deadlineMillis = 60_000)).toList(events) }
        advanceTimeBy(250)
        job.cancel()
        job.join()
        assertTrue("the step was running", events.any { it is Progress && it.stage == Stage.GENERATING })
        assertTrue("collector cancellation emits no terminal event", events.none { it is Tune2AIEvent.Terminal })
    }

    @Test
    fun deadlineExceededFailsWithTimeout() = runTest {
        val (engine, _) = engine(FakeBackend(tokens = List(100) { "x" }, tokenDelayMillis = 100))
        val def = WorkflowDefinition("slow", steps = listOf(WorkflowStep("a", StepType.MODEL, prompt = "go")))
        val events = engine.run(def, options = RequestOptions(deadlineMillis = 250)).toList()
        assertEquals(ErrorCategory.TIMEOUT, events.error().category)
    }

    @Test
    fun definitionRoundTripsThroughJson() {
        val def = WorkflowDefinition(
            "greet", version = 2, inputs = listOf("name"),
            steps = listOf(
                WorkflowStep("ask", StepType.MODEL, prompt = "Greet {name}", output = "greeting", transitions = listOf(Transition("shout"))),
                WorkflowStep("shout", StepType.TOOL, tool = "upper", arguments = """{"text":"{greeting}"}"""),
            ),
        )
        val json = Json.encodeToString(WorkflowDefinition.serializer(), def)
        assertEquals(def, Json.decodeFromString(WorkflowDefinition.serializer(), json))
    }
}
