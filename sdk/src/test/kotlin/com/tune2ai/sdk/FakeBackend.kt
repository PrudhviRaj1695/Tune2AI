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
) : InferenceBackend {
    override val capabilities = BackendCapabilities(formats, setOf(Task.CHAT))
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

    override fun generate(model: LoadedModel, request: GenerateRequest): Flow<String> = flow {
        lastRequest = request
        tokens.take(request.maxTokens).forEachIndexed { i, token ->
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
) = ModelDescriptor(id, id.replaceFirstChar { it.uppercase() }, format, path, (memoryGb * GB).toLong(), contextLength)
