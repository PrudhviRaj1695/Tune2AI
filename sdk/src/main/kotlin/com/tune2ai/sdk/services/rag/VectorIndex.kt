package com.tune2ai.sdk.services.rag

import com.tune2ai.sdk.core.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/** One stored chunk. [vector] is null when it was indexed without an embedding model. */
class IndexedChunk(
    val sourceId: String,
    val index: Int,
    val text: String,
    val vector: FloatArray?,
    val modelId: String?,
    val section: String? = null,
)

/**
 * Chunks and their vectors in [Storage] (decision D5), one record per chunk so no row grows past
 * Android's 2 MB cursor window. Everything is cached in memory after the first read; search is a
 * brute-force scan.
 * ponytail: brute force over an in-memory copy. Fine to ~10k chunks (D5's ceiling); past that,
 * move to an ANN index (HNSW) or sqlite-vec.
 */
class VectorIndex(private val storage: Storage) {
    private val lock = Mutex()
    private var cache: MutableMap<String, List<IndexedChunk>>? = null

    @Serializable
    private class Record(val s: String, val i: Int, val t: String, val v: String? = null, val m: String? = null, val h: String? = null)

    suspend fun replace(sourceId: String, chunks: List<IndexedChunk>) = lock.withLock {
        val all = loaded()
        val key = keyOf(sourceId)
        all[sourceId]?.forEach { storage.delete(CHUNKS, "$key/${it.index}") }
        chunks.forEach { c ->
            storage.put(CHUNKS, "$key/${c.index}", json.encodeToString(Record.serializer(), Record(sourceId, c.index, c.text, c.vector?.let(::encode), c.modelId, c.section)))
        }
        if (chunks.isEmpty()) all.remove(sourceId) else all[sourceId] = chunks
    }

    suspend fun remove(sourceId: String): Boolean = lock.withLock {
        val all = loaded()
        val removed = all.remove(sourceId) ?: return@withLock false
        val key = keyOf(sourceId)
        removed.forEach { storage.delete(CHUNKS, "$key/${it.index}") }
        true
    }

    suspend fun clear() = lock.withLock {
        storage.clear(CHUNKS)
        cache = HashMap()
    }

    suspend fun all(): List<IndexedChunk> = lock.withLock { loaded().values.flatten() }

    /**
     * Chunk lists per source. A source's list object is replaced (never mutated) when the source
     * changes, so callers can cache work per list and detect changes by identity.
     */
    suspend fun bySource(): Map<String, List<IndexedChunk>> = lock.withLock { HashMap(loaded()) }

    suspend fun chunksOf(sourceId: String): List<IndexedChunk> = lock.withLock { loaded()[sourceId].orEmpty() }

    private suspend fun loaded(): MutableMap<String, List<IndexedChunk>> {
        cache?.let { return it }
        val bySource = HashMap<String, MutableList<IndexedChunk>>()
        storage.list(CHUNKS).values.forEach { raw ->
            val r = runCatching { json.decodeFromString(Record.serializer(), raw) }.getOrNull() ?: return@forEach
            bySource.getOrPut(r.s) { mutableListOf() } += IndexedChunk(r.s, r.i, r.t, r.v?.let(::decode), r.m, r.h)
        }
        val map: MutableMap<String, List<IndexedChunk>> = HashMap()
        bySource.forEach { (id, list) -> map[id] = list.sortedBy { it.index } }
        cache = map
        return map
    }

    companion object {
        const val CHUNKS = "rag.chunks"
        private val json = Json { ignoreUnknownKeys = true }

        /** Storage-safe key for any source id. */
        fun keyOf(sourceId: String): String =
            MessageDigest.getInstance("SHA-1").digest(sourceId.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun encode(v: FloatArray): String {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { buf.putFloat(it) }
            return Base64.getEncoder().encodeToString(buf.array())
        }

        private fun decode(s: String): FloatArray {
            val buf = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(buf.remaining() / 4) { buf.getFloat() }
        }
    }
}
