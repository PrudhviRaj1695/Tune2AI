package com.tune2ai.sdk.api

import kotlin.random.Random

/** Identifies one request across every event and error it produces (NFR-12). */
data class RequestId(val value: String) {
    override fun toString() = value

    companion object {
        @JvmStatic
        fun random(): RequestId = RequestId("req_" + Random.nextLong().toULong().toString(16).padStart(16, '0'))
    }
}

/** Options shared by chat, agent, and workflow requests. Null preferences mean automatic selection. */
data class RequestOptions(
    val model: String? = null,
    val backend: String? = null,
    val deadlineMillis: Long = 30_000,
    val maxTokens: Int = 2048,
    val temperature: Float = 0.7f,
)

/** Token and timing figures reported when a request completes. */
data class Usage(
    val outputTokens: Int,
    val firstTokenMillis: Long?,
    val totalMillis: Long,
)
