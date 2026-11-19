package com.tune2ai.sdk.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tune2ai.sdk.Tune2AI
import com.tune2ai.sdk.services.embedding.Candidate
import com.tune2ai.sdk.services.embedding.CosineReranker
import com.tune2ai.sdk.services.embedding.cosine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** T2AI-023 and T2AI-024 on a device, through the public API: real llama.cpp embeddings, reranking, and RAG over SQLite. */
@RunWith(AndroidJUnit4::class)
class EmbeddingDeviceTest {

    private fun sdk() = Tune2AI.Builder(InstrumentationRegistry.getInstrumentation().targetContext).addBackend(LlamaBackend()).build()

    @Test
    fun embedsAndRanksWithARealModel() = runBlocking<Unit> {
        val model = TestModel.embeddingDescriptor()
        sdk().use { ai ->
            ai.models.register(model)

            val result = ai.embeddings.embed(listOf("The cat sat on the mat.", "A kitten rests on a rug.", "Quarterly revenue grew by nine percent."))
            assertEquals("embed-test", result.modelId)
            assertTrue("dimensions reported", result.dimensions > 0)
            assertEquals(3, result.vectors.size)
            assertTrue(result.vectors.all { it.size == result.dimensions })
            val (cat, kitten, revenue) = result.vectors
            assertTrue("vectors are unit length", Math.abs(cosine(cat, cat) - 1.0) < 1e-3)
            assertTrue("paraphrase is closer than an unrelated sentence", cosine(cat, kitten) > cosine(cat, revenue))

            val ranked = CosineReranker(ai.embeddings).rerank(
                "feline resting on a carpet",
                listOf(Candidate("finance", "Quarterly revenue grew by nine percent."), Candidate("cat", "The cat sat on the mat.")),
            )
            assertEquals("cat", ranked.first().candidate.id)
        }
    }

    @Test
    fun ragRetrievesBySemanticsAndHonoursUpdateAndDelete() = runBlocking<Unit> {
        val model = TestModel.embeddingDescriptor()
        sdk().use { ai ->
            ai.models.register(model)
            ai.rag.clear()
            try {
                ai.rag.add("pets", "Cats love to sleep in the sun and chase small toys.", mapOf("topic" to "animals"))
                ai.rag.add("finance", "The company reported strong quarterly revenue and rising profit margins.")
                ai.rag.add("cooking", "Simmer the tomato sauce slowly and add fresh basil at the end.")

                val hits = ai.rag.retrieve("What do kittens like to play with?", limit = 3)
                assertEquals("pets", hits.first().sourceId)
                assertEquals("animals", hits.first().metadata["topic"])

                ai.rag.update("pets", "Mountain glaciers are retreating because of warming temperatures.")
                assertTrue("old text is gone", ai.rag.retrieve("cats sleeping in the sun", limit = 10).none { it.text.contains("Cats") })

                assertTrue(ai.rag.delete("finance"))
                assertEquals(setOf("pets", "cooking"), ai.rag.sources().toSet())
            } finally {
                ai.rag.clear()
            }
        }
    }
}
