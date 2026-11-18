package com.tune2ai.sdk.api

import com.tune2ai.sdk.services.model.ModelManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * Register and inspect models. Get it from `Tune2AI.models`.
 * ponytail: the minimum Studio needs; T2AI-044 adds explicit load/unload and per-model state flows.
 */
class ModelsApi internal constructor(
    private val manager: ModelManager,
    private val ready: Deferred<Unit>,
) {
    /** State of every registered model, keyed by id. */
    val states: StateFlow<Map<String, ModelState>> get() = manager.states

    fun state(id: String): ModelState = manager.state(id)

    /** Models registered so far (includes models restored from earlier sessions once startup finishes). */
    suspend fun list(): List<ModelDescriptor> {
        ready.await()
        return manager.models()
    }

    /** Adds or replaces a model; it is remembered across app restarts. */
    suspend fun register(model: ModelDescriptor) {
        ready.await()
        manager.register(model)
    }

    suspend fun remove(id: String) {
        ready.await()
        manager.remove(id)
    }

    /** Blocking [list] for Java callers. Call from a background thread. */
    fun listBlocking(): List<ModelDescriptor> = runBlocking { list() }

    /** Blocking [register] for Java callers. Call from a background thread. */
    fun registerBlocking(model: ModelDescriptor) = runBlocking { register(model) }
}
