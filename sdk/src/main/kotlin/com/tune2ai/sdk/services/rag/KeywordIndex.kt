package com.tune2ai.sdk.services.rag

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ln

/** BM25 score of a chunk for a query, and the share of distinct query terms it contains. */
internal class Bm25(val score: Float, val coverage: Float)

/**
 * Inverted index for BM25 keyword search. Each source's chunks are tokenized once (text plus title
 * and section) and kept until that source changes, so a query only touches the chunks that contain
 * one of its terms instead of re-tokenizing the whole corpus on every question.
 *
 * Changes are detected by list identity (see [VectorIndex.bySource]); postings are rebuilt from the
 * cached term counts after any change, which is cheap next to tokenizing.
 */
internal class KeywordIndex {
    private class Doc(val chunk: IndexedChunk, val counts: Map<String, Int>, val length: Int)

    private val lock = Mutex()
    private val docs = HashMap<String, Pair<List<IndexedChunk>, List<Doc>>>()
    private var postings: Map<String, List<Doc>> = emptyMap()
    private var docCount = 0
    private var avgLength = 1.0

    suspend fun rank(
        query: String,
        bySource: Map<String, List<IndexedChunk>>,
        extra: (IndexedChunk) -> String,
    ): List<Pair<IndexedChunk, Bm25>> = lock.withLock {
        sync(bySource, extra)
        val terms = RagEngine.terms(query).distinct()
        if (terms.isEmpty() || docCount == 0) return@withLock emptyList()
        val scores = HashMap<Doc, DoubleArray>() // [score, matched terms]
        for (t in terms) {
            val list = postings[t] ?: continue
            val idf = ln(1 + (docCount - list.size + 0.5) / (list.size + 0.5))
            for (d in list) {
                val tf = d.counts.getValue(t)
                val acc = scores.getOrPut(d) { DoubleArray(2) }
                acc[0] += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * d.length / avgLength))
                acc[1] += 1.0
            }
        }
        scores.entries
            .map { (d, acc) -> d.chunk to Bm25(acc[0].toFloat(), (acc[1] / terms.size).toFloat()) }
            .sortedByDescending { it.second.score }
    }

    private fun sync(bySource: Map<String, List<IndexedChunk>>, extra: (IndexedChunk) -> String) {
        var changed = docs.keys.retainAll(bySource.keys)
        for ((sourceId, chunks) in bySource) {
            if (docs[sourceId]?.first === chunks) continue
            docs[sourceId] = chunks to chunks.map { c ->
                val words = RagEngine.terms(c.text + " " + extra(c))
                Doc(c, words.groupingBy { it }.eachCount(), words.size)
            }
            changed = true
        }
        if (!changed) return
        val all = docs.values.flatMap { it.second }
        val map = HashMap<String, MutableList<Doc>>()
        for (d in all) for (t in d.counts.keys) map.getOrPut(t) { ArrayList() } += d
        postings = map
        docCount = all.size
        avgLength = if (all.isEmpty()) 1.0 else all.sumOf { it.length }.toDouble() / all.size
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
    }
}
