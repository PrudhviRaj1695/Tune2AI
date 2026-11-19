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

/**
 * Options shared by chat, agent, and workflow requests. Null preferences mean automatic selection.
 *
 * @param system a free-text system prompt that overrides the default/named template when non-blank.
 * @param promptTemplate id of a registered prompt template to use as the system prompt; null uses the default.
 * @param promptVariables values for the template's declared variables.
 */
data class RequestOptions @JvmOverloads constructor(
    val model: String? = null,
    val backend: String? = null,
    val deadlineMillis: Long = 120_000,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val system: String? = null,
    val promptTemplate: String? = null,
    val promptVariables: Map<String, String> = emptyMap(),
)

/** Token and timing figures reported when a request completes. */
data class Usage(
    val outputTokens: Int,
    val firstTokenMillis: Long?,
    val totalMillis: Long,
)
