package com.tune2ai.sdk.services.rag

/**
 * Splits text into overlapping windows of about [sizeTokens] tokens, each sharing [overlapTokens] with
 * the previous one so a sentence cut at a boundary still appears whole in one chunk. Windows end on
 * whitespace where possible.
 * ponytail: token counts use the same ~4 characters per token estimate as the Context Manager; use the
 * embedding model's tokenizer if chunks overrun its input limit.
 */
class Chunker(private val sizeTokens: Int = 200, private val overlapTokens: Int = 40) {
    init {
        require(sizeTokens > 0) { "Chunk size must be positive" }
        require(overlapTokens in 0 until sizeTokens) { "Overlap must be at least 0 and smaller than the chunk size" }
    }

    fun chunk(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val size = sizeTokens * CHARS_PER_TOKEN
        val overlap = overlapTokens * CHARS_PER_TOKEN
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + size, clean.length)
            if (end < clean.length) {
                val space = clean.lastIndexOf(' ', end)
                if (space > start + size / 2) end = space // snap to a word boundary unless that wastes half the window
            }
            clean.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { chunks += it }
            if (end >= clean.length) break
            start = maxOf(end - overlap, start + 1) // always advance
            if (start > 0 && !clean[start - 1].isWhitespace()) {
                val space = clean.indexOf(' ', start)
                if (space in start until end) start = space + 1 // do not start mid-word
            }
        }
        return chunks
    }

    private companion object {
        const val CHARS_PER_TOKEN = 4 // matches estimateTokens in the Context Manager
    }
}
