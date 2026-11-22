package com.tune2ai.sdk.services.rag

/**
 * Splits text into overlapping windows of about [windowTokens] tokens (T2AI-024.1), measured with
 * the same chars/4 estimate as the Context Manager. Breaks between words, never inside one, and
 * prefers to start a window at a paragraph.
 *
 * The default window fits small embedding models (bge-small takes 512 tokens) with room to spare,
 * and three retrieved chunks stay well inside a 4k chat context.
 */
class Chunker(private val windowTokens: Int = 200, private val overlapTokens: Int = 40) {
    init {
        require(windowTokens > 0 && overlapTokens in 0 until windowTokens) { "Need 0 <= overlap < window" }
    }

    fun chunk(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace(Regex("[ \t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
        if (normalized.isEmpty()) return emptyList()
        val windowChars = windowTokens * 4
        val overlapChars = overlapTokens * 4
        // Words keep their trailing separator so paragraphs survive inside a chunk.
        val words = Regex("\\S+\\s*").findAll(normalized).map { it.value }.toList()

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            var end = start
            var length = 0
            while (end < words.size && (end == start || length + words[end].length <= windowChars)) {
                length += words[end].length
                end++
            }
            chunks += words.subList(start, end).joinToString("").trim()
            if (end >= words.size) break
            // Step back by about overlapChars of words, but always move forward.
            var next = end
            var back = 0
            while (next > start + 1 && back + words[next - 1].length <= overlapChars) {
                back += words[next - 1].length
                next--
            }
            start = next
        }
        return chunks
    }
}
