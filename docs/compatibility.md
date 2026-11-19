# Supported models and backends

What the project has actually run is listed separately from what should work, so you can tell tested from expected.

## Backends

| Backend | Module | Model format | Tasks | Platforms | Network |
|---|---|---|---|---|---|
| llama.cpp | `:sdk-llama` | `gguf` | chat, embedding | Android `arm64-v8a` (devices), `x86_64` (emulator) | none |
| Remote API | `:sdk-remote` | `remote` (an OpenAI-compatible Chat Completions endpoint, streamed over SSE) | chat | any Android device | HTTPS to your endpoint |
| ONNX Runtime | `:sdk-onnx` | `onnx` | | | Deferred (decision D8): llama.cpp and the Remote API cover on-device and large models |
| Core ML | iOS | `coreml` | | iOS | Later (milestone 8, needs the Kotlin Multiplatform conversion) |

llama.cpp is pinned to release `b12522`. It runs on the CPU (no GPU offload), using about half the device's cores, up to four.

## Models verified by the project

| Model | Format | Used for | How it was checked |
|---|---|---|---|
| SmolLM2-135M-Instruct, Q8_0 | GGUF | chat | Device test suite (chat streaming, prompt cache, backend contract). Too small to follow agent tool calls reliably. |
| Qwen2.5-0.5B-Instruct | GGUF | chat, agents, workflows | Run in Tune2AI Studio on the Android emulator: chat, agent tool calls, and a workflow with a tool step. |
| all-MiniLM-L6-v2, Q8_0 | GGUF | embeddings, RAG | Device test suite: embeddings, cosine reranking, and RAG over the real SQLite storage. |

## Models that should work

Any GGUF model whose architecture the pinned llama.cpp supports should load. Instruct-tuned models with a chat template that llama.cpp can apply work best; a model without one fails with `MODEL_UNAVAILABLE` ("does not support the chat template").

The project has not run these, so treat them as expected rather than verified:

- **Gemma 3 1B instruct (Q4_K_M, about 0.8 GB)**: a sensible step up from the 0.5B models on a phone. Check it in your own app before relying on it.
- Other small instruct models in the 0.5B to 3B range (Llama 3.2, Qwen2.5, Phi, SmolLM2 variants).
- Other BERT-style GGUF embedding models, registered with `tasks = setOf(Task.EMBEDDING)`.

Sizing: a model needs roughly its file size plus about 10% and the KV cache in memory. Register that as `memoryBytes`; the SDK refuses to load a model that would not leave `minFreeMemoryBytes` free. 4 GB devices suit models up to about 1B parameters at 4-bit; larger models need more memory than most phones keep free.

Performance depends heavily on the device. On the x86_64 emulator, Qwen2.5-0.5B generated roughly 6 to 10 tokens per second, which is not representative of a physical arm64 phone. Measure on your target devices.

## Remote endpoints

Any server speaking the OpenAI Chat Completions API (`POST {baseUrl}/chat/completions`, streaming) works: set the base URL (ending in `/v1`), the API key supplier, and a model name. Register the model with the remote format; there is no file, so the model name goes in `path`:

```kotlin nocheck
ai.models.register(
    ModelDescriptor(
        id = "remote-model",
        name = "gpt-4o-mini",
        format = RemoteBackend.FORMAT,
        path = "gpt-4o-mini",
        memoryBytes = 0,
        contextLength = 8192,
        tasks = setOf(Task.CHAT),
    ),
)
```

Release builds require HTTPS. The API key is read from your supplier on each request and is never stored by the SDK. The remote backend does not provide embeddings.

## Platform requirements

| | Requirement |
|---|---|
| Android | 8.0 (API 26) or newer; compile and target SDK 36 |
| Native backends | `arm64-v8a` devices; `x86_64` for the emulator |
| Build | Android Gradle Plugin 9.2, Gradle 9.4, JDK 17+ (Android Studio's JDK works) |
