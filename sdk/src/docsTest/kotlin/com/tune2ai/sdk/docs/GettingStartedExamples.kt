package com.tune2ai.sdk.docs

import android.content.Context
import com.tune2ai.sdk.Tune2AI
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.inference.InferenceBackend

fun createSdk(context: Context, backend: InferenceBackend): Tune2AI =
    Tune2AI.Builder(context)
        .addBackend(backend)
        .resourceLimits(ResourceLimits(minFreeMemoryBytes = 512L * 1024 * 1024))
        .build()

suspend fun registerModel(ai: Tune2AI, ggufPath: String, fileSizeBytes: Long) {
    ai.models.register(
        ModelDescriptor(
            id = "qwen2.5-0.5b",
            name = "Qwen2.5 0.5B Instruct",
            format = "gguf",
            path = ggufPath,
            memoryBytes = fileSizeBytes + fileSizeBytes / 10,
            contextLength = 4096,
        ),
    )
}
