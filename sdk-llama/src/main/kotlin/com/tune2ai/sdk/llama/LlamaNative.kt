package com.tune2ai.sdk.llama

/** Receives generated text as UTF-8 bytes. Return false to stop generation. */
internal fun interface TokenSink {
    fun onPiece(bytes: ByteArray): Boolean
}

/** JNI surface of llama_jni.cpp. Handles are native Session pointers; 0 means failure. */
internal object LlamaNative {
    init {
        System.loadLibrary("tune2ai_llama")
    }

    @JvmStatic external fun load(path: String, contextLength: Int, threads: Int): Long

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
    @JvmStatic external fun loadEmbedder(path: String, contextLength: Int, threads: Int): Long

    /** L2-normalised embedding of [text], truncated to the context length; null on failure. */
    @JvmStatic external fun embed(handle: Long, text: String): FloatArray?

    @JvmStatic external fun cancel(handle: Long)

    @JvmStatic external fun free(handle: Long)
}
