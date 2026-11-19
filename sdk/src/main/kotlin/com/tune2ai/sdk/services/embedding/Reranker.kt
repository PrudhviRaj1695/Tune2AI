package com.tune2ai.sdk.services.embedding

/** Something to rank against a query. [id] is the caller's handle for it. */
data class Candidate(val id: String, val text: String)

data class Ranked(val candidate: Candidate, val score: Double)

/**
 * Orders candidates by relevance to a query (FR-SVC-4). Implementations must be stable: candidates
 * with equal scores keep the order they were given in.
 */
interface Reranker {
    suspend fun rerank(query: String, candidates: List<Candidate>): List<Ranked>
}

/** Default reranker: re-scores candidates by embedding cosine similarity to the query. */
class CosineReranker(private val embeddings: EmbeddingService) : Reranker {
    override suspend fun rerank(query: String, candidates: List<Candidate>): List<Ranked> {
        if (candidates.isEmpty()) return emptyList()
        val vectors = embeddings.embed(listOf(query) + candidates.map { it.text }).vectors
        val q = vectors.first()
        // sortedByDescending is stable, so equal scores keep the caller's order.
        return candidates.mapIndexed { i, c -> Ranked(c, cosine(q, vectors[i + 1])) }.sortedByDescending { it.score }
    }
}
