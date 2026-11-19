package com.tune2ai.sdk

import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.services.embedding.Candidate
import com.tune2ai.sdk.services.embedding.CosineReranker
import com.tune2ai.sdk.services.embedding.EmbeddingService
import com.tune2ai.sdk.services.embedding.cosine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingTest {
    private val embedModel = fakeModel("embedder", tasks = setOf(Task.EMBEDDING))

    private suspend fun TestScope.service(backend: FakeBackend, withModel: Boolean = true): EmbeddingService {
        val manager = modelManager(backend)
        if (withModel) manager.register(embedModel)
        return EmbeddingService(manager, Router(manager))
    }

    @Test
    fun reportsModelIdentityDimensionsAndLimits() = runTest {
        val result = service(FakeBackend(embedDims = 16)).embed(listOf("hello world", "goodbye"))
        assertEquals("embedder", result.modelId)
        assertEquals(16, result.dimensions)
        assertEquals(4096, result.inputLimit)
        assertEquals(2, result.vectors.size)
        assertTrue(result.vectors.all { it.size == 16 })
    }

    @Test
    fun invalidInputsAndMissingServiceFailWithTypedErrors() = runTest {
        val svc = service(FakeBackend(embedDims = 8))
        assertEquals(ErrorCategory.INVALID_REQUEST, assertThrows(Tune2AIException::class.java) { kotlinx.coroutines.runBlocking { svc.embed(emptyList()) } }.category)
        assertEquals(ErrorCategory.INVALID_REQUEST, assertThrows(Tune2AIException::class.java) { kotlinx.coroutines.runBlocking { svc.embed(listOf(" ")) } }.category)

        // No embedding-capable model registered: the router says so.
        val none = service(FakeBackend(embedDims = 8), withModel = false)
        assertEquals(ErrorCategory.NO_COMPATIBLE_ROUTE, assertThrows(Tune2AIException::class.java) { kotlinx.coroutines.runBlocking { none.embed(listOf("x")) } }.category)
    }

    @Test
    fun aBackendWithoutEmbeddingsIsNotRoutedAndSaysSoIfCalledDirectly() = runTest {
        val chatOnly = FakeBackend()
        // Default embed() on a backend that never opted in is a typed error.
        val error = assertThrows(Tune2AIException.NoCompatibleRoute::class.java) {
            kotlinx.coroutines.runBlocking { chatOnly.embed(object : com.tune2ai.sdk.inference.LoadedModel { override val descriptor = embedModel }, listOf("x")) }
        }
        assertTrue(error.message!!.contains("does not support embeddings"))
        val svc = service(chatOnly) // model says EMBEDDING, but the backend does not offer it
        assertEquals(ErrorCategory.NO_COMPATIBLE_ROUTE, assertThrows(Tune2AIException::class.java) { kotlinx.coroutines.runBlocking { svc.embed(listOf("x")) } }.category)
    }

    @Test
    fun cosineDimensionMismatchIsATypedError() {
        assertEquals(1.0, cosine(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)), 1e-9)
        assertEquals(0.0, cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0.0)
        val e = assertThrows(Tune2AIException.InvalidRequest::class.java) { cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)) }
        assertTrue(e.message!!.contains("1 vs 2"))
    }

    @Test
    fun rerankingPutsTheMostSimilarFirstAndEqualScoresKeepInputOrder() = runTest {
        val reranker = CosineReranker(service(FakeBackend(embedDims = 64)))
        val ranked = reranker.rerank(
            "kotlin coroutines",
            listOf(Candidate("a", "gardening tips"), Candidate("b", "kotlin coroutines guide"), Candidate("c", "baking bread")),
        )
        assertEquals("b", ranked.first().candidate.id)

        // Identical texts score identically, so the caller's order is kept.
        val ties = reranker.rerank("kotlin", listOf(Candidate("x", "same text"), Candidate("y", "same text"), Candidate("z", "same text")))
        assertEquals(listOf("x", "y", "z"), ties.map { it.candidate.id })
        assertTrue(reranker.rerank("q", emptyList()).isEmpty())
    }

    @Test
    fun embeddingServiceBacksMemorySearch() = runTest {
        val backend = FakeBackend(embedDims = 64)
        val memory = com.tune2ai.sdk.services.memory.MemoryEngine(com.tune2ai.sdk.core.InMemoryStorage(), service(backend))
        memory.write("s", "I enjoy gardening in spring")
        memory.write("s", "my favourite language is kotlin")
        assertEquals("my favourite language is kotlin", memory.search("s", "which language kotlin").first().text)
        assertTrue(backend.embedCalls > 0)
    }
}
