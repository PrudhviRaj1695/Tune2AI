package com.tune2ai.sdk

import com.tune2ai.sdk.api.AgentLimits
import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Tool
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.ToolCall
import com.tune2ai.sdk.api.Tune2AIEvent.ToolResult
import com.tune2ai.sdk.api.Tune2AIException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch

/** Agent API end to end on the JVM: Builder → AgentApi → AgentEngine → controller → scripted FakeBackend. */
class AgentTest {
    private val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }
    private val toolStarted = CountDownLatch(1)
    private val tools = listOf(
        Tool("add", "Adds two integers.", """{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}""") { args ->
            val n = Regex("\\d+").findAll(args).map { it.value.toInt() }.sum()
            """{"sum":$n}"""
        },
        Tool("wait", "Takes a long time.", timeoutMillis = 30_000) { toolStarted.countDown(); Thread.sleep(30_000); "done" },
    )

    private suspend fun agent(backend: FakeBackend) = Tune2AI.Builder.forTests(FakePlatform())
        .addBackend(backend)
        .apply { tools.forEach { addTool(it) } }
        .build()
        .also { it.models.register(fakeModel(path = modelFile.path)) }

    private fun List<Tune2AIEvent>.text() = filterIsInstance<Tune2AIEvent.TextDelta>().joinToString("") { it.text }

    @Test
    fun callsAToolThenAnswers() = runBlocking {
        val backend = FakeBackend().apply {
            script += listOf("""{"tool": "add", """, """"arguments": {"a": 2, "b": 3}}""")
            script += listOf("The ", "answer ", "is 5.")
        }
        agent(backend).use { ai ->
            val events = ai.agent.run("What is 2 + 3?").toList()
            assertEquals(ToolCall(events[0].requestId, "call_1", "add", """{"a":2,"b":3}"""), events.filterIsInstance<ToolCall>().single())
            assertEquals("""{"sum":5}""", events.filterIsInstance<ToolResult>().single().resultJson)
            assertEquals("The answer is 5.", events.text())
            assertTrue(events.last() is Tune2AIEvent.Completed)
            assertTrue("tool call JSON is never shown as answer text", !events.text().contains("{"))
            // The second model step saw the tool result.
            assertTrue(backend.lastRequest!!.messages.last().content.contains("""Tool result for add: {"sum":5}"""))
            assertTrue(backend.lastRequest!!.messages.first().content.contains("- add: Adds two integers."))
        }
    }

    @Test
    fun badArgumentsBecomeAToolErrorTheModelCanRecoverFrom() = runBlocking {
        val backend = FakeBackend().apply {
            script += listOf("""{"tool": "add", "arguments": {"a": "two"}}""")
            script += listOf("I could not add those.")
        }
        agent(backend).use { ai ->
            val events = ai.agent.run("Add two and three").toList()
            val result = events.filterIsInstance<ToolResult>().single()
            assertTrue(result.isError)
            assertTrue(result.resultJson.contains("must be an integer"))
            assertTrue(events.last() is Tune2AIEvent.Completed)
        }
    }

    @Test
    fun malformedCallsGetACorrectionPrompt() = runBlocking {
        val backend = FakeBackend().apply {
            script += listOf("""{"tool": "add", "arguments": """)
            script += listOf("Five.")
        }
        agent(backend).use { ai ->
            val events = ai.agent.run("Add 2 and 3").toList()
            assertEquals("Five.", events.text())
            assertTrue(backend.lastRequest!!.messages.last().content.contains("could not be read"))
        }
    }

    @Test
    fun stepAndToolLimitsEndWithLimitReached() = runBlocking {
        val loop = FakeBackend(tokens = listOf("""{"tool": "add", "arguments": {"a": 1, "b": 1}}"""))
        agent(loop).use { ai ->
            val byTools = ai.agent.run("Loop forever", limits = AgentLimits(maxSteps = 10, maxToolCalls = 2)).toList()
            assertEquals(2, byTools.filterIsInstance<ToolCall>().size)
            assertEquals(ErrorCategory.LIMIT_REACHED, (byTools.last() as Tune2AIEvent.Failed).error.category)

            val bySteps = ai.agent.run("Loop forever", limits = AgentLimits(maxSteps = 3, maxToolCalls = 10)).toList()
            assertTrue((bySteps.last() as Tune2AIEvent.Failed).error.message!!.contains("all 3 steps"))
        }
    }

    @Test
    fun deadlineAppliesToTheWholeRun() = runBlocking {
        agent(FakeBackend(tokens = List(100) { "x" }, tokenDelayMillis = 50)).use { ai ->
            val events = ai.agent.run("Slow task", options = RequestOptions(deadlineMillis = 300)).toList()
            assertEquals(ErrorCategory.TIMEOUT, (events.last() as Tune2AIEvent.Failed).error.category)
        }
    }

    @Test
    fun cancellingDuringAToolCallEndsCancelledAndInterruptsTheTool() = runBlocking {
        val backend = FakeBackend(tokens = listOf("""{"tool": "wait", "arguments": {}}"""))
        agent(backend).use { ai ->
            val events = Collections.synchronizedList(mutableListOf<Tune2AIEvent>())
            val done = CompletableDeferred<Unit>()
            val handle = ai.agent.stream("Wait for it") { e ->
                events += e
                if (e is Tune2AIEvent.Terminal) done.complete(Unit)
            }
            toolStarted.await()
            val start = System.nanoTime()
            handle.cancel()
            withTimeout(2_000) { done.await() }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000)
            assertTrue(events.last() is Tune2AIEvent.Cancelled)
            assertTrue(events.filterIsInstance<ToolResult>().isEmpty())
        }
    }

    @Test
    fun unknownToolsAndInvalidToolsAreRejectedEarly() = runBlocking {
        agent(FakeBackend()).use { ai ->
            val events = ai.agent.run("x", tools = setOf("nope")).toList()
            assertEquals(ErrorCategory.INVALID_REQUEST, (events.last() as Tune2AIEvent.Failed).error.category)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Tune2AI.Builder.forTests(FakePlatform()).addBackend(FakeBackend()).addTool(Tool("Bad Name", "x") { "" }).build()
        }
        assertTrue(Tune2AIException.LimitReached("x").category == ErrorCategory.LIMIT_REACHED)
    }
}
