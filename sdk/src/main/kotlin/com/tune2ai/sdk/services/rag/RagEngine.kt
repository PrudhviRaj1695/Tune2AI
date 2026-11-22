package com.tune2ai.sdk.services.rag

import com.tune2ai.sdk.api.KnowledgeSource
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.RetrievedChunk
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.services.context.ContextItem
import com.tune2ai.sdk.services.context.ContextSource
import com.tune2ai.sdk.services.embedding.EmbeddingService
import com.tune2ai.sdk.services.embedding.cosine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Add, update, delete, and retrieve app knowledge (FR-SVC-5, T2AI-024.3).
 *
 * Indexing: text is split into section-aware chunks ([Chunker]); each chunk is embedded together
 * with its document title and section heading, so "the deadline" in a section called "Tax" is
 * found by a question about tax deadlines. Without an embedding model, chunks are stored without
 * vectors and [reindex] embeds them later.
 *
 * Retrieval is hybrid:
 * 1. Semantic: cosine similarity between the query (with the model's query instruction, see
 *    [QueryStyle]) and each chunk, keeping those above the similarity floor.
 * 2. Keyword: BM25 over chunk text, title, and section, keeping chunks that contain enough of the
 *    query's words (exact names and numbers that embeddings miss).
 * 3. The two rankings are merged with reciprocal rank fusion, at most [MAX_PER_SOURCE] passages
 *    come from one document unless it is the only match, and neighbouring chunks of the same
 *    document are merged into one passage so the model sees the whole thought once.
 *
 * Adding an existing id replaces all of its chunks; deleted content never appears in retrieval.
 */
