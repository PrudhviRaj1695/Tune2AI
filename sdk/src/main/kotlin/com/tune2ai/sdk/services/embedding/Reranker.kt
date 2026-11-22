package com.tune2ai.sdk.services.embedding

/** A candidate for reranking: an id, its text, and the vector it was indexed with (if any). */
class RerankCandidate(val id: String, val text: String, val vector: FloatArray?)

data class Ranked(val id: String, val score: Float)

/** Orders retrieval candidates for a query. Results are stable: equal scores keep input order. */
interface Reranker {
    fun rerank(query: String, queryVector: FloatArray?, candidates: List<RerankCandidate>): List<Ranked>
}

/**
 * Default reranker: cosine similarity against the query vector. Candidates without a vector score 0.
 * ponytail: a cross-encoder reranking model would rank better; plug one in behind [Reranker] if
 * retrieval quality needs it.
 */
class CosineReranker : Reranker {
    override fun rerank(query: String, queryVector: FloatArray?, candidates: List<RerankCandidate>): List<Ranked> =
        candidates
            .map { c -> Ranked(c.id, if (queryVector != null && c.vector != null) cosine(queryVector, c.vector) else 0f) }
            .sortedByDescending { it.score } // sortedBy* is stable
}
