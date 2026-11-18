package com.tune2ai.sdk

import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.ModelState
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.inference.BackendCapabilities
import com.tune2ai.sdk.inference.BackendRegistry
import com.tune2ai.sdk.inference.GenerateRequest
import com.tune2ai.sdk.inference.InferenceBackend
import com.tune2ai.sdk.inference.LoadedModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackendsAndModelsApiTest {
    private val modelFile = File.createTempFile("tiny", ".fake").apply { deleteOnExit() }

    private fun backendWith(id: String, formats: Set<String> = setOf("x"), tasks: Set<Task> = setOf(Task.CHAT)) = object : InferenceBackend {
        override val id = id
        override val capabilities = BackendCapabilities(formats, tasks)
        override suspend fun load(model: com.tune2ai.sdk.api.ModelDescriptor): LoadedModel = error("unused")
        override suspend fun unload(model: LoadedModel) = Unit
        override fun generate(model: LoadedModel, request: GenerateRequest): Flow<String> = emptyFlow()
    }

    @Test
    fun registryValidatesBackends() {
        val registry = BackendRegistry()
        registry.register(backendWith("llama.cpp"))
        assertThrows(IllegalArgumentException::class.java) { registry.register(backendWith("llama.cpp")) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(backendWith("Bad Id")) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(backendWith("noformats", formats = emptySet())) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(backendWith("notasks", tasks = emptySet())) }
        assertEquals(listOf("llama.cpp"), registry.all.map { it.id })
    }

    private fun build(vararg backends: FakeBackend) = Tune2AI.Builder.forTests(FakePlatform()).apply { backends.forEach { addBackend(it) } }.build()

    @Test
    fun unavailableBackendsExplainThemselves() = runBlocking {
        val remote = FakeBackend("remote", formats = setOf("remote"), unavailable = "No API key configured", network = true)
        build(FakeBackend(), remote).use { ai ->
            assertEquals("No API key configured", ai.backends().single { it.id == "remote" }.unavailableReason)
            ai.models.register(fakeModel("cloud", format = "remote", path = "gpt-x"))
            assertEquals(ModelState.Unavailable("remote: No API key configured"), ai.models.state("cloud").value)

            val events = ai.chat.send("Hi", RequestOptions(backend = "remote")).toList()
            val error = (events.last() as Tune2AIEvent.Failed).error
            assertEquals(ErrorCategory.NO_COMPATIBLE_ROUTE, error.category)
            assertTrue(error.message!!.contains("No API key configured"))
        }
    }

    @Test
    fun networkModelsNeedNoFileAndNoMemoryHeadroom() = runBlocking {
        val remote = FakeBackend("remote", formats = setOf("remote"), network = true)
        build(remote).use { ai ->
            ai.models.register(fakeModel("cloud", format = "remote", path = "gpt-x", memoryGb = 100.0))
            assertEquals(ModelState.Unloaded, ai.models.state("cloud").value)
            assertTrue(ai.chat.send("Hi").toList().last() is Tune2AIEvent.Completed)
        }
    }

    @Test
    fun modelApiListsLoadsAndUnloads() = runBlocking {
        val backend = FakeBackend()
        build(backend).use { ai ->
            ai.models.register(fakeModel(path = modelFile.path))
            val info = ai.models.list().single()
            assertEquals(listOf("fake"), info.backends)
            assertEquals(ModelState.Unloaded, info.state)

            val state = ai.models.state("tiny")
            ai.models.load("tiny")
            withTimeout(1_000) { state.first { it == ModelState.Loaded("fake") } }
            assertEquals(1, backend.loads)
            ai.models.unload("tiny")
            withTimeout(1_000) { state.first { it == ModelState.Unloaded } }
            assertEquals(1, backend.unloads)
        }
    }

    @Test
    fun loadingAnUnknownOrUnfitModelFailsWithTypedErrors() = runBlocking {
        build(FakeBackend()).use { ai ->
            assertEquals(ErrorCategory.MODEL_UNAVAILABLE,
                assertThrows(Tune2AIException::class.java) { runBlocking { ai.models.load("nope") } }.category)
            ai.models.register(fakeModel("gone", path = "/does/not/exist.fake"))
            val error = assertThrows(Tune2AIException::class.java) { runBlocking { ai.models.load("gone") } }
            assertTrue(error.message!!.contains("not found"))
        }
    }
}
