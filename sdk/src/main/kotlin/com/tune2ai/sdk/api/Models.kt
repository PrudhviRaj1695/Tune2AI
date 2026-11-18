package com.tune2ai.sdk.api

import kotlinx.serialization.Serializable

/** What a model can be used for. Routing matches requests to models by task. */
@Serializable
enum class Task { CHAT, TOOL_CALLING, EMBEDDING }

/**
 * A model the application makes available to the SDK (open decision 2: the app supplies the catalog).
 *
 * @param format file format, matched against backend capabilities, e.g. "gguf" or "onnx".
 * @param path absolute path to the model file in app-private storage.
 * @param memoryBytes memory needed once loaded; used to refuse loads that would not fit (NFR-8).
 * @param backends backend ids allowed to run this model; empty means any backend supporting [format].
 */
@Serializable
data class ModelDescriptor(
    val id: String,
    val name: String,
    val format: String,
    val path: String,
    val memoryBytes: Long,
    val contextLength: Int = 4096,
    val tasks: Set<Task> = setOf(Task.CHAT),
    val backends: Set<String> = emptySet(),
    val version: String = "1",
)

/** Lifecycle and availability of a registered model, as reported by the Model Manager. */
sealed interface ModelState {
    data object Unloaded : ModelState
    data object Loading : ModelState
    data class Loaded(val backendId: String) : ModelState
    data class Failed(val reason: String) : ModelState

    /** The model cannot be used right now; [reason] says why (missing file, no backend, not enough memory). */
    data class Unavailable(val reason: String) : ModelState
}

@Serializable
enum class Role { SYSTEM, USER, ASSISTANT }

@Serializable
data class ChatMessage(val role: Role, val content: String) {
    companion object {
        @JvmStatic fun system(content: String) = ChatMessage(Role.SYSTEM, content)
        @JvmStatic fun user(content: String) = ChatMessage(Role.USER, content)
        @JvmStatic fun assistant(content: String) = ChatMessage(Role.ASSISTANT, content)
    }
}
