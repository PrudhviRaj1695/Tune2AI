# Tune2AI architecture refinement

![Tune2AI revised architecture](architecture.svg)

Source: [scripts/architecture_diagram.py](../scripts/architecture_diagram.py) (`python scripts/architecture_diagram.py docs/architecture.svg`). Orange elements are the changes from the [original drawing](original-architecture.jpeg).

Requirements: [requirements.md](requirements.md). Backlog: [backlog/](backlog/README.md).

## Working assumption

Tune2AI is an on-device SDK. Everything in the diagram runs inside the application process. Remote execution is supported as one more inference backend, not as a separate cloud stack, so routing, context assembly, tools, and the event contract are the same whether a model runs locally or remotely.

## Changes from the original drawing

1. **One request path.** Chat, Agent, and Workflow APIs all enter through the Request Controller. The Workflow API no longer bypasses it to reach the Workflow Engine, so validation, cancellation, and request IDs are handled once.
2. **The Router asks the Model Manager.** The Model & Task Router selects a model from the Model Manager's catalog, availability, and load state before calling the Inference Abstraction. Availability is decided in one place.
3. **The Model Manager owns load/unload.** Model lifecycle reaches the runtime through the Model Manager. The Model API talks to the Model Manager directly because lifecycle calls are not inference requests.
4. **Context goes through the Context Manager only.** The direct Agent Engine → Memory Engine edge is removed. Agent and workflow model steps go back through the Request Controller, which asks the Context Manager, so context limits are enforced in one component.
5. **Resource signals flow upward.** The Device Resource Monitor feeds the Model Manager, which lets routing and model loading react to memory, battery, and thermal pressure.
6. **Remote is a backend.** "Remote API" sits next to llama.cpp, ONNX Runtime, and Core ML behind the Inference Abstraction. It is optional and uses HTTPS with SSE streaming.
7. **Native adapters implement storage and resource signals.** Inference backends reach native code through the Android (Kotlin/JNI) or iOS (Swift) adapter. Each adapter provides Local Storage and Device Resource Monitor.

## Responsibilities

| Component | Owns | Should not own |
|---|---|---|
| SDK APIs | Stable typed interface, cancellation, streaming events, error types | Routing rules, backend details |
| Request Controller | Validation, request lifecycle, request IDs, result assembly | Model selection logic, backend protocols |
| Model & Task Router | Choosing a model and backend for a task | Model metadata or load state (asks Model Manager) |
| Agent / Workflow Engines | Step execution, iteration limits, checkpoints, tool handoffs | Direct memory or storage access outside their checkpoints |
| Tool Calling Engine | Schema validation, permissions, invocation, timeouts | Unrestricted access to host resources |
| Context Manager | Prompt assembly, retrieval, memory reads/writes, context limits | Tool execution |
| Model Manager | Catalog, compatibility, availability, load/unload | Request routing policy |
| Inference backends | Translating the shared inference contract to llama.cpp, ONNX Runtime, Core ML, or a remote API | Product-level policy |
| Native adapters | Platform calls, Local Storage, resource signals | Shared runtime logic |

## Technical decisions

These are the defaults the backlog tasks are written against. Change a decision here first, then update the affected stories.

| ID | Decision | Why | Revisit when |
|---|---|---|---|
| D1 | The SDK is written in **Kotlin** as Android library modules. Shared runtime code (`api`, `core`, `services`, `inference` packages) must not import `android.*`. | Matches the Kotlin/JNI adapter in the diagram. Keeping Android imports out of the shared packages lets them move to Kotlin Multiplatform `commonMain` without rewrites. | iOS work starts (milestone 8): convert to Kotlin Multiplatform and add an iOS target. |
| D2 | Streaming uses **kotlinx-coroutines `Flow`**; cancellation is coroutine cancellation. JSON (tool schemas, workflow definitions, checkpoints) uses **kotlinx-serialization-json**. | Standard Kotlin streaming and cancellation; both libraries are multiplatform and work in JVM unit tests, unlike `org.json`. | — |
| D3 | Module layout: `:sdk` (APIs, orchestration, services, inference abstraction, Android adapter), `:sdk-llama` (llama.cpp via NDK/JNI), `:sdk-onnx`, `:sdk-remote`. `:app` (Tune2AI Studio) is the Android sample. | Apps include only the backends they use; `:sdk` alone has no native code and no network permission. | A module would hold one class: merge it. |
| D4 | The Remote API backend uses `HttpURLConnection` and parses SSE by hand. | Avoids an HTTP client dependency for one streaming endpoint. | Retries, HTTP/2, or several providers make it painful: switch to OkHttp. |
| D5 | Local storage starts as SQLite through the Android framework API (`SQLiteOpenHelper`). The vector index starts as a brute-force cosine scan. | No dependencies; correct for small corpora. | RAG corpora exceed ~10k chunks or retrieval exceeds the latency budget in [requirements.md](requirements.md). |
| D7 | The Remote API backend speaks the **OpenAI-compatible Chat Completions** API with SSE streaming. The API key comes from an app callback per request and is never stored. | One adapter covers OpenAI, vLLM, Ollama, llama.cpp server, and most hosted providers. | A provider without an OpenAI-compatible endpoint is needed: add another wire format to `RemoteBackend`. |
| D8 | **ONNX Runtime is deferred.** On-device models run through llama.cpp (GGUF); larger models through the Remote API backend. | ONNX Runtime GenAI is not published for Android on Maven Central; plain `onnxruntime-android` would need our own tokenizer and KV-cache code per model family. | A required model ships only as ONNX, or ONNX Runtime GenAI is published for Android. |
| D6 | Tools are host-provided: the app registers Kotlin handlers with a JSON schema. No downloaded or interpreted code. | Answers the tool trust question with the smallest safe surface. | A real need for user-defined tools appears. |
| D9 | The public API is dumped to `<module>/api/*.api` and checked in CI by `gradle/api-dump.gradle`, which calls the binary-compatibility-validator **engine library** directly. | The validator plugin and the Kotlin plugin's own ABI validation register no tasks with AGP 9's built-in Kotlin, so there is no off-the-shelf check. The script still reads Kotlin visibility, so `internal` declarations stay out of the dump. | Replace the script with the Kotlin Gradle plugin's ABI validation once it supports Android library modules. |
| D10 | SDK modules publish to **GitHub Packages** (Maven) from a release workflow on a `v*` tag; the version is `VERSION_NAME` in `gradle.properties`. API documentation is generated with **Dokka**. | Needs no extra accounts or secrets (the workflow's `GITHUB_TOKEN` is enough) and matches where the code is hosted. | Add Maven Central when the project has a license and signing keys. |

## Decisions still open

1. **State ownership:** is conversation and memory state owned by the app or stored by the SDK? Default until decided: stored by the SDK in app-private storage, clearable by the app (T2AI-004, T2AI-025).
2. **Model catalog source:** is the catalog static in the SDK, supplied by the application, or fetched remotely? Default until decided: supplied by the application (T2AI-020).

## Diagram conventions

- Solid arrows are request/data flow; dashed arrows are control flow and signals.
- Every component runs on the device. Anything that leaves the device is labelled with its protocol.
- Use "backend" for inference integrations and "Model Manager" for metadata and lifecycle.
