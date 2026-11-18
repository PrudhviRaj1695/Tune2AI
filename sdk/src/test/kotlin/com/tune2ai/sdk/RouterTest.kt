package com.tune2ai.sdk

import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException.NoCompatibleRoute
import com.tune2ai.sdk.core.Router
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    @Test
    fun picksTheSmallestModelWhenNothingIsLoaded() = runTest {
        val manager = modelManager()
        manager.register(fakeModel("big", memoryGb = 2.0))
        manager.register(fakeModel("small", memoryGb = 0.5))
        assertEquals("small", Router(manager).route(Task.CHAT, RequestOptions()).model.id)
    }

    @Test
    fun prefersAnAlreadyLoadedModel() = runTest {
        val backend = FakeBackend()
        val manager = modelManager(backend)
        val big = fakeModel("big", memoryGb = 2.0)
        manager.register(big)
        manager.register(fakeModel("small", memoryGb = 0.5))
        manager.acquire(big, backend).close()
        assertEquals("big", Router(manager).route(Task.CHAT, RequestOptions()).model.id)
    }

    @Test
    fun honoursAnExplicitModelAndBackend() = runTest {
        val manager = modelManager(FakeBackend("a"), FakeBackend("b"))
        manager.register(fakeModel("big", memoryGb = 2.0))
        manager.register(fakeModel("small", memoryGb = 0.5))
        val route = Router(manager).route(Task.CHAT, RequestOptions(model = "big", backend = "b"))
        assertEquals("big" to "b", route.model.id to route.backend.id)
    }

    @Test
    fun rejectsPreferencesItCannotHonourAndSaysWhy() = runTest {
        val manager = modelManager(resources = FakeResourceMonitor(snapshot(availableGb = 1.0)))
        manager.register(fakeModel("huge", memoryGb = 4.0))
        manager.register(fakeModel("ok", memoryGb = 0.1))
        val router = Router(manager)

        assertTrue(assertThrows(NoCompatibleRoute::class.java) { router.route(Task.CHAT, RequestOptions(model = "nope")) }
            .message!!.contains("not registered"))
        assertTrue(assertThrows(NoCompatibleRoute::class.java) { router.route(Task.CHAT, RequestOptions(model = "huge")) }
            .message!!.contains("unavailable"))
        assertTrue(assertThrows(NoCompatibleRoute::class.java) { router.route(Task.CHAT, RequestOptions(model = "ok", backend = "remote")) }
            .message!!.contains("Backend 'remote' is not registered"))
        assertTrue(assertThrows(NoCompatibleRoute::class.java) { router.route(Task.EMBEDDING, RequestOptions()) }
            .message!!.contains("does not support EMBEDDING"))
    }

    @Test
    fun explainsWhenNoModelIsRegistered() = runTest {
        val error = assertThrows(NoCompatibleRoute::class.java) { Router(modelManager()).route(Task.CHAT, RequestOptions()) }
        assertEquals("No models are registered", error.message)
    }
}
