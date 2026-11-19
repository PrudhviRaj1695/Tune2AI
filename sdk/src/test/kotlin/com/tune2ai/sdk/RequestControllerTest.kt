package com.tune2ai.sdk

import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.ModelState
import com.tune2ai.sdk.api.RequestId
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.Completed
import com.tune2ai.sdk.api.Tune2AIEvent.Failed
import com.tune2ai.sdk.api.Tune2AIEvent.Progress
import com.tune2ai.sdk.api.Tune2AIEvent.Stage
import com.tune2ai.sdk.api.Tune2AIEvent.TextDelta
import com.tune2ai.sdk.core.AssembledContext
import com.tune2ai.sdk.core.ContextAssembler
import com.tune2ai.sdk.core.RequestController
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.services.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestControllerTest {
    private val id = RequestId("req_test")
    private val hello = listOf(ChatMessage.user("Hello"))

    private suspend fun TestScope.controller(
        backend: FakeBackend = FakeBackend(),
        context: ContextAssembler = ContextAssembler.PassThrough,
        contextLength: Int = 4096,
    ): Pair<RequestController, ModelManager> {
        val manager = modelManager(backend)
        manager.register(fakeModel(contextLength = contextLength))
        return RequestController(manager, Router(manager), context, StandardTestDispatcher(testScheduler)) to manager
    }

    private fun List<Tune2AIEvent>.stages() = filterIsInstance<Progress>().map { it.stage }
    private fun List<Tune2AIEvent>.error() = (last() as Failed).error

    @Test
    fun streamsTheWholeRouteAndCompletes() = runTest {
        val (controller, manager) = controller()
        val events = controller.chat(hello, requestId = id).toList()

        assertEquals(Stage.entries, events.stages())
        assertEquals("Hello, world", events.filterIsInstance<TextDelta>().joinToString("") { it.text })
        assertEquals(3, (events.last() as Completed).usage.outputTokens)
        assertTrue(events.all { it.requestId == id })
        assertEquals("Tiny", (events[2] as Progress).detail!!.substringAfter(" · "))
        manager.trim()
        assertEquals("lease released after completion", ModelState.Unloaded, manager.state("tiny"))
    }

    @Test
    fun invalidRequestsFailBeforeRouting() = runTest {
        val (controller, _) = controller()
        val events = controller.chat(listOf(ChatMessage.assistant("hi")), requestId = id).toList()
        assertEquals(listOf(Stage.RECEIVED), events.stages())
        assertEquals(ErrorCategory.INVALID_REQUEST, events.error().category)

        val blank = controller.chat(listOf(ChatMessage.user("  ")), requestId = id).toList()
        assertEquals(ErrorCategory.INVALID_REQUEST, blank.error().category)
    }

    @Test
    fun maxTokensMustFitTheRoutedModelsContext() = runTest {
        val (small, _) = controller(contextLength = 256)
        val events = small.chat(hello, RequestOptions(maxTokens = 512), id).toList()
        assertEquals(listOf(Stage.RECEIVED, Stage.VALIDATED), events.stages())
        assertEquals(ErrorCategory.INVALID_REQUEST, events.error().category)
    }

    @Test
    fun noModelMeansNoCompatibleRoute() = runTest {
        val manager = modelManager()
        val controller = RequestController(manager, Router(manager), dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(ErrorCategory.NO_COMPATIBLE_ROUTE, controller.chat(hello).toList().error().category)
    }

    @Test
    fun deadlineBecomesTimeout() = runTest {
        val (controller, _) = controller(FakeBackend(tokenDelayMillis = 1_000))
        val events = controller.chat(hello, RequestOptions(deadlineMillis = 1_500), id).toList()
        assertEquals(1, events.filterIsInstance<TextDelta>().size)
        assertEquals(ErrorCategory.TIMEOUT, events.error().category)
    }

    @Test
    fun backendFailureMidStreamFailsAndReleasesTheModel() = runTest {
        val (controller, manager) = controller(FakeBackend(failAfterTokens = 2))
        val events = controller.chat(hello, requestId = id).toList()
        assertEquals(2, events.filterIsInstance<TextDelta>().size)
        assertEquals(ErrorCategory.BACKEND_FAILURE, events.error().category)
        manager.trim()
        assertEquals(ModelState.Unloaded, manager.state("tiny"))
    }

    @Test
    fun cancellingTheCollectorReleasesTheModel() = runTest {
        val (controller, manager) = controller(FakeBackend(tokenDelayMillis = 100))
        val events = mutableListOf<Tune2AIEvent>()
        val job = launch { controller.chat(hello, requestId = id).toList(events) }
        advanceTimeBy(150)
        job.cancel()
        job.join()
        assertTrue(events.none { it is Tune2AIEvent.Terminal })
        manager.trim()
        assertEquals(ModelState.Unloaded, manager.state("tiny"))
    }

    @Test
    fun contextAssemblerShapesTheModelInput() = runTest {
        val backend = FakeBackend()
        val (controller, _) = controller(backend, context = { messages, _, _ ->
            AssembledContext(listOf(ChatMessage.system("Be brief.")) + messages, "Prompt v3")
        })
        val events = controller.chat(hello).toList()
        assertEquals("Prompt v3", events.filterIsInstance<Progress>().single { it.stage == Stage.CONTEXT_ASSEMBLED }.detail)
        assertEquals(2, backend.lastRequest!!.messages.size)
    }

    /** NFR-4: SDK overhead (everything except inference) at most 50 ms p95. Real time, zero-latency backend. */
    @Test
    fun overheadStaysWithinBudget() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val manager = ModelManager(listOf(FakeBackend()), InMemoryStorage(), FakeResourceMonitor(), ResourceLimits(), scope, fileExists = { true })
            manager.register(fakeModel())
            val controller = RequestController(manager, Router(manager), dispatcher = Dispatchers.Default)
            repeat(50) { controller.chat(hello).toList() } // warm up the JIT
            val samples = List(300) {
                val start = System.nanoTime()
                controller.chat(hello).toList()
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()
            val p95 = samples[(samples.size * 0.95).toInt()]
            println("RequestController overhead: p50=${samples[samples.size / 2]} ms, p95=$p95 ms")
            assertTrue("p95 overhead $p95 ms exceeds 50 ms", p95 <= 50.0)
        } finally {
            scope.cancel()
        }
    }

    /** NFR-5: the first streamed token reaches the caller within 100 ms of the backend producing it. Real time, zero-latency backend. */
    @Test
    fun firstTokenIsDeliveredWithinBudget() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val backend = FakeBackend()
            val manager = ModelManager(listOf(backend), InMemoryStorage(), FakeResourceMonitor(), ResourceLimits(), scope, fileExists = { true })
            manager.register(fakeModel())
            val controller = RequestController(manager, Router(manager), dispatcher = Dispatchers.Default)
            suspend fun oneRun(): Double {
                var receivedAt = 0L
                controller.chat(hello).collect { if (it is TextDelta && receivedAt == 0L) receivedAt = System.nanoTime() }
                return (receivedAt - backend.firstTokenNanos) / 1_000_000.0
            }
            repeat(50) { oneRun() } // warm up the JIT
            val samples = List(300) { oneRun() }.sorted()
            val p95 = samples[(samples.size * 0.95).toInt()]
            println("First-token delivery latency: p50=${samples[samples.size / 2]} ms, p95=$p95 ms, max=${samples.last()} ms")
            assertTrue("p95 first-token latency $p95 ms exceeds 100 ms", p95 <= 100.0)
        } finally {
            scope.cancel()
        }
    }
}
