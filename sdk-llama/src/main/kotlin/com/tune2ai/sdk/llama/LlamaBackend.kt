package com.tune2ai.sdk.llama

import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.inference.BackendCapabilities
import com.tune2ai.sdk.inference.GenerateRequest
import com.tune2ai.sdk.inference.InferenceBackend
import com.tune2ai.sdk.inference.LoadedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * Runs GGUF models on the device CPU through llama.cpp (FR-INF-2).
 *
 * Native calls block, so they run on a dedicated thread pool; one generation at a time per model.
 */
class LlamaBackend(
    private val threads: Int = defaultThreads(),
) : InferenceBackend {
    override val id = ID
    override val capabilities = BackendCapabilities(formats = setOf("gguf"), tasks = setOf(Task.CHAT))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val native = Dispatchers.IO.limitedParallelism(2)

    private class Model(override val descriptor: ModelDescriptor, val handle: Long) : LoadedModel {
        val lock = Mutex()
    }

    override suspend fun load(model: ModelDescriptor): LoadedModel = withContext(native) {
        val file = File(model.path)
        if (!file.isFile) throw Tune2AIException.ModelUnavailable("Model file not found: ${model.path}")
        if (!isGguf(file)) throw Tune2AIException.ModelUnavailable("Not a GGUF model file: ${model.path}")
        val handle = try {
            LlamaNative.load(file.path, model.contextLength, threads)
        } catch (e: UnsatisfiedLinkError) {
            throw Tune2AIException.BackendFailure("llama.cpp native library is missing for this device ABI", e)
        }
        if (handle == 0L) throw Tune2AIException.ModelUnavailable("llama.cpp could not load ${file.name} (unsupported architecture or not enough memory)")
        Model(model, handle)
    }

    override suspend fun unload(model: LoadedModel) {
        val m = model as Model
        m.lock.withLock { withContext(native) { LlamaNative.free(m.handle) } }
    }

    override fun generate(model: LoadedModel, request: GenerateRequest): Flow<String> = channelFlow {
        val m = model as Model
        m.lock.withLock {
            val prompt = withContext(native) {
                LlamaNative.applyTemplate(
                    m.handle,
                    request.messages.map { it.role.name.lowercase() }.toTypedArray(),
                    request.messages.map { it.content }.toTypedArray(),
                )
            } ?: throw Tune2AIException.ModelUnavailable("llama.cpp does not support the chat template of ${m.descriptor.name}")

            coroutineScope {
                // Flips the native cancel flag as soon as this request is cancelled, which also
                // interrupts prompt processing (via llama.cpp's abort callback), not only the token loop.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        LlamaNative.cancel(m.handle)
                    }
                }
                try {
                    val error = withContext(native) {
                        LlamaNative.generate(m.handle, prompt, request.maxTokens, request.temperature, Random.nextInt()) { bytes ->
                            trySendBlocking(String(bytes, Charsets.UTF_8)).isSuccess
                        }
                    }
                    error?.let { throw Tune2AIException.BackendFailure(it) }
                } finally {
                    watcher.cancelAndJoin() // before releasing the lock, so a late cancel cannot hit the next request
                }
            }
        }
    }

    companion object {
        const val ID = "llama.cpp"

        /**
         * Memory a GGUF model needs once loaded: weights (memory-mapped file) plus KV cache and buffers.
         * ponytail: rough estimate from file size; read n_layer/n_embd from GGUF metadata if it misjudges.
         */
        @JvmStatic
        fun estimateMemoryBytes(file: File, contextLength: Int): Long =
            file.length() + file.length() / 10 + contextLength * 64L * 1024

        // ponytail: fixed thread count; big.LITTLE-aware selection if real devices show contention.
        private fun defaultThreads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        private fun isGguf(file: File): Boolean = file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
        }
    }
}
