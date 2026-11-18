package com.tune2ai.sdk.api

/**
 * Everything a chat, agent, or workflow request reports, as a stream.
 *
 * Contract (NFR-7):
 * - Every event carries the [requestId] of the request that produced it.
 * - A stream emits zero or more non-terminal events, then exactly one [Terminal] event
 *   ([Completed], [Cancelled], or [Failed]), then completes.
 * - Cancelling the collector stops the work. A request cancelled for any other reason ends with
 *   [Cancelled], never [Failed].
 *
 * Versioning (NFR-11): new event types may be added in minor versions, so `when` over this type
 * in application code should keep an `else` branch. Removing or renaming a type is a major change.
 */
sealed interface Tune2AIEvent {
    val requestId: RequestId

    /** The request reached a stage of the route. Drives the Studio route strip and trace. */
    data class Progress(override val requestId: RequestId, val stage: Stage, val detail: String? = null) : Tune2AIEvent

    /** A piece of generated text. Concatenate in order to get the full output. */
    data class TextDelta(override val requestId: RequestId, val text: String) : Tune2AIEvent

    /** The model asked to call a tool. [argumentsJson] has been validated against the tool's schema. */
    data class ToolCall(
        override val requestId: RequestId,
        val callId: String,
        val name: String,
        val argumentsJson: String,
    ) : Tune2AIEvent

    /** A tool finished. Failures and timeouts are results too ([isError]), not stream failures. */
    data class ToolResult(
        override val requestId: RequestId,
        val callId: String,
        val name: String,
        val resultJson: String,
        val isError: Boolean,
    ) : Tune2AIEvent

    /** Marker for the single event that ends every stream. */
    sealed interface Terminal : Tune2AIEvent

    data class Completed(override val requestId: RequestId, val usage: Usage) : Terminal

    data class Cancelled(override val requestId: RequestId) : Terminal

    data class Failed(override val requestId: RequestId, val error: Tune2AIException) : Terminal

    /** Route stages, in order. Names are stable (they cross the Studio bridge). */
    enum class Stage { RECEIVED, VALIDATED, ROUTED, CONTEXT_ASSEMBLED, GENERATING }
}
