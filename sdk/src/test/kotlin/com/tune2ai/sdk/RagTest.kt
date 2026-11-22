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
    fun chunkerPacksWholeSentencesWithOverlap() {
        val text = (1..60).joinToString(" ") { "Sentence number $it is here." }
        val chunks = Chunker(windowTokens = 50, overlapTokens = 10).chunk(text)
        assertTrue(chunks.size > 5)
        chunks.forEach { c ->
            assertTrue("chunk too long: ${c.length}", c.length <= 200)
            assertTrue("cut mid-sentence: $c", c.startsWith("Sentence") && c.endsWith("."))
        }
        // Overlap: the last sentence of a chunk opens the next one.
        assertTrue(chunks[1].startsWith(chunks[0].substringAfterLast(". ").let { if (it.endsWith(".")) it else "$it." }.trim()))
        assertTrue((1..60).all { n -> chunks.any { "number $n is" in it } })
        assertEquals(emptyList<String>(), Chunker().chunk("   \n\n  "))
    }

    @Test
    fun chunkerSplitsVeryLongSentencesBetweenWords() {
        val text = (1..600).joinToString(" ") { "word$it" }
        val chunks = Chunker(windowTokens = 50, overlapTokens = 10).chunk(text)
        chunks.forEach { assertTrue(it.length <= 200) }
        assertTrue((1..600).all { n -> chunks.any { "word$n" in it.split(" ") } })
    }

    @Test
    fun chunksRememberTheirSectionAndHeadingsStartNewChunks() {
        val doc = "# Flights\n\nWe fly out Friday at 07:40 from Terminal 2. " + "Bring passports. ".repeat(20) +
            "\n\n# Hotel\n\nCheck-in is after 15:00.\n\n- Breakfast included\n- Late checkout until 12:00"
        val pieces = Chunker(windowTokens = 80, overlapTokens = 10).pieces(doc)
        val hotel = pieces.first { "Check-in" in it.text }
        assertEquals("Hotel", hotel.section)
        assertTrue("a heading starts its own chunk", hotel.text.startsWith("# Hotel"))
        assertTrue("list items stay on their own lines", "- Breakfast included\n- Late checkout" in hotel.text)
        assertEquals("Flights", pieces.first().section)
    }

    @Test
    fun retrievalRanksTheRelevantSourceFirst() = runTest {
        val rag = engine()
        rag.add("pets", "pets.txt", "Our cat Milo eats salmon and sleeps on the sofa.")
        rag.add("tax", "tax.txt", "The quarterly tax filing deadline is the fifteenth.")
        rag.add("car", "car.txt", "The car needs new tyres before winter.")

        val hits = rag.retrieve("milo the cat eats salmon", limit = 2)
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
    fun neighbouringChunksMergeIntoOnePassageWithoutRepeatingTheOverlap() = runTest {
        val rag = RagEngine(InMemoryStorage(), null, Chunker(windowTokens = 30, overlapTokens = 8))
        val text = "Gate code for the garage is 1984 and you press the bell twice. " +
            "Then the garage gate opens slowly; wait for the garage light. " +
            "Parking spots for visitors are on level 3 next to the garage lift."
        rag.add("garage", "garage.txt", text)
        val hits = rag.retrieve("garage gate code", limit = 3)
        assertEquals(1, hits.size)
        val passage = hits.single()
        assertTrue(passage.lastIndex > passage.index)
        assertEquals("garage.txt#${passage.index + 1}-${passage.lastIndex + 1}", passage.ref)
        assertEquals("each sentence once", 1, Regex("Then the garage gate opens").findAll(passage.text).count())
    }

    @Test
    fun oneDocumentCannotCrowdOutTheOthers() = runTest {
        val rag = RagEngine(InMemoryStorage(), null, Chunker(windowTokens = 20, overlapTokens = 0))
        rag.add("big", "big.txt", (1..12).joinToString("\n\n") { "Invoice $it was paid in March." })
        rag.add("small", "small.txt", "The March invoice from the landlord is still open.")
        val hits = rag.retrieve("march invoice", limit = 3)
        assertTrue(hits.any { it.sourceId == "small" })
        assertTrue(hits.count { it.sourceId == "big" } <= 2)
    }

    @Test
    fun keywordMatchesNeedEnoughOfTheQuestion() = runTest {
        val rag = engine(withEmbedder = false)
        rag.add("a", "a.txt", "The quarterly budget review meeting is on Monday.")
        assertTrue(rag.retrieve("budget review monday").isNotEmpty())
        assertTrue("one weak word out of three is not enough", rag.retrieve("monday football tickets").isEmpty())
    }

    @Test
    fun documentTitleAndSectionAreSearchable() = runTest {
        val rag = engine(withEmbedder = false)
        rag.add("w", "Wi-Fi.md", "# Guest network\n\nThe password is pelican42.")
        assertEquals("w", rag.retrieve("guest network wifi").single().sourceId)
    }

    @Test
    fun strictnessFiltersWeakSemanticMatches() = runTest {
        val rag = engine()
        rag.add("pets", "pets.txt", "Our cat Milo eats salmon and sleeps on the sofa all day long.")
        val loose = rag.retrieve("cat sofa", minSimilarity = 0.3f)
        val strict = rag.retrieve("cat sofa", minSimilarity = 0.9f)
        assertTrue(loose.any { it.byEmbedding })
        assertTrue(strict.none { it.byEmbedding })
    }

    @Test
    fun passageLooksUpATraceReference() = runTest {
        val rag = engine()
        rag.add("pets", "pets.txt", "Our cat Milo eats salmon.")
        assertEquals("Our cat Milo eats salmon.", rag.passage("pets.txt#1")!!.text)
        assertNull(rag.passage("pets.txt#9"))
        assertNull(rag.passage("nope.txt#1"))
    }

    @Test
    fun bgeQueriesGetTheirRetrievalInstruction() {
        val style = com.tune2ai.sdk.services.rag.QueryStyle.of("bge-small-en-v1.5-q8_0")
        assertTrue(style.query("tax").startsWith("Represent this sentence"))
        assertEquals("tax", com.tune2ai.sdk.services.rag.QueryStyle.of("other").query("tax"))
    }

    @Test
    fun ragPassagesAreNumberedWithCitationGuidance() = runTest {
        val rag = engine()
        rag.add("pets", "pets.txt", "# Food\n\nOur cat Milo eats salmon.")
        val source = RagContextSource(rag)
        val items = source.fetch("milo cat eats salmon", fakeModel())
        assertTrue(items.single().text.startsWith("[1] pets.txt › Food\n"))
        assertTrue("cite" in source.render(items))
    }

    @Test
    fun wordEndingsMatch() {
        val stem = RagEngine.Companion::stem
        assertEquals(stem("message"), stem("messages"))
        assertEquals(stem("message"), stem("messaged"))
        assertEquals(stem("call"), stem("called"))
        assertEquals(stem("call"), stem("calling"))
        assertEquals(stem("text"), stem("texted"))
        assertEquals(stem("reply"), stem("replies"))
        assertEquals("bus", stem("bus"))
        assertEquals("1984", stem("1984"))
    }

    @Test
    fun pluralQuestionsFindSingularText() = runTest {
        val rag = engine(withEmbedder = false)
        rag.add("sms", "SMS with HDFC Bank", "# SMS text message with HDFC Bank\n\nYour account was debited 2,500 on Friday.")
        assertEquals("sms", rag.retrieve("messages from the bank").firstOrNull()?.sourceId)
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
