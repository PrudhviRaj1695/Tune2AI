# Writing an inference backend

A backend runs models of one or more formats behind the `InferenceBackend` interface (`sdk/src/main/kotlin/com/tune2ai/sdk/inference/InferenceBackend.kt`). Nothing above that interface knows which backend runs a request, so a new backend needs no change to the public API, the router, or the Model Manager.

Existing backends to copy from: `:sdk-llama` (`LlamaBackend`, native code through JNI) and `:sdk-remote` (`RemoteBackend`, HTTPS + SSE).

## 1. Create a module

One module per backend (decision D3), so apps only ship what they use. Depend on `:sdk` with `api project(':sdk')`. Declare permissions in the backend's own manifest (for example `INTERNET`); `:sdk` must stay permission-free (NFR-1).

## 2. Implement the interface

```kotlin nocheck
class MyBackend : InferenceBackend {
    override val id = "my-backend"                       // lowercase, [a-z0-9._-]
    override val capabilities = BackendCapabilities(
        formats = setOf("myformat"),                      // matched against ModelDescriptor.format
        tasks = setOf(Task.CHAT),
        requiresNetwork = false,                          // true skips file and memory checks
    )
    override val unavailableReason: String? get() = null  // e.g. "No API key configured"

    override suspend fun load(model: ModelDescriptor): LoadedModel { … }
    override suspend fun unload(model: LoadedModel) { … }
    override fun generate(model: LoadedModel, request: GenerateRequest): Flow<String> = …
}
```

| Concern | Rule |
|---|---|
| Lifecycle | Only the Model Manager calls `load`/`unload`. Keep native or network resources inside your `LoadedModel`; release them in `unload`. |
| Blocking work | Never block the caller's thread. Run native or blocking I/O on your own dispatcher (`withContext`), as `LlamaBackend` does. |
| Streaming | `generate` emits text pieces in order. Emit complete UTF-8 characters only. Apply the model's chat template yourself; `request.messages` is the full chat. |
| Cancellation | Cancelling the collector must stop work within 500 ms (NFR-7). Check for cancellation per token and interrupt long blocking calls (see the watcher coroutine in `LlamaBackend.generate` and `RemoteBackend.generate`). |
| Errors | Throw `Tune2AIException`: an invalid or missing model is `ModelUnavailable`, a resource problem is `ResourceLimit`, anything else is `BackendFailure`. Never put credentials or user content in messages. |
| Concurrency | The SDK may call `generate` concurrently on the same model. Serialize per model if the runtime is not thread-safe. |
| Availability | Report device- or configuration-level problems through `unavailableReason`; models on an unavailable backend show as `Unavailable` with that reason, and routing explains it. |
| Embeddings (optional) | To serve `Task.EMBEDDING`, list it in `capabilities.tasks` and override `embed(model, texts)`: one vector per text, in order, all the same length, ideally L2-normalised. Reject texts you cannot embed with a typed error; inputs beyond the model's context may be truncated. Backends that do not override it get a `NoCompatibleRoute` error. `LlamaBackend` loads a model registered with only `EMBEDDING` in `tasks` in embedding mode. |

## 3. Pass the contract tests

Every backend runs the shared suite `InferenceBackendContract` (`sdk/src/sharedTest`): load/generate/unload, cancellation within 500 ms, and `ModelUnavailable` for a broken model.

```kotlin nocheck
class MyBackendContractTest : InferenceBackendContract() {
    override fun backend() = MyBackend()
    override fun validModel() = …   // a real, small model
    override fun brokenModel() = …  // exists but is not a valid model
}
```

Add `test.kotlin.srcDir '../sdk/src/sharedTest/kotlin'` (JVM) or `androidTest.kotlin.srcDir …` (device) to your module, as `:sdk-remote` and `:sdk-llama` do.

## 4. Register it

```kotlin nocheck
val ai = Tune2AI.Builder(context)
    .addBackend(LlamaBackend())
    .addBackend(MyBackend())
    .build()
ai.backends()   // every backend with its unavailableReason
```

Registration fails fast on a duplicate id, an invalid id, or empty formats/tasks.
