package com.tune2ai.sdk.services.rag

/** One chunk of a document and the section heading it sits under (null before the first heading). */
data class Piece(val text: String, val section: String?)

/**
 * Splits text into windows of about [windowTokens] tokens (T2AI-024.1), measured with the same
 * chars/4 estimate as the Context Manager, so retrieved passages are self-contained:
 *
 * - Whole paragraphs and sentences are packed together; a sentence is only cut when it alone is
 *   longer than a window.
 * - A Markdown heading starts a new chunk (unless the current one is still small), and every chunk
 *   remembers the heading it belongs to.
 * - Consecutive chunks overlap by up to [overlapTokens] of trailing sentences, so a fact that
 *   straddles a boundary is still found whole.
 *
 * The default window fits small embedding models (bge-small takes 512 tokens) with room for the
 * title and section prefix, and a few passages stay well inside a 4k chat context.
 */
class Chunker(private val windowTokens: Int = 200, private val overlapTokens: Int = 40) {
    init {
        require(windowTokens > 0 && overlapTokens in 0 until windowTokens) { "Need 0 <= overlap < window" }
    }

    private val windowChars get() = windowTokens * 4
    private val overlapChars get() = overlapTokens * 4

    fun chunk(text: String): List<String> = pieces(text).map { it.text }

    fun pieces(text: String): List<Piece> {
        val normalized = text.replace("\r\n", "\n").replace('\t', ' ').replace(Regex(" {2,}"), " ").trim()
        if (normalized.isEmpty()) return emptyList()

        val out = mutableListOf<Piece>()
        var current = mutableListOf<Unit>() // sentences of the chunk being built
        var section: String? = null
        var chunkSection: String? = null

        fun length(units: List<Unit>) = units.sumOf { it.text.length + 2 }
        fun flush(keepOverlap: Boolean) {
            if (current.isEmpty()) return
            out += Piece(join(current), chunkSection)
            current = if (keepOverlap) tail(current) else mutableListOf()
            chunkSection = section
        }

        for (paragraph in normalized.split(Regex("\n\\s*\n"))) {
            val lines = paragraph.trim().lines()
            val heading = HEADING.matchEntire(lines.first().trim())?.groupValues?.get(1)?.trim()
            if (heading != null) {
                if (length(current) > windowChars / 4) flush(keepOverlap = false)
                section = heading
                if (current.isEmpty()) chunkSection = heading
            }
            sentences(paragraph.trim()).forEachIndexed { i, (sentence, startsLine) ->
                splitLong(sentence).forEachIndexed { j, part ->
                    if (current.isNotEmpty() && length(current) + part.length > windowChars) flush(keepOverlap = true)
                    if (current.isEmpty()) chunkSection = section
                    current += Unit(part, newParagraph = i == 0 && j == 0, newLine = startsLine && j == 0)
                }
            }
        }
        flush(keepOverlap = false)
        return out
    }

    /** A sentence; [newLine] keeps list items, table rows, and code lines on their own lines. */
    private class Unit(val text: String, val newParagraph: Boolean, val newLine: Boolean = false)

    private fun join(units: List<Unit>): String = buildString {
        units.forEachIndexed { i, u ->
            if (i > 0) append(if (u.newParagraph) "\n\n" else if (u.newLine) "\n" else " ")
            append(u.text)
        }
    }.trim()

    /** Trailing sentences worth at most [overlapChars], carried into the next chunk. */
    private fun tail(units: List<Unit>): MutableList<Unit> {
        val kept = ArrayDeque<Unit>()
        var size = 0
        for (u in units.asReversed()) {
            if (size + u.text.length > overlapChars || kept.size == units.size - 1) break
            kept.addFirst(u)
            size += u.text.length
        }
        return kept.mapIndexed { i, u -> if (i == 0) Unit(u.text, newParagraph = false) else u }.toMutableList()
    }

    /** Sentences of a paragraph, each flagged when it starts a new line. */
    private fun sentences(paragraph: String): List<Pair<String, Boolean>> =
        paragraph.lines().flatMapIndexed { lineNo, line ->
            line.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
                .mapIndexed { k, sentence -> sentence to (lineNo > 0 && k == 0) }
        }

    /** Cuts a single over-long sentence (or table row, or code line run) between words. */
    private fun splitLong(sentence: String): List<String> {
        if (sentence.length <= windowChars) return listOf(sentence)
        val words = Regex("\\S+\\s*").findAll(sentence).map { it.value }.toList()
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        for (w in words) {
            if (sb.isNotEmpty() && sb.length + w.length > windowChars) {
                parts += sb.toString().trim()
                sb.setLength(0)
            }
            sb.append(w)
        }
        if (sb.isNotBlank()) parts += sb.toString().trim()
        return parts
    }

    private companion object {
        val HEADING = Regex("^#{1,6}\\s+(.+)$")
        // Whitespace after . ! ? or after . ! ? plus a closing quote/bracket. Fixed-length lookbehinds
        // only: Android's ICU regex engine is stricter than the JVM's (see T2AI-021.3).
        val SENTENCE_END = Regex("(?<=[.!?])\\s+|(?<=[.!?][\"')\\]])\\s+")
    }
}
