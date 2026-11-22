package com.tune2ai.sdk.llama

/** Receives generated text as UTF-8 bytes. Return false to stop generation. */
internal fun interface TokenSink {
    fun onPiece(bytes: ByteArray): Boolean
}

/** Hardware the app can ask for. The ordinal is what the native side receives. */
internal enum class HardwareMode { AUTO, CPU, GPU, NPU }

/** JNI surface of llama_jni.cpp. Handles are native Session pointers; 0 means failure. */
internal object LlamaNative {
    init {
        System.loadLibrary("tune2ai_llama")
    }

    /** Loads on the hardware chosen by [hardware] (see [HardwareMode]); falls back to CPU if it fails. 0 on failure. */
    @JvmStatic external fun load(path: String, contextLength: Int, threads: Int, hardware: Int): Long

    /** Why a model is not on the requested hardware (for example "GPU (Adreno 750) failed to start"); empty if it is. */
    @JvmStatic external fun backendNote(handle: Long): String

    /** True once the NPU/GPU threw during a request; the model must be reloaded on CPU. */
    @JvmStatic external fun acceleratorFailed(handle: Long): Boolean

    /** What a loaded model runs on, e.g. "CPU" or "GPU (Adreno 750)". */
    @JvmStatic external fun backendName(handle: Long): String

    /** NPUs/GPUs usable on this phone, best first. Empty means CPU only. */
    @JvmStatic external fun accelerators(): Array<String>

    /** The chat formatted with the model's own template, or null if llama.cpp does not support it. */
    @JvmStatic external fun applyTemplate(handle: Long, roles: Array<String>, contents: Array<String>): String?

    /** Blocks until done. Returns null on success or cancellation, else an error message. */
    @JvmStatic external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
        sink: TokenSink,
    ): String?

    /** Loads an embedding model (context outputs pooled embeddings). 0 on failure. */
    @JvmStatic external fun loadEmbedder(path: String, contextLength: Int, threads: Int, hardware: Int): Long

    /** L2-normalised embedding of [text], truncated to the context length; null on failure. */
    @JvmStatic external fun embed(handle: Long, text: String): FloatArray?

    @JvmStatic external fun cancel(handle: Long)

    @JvmStatic external fun free(handle: Long)
}
