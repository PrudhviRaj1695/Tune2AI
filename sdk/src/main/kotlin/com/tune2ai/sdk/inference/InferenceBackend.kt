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

    suspend fun load(model: ModelDescriptor): LoadedModel

    suspend fun unload(model: LoadedModel)

    fun generate(model: LoadedModel, request: GenerateRequest): Flow<String>
}

/** True if this backend may run [model] for [task]. */
fun InferenceBackend.canRun(model: ModelDescriptor, task: Task): Boolean =
    model.format in capabilities.formats &&
        task in capabilities.tasks &&
        task in model.tasks &&
        (model.backends.isEmpty() || id in model.backends)
