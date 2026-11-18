# E2 — Inference runtime

Goal: one backend-neutral interface with llama.cpp first, then ONNX Runtime, the optional Remote API backend, and (on iOS) Core ML.

### T2AI-010 — Define the inference abstraction

- **Status:** Done
- **Milestone:** 2 · **Requirements:** FR-INF-1 · **Depends on:** T2AI-001

**Story:** As a runtime maintainer, I want a backend-neutral inference interface so that backends can be added without changing SDK APIs or orchestration logic.

**Acceptance criteria**

- `InferenceBackend` exposes `capabilities`, `load`, `unload`, and `generate(): Flow<Token>`; cancelling the collector cancels generation.
- Capabilities report model formats, modalities, context length, tool-call support, and memory needs.
- Backend errors map to stable runtime error categories.
- A fake backend exists for JVM tests of everything above the runtime.

**Tasks**

- [x] T2AI-010.1 `inference/InferenceBackend.kt` (`InferenceBackend`, `BackendCapabilities`, `LoadedModel`, `GenerateRequest` with chat messages and sampling params; `generate` streams `Flow<String>`). `ModelDescriptor`/`ChatMessage` live in `api/Models.kt`. Stop sequences dropped: backends stop at the model's end-of-generation token.
- [x] T2AI-010.2 `FakeBackend` in `sdk/src/test`: scripted tokens, configurable delay and failure.
- [x] T2AI-010.3 Contract test suite (`InferenceBackendContract` in `sdk/src/sharedTest`) that any backend can run: load/unload, stream, cancel within 500 ms (NFR-7), error mapping.
- [x] T2AI-010.4 Run the contract suite against `FakeBackend`.

### T2AI-011 — Integrate the llama.cpp backend

- **Status:** Done
- **Milestone:** 2 · **Requirements:** FR-INF-2, NFR-9 · **Depends on:** T2AI-010

**Story:** As an application developer, I want to run a GGUF model through llama.cpp so that Tune2AI provides local inference on Android.

**Acceptance criteria**

- `:sdk-llama` implements `InferenceBackend` and passes the contract suite on an arm64-v8a device or emulator.
- Load, streaming generation, cancellation, and unload work through the shared interface.
- Invalid files, unsupported architectures, and out-of-memory on load return actionable typed errors.

**Tasks**

- [x] T2AI-011.1 Create `:sdk-llama` (Android library, NDK, CMake). Add llama.cpp as a shallow git submodule pinned to release tag `b12522` under `sdk-llama/src/main/cpp/llama.cpp`.
- [x] T2AI-011.2 `CMakeLists.txt` building llama.cpp + a thin JNI bridge for arm64-v8a and x86_64.
- [x] T2AI-011.3 JNI bridge (`llama_jni.cpp`): load model, create context, chat template, tokenize, chunked prompt + decode loop on the new `llama_batch_ext`/`llama_process` API (`llama_decode` is deprecated at this tag), cancel flag checked per token and via llama.cpp's abort callback, UTF-8-safe streaming, free.
- [x] T2AI-011.4 `LlamaBackend.kt`: wraps the bridge on a small dedicated dispatcher (`Dispatchers.IO.limitedParallelism(2)`), one generation per model at a time; emits text as a `Flow`.
- [x] T2AI-011.5 Pre-load check: `LlamaBackend.estimateMemoryBytes` (file size + overhead, `ponytail:` note) feeds `ModelDescriptor.memoryBytes`; the Model Manager refuses loads that break `ResourceLimits` (NFR-8). The backend also rejects non-GGUF files before calling native code.
- [x] T2AI-011.6 Tests: contract suite as an instrumented test with SmolLM2-135M-Instruct Q8_0 pushed via `adb` (documented in `TestModel.kt` and AGENTS.md), plus `FirstTokensTest` for the full route. Emulator result: "The capital of France is Paris.", ~47 tok/s.

### T2AI-012 — Integrate the ONNX Runtime backend

- **Status:** Todo
- **Milestone:** 4 · **Requirements:** FR-INF-3 · **Depends on:** T2AI-010

