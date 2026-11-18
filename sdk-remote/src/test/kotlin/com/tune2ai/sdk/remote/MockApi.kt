package com.tune2ai.sdk.remote

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** OpenAI-compatible test server: GET /v1/models and streaming POST /v1/chat/completions. */
class MockApi(
    val apiKey: String = "sk-test-secret",
    var models: List<String> = listOf("tiny"),
    var chunks: List<String> = listOf("Hello", ",", " world"),
    var chunkDelayMillis: Long = 0,
    var status: Int = 200,
) : AutoCloseable {
    @Volatile var lastBody: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.createContext("/v1/models") { ex ->
            val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $apiKey"
            val code = if (!ok) 401 else status
            val body = """{"object":"list","data":[${models.joinToString(",") { """{"id":"$it","object":"model"}""" }}]}""".toByteArray()
            ex.sendResponseHeaders(code, if (code == 200) body.size.toLong() else -1)
            if (code == 200) ex.responseBody.use { it.write(body) } else ex.close()
        }
        server.createContext("/v1/chat/completions") { ex ->
            lastBody = ex.requestBody.readBytes().decodeToString()
            val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $apiKey"
            val code = if (!ok) 401 else status
            if (code != 200) {
                ex.sendResponseHeaders(code, -1)
                ex.close()
                return@createContext
            }
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            // Like real servers, stop at max_tokens (one chunk per token here).
            val maxTokens = Regex(""""max_tokens":(\d+)""").find(lastBody!!)?.groupValues?.get(1)?.toInt() ?: Int.MAX_VALUE
            try {
                ex.responseBody.use { out ->
                    out.write(": keep-alive comment\n\n".toByteArray())
                    chunks.take(maxTokens).forEach { c ->
                        Thread.sleep(chunkDelayMillis)
                        val json = """{"choices":[{"index":0,"delta":{"content":${kotlinx.serialization.json.JsonPrimitive(c)}}}]}"""
                        out.write("data: $json\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    out.write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
                }
            } catch (_: Exception) {
                // client disconnected (cancellation tests)
            }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    override fun close() = server.stop(0)
}
