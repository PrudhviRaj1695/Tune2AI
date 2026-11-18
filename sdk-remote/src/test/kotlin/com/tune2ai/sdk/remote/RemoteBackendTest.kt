package com.tune2ai.sdk.remote

import com.tune2ai.sdk.InferenceBackendContract
import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.inference.GenerateRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

private fun remoteModel(name: String) = ModelDescriptor(name, name, RemoteBackend.FORMAT, name, 0, 8192)

class RemoteBackendContractTest : InferenceBackendContract() {
    private val api = MockApi(chunks = List(2_000) { " tok" }, chunkDelayMillis = 2)
    override fun backend() = RemoteBackend(api.baseUrl, { api.apiKey })
    override fun validModel() = remoteModel("tiny")
    override fun brokenModel() = remoteModel("missing")

    @After fun stop() = api.close()
}

class RemoteBackendTest {
    private val api = MockApi()
    private val hello = GenerateRequest(listOf(ChatMessage.system("Be brief."), ChatMessage.user("Hi")), maxTokens = 64, temperature = 0.2f)

    @After fun stop() = api.close()

    private fun backend(key: String? = api.apiKey) = RemoteBackend(api.baseUrl, { key })

    private fun failure(block: suspend () -> Unit): Tune2AIException =
        assertThrows(Tune2AIException::class.java) { runBlocking { block() } }

    @Test
    fun streamsTextAndSendsAnOpenAiCompatibleRequest() = runBlocking {
        val backend = backend()
        val model = backend.load(remoteModel("tiny"))
        assertEquals("Hello, world", backend.generate(model, hello).toList().joinToString(""))
        val body = api.lastBody!!
        assertTrue(body, body.contains(""""stream":true""") && body.contains(""""max_tokens":64"""))
        assertTrue(body, body.contains(""""role":"system"""") && body.contains(""""model":"tiny""""))
    }

    @Test
    fun missingKeyMakesTheBackendUnavailable() {
        assertEquals("No API key configured", backend(key = null).unavailableReason)
        assertNull(RemoteBackend(api.baseUrl, { null }, requireApiKey = false).unavailableReason)
    }

    @Test
    fun httpFailuresMapToStableCategoriesWithoutLeakingTheKey() {
        val wrongKey = "sk-wrong-key-123"
        val auth = failure { backend(wrongKey).load(remoteModel("tiny")) }
        assertEquals(ErrorCategory.BACKEND_FAILURE, auth.category)
        assertTrue(auth.message!!.contains("API key"))
        assertFalse(auth.message!!.contains(wrongKey))

        api.status = 429
        assertEquals(ErrorCategory.RESOURCE_LIMIT, failure { backend().load(remoteModel("tiny")) }.category)
        api.status = 503
        assertEquals(ErrorCategory.BACKEND_FAILURE, failure { backend().load(remoteModel("tiny")) }.category)
    }

    @Test
    fun unreachableServerIsABackendFailure() {
        val dead = RemoteBackend("http://127.0.0.1:1/v1", { "k" }, connectTimeoutMillis = 2_000)
        assertEquals(ErrorCategory.BACKEND_FAILURE, failure { dead.load(remoteModel("tiny")) }.category)
    }

    @Test
    fun plainHttpIsRefusedForNonLocalHosts() {
        assertThrows(IllegalArgumentException::class.java) { RemoteBackend("http://api.example.com/v1", { "k" }) }
        RemoteBackend("https://api.example.com/v1", { "k" })
        RemoteBackend("http://10.0.2.2:8080/v1", { null }, requireApiKey = false)
        assertEquals("Not a valid URL", RemoteBackend.validateBaseUrl("not a url"))
    }

    @Test
    fun baseUrlCanChangeAtRuntime() = runBlocking {
        var url: String? = null
        val backend = RemoteBackend({ url }, { api.apiKey })
        assertEquals("No remote API URL configured", backend.unavailableReason)
        url = "http://example.com/v1"
        assertTrue(backend.unavailableReason!!.contains("HTTPS"))
        url = api.baseUrl
        assertNull(backend.unavailableReason)
        assertEquals("Hello, world", backend.generate(backend.load(remoteModel("tiny")), hello).toList().joinToString(""))
    }

    @Test
    fun sseParsingHandlesCommentsMultiLineDataAndLineEndings() {
        val stream = ": comment\r\ndata: a\r\n\r\nevent: x\ndata: b\ndata: c\n\nid: 1\rdata: d\r\rdata: tail"
        assertEquals(listOf("a", "b\nc", "d", "tail"), StringReader(stream).buffered().sseData().toList())
    }
}
