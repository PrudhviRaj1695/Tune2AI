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
) : InferenceBackend {
    override val capabilities = BackendCapabilities(formats, setOf(Task.CHAT, Task.EMBEDDING), requiresNetwork = network)
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

    /** Length of vectors from [embed]; change it between calls to simulate a mismatched model. */
    var embedDimensions = 256
    var embedCalls = 0
        private set

    /** Hashed bag of words (stop words dropped), L2-normalised: texts sharing words get similar vectors. */
    var embedOverride: ((List<String>) -> List<FloatArray>)? = null

    override suspend fun embed(model: LoadedModel, texts: List<String>): List<FloatArray> {
        embedCalls++
        embedOverride?.let { return it(texts) }
        return texts.map { text ->
            val v = FloatArray(embedDimensions)
            com.tune2ai.sdk.services.rag.RagEngine.terms(text).forEach { v[Math.floorMod(it.hashCode(), embedDimensions)] += 1f }
            val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            if (norm > 0) for (i in v.indices) v[i] /= norm
            v
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
