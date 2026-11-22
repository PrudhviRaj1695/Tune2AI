package com.tune2ai.sdk

import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Role
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.services.context.ContextItem
import com.tune2ai.sdk.services.context.ContextManager
import com.tune2ai.sdk.services.context.ContextSource
import com.tune2ai.sdk.services.prompt.PromptManager
import com.tune2ai.sdk.services.prompt.PromptTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAndContextTest {
    private val assistant = PromptTemplate("assistant", 3, "You help with {{app}}. Be concise.", setOf("app"))

    @Test
    fun rendersDeclaredVariables() {
        assertEquals("You help with MatchMate. Be concise.", assistant.render(mapOf("app" to "MatchMate")))
    }

    @Test
    fun missingOrUnknownVariablesFailBeforeInference() {
        assertEquals(ErrorCategory.INVALID_REQUEST, assertThrows(Tune2AIException::class.java) { assistant.render() }.category)
        assertThrows(Tune2AIException.InvalidRequest::class.java) { assistant.render(mapOf("app" to "x", "extra" to "y")) }
    }

    @Test
    fun undeclaredPlaceholdersAreRejectedAtCreation() {
        assertThrows(IllegalArgumentException::class.java) { PromptTemplate("t", 1, "Hi {{name}}") }
    }

    @Test
    fun escapedBracesStayLiteral() {
        assertEquals("Use {{name}} as a placeholder", PromptTemplate("t", 1, """Use \{{name}} as a placeholder""").render())
    }

    @Test
    fun latestVersionWinsUnlessPinned() {
        val prompts = PromptManager().apply {
            register(PromptTemplate("p", 1, "one"))
            register(PromptTemplate("p", 2, "two"))
        }
        assertEquals("two", prompts.get("p").text)
        assertEquals("one", prompts.get("p", 1).text)
        assertThrows(Tune2AIException.InvalidRequest::class.java) { prompts.get("missing") }
    }

    private val prompts = PromptManager().apply { register(PromptTemplate("assistant", 3, "Be concise.")) }

    @Test
    fun ordersSystemPromptThenSourcesThenHistoryThenMessage() = runTest {
        val memory = object : ContextSource {
            override val name = "memory"
            override suspend fun fetch(query: String, model: com.tune2ai.sdk.api.ModelDescriptor) = listOf(ContextItem("m1", "User prefers Kotlin"))
        }
        val context = ContextManager(prompts, "assistant", listOf(memory))
        val history = listOf(ChatMessage.user("Hi"), ChatMessage.assistant("Hello!"), ChatMessage.user("Which language?"))
        val result = context.assemble(history, fakeModel(), RequestOptions(maxTokens = 64))

        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER), result.messages.map { it.role })
        assertTrue(result.messages[0].content.startsWith("Be concise."))
        assertTrue(result.messages[0].content.contains("- User prefers Kotlin"))
        assertEquals(listOf("prompt:assistant@3", "memory:m1", "conversation:2 messages"), result.included)
        assertTrue(result.omitted.isEmpty())
    }

    @Test
    fun trimsOldestTurnsToFitAndReportsIt() = runTest {
        val context = ContextManager(prompts, "assistant", countTokens = { 100 })
        val turns = List(10) { if (it % 2 == 0) ChatMessage.user("q$it") else ChatMessage.assistant("a$it") } + ChatMessage.user("latest")
        val result = context.assemble(turns, fakeModel(contextLength = 600), RequestOptions(maxTokens = 100))

        // Budget 600 - 100 = 500; each message costs 104; system + latest = 208, so 2 of 10 history messages fit.
        assertEquals(4, result.messages.size)
        assertEquals("latest", result.messages.last().content)
        assertEquals("a9", result.messages[result.messages.size - 2].content) // newest history kept
        assertEquals(listOf("conversation:8 oldest messages"), result.omitted)
        assertTrue(result.summary.contains("trimmed 8"))
    }

    @Test
    fun contextIsCutToItsBudgetBestFirst() = runTest {
        val passages = object : ContextSource {
            override val name = "knowledge"
            override suspend fun fetch(query: String, model: com.tune2ai.sdk.api.ModelDescriptor) =
                List(5) { ContextItem("p$it", "word ".repeat(100)) }
        }
        // Each passage ~100 tokens + overhead: a 250-token budget keeps the two best.
        val context = ContextManager(prompts, sources = listOf(passages), countTokens = { it.split(' ').count { w -> w.isNotBlank() } }, contextBudgetTokens = 250)
        val result = context.assemble(listOf(ChatMessage.user("what is it")), fakeModel(), RequestOptions(maxTokens = 64))

        assertEquals(listOf("knowledge:p0", "knowledge:p1"), result.included.filter { it.startsWith("knowledge:") })
        assertEquals(listOf("knowledge:p2", "knowledge:p3", "knowledge:p4"), result.omitted)
    }

    @Test
    fun anOversizedBestPassageIsShortenedRatherThanDropped() = runTest {
        val big = object : ContextSource {
            override val name = "sms"
            override suspend fun fetch(query: String, model: com.tune2ai.sdk.api.ModelDescriptor) =
                listOf(ContextItem("latest", "word ".repeat(2000)))
        }
        val words = { t: String -> t.split(' ', '\n').count { w -> w.isNotBlank() } }
        val context = ContextManager(prompts, sources = listOf(big), countTokens = words, contextBudgetTokens = 300)
        val result = context.assemble(listOf(ChatMessage.user("my messages")), fakeModel(), RequestOptions(maxTokens = 64))

        assertEquals(listOf("sms:latest"), result.included.filter { it.startsWith("sms:") })
        assertTrue("fits the budget", words(result.messages.first().content) <= 300)
    }

    @Test
    fun aMessageTooLargeForTheModelIsRejected() = runTest {
        val context = ContextManager(prompts, countTokens = { it.length })
        val error = assertThrows(Tune2AIException.InvalidRequest::class.java) {
            kotlinx.coroutines.runBlocking { context.assemble(listOf(ChatMessage.user("x".repeat(500))), fakeModel(contextLength = 256), RequestOptions(maxTokens = 64)) }
        }
        assertTrue(error.message!!.contains("allows 192"))
    }
}
