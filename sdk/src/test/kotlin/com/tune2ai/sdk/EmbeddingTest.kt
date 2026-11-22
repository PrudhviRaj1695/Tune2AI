package com.tune2ai.sdk

import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.services.embedding.CosineReranker
import com.tune2ai.sdk.services.embedding.EmbeddingService
import com.tune2ai.sdk.services.embedding.RerankCandidate
import com.tune2ai.sdk.services.embedding.cosine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingTest {
    private val embedder = fakeModel("embedder", memoryGb = 0.1, contextLength = 512, tasks = setOf(Task.EMBEDDING))

    @Test
    fun embeddingReportsModelDimensionsAndInputLimit() = runTest {
        val manager = modelManager()
        manager.register(fakeModel("chat"))
        manager.register(embedder)
        val service = EmbeddingService(manager, Router(manager))

        val result = service.embed(listOf("red apples", "green apples"))
        assertEquals("embedder", result.modelId)
        assertEquals(256, result.dimensions)
        assertEquals(512, result.maxInputTokens)
        assertEquals(2, result.vectors.size)
        assertEquals("embedder", service.modelId())
    }

    @Test
    fun chatModelsAreNeverUsedForEmbedding() = runTest {
        val manager = modelManager()
        manager.register(fakeModel("chat"))
        val service = EmbeddingService(manager, Router(manager))
        assertFalse(service.isAvailable())
        assertNull(service.modelId())
        val error = runCatching { service.embed(listOf("x")) }.exceptionOrNull()
        assertTrue(error is Tune2AIException.NoCompatibleRoute)
    }

    @Test
    fun inconsistentDimensionsAreATypedError() = runTest {
        val backend = FakeBackend().apply { embedOverride = { texts -> texts.mapIndexed { i, _ -> FloatArray(if (i == 0) 4 else 8) { 1f } } } }
        val manager = modelManager(backend)
        manager.register(embedder)
        val error = runCatching { EmbeddingService(manager, Router(manager)).embed(listOf("a", "b")) }.exceptionOrNull()
        assertTrue(error is Tune2AIException.BackendFailure)
    }

    @Test
    fun cosineRejectsDimensionMismatch() {
        assertThrows(Tune2AIException.InvalidRequest::class.java) { cosine(FloatArray(3), FloatArray(4)) }
        assertEquals(1f, cosine(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)), 1e-6f)
    }

    @Test
    fun rerankingIsStableForEqualScores() {
        val q = floatArrayOf(1f, 0f)
        val ranked = CosineReranker().rerank(
            "q", q,
            listOf(
                RerankCandidate("a", "", floatArrayOf(0f, 1f)),
                RerankCandidate("b", "", floatArrayOf(1f, 0f)),
                RerankCandidate("c", "", floatArrayOf(0f, 1f)),
                RerankCandidate("d", "", null),
            ),
        )
        assertEquals(listOf("b", "a", "c", "d"), ranked.map { it.id })
    }
}
