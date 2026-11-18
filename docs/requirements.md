# Tune2AI requirements

Architecture: [architecture.md](architecture.md). Backlog (epics, stories, tasks): [backlog/](backlog/README.md).

## 1. Product goal

Tune2AI is a mobile SDK that lets an application run AI features — chat, tool-using agents, and multi-step workflows — on the device, with an optional remote model backend, through one typed API. Tune2AI Studio (`:app`) is the Android sample and developer console built on the public SDK.

## 2. Users

| User | Needs |
|---|---|
| **App developer** (primary) | Add chat, agents, and workflows with one dependency; choose models; get streaming results, cancellation, and typed errors. |
| **SDK maintainer** | Add backends and services without changing public APIs. |
| **End user** of an app built on Tune2AI | Fast responses, private data stays on the device unless the app chooses a remote backend, app stays responsive under low memory or battery. |

## 3. Scope

**In scope (v1):** Android SDK, llama.cpp and ONNX Runtime backends, optional Remote API backend, chat/agent/workflow/model APIs, context, prompts, memory, RAG, Tune2AI Studio sample.

**Later (v1.x):** iOS SDK (Swift adapter, Core ML backend, iOS sample) — milestone 8.

**Out of scope:** a hosted Tune2AI cloud service, model training or fine-tuning, model distribution/CDN, user-defined (downloaded or interpreted) tools.

## 4. Functional requirements

IDs are stable; stories reference them. "Must" items block v1; "Should" items may slip to v1.x.

### 4.1 Developer SDK

| ID | Requirement | Priority | Stories |
|---|---|---|---|
| FR-SDK-1 | One entry point (`Tune2AI`) configures storage, models, backends, and tools, and exposes Chat, Agent, Workflow, and Model APIs. | Must | T2AI-040 |
| FR-SDK-2 | All operations share request/response types, request IDs, and a typed error hierarchy. | Must | T2AI-001 |
| FR-SDK-3 | Generation streams events (text delta, tool call, tool result, progress, completed, failed) and can be cancelled at any point. | Must | T2AI-001, T2AI-032 |
| FR-SDK-4 | Chat supports one-shot and session-based conversations. | Must | T2AI-041 |
| FR-SDK-5 | Agent API runs bounded tool-using tasks and distinguishes completed, cancelled, limit reached, and failed. | Must | T2AI-042 |
| FR-SDK-6 | Workflow API defines, validates, starts, inspects, cancels, and resumes workflows. | Must | T2AI-043 |
| FR-SDK-7 | Model API lists models with compatibility, and loads, unloads, and reports model status. | Must | T2AI-044 |

### 4.2 Orchestration

| ID | Requirement | Priority | Stories |
|---|---|---|---|
| FR-ORC-1 | Every Chat, Agent, and Workflow request passes through one Request Controller and ends in exactly one terminal state. | Must | T2AI-030 |
| FR-ORC-2 | The router selects a compatible model and backend using the Model Manager; an explicit preference is honoured or rejected with a typed error naming the unmet capability. | Must | T2AI-031 |
| FR-ORC-3 | Tools are registered by the host app with a JSON schema; inputs are validated before invocation; invocation has a timeout. | Must | T2AI-033 |
| FR-ORC-4 | Agents run model/tool iterations bounded by step count and deadline. | Must | T2AI-034 |
| FR-ORC-5 | Workflows run ordered model and tool steps with transitions; invalid definitions fail before execution. | Must | T2AI-035 |
| FR-ORC-6 | Workflow runs can be checkpointed and resumed after process death without repeating completed steps. | Should | T2AI-036 |

### 4.3 AI services

| ID | Requirement | Priority | Stories |
|---|---|---|---|
| FR-SVC-1 | Model Manager holds the catalog, compatibility, availability, and load state, and is the only caller of load/unload. | Must | T2AI-020 |
| FR-SVC-2 | Prompt templates are versioned, declare variables, and fail before inference when variables are missing. | Must | T2AI-021 |
| FR-SVC-3 | Context Manager assembles prompt, conversation, memory, and retrieved content within the model's context limit, and reports what was included and omitted. | Must | T2AI-022 |
| FR-SVC-4 | Embedding and optional reranking services report model identity and dimensions. | Should | T2AI-023 |
| FR-SVC-5 | RAG supports add, update, delete, and retrieve; deleted content never appears in later retrieval. | Should | T2AI-024 |
| FR-SVC-6 | Memory supports write, search, update, delete, clear, and disable, scoped per application session. | Should | T2AI-025 |

