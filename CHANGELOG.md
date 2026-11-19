# Changelog

All notable changes to the Tune2AI SDK. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [semantic versioning](https://semver.org/) (NFR-11): before 1.0, a minor version may break the public API, and the diff of `sdk/api/*.api` shows exactly what changed. To write release notes, copy [docs/release-notes-template.md](docs/release-notes-template.md).

The release workflow publishes the section whose heading matches the tag, so keep each version under its own `## [x.y.z]` heading.

## [Unreleased]

## [0.1.0] - 2026-11-20

First versioned release of the Android SDK.

### Capabilities

- **Chat**: one-shot and persisted-session chat with streaming, cancellation, a per-request system prompt, and typed errors (`ai.chat`).
- **Agents**: bounded model and tool loops over tools your app registers, with step and tool-call limits (`ai.agent`).
- **Workflows**: ordered model and tool steps with variables and branches, validated before running, checkpointed after every step, and resumable after process death (`ai.workflow`).
- **Models**: register, list, load, and unload models with compatibility and memory checks (`ai.models`).
- **Knowledge**: per-session memory, embeddings with a stable cosine reranker, and RAG with add, update, delete, and retrieve (`ai.memory`, `ai.embeddings`, `ai.rag`).
- **Backends**: llama.cpp for GGUF models (chat and embeddings, Android arm64-v8a and x86_64) and an OpenAI-compatible remote backend (chat).
- Docs: [getting started](docs/getting-started.md), one [guide per API](docs/api/README.md), and the [compatibility matrix](docs/compatibility.md). `./gradlew :dokkaGenerate` writes the API reference.

### Modules

| Artifact | Contents | Size |
|---|---|---|
| `com.tune2ai:tune2ai-sdk` | API, orchestration, services, storage. No `INTERNET` permission | 430 KB |
| `com.tune2ai:tune2ai-sdk-llama` | llama.cpp backend (llama.cpp `b12522`), arm64-v8a and x86_64 | 5.1 MB |
| `com.tune2ai:tune2ai-sdk-remote` | Remote backend. The only module that declares `INTERNET` | 29 KB |

### Backend compatibility

| Backend | Format | Tasks | Platforms |
|---|---|---|---|
| llama.cpp | GGUF | chat, embeddings | Android arm64-v8a, x86_64 (emulator) |
| Remote API | OpenAI Chat Completions (SSE) | chat | Android |
| ONNX Runtime | | | Deferred (decision D8) |
| Core ML | | | iOS, later |

Models checked by the project are listed in [docs/compatibility.md](docs/compatibility.md): SmolLM2-135M, Qwen2.5-0.5B-Instruct, and all-MiniLM-L6-v2. Gemma 3 1B is expected to work but has not been run.

### Measured non-functional requirements

| NFR | Target | Measured | How |
|---|---|---|---|
| NFR-1 Privacy | No network without a remote backend | `tune2ai-sdk` and `tune2ai-sdk-llama` AARs declare no permissions; only `tune2ai-sdk-remote` declares `INTERNET` | Inspected the published AAR manifests |
| NFR-4 SDK overhead | p95 at most 50 ms | p95 1.2 ms (p50 0.9 ms) | `RequestControllerTest.overheadStaysWithinBudget`: 300 requests, zero-latency fake backend, on a development PC (JVM) |
| NFR-5 First token | Delivered within 100 ms of the backend producing it | p95 0.2 ms (max 1.9 ms) | `RequestControllerTest.firstTokenIsDeliveredWithinBudget`: same setup |
| NFR-7 Terminal event and cancellation | Exactly one terminal event; backend work stops within 500 ms | Passed | `TerminalGuardTest` (JVM); `cancellationStopsWithin500ms` runs against real llama.cpp on the Android emulator |
| NFR-10 Size | `tune2ai-sdk` AAR at most 1 MB | 440,460 bytes | `./gradlew :sdk:checkAarSize`, also run in CI |

NFR-4 and NFR-5 measure the SDK's own overhead on a JVM with a backend that responds instantly; they were not measured on a reference phone. Model speed depends on the device and model: on the x86_64 emulator, Qwen2.5-0.5B generated roughly 6 to 10 tokens per second, which is not representative of a physical arm64 phone.

### Known limitations

- Inference runs on the CPU only (no GPU offload).
- Throughput and the NFR budgets have not been measured on a reference arm64 device.
- Agents need an instruction-following model: Qwen2.5-0.5B-Instruct or larger. SmolLM2-135M usually does not follow the tool-call format.
- Workflow definitions cannot loop (any cycle is rejected), and a changed definition `version` cannot resume older runs.
- RAG retrieval scans every stored chunk; it suits thousands of chunks, not hundreds of thousands. Changing the embedding model requires clearing and re-adding content.
- Memory search uses keyword overlap unless you supply an `Embedder`.
- Tune2AI Studio does not expose memory or RAG yet; its Memory and RAG switches are simulated.
- ONNX Runtime and iOS are not available.
- The repository does not declare a license yet; add one before distributing the artifacts outside GitHub Packages.
- Embedding calls cannot be interrupted mid-text (cancellation lands between texts).

### Migration

This is the first versioned release. If you built against pre-release Studio builds:

- `RequestOptions` gained `system` before `promptTemplate`. Use named arguments if you passed `promptTemplate` or `promptVariables` positionally.
- Engine and plumbing classes (`RequestController`, `Router`, `ModelManager`, `AgentEngine`, `WorkflowEngine`, `AndroidPlatform`, and similar) are now `internal`. Use `Tune2AI` and its `chat`, `agent`, `workflow`, `models`, `memory`, `embeddings`, and `rag` properties.
- Prompt templates, tools, and backends are registered on `Tune2AI.Builder` as before.
