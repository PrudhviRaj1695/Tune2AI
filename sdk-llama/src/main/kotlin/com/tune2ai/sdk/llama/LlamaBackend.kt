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
 * Runs GGUF models on the device through llama.cpp (FR-INF-2): on the NPU or GPU when one is available
 * and works, otherwise on the CPU. Pass [useAccelerator] = false to force CPU.
 *
 * Native calls block, so they run on a dedicated thread pool; one generation at a time per model.
 */
class LlamaBackend @JvmOverloads constructor(
    private val threads: Int = defaultThreads(),
    private val useAccelerator: Boolean = true,
    /**
     * Where to remember GPU/NPU crashes (for example `context.filesDir`). Some GPU drivers end the whole process
     * on a fault, which no exception handler can catch; with this set, a model that crashed the GPU once runs on
     * CPU after the app restarts instead of crashing again. Null disables the guard.
     */
    stateDir: File? = null,
) : InferenceBackend {
    private val guard = stateDir?.let { GpuCrashGuard(File(it, "tune2ai-gpu")).also { g -> guards += g } }
    override val id = ID
    override val capabilities = BackendCapabilities(formats = setOf("gguf"), tasks = setOf(Task.CHAT, Task.EMBEDDING))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val native = Dispatchers.IO.limitedParallelism(2)

    private class Model(override val descriptor: ModelDescriptor, var handle: Long, val embedder: Boolean, val path: String) : LoadedModel {
        val lock = Mutex()
    }

    /** Loads [file] on the current hardware choice and records what it ended up on. 0 on failure. */
    private fun open(model: ModelDescriptor, file: File, embedder: Boolean, mode: HardwareMode): Long {
        var m = if (useAccelerator) mode else HardwareMode.CPU
        val crashed = m != HardwareMode.CPU && guard?.crashedBefore(file) == true
        if (crashed) m = HardwareMode.CPU
        val handle = guard.watching(file, m != HardwareMode.CPU) {
            if (embedder) LlamaNative.loadEmbedder(file.path, model.contextLength, threads, m.ordinal)
            else LlamaNative.load(file.path, model.contextLength, threads, m.ordinal)
        }
        if (handle != 0L) {
            hardwareByModel[model.id] = LlamaNative.backendName(handle)
            val note = if (crashed) "The GPU crashed the app with this model before, so it runs on the CPU. Choose GPU to try again"
            else LlamaNative.backendNote(handle)
            if (note.isEmpty()) noteByModel.remove(model.id) else noteByModel[model.id] = note
        }
        return handle
    }

    private fun onAccelerator(m: Model) = hardwareByModel[m.descriptor.id]?.let { it != "CPU" } == true

    /**
     * The NPU/GPU threw while serving a request. Rebuilds the model on CPU so this and later requests work.
     * Called with the model lock held.
     */
    private fun recoverOnCpu(m: Model) {
        LlamaNative.free(m.handle)
        m.handle = open(m.descriptor, File(m.path), m.embedder, HardwareMode.CPU)
        if (m.handle == 0L) throw Tune2AIException.BackendFailure("The GPU/NPU failed and ${m.descriptor.name} could not reload on the CPU")
        noteByModel[m.descriptor.id] = "The GPU/NPU stopped working, so this model moved to the CPU"
    }

    override suspend fun load(model: ModelDescriptor): LoadedModel = withContext(native) {
        val file = File(model.path)
        if (!file.isFile) throw Tune2AIException.ModelUnavailable("Model file not found: ${model.path}")
        if (!isGguf(file)) throw Tune2AIException.ModelUnavailable("Not a GGUF model file: ${model.path}")
        // A model registered only for EMBEDDING gets an embeddings context; anything else a chat context.
        val embedder = Task.EMBEDDING in model.tasks && Task.CHAT !in model.tasks
        val handle = try {
            open(model, file, embedder, preference)
        } catch (e: UnsatisfiedLinkError) {
            throw Tune2AIException.BackendFailure("llama.cpp native library is missing for this device ABI", e)
        }
        if (handle == 0L) throw Tune2AIException.ModelUnavailable("llama.cpp could not load ${file.name} (unsupported architecture or not enough memory)")
        Model(model, handle, embedder, file.path)
    }

    override suspend fun unload(model: LoadedModel) {
        val m = model as Model
        hardwareByModel.remove(m.descriptor.id)
        noteByModel.remove(m.descriptor.id)
        m.lock.withLock { withContext(native) { LlamaNative.free(m.handle) } }
    }

    override suspend fun embed(model: LoadedModel, texts: List<String>): List<FloatArray> {
        val m = model as Model
        if (!m.embedder) throw Tune2AIException.NoCompatibleRoute("${m.descriptor.name} is a chat model; register it with Task.EMBEDDING to embed")
        return m.lock.withLock {
            withContext(native) {
                texts.map { text ->
                    (LlamaNative.embed(m.handle, text)
                        ?: if (LlamaNative.acceleratorFailed(m.handle)) { recoverOnCpu(m); LlamaNative.embed(m.handle, text) } else null)
                        ?: throw Tune2AIException.BackendFailure("llama.cpp could not embed text with ${m.descriptor.name}")
                }
            }
        }
    }

    override fun generate(model: LoadedModel, request: GenerateRequest): Flow<String> = channelFlow {
        val m = model as Model
        if (m.embedder) throw Tune2AIException.NoCompatibleRoute("${m.descriptor.name} is an embedding model and cannot chat")
        m.lock.withLock {
            var attempt = 0
            while (true) {
                var emitted = false
                val prompt = withContext(native) {
                    LlamaNative.applyTemplate(
                        m.handle,
                        request.messages.map { it.role.name.lowercase() }.toTypedArray(),
                        request.messages.map { it.content }.toTypedArray(),
                    )
                } ?: throw Tune2AIException.ModelUnavailable("llama.cpp does not support the chat template of ${m.descriptor.name}")

                val error = coroutineScope {
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
                        withContext(native) {
                            guard.watching(File(m.path), onAccelerator(m)) {
                                LlamaNative.generate(m.handle, prompt, request.maxTokens, request.temperature, Random.nextInt()) { bytes ->
                                    emitted = true
                                    trySendBlocking(String(bytes, Charsets.UTF_8)).isSuccess
                                }
                            }
                        }
                    } finally {
                        watcher.cancelAndJoin() // before releasing the lock, so a late cancel cannot hit the next request
                    }
                }
                if (error == null) break
                if (error == ACCELERATOR_FAILED) {
                    withContext(native) { recoverOnCpu(m) }
                    // Nothing was shown yet: run the same request again on the CPU.
                    if (!emitted && attempt++ == 0) continue
                    throw Tune2AIException.BackendFailure("The GPU/NPU stopped working mid-answer. The model moved to the CPU; send the message again.")
                }
                throw Tune2AIException.BackendFailure(error)
            }
        }
    }

    companion object {
        const val ID = "llama.cpp"

        private const val ACCELERATOR_FAILED = "accelerator-failed"
        private val hardwareByModel = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val noteByModel = java.util.concurrent.ConcurrentHashMap<String, String>()

        @Volatile
        private var preference = HardwareMode.AUTO

        /**
         * Hardware to run new model loads on: "auto" (NPU, then GPU, then CPU), "cpu", "gpu" or "npu".
         * A choice the phone cannot honour falls back to CPU. Takes effect when a model is next loaded.
         * ponytail: one choice for all models; make it per model if apps need to mix.
         */
        @JvmStatic
        var hardware: String
            get() = preference.name.lowercase()
            set(value) { preference = HardwareMode.entries.firstOrNull { it.name.equals(value, true) } ?: HardwareMode.AUTO }

        private val guards = java.util.concurrent.CopyOnWriteArrayList<GpuCrashGuard>()

        /** Forgets every model that crashed the GPU, so the next load tries the GPU/NPU again. */
        @JvmStatic
        fun forgetGpuCrashes() = guards.forEach { it.forgetAll() }

        /** Why a loaded model is not on the requested hardware, or null. */
        @JvmStatic
        fun hardwareNoteOf(modelId: String): String? = noteByModel[modelId]

        /** What a loaded model runs on, e.g. "CPU" or "GPU (Adreno 750)"; null when it is not loaded. */
        @JvmStatic
        fun hardwareOf(modelId: String): String? = hardwareByModel[modelId]

        /** NPUs/GPUs usable on this phone, best first (e.g. "GPU (Adreno 750)"); empty means CPU only. */
        @JvmStatic
        fun availableAccelerators(): List<String> =
            try { LlamaNative.accelerators().toList() } catch (e: UnsatisfiedLinkError) { emptyList() }

        /**
         * Memory a GGUF model needs once loaded: weights (memory-mapped file) plus KV cache and buffers.
         * ponytail: rough estimate from file size; read n_layer/n_embd from GGUF metadata if it misjudges.
         */
        @JvmStatic
        fun estimateMemoryBytes(file: File, contextLength: Int): Long =
            file.length() + file.length() / 10 + contextLength * 64L * 1024

        /**
         * Half the cores, at most 4. ggml's worker threads spin-wait on each other, so using every
         * core lets UI rendering stall the whole pool: on a 4-core device with Studio's WebView,
         * 4 threads ran at 0.6 tok/s.
         * ponytail: core-count heuristic; pin to big cores if real devices show contention.
         */
        @JvmStatic
        fun defaultThreads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

        private fun isGguf(file: File): Boolean = file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
        }
    }
}
