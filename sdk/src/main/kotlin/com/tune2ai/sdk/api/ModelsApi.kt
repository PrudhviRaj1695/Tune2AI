package com.tune2ai.sdk.api

import com.tune2ai.sdk.services.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/** A registered backend and whether it can run right now. */
data class BackendInfo(
    val id: String,
    val formats: Set<String>,
    val tasks: Set<Task>,
    val requiresNetwork: Boolean,
    val unavailableReason: String?,
)

/** A registered model, its current state, and the backends that can run it. */
data class ModelInfo(
    val descriptor: ModelDescriptor,
    val state: ModelState,
    val backends: List<String>,
)

/** Discover, register, load, and unload models (FR-SDK-7). Get it from `Tune2AI.models`. */
class ModelsApi internal constructor(
    private val manager: ModelManager,
    private val scope: CoroutineScope,
    private val ready: Deferred<Unit>,
) {
    /** State of every registered model, keyed by id. */
    val states: StateFlow<Map<String, ModelState>> get() = manager.states

    /** State of one model; [ModelState.Unavailable] while it is not registered. */
    fun state(id: String): StateFlow<ModelState> = manager.states
        .map { it[id] ?: notRegistered(id) }
        .stateIn(scope, SharingStarted.Eagerly, manager.states.value[id] ?: notRegistered(id))

    /** Registered models with state and compatible backends (includes models restored from earlier sessions). */
    suspend fun list(): List<ModelInfo> {
        ready.await()
        return manager.models().map { model ->
            ModelInfo(model, manager.state(model.id), model.tasks.flatMap { manager.backendsFor(model, it) }.map { it.id }.distinct())
        }
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

    /**
     * Loads a model ahead of the first request, on the first backend that can run it. It stays
     * loaded until [unload], or until it is trimmed under memory pressure.
     */
    suspend fun load(id: String) {
        ready.await()
        val model = manager.find(id) ?: throw Tune2AIException.ModelUnavailable("Model '$id' is not registered")
        val backend = model.tasks.flatMap { manager.backendsFor(model, it) }.firstOrNull()
            ?: throw Tune2AIException.ModelUnavailable(
                (manager.state(id) as? ModelState.Unavailable)?.reason ?: "No available backend can run '$id'",
            )
        manager.acquire(model, backend).close()
    }

    suspend fun unload(id: String) {
        ready.await()
        manager.unload(id)
    }

    /** Blocking [list] for Java callers. Call from a background thread. */
    fun listBlocking(): List<ModelInfo> = runBlocking { list() }

    /** Blocking [register] for Java callers. Call from a background thread. */
    fun registerBlocking(model: ModelDescriptor) = runBlocking { register(model) }

    /** Blocking [load] for Java callers. Call from a background thread. */
    fun loadBlocking(id: String) = runBlocking { load(id) }

    private fun notRegistered(id: String) = ModelState.Unavailable("Model '$id' is not registered")
}
