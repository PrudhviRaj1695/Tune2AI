package com.tune2ai.sdk.llama

import java.io.File

/**
 * Remembers GPU/NPU work that ended the process. Some vendor drivers (seen with Adreno OpenCL) call exit() on a
 * GPU fault, so the app cannot catch it. A marker file is written before the risky call and deleted after it;
 * a marker found later means the process died inside, and that model is kept on CPU from then on.
 */
internal class GpuCrashGuard(private val dir: File) {
    private fun key(model: File) = model.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + model.length()
    private fun running(model: File) = File(dir, "running-" + key(model))
    private fun crashed(model: File) = File(dir, "crashed-" + key(model))

    /** True if an earlier GPU/NPU run of [model] ended the process. */
    fun crashedBefore(model: File): Boolean {
        val marker = running(model)
        if (marker.exists()) {
            crashed(model).also { it.parentFile?.mkdirs() }.writeText("")
            marker.delete()
        }
        return crashed(model).exists()
    }

    fun begin(model: File) {
        dir.mkdirs()
        running(model).writeText("")
    }

    fun end(model: File) {
        running(model).delete()
    }

    fun forgetAll() {
        dir.listFiles()?.forEach { if (it.name.startsWith("crashed-")) it.delete() }
    }
}

/** Runs [block] with a crash marker for [model] when [onGpu] and a guard is set. */
internal inline fun <T> GpuCrashGuard?.watching(model: File, onGpu: Boolean, block: () -> T): T {
    if (this == null || !onGpu) return block()
    begin(model)
    try {
        return block()
    } finally {
        end(model)
    }
}
