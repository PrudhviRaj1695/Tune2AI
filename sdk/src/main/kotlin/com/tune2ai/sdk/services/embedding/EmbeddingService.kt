package com.tune2ai.sdk.services.embedding

import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.core.Router
import com.tune2ai.sdk.services.model.ModelManager
import kotlin.math.sqrt

/**
 * Vectors from one embedding model (FR-SVC-4).
 *
 * @param modelId the model that produced them; vectors from different models are not comparable.
 * @param dimensions length of every vector.
 * @param maxInputTokens input limit of the model; longer texts were truncated.
 */
class Embeddings(
    val modelId: String,
    val dimensions: Int,
    val maxInputTokens: Int,
    val vectors: List<FloatArray>,
)

/**
 * Turns text into vectors with a registered [Task.EMBEDDING] model. The model is chosen by the
 * [Router] and held through the [ModelManager], like any other request.
 */
class EmbeddingService(private val models: ModelManager, private val router: Router) {

    /** True if a model that can embed is registered and available. */
    fun isAvailable(): Boolean = models.candidates(Task.EMBEDDING).isNotEmpty()

    /**
     * Id of the model [embed] would use: [preferred] when it is registered and available, otherwise
     * the router's choice; null if no embedding model is available.
     */
    @JvmOverloads
    fun modelId(preferred: String? = null): String? =
        preferred?.let { p -> runCatching { router.route(Task.EMBEDDING, RequestOptions(model = p)).model.id }.getOrNull() }
            ?: runCatching { router.route(Task.EMBEDDING, RequestOptions()).model.id }.getOrNull()

    /**
     * @throws Tune2AIException.NoCompatibleRoute if no embedding model is available.
     * @throws Tune2AIException.BackendFailure if the backend returns vectors of mixed or wrong length.
     */
    suspend fun embed(texts: List<String>, modelId: String? = null): Embeddings {
        val route = router.route(Task.EMBEDDING, RequestOptions(model = modelId))
        if (texts.isEmpty()) return Embeddings(route.model.id, 0, route.model.contextLength, emptyList())
        val vectors = models.acquire(route.model, route.backend).use { lease -> route.backend.embed(lease.model, texts) }
        if (vectors.size != texts.size) {
            throw Tune2AIException.BackendFailure("Embedding returned ${vectors.size} vectors for ${texts.size} texts")
        }
        val dimensions = vectors.first().size
        if (dimensions == 0 || vectors.any { it.size != dimensions }) {
            throw Tune2AIException.BackendFailure("Embedding vectors from ${route.model.id} have inconsistent dimensions")
        }
        return Embeddings(route.model.id, dimensions, route.model.contextLength, vectors)
    }
}

/** Cosine similarity of two vectors of equal length. */
fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) throw Tune2AIException.InvalidRequest("Vector dimensions differ: ${a.size} and ${b.size}")
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
}
