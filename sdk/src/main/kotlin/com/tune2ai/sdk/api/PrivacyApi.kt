package com.tune2ai.sdk.api

import com.tune2ai.sdk.services.guard.PrivacyGuard
import com.tune2ai.sdk.services.guard.Redaction
import com.tune2ai.sdk.services.guard.SensitiveKind

/**
 * Guardrails for personal data (get it from `Tune2AI.privacy`). Both rules are on by default:
 *
 * - [redactSensitive]: one-time codes, card numbers, IDs, passwords, and keys in retrieved knowledge,
 *   app context sources, and agent tool results are replaced with placeholders before any model sees
 *   them, and the model is told never to reveal such values.
 * - [personalContextWithRemote] = false: when a request runs on a backend that needs the network
 *   (the Remote API), no knowledge or app context is attached at all, so personal data stays on
 *   the device. Only the user's own messages are sent.
 */
class PrivacyApi internal constructor(private val guard: PrivacyGuard) {
    @Volatile var redactSensitive: Boolean = true

    @Volatile var personalContextWithRemote: Boolean = false

    /** [text] with sensitive values hidden (always applied, whatever [redactSensitive] says). */
    fun redact(text: String): Redaction = guard.redact(text)

    /** Sensitive kinds found in [text], for example to refuse sending it somewhere. */
    fun find(text: String): Set<SensitiveKind> = guard.find(text)

    /** Applies [redact] only while [redactSensitive] is on. */
    internal fun apply(text: String): Redaction = if (redactSensitive) guard.redact(text) else Redaction(text, emptyMap())
}
