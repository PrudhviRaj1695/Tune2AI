package com.tune2ai.sdk.services.rag

import com.tune2ai.sdk.api.KnowledgeSource
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.RetrievedChunk
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.services.context.ContextItem
import com.tune2ai.sdk.services.context.ContextSource
import com.tune2ai.sdk.services.embedding.CosineReranker
import com.tune2ai.sdk.services.embedding.EmbeddingService
import com.tune2ai.sdk.services.embedding.RerankCandidate
import com.tune2ai.sdk.services.embedding.Reranker
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.math.ln

/**
 * Add, update, delete, and retrieve app knowledge (FR-SVC-5, T2AI-024.3).
 *
 * Text is chunked and, when an embedding model is available, embedded; retrieval then ranks by
 * cosine similarity. Without one, chunks are still stored and retrieval falls back to BM25 keyword
 * scoring, so an app works before (or without) downloading an embedding model. [reindex] embeds
 * chunks stored earlier without vectors.
 *
 * Adding an existing id replaces all of its chunks; deleted content never appears in retrieval.
 */
class RagEngine(
    private val storage: Storage,
    private val embeddings: EmbeddingService?,
    private val chunker: Chunker = Chunker(),
    private val reranker: Reranker = CosineReranker(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val index = VectorIndex(storage)
    private val json = Json { ignoreUnknownKeys = true }

    /** Embedding model new content is indexed with, or null when retrieval uses keywords. */
    fun embeddingModel(): String? = embeddings?.modelId()

    suspend fun add(id: String, name: String, text: String, metadata: Map<String, String> = emptyMap()): KnowledgeSource {
        if (id.isBlank()) throw Tune2AIException.InvalidRequest("A knowledge source needs an id")
        val pieces = chunker.chunk(text)
        val (vectors, modelId) = embed(pieces)
        index.replace(id, pieces.mapIndexed { i, t -> IndexedChunk(id, i, t, vectors?.get(i), modelId) })
        val source = KnowledgeSource(id, name, metadata, pieces.size, text.length, clock(), modelId)
        storage.put(SOURCES, VectorIndex.keyOf(id), json.encodeToString(KnowledgeSource.serializer(), source))
        return source
    }

    suspend fun delete(id: String): Boolean {
        val removed = index.remove(id)
        return storage.delete(SOURCES, VectorIndex.keyOf(id)) || removed
    }

    suspend fun sources(): List<KnowledgeSource> =
        storage.list(SOURCES).values.mapNotNull { runCatching { json.decodeFromString(KnowledgeSource.serializer(), it) }.getOrNull() }
            .sortedByDescending { it.addedAtMillis }

    suspend fun clear() {
        index.clear()
        storage.clear(SOURCES)
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
            val (vectors, modelId) = embed(chunks.map { it.text })
            index.replace(source.id, chunks.mapIndexed { i, c -> IndexedChunk(c.sourceId, c.index, c.text, vectors?.get(i), modelId) })
            storage.put(SOURCES, VectorIndex.keyOf(source.id), json.encodeToString(KnowledgeSource.serializer(), source.copy(embeddingModel = modelId)))
            onProgress(n + 1, stale.size)
        }
        return stale.size
    }

    /**
     * Up to [limit] chunks relevant to [query], best first. Uses embeddings for chunks embedded by
     * the current model, then fills remaining places by keyword match. Chunks below the relevance
     * floor are left out, so an unrelated question retrieves nothing.
     */
    suspend fun retrieve(query: String, limit: Int = 3): List<RetrievedChunk> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val chunks = index.all()
        if (chunks.isEmpty()) return emptyList()
        val sources = sources().associateBy { it.id }
        val results = mutableListOf<RetrievedChunk>()

        val model = embeddingModel()
        val embedded = if (model == null) emptyList() else chunks.filter { it.modelId == model && it.vector != null }
        if (embedded.isNotEmpty()) {
            val queryVector = runCatching { embeddings!!.embed(listOf(query), model).vectors.single() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (queryVector != null && queryVector.size == embedded.first().vector!!.size) {
                val byKey = embedded.associateBy { "${it.sourceId}\u0000${it.index}" }
                reranker.rerank(query, queryVector, embedded.map { RerankCandidate("${it.sourceId}\u0000${it.index}", it.text, it.vector) })
                    .filter { it.score >= MIN_COSINE }
                    .take(limit)
                    .forEach { r -> results += byKey.getValue(r.id).toResult(sources, r.score, byEmbedding = true) }
            }
        }
        if (results.size < limit) {
            val taken = results.map { it.sourceId to it.index }.toSet()
            val rest = chunks.filter { (it.sourceId to it.index) !in taken }
            keywordRank(query, rest).take(limit - results.size).forEach { (c, score) -> results += c.toResult(sources, score, byEmbedding = false) }
        }
        return results
    }

    private suspend fun embed(texts: List<String>): Pair<List<FloatArray>?, String?> {
        val service = embeddings ?: return null to null
        val model = service.modelId() ?: return null to null
        if (texts.isEmpty()) return null to model
        return try {
            val vectors = texts.chunked(EMBED_BATCH).flatMap { service.embed(it, model).vectors }
            vectors to model
        } catch (e: CancellationException) {
            throw e
        } catch (e: Tune2AIException) {
            null to null // stored without vectors; keyword search still finds it, reindex() can embed later
        }
    }

    /** BM25 over the given chunks; only chunks sharing at least one query term score. */
    private fun keywordRank(query: String, chunks: List<IndexedChunk>): List<Pair<IndexedChunk, Float>> {
        val terms = terms(query).distinct()
        if (terms.isEmpty() || chunks.isEmpty()) return emptyList()
        val docs = chunks.map { it to terms(it.text) }
        val avgLength = docs.sumOf { it.second.size }.toDouble() / docs.size
        val df = terms.associateWith { t -> docs.count { (_, words) -> t in words } }
        return docs.mapNotNull { (chunk, words) ->
            val counts = words.groupingBy { it }.eachCount()
            var score = 0.0
            for (t in terms) {
                val tf = counts[t] ?: continue
                val idf = ln(1 + (docs.size - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * words.size / avgLength))
            }
            if (score > 0) chunk to score.toFloat() else null
        }.sortedByDescending { it.second }
    }

    private fun IndexedChunk.toResult(sources: Map<String, KnowledgeSource>, score: Float, byEmbedding: Boolean): RetrievedChunk {
        val source = sources[sourceId]
        return RetrievedChunk(sourceId, source?.name ?: sourceId, index, text, score, byEmbedding, source?.metadata.orEmpty())
    }

    companion object {
        const val SOURCES = "rag.sources"
        private const val EMBED_BATCH = 16
        private const val K1 = 1.2
        private const val B = 0.75

        /**
         * ponytail: one floor for every embedding model, tuned for bge-small (unrelated text scores
         * about 0.3–0.5). Make it per-model if other embedders retrieve too much or too little.
         */
        const val MIN_COSINE = 0.55f

        private val STOP_WORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for", "from", "how", "i", "in", "is", "it",
            "me", "my", "of", "on", "or", "our", "tell", "that", "the", "this", "to", "was", "what", "when", "where", "which",
            "who", "why", "with", "you", "your",
        )

        internal fun terms(text: String): List<String> =
            Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filter { it.length > 1 && it !in STOP_WORDS }.toList()
    }
}

/**
 * Feeds retrieved chunks to the Context Manager (T2AI-024.4), the only path from RAG to a model.
 * [enabled] is read on every request, so the app can switch knowledge on and off at any time.
 */
class RagContextSource(
    private val engine: RagEngine,
    private val limit: Int = 3,
    private val enabled: () -> Boolean = { true },
) : ContextSource {
    override val name = "rag"

    override suspend fun fetch(query: String, model: ModelDescriptor): List<ContextItem> {
        if (!enabled()) return emptyList()
        // Knowledge is an enhancement: if retrieval fails, the request goes ahead without it.
        val hits = try {
            engine.retrieve(query, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return hits.map { c ->
            ContextItem("${c.sourceName}#${c.index + 1}", "From \"${c.sourceName}\": ${c.text}")
        }
    }
}
