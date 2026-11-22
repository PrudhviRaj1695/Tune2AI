package com.tune2ai.sdk.inference

import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Task
import kotlinx.coroutines.flow.Flow

/** What a backend can run. Routing and the Model Manager use this; nothing else inspects backends. */
data class BackendCapabilities(
    val formats: Set<String>,
    val tasks: Set<Task> = setOf(Task.CHAT),
    val requiresNetwork: Boolean = false,
)

/** A model held in memory by a backend. Backends return their own implementation from [InferenceBackend.load]. */
interface LoadedModel {
    val descriptor: ModelDescriptor
}

data class GenerateRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int,
    val temperature: Float,
)

/**
 * The backend-neutral inference contract (FR-INF-1). llama.cpp, ONNX Runtime, Core ML, and the
 * Remote API each implement it; nothing above this interface knows which one runs a request.
 *
 * Rules every backend follows (checked by the contract test suite):
 * - [load] and [unload] are only called by the Model Manager.
 * - [generate] streams text pieces in order; cancelling the collector stops generation within 500 ms.
 * - Failures are thrown as [com.tune2ai.sdk.api.Tune2AIException]; a bad model file is `ModelUnavailable`.
 */
interface InferenceBackend {
    val id: String
    val capabilities: BackendCapabilities

    /** Why this backend cannot run anything right now (for example, no API key), or null if it can. */
    val unavailableReason: String? get() = null

    suspend fun load(model: ModelDescriptor): LoadedModel

    suspend fun unload(model: LoadedModel)

    fun generate(model: LoadedModel, request: GenerateRequest): Flow<String>

    /**
     * Optional (T2AI-023): one L2-normalised vector per text, all the same length. Backends that
     * embed list [com.tune2ai.sdk.api.Task.EMBEDDING] in [capabilities]; texts longer than the
     * model's input limit are truncated by the backend.
     */
    suspend fun embed(model: LoadedModel, texts: List<String>): List<FloatArray> =
        throw com.tune2ai.sdk.api.Tune2AIException.NoCompatibleRoute("Backend '$id' does not compute embeddings")
}

/** True if this backend supports [model]'s format and is allowed to run it (ignores availability). */
fun InferenceBackend.supports(model: ModelDescriptor): Boolean =
    model.format in capabilities.formats && (model.backends.isEmpty() || id in model.backends)

/** True if this backend is available and may run [model] for [task]. */
fun InferenceBackend.canRun(model: ModelDescriptor, task: Task): Boolean =
    unavailableReason == null && supports(model) && task in capabilities.tasks && task in model.tasks
