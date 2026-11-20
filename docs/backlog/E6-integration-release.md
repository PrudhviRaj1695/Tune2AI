# E6 — Integration and release

Goal: Tune2AI Studio becomes the real Android sample, then docs and versioned packages ship.

### T2AI-050 — Turn Tune2AI Studio into the Android sample

- **Status:** Done
- **Milestone:** 3 · **Requirements:** FR-APP-1, FR-APP-2 · **Depends on:** T2AI-041

**Story:** As an Android developer, I want a sample app using the public SDK so that I see how to configure Tune2AI and complete a local chat request.

**Acceptance criteria**

- Studio initializes the SDK, selects a model, streams chat, cancels, and cleans up using only public APIs.
- The simulated reply in `app/src/main/assets/www/app.js` is removed; replies and the trace panel show real data (request id, route, first-token time, tokens/s).
- Loading, resource-limit, cancellation, and error states are shown.
- The WebView bridge exposes only the methods the UI needs and accepts calls only from the bundled page.

**Tasks**

- [x] T2AI-050.1 Create the SDK instance in `MainActivity` (llama.cpp backend, "helpful-assistant" v3 default prompt) and close it in `onDestroy`.
- [x] T2AI-050.2 `StudioBridge.java` added with `addJavascriptInterface`: `send(json)` (the page supplies its own request id), `cancel(id)`, `newSession()`, `listModels()`, `resources()`, `importModel()`. The WebView refuses to navigate away from the bundled page. Events are pushed to JS with `evaluateJavascript` on the main thread.
- [x] T2AI-050.3 Model import: pick a GGUF file with the system file picker (`ACTION_OPEN_DOCUMENT`) and copy it to app-private storage; non-GGUF files are rejected. `.gguf` files already in `files/models/` are registered at startup (lets adb seed models).
- [x] T2AI-050.4 `app.js`: replace `simulate(request, emit)` with a bridge-backed source that emits the same event shapes (`step`, `first-token`, `delta`, `completed`, plus `failed`). The UI already streams deltas, animates the route, and cancels from the send button. Keep `simulate()` only as the fallback when the bridge is absent (browser preview), and remove the "Simulated" badge when the bridge is present.
- [x] T2AI-050.5 Show resource-limit and error events as toasts plus a failed trace state.
- [x] T2AI-050.6 Manual test script in `app/README.md`. Verified on the emulator by driving the WebView over DevTools: chat, follow-up with history, cancel, trace.

### T2AI-054 — Configure a remote endpoint in Studio

- **Status:** Done
- **Milestone:** 4 · **Requirements:** FR-INF-5, NFR-2 · **Depends on:** T2AI-015, T2AI-050

**Story:** As a developer evaluating Tune2AI, I want to point Studio at a remote OpenAI-compatible endpoint so that I can compare remote and on-device runs on the same route.

**Acceptance criteria**

- Run setup → Remote shows fields for base URL, API key, and model name; Remote is enabled once they are filled.
- The API key is kept in memory only and never written to disk or logs.
- Remote runs show the same route, trace, and errors as on-device runs; an invalid key shows a clear failure.

**Tasks**

- [x] T2AI-054.1 Register `RemoteBackend` in `MainActivity` with an API-key supplier owned by `StudioBridge`; the base URL is a supplier too (`RemoteBackend({ url }, { key })`), so settings change without rebuilding the SDK.
- [x] T2AI-054.2 Bridge methods to set endpoint, key, and model; register the remote model descriptor.
- [x] T2AI-054.3 Run setup UI for the three fields; enable the Remote option.
- [x] T2AI-054.4 Manual test against a local OpenAI-compatible server on the development machine via `10.0.2.2` (debug builds allow cleartext to it only): wrong key shows "rejected the API key (HTTP 401)"; right key streams the reply with the remote route in the trace.

### T2AI-055 — Read-only insights from user-shared transaction messages

- **Status:** In progress
- **Milestone:** 9 · **Requirements:** FR-APP-3 · **Depends on:** T2AI-050

**Story:** As a Studio user, I want to share transaction messages or a statement CSV and ask questions about the summarized activity, without granting inbox access or allowing the agent to move money.

**Acceptance criteria**

- Studio accepts messages only when the user explicitly shares them and can import a local CSV statement.
- Raw message text is not retained; the finance tool exposes only aggregated totals to the local agent.
- The finance agent is read-only, routes through the on-device backend, and can be cleared by the user.
- No SMS, notification, or contacts permission is requested.

**Tasks**

