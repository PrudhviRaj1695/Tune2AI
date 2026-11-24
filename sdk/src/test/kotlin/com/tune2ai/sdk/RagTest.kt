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
    fun eachEmbeddingModelGetsItsOwnPromptFormat() {
        val gemma = com.tune2ai.sdk.services.rag.QueryStyle.of("embeddinggemma-300m-q8_0")
        assertEquals("task: search result | query: tax", gemma.query("tax"))
        assertEquals("title: notes.md › Tax | text: Due Friday", gemma.document("notes.md › Tax", "Due Friday"))
        assertEquals("search_query: tax", com.tune2ai.sdk.services.rag.QueryStyle.of("nomic-embed-text-v1.5.q8_0").query("tax"))
    }

    @Test
    fun thePreferredEmbeddingModelIsUsedAndReindexSwitchesToIt() = runTest {
        val manager = modelManager()
        manager.register(fakeModel("small-embedder", memoryGb = 0.05, contextLength = 512, tasks = setOf(Task.EMBEDDING)))
        manager.register(fakeModel("big-embedder", memoryGb = 0.3, contextLength = 2048, tasks = setOf(Task.EMBEDDING)))
        val rag = RagEngine(InMemoryStorage(), EmbeddingService(manager, Router(manager)))
        rag.preferredEmbeddingModel = "small-embedder"
        assertEquals("small-embedder", rag.add("pets", "pets.txt", "Our cat Milo eats salmon.").embeddingModel)
        rag.preferredEmbeddingModel = "big-embedder"
        assertEquals(1, rag.reindex())
        assertEquals("big-embedder", rag.sources().single().embeddingModel)
        rag.preferredEmbeddingModel = "not-installed"
        assertTrue("falls back to an available model", rag.embeddingModel() != null)
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
    fun chunkingStrategiesShapeChunks() {
        val doc = "# Intro\n\nShort intro.\n\n# Details\n\n" + (1..40).joinToString(" ") { "Fact number $it is here." }
        val smart = com.tune2ai.sdk.services.rag.Chunker(com.tune2ai.sdk.services.rag.ChunkingStrategy.SMART).pieces(doc)
        val precise = com.tune2ai.sdk.services.rag.Chunker(com.tune2ai.sdk.services.rag.ChunkingStrategy.PRECISE).pieces(doc)
        val broad = com.tune2ai.sdk.services.rag.Chunker(com.tune2ai.sdk.services.rag.ChunkingStrategy.BROAD).pieces(doc)
        val sections = com.tune2ai.sdk.services.rag.Chunker(com.tune2ai.sdk.services.rag.ChunkingStrategy.SECTIONS).pieces(doc)
        val fixed = com.tune2ai.sdk.services.rag.Chunker(com.tune2ai.sdk.services.rag.ChunkingStrategy.FIXED).pieces(doc)
        assertTrue(precise.size > smart.size && smart.size > broad.size)
        assertEquals("sections splits at every heading", "Intro", sections.first().section)
        assertTrue(sections.first().text.contains("Short intro") && !sections.first().text.contains("Fact number"))
        assertTrue(fixed.all { it.text.length <= 800 })
        assertEquals("Details", fixed.last().section)
        assertEquals(com.tune2ai.sdk.services.rag.ChunkingStrategy.AUTO, com.tune2ai.sdk.services.rag.ChunkingStrategy.of("nope"))
    }

    @Test
    fun adaptiveKeepsSmallDocumentsWhole() {
        val note = "# Parking\n\nLevel 3, spot 42. The gate code changes monthly; ask reception."
        val pieces = com.tune2ai.sdk.services.rag.Chunker().pieces(note)
        assertEquals(1, pieces.size)
        assertEquals(note, pieces.single().text)
        assertEquals("Parking", pieces.single().section)
    }

    @Test
    fun adaptiveSplitsLargeDocumentsIntoSectionParentsAndSmallChildren() {
        val section = { name: String -> "# $name\n\n" + (1..30).joinToString(" ") { "$name fact $it is written here." } }
        val doc = listOf("Flights", "Hotel", "Budget").joinToString("\n\n") { section(it) }
        val pieces = com.tune2ai.sdk.services.rag.Chunker().pieces(doc)
        assertTrue(pieces.all { it.parent != null && it.text.length <= 100 * 4 + 120 })
        val byParent = pieces.groupBy { it.parent }
        assertTrue("several children per parent", byParent.values.any { it.size > 1 })
        assertTrue("parents never span two sections", byParent.values.all { kids -> kids.map { it.section }.distinct().size == 1 })
        assertEquals(listOf("Flights", "Hotel", "Budget"), pieces.map { it.section }.distinct())
    }

    @Test
    fun aChildMatchReturnsItsWholeParentOnce() = runTest {
        val rag = engine(withEmbedder = false)
        // Large enough (~700 tokens) to be split; each section fits one ~400-token parent.
        val doc = "# Hotel\n\n" + (1..40).joinToString(" ") { "Hotel detail $it is noted." } + " The safe code is behind the mirror." +
            "\n\n# Flights\n\n" + (1..40).joinToString(" ") { "Flight detail $it is noted." }
        rag.add("trip", "trip.md", doc)
        val hits = rag.retrieve("where is the hotel safe behind the mirror", limit = 3)
        val passage = hits.first()
        assertEquals("Hotel", passage.section)
        assertTrue("the whole parent section", passage.text.contains("Hotel detail 1 is noted") && passage.text.contains("behind the mirror"))
        assertTrue("not the other section", !passage.text.contains("Flight detail"))
        assertEquals("one passage per parent", hits.size, hits.map { it.index }.distinct().size)
    }

    @Test
    fun eachEmbeddingModelHasItsOwnRelevanceFloor() {
        val q = com.tune2ai.sdk.services.rag.QueryStyle
        assertEquals(0.55f, q.of("bge-small-en-v1.5-q8_0").minCosine)
        assertEquals(0.45f, q.of("nomic-embed-text-v1.5.q8_0").minCosine)
        assertEquals(0.40f, q.of("embeddinggemma-300m-q8_0").minCosine)
    }

    @Test
    fun smallTalkRetrievesNothing() = runTest {
        val rag = engine(withEmbedder = false)
        rag.add("sms", "sms", "Hi, your OTP is 4411. Thanks, good morning!")
        assertTrue(rag.retrieve("Hi").isEmpty())
        assertTrue(rag.retrieve("thanks, ok").isEmpty())
        assertTrue(rag.retrieve("what is my OTP").isNotEmpty())
    }

    @Test
    fun rechunkResplitsExistingDocumentsFromTheirOriginalText() = runTest {
        val rag = engine(withEmbedder = false)
        val text = (1..40).joinToString(" ") { "Sentence $it about apples." }
        rag.chunking = com.tune2ai.sdk.services.rag.ChunkingStrategy.BROAD
        val before = rag.add("doc", "doc.txt", text, mapOf("kind" to "file")).chunks
        rag.chunking = com.tune2ai.sdk.services.rag.ChunkingStrategy.PRECISE
        val progress = mutableListOf<Pair<Int, Int>>()
        assertEquals(1, rag.rechunk { d, t -> progress += d to t })
        val after = rag.sources().single()
        assertTrue(after.chunks > before)
        assertEquals("file", after.metadata["kind"])
        assertEquals("precise", after.metadata["chunking"])
        assertEquals(listOf(1 to 1), progress)
        assertEquals(0, rag.rechunk())
        assertTrue(rag.retrieve("sentence 33 apples").isNotEmpty())
    }

    @Test
    fun neighbourExpansionAddsSurroundingPassages() = runTest {
        val rag = RagEngine(InMemoryStorage(), null, Chunker(windowTokens = 15, overlapTokens = 0))
        rag.add("story", "story.txt", "The vault is on level two. Its door is painted blue. The key hangs behind the reception desk. Visitors sign in first.")
        val plain = rag.retrieve("where does the key hang", limit = 1)
        val wide = rag.retrieve("where does the key hang", limit = 1, neighbors = true)
        assertTrue(wide.single().text.length > plain.single().text.length)
        assertTrue(wide.single().lastIndex > wide.single().index)
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
