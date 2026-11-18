package com.tune2ai.sdk

import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Role
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.services.prompt.PromptTemplate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/** The public API end to end on the JVM: Builder → chat/session → controller → FakeBackend. Real time. */
class Tune2AITest {
    private val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }

    private fun build(platform: FakePlatform = FakePlatform(), backend: FakeBackend = FakeBackend()) =
        Tune2AI.Builder.forTests(platform)
            .addBackend(backend)
            .addPromptTemplate(PromptTemplate("assistant", 3, "Be concise."))
            .defaultPromptTemplate("assistant")
            .build()

    private suspend fun Tune2AI.withModel() = apply { models.register(fakeModel(path = modelFile.path)) }

    @Test
    fun buildRequiresABackendAndKnownDefaultTemplate() {
        assertThrows(Tune2AIException.InvalidRequest::class.java) { Tune2AI.Builder.forTests(FakePlatform()).build() }
        assertThrows(Tune2AIException.InvalidRequest::class.java) {
            Tune2AI.Builder.forTests(FakePlatform()).addBackend(FakeBackend()).defaultPromptTemplate("nope").build()
        }
    }

    @Test
    fun oneShotChatUsesTheDefaultPrompt() = runBlocking {
        val backend = FakeBackend()
        build(backend = backend).withModel().use { ai ->
            val events = ai.chat.send("Hi").toList()
            assertTrue(events.last() is Tune2AIEvent.Completed)
            val context = events.filterIsInstance<Tune2AIEvent.Progress>().single { it.stage == Tune2AIEvent.Stage.CONTEXT_ASSEMBLED }
            assertEquals(listOf("prompt:assistant@3"), context.sources)
            assertEquals(Role.SYSTEM, backend.lastRequest!!.messages.first().role)
        }
    }

    @Test
    fun sessionHistoryPersistsAcrossInstances() = runBlocking {
        val platform = FakePlatform()
        build(platform).withModel().use { ai ->
            ai.chat.session("s1").send("First question").toList()
            ai.chat.session("s1").send("Second question").toList()
        }
        build(platform).use { ai ->
            val history = ai.chat.session("s1").history()
            assertEquals(listOf("First question", "Hello, world", "Second question", "Hello, world"), history.map { it.content })
            assertTrue(ai.chat.session("other").history().isEmpty())
        }
    }

    @Test
    fun failedRequestsAreNotAddedToHistory() = runBlocking {
        build(backend = FakeBackend(failAfterTokens = 1)).withModel().use { ai ->
            val session = ai.chat.session("s")
            val events = session.send("Hi").toList()
            assertEquals(ErrorCategory.BACKEND_FAILURE, (events.last() as Tune2AIEvent.Failed).error.category)
            assertTrue(session.history().isEmpty())
        }
    }

    @Test
    fun callbackApiDeliversCancelledAfterCancel() = runBlocking {
        build(backend = FakeBackend(tokens = List(1_000) { "x" }, tokenDelayMillis = 10)).withModel().use { ai ->
            val events = Collections.synchronizedList(mutableListOf<Tune2AIEvent>())
            val firstToken = CompletableDeferred<Unit>()
            val done = CompletableDeferred<Unit>()
            val handle = ai.chat.session("s").stream("Hi", RequestOptions()) { event ->
                events += event
                if (event is Tune2AIEvent.TextDelta) firstToken.complete(Unit)
                if (event is Tune2AIEvent.Terminal) done.complete(Unit)
            }
            withTimeout(5_000) { firstToken.await() }
            handle.cancel()
            withTimeout(5_000) { done.await() }
            assertTrue(events.last() is Tune2AIEvent.Cancelled)
            assertEquals(1, events.count { it is Tune2AIEvent.Terminal })
            assertEquals(events.first().requestId, events.last().requestId)
            assertTrue("cancelled exchange is not stored", ai.chat.session("s").history().isEmpty())
        }
    }

    @Test
    fun closeUnloadsModelsAndClosesThePlatform() = runBlocking {
        val platform = FakePlatform()
        val backend = FakeBackend()
        val ai = build(platform, backend).withModel()
        ai.chat.send(listOf(ChatMessage.user("Hi"))).toList()
        ai.close()
        assertEquals(1, backend.unloads)
        assertTrue(platform.closed)
    }
}
