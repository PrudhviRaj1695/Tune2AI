package com.tune2ai.sdk

import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.inference.BackendCapabilities
import com.tune2ai.sdk.inference.GenerateRequest
import com.tune2ai.sdk.inference.InferenceBackend
import com.tune2ai.sdk.inference.LoadedModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scripted backend for JVM tests: configurable tokens, delays, and failures. */
class FakeBackend(
    override val id: String = "fake",
    formats: Set<String> = setOf("fake"),
    var tokens: List<String> = listOf("Hello", ",", " world"),
    var tokenDelayMillis: Long = 0,
    var loadDelayMillis: Long = 0,
    var failLoad: Tune2AIException? = null,
    var failAfterTokens: Int? = null,
    var unavailable: String? = null,
    network: Boolean = false,
    /** Non-null makes this an embedding backend producing vectors of this size (a hashed bag of words). */
    val embedDims: Int? = null,
) : InferenceBackend {
    override val capabilities = BackendCapabilities(
        formats,
        if (embedDims == null) setOf(Task.CHAT) else setOf(Task.CHAT, Task.EMBEDDING),
        requiresNetwork = network,
    )
    override val unavailableReason: String? get() = unavailable
    var loads = 0
        private set
    var unloads = 0
        private set
    var lastRequest: GenerateRequest? = null
        private set

    private data class Model(override val descriptor: ModelDescriptor) : LoadedModel

    override suspend fun load(model: ModelDescriptor): LoadedModel {
        delay(loadDelayMillis)
        failLoad?.let { throw it }
        if (model.path.endsWith(".broken")) throw Tune2AIException.ModelUnavailable("Not a valid model file: ${model.path}")
        loads++
        return Model(model)
    }

    override suspend fun unload(model: LoadedModel) {
        unloads++
    }

    var embedCalls = 0
        private set

    override suspend fun embed(model: LoadedModel, texts: List<String>): List<FloatArray> {
        val dims = embedDims ?: return super.embed(model, texts)
        embedCalls++
        return texts.map { text ->
            FloatArray(dims).also { v -> text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.forEach { v[Math.floorMod(it.hashCode(), dims)] += 1f } }
        }
    }

    /** Scripted replies: each generate call takes the next one; when empty, [tokens] is used. */
    val script = ArrayDeque<List<String>>()

    override fun generate(model: LoadedModel, request: GenerateRequest): Flow<String> = flow {
        lastRequest = request
        val reply = synchronized(script) { script.removeFirstOrNull() } ?: tokens
        reply.take(request.maxTokens).forEachIndexed { i, token ->
            if (i == failAfterTokens) throw Tune2AIException.BackendFailure("Scripted failure after $i tokens")
            delay(tokenDelayMillis)
            emit(token)
        }
    }
}

fun fakeModel(
    id: String = "tiny",
    memoryGb: Double = 1.0,
    format: String = "fake",
    path: String = "/models/$id.$format",
    contextLength: Int = 4096,
    tasks: Set<Task> = setOf(Task.CHAT),
) = ModelDescriptor(id, id.replaceFirstChar { it.uppercase() }, format, path, (memoryGb * GB).toLong(), contextLength, tasks)
