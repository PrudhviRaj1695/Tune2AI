package com.tune2ai.sdk.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tune2ai.sdk.api.ResourceLimits
import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.platform.android.AndroidPlatform
import com.tune2ai.sdk.services.embedding.Candidate
import com.tune2ai.sdk.services.embedding.CosineReranker
import com.tune2ai.sdk.services.embedding.EmbeddingService
import com.tune2ai.sdk.services.embedding.cosine
import com.tune2ai.sdk.services.model.ModelManager
import com.tune2ai.sdk.services.rag.RagEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** T2AI-023 on a device: real llama.cpp embeddings through EmbeddingService and the cosine reranker. */
@RunWith(AndroidJUnit4::class)
class EmbeddingDeviceTest {

    @Test
    fun embedsAndRanksWithARealModel() = runBlocking<Unit> {
        val model = TestModel.embeddingDescriptor()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        AndroidPlatform(InstrumentationRegistry.getInstrumentation().targetContext).use { platform ->
            try {
                val models = ModelManager(listOf(LlamaBackend()), InMemoryStorage(), platform.resources, ResourceLimits(), scope)
                models.register(model)
                val service = EmbeddingService(models, Router(models))

                val result = service.embed(listOf("The cat sat on the mat.", "A kitten rests on a rug.", "Quarterly revenue grew by nine percent."))
                assertEquals("embed-test", result.modelId)
                assertTrue("dimensions reported", result.dimensions > 0)
                assertEquals(3, result.vectors.size)
                assertTrue(result.vectors.all { it.size == result.dimensions })
                val (cat, kitten, revenue) = result.vectors
                assertTrue("vectors are unit length", Math.abs(cosine(cat, cat) - 1.0) < 1e-3)
                assertTrue("paraphrase is closer than an unrelated sentence", cosine(cat, kitten) > cosine(cat, revenue))

                val ranked = CosineReranker(service).rerank(
                    "feline resting on a carpet",
                    listOf(Candidate("finance", "Quarterly revenue grew by nine percent."), Candidate("cat", "The cat sat on the mat.")),
                )
                assertEquals("cat", ranked.first().candidate.id)
            } finally {
                scope.cancel()
            }
        }
    }

    /** T2AI-024 on a device: RAG over the real SQLite Storage with real embeddings. */
    @Test
    fun ragRetrievesBySemanticsAndHonoursUpdateAndDelete() = runBlocking<Unit> {
        val model = TestModel.embeddingDescriptor()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        AndroidPlatform(InstrumentationRegistry.getInstrumentation().targetContext).use { platform ->
            val models = ModelManager(listOf(LlamaBackend()), InMemoryStorage(), platform.resources, ResourceLimits(), scope)
            models.register(model)
            val rag = RagEngine(platform.storage, EmbeddingService(models, Router(models)))
            rag.clear()
            try {
                rag.add("pets", "Cats love to sleep in the sun and chase small toys.", mapOf("topic" to "animals"))
                rag.add("finance", "The company reported strong quarterly revenue and rising profit margins.")
                rag.add("cooking", "Simmer the tomato sauce slowly and add fresh basil at the end.")

                val hits = rag.retrieve("What do kittens like to play with?", limit = 3)
                assertEquals("pets", hits.first().sourceId)
                assertEquals("animals", hits.first().metadata["topic"])

                rag.update("pets", "Mountain glaciers are retreating because of warming temperatures.")
                assertTrue("old text is gone", rag.retrieve("cats sleeping in the sun", limit = 10).none { it.text.contains("Cats") })

                assertTrue(rag.delete("finance"))
                assertEquals(setOf("pets", "cooking"), rag.sources().toSet())
            } finally {
                rag.clear()
                scope.cancel()
            }
        }
    }
}
