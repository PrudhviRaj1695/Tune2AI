package com.tune2ai.sdk.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.inference.GenerateRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** KV prompt caching must change speed only, never output. Greedy decoding makes output comparable. */
@RunWith(AndroidJUnit4::class)
class PromptCacheTest {
    private fun request(vararg messages: ChatMessage) = GenerateRequest(messages.toList(), maxTokens = 24, temperature = 0f)

    @Test
    fun cachedPromptsProduceIdenticalOutput() = runBlocking<Unit> {
        val backend = LlamaBackend()
        val model = backend.load(TestModel.descriptor())
        try {
            val a = request(ChatMessage.system("Be brief."), ChatMessage.user("Name three colors."))
            val b = request(ChatMessage.system("Be brief."), ChatMessage.user("Name three animals."))
            suspend fun run(r: GenerateRequest) = backend.generate(model, r).toList().joinToString("")

            val cold = run(a)
            val warm = run(a)            // whole prompt cached except the last token
            val other = run(b)           // shares the system prefix, diverges after it
            val again = run(a)           // cache rewound to the shared prefix, then re-extended
            assertTrue(cold.isNotBlank())
            assertEquals(cold, warm)
            assertEquals(cold, again)
            assertTrue(other.isNotBlank())
        } finally {
            backend.unload(model)
        }
    }
}
