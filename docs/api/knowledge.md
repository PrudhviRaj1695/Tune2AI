# Memory, embeddings, and RAG

Three services feed extra context into requests. They reach a model only through the Context Manager, which places them in this order: system prompt, memory, retrieved content, conversation history, your message. It keeps the total within the model's context minus `maxTokens`, and the trace reports what was included (for example `memory:mem_1a2b…`, `rag:handbook#0`) and what was left out.

## Memory

`ai.memory` holds facts the app chooses to remember, scoped to a session id and isolated between sessions. The SDK feeds the `"default"` session into every request.

```kotlin
suspend fun rememberAndRecall(ai: Tune2AI): List<String> {
    ai.memory.write("default", "The user prefers the Kotlin language")
    return ai.memory.search("default", "Which language?").map { it.text }
}
```

- `write`, `update`, `delete`, `list`, `search`, and `clear(session)` are explicit; nothing is remembered automatically.
- Search ranks by keyword overlap and drops entries with no overlap. An app can build `MemoryEngine` with an `Embedder` to rank by embedding similarity instead.
- `ai.memory.enabled = false` stops memory reaching requests; stored entries are kept.

## Embeddings

`ai.embeddings` turns text into vectors with an embedding model. Register one with the `EMBEDDING` task (it is then used for embeddings only, never chat):

```kotlin
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
```

```kotlin
suspend fun embeddingSize(ai: Tune2AI): Int = ai.embeddings.embed(listOf("hello", "world")).dimensions
```

The result reports the model id, the vector `dimensions`, and the model's input limit (its context length). With llama.cpp the vectors are unit length, and input longer than the context is truncated. Blank input fails with `INVALID_REQUEST`; with no embedding model registered the call fails with `NO_COMPATIBLE_ROUTE`. Comparing vectors of different sizes (from different models) is an `INVALID_REQUEST` error, not a wrong number.

`CosineReranker(ai.embeddings)` reorders candidates by similarity to a query. It is stable: equal scores keep the order you gave.

## RAG

`ai.rag` indexes your content as *sources*. Each source is split into overlapping chunks, embedded, and stored.

```kotlin
suspend fun indexAndRetrieve(ai: Tune2AI): List<String> {
    ai.rag.add("handbook", "The office wifi password rotates every monday morning", mapOf("team" to "it"))
    ai.rag.update("handbook", "The office wifi password rotates every friday") // replaces all chunks
    return ai.rag.retrieve("when does the wifi password rotate?", limit = 3).map { "${it.ref}: ${it.text}" }
}
```

- `add` indexes a new source and fails if the id exists; `update` replaces all of a source's chunks and fails if it does not; `delete` removes a source. Updated or deleted content never appears in later retrieval.
- `retrieve` returns the closest chunks, best first, each with its `sourceId`, position, text, metadata, and score.
- The index records which embedding model made each vector. If you change embedding models, retrieval fails with `INVALID_REQUEST` until you `clear()` and add the content again.
- Retrieval reads every stored chunk (brute force). That is fine for thousands of chunks on a phone, not for hundreds of thousands.
- With an empty index, requests behave as if RAG were absent and need no embedding model.

## Turning them off

```kotlin
fun switchOffKnowledge(ai: Tune2AI) {
    ai.memory.enabled = false
    ai.rag.enabled = false
}
```
