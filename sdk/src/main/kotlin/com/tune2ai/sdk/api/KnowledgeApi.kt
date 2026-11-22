package com.tune2ai.sdk.api

import com.tune2ai.sdk.services.rag.ChunkingStrategy
import com.tune2ai.sdk.services.rag.RagEngine
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking

/**
 * The app's knowledge index (RAG, FR-SVC-5). Get it from `Tune2AI.knowledge`.
 *
 * Text added here is chunked and stored on the device. While [enabled], every chat and agent
 * request includes the chunks most relevant to the user's message, through the Context Manager.
 * Register a model with [Task.EMBEDDING] for semantic search; without one, search is by keyword.
 */
class KnowledgeApi internal constructor(
    private val engine: RagEngine,
    private val ready: Deferred<Unit>,
) {
    /** Whether requests include retrieved knowledge. On by default. */
    @Volatile var enabled: Boolean = true

    /** Most passages one request includes (1–8). More gives more context but a slower first token. */
    @Volatile var passagesPerAnswer: Int = 3
        set(value) { field = value.coerceIn(1, 8) }

    /**
     * Cosine floor for meaning-based matches (0.3–0.9). Raise it when unrelated passages show up;
     * lower it when relevant ones are missed. Keyword matches have their own coverage rule.
     */
    @Volatile var minSimilarity: Float = RagEngine.MIN_COSINE
        set(value) { field = value.coerceIn(0.3f, 0.9f) }

    /** Adds the passage before and after each match, for more surrounding context. Off by default. */
    @Volatile var includeNeighbors: Boolean = false

    /** How new documents are split into passages; [rechunk] applies a change to existing ones. */
    var chunking: ChunkingStrategy
        get() = engine.chunking
        set(value) { engine.chunking = value }

    /**
     * Embedding model to use when several are registered (null: the router chooses). After changing
     * it, call [reindex] so existing documents are embedded with it; until then they are found by keyword.
     */
    var preferredEmbeddingModel: String?
        get() = engine.preferredEmbeddingModel
        set(value) { engine.preferredEmbeddingModel = value }

    /** Embedding model used for new content and search, or null when search is by keyword. */
    fun embeddingModel(): String? = engine.embeddingModel()

    /** Adds a document, or replaces the one with the same [id]. */
    suspend fun add(id: String, name: String, text: String, metadata: Map<String, String> = emptyMap()): KnowledgeSource {
        ready.await()
        return engine.add(id, name, text, metadata)
    }

    /** Removes a document; it never appears in later retrieval. Returns false if it was not there. */
    suspend fun remove(id: String): Boolean {
        ready.await()
        return engine.delete(id)
    }

    suspend fun sources(): List<KnowledgeSource> {
        ready.await()
        return engine.sources()
    }

    /** The chunks a request for [query] would include, best first. */
    suspend fun search(query: String, limit: Int = passagesPerAnswer): List<RetrievedChunk> {
        ready.await()
        return engine.retrieve(query, limit, minSimilarity, includeNeighbors)
    }

    /** The passage behind a reference from a request trace, e.g. "notes.md#2" (see [RetrievedChunk.ref]). */
    suspend fun passage(ref: String): RetrievedChunk? {
        ready.await()
        return engine.passage(ref)
    }

    /** Embeds documents stored before the current embedding model was available. */
    suspend fun reindex(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        ready.await()
        return engine.reindex(onProgress)
    }

    /** Re-splits documents made with another [chunking] strategy. Returns how many changed. */
    suspend fun rechunk(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        ready.await()
        return engine.rechunk(onProgress)
    }

    /** Blocking [rechunk] for Java callers; [onProgress] receives (done, total). */
    fun rechunkBlocking(onProgress: ProgressListener): Int = runBlocking { rechunk { done, total -> onProgress.onProgress(done, total) } }

    suspend fun clear() {
        ready.await()
        engine.clear()
    }

    /** Blocking [add] for Java callers. Call from a background thread. */
    fun addBlocking(id: String, name: String, text: String, metadata: Map<String, String>): KnowledgeSource =
        runBlocking { add(id, name, text, metadata) }

    fun removeBlocking(id: String): Boolean = runBlocking { remove(id) }

    fun sourcesBlocking(): List<KnowledgeSource> = runBlocking { sources() }

    fun searchBlocking(query: String, limit: Int): List<RetrievedChunk> = runBlocking { search(query, limit) }

    fun passageBlocking(ref: String): RetrievedChunk? = runBlocking { passage(ref) }

    /** Blocking [reindex] for Java callers; [onProgress] receives (done, total). */
    fun reindexBlocking(onProgress: ProgressListener): Int = runBlocking { reindex { done, total -> onProgress.onProgress(done, total) } }

    fun clearBlocking() = runBlocking { clear() }

    fun interface ProgressListener {
        fun onProgress(done: Int, total: Int)
    }
}
