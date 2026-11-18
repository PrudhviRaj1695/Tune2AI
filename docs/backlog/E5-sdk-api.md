# E5 — Developer SDK APIs

Goal: the public surface app developers use. Everything here is a thin layer over E4; no logic lives only in the API classes.

### T2AI-040 — Provide the unified AI API

- **Status:** Done
- **Milestone:** 3 · **Requirements:** FR-SDK-1 · **Depends on:** T2AI-002, T2AI-020, T2AI-030

**Story:** As an application developer, I want one SDK entry point so that initialization and shared configuration are consistent.

**Acceptance criteria**

- `Tune2AI.Builder(context)` configures backends, models, tools, resource limits, and storage; `build()` returns a `Tune2AI` exposing `chat`, `agent`, `workflow`, and `models`.
- `close()` unloads models and releases native resources.
- Dependency failures at build time throw a typed error naming the missing piece.

**Tasks**

- [x] T2AI-040.1 `Tune2AI.kt` (package `com.tune2ai.sdk`, because the Builder takes an Android `Context`) and `Tune2AI.Builder` wiring platform, storage, registry, Model Manager, controller.
- [x] T2AI-040.2 Moved to T2AI-053: public API surface review and API dump belong with the first versioned release.
- [x] T2AI-040.3 Tests: build with fakes; build without any backend fails clearly; `close()` unloads.

### T2AI-041 — Publish the Chat API

- **Status:** Done
- **Milestone:** 3 · **Requirements:** FR-SDK-3, FR-SDK-4 · **Depends on:** T2AI-032, T2AI-040

**Story:** As an application developer, I want a Chat API for one-shot and session conversations so that I build chat without calling internals.

**Acceptance criteria**

- `chat.send(message, options): Flow<Tune2AIEvent>` and `chat.session(id)` with history persisted via storage.
- Model selection and context options are exposed; errors are typed.

**Tasks**

- [x] T2AI-041.1 `api/ChatApi.kt`: `ChatApi` and `ChatSession` (history in namespace `chat/<sessionId>`; only completed exchanges are stored).
- [x] T2AI-041.2 Java-friendly callback adapter: `stream(message, options, EventListener)` returning `Cancellable`; always delivers exactly one terminal event, including `Cancelled`.
- [x] T2AI-041.3 Tests: one-shot, session history across instances, cancellation.

### T2AI-042 — Publish the Agent API

- **Status:** Todo
- **Milestone:** 5 · **Requirements:** FR-SDK-5 · **Depends on:** T2AI-034, T2AI-040

**Story:** As an application developer, I want an Agent API for bounded tool-using tasks so that I request multi-step execution through the SDK.

**Acceptance criteria**

- Accepts task input, allowed tools, and limits; streams progress and tool activity; can be cancelled.
- The result distinguishes completed, cancelled, limit reached, and failed.

**Tasks**

- [ ] T2AI-042.1 `api/AgentApi.kt` over `AgentEngine`.
- [ ] T2AI-042.2 Tests: each terminal outcome reachable through the public API.

### T2AI-043 — Publish the Workflow API

- **Status:** Todo
- **Milestone:** 6 · **Requirements:** FR-SDK-6 · **Depends on:** T2AI-035, T2AI-036, T2AI-040

**Story:** As an application developer, I want a Workflow API to define, start, inspect, cancel, and resume workflows.

**Acceptance criteria**

- Definitions are validated before a run starts.
- Run state and step results are inspectable; resume capability is reported.

**Tasks**

- [ ] T2AI-043.1 `api/WorkflowApi.kt`: `validate`, `start`, `status`, `cancel`, `resume`, `list`, `delete`.
- [ ] T2AI-043.2 Tests: full lifecycle through the public API.

### T2AI-044 — Publish the Model API

- **Status:** Todo
- **Milestone:** 4 · **Requirements:** FR-SDK-7 · **Depends on:** T2AI-020, T2AI-040

**Progress:** a minimal `ModelsApi` (register, list, states, remove) shipped with T2AI-050 for Studio's model import.

**Story:** As an application developer, I want a Model API to discover and manage models so that I prepare models before sending requests.

**Acceptance criteria**

- Lists models with backend and capability compatibility and current state.
- Load, unload, and status; lifecycle and resource errors use the shared error contract.

**Tasks**

- [ ] T2AI-044.1 `api/ModelApi.kt`: `register`, `list`, `state(id): StateFlow`, `load`, `unload`.
- [ ] T2AI-044.2 Tests: state transitions observed through the public API; load refused under resource limit.
