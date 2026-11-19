package com.tune2ai.sdk.services.rag

import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.services.embedding.cosine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One indexed chunk: its text and metadata, the vector that represents it, and which model made that vector. */
@Serializable
data class IndexedChunk(
    val sourceId: String,
    val ordinal: Int,
    val text: String,
    val metadata: Map<String, String>,
    val modelId: String,
    val vector: FloatArray,
)

data class ScoredChunk(val chunk: IndexedChunk, val score: Double)

/**
 * Chunk vectors kept in the `rag` storage namespace (decision D5) and searched by cosine similarity.
 * ponytail: brute force, every search reads and scores every chunk. Fine up to a few thousand chunks
 * on a phone; past that, add an ANN index behind this same class.
 */
class VectorIndex(private val storage: Storage) {

    suspend fun put(chunk: IndexedChunk) {
        storage.put(NAMESPACE, key(chunk.sourceId, chunk.ordinal), JSON.encodeToString(IndexedChunk.serializer(), chunk))
    }

    /** Removes chunks of [sourceId] with ordinal at or above [fromOrdinal]. Returns how many were removed. */
    suspend fun removeSource(sourceId: String, fromOrdinal: Int = 0): Int {
        val prefix = prefix(sourceId)
        var removed = 0
        storage.list(NAMESPACE).keys
            .filter { it.startsWith(prefix) && it.substring(prefix.length).toInt() >= fromOrdinal }
            .forEach { if (storage.delete(NAMESPACE, it)) removed++ }
        return removed
    }

    suspend fun contains(sourceId: String): Boolean = storage.list(NAMESPACE).keys.any { it.startsWith(prefix(sourceId)) }

    suspend fun sources(): List<String> = all().map { it.sourceId }.distinct().sorted()

    suspend fun clear() = storage.clear(NAMESPACE)

    suspend fun isEmpty(): Boolean = storage.list(NAMESPACE).isEmpty()

    /**
     * The [limit] chunks most similar to [query], best first; equal scores keep source then position order.
     * Chunks embedded by a different model than [modelId] cannot be compared, so they fail the search with
     * a typed error rather than returning meaningless scores.
     */
    suspend fun search(query: FloatArray, modelId: String, limit: Int): List<ScoredChunk> {
        val chunks = all()
        chunks.firstOrNull { it.modelId != modelId }?.let {
            throw Tune2AIException.InvalidRequest("The index was built with embedding model '${it.modelId}', not '$modelId'; clear it and add the content again")
        }
        return chunks.map { ScoredChunk(it, cosine(query, it.vector)) }
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunk.sourceId }.thenBy { it.chunk.ordinal })
            .take(limit)
    }

    private suspend fun all(): List<IndexedChunk> =
        storage.list(NAMESPACE).values.map { JSON.decodeFromString(IndexedChunk.serializer(), it) }

    // The length prefix keeps "a" and "ab" apart: keys are "<len>:<sourceId>:<ordinal>".
    private fun prefix(sourceId: String) = "${sourceId.length}:$sourceId:"
    private fun key(sourceId: String, ordinal: Int) = prefix(sourceId) + ordinal

    private companion object {
        const val NAMESPACE = "rag"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
