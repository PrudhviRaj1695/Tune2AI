package com.tune2ai.sdk.remote

import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.inference.BackendCapabilities
import com.tune2ai.sdk.inference.GenerateRequest
import com.tune2ai.sdk.inference.InferenceBackend
import com.tune2ai.sdk.inference.LoadedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Runs models on a remote server that speaks the OpenAI-compatible Chat Completions API
 * (OpenAI, vLLM, Ollama, llama.cpp server, and most hosted providers) — FR-INF-5.
 *
 * Register models with `format = RemoteBackend.FORMAT` and `path` = the server's model name.
 *
 * @param baseUrl API root, for example `https://api.example.com/v1`, read for every request so an app can
 *   change it at runtime. Must be HTTPS unless the host is local (localhost, 127.0.0.1, or 10.0.2.2,
 *   the emulator's alias for the development machine).
 * @param apiKey called for every request; the key is sent as a Bearer token and never stored or logged.
 * @param requireApiKey false for local servers that need no key.
 */
class RemoteBackend @JvmOverloads constructor(
    private val baseUrl: () -> String?,
    private val apiKey: () -> String?,
    override val id: String = "remote",
    private val requireApiKey: Boolean = true,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
) : InferenceBackend {

    /** A fixed API root; fails immediately if it is not allowed. */
    @JvmOverloads
    constructor(
        baseUrl: String,
        apiKey: () -> String?,
        id: String = "remote",
        requireApiKey: Boolean = true,
        connectTimeoutMillis: Int = 15_000,
        readTimeoutMillis: Int = 60_000,
    ) : this({ baseUrl }, apiKey, id, requireApiKey, connectTimeoutMillis, readTimeoutMillis) {
        validateBaseUrl(baseUrl)?.let { throw IllegalArgumentException(it) }
    }

    override val capabilities = BackendCapabilities(formats = setOf(FORMAT), tasks = setOf(Task.CHAT), requiresNetwork = true)

    override val unavailableReason: String?
        get() {
            val url = baseUrl()
            return when {
                url.isNullOrBlank() -> "No remote API URL configured"
                validateBaseUrl(url) != null -> validateBaseUrl(url)
                requireApiKey && apiKey().isNullOrBlank() -> "No API key configured"
                else -> null
            }
        }

    private class RemoteModel(override val descriptor: ModelDescriptor) : LoadedModel

    /** Confirms the server lists the model (GET /models). */
    override suspend fun load(model: ModelDescriptor): LoadedModel = withContext(Dispatchers.IO) {
        val conn = open("GET", "/models")
        try {
            checkStatus(conn, model)
            val ids = Json.parseToJsonElement(conn.inputStream.bufferedReader().readText())
                .jsonObject["data"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            if (model.path !in ids) throw Tune2AIException.ModelUnavailable("The remote API does not offer model '${model.path}'")
            RemoteModel(model)
        } catch (e: IOException) {
            throw networkError(e)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun unload(model: LoadedModel) = Unit

    override fun generate(model: LoadedModel, request: GenerateRequest): Flow<String> = channelFlow {
        val descriptor = (model as RemoteModel).descriptor
        val body = buildJsonObject {
            put("model", descriptor.path)
            put("stream", true)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            put("messages", buildJsonArray {
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role.name.lowercase())
                        put("content", m.content)
                    })
                }
            })
        }.toString()

        coroutineScope {
            val conn = withContext(Dispatchers.IO) { open("POST", "/chat/completions", body) }
            // Closing the connection is the only way to interrupt a blocking read when cancelled.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    conn.disconnect()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    checkStatus(conn, descriptor)
                    for (data in conn.inputStream.bufferedReader().sseData()) {
                        if (data == "[DONE]") break
                        delta(data)?.takeIf { it.isNotEmpty() }?.let { send(it) }
                    }
                }
            } catch (e: IOException) {
                ensureActive() // a cancelled request ends as cancelled, not as a network error
                throw networkError(e)
            } finally {
                watcher.cancel()
                conn.disconnect()
            }
        }
    }

    private fun open(method: String, path: String, body: String? = null): HttpURLConnection {
        val base = baseUrl()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: throw Tune2AIException.BackendFailure("No remote API URL configured")
        validateBaseUrl(base)?.let { throw Tune2AIException.BackendFailure(it) }
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMillis
        conn.readTimeout = readTimeoutMillis
        conn.setRequestProperty("Accept", if (body != null) "text/event-stream" else "application/json")
        apiKey()?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return conn
    }

    /** Maps HTTP failures to SDK categories. Messages never include the API key or response bodies. */
    private fun checkStatus(conn: HttpURLConnection, model: ModelDescriptor) {
        val code = conn.responseCode
        if (code in 200..299) return
        throw when (code) {
            401, 403 -> Tune2AIException.BackendFailure("The remote API rejected the API key (HTTP $code)")
            404 -> Tune2AIException.ModelUnavailable("The remote API does not offer model '${model.path}' (HTTP 404)")
            429 -> Tune2AIException.ResourceLimit("The remote API rate limit or quota was reached (HTTP 429)")
            else -> Tune2AIException.BackendFailure("The remote API returned HTTP $code")
        }
    }

    private fun networkError(e: IOException): Tune2AIException = when (e) {
        is SocketTimeoutException -> Tune2AIException.Timeout("The remote API did not respond in time")
        else -> Tune2AIException.BackendFailure("Could not reach the remote API: ${e.javaClass.simpleName}", e)
    }

    private fun delta(data: String): String? = runCatching {
        val choice = Json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        (choice?.get("delta") as? JsonObject)?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrElse { throw Tune2AIException.BackendFailure("The remote API sent an unreadable stream event") }

    companion object {
        /** `ModelDescriptor.format` for models served by this backend. */
        const val FORMAT = "remote"
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        /** Why [url] cannot be used as an API root, or null if it can. */
        @JvmStatic
        fun validateBaseUrl(url: String): String? {
            val parsed = runCatching { URL(url.trim()) }.getOrNull() ?: return "Not a valid URL"
            return when {
                parsed.protocol == "https" -> null
                parsed.protocol == "http" && parsed.host in LOCAL_HOSTS -> null
                else -> "Remote API must use HTTPS (plain HTTP is only allowed for local development hosts)"
            }
        }
    }
}
