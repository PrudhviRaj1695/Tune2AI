package com.tune2ai.sdk.services.guard

/** Kinds of sensitive data the guard recognises. Names are shown to users and in traces. */
enum class SensitiveKind(val label: String) {
    ONE_TIME_CODE("one-time code"),
    CARD_NUMBER("card number"),
    CARD_SECURITY_CODE("card security code"),
    BANK_ACCOUNT("bank account number"),
    GOVERNMENT_ID("ID number"),
    PASSWORD("password"),
    SECRET_KEY("secret key or token"),
}

/** [text] with sensitive values replaced, and how many of each kind were hidden. */
data class Redaction(val text: String, val counts: Map<SensitiveKind, Int>) {
    val total: Int get() = counts.values.sum()
}

/**
 * Finds and hides sensitive values in text before it reaches a model (NFR-2 extended to content):
 * one-time codes, card numbers (Luhn-checked) and security codes, bank account numbers, government
 * IDs (Aadhaar with its Verhoeff check digit, PAN, US SSN, IBAN), passwords, and API keys or tokens.
 * Each value becomes a placeholder such as `[hidden one-time code]`, so the model still knows
 * something was there but cannot repeat it.
 *
 * Plain regular expressions with fixed-length lookarounds only (Android's ICU engine is stricter
 * than the JVM's; see T2AI-021.3). ponytail: pattern-based, so it misses values with no recognisable
 * shape or label and can hide the odd harmless number; add an on-device classifier if that matters.
 */
class PrivacyGuard {

    fun redact(text: String): Redaction {
        if (text.isEmpty()) return Redaction(text, emptyMap())
        val counts = LinkedHashMap<SensitiveKind, Int>()
        var out = text
        fun hide(kind: SensitiveKind) = "[hidden ${kind.label}]"
        fun count(kind: SensitiveKind) {
            counts[kind] = (counts[kind] ?: 0) + 1
        }

        // Secrets first: they can contain digit runs that look like other kinds.
        for (p in SECRET_PATTERNS) out = p.replace(out) { count(SensitiveKind.SECRET_KEY); hide(SensitiveKind.SECRET_KEY) }
        out = PRIVATE_KEY.replace(out) { count(SensitiveKind.SECRET_KEY); hide(SensitiveKind.SECRET_KEY) }
        out = PASSWORD.replace(out) { m -> count(SensitiveKind.PASSWORD); m.groupValues[1] + m.groupValues[2] + hide(SensitiveKind.PASSWORD) }
        out = OTP_BEFORE.replace(out) { m -> count(SensitiveKind.ONE_TIME_CODE); m.groupValues[1] + hide(SensitiveKind.ONE_TIME_CODE) }
        out = OTP_AFTER.replace(out) { m -> count(SensitiveKind.ONE_TIME_CODE); hide(SensitiveKind.ONE_TIME_CODE) + m.groupValues[2] }
        out = CVV.replace(out) { m -> count(SensitiveKind.CARD_SECURITY_CODE); m.groupValues[1] + hide(SensitiveKind.CARD_SECURITY_CODE) }
        out = CARD.replace(out) { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length in 13..19 && luhn(digits)) {
                count(SensitiveKind.CARD_NUMBER)
                hide(SensitiveKind.CARD_NUMBER)
            } else {
                m.value
            }
        }
        out = AADHAAR.replace(out) { m ->
            if (verhoeff(m.value.filter(Char::isDigit))) {
                count(SensitiveKind.GOVERNMENT_ID)
                hide(SensitiveKind.GOVERNMENT_ID)
            } else {
                m.value
            }
        }
        for (p in ID_PATTERNS) out = p.replace(out) { count(SensitiveKind.GOVERNMENT_ID); hide(SensitiveKind.GOVERNMENT_ID) }
        out = ACCOUNT.replace(out) { m -> count(SensitiveKind.BANK_ACCOUNT); m.groupValues[1] + hide(SensitiveKind.BANK_ACCOUNT) }
        return Redaction(out, counts)
    }

    /** The kinds of sensitive data in [text], without changing it. */
    fun find(text: String): Set<SensitiveKind> = redact(text).counts.keys

    companion object {
        private const val I = "(?i)"

        // "OTP is 482913", "verification code: 4829", "Use 482913 as your one-time password"
        private val OTP_BEFORE = Regex(
            "$I(\\b(?:otp|one[- ]time (?:pass(?:word|code)|code)|verification code|security code|login code|" +
                "auth(?:entication)? code|confirmation code|passcode|code)\\b[^0-9\\n]{0,25})\\d{4,8}\\b",
        )
        private val OTP_AFTER = Regex(
            "$I\\b(\\d{4,8})(\\s+(?:is|as)\\s+(?:your|the)\\s+(?:otp|one[- ]time|verification|security|login|confirmation)\\b)",
        )
        private val CVV = Regex("$I(\\b(?:cvv2?|cvc2?|card security code)\\b[^0-9\\n]{0,10})\\d{3,4}\\b")
        private val CARD = Regex("\\b\\d(?:[ -]?\\d){12,18}\\b")
        private val AADHAAR = Regex("\\b[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}\\b")
        private val ID_PATTERNS = listOf(
            Regex("\\b[A-Z]{3}[ABCFGHLJPT][A-Z]\\d{4}[A-Z]\\b"), // India PAN
            Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"), // US SSN
            Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b"), // IBAN
        )
        // "A/c no. 123456789012", "account number: 0012345678" (masked forms like XX1234 are left alone)
        private val ACCOUNT = Regex("$I(\\b(?:a/c|acct|account)(?:\\s*(?:no\\.?|number|#))?[\\s:#.-]{0,4})\\d{6,18}\\b")
        // "password: hunter2", "Wi-Fi password is pelican42", "PIN - 4821"
        private val PASSWORD = Regex("$I(\\b(?:password|passwd|pwd|passphrase|wi-?fi key|pin)\\b)(\\s*(?:is|:|=|-)\\s*)[^\\s,;]+")
        private val SECRET_PATTERNS = listOf(
            Regex("\\b(?:sk|pk|rk)-(?:live-|test-|proj-)?[A-Za-z0-9_-]{16,}"),
            Regex("\\bAKIA[0-9A-Z]{16}\\b"),
            Regex("\\bAIza[0-9A-Za-z_-]{35}"),
            Regex("\\bgh[pousr]_[A-Za-z0-9]{30,}"),
            Regex("\\bxox[abposr]-[A-Za-z0-9-]{10,}"),
            Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
        )
        private val PRIVATE_KEY = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")

        internal fun luhn(digits: String): Boolean {
            var sum = 0
            digits.reversed().forEachIndexed { i, c ->
                var d = c - '0'
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return sum % 10 == 0
        }

        private val D = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
            intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
            intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
            intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
            intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
        )
        private val P = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
            intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
            intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
            intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
        )

        /** Verhoeff checksum, used by Aadhaar numbers; keeps ordinary 12-digit numbers visible. */
        internal fun verhoeff(digits: String): Boolean {
            var c = 0
            digits.reversed().forEachIndexed { i, ch -> c = D[c][P[i % 8][ch - '0']] }
            return c == 0
        }
    }
}
