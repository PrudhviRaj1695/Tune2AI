# Getting started (Android)

Tune2AI runs chat, tool-using agents, and workflows on the device, with an optional remote model backend, through one entry point: `Tune2AI`.

Code blocks marked `kotlin` on this page and in [api/](api/) are compiled and run by the SDK's tests (`sdk/src/docsTest`), so they match the real API. Imports are omitted; the test sources have them. Blocks marked `kotlin nocheck` need a module the SDK tests do not depend on.

## Requirements

- Android 8.0 or newer (`minSdk 26`).
- Native on-device inference (`:sdk-llama`) needs an `arm64-v8a` device. `x86_64` is supported for the emulator.
- A model file. The SDK does not download models: your app supplies a GGUF file, or a remote endpoint. See [compatibility.md](compatibility.md).

## Modules

| Module | Adds | Notes |
|---|---|---|
| `:sdk` | The API (`Tune2AI`), orchestration, services, storage | No network code and no `INTERNET` permission |
| `:sdk-llama` | llama.cpp backend for GGUF models | Native library, arm64-v8a and x86_64 |
| `:sdk-remote` | OpenAI-compatible remote backend (HTTPS + SSE) | The only module that declares `INTERNET` |

Apps that never call a remote backend should not include `:sdk-remote`, and then make no network calls (NFR-1).

Tagged releases publish `com.tune2ai:tune2ai-sdk`, `tune2ai-sdk-llama`, and `tune2ai-sdk-remote` to GitHub Packages (see the [changelog](../CHANGELOG.md) for versions). GitHub Packages asks for a GitHub token even for public repositories:

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri('https://maven.pkg.github.com/PrudhviRaj1695/Tune2AI')
            credentials {
                username = settings.providers.gradleProperty('gpr.user').orNull ?: System.getenv('GITHUB_ACTOR')
                password = settings.providers.gradleProperty('gpr.token').orNull ?: System.getenv('GITHUB_TOKEN')
            }
        }
    }
}

// app/build.gradle
dependencies {
    implementation 'com.tune2ai:tune2ai-sdk:0.1.0'
    implementation 'com.tune2ai:tune2ai-sdk-llama:0.1.0'
}
```

Before the first release is tagged, or to try unreleased changes, add the modules from a checkout instead:

```groovy
// settings.gradle
include ':sdk', ':sdk-llama'

// app/build.gradle
dependencies {
    implementation project(':sdk')
    implementation project(':sdk-llama')
}
```

`:sdk-llama` builds llama.cpp, which is a git submodule: clone with `--recurse-submodules`. The first build installs the NDK and CMake and takes several minutes.

## Create the SDK

Build one `Tune2AI` per process and keep it for the app's lifetime.

```kotlin nocheck
val ai = Tune2AI.Builder(context)
    .addBackend(LlamaBackend())                       // :sdk-llama
    .addBackend(RemoteBackend({ baseUrl }, { apiKey })) // :sdk-remote, optional
    .build()
```

Any backend works the same way; here is the general form with a resource limit:

```kotlin
fun createSdk(context: Context, backend: InferenceBackend): Tune2AI =
    Tune2AI.Builder(context)
        .addBackend(backend)
        .resourceLimits(ResourceLimits(minFreeMemoryBytes = 512L * 1024 * 1024))
        .build()
```

`build()` fails fast: it throws `InvalidRequest` if no backend was added or the default prompt template was never registered, and `IllegalArgumentException` if a tool has an invalid name or schema.

## Register a model

Describe the model file once. The registration is remembered across restarts.

```kotlin
suspend fun registerModel(ai: Tune2AI, ggufPath: String, fileSizeBytes: Long) {
    ai.models.register(
        ModelDescriptor(
            id = "qwen2.5-0.5b",
            name = "Qwen2.5 0.5B Instruct",
            format = "gguf",
            path = ggufPath,
            memoryBytes = fileSizeBytes + fileSizeBytes / 10,
            contextLength = 4096,
        ),
    )
}
```

`memoryBytes` is what the model needs once loaded; the Model Manager refuses to load a model that would not fit (NFR-8). With llama.cpp, `LlamaBackend.estimateMemoryBytes(file, contextLength)` gives a reasonable value.

## Send a message

```kotlin
suspend fun askOnce(ai: Tune2AI): String {
    val answer = StringBuilder()
    ai.chat.send("What is the capital of France?").collect { event ->
        when (event) {
            is Tune2AIEvent.TextDelta -> answer.append(event.text)
            is Tune2AIEvent.Failed -> throw event.error
            else -> Unit
        }
    }
    return answer.toString()
}
```

Every request is a `Flow<Tune2AIEvent>`. See [api/events-and-errors.md](api/events-and-errors.md) for the event contract.

## Lifecycle

- Call `ai.close()` when you are done (for example in `Activity.onDestroy`). It cancels running requests, unloads models, and releases native resources.
- Models load on the first request and stay loaded. Under memory pressure the Model Manager unloads idle models; a model in use is never unloaded.
- Nothing blocks the main thread: inference and storage run on background dispatchers, and the callback API delivers events on a background thread. Post to the main thread yourself before touching views.
- Cancelling the collector (or calling `Cancellable.cancel()`) stops the work. Cancellation is not an error: a cancelled request ends with `Cancelled`.

## Storage and privacy

- Conversation history, model registrations, memory, RAG chunks, and workflow checkpoints are stored in a SQLite database in the app's private storage, in separate namespaces. The app owns that data; nothing expires on its own.
- The SDK never persists API keys. `RemoteBackend` reads the key from the function you give it, each time.
- With no remote backend configured, the SDK makes no network calls.
- Tool arguments and results are not written to logs.

## Resource behaviour

`ResourceLimits` tells the SDK when to refuse or stop work: free memory that must remain after a model loads, a thermal state at which new work is refused, and an optional minimum battery level. Violations surface as `RESOURCE_LIMIT` errors with a reason, and models that do not fit show as `Unavailable` with the reason. Read the current values with `ai.resources()`.

## Next

- [Chat](api/chat.md), [Agent](api/agent.md), [Workflow](api/workflow.md), [Models](api/models.md), [Memory, embeddings, and RAG](api/knowledge.md)
- [Events and errors](api/events-and-errors.md)
- [Supported models and backends](compatibility.md)
- Writing your own backend: [backend-guide.md](backend-guide.md)
