package com.tune2ai.sdk.llama

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.services.embedding.cosine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * llama.cpp embeddings on a device (T2AI-023). Needs bge-small-en-v1.5 (37 MB), skipped otherwise:
 *   curl -L -o models/bge-small-en-v1.5-q8_0.gguf https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q8_0.gguf
 *   adb push models/bge-small-en-v1.5-q8_0.gguf /data/local/tmp/tune2ai-embed.gguf
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingDeviceTest {
    private fun descriptor(): ModelDescriptor {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(instrumentation.targetContext.filesDir, "tune2ai-embed.gguf")
        if (!target.isFile || target.length() == 0L) {
            val pfd = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/tune2ai-embed.gguf")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        assumeTrue("Push an embedding model to /data/local/tmp/tune2ai-embed.gguf", target.length() > 1_000_000)
        return ModelDescriptor("bge-small", "BGE small", "gguf", target.path, 100_000_000, 512, setOf(Task.EMBEDDING))
    }

    @Test
    fun similarTextsAreCloserThanUnrelatedOnes() = runBlocking<Unit> {
        val backend = LlamaBackend()
        val model = backend.load(descriptor())
        try {
            val (cat, kitten, tax) = backend.embed(model, listOf("The cat sat on the mat.", "A kitten is sitting on a rug.", "Quarterly tax filing deadlines"))
            assertEquals(cat.size, tax.size)
            assertTrue(cat.size >= 128)
            assertEquals(1.0, kotlin.math.sqrt(cat.sumOf { (it * it).toDouble() }), 1e-3) // normalised
            assertTrue("related texts should score higher", cosine(cat, kitten) > cosine(cat, tax))
            // Longer than the 512-token context: truncated, not an error.
            assertEquals(cat.size, backend.embed(model, listOf("word ".repeat(2_000))).single().size)
        } finally {
            backend.unload(model)
        }
    }
}
