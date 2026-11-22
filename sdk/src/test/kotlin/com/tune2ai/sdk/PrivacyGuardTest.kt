package com.tune2ai.sdk

import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Tool
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.services.context.BlockingContextSource
import com.tune2ai.sdk.services.context.ContextItem
import com.tune2ai.sdk.services.guard.PrivacyGuard
import com.tune2ai.sdk.services.guard.SensitiveKind
import com.tune2ai.sdk.services.prompt.PromptTemplate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyGuardTest {
    private val guard = PrivacyGuard()

    private fun hides(text: String, kind: SensitiveKind, secret: String) {
        val r = guard.redact(text)
        assertTrue("$kind not found in: $text → ${r.text}", kind in r.counts)
        assertFalse("still visible: ${r.text}", secret in r.text)
    }

    @Test
    fun hidesOneTimeCodes() {
        hides("Your OTP is 482913. Do not share it.", SensitiveKind.ONE_TIME_CODE, "482913")
        hides("482913 is your verification code for HDFC", SensitiveKind.ONE_TIME_CODE, "482913")
        hides("Use code: 7741 to log in", SensitiveKind.ONE_TIME_CODE, "7741")
    }

    @Test
    fun hidesCardsAccountsAndIds() {
        hides("Card 4111 1111 1111 1111 charged", SensitiveKind.CARD_NUMBER, "4111 1111 1111 1111")
        hides("CVV: 123", SensitiveKind.CARD_SECURITY_CODE, "123")
        hides("A/c no. 001234567890 credited", SensitiveKind.BANK_ACCOUNT, "001234567890")
        hides("PAN ABCPE1234F on file", SensitiveKind.GOVERNMENT_ID, "ABCPE1234F")
        hides("Aadhaar 2341 2341 2346", SensitiveKind.GOVERNMENT_ID, "2341 2341 2346")
        hides("SSN 123-45-6789", SensitiveKind.GOVERNMENT_ID, "123-45-6789")
    }

    @Test
    fun hidesPasswordsAndKeys() {
        hides("The Wi-Fi password is pelican42", SensitiveKind.PASSWORD, "pelican42")
        hides("key sk-proj-AbCdEf0123456789XyZ0123", SensitiveKind.SECRET_KEY, "sk-proj-AbCdEf0123456789XyZ0123")
        hides("AKIAABCDEFGHIJKLMNOP", SensitiveKind.SECRET_KEY, "AKIAABCDEFGHIJKLMNOP")
    }

    @Test
    fun leavesOrdinaryNumbersAlone() {
        val text = "Flight TP1351 leaves at 07:40 on 27/11/2026 from gate 12. Call +91 98450 12345. Order 123456789012 shipped."
        assertEquals(text, guard.redact(text).text)
        assertTrue(guard.find("Card 4111 1111 1111 1112").isEmpty())
    }

    // ---------- In the request path ----------

    private val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }

    private class Sms : BlockingContextSource() {
        override val name = "phone"
        override fun fetchBlocking(query: String, model: ModelDescriptor) =
            listOf(ContextItem("latest SMS", "Latest SMS: HDFC: Your OTP is 482913. Do not share."))
    }

    private fun ai(backend: FakeBackend) = Tune2AI.Builder.forTests(FakePlatform()).addBackend(backend)
        .addPromptTemplate(PromptTemplate("assistant", 1, "Be concise."))
        .defaultPromptTemplate("assistant")
        .addContextSource(Sms())
        .addTool(Tool("read_sms", "Reads SMS.", handler = { "\"Your OTP is 551177\"" }))
        .build()

    @Test
    fun contextReachesTheModelRedactedWithGuidance() = runBlocking {
        val backend = FakeBackend()
        ai(backend).use { ai ->
            ai.models.register(fakeModel(path = modelFile.path))
            val events = ai.chat.send("What was my last SMS?").toList()
            val system = backend.lastRequest!!.messages.first().content
            assertFalse(system.contains("482913"))
            assertTrue(system.contains("[hidden one-time code]"))
            assertTrue(system.contains("Never reveal one-time codes"))
            val sources = events.filterIsInstance<Tune2AIEvent.Progress>().single { it.stage == Tune2AIEvent.Stage.CONTEXT_ASSEMBLED }.sources
            assertTrue(sources.toString(), sources.contains("guard:1 sensitive values hidden"))
        }
    }

    @Test
    fun personalContextNeverGoesToARemoteModel() = runBlocking {
        val remote = FakeBackend(id = "remote", formats = setOf("fake"), network = true)
        ai(remote).use { ai ->
            ai.models.register(fakeModel(path = modelFile.path))
            val events = ai.chat.send("What was my last SMS?").toList()
            val system = remote.lastRequest!!.messages.first().content
            assertFalse(system.contains("HDFC"))
            val sources = events.filterIsInstance<Tune2AIEvent.Progress>().single { it.stage == Tune2AIEvent.Stage.CONTEXT_ASSEMBLED }.sources
            assertTrue(sources.any { it.startsWith("omitted guard:") })

            ai.privacy.personalContextWithRemote = true
            ai.chat.send("What was my last SMS?").toList()
            assertTrue(remote.lastRequest!!.messages.first().content.contains("HDFC"))
        }
    }

    @Test
    fun agentToolResultsAreRedacted() = runBlocking {
        val backend = FakeBackend()
        backend.script += listOf("""{"tool": "read_sms", "arguments": {}}""")
        backend.script += listOf("Done.")
        ai(backend).use { ai ->
            ai.models.register(fakeModel(path = modelFile.path))
            val events = ai.agent.run("Read my SMS").toList()
            val result = events.filterIsInstance<Tune2AIEvent.ToolResult>().single().resultJson
            assertFalse(result.contains("551177"))
            assertFalse(backend.lastRequest!!.messages.any { "551177" in it.content })
        }
    }

    @Test
    fun turningRedactionOffPassesValuesThrough() = runBlocking {
        val backend = FakeBackend()
        ai(backend).use { ai ->
            ai.models.register(fakeModel(path = modelFile.path))
            ai.privacy.redactSensitive = false
            ai.chat.send("What was my last SMS?").toList()
            assertTrue(backend.lastRequest!!.messages.first().content.contains("482913"))
            assertEquals("[hidden one-time code]", ai.privacy.redact("OTP 482913").text.substringAfter("OTP "))
        }
    }
}
