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

/**
 * A retrieved passage of a source, best first.
 *
 * @param index first chunk of the passage (0-based); [lastIndex] is the last one when neighbouring
 *   chunks were merged into one passage.
 * @param score cosine similarity when [byEmbedding], else keyword relevance scaled to 0..1.
 * @param section the Markdown heading the passage sits under, if any.
 */
data class RetrievedChunk(
    val sourceId: String,
    val sourceName: String,
    val index: Int,
    val text: String,
    val score: Float,
    val byEmbedding: Boolean,
    val metadata: Map<String, String>,
    val lastIndex: Int = index,
    val section: String? = null,
) {
    /** Stable reference used in request traces, e.g. "notes.md#2" or "notes.md#2-3". */
    val ref: String get() = "$sourceName#${index + 1}" + if (lastIndex > index) "-${lastIndex + 1}" else ""
}
