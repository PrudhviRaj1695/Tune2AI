package com.tune2ai.sdk

import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.services.embedding.EmbeddingService
import com.tune2ai.sdk.services.rag.Chunker
import com.tune2ai.sdk.services.rag.RagContextSource
import com.tune2ai.sdk.services.rag.RagEngine
import com.tune2ai.sdk.services.prompt.PromptTemplate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RagTest {
    private val embedderModel = fakeModel("embedder", memoryGb = 0.1, contextLength = 512, tasks = setOf(Task.EMBEDDING))

    private suspend fun TestScope.engine(withEmbedder: Boolean = true, storage: InMemoryStorage = InMemoryStorage()): RagEngine {
        val manager = modelManager()
        if (withEmbedder) manager.register(embedderModel)
        return RagEngine(storage, EmbeddingService(manager, Router(manager)))
    }

    @Test
    fun chunkerMakesOverlappingWindowsWithinTheLimit() {
        val text = (1..600).joinToString(" ") { "word$it" }
        val chunks = Chunker(windowTokens = 50, overlapTokens = 10).chunk(text)
        assertTrue(chunks.size > 5)
        chunks.forEach { assertTrue("chunk too long: ${it.length}", it.length <= 200) }
        // Overlap: each chunk starts with words from the end of the previous one.
        val lastOfFirst = chunks[0].split(" ").last()
        assertTrue(chunks[1].split(" ").contains(lastOfFirst))
        // Nothing lost: every word appears somewhere.
        assertTrue((1..600).all { n -> chunks.any { "word$n" in it.split(" ") } })
        assertEquals(emptyList<String>(), Chunker().chunk("   \n\n  "))
    }

    @Test
    fun retrievalRanksTheRelevantSourceFirst() = runTest {
        val rag = engine()
        rag.add("pets", "pets.txt", "Our cat Milo eats salmon and sleeps on the sofa.")
        rag.add("tax", "tax.txt", "The quarterly tax filing deadline is the fifteenth.")
        rag.add("car", "car.txt", "The car needs new tyres before winter.")

        val hits = rag.retrieve("what does milo the cat eat? salmon?", limit = 2)
        assertEquals("pets", hits.first().sourceId)
        assertTrue(hits.first().byEmbedding)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score || !b.byEmbedding })
        assertEquals("pets.txt", hits.first().sourceName)
    }

    @Test
    fun updateReplacesAllChunksOfASource() = runTest {
        val rag = engine()
        rag.add("notes", "notes.md", "The wifi password is pelican.")
        rag.add("notes", "notes.md", "The office door code is 4821.")
        assertEquals(1, rag.sources().size)
        assertTrue(rag.retrieve("wifi password pelican").none { "pelican" in it.text })
        assertTrue(rag.retrieve("office door code").any { "4821" in it.text })
    }

    @Test
    fun deletedContentNeverComesBack() = runTest {
        val storage = InMemoryStorage()
        val rag = engine(storage = storage)
        rag.add("secret", "secret.txt", "The launch date is March ninth.")
        assertTrue(rag.delete("secret"))
        assertFalse(rag.delete("secret"))
        assertTrue(rag.retrieve("launch date March").isEmpty())
        assertTrue(rag.sources().isEmpty())
        // Also after a restart (fresh engine over the same storage).
        assertTrue(engine(storage = storage).retrieve("launch date March").isEmpty())
    }

    @Test
    fun indexSurvivesRestart() = runTest {
        val storage = InMemoryStorage()
        engine(storage = storage).add("pets", "pets.txt", "Our cat Milo eats salmon.")
        val again = engine(storage = storage)
        assertEquals(listOf("pets"), again.sources().map { it.id })
        assertEquals("pets", again.retrieve("cat salmon").first().sourceId)
    }

    @Test
    fun withoutAnEmbeddingModelSearchIsByKeyword() = runTest {
        val rag = engine(withEmbedder = false)
        assertNull(rag.embeddingModel())
        val source = rag.add("pets", "pets.txt", "Our cat Milo eats salmon.")
        assertNull(source.embeddingModel)
        rag.add("tax", "tax.txt", "Quarterly tax filing deadline.")
        val hits = rag.retrieve("When is the tax deadline?")
        assertEquals("tax", hits.single().sourceId)
        assertFalse(hits.single().byEmbedding)
        assertTrue("unrelated question retrieves nothing", rag.retrieve("weather tomorrow").isEmpty())
    }

    @Test
    fun reindexEmbedsSourcesAddedBeforeTheEmbedderArrived() = runTest {
        val manager = modelManager()
        val storage = InMemoryStorage()
        val rag = RagEngine(storage, EmbeddingService(manager, Router(manager)))
        rag.add("pets", "pets.txt", "Our cat Milo eats salmon.")
        assertNull(rag.sources().single().embeddingModel)

        manager.register(embedderModel)
        val progress = mutableListOf<Pair<Int, Int>>()
        assertEquals(1, rag.reindex { done, total -> progress += done to total })
        assertEquals("embedder", rag.sources().single().embeddingModel)
        assertEquals(listOf(1 to 1), progress)
        assertTrue(rag.retrieve("cat milo salmon").first().byEmbedding)
        assertEquals(0, rag.reindex())
    }

    @Test
    fun topKIsRespected() = runTest {
        val rag = engine()
        repeat(10) { rag.add("doc$it", "doc$it.txt", "apples apples apples number $it") }
        assertEquals(3, rag.retrieve("apples", limit = 3).size)
        assertEquals(10, rag.retrieve("apples", limit = 50).size)
    }

    @Test
    fun disabledSourceContributesNothing() = runTest {
        val rag = engine()
        rag.add("pets", "pets.txt", "Our cat Milo eats salmon.")
        var on = false
        val source = RagContextSource(rag) { on }
        assertTrue(source.fetch("cat salmon", fakeModel()).isEmpty())
        on = true
        assertEquals("pets.txt#1", source.fetch("cat salmon", fakeModel()).single().ref)
    }

    @Test
    fun retrievalFailureLeavesTheRequestWithoutKnowledge() = runTest {
        val broken = object : com.tune2ai.sdk.core.Storage by InMemoryStorage() {
            override suspend fun list(namespace: String): Map<String, String> = throw com.tune2ai.sdk.api.Tune2AIException.StorageFailure("disk gone")
        }
        val source = RagContextSource(RagEngine(broken, null))
        assertTrue(source.fetch("anything", fakeModel()).isEmpty())
    }

    @Test
    fun chatRequestsIncludeRetrievedKnowledge() = runBlocking {
        val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }
        val backend = FakeBackend()
        Tune2AI.Builder.forTests(FakePlatform()).addBackend(backend)
            .addPromptTemplate(PromptTemplate("assistant", 1, "Be concise."))
            .defaultPromptTemplate("assistant")
            .build().use { ai ->
                ai.models.register(fakeModel(path = modelFile.path))
                ai.knowledge.add("pets", "pets.txt", "Our cat Milo eats salmon.")

                val events = ai.chat.send("What does Milo eat?").toList()
                val context = events.filterIsInstance<Tune2AIEvent.Progress>().single { it.stage == Tune2AIEvent.Stage.CONTEXT_ASSEMBLED }
                assertTrue(context.sources.toString(), context.sources.contains("rag:pets.txt#1"))
                assertTrue("salmon" in backend.lastRequest!!.messages.first().content)

                ai.knowledge.enabled = false
                val off = ai.chat.send("What does Milo eat?").toList()
                val offContext = off.filterIsInstance<Tune2AIEvent.Progress>().single { it.stage == Tune2AIEvent.Stage.CONTEXT_ASSEMBLED }
                assertFalse(offContext.sources.any { it.startsWith("rag:") })
            }
    }
}
