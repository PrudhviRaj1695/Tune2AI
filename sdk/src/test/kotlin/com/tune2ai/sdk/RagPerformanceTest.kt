package com.tune2ai.sdk

import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.services.rag.RagEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Questions over a large index (internal storage: thousands of files) must not re-read or
 * re-tokenize the corpus each time. 3,000 documents, ~15,000 chunks, keyword search.
 */
class RagPerformanceTest {
    @Test
    fun repeatedQuestionsOverALargeIndexAreFast() = runBlocking {
        val rag = RagEngine(InMemoryStorage(), null)
        val words = listOf("invoice", "meeting", "travel", "recipe", "budget", "garden", "contract", "doctor", "school", "music")
        repeat(3_000) { d ->
            val text = "Document code zq$d. " + (1..60).joinToString(" ") { s -> "Note $d line $s mentions ${words[(d + s) % words.size]} and item${d * 7 + s}." }
            rag.add("doc$d", "file$d.txt", text)
        }
        rag.retrieve("warm up the index") // first query builds the keyword index once
        val times = (1..5).map { q ->
            val t0 = System.nanoTime()
            val hits = rag.retrieve("budget meeting item${q * 1000 + 7}")
            assertTrue(hits.isNotEmpty())
            (System.nanoTime() - t0) / 1_000_000
        }
        println("RagPerformanceTest: query times ms = $times")
        assertTrue("queries took $times ms", times.max() < 1_500)
        assertEquals("doc1234", rag.retrieve("document code zq1234").first().sourceId)
    }
}
