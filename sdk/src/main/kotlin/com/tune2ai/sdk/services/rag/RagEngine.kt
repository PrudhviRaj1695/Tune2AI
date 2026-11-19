package com.tune2ai.sdk.services.rag

import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.services.context.ContextItem
import com.tune2ai.sdk.services.context.ContextSource
import com.tune2ai.sdk.services.embedding.EmbeddingService

/** A retrieved chunk, with the source it came from and the metadata supplied when it was added. */
data class RetrievedChunk(
    val sourceId: String,
    val ordinal: Int,
    val text: String,
    val metadata: Map<String, String>,
    val score: Double,
) {
    val ref: String get() = "$sourceId#$ordinal"
}

/**
 * Application knowledge the model can draw on (FR-SVC-5). Content is added as a source: it is split
 * into chunks, embedded with the [EmbeddingService], and indexed in storage. Retrieval returns the
 * chunks closest to a query. Updated or deleted content never appears in later retrieval.
 * Retrieved chunks reach a request only through [RagContextSource] and the Context Manager.
 *
 * Needs an embedding-capable model registered (`tasks` containing EMBEDDING); without one, add and
 * retrieve fail with a typed NO_COMPATIBLE_ROUTE error.
 */
class RagEngine(
    storage: Storage,
    private val embeddings: EmbeddingService,
    private val chunker: Chunker = Chunker(),
) {
    private val index = VectorIndex(storage)

    /** When false, [RagContextSource] returns nothing. The index is kept. */
    @Volatile
    var enabled: Boolean = true

    /** Indexes [text] as a new source; returns the number of chunks. Fails if [sourceId] already exists (use [update]). */
    suspend fun add(sourceId: String, text: String, metadata: Map<String, String> = emptyMap()): Int {
        if (index.contains(sourceId)) throw Tune2AIException.InvalidRequest("Source '$sourceId' is already indexed; use update to replace it")
        return write(sourceId, text, metadata)
    }

    /**
     * Replaces everything indexed for [sourceId] with [text]. The new chunks are embedded before anything
     * is touched, so a failed embedding leaves the old content in place.
     * ponytail: Storage has no transactions; a process death between writing the new chunks and removing
     * the surplus old ones can leave a few stale tail chunks until the next update.
     */
    suspend fun update(sourceId: String, text: String, metadata: Map<String, String> = emptyMap()): Int {
        if (!index.contains(sourceId)) throw Tune2AIException.InvalidRequest("Source '$sourceId' is not indexed; use add")
        return write(sourceId, text, metadata)
    }

    /** Removes a source and all its chunks. Returns false if it was not indexed. */
    suspend fun delete(sourceId: String): Boolean = index.removeSource(sourceId) > 0

    suspend fun sources(): List<String> = index.sources()

    suspend fun clear() = index.clear()

    /** The [limit] chunks most relevant to [query], best first. An empty index returns nothing without embedding. */
    suspend fun retrieve(query: String, limit: Int = 5): List<RetrievedChunk> {
        if (query.isBlank()) throw Tune2AIException.InvalidRequest("A retrieval query must not be blank")
        if (limit < 1) throw Tune2AIException.InvalidRequest("limit must be at least 1")
        if (index.isEmpty()) return emptyList()
        val q = embeddings.embed(listOf(query))
        return index.search(q.vectors.single(), q.modelId, limit)
            .map { RetrievedChunk(it.chunk.sourceId, it.chunk.ordinal, it.chunk.text, it.chunk.metadata, it.score) }
    }

    private suspend fun write(sourceId: String, text: String, metadata: Map<String, String>): Int {
        if (sourceId.isBlank()) throw Tune2AIException.InvalidRequest("A source id must not be blank")
        val chunks = chunker.chunk(text)
        if (chunks.isEmpty()) throw Tune2AIException.InvalidRequest("Source '$sourceId' has no text to index")
        val result = embeddings.embed(chunks)
        chunks.forEachIndexed { i, chunk ->
            index.put(IndexedChunk(sourceId, i, chunk, metadata, result.modelId, result.vectors[i]))
        }
        index.removeSource(sourceId, fromOrdinal = chunks.size) // surplus chunks from a longer earlier version
        return chunks.size
    }
}

/**
 * Feeds retrieved chunks into the Context Manager (FR-SVC-5), its only path to a request. Returns
 * nothing while [RagEngine.enabled] is false or the index is empty, so chat works with no embedding
 * model until content is added.
 */
internal class RagContextSource(private val rag: RagEngine, private val limit: Int = 3) : ContextSource {
    override val name = "rag"

    override suspend fun fetch(query: String, model: ModelDescriptor): List<ContextItem> =
        if (!rag.enabled) emptyList()
        else rag.retrieve(query, limit).map { ContextItem(it.ref, it.text) }
}
