package com.tune2ai.sdk

import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.services.context.ContextManager
import com.tune2ai.sdk.services.embedding.Embedder
import com.tune2ai.sdk.services.memory.MemoryContextSource
import com.tune2ai.sdk.services.memory.MemoryEngine
import com.tune2ai.sdk.services.prompt.PromptManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    private fun engine(embedder: Embedder? = null): MemoryEngine {
        var t = 0L
        return MemoryEngine(InMemoryStorage(), embedder, clock = { t++ }) // deterministic createdAt
    }

    @Test
    fun writeListSearchAndKeywordRanking() = runTest {
        val m = engine()
        m.write("s", "User prefers Kotlin for Android")
        m.write("s", "User is based in Berlin")
        m.write("s", "User likes strong coffee")

        assertEquals(3, m.list("s").size)
        val hits = m.search("s", "What language on Android?")
        assertEquals("User prefers Kotlin for Android", hits.first().text) // most term overlap wins
        assertTrue(m.search("s", "completely unrelated xyzzy").isEmpty()) // no overlap -> nothing
    }

    @Test
    fun updateReplacesAndDeleteRemoves() = runTest {
        val m = engine()
        val id = m.write("s", "User likes tea")
        assertTrue(m.update("s", id, "User likes coffee"))
        assertEquals("User likes coffee", m.list("s").single().text)
        assertFalse(m.update("s", "missing", "x"))

        assertTrue(m.delete("s", id))
        assertTrue(m.list("s").isEmpty())
        assertFalse(m.delete("s", id)) // already gone
    }

    @Test
    fun sessionsAreIsolatedAndClearIsScoped() = runTest {
        val m = engine()
        m.write("alice", "Alice's secret")
        m.write("bob", "Bob's note")

        assertTrue(m.search("bob", "secret").isEmpty())          // Bob cannot see Alice's memory
        assertEquals("Alice's secret", m.list("alice").single().text)

        m.clear("alice")
        assertTrue(m.list("alice").isEmpty())
        assertEquals(1, m.list("bob").size)                       // clear did not touch Bob
    }

    @Test
    fun embeddingSearchRanksBySimilarityWhenAvailable() = runTest {
        val axes = listOf("kotlin", "java", "python")
        val embedder = Embedder { text ->
            FloatArray(axes.size) { i -> if (axes[i] in text.lowercase()) 1f else 0f }
        }
        val m = engine(embedder)
        m.write("s", "java is fine")
        m.write("s", "I prefer kotlin")
        m.write("s", "python too")

        assertEquals("I prefer kotlin", m.search("s", "kotlin rocks").first().text)
    }

    @Test
    fun disabledMemoryContributesNothingThroughContext() = runTest {
        val m = engine()
        m.write("s", "User prefers the Kotlin language")
        val source = MemoryContextSource(m, "s")
        val prompts = PromptManager()
        val context = ContextManager(prompts, sources = listOf(source))
        val ask = listOf(com.tune2ai.sdk.api.ChatMessage.user("Which language?"))

        val on = context.assemble(ask, fakeModel(), RequestOptions(maxTokens = 64))
        assertTrue("memory feeds context when enabled", on.included.any { it.startsWith("memory:") })

        m.enabled = false
        val off = context.assemble(ask, fakeModel(), RequestOptions(maxTokens = 64))
        assertTrue("disabled memory adds nothing", off.included.none { it.startsWith("memory:") })
    }
}