class RagEngine(
    private val storage: Storage,
    private val embeddings: EmbeddingService?,
    chunker: Chunker = Chunker(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val index = VectorIndex(storage)

    /** How new documents are split. [rechunk] applies a change to existing ones. */
    @Volatile var chunking: ChunkingStrategy = chunker.strategy
    private val json = Json { ignoreUnknownKeys = true }

    /** Embedding model new content is indexed with, or null when retrieval uses keywords. */
    fun embeddingModel(): String? = embeddings?.modelId()

    suspend fun add(id: String, name: String, text: String, metadata: Map<String, String> = emptyMap()): KnowledgeSource {
        if (id.isBlank()) throw Tune2AIException.InvalidRequest("A knowledge source needs an id")
        val strategy = chunking
        val pieces = Chunker(strategy).pieces(text)
        val chunks = pieces.mapIndexed { i, p -> IndexedChunk(id, i, p.text, null, null, p.section) }
        val (vectors, modelId) = embed(name, chunks)
        index.replace(id, chunks.mapIndexed { i, c -> c.withVector(vectors?.get(i), modelId) })
        // The original text is kept so a different chunking strategy can re-split it later.
        storage.put(TEXTS, VectorIndex.keyOf(id), text.take(MAX_STORED_TEXT))
        val source = KnowledgeSource(id, name, metadata + (CHUNKING_KEY to strategy.id), pieces.size, text.length, clock(), modelId)
        putSource(source)
        return source
    }

    /**
     * Re-splits every document whose chunks were made with another strategy than [chunking] (and
     * re-embeds them). Documents indexed before original text was kept are rebuilt from their chunks.
     * Returns how many were re-split. [onProgress] gets (done, total).
     */
    suspend fun rechunk(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val target = chunking.id
        val stale = sources().filter { (it.metadata[CHUNKING_KEY] ?: ChunkingStrategy.SMART.id) != target }
        stale.forEachIndexed { n, source ->
            val text = storage.get(TEXTS, VectorIndex.keyOf(source.id))
                ?: index.chunksOf(source.id).takeIf { it.isNotEmpty() }?.let { chunks -> mergeTexts(chunks.map { it.text }) }
            if (text != null) {
                val kept = add(source.id, source.name, text, source.metadata - CHUNKING_KEY)
                // Keep the original "added" time so the Sources list order does not change.
                putSource(kept.copy(addedAtMillis = source.addedAtMillis))
            }
            onProgress(n + 1, stale.size)
        }
        return stale.size
    }

    suspend fun delete(id: String): Boolean {
        val removed = index.remove(id)
        storage.delete(TEXTS, VectorIndex.keyOf(id))
        val deleted = storage.delete(SOURCES, VectorIndex.keyOf(id))
        sourcesCache = null
        return deleted || removed
    }

    /** Cached after the first read; every write to [SOURCES] goes through this class and clears it. */
    @Volatile private var sourcesCache: List<KnowledgeSource>? = null

    suspend fun sources(): List<KnowledgeSource> = sourcesCache ?: storage.list(SOURCES).values
        .mapNotNull { runCatching { json.decodeFromString(KnowledgeSource.serializer(), it) }.getOrNull() }
        .sortedByDescending { it.addedAtMillis }
        .also { sourcesCache = it }

    private suspend fun putSource(source: KnowledgeSource) {
        storage.put(SOURCES, VectorIndex.keyOf(source.id), json.encodeToString(KnowledgeSource.serializer(), source))
        sourcesCache = null
    }

    suspend fun clear() {
        index.clear()
        storage.clear(SOURCES)
        storage.clear(TEXTS)
        sourcesCache = null
    }

    /**
     * Re-embeds every source whose chunks lack vectors from the current embedding model.
     * Returns how many sources were updated. [onProgress] gets (done, total).
     */
    suspend fun reindex(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val model = embeddingModel() ?: return 0
        val stale = sources().filter { it.embeddingModel != model }
        stale.forEachIndexed { n, source ->
            val chunks = index.chunksOf(source.id)
            val (vectors, modelId) = embed(source.name, chunks)
            index.replace(source.id, chunks.mapIndexed { i, c -> c.withVector(vectors?.get(i), modelId) })
            putSource(source.copy(embeddingModel = modelId))
            onProgress(n + 1, stale.size)
        }
        return stale.size
    }

    /** The passage behind a trace reference such as "notes.md#2" or "notes.md#2-3". */
    suspend fun passage(ref: String): RetrievedChunk? {
        val match = REF.matchEntire(ref) ?: return null
        val (name, first, last) = match.destructured
        val source = sources().firstOrNull { it.name == name } ?: return null
        val chunks = index.chunksOf(source.id)
        val from = first.toInt() - 1
        val to = (last.ifEmpty { first }).toInt() - 1
        val picked = chunks.filter { it.index in from..to }.ifEmpty { return null }
        return RetrievedChunk(source.id, source.name, from, mergeTexts(picked.map { it.text }), 0f, false, source.metadata, to, picked.first().section)
    }

    /**
     * Up to [limit] passages relevant to [query], best first; fewer (or none) when nothing is
     * relevant enough. [minSimilarity] is the cosine floor for semantic matches: raise it for
     * stricter retrieval, lower it to retrieve more loosely related passages.
     */
    suspend fun retrieve(query: String, limit: Int = 3, minSimilarity: Float = MIN_COSINE, neighbors: Boolean = false): List<RetrievedChunk> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val chunks = index.all()
        if (chunks.isEmpty()) return emptyList()
        val sources = sources().associateBy { it.id }
        fun key(c: IndexedChunk) = "${c.sourceId}\u0000${c.index}"
        val byKey = chunks.associateBy(::key)

        // 1. Semantic ranking.
        val model = embeddingModel()
        val embedded = if (model == null) emptyList() else chunks.filter { it.modelId == model && it.vector != null }
        val cosines = HashMap<String, Float>()
        var semanticRan = false
        if (embedded.isNotEmpty()) {
            val queryVector = runCatching { embeddings!!.embed(listOf(QueryStyle.of(model!!).query(query)), model).vectors.single() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (queryVector != null && queryVector.size == embedded.first().vector!!.size) {
                semanticRan = true
                embedded.forEach { cosines[key(it)] = cosine(queryVector, it.vector!!) }
            }
        }
        val semantic = cosines.entries.filter { it.value >= minSimilarity }.sortedByDescending { it.value }.take(CANDIDATES).map { it.key }

        // 2. Keyword ranking. Keyword-only hits must cover enough of the question to count.
        val keywordScores = keywords.rank(query, index.bySource()) { c -> listOfNotNull(sources[c.sourceId]?.name, c.section).joinToString(" ") }
        val keywordByChunk = keywordScores.associate { (c, s) -> c to s }
        val keyword = keywordScores.filter { (_, s) -> s.coverage >= (if (semanticRan) 0.5f else 0.34f) }
            .take(CANDIDATES).map { key(it.first) }
        val topBm25 = keywordScores.firstOrNull()?.second?.score ?: 1f

        // 3. Reciprocal rank fusion.
        val fused = HashMap<String, Float>()
        semantic.forEachIndexed { rank, k -> fused[k] = (fused[k] ?: 0f) + 1f / (RRF_K + rank + 1) }
        keyword.forEachIndexed { rank, k -> fused[k] = (fused[k] ?: 0f) + 1f / (RRF_K + rank + 1) }
        val ranked = fused.entries.sortedByDescending { it.value }.map { byKey.getValue(it.key) }

        // Diversity: at most MAX_PER_SOURCE chunks per document, unless only one document matched.
        val manySources = ranked.map { it.sourceId }.distinct().size > 1
        val perSource = HashMap<String, Int>()
        val picked = ranked.filter { c ->
            val n = perSource.getOrDefault(c.sourceId, 0)
            (!manySources || n < MAX_PER_SOURCE).also { if (it) perSource[c.sourceId] = n + 1 }
        }.take(limit).let { top ->
            if (!neighbors) top
            // Neighbour expansion: add the chunk before and after each hit, so the model sees the
            // sentences around a match (a table's header, the start of a list, the end of a story).
            else top + top.flatMap { c -> listOf(c.index - 1, c.index + 1).mapNotNull { byKey["${c.sourceId}\u0000$it"] } }
                .filter { n -> top.none { it === n } }.distinct()
        }

        // Merge neighbouring chunks of one document into a single passage, keeping rank order.
        val scoreOf = { c: IndexedChunk ->
            cosines[key(c)]?.takeIf { key(c) in semantic } ?: (keywordByChunk[c]?.score?.div(topBm25) ?: 0f)
        }
        val groups = mutableListOf<MutableList<IndexedChunk>>()
        for (c in picked) {
            val group = groups.firstOrNull { g -> g.first().sourceId == c.sourceId && g.any { kotlin.math.abs(it.index - c.index) == 1 } }
            if (group != null) group += c else groups += mutableListOf(c)
        }
        return groups.map { g ->
            val ordered = g.sortedBy { it.index }
            val first = ordered.first()
            val source = sources[first.sourceId]
            RetrievedChunk(
                sourceId = first.sourceId,
                sourceName = source?.name ?: first.sourceId,
                index = first.index,
                text = mergeTexts(ordered.map { it.text }),
                score = g.maxOf(scoreOf),
                byEmbedding = g.any { key(it) in semantic },
                metadata = source?.metadata.orEmpty(),
                lastIndex = ordered.last().index,
                section = first.section,
            )
        }
    }

    private suspend fun embed(sourceName: String, chunks: List<IndexedChunk>): Pair<List<FloatArray>?, String?> {
        val service = embeddings ?: return null to null
        val model = service.modelId() ?: return null to null
        if (chunks.isEmpty()) return null to model
        val style = QueryStyle.of(model)
        val inputs = chunks.map { style.document(listOfNotNull(sourceName, it.section).joinToString(" › "), it.text) }
        return try {
            inputs.chunked(EMBED_BATCH).flatMap { service.embed(it, model).vectors } to model
        } catch (e: CancellationException) {
            throw e
        } catch (e: Tune2AIException) {
            null to null // stored without vectors; keyword search still finds it, reindex() can embed later
        }
    }

    private val keywords = KeywordIndex()

    private fun IndexedChunk.withVector(vector: FloatArray?, modelId: String?) = IndexedChunk(sourceId, index, text, vector, modelId, section)

    companion object {
        const val SOURCES = "rag.sources"
        const val TEXTS = "rag.texts"
        const val CHUNKING_KEY = "chunking"
        private const val MAX_STORED_TEXT = 400_000 // under Android's 2 MB SQLite row limit even at 3 bytes a character
        private const val EMBED_BATCH = 16
        private const val CANDIDATES = 20
        private const val RRF_K = 60f
        private const val MAX_PER_SOURCE = 2
        private val REF = Regex("^(.*)#(\\d+)(?:-(\\d+))?$")

        /**
         * ponytail: one default floor, tuned for bge-small (unrelated text scores about 0.3–0.5).
         * Apps can change it per request through `KnowledgeApi.minSimilarity`.
         */
        const val MIN_COSINE = 0.55f

        private val STOP_WORDS = setOf(
            "a", "about", "an", "and", "any", "are", "as", "at", "be", "by", "can", "could", "did", "do", "does", "for",
            "from", "had", "has", "have", "how", "i", "if", "in", "is", "it", "its", "me", "my", "of", "on", "or", "our",
            "please", "should", "so", "tell", "than", "that", "the", "their", "them", "then", "there", "these", "this",
            "to", "was", "we", "were", "what", "when", "where", "which", "who", "why", "will", "with", "would", "you", "your",
        )

        internal fun terms(text: String): List<String> =
            Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filter { it.length > 1 && it !in STOP_WORDS }.map(::stem).toList()

        /**
         * Light English suffix folding so "messages" finds "message" and "called" finds "call".
         * ponytail: a few rules, not a full stemmer (Porter/Snowball); swap one in if recall needs it.
         */
        internal fun stem(word: String): String {
            if (word.length <= 3 || word.any { it.isDigit() }) return word
            val w = when {
                word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
                word.endsWith("sses") || word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") -> word.dropLast(2)
                word.endsWith("ing") && word.length > 5 -> word.dropLast(3)
                word.endsWith("ed") && word.length > 4 -> word.dropLast(2)
                word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") -> word.dropLast(1)
                else -> word
            }
            // Fold a final "e" so message / messages / messaged meet at "messag".
            return if (w.length > 4 && w.endsWith("e")) w.dropLast(1) else w
        }

        /** Joins consecutive chunks, dropping the overlap the [Chunker] repeated between them. */
        internal fun mergeTexts(texts: List<String>): String = texts.reduce { acc, next ->
            val max = minOf(acc.length, next.length, 600)
            val overlap = (max downTo 20).firstOrNull { n -> acc.endsWith(next.substring(0, n)) } ?: 0
            if (overlap > 0) acc + next.substring(overlap) else "$acc\n\n$next"
        }
    }
}

/**
 * How an embedding model expects queries and documents to be phrased. Retrieval models are
 * trained with these prefixes, and leaving them out costs accuracy.
 * ponytail: matched on the model id; move into ModelDescriptor metadata if apps bring other models.
 */
internal class QueryStyle(private val queryPrefix: String, private val documentPrefix: String) {
    fun query(text: String) = queryPrefix + text
    fun document(title: String, text: String) = "$documentPrefix$title\n$text"

    companion object {
        fun of(modelId: String): QueryStyle {
            val id = modelId.lowercase()
            return when {
                "bge" in id && "-zh" !in id -> QueryStyle("Represent this sentence for searching relevant passages: ", "")
                "nomic" in id -> QueryStyle("search_query: ", "search_document: ")
                "e5" in id -> QueryStyle("query: ", "passage: ")
                else -> QueryStyle("", "")
            }
        }
    }
}

/**
 * Feeds retrieved passages to the Context Manager (T2AI-024.4), the only path from RAG to a model.
 * Passages are numbered so the model can cite them ([1], [2]); [limit], [minSimilarity], and
 * [enabled] are read on every request, so the app can change them at any time.
 */
class RagContextSource(
    private val engine: RagEngine,
    private val limit: () -> Int = { 3 },
    private val minSimilarity: () -> Float = { RagEngine.MIN_COSINE },
    private val neighbors: () -> Boolean = { false },
    private val enabled: () -> Boolean = { true },
) : ContextSource {
    override val name = "rag"

    override suspend fun fetch(query: String, model: ModelDescriptor): List<ContextItem> {
        if (!enabled()) return emptyList()
        // Knowledge is an enhancement: if retrieval fails, the request goes ahead without it.
        val hits = try {
            engine.retrieve(query, limit(), minSimilarity(), neighbors())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return hits.mapIndexed { i, c ->
            val where = listOfNotNull(c.sourceName, c.section).joinToString(" › ")
            ContextItem(c.ref, "[${i + 1}] $where\n${c.text}")
        }
    }

    override fun render(items: List<ContextItem>): String =
        "Passages from the user's own documents, most relevant first. Use them when they answer the question " +
            "and cite each one you use like [1]. If they do not contain the answer, say so instead of guessing.\n\n" +
            items.joinToString("\n\n") { it.text }
}
