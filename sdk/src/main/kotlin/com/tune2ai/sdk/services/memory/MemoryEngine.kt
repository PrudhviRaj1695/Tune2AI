package com.tune2ai.sdk.services.memory

import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.services.embedding.Embedder
import com.tune2ai.sdk.services.embedding.cosine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** One remembered fact. [id] is stable; [createdAt] gives a deterministic order and tie-break. */
@Serializable
data class MemoryEntry(val id: String, val text: String, val createdAt: Long)

/**
 * Explicit, per-session memory (FR-SVC-6) stored in the `memory/<session>` namespace. Sessions are
 * isolated: operations on one never see or touch another. [search] ranks by embedding similarity when
 * an [Embedder] is configured, otherwise by keyword overlap. Memory only reaches a request through
 * [MemoryContextSource]; when [enabled] is false it contributes nothing.
 */
class MemoryEngine(
    private val storage: Storage,
    private val embedder: Embedder? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** When false, [MemoryContextSource] returns nothing. Stored memories are kept; writes still work. */
    @Volatile
    var enabled: Boolean = true

    private fun namespace(session: String) = "memory/$session"

    /** Remembers [text] in [session]; returns the new entry id. */
    suspend fun write(session: String, text: String): String {
        val entry = MemoryEntry(newId(), text, clock())
        storage.put(namespace(session), entry.id, JSON.encodeToString(MemoryEntry.serializer(), entry))
        return entry.id
    }

    /** Replaces an entry's text, keeping its id and original order. Returns false if [id] is unknown. */
    suspend fun update(session: String, id: String, text: String): Boolean {
        val existing = get(session, id) ?: return false
        storage.put(namespace(session), id, JSON.encodeToString(MemoryEntry.serializer(), existing.copy(text = text)))
        return true
    }

    suspend fun delete(session: String, id: String): Boolean = storage.delete(namespace(session), id)

    /** Forgets everything in [session]. */
    suspend fun clear(session: String) = storage.clear(namespace(session))

    /** All entries in [session], oldest first — for the host to inspect. */
    suspend fun list(session: String): List<MemoryEntry> = entries(session).sortedBy { it.createdAt }

    /**
     * The [limit] entries in [session] most relevant to [query]. Embedding cosine when an [Embedder] is
     * set, else keyword overlap; entries with no overlap are dropped. Ties break by age then id, so the
     * order is stable.
     * ponytail: O(n) scan over the session's entries; fine for the hundreds a session holds. Add an index
     * if a session grows to tens of thousands.
     */
    suspend fun search(session: String, query: String, limit: Int = 5): List<MemoryEntry> {
        val all = entries(session)
        if (all.isEmpty()) return emptyList()
        val scored = if (embedder != null) {
            val q = embedder.embed(query)
            all.map { it to cosine(q, embedder.embed(it.text)) }
        } else {
            val terms = tokenize(query)
            all.map { it to tokenize(it.text).count { t -> t in terms }.toDouble() }
        }
        return scored.filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<MemoryEntry, Double>> { it.second }.thenBy { it.first.createdAt }.thenBy { it.first.id })
            .take(limit)
            .map { it.first }
    }

    private suspend fun get(session: String, id: String): MemoryEntry? =
        storage.get(namespace(session), id)?.let { JSON.decodeFromString(MemoryEntry.serializer(), it) }

    private suspend fun entries(session: String): List<MemoryEntry> =
        storage.list(namespace(session)).values.map { JSON.decodeFromString(MemoryEntry.serializer(), it) }

    private fun newId() = "mem_" + Random.nextLong().toULong().toString(16).padStart(16, '0')

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        fun tokenize(text: String): Set<String> =
            text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
    }
}
