package com.tune2ai.sdk.services.rag

/** One chunk of a document and the section heading it sits under (null before the first heading). */
data class Piece(val text: String, val section: String?)

/** How a [ChunkingStrategy] decides where chunks end. */
enum class ChunkingMode {
    /** Whole sentences and paragraphs, a new chunk at headings once the current one has some text. */
    SENTENCES,

    /** One chunk per Markdown section (split further only when a section is longer than the window). */
    SECTIONS,

    /** Fixed-size word windows that ignore sentences and headings. The classic baseline. */
    FIXED,
}

/**
 * How documents are split before indexing. Smaller windows find exact facts more precisely; larger
 * ones give the model more surrounding context per passage. Changing it applies to new documents;
 * `KnowledgeApi.rechunk` re-splits existing ones.
 */
data class ChunkingStrategy(val id: String, val mode: ChunkingMode, val windowTokens: Int, val overlapTokens: Int) {
    init {
        require(windowTokens > 0 && overlapTokens in 0 until windowTokens) { "Need 0 <= overlap < window" }
    }

    companion object {
        /** Default: paragraph- and sentence-aware ~200-token chunks with a sentence of overlap. */
        @JvmField val SMART = ChunkingStrategy("smart", ChunkingMode.SENTENCES, 200, 40)

        /** Small chunks for exact facts (numbers, names, dates) in dense documents. */
        @JvmField val PRECISE = ChunkingStrategy("precise", ChunkingMode.SENTENCES, 100, 20)

        /** Large chunks for explanations that need surrounding context. Fits bge-small's 512 tokens. */
        @JvmField val BROAD = ChunkingStrategy("broad", ChunkingMode.SENTENCES, 380, 60)

        /** Whole sections of structured documents (Markdown headings, PDF pages, calendar days). */
        @JvmField val SECTIONS = ChunkingStrategy("sections", ChunkingMode.SECTIONS, 380, 0)

        /** Fixed word windows: predictable sizes for logs, CSV, and text without sentences. */
        @JvmField val FIXED = ChunkingStrategy("fixed", ChunkingMode.FIXED, 200, 40)

        @JvmField val ALL = listOf(SMART, PRECISE, BROAD, SECTIONS, FIXED)

        @JvmStatic fun of(id: String): ChunkingStrategy = ALL.firstOrNull { it.id == id } ?: SMART
    }
}

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
class Chunker(val strategy: ChunkingStrategy = ChunkingStrategy.SMART) {
    constructor(windowTokens: Int, overlapTokens: Int) :
        this(ChunkingStrategy("custom", ChunkingMode.SENTENCES, windowTokens, overlapTokens))

    private val windowChars get() = strategy.windowTokens * 4
    private val overlapChars get() = strategy.overlapTokens * 4

    fun chunk(text: String): List<String> = pieces(text).map { it.text }

    fun pieces(text: String): List<Piece> {
        val normalized = text.replace("\r\n", "\n").replace('\t', ' ').replace(Regex(" {2,}"), " ").trim()
        if (normalized.isEmpty()) return emptyList()
        if (strategy.mode == ChunkingMode.FIXED) return fixedWindows(normalized)

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
                val sectionsMode = strategy.mode == ChunkingMode.SECTIONS
                if (if (sectionsMode) current.isNotEmpty() else length(current) > windowChars / 4) flush(keepOverlap = false)
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

    /** Word windows of [windowChars] stepping back [overlapChars]; each keeps the heading it starts under. */
    private fun fixedWindows(text: String): List<Piece> {
        val headings = Regex("(?m)^#{1,6}\\s+(.+)$").findAll(text).map { it.range.first to it.groupValues[1].trim() }.toList()
        val words = Regex("\\S+\\s*").findAll(text).toList()
        val out = mutableListOf<Piece>()
        var start = 0
        while (start < words.size) {
            var end = start
            var length = 0
            while (end < words.size && (end == start || length + words[end].value.length <= windowChars)) {
                length += words[end].value.length
                end++
            }
            val offset = words[start].range.first
            out += Piece(words.subList(start, end).joinToString("") { it.value }.trim(), headings.lastOrNull { it.first <= offset }?.second)
            if (end >= words.size) break
            var next = end
            var back = 0
            while (next > start + 1 && back + words[next - 1].value.length <= overlapChars) {
                back += words[next - 1].value.length
                next--
            }
            start = next
        }
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
