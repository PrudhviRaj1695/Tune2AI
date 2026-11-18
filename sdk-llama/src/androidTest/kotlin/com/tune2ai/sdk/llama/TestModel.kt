package com.tune2ai.sdk.llama

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tune2ai.sdk.api.ModelDescriptor
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * The GGUF model used by device tests: SmolLM2-135M-Instruct Q8_0 (about 145 MB).
 * Push it once:  adb push models/SmolLM2-135M-Instruct-Q8_0.gguf /data/local/tmp/tune2ai-test.gguf
 * Tests copy it into app-private storage through the shell; without it they are skipped.
 */
object TestModel {
    private const val DEVICE_PATH = "/data/local/tmp/tune2ai-test.gguf"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val file: File by lazy {
        val target = File(context.filesDir, "tune2ai-test.gguf")
        if (!target.isFile || target.length() == 0L) {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $DEVICE_PATH")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        target
    }

    fun descriptor(): ModelDescriptor {
        assumeTrue("Push the test model first: adb push models/SmolLM2-135M-Instruct-Q8_0.gguf $DEVICE_PATH", file.length() > 1_000_000)
        return ModelDescriptor(
            id = "smollm2-135m",
            name = "SmolLM2 135M",
            format = "gguf",
            path = file.path,
            memoryBytes = LlamaBackend.estimateMemoryBytes(file, 2048),
            contextLength = 2048,
        )
    }

    fun broken(): ModelDescriptor {
        val bad = File(context.filesDir, "not-a-model.gguf").apply { writeText("this is not a GGUF file") }
        return ModelDescriptor("broken", "Broken", "gguf", bad.path, 1_000, 2048)
    }
}
