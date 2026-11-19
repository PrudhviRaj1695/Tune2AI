# AGENTS.md — Tune2AI

Context for coding agents and new contributors. Read this first, then the story you are working on.

## What this project is

Tune2AI is an on-device AI SDK for mobile apps (chat, tool-using agents, workflows) with an optional remote model backend. Tune2AI Studio (`:app`) is the Android sample and developer console.

**Current state:** Milestones 1–7 are done (ONNX Runtime, T2AI-012, is deferred by decision D8). Tune2AI Studio is live: on-device chat through llama.cpp and remote chat through any OpenAI-compatible endpoint, both through the public SDK. Agent mode and Workflow mode are live (agent tools: `device_status`, `list_models`, `current_time`). The SDK also has per-session memory, embeddings, and RAG (`Tune2AI.memory`, `.embeddings`, `.rag`), tested on the JVM and on a device; Studio does not expose memory or RAG yet, so its memory/RAG switches are still simulated. See `app/README.md` to run it.

## Where things are

| Path | What |
|---|---|
| [docs/requirements.md](docs/requirements.md) | Functional (`FR-…`) and non-functional (`NFR-…`) requirements |
| [docs/architecture.md](docs/architecture.md) | Revised architecture, diagram, technical decisions D1–D6, open decisions |
| [docs/backlog/](docs/backlog/README.md) | Epics, stories, tasks, milestones. **The source of truth for what to build next.** |
| `app/` | Tune2AI Studio. Native shell: `MainActivity.java`. UI: `app/src/main/assets/www/` (the only copy of the web UI) |
| `scripts/architecture_diagram.py` | Regenerates `docs/architecture.svg` |
| `sdk/` | The SDK (Kotlin). Packages: `api` (public types), `core`, `services`, `inference`, `platform/android`. Tests: `src/test` (JVM), `src/androidTest` (device), `src/sharedTest` (contracts run on both) |
| `sdk-llama/` | llama.cpp backend: Kotlin `LlamaBackend` + JNI bridge `src/main/cpp/llama_jni.cpp`. llama.cpp is a git submodule pinned to a release tag (clone with `--recurse-submodules`) |
| `sdk-remote/` | Remote backend: OpenAI-compatible Chat Completions over HTTPS + SSE (decision D7). The only SDK module with `INTERNET` |
| `docs/backend-guide.md` | How to write and test a new backend |
| `models/` | Local model files for testing (git-ignored) |

## Build and test

```sh
# Windows (Git Bash): use Android Studio's JDK
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

./gradlew :app:assembleDebug            # Studio APK -> app/build/outputs/apk/debug/
./gradlew test                          # JVM unit tests (once :sdk exists)
./gradlew connectedAndroidTest          # instrumented tests, needs a device/emulator
./gradlew apiCheck :sdk:checkAarSize     # public API dump and the 1 MB :sdk size budget (run ./gradlew apiDump after an intended API change)
./gradlew :dokkaGenerate                # API reference -> build/dokka/html
./gradlew publishAllPublicationsToLocalRepository  # dry-run Maven publish -> build/repo
```

llama.cpp device tests need a small model on the device (skipped otherwise):

```sh
mkdir -p models && curl -L -o models/SmolLM2-135M-Instruct-Q8_0.gguf   https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q8_0.gguf
MSYS_NO_PATHCONV=1 adb push models/SmolLM2-135M-Instruct-Q8_0.gguf /data/local/tmp/tune2ai-test.gguf
./gradlew :sdk-llama:connectedDebugAndroidTest
```

On Windows Git Bash, prefix `adb` commands that take device paths with `MSYS_NO_PATHCONV=1`; otherwise `/data/...` is rewritten to a Windows path. The first `:sdk-llama` build installs the NDK and CMake and compiles llama.cpp (about 7 minutes); later builds are incremental.

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
- Public API changes are deliberate: keep internals `internal`, and run `./gradlew apiDump` in the same change (`apiCheck` fails CI on drift). Docs examples live in `sdk/src/docsTest`; a `kotlin` block in `docs/` must match them verbatim (`DocsSnippetsTest`).
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
