package com.tune2ai.sdk.core

/**
 * Namespaced key-value storage for sessions, model metadata, memory, and workflow checkpoints.
 *
 * Ownership and retention: the SDK writes records; the application owns them and can clear any
 * namespace at any time. Nothing expires on its own. Data lives in app-private storage only; at-rest
 * encryption relies on Android file-based encryption.
 *
 * Failures throw [com.tune2ai.sdk.api.Tune2AIException.StorageFailure]; a write never fails silently.
 */
interface Storage {
    suspend fun get(namespace: String, key: String): String?

    /** Inserts or replaces the record. */
    suspend fun put(namespace: String, key: String, value: String)

    /** Returns true if a record was deleted. */
    suspend fun delete(namespace: String, key: String): Boolean

    /** All records in [namespace], ordered by key. */
    suspend fun list(namespace: String): Map<String, String>

    suspend fun clear(namespace: String)
}

/** Process-lifetime storage. Used by tests and by apps that want nothing persisted. */
class InMemoryStorage : Storage {
    private val records = HashMap<String, java.util.TreeMap<String, String>>()

    override suspend fun get(namespace: String, key: String) = synchronized(records) { records[namespace]?.get(key) }

    override suspend fun put(namespace: String, key: String, value: String) {
        synchronized(records) { records.getOrPut(namespace) { java.util.TreeMap() }[key] = value }
    }

    override suspend fun delete(namespace: String, key: String) =
        synchronized(records) { records[namespace]?.remove(key) != null }

    override suspend fun list(namespace: String): Map<String, String> =
        synchronized(records) { LinkedHashMap(records[namespace].orEmpty()) }

    override suspend fun clear(namespace: String) {
        synchronized(records) { records.remove(namespace) }
    }
}
