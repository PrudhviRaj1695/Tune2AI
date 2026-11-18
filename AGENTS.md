# AGENTS.md — Tune2AI

Context for coding agents and new contributors. Read this first, then the story you are working on.

## What this project is

Tune2AI is an on-device AI SDK for mobile apps (chat, tool-using agents, workflows) with an optional remote model backend. Tune2AI Studio (`:app`) is the Android sample and developer console.

**Current state:** `:app` is a Java WebView prototype with simulated replies. The SDK modules do not exist yet; they start with story T2AI-000.

## Where things are

| Path | What |
|---|---|
| [docs/requirements.md](docs/requirements.md) | Functional (`FR-…`) and non-functional (`NFR-…`) requirements |
| [docs/architecture.md](docs/architecture.md) | Revised architecture, diagram, technical decisions D1–D6, open decisions |
| [docs/backlog/](docs/backlog/README.md) | Epics, stories, tasks, milestones. **The source of truth for what to build next.** |
| `app/` | Tune2AI Studio. Native shell: `MainActivity.java`. UI: `app/src/main/assets/www/` (the only copy of the web UI) |
| `scripts/architecture_diagram.py` | Regenerates `docs/architecture.svg` |
| `sdk/`, `sdk-llama/`, `sdk-onnx/`, `sdk-remote/` | Planned modules (decision D3) |

## Build and test

```sh
# Windows (Git Bash): use Android Studio's JDK
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

./gradlew :app:assembleDebug            # Studio APK -> app/build/outputs/apk/debug/
./gradlew test                          # JVM unit tests (once :sdk exists)
./gradlew connectedAndroidTest          # instrumented tests, needs a device/emulator
```

Web UI preview without Android: `python -m http.server -d app/src/main/assets/www`.

Known environment issue: the repo lives in OneDrive. If Gradle fails with `Cannot snapshot …: not a regular file`, the file is an online-only placeholder. Open it once (or mark the folder "Always keep on this device") and rebuild.

## Architecture rules

Follow [docs/architecture.md](docs/architecture.md). The rules agents most often break:

1. **One request path.** Chat, Agent, and Workflow APIs go through `RequestController`. No API calls an engine directly.
2. **The Router asks the Model Manager** for candidates. Only the Model Manager calls `load`/`unload`.
3. **Context only via the Context Manager.** Engines never call the Memory or RAG engines directly.
4. **No `android.*` imports** in `api`, `core`, `services`, or `inference` packages (decision D1). Android code goes in `platform/android` or backend modules.
5. **Remote is a backend**, not a separate stack. `:sdk` makes no network calls and has no `INTERNET` permission (NFR-1).
6. **Never persist or log credentials.** Tool arguments and results are redacted from logs by default (NFR-2).
7. **Every request ends in exactly one terminal event** (NFR-7). Use the terminal guard from T2AI-001.

## Conventions

- SDK: Kotlin, coroutines `Flow` for streaming, kotlinx-serialization for JSON. `:app` stays Java until there is a reason to change.
- Public API changes are deliberate: keep internals `internal`; once the API dump exists (T2AI-040), update it in the same change.
- Don't add a dependency unless the story or a decision in architecture.md calls for it. If you need one, add a decision row first.
- Deliberate shortcuts with a known ceiling get a `ponytail:` comment naming the ceiling and the upgrade path.
- Tests: JVM tests with `FakeBackend`/`FakePlatform` for everything above the platform layer; instrumented tests only for Android and native code.

## How to work a story

1. Open the story in `docs/backlog/E*.md`. Check **Depends on** stories are `Done`; if not, stop and say so.
2. Set **Status** to `In progress`.
3. Do the tasks in order. One task ≈ one reviewable change. Tick each `- [ ]` → `- [x]` when it is built and verified.
4. If a task is wrong or missing, fix the story text in the same change and say why. If a requirement changes, update `docs/requirements.md` too.
5. When everything is ticked and the Definition of Done holds, set **Status** to `Done`.

In Claude Code, `/story T2AI-0XX` runs this loop.

## Definition of done

- All acceptance criteria are met and each is covered by a test (or a documented manual check where a test is impractical).
- `./gradlew :app:assembleDebug test` passes; instrumented tests pass for stories that touch Android or native code.
- No architecture rule above is broken.
- Docs touched by the change (story, requirements, architecture, KDoc for public API) are updated.
- Commit message references the story ID, e.g. `T2AI-004: SQLite storage`.
