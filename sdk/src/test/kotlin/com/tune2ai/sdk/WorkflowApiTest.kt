package com.tune2ai.sdk

import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.Tool
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.Completed
import com.tune2ai.sdk.api.Tune2AIEvent.Failed
import com.tune2ai.sdk.core.workflow.StepType
import com.tune2ai.sdk.core.workflow.Transition
import com.tune2ai.sdk.core.workflow.WorkflowDefinition
import com.tune2ai.sdk.core.workflow.WorkflowStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/** Workflow API end to end on the JVM: Builder → WorkflowApi → WorkflowEngine → controller → scripted FakeBackend. */
class WorkflowApiTest {
    private val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }
    private val upper = Tool("upper", "Uppercases text.", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""") { args ->
        val text = Regex(""""text"\s*:\s*"(.*?)"""").find(args)?.groupValues?.get(1).orEmpty()
        """{"upper":"${text.uppercase()}"}"""
    }

    private suspend fun ai(backend: FakeBackend) = Tune2AI.Builder.forTests(FakePlatform())
        .addBackend(backend)
        .addTool(upper)
        .build()
        .also { it.models.register(fakeModel(path = modelFile.path)) }

    private val linear = WorkflowDefinition(
        id = "greet",
        inputs = listOf("name"),
        steps = listOf(
            WorkflowStep("ask", StepType.MODEL, prompt = "Greet {name}", output = "greeting", transitions = listOf(Transition("shout"))),
            WorkflowStep("shout", StepType.TOOL, tool = "upper", arguments = """{"text":"{greeting}"}"""),
        ),
    )

    @Test
    fun validateReportsProblemsAndPasses() = runBlocking {
        ai(FakeBackend()).use { t ->
            assertTrue(t.workflow.validate(linear).isEmpty())
            val bad = WorkflowDefinition("bad", steps = listOf(WorkflowStep("a", StepType.TOOL, tool = "nope")))
            assertTrue(t.workflow.validate(bad).any { it.contains("unregistered tool 'nope'") })
        }
    }

    @Test
    fun startRunsAndStatusReportsCompletion() = runBlocking {
        ai(FakeBackend(tokens = listOf("Hi ", "Ada"))).use { t ->
            val events = t.workflow.start(linear, inputs = mapOf("name" to "Ada")).toList()
            assertEquals("""{"upper":"HI ADA"}""", events.filterIsInstance<Tune2AIEvent.TextDelta>().joinToString("") { it.text })
            val runId = events.first().requestId.value
            assertTrue(events.last() is Completed)

            val status = t.workflow.status(runId)!!
            assertTrue(status.completed)
            assertFalse("a finished run cannot be resumed", status.canResume)
            assertEquals(listOf(runId), t.workflow.list().map { it.runId })
        }
    }

    @Test
    fun resumeRefusesAChangedDefinitionThenDeleteRemovesTheRun() = runBlocking {
        ai(FakeBackend(tokens = listOf("Hi ", "Ada"))).use { t ->
            val runId = t.workflow.start(linear, inputs = mapOf("name" to "Ada")).toList().first().requestId

            val changed = t.workflow.resume(linear.copy(version = 9), runId).toList()
            assertEquals(ErrorCategory.INVALID_REQUEST, (changed.last() as Failed).error.category)

            assertTrue(t.workflow.delete(runId.value))
            assertNull(t.workflow.status(runId.value))
        }
    }

    @Test
    fun streamCanBeCancelledByRunId() = runBlocking {
        // A model step that streams slowly, so cancel lands mid-run.
        ai(FakeBackend(tokens = List(100) { "x" }, tokenDelayMillis = 50)).use { t ->
            val def = WorkflowDefinition("slow", steps = listOf(WorkflowStep("a", StepType.MODEL, prompt = "go")))
            val events = Collections.synchronizedList(mutableListOf<Tune2AIEvent>())
            val done = CompletableDeferred<Unit>()
            val runId = com.tune2ai.sdk.api.RequestId("slow-run")
            t.workflow.stream(def, requestId = runId) { e ->
                events += e
                if (e is Tune2AIEvent.Terminal) done.complete(Unit)
            }
            while (events.none { it is Tune2AIEvent.Progress }) Thread.sleep(10)
            assertTrue("cancel reports an active run", t.workflow.cancel(runId.value))
            withTimeout(2_000) { done.await() }
            assertTrue(events.last() is Tune2AIEvent.Cancelled)
            assertFalse("second cancel finds nothing", t.workflow.cancel(runId.value))
        }
    }
}
