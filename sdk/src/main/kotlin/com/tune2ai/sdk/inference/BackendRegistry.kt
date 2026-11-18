package com.tune2ai.sdk.inference

import com.tune2ai.sdk.api.BackendInfo

/**
 * The extension point for inference backends (FR-INF-4). Registration validates the backend's
 * identity and capabilities; see docs/backend-guide.md for writing one.
 */
class BackendRegistry {
    private val backends = LinkedHashMap<String, InferenceBackend>()

    val all: List<InferenceBackend> get() = backends.values.toList()

    fun register(backend: InferenceBackend) {
        require(ID.matches(backend.id)) { "Backend id '${backend.id}' must match ${ID.pattern}" }
        require(backend.id !in backends) { "Backend '${backend.id}' is already registered" }
        require(backend.capabilities.formats.isNotEmpty()) { "Backend '${backend.id}' must declare at least one model format" }
        require(backend.capabilities.tasks.isNotEmpty()) { "Backend '${backend.id}' must declare at least one task" }
        backends[backend.id] = backend
    }

    /** Every registered backend, with the reason it is unavailable (null when it can run). */
    fun describe(): List<BackendInfo> = backends.values.map {
        BackendInfo(it.id, it.capabilities.formats, it.capabilities.tasks, it.capabilities.requiresNetwork, it.unavailableReason)
    }

    private companion object {
        val ID = Regex("[a-z0-9][a-z0-9._-]*")
    }
}
