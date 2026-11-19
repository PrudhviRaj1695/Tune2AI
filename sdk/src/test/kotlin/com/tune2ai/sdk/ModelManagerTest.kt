package com.tune2ai.sdk

import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.ModelState
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.services.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

internal fun TestScope.modelManager(
    vararg backends: FakeBackend = arrayOf(FakeBackend()),
    resources: FakeResourceMonitor = FakeResourceMonitor(),
    storage: Storage = InMemoryStorage(),
    limits: ResourceLimits = ResourceLimits(minFreeMemoryBytes = GB / 2),
    scope: CoroutineScope = backgroundScope,
) = ModelManager(backends.toList(), storage, resources, limits, scope, fileExists = { "missing" !in it })

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {

    @Test
    fun concurrentAcquiresLoadOnce() = runTest {
        val backend = FakeBackend(loadDelayMillis = 100)
        val manager = modelManager(backend)
        val model = fakeModel()
        manager.register(model)
        val leases = List(5) { async { manager.acquire(model, backend) } }.awaitAll()
        assertEquals(1, backend.loads)
        assertEquals(ModelState.Loaded("fake"), manager.state(model.id))
        leases.forEach { it.close() }
    }

    @Test
    fun lowMemoryUnloadsIdleModelsButNotLeasedOnes() = runTest {
        val backend = FakeBackend()
        val resources = FakeResourceMonitor()
        val manager = modelManager(backend, resources = resources)
        val idle = fakeModel("idle")
        val busy = fakeModel("busy")
        manager.register(idle)
        manager.register(busy)
        manager.acquire(idle, backend).close()
        val lease = manager.acquire(busy, backend)

        resources.snapshot.value = snapshot(lowMemory = true)
        runCurrent()

        assertEquals(1, backend.unloads)
        assertEquals(ModelState.Loaded("fake"), manager.state("busy"))
        lease.close()
        manager.trim()
        assertEquals(2, backend.unloads)
    }

    @Test
    fun modelsThatCannotRunAreUnavailableWithAReason() = runTest {
        val manager = modelManager(resources = FakeResourceMonitor(snapshot(availableGb = 2.0)))
        manager.register(fakeModel("huge", memoryGb = 3.0))
        manager.register(fakeModel("gone", path = "/models/missing.fake"))
        manager.register(fakeModel("onnx", format = "onnx"))
        manager.register(fakeModel("fits", memoryGb = 1.0))

        assertTrue((manager.state("huge") as ModelState.Unavailable).reason.contains("only 2048 MB is free"))
        assertTrue((manager.state("gone") as ModelState.Unavailable).reason.contains("not found"))
        assertTrue((manager.state("onnx") as ModelState.Unavailable).reason.contains("No registered backend runs onnx"))
        assertEquals(listOf("fits"), manager.candidates(Task.CHAT).map { it.model.id })
    }

    @Test
    fun acquireRefusesWhenTheModelDoesNotFit() = runTest {
        val backend = FakeBackend()
        val manager = modelManager(backend, resources = FakeResourceMonitor(snapshot(availableGb = 1.0)))
        val model = fakeModel(memoryGb = 2.0)
        manager.register(model)
        try {
            manager.acquire(model, backend)
            fail("expected a resource limit")
        } catch (e: Tune2AIException) {
            assertEquals(ErrorCategory.RESOURCE_LIMIT, e.category)
            assertEquals(0, backend.loads)
        }
    }

    @Test
    fun failedLoadIsReportedAndClearedByReRegistering() = runTest {
        val backend = FakeBackend()
        val manager = modelManager(backend)
        val broken = fakeModel(path = "/models/tiny.broken")
        manager.register(broken)
        runCatching { manager.acquire(broken, backend) }
        assertTrue(manager.state(broken.id) is ModelState.Failed)
        assertTrue(manager.candidates(Task.CHAT).isEmpty())

        manager.register(broken.copy(path = "/models/tiny.fake"))
        assertEquals(ModelState.Unloaded, manager.state(broken.id))
    }

    @Test
    fun catalogSurvivesRestart() = runTest {
        val storage = InMemoryStorage()
        modelManager(storage = storage).register(fakeModel("kept"))
        val restarted = modelManager(storage = storage)
        restarted.restore()
        assertEquals(listOf("kept"), restarted.models().map { it.id })
    }

    @Test
    fun statesFlowReflectsLoading() = runTest {
        val backend = FakeBackend()
        val manager = modelManager(backend)
        val model = fakeModel()
        manager.register(model)
        assertEquals(ModelState.Unloaded, manager.states.value[model.id])
        val lease = manager.acquire(model, backend)
        assertEquals(ModelState.Loaded("fake"), manager.states.value[model.id])
        lease.close()
        manager.unload(model.id)
        assertEquals(ModelState.Unloaded, manager.states.value[model.id])
    }
}
