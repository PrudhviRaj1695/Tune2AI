# E1 — SDK contracts and native platform

Goal: the shared types every layer speaks, and the Android adapter that gives the runtime storage and resource signals.

### T2AI-001 — Define shared SDK request, response, and event types

- **Status:** Done
- **Milestone:** 1 · **Requirements:** FR-SDK-2, FR-SDK-3, NFR-7, NFR-12 · **Depends on:** T2AI-000

**Story:** As an application developer, I want consistent request and response types across the SDK APIs so that I can handle results, progress, and errors uniformly.

**Acceptance criteria**

- Chat, agent, workflow, and model operations share `RequestId`, options, result, and error types.
- `Tune2AIEvent` is a sealed type covering text delta, tool call, tool result, progress, completed, cancelled, and failed; every event carries the `RequestId`.
- `Tune2AIException` is a sealed hierarchy with stable categories (invalid request, no compatible route, model unavailable, resource limit, backend failure, tool failure, storage failure, timeout). Cancellation is not an error: it is the `Cancelled` terminal event.
- A stream emits exactly one terminal event and then completes; cancellation produces `cancelled`, not `failed`.

**Tasks**

- [x] T2AI-001.1 `api/Requests.kt` (`RequestId`, `RequestOptions`, `Usage`) (model preference, backend preference, deadline, max tokens, temperature).
- [x] T2AI-001.2 `api/Tune2AIEvent.kt` sealed interface with the event types above.
- [x] T2AI-001.3 `api/Tune2AIException.kt` sealed hierarchy with a `category`. (Changed: the `RequestId` travels on the `Failed` event, because backends throw errors without knowing the request.)
- [x] T2AI-001.4 `core/TerminalGuard.kt`: a `Flow` operator that enforces "exactly one terminal event" and maps `CancellationException` to `cancelled`.
- [x] T2AI-001.5 Tests: terminal guard (normal, error, cancel, double-terminal); exception categories are exhaustive in a `when`.
- [x] T2AI-001.6 Document the event contract and versioning rule (NFR-11) in KDoc on `Tune2AIEvent`.

### T2AI-002 — Create the Android native adapter

- **Status:** Done
- **Milestone:** 1 · **Requirements:** FR-PLT-1 · **Depends on:** T2AI-001

**Story:** As an Android application developer, I want the SDK to access Android capabilities through one adapter so that native functionality is isolated from shared runtime code.

**Acceptance criteria**

- Shared packages depend on a `Platform` interface; `platform/android/AndroidPlatform` is the only implementation and the only place `android.*` is imported outside backends.
- The adapter is created from an application `Context` and holds no `Activity` references.
- Native and platform failures map to `Tune2AIException` categories.

**Tasks**

- [x] T2AI-002.1 `core/Platform.kt` interface: `storage`, `resources`, `filesDir`, `ioDispatcher`; `AutoCloseable` so listeners and the database are released.
- [x] T2AI-002.2 `platform/android/AndroidPlatform.kt` implementing it from `Context.applicationContext`.
- [x] T2AI-002.3 `platform/FakePlatform.kt` in test sources for JVM tests.
- [x] T2AI-002.4 `platform/android/NativeErrors.kt`: map JNI/IO exceptions to SDK error categories.
- [x] T2AI-002.5 Tests: instrumented test creates `AndroidPlatform` and round-trips one storage record.

### T2AI-003 — Create the iOS native adapter

- **Status:** Todo
- **Milestone:** 8 · **Requirements:** FR-PLT-4 · **Depends on:** T2AI-002, Kotlin Multiplatform conversion (D1)

**Story:** As an iOS application developer, I want the SDK to access iOS capabilities through a Swift adapter so that native functionality is isolated from shared runtime code.

**Acceptance criteria**

- `Platform` has an iOS implementation providing storage and resource signals.
- Swift failures map to the same SDK error categories as Android.
- Initialization and release follow iOS app lifecycle expectations.

**Tasks**

- [ ] T2AI-003.1 Convert `:sdk` to Kotlin Multiplatform: move `api`, `core`, `services`, `inference` to `commonMain`; keep Android code in `androidMain`.
- [ ] T2AI-003.2 Add `iosArm64` and `iosSimulatorArm64` targets and an XCFramework build.
- [ ] T2AI-003.3 `iosMain` `IosPlatform` with storage (SQLite) and resource signals (`ProcessInfo` thermal state, memory warnings).
- [ ] T2AI-003.4 Tests: common tests run on JVM and iOS simulator.

### T2AI-004 — Provide the local storage interface

- **Status:** Done
- **Milestone:** 1 · **Requirements:** FR-PLT-2, NFR-3 · **Depends on:** T2AI-002

**Story:** As an application developer, I want a consistent local storage interface so that sessions, model metadata, and AI service state persist across app restarts.

**Acceptance criteria**

- Namespaced records support get, put, delete, list by namespace, and clear namespace.
- Data is stored in app-private storage only.
- Write failures surface as typed errors; no silent data loss.
- Retention and ownership are documented: the SDK stores, the app can clear any namespace.

**Tasks**

- [x] T2AI-004.1 `core/Storage.kt` interface (suspend functions, `ByteArray` or `String` values keyed by namespace + key).
- [x] T2AI-004.2 `platform/android/SqliteStorage.kt` using `SQLiteOpenHelper` (D5), one table `(namespace, key, value, updated_at)`, primary key `(namespace, key)`.
- [x] T2AI-004.3 `InMemoryStorage` in `core` (changed: in main sources, not test, so apps can choose storage that persists nothing). Contract tests in `src/sharedTest` run against it on the JVM and against SQLite on devices.
- [x] T2AI-004.4 Tests: CRUD, namespace isolation, clear namespace, behaviour after reopen (instrumented).
- [x] T2AI-004.5 KDoc: ownership, retention, and that encryption at rest relies on Android file-based encryption.

### T2AI-005 — Monitor device resources

- **Status:** Done
- **Milestone:** 1 · **Requirements:** FR-PLT-3, NFR-8 · **Depends on:** T2AI-002

**Story:** As an application developer, I want the SDK to observe device resource pressure so that inference work responds safely on constrained devices.

**Acceptance criteria**

- Signals include available memory, low-memory state, battery level/charging, and thermal status (API 29+; reported as unknown below).
- Signals are exposed as a `StateFlow` consumed by the Model Manager (T2AI-020).
- The runtime can reject, defer, or stop work according to configured limits, and the app receives a typed status or error when that happens.

**Tasks**

- [x] T2AI-005.1 `ResourceMonitor` with `StateFlow<ResourceSnapshot>` in `core/Platform.kt`; `ResourceSnapshot`, `ThermalState`, and `ResourceLimits` in `api/Resources.kt` (public configuration).
- [x] T2AI-005.2 `platform/android/AndroidResourceMonitor.kt` using `ActivityManager.MemoryInfo`, `ComponentCallbacks2.onTrimMemory`, `BatteryManager`, `PowerManager.addThermalStatusListener`.
- [x] T2AI-005.3 `api/ResourceLimits.kt` in configuration: minimum free memory, stop on thermal ≥ severe, optional battery floor.
- [x] T2AI-005.4 Tests: fake monitor drives limit decisions (JVM); instrumented smoke test reads a real snapshot.
