# Model API

`ai.models` is the catalog your app controls. The SDK does not download models: you give it a file path (or a remote model name) and it handles compatibility, loading, memory limits, and unloading.

## Register and list

Registering stores a `ModelDescriptor` (id, name, format, path, memory estimate, context length, tasks, allowed backends, version). It is remembered across restarts. A model whose file is missing, whose format no backend supports, or that does not fit in memory shows as `Unavailable` with the reason.

```kotlin
suspend fun describeModels(ai: Tune2AI): List<String> =
    ai.models.list().map { "${it.descriptor.name}: ${it.state} on ${it.backends}" }
```

States: `Unloaded`, `Loading`, `Loaded(backendId)`, `Failed(reason)`, `Unavailable(reason)`. `ai.models.states` is a `StateFlow` of every model's state and `ai.models.state(id)` follows one, which is what a UI needs.

## Load and unload

Models load on the first request that needs them. To avoid that wait, load ahead of time:

```kotlin
suspend fun preload(ai: Tune2AI, id: String) {
    ai.models.load(id) // loads ahead of the first request
    // ... later, when the screen closes:
    ai.models.unload(id)
}
```

A loaded model stays loaded until you unload it, or until the Model Manager trims it under memory pressure. A model that a request is using is never trimmed.

## Backends

```kotlin
fun explainBackends(ai: Tune2AI): List<String> =
    ai.backends().map { backend ->
        backend.unavailableReason?.let { "${backend.id} unavailable: $it" } ?: "${backend.id} ready"
    }
```

Each `BackendInfo` lists the formats and tasks the backend supports and whether it needs the network. A backend can be unavailable for configuration reasons (the remote backend without an API key); its models then show as `Unavailable` with that reason.

## Tasks

A model's `tasks` say what it may be used for: `CHAT` (the default) and `EMBEDDING`. Register an embedding model with `tasks = setOf(Task.EMBEDDING)` (see [knowledge.md](knowledge.md)). The router never sends a chat request to an embedding-only model.

## Java

`listBlocking()`, `registerBlocking(...)`, and `loadBlocking(...)` are blocking versions for background threads.
