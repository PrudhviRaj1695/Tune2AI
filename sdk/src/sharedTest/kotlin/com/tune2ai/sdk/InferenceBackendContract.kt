package com.tune2ai.sdk

import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.inference.GenerateRequest
import com.tune2ai.sdk.inference.InferenceBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Behaviour every [InferenceBackend] must have (T2AI-010). Backends run it with a real model:
 * the JVM suite uses FakeBackend, sdk-llama runs it on a device with a GGUF file.
 * Uses real time (runBlocking), because cancellation latency is measured in wall-clock time.
 */
abstract class InferenceBackendContract {
    abstract fun backend(): InferenceBackend
    abstract fun validModel(): ModelDescriptor

    /** A descriptor whose file exists but is not a valid model. */
    abstract fun brokenModel(): ModelDescriptor

    private val prompt = GenerateRequest(listOf(ChatMessage.user("Count from one to fifty.")), maxTokens = 32, temperature = 0f)

    @Test
    fun loadGenerateUnload() = runBlocking {
        val backend = backend()
        val model = backend.load(validModel())
        try {
            val pieces = backend.generate(model, prompt).toList()
            assertTrue("expected output", pieces.joinToString("").isNotBlank())
            assertTrue("respects maxTokens", pieces.size <= prompt.maxTokens)
        } finally {
            backend.unload(model)
        }
    }

    @Test
    fun cancellationStopsWithin500ms() = runBlocking {
        val backend = backend()
        val model = backend.load(validModel())
        try {
            val producing = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                backend.generate(model, prompt.copy(maxTokens = 2_000)).collect { producing.complete(Unit) }
            }
            withTimeout(60_000) { producing.await() }
            val start = System.nanoTime()
            job.cancel()
            withTimeout(5_000) { job.join() }
            val millis = (System.nanoTime() - start) / 1_000_000
            assertTrue("cancel took $millis ms", millis < 500)
        } finally {
            backend.unload(model)
        }
    }

    @Test
    fun brokenModelFailsWithModelUnavailable() = runBlocking {
        try {
            backend().load(brokenModel())
            fail("loading a broken model must throw")
        } catch (e: Tune2AIException) {
            assertEquals(ErrorCategory.MODEL_UNAVAILABLE, e.category)
        }
    }
}
