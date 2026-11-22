package com.tune2ai.sdk.api

import kotlinx.serialization.Serializable

/**
 * A document the app added to the knowledge index (FR-SVC-5).
 *
 * @param id the app's stable id for it, for example a file URI. Adding the same id replaces it.
 * @param embeddingModel model its chunks were embedded with, or null if it is searched by keyword.
 */
@Serializable
data class KnowledgeSource(
    val id: String,
    val name: String,
    val metadata: Map<String, String> = emptyMap(),
    val chunks: Int,
    val characters: Int,
    val addedAtMillis: Long,
    val embeddingModel: String? = null,
)

/** A retrieved piece of a source, best first. [score] is cosine similarity, or a keyword score. */
data class RetrievedChunk(
    val sourceId: String,
    val sourceName: String,
    val index: Int,
    val text: String,
    val score: Float,
    val byEmbedding: Boolean,
    val metadata: Map<String, String>,
)