- [x] T2AI-055.1 Add an Android `ACTION_SEND` text share target and process shared text on-device.
- [x] T2AI-055.2 Extract clear debit/credit amounts, aggregate by currency, and expose only the aggregate through `finance_summary`.
- [x] T2AI-055.3 Add the Finance Studio mode, CSV import, and a local-only finance agent.
- [x] T2AI-055.4 Clear aggregate data and cancel finance runs on explicit clear or SDK shutdown; request no inbox permissions.
- [x] T2AI-055.5 Document and verify the APK, emulator share/clear flow, and absence of SMS/contacts permissions (manual checks are in `app/README.md`).
- [ ] T2AI-055.6 Verify CSV import and a finance question route through llama.cpp on the emulator, including when Remote is selected.

### T2AI-051 — Provide an iOS sample

- **Status:** Todo
- **Milestone:** 8 · **Requirements:** FR-PLT-4 · **Depends on:** T2AI-003, T2AI-013

**Story:** As an iOS developer, I want a sample app using the public SDK so that I see how to configure Tune2AI and complete a local chat request.

**Acceptance criteria**

- Demonstrates initialization, model selection, streaming chat, cancellation, and cleanup with only public APIs.
- Shows loading, resource-limit, and error states.

**Tasks**

- [ ] T2AI-051.1 SwiftUI app under `ios/Tune2AIStudio` consuming the XCFramework.
- [ ] T2AI-051.2 Chat screen with streaming and cancel.
- [ ] T2AI-051.3 Manual test script in its README.

### T2AI-052 — Document SDK integration and supported models

- **Status:** Done
- **Milestone:** 9 · **Requirements:** FR-REL-1 · **Depends on:** milestones 1–7

**Story:** As an application developer, I want setup and compatibility docs so that I choose a supported configuration and troubleshoot integration.

**Acceptance criteria**

- Docs cover setup, lifecycle, each API, storage, resource behaviour, and typed errors, with runnable examples.
- Supported model formats and the backend compatibility matrix are listed.

**Tasks**

- [x] T2AI-052.1 `docs/getting-started.md` (Android).
- [x] T2AI-052.2 `docs/api/` one page per API with examples compiled in a docs test source set so they cannot rot. (Examples live in `sdk/src/docsTest`; `DocsExamplesTest` runs them and `DocsSnippetsTest` fails if a `kotlin` block in the docs differs from them. Blocks that need other modules are tagged `kotlin nocheck`. Memory, embeddings and RAG share one page, `knowledge.md`; events and errors have their own.)
- [x] T2AI-052.3 Backend and model compatibility matrix (`docs/compatibility.md`; lists tested models separately from models that should work).
- [x] T2AI-052.4 Generated API reference with Dokka (`./gradlew :dokkaGenerate` writes `build/dokka/html`; added to CI).

### T2AI-053 — Package and version SDK releases

- **Status:** Done
- **Milestone:** 9 · **Requirements:** FR-REL-1, NFR-10, NFR-11 · **Depends on:** T2AI-052

**Story:** As a release owner, I want versioned SDK packages so that applications adopt Tune2AI predictably.

**Acceptance criteria**

- `:sdk`, `:sdk-llama`, and `:sdk-remote` publish as versioned Maven artifacts. (`:sdk-onnx` does not exist: ONNX Runtime is deferred, decision D8.)
- Release notes list capabilities, the backend matrix, measured NFR values, known limitations, and migration steps.
- `:sdk` AAR size is checked against NFR-10 in CI.

**Tasks**

- [x] T2AI-053.1 `maven-publish` configuration with a single version property in `gradle.properties`. (`gradle/publish.gradle`; `VERSION_NAME` is the property and `VersionTest` fails if `SDK_VERSION` differs. Verified with a dry-run publish to `build/repo`; the GitHub Packages upload itself runs only from the release workflow.)
- [x] T2AI-053.2 Release workflow: tag → build → test → publish. (`.github/workflows/release.yml`, decision D10. It has not run yet: it starts on the first `v*` tag. The version check, the release-notes extraction, and every Gradle task it calls were run locally.)
- [x] T2AI-053.3 CI size check for the `:sdk` AAR. (`./gradlew :sdk:checkAarSize`, run in both workflows; 440,460 bytes against the 1,048,576 budget.)
- [x] T2AI-053.4 `CHANGELOG.md` and the release notes template. (`CHANGELOG.md` has the 0.1.0 notes with measured NFR values; added `NFR-5` measurement `firstTokenIsDeliveredWithinBudget`.)
- [x] T2AI-053.5 Public API surface review (mark internals `internal`) and an API dump checked in CI (moved from T2AI-040.2). (Engines, router, model manager, platform plumbing, index internals, and context sources are now `internal`; the `:sdk` dump went from 1,402 to 1,032 lines. The dump is produced by `gradle/api-dump.gradle`, decision D9, because the validator plugin does not support AGP 9. `:sdk-llama`'s device tests moved onto the public `Tune2AI` facade.)
