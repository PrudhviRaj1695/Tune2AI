package com.tune2ai.sdk

import com.tune2ai.sdk.core.Storage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour every [Storage] implementation must have. Runs on the JVM and on devices. */
abstract class StorageContract {
    abstract fun newStorage(): Storage

    /** Reopens the same underlying store, or null if the implementation is not persistent. */
    open fun reopen(): Storage? = null

    @Test
    fun putGetUpdateDelete() = runTest {
        val s = newStorage()
        assertNull(s.get("ns", "a"))
        s.put("ns", "a", "1")
        assertEquals("1", s.get("ns", "a"))
        s.put("ns", "a", "2")
        assertEquals("2", s.get("ns", "a"))
        assertTrue(s.delete("ns", "a"))
        assertFalse(s.delete("ns", "a"))
        assertNull(s.get("ns", "a"))
    }

    @Test
    fun namespacesAreIsolated() = runTest {
        val s = newStorage()
        s.put("chat/1", "k", "one")
        s.put("chat/2", "k", "two")
        assertEquals("one", s.get("chat/1", "k"))
        s.clear("chat/1")
        assertEquals(emptyMap<String, String>(), s.list("chat/1"))
        assertEquals("two", s.get("chat/2", "k"))
    }

    @Test
    fun listIsOrderedByKey() = runTest {
        val s = newStorage()
        s.put("ns", "b", "2")
        s.put("ns", "a", "1")
        s.put("ns", "c", "3")
        assertEquals(listOf("a" to "1", "b" to "2", "c" to "3"), s.list("ns").toList())
    }

    @Test
    fun storesUnicodeAndLargeValues() = runTest {
        val s = newStorage()
        val big = "é漢🙂".repeat(50_000)
        s.put("ns", "big", big)
        assertEquals(big, s.get("ns", "big"))
    }

    @Test
    fun survivesReopen() = runTest {
        val s = newStorage()
        s.put("ns", "k", "kept")
        val reopened = reopen() ?: return@runTest
        assertEquals("kept", reopened.get("ns", "k"))
    }
}
