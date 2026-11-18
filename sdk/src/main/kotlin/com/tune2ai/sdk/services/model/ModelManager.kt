package com.tune2ai.sdk.services.model

import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.ModelState
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.ResourceMonitor
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.core.toTune2AIException
import com.tune2ai.sdk.inference.InferenceBackend
import com.tune2ai.sdk.inference.LoadedModel
import com.tune2ai.sdk.inference.canRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/** A model and a backend that can run it for a task. */
data class Candidate(val model: ModelDescriptor, val backend: InferenceBackend, val loaded: Boolean)

/**
 * Owns the model catalog, availability, and load state (FR-SVC-1). The only caller of
 * [InferenceBackend.load] and [InferenceBackend.unload]. Availability includes device resources:
 * a model that would not fit within [limits] is [ModelState.Unavailable].
 *
 * Models in use are held through a [Lease]; on low memory, models without leases are unloaded.
 */
class ModelManager(
    private val backends: List<InferenceBackend>,
    private val storage: Storage,
    private val resources: ResourceMonitor,
    private val limits: ResourceLimits,
    scope: CoroutineScope,
    private val fileExists: (String) -> Boolean = { File(it).isFile },
) {
    private class Held(val model: LoadedModel, val backend: InferenceBackend, var leases: Int)

    private val lock = Any()
    private val catalog = LinkedHashMap<String, ModelDescriptor>()
    private val held = HashMap<String, Held>()
    private val loading = HashSet<String>()
    private val failures = HashMap<String, String>()
    private val loadLocks = HashMap<String, Mutex>()
    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())

    /** Current state of every registered model, keyed by id. */
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    init {
        scope.launch {
            resources.snapshot.collect { snapshot ->
                if (snapshot.lowMemory) trim()
                publish()
            }
        }
    }

    /** Adds or replaces a model and persists it, so it is restored on the next start. */
    suspend fun register(model: ModelDescriptor) {
        storage.put(NAMESPACE, model.id, json.encodeToString(ModelDescriptor.serializer(), model))
        synchronized(lock) {
            catalog[model.id] = model
            failures.remove(model.id)
        }
        publish()
    }

    /** Unloads and forgets a model. */
    suspend fun remove(id: String) {
        unload(id)
        storage.delete(NAMESPACE, id)
        synchronized(lock) { catalog.remove(id) }
        publish()
    }

    /** Loads models registered in earlier sessions. */
    suspend fun restore() {
        val saved = storage.list(NAMESPACE).values.map { json.decodeFromString(ModelDescriptor.serializer(), it) }
        synchronized(lock) { saved.forEach { catalog.putIfAbsent(it.id, it) } }
        publish()
    }

    fun models(): List<ModelDescriptor> = synchronized(lock) { catalog.values.toList() }

    fun find(id: String): ModelDescriptor? = synchronized(lock) { catalog[id] }

    fun state(id: String): ModelState = synchronized(lock) { stateOf(id) }

    fun backendsFor(model: ModelDescriptor, task: Task): List<InferenceBackend> = backends.filter { it.canRun(model, task) }

    /** Usable (model, backend) pairs for [task]; unavailable and failed models are excluded. */
    fun candidates(task: Task): List<Candidate> = synchronized(lock) {
        catalog.values.flatMap { model ->
            val state = stateOf(model.id)
            if (state is ModelState.Unavailable || state is ModelState.Failed) {
                emptyList()
            } else {
                backendsFor(model, task).map { Candidate(model, it, held[model.id]?.backend == it) }
            }
        }
    }

    /** Why each registered model cannot serve [task], for "no compatible route" errors. */
    fun explainUnavailable(task: Task): List<String> = synchronized(lock) {
        catalog.values.map { model ->
            val state = stateOf(model.id)
            when {
                task !in model.tasks -> "${model.id}: does not support $task"
                state is ModelState.Unavailable -> "${model.id}: ${state.reason}"
                state is ModelState.Failed -> "${model.id}: last load failed (${state.reason})"
                backendsFor(model, task).isEmpty() -> "${model.id}: no backend runs $task for ${model.format}"
                else -> "${model.id}: available"
            }
        }
    }

    /**
     * Loads [model] on [backend] if needed and returns a lease. Close the lease when the request ends.
     * Concurrent calls for the same model load it once.
     */
    suspend fun acquire(model: ModelDescriptor, backend: InferenceBackend): Lease {
        val loadLock = synchronized(lock) { loadLocks.getOrPut(model.id) { Mutex() } }
        loadLock.withLock {
            synchronized(lock) {
                held[model.id]?.takeIf { it.backend == backend }?.let {
                    it.leases++
                    return Lease(model.id, it.model)
                }
            }
            unloadHeld(model.id, onlyIfIdle = true)
            if (synchronized(lock) { model.id in held }) {
                throw Tune2AIException.ModelUnavailable("Model '${model.id}' is in use on another backend")
            }
            limits.violation(resources.snapshot.value, model.memoryBytes)?.let {
                throw Tune2AIException.ResourceLimit("Cannot load '${model.id}': $it")
            }
            synchronized(lock) { loading += model.id }
            publish()
            try {
                val loaded = backend.load(model)
                synchronized(lock) {
                    held[model.id] = Held(loaded, backend, leases = 1)
                    failures.remove(model.id)
                }
                return Lease(model.id, loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val error = e.toTune2AIException()
                synchronized(lock) { failures[model.id] = error.message ?: error.category.name }
                throw error
            } finally {
                synchronized(lock) { loading -= model.id }
                publish()
            }
        }
    }

    /** Unloads [id] now, even if leased (in-flight generations on it will fail). */
    suspend fun unload(id: String) {
        val loadLock = synchronized(lock) { loadLocks.getOrPut(id) { Mutex() } }
        loadLock.withLock { unloadHeld(id, onlyIfIdle = false) }
        publish()
    }

    /** Unloads every model that no request is using. Called automatically on low memory. */
    suspend fun trim() {
        val idle = synchronized(lock) { held.filterValues { it.leases == 0 }.keys.toList() }
        idle.forEach { id ->
            val loadLock = synchronized(lock) { loadLocks.getOrPut(id) { Mutex() } }
            loadLock.withLock { unloadHeld(id, onlyIfIdle = true) }
        }
        publish()
    }

    private suspend fun unloadHeld(id: String, onlyIfIdle: Boolean) {
        val target = synchronized(lock) {
            val h = held[id] ?: return
            if (onlyIfIdle && h.leases > 0) return
            held.remove(id)
            h
        }
        target.backend.unload(target.model)
    }

    /** Held while a request uses a model; keeps it from being trimmed. */
    inner class Lease internal constructor(private val id: String, val model: LoadedModel) : AutoCloseable {
        private var closed = false

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                held[id]?.let { it.leases-- }
            }
        }
    }

    private fun publish() {
        _states.value = synchronized(lock) { catalog.keys.associateWith { stateOf(it) } }
    }

    // Caller holds [lock].
    private fun stateOf(id: String): ModelState {
        val model = catalog[id] ?: return ModelState.Unavailable("Model '$id' is not registered")
        held[id]?.let { return ModelState.Loaded(it.backend.id) }
        if (id in loading) return ModelState.Loading
        failures[id]?.let { return ModelState.Failed(it) }
        if (!fileExists(model.path)) return ModelState.Unavailable("Model file not found: ${model.path}")
        if (backends.none { model.format in it.capabilities.formats }) {
            return ModelState.Unavailable("No registered backend runs ${model.format} models")
        }
        limits.violation(resources.snapshot.value, model.memoryBytes)?.let { return ModelState.Unavailable(it) }
        return ModelState.Unloaded
    }

    private companion object {
        const val NAMESPACE = "models"
        val json = Json { ignoreUnknownKeys = true }
    }
}
