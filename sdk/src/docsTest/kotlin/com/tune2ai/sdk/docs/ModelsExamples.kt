package com.tune2ai.sdk.docs

import com.tune2ai.sdk.Tune2AI

suspend fun describeModels(ai: Tune2AI): List<String> =
    ai.models.list().map { "${it.descriptor.name}: ${it.state} on ${it.backends}" }

suspend fun preload(ai: Tune2AI, id: String) {
    ai.models.load(id) // loads ahead of the first request
    // ... later, when the screen closes:
    ai.models.unload(id)
}

fun explainBackends(ai: Tune2AI): List<String> =
    ai.backends().map { backend ->
        backend.unavailableReason?.let { "${backend.id} unavailable: $it" } ?: "${backend.id} ready"
    }
