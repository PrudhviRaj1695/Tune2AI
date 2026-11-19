# E3 — AI services

Goal: the Model Manager that owns model state, and the Context Manager with the prompt, memory, and retrieval services it draws on.

### T2AI-020 — Manage model metadata and lifecycle

- **Status:** Done
- **Milestone:** 2 · **Requirements:** FR-SVC-1, FR-PLT-3, NFR-8 · **Depends on:** T2AI-005, T2AI-010

**Story:** As an application developer, I want a Model Manager to register, inspect, load, unload, and select models so that model use is consistent across backends.

**Acceptance criteria**

- Model descriptors (supplied by the app — open decision 2 default) include id, version, format, file location, capabilities, compatible backends, and memory requirement.
- The Model Manager exposes availability and load state as a `StateFlow` and is the only component that calls `load`/`unload` on a backend.
- Availability accounts for resource signals from T2AI-005; a model that does not fit is unavailable with a reason.
- A model cannot be selected when its backend or required capabilities are unavailable.

**Tasks**

- [x] T2AI-020.1 `api/Models.kt`: `ModelDescriptor`, `ModelState` (`Unloaded`, `Loading`, `Loaded`, `Failed(reason)`, `Unavailable(reason)`).
- [x] T2AI-020.2 `services/model/ModelManager.kt`: registry, `candidates(task)`, `acquire(model, backend)` returning a `Lease`, `unload(id)`, `trim()`; load serialized per model with a `Mutex`.
- [x] T2AI-020.3 Subscribe to `ResourceMonitor`; unload idle models on low-memory (`onTrimMemory`) and mark oversized models unavailable.
- [x] T2AI-020.4 Persist descriptors through `Storage` (namespace `models`).
- [x] T2AI-020.5 Tests (JVM, `FakeBackend` + fake monitor): concurrent `ensureLoaded` loads once; low memory unloads; incompatible model never returned as a candidate.

### T2AI-021 — Manage prompt templates

- **Status:** Done
- **Milestone:** 3 · **Requirements:** FR-SVC-2 · **Depends on:** T2AI-001

**Story:** As an application developer, I want reusable prompt templates so that requests apply consistent instructions and formatting.

**Acceptance criteria**

- Templates have stable ids, versions, and declared variables.
- Missing or undeclared variables fail before model invocation with `invalid request`.
- The template id and version used are included in result metadata.

**Tasks**

- [x] T2AI-021.1 `services/prompt/PromptTemplate.kt` with `{{variable}}` substitution (no template engine dependency).
- [x] T2AI-021.2 `services/prompt/PromptManager.kt`: register, get by id + version, render.
- [x] T2AI-021.3 Tests: render, missing variable, undeclared variable, escaping of literal braces; plus a device test, because Android's ICU regex engine rejected a pattern the JVM accepted (it crashed Studio on launch).

### T2AI-022 — Assemble request context

- **Status:** Done
- **Milestone:** 3 · **Requirements:** FR-SVC-3 · **Depends on:** T2AI-020, T2AI-021

**Story:** As an application developer, I want a Context Manager to combine input, templates, conversation, memory, and retrieved content so that the model receives bounded, relevant context.

**Acceptance criteria**

- Sources and their order are configurable: system template → memory → retrieved chunks → conversation history → user input.
- The assembled context never exceeds the selected model's context length minus the reserved output tokens.
- The result reports included source references and what was omitted due to limits.
- The Context Manager is the only path from memory and RAG to a model request.

**Tasks**

- [x] T2AI-022.1 `services/context/ContextSource.kt` interface; memory and RAG plug in later (T2AI-024, T2AI-025).
- [x] T2AI-022.2 `services/context/TokenCounter.kt`: chars/4 estimate with a `ponytail:` note (`estimateTokens` in `ContextManager.kt`); switch to the backend tokenizer if trimming misjudges.
- [x] T2AI-022.3 `services/context/ContextManager.kt`: assemble, trim oldest conversation turns first, report `included`/`omitted` sources (sent as `Progress.sources`).
- [x] T2AI-022.4 Tests: ordering, trimming at the limit, omitted-content report, empty sources.

### T2AI-023 — Provide embedding and reranking services

- **Status:** Todo
- **Milestone:** 7 · **Requirements:** FR-SVC-4 · **Depends on:** T2AI-010, T2AI-020

**Story:** As an application developer, I want text embedding and optional reranking so that stored content can be searched for relevant context.

**Acceptance criteria**

- Embedding requests report model id, dimensions, and input limits.
- Reranking takes candidates and returns a stable ranked result.
- Dimension mismatches or unavailable services return typed errors.

**Tasks**

- [ ] T2AI-023.1 Add `embed` to the inference capabilities (optional operation) and implement it in `:sdk-llama`.
- [ ] T2AI-023.2 `services/embedding/EmbeddingService.kt` and `Reranker.kt` (interface; default implementation = cosine re-score).
- [ ] T2AI-023.3 Tests: dimension mismatch error, stable ordering for equal scores.

### T2AI-024 — Implement the RAG engine

- **Status:** Todo
- **Milestone:** 7 · **Requirements:** FR-SVC-5 · **Depends on:** T2AI-004, T2AI-022, T2AI-023

**Story:** As an application developer, I want to add, update, delete, and retrieve content so that model requests can use application knowledge.

**Acceptance criteria**

- Content is chunked and indexed with the embedding service.
- Retrieval returns ranked chunks with source ids and metadata.
- Updated or deleted content never appears in later retrieval.
- The Context Manager includes retrieved chunks as a context source.

**Tasks**

- [ ] T2AI-024.1 `services/rag/Chunker.kt` (fixed token windows with overlap).
- [ ] T2AI-024.2 `services/rag/VectorIndex.kt` brute-force cosine over vectors in `Storage` (D5), with a `ponytail:` note on the corpus-size ceiling.
- [ ] T2AI-024.3 `services/rag/RagEngine.kt`: add/update (replace all chunks of a source)/delete/retrieve.
- [ ] T2AI-024.4 `RagContextSource` registered with the Context Manager.
- [ ] T2AI-024.5 Tests: update replaces, delete removes, top-k ordering.

### T2AI-025 — Implement the memory engine

- **Status:** Done
- **Milestone:** 7 · **Requirements:** FR-SVC-6 · **Depends on:** T2AI-004, T2AI-022

**Story:** As an application developer, I want memory operations to persist and retrieve selected information so that relevant state carries across requests.

**Acceptance criteria**

- Explicit write, search, update, delete, and clear operations.
- Memory is scoped per application session and isolated between sessions.
- The app can disable, inspect, and clear memory.
- Memory reaches requests only through the Context Manager.

**Tasks**

- [x] T2AI-025.1 `services/memory/MemoryEngine.kt` on `Storage` (namespace per session).
- [x] T2AI-025.2 Search by embedding when available, else keyword match.
- [x] T2AI-025.3 `MemoryContextSource` registered with the Context Manager; respects the disabled flag.
- [x] T2AI-025.4 Tests: session isolation, clear, disabled memory contributes nothing.
