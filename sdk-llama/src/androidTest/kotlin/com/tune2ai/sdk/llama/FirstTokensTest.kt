package com.tune2ai.sdk.llama

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.Stage
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.core.RequestController
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.platform.android.AndroidPlatform
import com.tune2ai.sdk.services.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Milestone 2 outcome: a chat request streams real tokens from llama.cpp through
 * Request Controller → Router → Model Manager → inference, on a device.
 */
@RunWith(AndroidJUnit4::class)
class FirstTokensTest {

    @Test
    fun chatStreamsRealTokensThroughTheWholeRoute() = runBlocking<Unit> {
        val model = TestModel.descriptor()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        AndroidPlatform(InstrumentationRegistry.getInstrumentation().targetContext).use { platform ->
            try {
                val models = ModelManager(listOf(LlamaBackend()), InMemoryStorage(), platform.resources, ResourceLimits(), scope)
                models.register(model)
                val controller = RequestController(models, Router(models))

                val events = controller.chat(
                    listOf(ChatMessage.user("What is the capital of France? Answer in one sentence.")),
                    RequestOptions(maxTokens = 48, temperature = 0f, deadlineMillis = 120_000),
                ).toList()

                val terminal = events.last()
                assertTrue("expected Completed, got $terminal", terminal is Tune2AIEvent.Completed)
                assertEquals(Stage.entries, events.filterIsInstance<Tune2AIEvent.Progress>().map { it.stage })
                val text = events.filterIsInstance<Tune2AIEvent.TextDelta>().joinToString("") { it.text }
                assertTrue("expected generated text", text.isNotBlank())
                val usage = (terminal as Tune2AIEvent.Completed).usage
                val tps = usage.outputTokens * 1000.0 / (usage.totalMillis - (usage.firstTokenMillis ?: 0)).coerceAtLeast(1)
                Log.i("FirstTokens", "\"$text\" — ${usage.outputTokens} tokens, first token ${usage.firstTokenMillis} ms, ~${"%.1f".format(tps)} tok/s")
            } finally {
                scope.cancel()
            }
        }
    }
}
