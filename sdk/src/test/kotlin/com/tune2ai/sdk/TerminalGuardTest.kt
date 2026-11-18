package com.tune2ai.sdk

import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.RequestId
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.Cancelled
import com.tune2ai.sdk.api.Tune2AIEvent.Completed
import com.tune2ai.sdk.api.Tune2AIEvent.Failed
import com.tune2ai.sdk.api.Tune2AIEvent.TextDelta
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.api.Usage
import com.tune2ai.sdk.core.withTerminalGuarantee
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalGuardTest {
    private val id = RequestId("req_test")
    private val delta = TextDelta(id, "hi")
    private val done = Completed(id, Usage(1, 10, 20))

    private fun List<Tune2AIEvent>.failure() = (last() as Failed).error

    @Test
    fun passesThroughAWellFormedStream() = runTest {
        assertEquals(listOf(delta, done), flowOf(delta, done).withTerminalGuarantee(id).toList())
    }

    @Test
    fun dropsEverythingAfterTheFirstTerminalEvent() = runTest {
        assertEquals(listOf(done), flowOf(done, delta, Cancelled(id)).withTerminalGuarantee(id).toList())
    }

    @Test
    fun upstreamErrorBecomesFailed() = runTest {
        val events = flow { emit(delta); error("boom") }.withTerminalGuarantee(id).toList()
        assertEquals(delta, events.first())
        assertEquals(ErrorCategory.BACKEND_FAILURE, events.failure().category)
        assertEquals("boom", events.failure().message)
    }

    @Test
    fun sdkErrorsKeepTheirCategory() = runTest {
        val events = flow<Tune2AIEvent> { throw Tune2AIException.ResourceLimit("low memory") }.withTerminalGuarantee(id).toList()
        assertEquals(ErrorCategory.RESOURCE_LIMIT, events.failure().category)
    }

    @Test
    fun upstreamCancellationBecomesCancelledNotFailed() = runTest {
        val events = flow { emit(delta); throw CancellationException("stopped by backend") }.withTerminalGuarantee(id).toList()
        assertEquals(listOf(delta, Cancelled(id)), events)
    }

    @Test
    fun timeoutBecomesTimeoutFailure() = runTest {
        val events = flow<Tune2AIEvent> { withTimeout(10) { delay(1_000) } }.withTerminalGuarantee(id).toList()
        assertEquals(ErrorCategory.TIMEOUT, events.failure().category)
    }

    @Test
    fun missingTerminalEventBecomesFailed() = runTest {
        val events = flowOf(delta).withTerminalGuarantee(id).toList()
        assertEquals(2, events.size)
        assertTrue(events.last() is Failed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun collectorCancellationEmitsNothingMore() = runTest {
        val events = mutableListOf<Tune2AIEvent>()
        val job = launch { flow { emit(delta); awaitCancellation() }.withTerminalGuarantee(id).toList(events) }
        advanceUntilIdle()
        job.cancel()
        job.join()
        assertEquals(listOf(delta), events)
    }

    @Test
    fun everyErrorCategoryHasAnExceptionType() {
        // Compile-time exhaustiveness: adding a category without a subclass mapping fails here.
        val all = ErrorCategory.entries.map { category ->
            when (category) {
                ErrorCategory.INVALID_REQUEST -> Tune2AIException.InvalidRequest("x")
                ErrorCategory.NO_COMPATIBLE_ROUTE -> Tune2AIException.NoCompatibleRoute("x")
                ErrorCategory.MODEL_UNAVAILABLE -> Tune2AIException.ModelUnavailable("x")
                ErrorCategory.RESOURCE_LIMIT -> Tune2AIException.ResourceLimit("x")
                ErrorCategory.BACKEND_FAILURE -> Tune2AIException.BackendFailure("x")
                ErrorCategory.TOOL_FAILURE -> Tune2AIException.ToolFailure("x")
                ErrorCategory.STORAGE_FAILURE -> Tune2AIException.StorageFailure("x")
                ErrorCategory.TIMEOUT -> Tune2AIException.Timeout("x")
            }
        }
        assertEquals(ErrorCategory.entries, all.map { it.category })
    }

    @Test
    fun requestIdsAreUniqueAndPrefixed() {
        val ids = List(1_000) { RequestId.random().value }
        assertEquals(1_000, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("req_") && it.length == 20 })
    }
}
