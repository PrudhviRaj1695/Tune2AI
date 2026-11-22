package com.tune2ai.sdk

import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.services.context.BlockingContextSource
import com.tune2ai.sdk.services.context.ContextItem
import com.tune2ai.sdk.services.prompt.PromptTemplate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** App-provided context sources (Tune2AI.Builder.addContextSource). */
class ContextSourceTest {
    private val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }

    private class Live(override val name: String, private val fail: Boolean = false) : BlockingContextSource() {
        override fun fetchBlocking(query: String, model: ModelDescriptor): List<ContextItem> {
            if (fail) throw IllegalStateException("provider gone")
            return if ("sms" in query.lowercase()) listOf(ContextItem("recent sms", "Latest SMS: Mom: dinner at 7")) else emptyList()
        }
    }

    private fun build(vararg sources: Live, backend: FakeBackend = FakeBackend()) =
        Tune2AI.Builder.forTests(FakePlatform()).addBackend(backend)
            .addPromptTemplate(PromptTemplate("assistant", 1, "Be concise."))
            .defaultPromptTemplate("assistant")
            .apply { sources.forEach { addContextSource(it) } }
            .build()

    @Test
    fun appSourcesReachTheModelThroughTheContextManager() = runBlocking {
        val backend = FakeBackend()
        build(Live("phone"), backend = backend).use { ai ->
            ai.models.register(fakeModel(path = modelFile.path))
            val events = ai.chat.send("What was my last SMS?").toList()
            val context = events.filterIsInstance<Tune2AIEvent.Progress>().single { it.stage == Tune2AIEvent.Stage.CONTEXT_ASSEMBLED }
            assertTrue(context.sources.contains("phone:recent sms"))
            assertTrue("dinner at 7" in backend.lastRequest!!.messages.first().content)

            val other = ai.chat.send("Hello").toList()
            assertTrue(other.filterIsInstance<Tune2AIEvent.Progress>().single { it.stage == Tune2AIEvent.Stage.CONTEXT_ASSEMBLED }.sources.none { it.startsWith("phone:") })
        }
    }

    @Test
    fun aFailingSourceDoesNotFailTheRequest() = runBlocking {
        build(Live("phone", fail = true)).use { ai ->
            ai.models.register(fakeModel(path = modelFile.path))
            assertTrue(ai.chat.send("Read my SMS").toList().last() is Tune2AIEvent.Completed)
        }
    }

    @Test
    fun namesMustBeUnique() {
        assertThrows(Tune2AIException.InvalidRequest::class.java) {
            Tune2AI.Builder.forTests(FakePlatform()).addContextSource(Live("phone")).addContextSource(Live("phone"))
        }
        assertThrows(Tune2AIException.InvalidRequest::class.java) {
            Tune2AI.Builder.forTests(FakePlatform()).addContextSource(Live("rag"))
        }
    }
}

/** Prompts registered while the app runs (a user-edited system prompt). */
class RuntimePromptTest {
    @Test
    fun aNewerVersionBecomesTheDefault() = runBlocking {
        val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }
        val backend = FakeBackend()
        Tune2AI.Builder.forTests(FakePlatform()).addBackend(backend)
            .addPromptTemplate(PromptTemplate("assistant", 1, "Be concise."))
            .defaultPromptTemplate("assistant")
            .build().use { ai ->
                ai.models.register(fakeModel(path = modelFile.path))
                ai.addPromptTemplate(PromptTemplate("assistant", 2, "Answer only from the passages."))
                val events = ai.chat.send("Hi").toList()
                assertTrue(backend.lastRequest!!.messages.first().content.startsWith("Answer only from the passages."))
                assertTrue(events.filterIsInstance<Tune2AIEvent.Progress>().single { it.stage == Tune2AIEvent.Stage.CONTEXT_ASSEMBLED }.sources.contains("prompt:assistant@2"))
            }
    }
}
