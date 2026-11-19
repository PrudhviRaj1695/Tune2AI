package com.tune2ai.sdk.docs

import com.tune2ai.sdk.Tune2AI
import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.Task

suspend fun rememberAndRecall(ai: Tune2AI): List<String> {
    ai.memory.write("default", "The user prefers the Kotlin language")
    return ai.memory.search("default", "Which language?").map { it.text }
}

suspend fun registerEmbeddingModel(ai: Tune2AI, path: String) {
    ai.models.register(
        ModelDescriptor(
            id = "minilm",
            name = "MiniLM",
            format = "gguf",
            path = path,
            memoryBytes = 40L * 1024 * 1024,
            contextLength = 512,
            tasks = setOf(Task.EMBEDDING),
        ),
    )
}

suspend fun indexAndRetrieve(ai: Tune2AI): List<String> {
    ai.rag.add("handbook", "The office wifi password rotates every monday morning", mapOf("team" to "it"))
    ai.rag.update("handbook", "The office wifi password rotates every friday") // replaces all chunks
    return ai.rag.retrieve("when does the wifi password rotate?", limit = 3).map { "${it.ref}: ${it.text}" }
}

suspend fun embeddingSize(ai: Tune2AI): Int = ai.embeddings.embed(listOf("hello", "world")).dimensions

fun switchOffKnowledge(ai: Tune2AI) {
    ai.memory.enabled = false
    ai.rag.enabled = false
}
