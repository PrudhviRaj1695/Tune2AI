package com.tune2ai.sdk

import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.services.context.ContextManager
import com.tune2ai.sdk.services.embedding.EmbeddingService
import com.tune2ai.sdk.services.prompt.PromptManager
import com.tune2ai.sdk.services.rag.Chunker
import com.tune2ai.sdk.services.rag.RagContextSource
import com.tune2ai.sdk.services.rag.RagEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RagTest {
    private suspend fun failure(block: suspend () -> Unit): Tune2AIException {
        try {
            block()
        } catch (e: Tune2AIException) {
            return e
        }
        throw AssertionError("expected a Tune2AIException")
    }

    private suspend fun TestScope.engine(
        backend: FakeBackend = FakeBackend(embedDims = 64),
        storage: Storage = InMemoryStorage(),
        chunker: Chunker = Chunker(),
        modelId: String = "embedder",
        withModel: Boolean = true,
    ): RagEngine {
        val manager = modelManager(backend)
        if (withModel) manager.register(fakeModel(modelId, tasks = setOf(Task.EMBEDDING)))
        return RagEngine(storage, EmbeddingService(manager, Router(manager)), chunker)
    }

    // ---- Chunker ----

    @Test
    fun chunkerWindowsOverlapAndCoverEveryWord() {
        val words = List(100) { "w$it" }
        val chunks = Chunker(sizeTokens = 5, overlapTokens = 1).chunk(words.joinToString(" "))

        assertTrue(chunks.size > 1)
        assertTrue("windows stay within the size", chunks.all { it.length <= 20 })
        val seen = chunks.flatMap { it.split(" ") }.toSet()
        assertTrue("no word is lost or cut", words.all { it in seen })
        assertTrue("consecutive chunks share text", chunks.zipWithNext().any { (a, b) -> a.split(" ").intersect(b.split(" ").toSet()).isNotEmpty() })
    }

    @Test
    fun chunkerHandlesShortEmptyAndUnbrokenText() {
        val chunker = Chunker(sizeTokens = 5, overlapTokens = 1)
        assertEquals(listOf("short text"), chunker.chunk("  short text  "))
        assertTrue(chunker.chunk("   ").isEmpty())
        val unbroken = chunker.chunk("x".repeat(100)) // no spaces: must still terminate and cover everything
        assertTrue(unbroken.size > 1 && unbroken.all { it.length <= 20 })
        assertThrows(IllegalArgumentException::class.java) { Chunker(sizeTokens = 5, overlapTokens = 5) }
    }

    // ---- RagEngine ----

    @Test
    fun retrieveReturnsRankedChunksWithSourceIdsAndMetadata() = runTest {
        val rag = engine()
        rag.add("kotlin", "kotlin coroutines make async code simple", mapOf("url" to "https://kotlinlang.org"))
        rag.add("bread", "baking sourdough bread needs a starter")
        rag.add("alps", "the alps are mountains in europe")

        val hits = rag.retrieve("kotlin async coroutines", limit = 2)
        assertEquals(2, hits.size)
        assertEquals("kotlin", hits.first().sourceId)
        assertEquals("https://kotlinlang.org", hits.first().metadata["url"])
        assertEquals("kotlin#0", hits.first().ref)
        assertTrue("best first", hits[0].score >= hits[1].score)
        assertEquals(listOf("alps", "bread", "kotlin"), rag.sources())
    }

    @Test
    fun updateReplacesAllChunksSoOldContentNeverReturns() = runTest {
        val rag = engine(chunker = Chunker(sizeTokens = 5, overlapTokens = 1))
        val before = rag.add("doc", List(30) { "alpha$it" }.joinToString(" "))
        assertTrue("the first version spans several chunks", before > 2)

        val after = rag.update("doc", "delta echo")
        assertEquals(1, after)
        val all = rag.retrieve("alpha", limit = 100)
        assertEquals("surplus old chunks are gone", 1, all.size)
        assertTrue(all.none { it.text.contains("alpha") })
    }

    @Test
    fun deleteRemovesTheSourceFromRetrieval() = runTest {
        val backend = FakeBackend(embedDims = 64)
        val rag = engine(backend)
        rag.add("a", "alpha bravo")
        rag.add("b", "charlie delta")

        assertTrue(rag.delete("a"))
        assertFalse("already gone", rag.delete("a"))
        assertEquals(listOf("b"), rag.retrieve("alpha bravo", 10).map { it.sourceId })

        rag.delete("b")
        val calls = backend.embedCalls
        assertTrue(rag.retrieve("anything", 5).isEmpty())
        assertEquals("an empty index is not embedded against", calls, backend.embedCalls)
    }

    @Test
    fun invalidUsesFailWithTypedErrors() = runTest {
        val rag = engine()
        rag.add("a", "alpha")
        assertEquals(ErrorCategory.INVALID_REQUEST, failure { rag.add("a", "again") }.category)       // use update
        assertEquals(ErrorCategory.INVALID_REQUEST, failure { rag.update("zzz", "text") }.category)   // not indexed
        assertEquals(ErrorCategory.INVALID_REQUEST, failure { rag.add("b", "   ") }.category)         // nothing to index
        assertEquals(ErrorCategory.INVALID_REQUEST, failure { rag.retrieve(" ") }.category)
        assertEquals(ErrorCategory.INVALID_REQUEST, failure { rag.retrieve("x", 0) }.category)

        val noModel = engine(withModel = false)
        assertEquals(ErrorCategory.NO_COMPATIBLE_ROUTE, failure { noModel.add("a", "alpha") }.category)
    }

    @Test
    fun anIndexBuiltByAnotherModelIsRefusedButPersistsForTheSameOne() = runTest {
        val storage = InMemoryStorage()
        engine(FakeBackend(embedDims = 16), storage, modelId = "model-a").add("kb", "alpha bravo charlie")

        val sameModel = engine(FakeBackend(embedDims = 16), storage, modelId = "model-a") // a fresh engine over the same storage
        assertEquals("kb", sameModel.retrieve("alpha").single().sourceId)

        val otherModel = engine(FakeBackend(embedDims = 32), storage, modelId = "model-b")
        val error = failure { otherModel.retrieve("alpha") }
        assertEquals(ErrorCategory.INVALID_REQUEST, error.category)
        assertTrue(error.message!!.contains("built with embedding model 'model-a'"))
    }

    // ---- Context Manager integration ----

    @Test
    fun retrievedChunksReachTheContextOnlyThroughTheContextManager() = runTest {
        val backend = FakeBackend(embedDims = 64)
        val rag = engine(backend)
        val context = ContextManager(PromptManager(), sources = listOf(RagContextSource(rag)))
        val ask = listOf(ChatMessage.user("how do kotlin coroutines work?"))
        val options = RequestOptions(maxTokens = 64)

        val empty = context.assemble(ask, fakeModel(), options)
        assertTrue("no content yet, no embedding needed", empty.included.none { it.startsWith("rag:") } && backend.embedCalls == 0)

        rag.add("kb", "kotlin coroutines suspend without blocking threads")
        val on = context.assemble(ask, fakeModel(), options)
        assertTrue(on.included.contains("rag:kb#0"))
        assertTrue(on.messages.first().content.contains("kotlin coroutines suspend without blocking threads"))

        rag.enabled = false
        assertTrue(context.assemble(ask, fakeModel(), options).included.none { it.startsWith("rag:") })
    }

    @Test
    fun aChatRequestSeesIndexedKnowledgeThroughTheFacade() = runBlocking<Unit> {
        val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }
        val backend = FakeBackend(embedDims = 64)
        Tune2AI.Builder.forTests(FakePlatform()).addBackend(backend).build().use { ai ->
            ai.models.register(fakeModel("tiny", path = modelFile.path))
            ai.models.register(fakeModel("embedder", path = modelFile.path, tasks = setOf(Task.EMBEDDING)))

            ai.rag.add("handbook", "The office wifi password rotates every monday morning")
            ai.chat.send("When does the wifi password rotate?").collect { }

            val system = backend.lastRequest!!.messages.first { it.role == com.tune2ai.sdk.api.Role.SYSTEM }.content
            assertTrue(system.contains("wifi password rotates every monday morning"))
        }
    }
}