### 4.4 Inference runtime

| ID | Requirement | Priority | Stories |
|---|---|---|---|
| FR-INF-1 | One backend-neutral inference interface: capabilities, load, unload, generate (streaming), cancel. | Must | T2AI-010 |
| FR-INF-2 | llama.cpp backend runs GGUF models on Android arm64-v8a. | Must | T2AI-011 |
| FR-INF-3 | ONNX Runtime backend runs supported ONNX generative models. | Should | T2AI-012 |
| FR-INF-4 | Backends register through a documented extension point; unavailable backends explain why. | Must | T2AI-014 |
| FR-INF-5 | Optional Remote API backend streams over HTTPS/SSE using credentials supplied by the app. | Should | T2AI-015 |
| FR-INF-6 | Core ML backend on iOS. | Later | T2AI-013 |

### 4.5 Native platform

| ID | Requirement | Priority | Stories |
|---|---|---|---|
| FR-PLT-1 | Android adapter isolates all `android.*` usage and maps native failures to SDK errors. | Must | T2AI-002 |
| FR-PLT-2 | Namespaced local storage (read, write, update, delete, clear namespace) in app-private storage. | Must | T2AI-004 |
| FR-PLT-3 | Device resource signals (available memory, low-memory, battery, thermal) reach the Model Manager. | Must | T2AI-005 |
| FR-PLT-4 | iOS adapter. | Later | T2AI-003 |

### 4.6 Tune2AI Studio and release

| ID | Requirement | Priority | Stories |
|---|---|---|---|
| FR-APP-1 | Studio uses only public SDK APIs and replaces its simulated replies with real streamed output. | Must | T2AI-050 |
| FR-APP-2 | Studio shows loading, resource-limit, cancellation, and error states. | Must | T2AI-050 |
| FR-REL-1 | Versioned Android packages with a backend compatibility matrix, docs, and release notes. | Must | T2AI-052, T2AI-053 |

## 5. Non-functional requirements

Numeric targets are initial budgets. Measure them in milestone 2 on the reference device and adjust this table, not the code.

| ID | Category | Requirement |
|---|---|---|
| NFR-1 | Privacy | With no Remote API backend configured, the SDK makes no network calls. `:sdk` declares no `INTERNET` permission; only `:sdk-remote` does. |
| NFR-2 | Security | The SDK never persists credentials. Tool handlers run only when registered by the host app and allowed for the request. Tool arguments and results are excluded from logs by default. |
| NFR-3 | Security | Stored data lives in app-private storage; WebView in Studio cannot read the file system. |
| NFR-4 | Performance | SDK overhead (controller + routing + context assembly, excluding inference) ≤ 50 ms p95 per request. |
| NFR-5 | Performance | First streamed token is delivered to the caller within 100 ms of the backend producing it. |
| NFR-6 | Responsiveness | No SDK call blocks the main thread; all I/O and inference run on background dispatchers. |
| NFR-7 | Reliability | Every request ends in exactly one terminal event (completed, cancelled, or failed). Cancellation stops backend work within 500 ms. |
| NFR-8 | Resource safety | Model load is refused with a typed error when declared memory needs exceed available memory; load never crashes the process with OOM by design. |
| NFR-9 | Compatibility | Android API 26+ (minSdk 26), arm64-v8a required for native backends; x86_64 supported for emulator testing. |
| NFR-10 | Size | `:sdk` AAR ≤ 1 MB. Native backends are separate modules so apps only ship what they use. |
| NFR-11 | API stability | Public API is versioned (semver). Breaking changes require a major version and a migration note. |
| NFR-12 | Observability | Each request carries a request ID through every event and error. |
| NFR-13 | Testability | Shared runtime packages are testable on the JVM with a fake backend; no device needed. |

## 6. Constraints and assumptions

- Kotlin SDK, Android library modules, coroutines `Flow` for streaming — see decisions D1–D6 in [architecture.md](architecture.md).
- Models are supplied by the application (file path or URI); the SDK does not download models.
- Build: AGP 9.2, Gradle 9.4 wrapper, compileSdk/targetSdk 36, JDK from Android Studio.

## 7. Acceptance for v1

v1 is done when milestones 1–7 in [backlog/README.md](backlog/README.md) are complete, every "Must" requirement above has a story in `Done`, and NFR-1, 4, 5, 7, and 10 have been measured and recorded in the release notes.