**Story:** As an application developer, I want to run supported ONNX models through ONNX Runtime so that I can use this backend through the same SDK contract.

**Acceptance criteria**

- `:sdk-onnx` implements `InferenceBackend`, reports its capabilities, and passes the contract suite.
- Unsupported graphs, operators, or configurations fail with actionable errors.

**Tasks**

- [ ] T2AI-012.1 Create `:sdk-onnx` with the `onnxruntime-android` (or `onnxruntime-genai`) dependency; record the choice in the module README.
- [ ] T2AI-012.2 `OnnxBackend.kt`: load session, generation loop with cancellation, unload.
- [ ] T2AI-012.3 Map ORT exceptions to SDK error categories.
- [ ] T2AI-012.4 Tests: contract suite with a small ONNX model (instrumented).

### T2AI-013 — Integrate the Core ML backend

- **Status:** Todo
- **Milestone:** 8 · **Requirements:** FR-INF-6 · **Depends on:** T2AI-003

**Story:** As an iOS application developer, I want supported models to use Core ML so that inference uses the native iOS runtime.

**Acceptance criteria**

- Implements `InferenceBackend` through the iOS adapter and passes the contract suite on iOS.
- Unsupported model features and platform requirements are reported before execution where possible.

**Tasks**

- [ ] T2AI-013.1 Swift Core ML wrapper exposed to Kotlin via cinterop or a Swift-implemented interface.
- [ ] T2AI-013.2 `CoreMlBackend` in `iosMain`.
- [ ] T2AI-013.3 Tests: contract suite on the iOS simulator/device.

### T2AI-014 — Register and describe backends

- **Status:** Todo
- **Milestone:** 4 · **Requirements:** FR-INF-4 · **Depends on:** T2AI-010

**Story:** As a runtime maintainer, I want backends to register through a documented extension point so that new runtimes do not require public API changes.

**Acceptance criteria**

- Apps register backends at configuration time (`Tune2AI.Builder.addBackend(...)`); registration validates the backend's capabilities.
- The runtime lists available backends and explains why a requested backend is unavailable.
- A backend integration guide covers lifecycle, errors, streaming, cancellation, and resource reporting.

**Tasks**

- [ ] T2AI-014.1 `inference/BackendRegistry.kt` with registration validation and an `unavailableReason` per backend id.
- [ ] T2AI-014.2 Wire the registry into configuration (T2AI-040).
- [ ] T2AI-014.3 `docs/backend-guide.md`: how to implement and test a backend with the contract suite.
- [ ] T2AI-014.4 Tests: duplicate id rejected, invalid capabilities rejected, unavailable reason reported.

### T2AI-015 — Integrate the optional Remote API backend

- **Status:** Todo
- **Milestone:** 4 · **Requirements:** FR-INF-5, NFR-1, NFR-2 · **Depends on:** T2AI-010, T2AI-014

**Story:** As an application developer, I want to route requests to a remote model API through the same inference abstraction so that remote and local execution share routing, context, tools, and events.

**Acceptance criteria**

- `:sdk-remote` implements `InferenceBackend` and passes the contract suite against a local mock server.
- Requests use HTTPS; output streams over SSE into the shared event contract; cancellation closes the connection.
- Credentials are supplied by the app through a provider callback and never persisted or logged.
- Network, authentication, and quota failures map to stable error categories.
- Only `:sdk-remote` declares `INTERNET`; `:sdk` alone makes no network calls (NFR-1).

**Tasks**

- [ ] T2AI-015.1 Resolve open decision 3 in [architecture.md](../architecture.md): pick the first provider API format.
- [ ] T2AI-015.2 Create `:sdk-remote` with `INTERNET` in its manifest.
- [ ] T2AI-015.3 `SseReader.kt` over `HttpURLConnection` (D4).
- [ ] T2AI-015.4 `RemoteBackend.kt`: request mapping, streaming, cancellation, HTTP status → error category.
- [ ] T2AI-015.5 Tests: `SseReader` unit tests (split frames, comments, multi-line data); contract suite against a JVM mock HTTP server; assert credentials never appear in logged messages.
