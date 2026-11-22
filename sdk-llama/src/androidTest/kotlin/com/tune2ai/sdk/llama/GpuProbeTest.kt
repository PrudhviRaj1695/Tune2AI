package com.tune2ai.sdk.llama

import android.os.Build
import android.system.Os
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Manual probe: loads a model on the GPU and generates, logging what happened. Skipped without a model. */
class GpuProbeTest {
    @Test
    fun gpuGenerates() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val args = InstrumentationRegistry.getArguments()
        val model = File(args.getString("model") ?: "/data/local/tmp/probe.gguf")
        assumeTrue(model.isFile)
        args.getString("ngl")?.let { Os.setenv("TUNE2AI_GPU_LAYERS", it, true) }
        args.getString("skip")?.let { Os.setenv("TUNE2AI_SKIP_BACKENDS", it, true) }
        args.getString("vkenv")?.split(',')?.filter { it.isNotBlank() }?.forEach { kv ->
            val (k, v) = kv.trim().split('=', limit = 2).let { it[0] to (it.getOrNull(1) ?: "1") }
            Os.setenv(k, v, true)
        }
        val handle = LlamaNative.load(model.path, 2048, 4, HardwareMode.GPU.ordinal)
        Log.i("GpuProbe", "env=${args.getString("vkenv")} backend=${LlamaNative.backendName(handle)} note=${LlamaNative.backendNote(handle)}")
        if (handle == 0L) return
        val prompt = LlamaNative.applyTemplate(handle, arrayOf("user"), arrayOf("Say hello in five words.")) ?: "Hello"
        val sb = StringBuilder()
        val err = LlamaNative.generate(handle, prompt, 24, 0f, 1) { sb.append(String(it)); true }
        Log.i("GpuProbe", "error=$err text=${sb}")
        LlamaNative.free(handle)
    }
}
