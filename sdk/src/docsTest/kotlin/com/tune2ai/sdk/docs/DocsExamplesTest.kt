package com.tune2ai.sdk.docs

import com.tune2ai.sdk.FakeBackend
import com.tune2ai.sdk.FakePlatform
import com.tune2ai.sdk.Tune2AI
import com.tune2ai.sdk.api.ModelState
import com.tune2ai.sdk.api.Role
import com.tune2ai.sdk.api.Tool
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.fakeModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/** Runs every example quoted in docs/ so the documentation cannot describe an API that no longer works. */
class DocsExamplesTest {
    private val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }

    private val upper = Tool("upper", "Uppercases text.", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""") { args ->
        val text = Regex(""""text"\s*:\s*"(.*?)"""").find(args)?.groupValues?.get(1).orEmpty()
        """{"upper":"${text.uppercase()}"}"""
    }

    private suspend fun sdk(backend: FakeBackend, configure: (Tune2AI.Builder) -> Tune2AI.Builder = { it }, modelId: String = "tiny"): Tune2AI =
        configure(Tune2AI.Builder.forTests(FakePlatform()).addBackend(backend)).build()
            .also { it.models.register(fakeModel(modelId, path = modelFile.path)) }

    @Test
    fun gettingStartedSnippetsWork() = runBlocking {
        val reference: (android.content.Context, com.tune2ai.sdk.inference.InferenceBackend) -> Tune2AI = ::createSdk // compiles; needs a Context to run
        assertNotNull(reference)
        sdk(FakeBackend(), modelId = "other").use { ai ->
            registerModel(ai, modelFile.path, fileSizeBytes = 1_000_000)
            assertTrue(ai.models.list().any { it.descriptor.id == "qwen2.5-0.5b" })
        }
    }

    @Test
    fun chatSnippetsWork() = runBlocking {
        sdk(FakeBackend()).use { ai ->
            assertEquals("Hello, world", askOnce(ai))

            val history = converse(ai)
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), history.map { it.role })
            assertEquals("My name is Ada.", history.first().content)
        }
        sdk(FakeBackend(tokens = listOf("One", " sentence", ".", " More", " text"))).use { ai ->
            assertEquals("One sentence.", firstSentence(ai))
        }
    }

    @Test
    fun optionsAndTheCallbackFormWork() = runBlocking {
        val backend = FakeBackend()
        sdk(backend, modelId = "qwen2.5-0.5b").use { ai ->
            ai.chat.send("Ahoy", pirateOptions()).collect { }
            assertEquals("You are a pirate. Answer in pirate speak.", backend.lastRequest!!.messages.first().content)

            val events = Collections.synchronizedList(mutableListOf<Tune2AIEvent>())
            val done = CompletableDeferred<Unit>()
            val handle = startFromJavaStyleCode(ai) { event ->
                events += event
                if (event is Tune2AIEvent.Terminal) done.complete(Unit)
            }
            assertNotNull(handle)
            withTimeout(5_000) { done.await() }
            assertTrue(events.last() is Tune2AIEvent.Completed)
        }
    }

    @Test
    fun agentSnippetsWork() = runBlocking {
        val backend = FakeBackend().apply {
            script += listOf("""{"tool": "add", "arguments": {"a": 2, "b": 3}}""")
            script += listOf("The answer is 5.")
        }
        sdk(backend, configure = ::addTools).use { ai ->
            val log = mutableListOf<String>()
            assertEquals("The answer is 5.", askAgent(ai, log::add))
            assertEquals(listOf("""calling add with {"a":2,"b":3}""", """add returned {"sum":5}"""), log)
        }
    }

    @Test
    fun workflowSnippetsWork() = runBlocking {
        sdk(FakeBackend(tokens = listOf("Hi ", "Ada")), configure = { it.addTool(upper) }).use { ai ->
            assertEquals("""{"upper":"HI ADA"}""", runGreeting(ai))
            assertTrue("a finished run is not resumable", resumeUnfinished(ai).isEmpty())
            assertEquals(1, ai.workflow.list().size)
            deleteFinishedRuns(ai)
            assertTrue(ai.workflow.list().isEmpty())
        }
    }

    @Test
    fun modelSnippetsWork() = runBlocking {
        sdk(FakeBackend()).use { ai ->
            assertEquals(listOf("Tiny: Unloaded on [fake]"), describeModels(ai))
            preload(ai, "tiny")
            assertEquals(ModelState.Unloaded, ai.models.state("tiny").value)
            assertEquals(listOf("fake ready"), explainBackends(ai))
        }
    }

    @Test
    fun knowledgeSnippetsWork() = runBlocking {
        sdk(FakeBackend(formats = setOf("gguf", "fake"), embedDims = 32)).use { ai ->
            assertEquals(listOf("The user prefers the Kotlin language"), rememberAndRecall(ai))

            registerEmbeddingModel(ai, modelFile.path)
            assertEquals(32, embeddingSize(ai))
            assertEquals(listOf("handbook#0: The office wifi password rotates every friday"), indexAndRetrieve(ai))

            switchOffKnowledge(ai)
            assertEquals(false, ai.memory.enabled)
            assertEquals(false, ai.rag.enabled)
        }
    }

    @Test
    fun everyErrorTypeHasAdvice() {
        val errors = listOf(
            Tune2AIException.InvalidRequest("a"), Tune2AIException.NoCompatibleRoute("a"), Tune2AIException.ModelUnavailable("a"),
            Tune2AIException.ResourceLimit("a"), Tune2AIException.BackendFailure("a"), Tune2AIException.ToolFailure("a"),
            Tune2AIException.StorageFailure("a"), Tune2AIException.Timeout("a"), Tune2AIException.LimitReached("a"),
        )
        assertEquals(com.tune2ai.sdk.api.ErrorCategory.entries.toSet(), errors.map { it.category }.toSet())
        assertTrue(errors.all { advice(it).isNotBlank() })
    }
}
