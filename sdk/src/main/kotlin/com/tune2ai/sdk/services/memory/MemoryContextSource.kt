package com.tune2ai.sdk.services.memory

import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.services.context.ContextItem
import com.tune2ai.sdk.services.context.ContextSource

/**
 * Feeds a session's memory into the Context Manager (FR-SVC-6) — memory's only path to a request.
 * Returns nothing while [MemoryEngine.enabled] is false.
 */
class MemoryContextSource(
    private val memory: MemoryEngine,
    private val session: String,
    private val limit: Int = 3,
) : ContextSource {
    override val name = "memory"

    override suspend fun fetch(query: String, model: ModelDescriptor): List<ContextItem> =
        if (!memory.enabled) emptyList()
        else memory.search(session, query, limit).map { ContextItem(it.id, it.text) }
}
