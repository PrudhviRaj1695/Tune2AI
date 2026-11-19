package com.tune2ai.sdk.services.embedding

import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.services.model.ModelManager

/** Turns text into a vector for similarity search. [EmbeddingService] is the real one; tests pass lambdas. */
fun interface Embedder {
    suspend fun embed(text: String): FloatArray
}

/**
 * Vectors for a batch of texts (FR-SVC-4), with the identity and limits of the model that made them,
 * so callers can store them alongside and detect a model change later.
 *
 * @param inputLimit the model's context length in tokens; longer inputs may be truncated by the backend.
 */
class EmbeddingResult(
    val modelId: String,
    val dimensions: Int,
    val inputLimit: Int,
    val vectors: List<FloatArray>,
)

/**
 * Embeds text with an embedding-capable model. Routes through the [Router] and holds the model with a
 * lease from the [ModelManager], so loading, memory limits, and trimming apply as for any request.
 * Also an [Embedder], which plugs it into memory search.
 */
class EmbeddingService(
    private val models: ModelManager,
    private val router: Router,
) : Embedder {

    suspend fun embed(texts: List<String>, options: RequestOptions = RequestOptions()): EmbeddingResult {
        if (texts.isEmpty()) throw Tune2AIException.InvalidRequest("Nothing to embed")
        if (texts.any { it.isBlank() }) throw Tune2AIException.InvalidRequest("Texts to embed must not be blank")
        val route = router.route(Task.EMBEDDING, options)
        val vectors = models.acquire(route.model, route.backend).use { lease ->
            route.backend.embed(lease.model, texts)
        }
        if (vectors.size != texts.size) {
            throw Tune2AIException.BackendFailure("Backend '${route.backend.id}' returned ${vectors.size} vectors for ${texts.size} texts")
        }
        val dimensions = vectors.first().size
        if (dimensions == 0 || vectors.any { it.size != dimensions }) {
            throw Tune2AIException.BackendFailure("Backend '${route.backend.id}' returned vectors of inconsistent size")
        }
        return EmbeddingResult(route.model.id, dimensions, route.model.contextLength, vectors)
    }

    override suspend fun embed(text: String): FloatArray = embed(listOf(text)).vectors.single()
}

/** Cosine similarity of two vectors; a dimension mismatch is a typed error, not a wrong number. */
fun cosine(a: FloatArray, b: FloatArray): Double {
    if (a.size != b.size) throw Tune2AIException.InvalidRequest("Embedding dimensions differ: ${a.size} vs ${b.size} (vectors from different models?)")
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / (Math.sqrt(na) * Math.sqrt(nb))
}
