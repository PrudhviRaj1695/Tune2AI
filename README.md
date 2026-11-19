# Tune2AI Studio

Tune2AI is an on-device AI SDK for Android (chat, tool-using agents, workflows, memory, embeddings, and RAG) with an optional remote model backend. Tune2AI Studio is the sample app and developer console built on the public SDK.

Start with [docs/getting-started.md](docs/getting-started.md).

## Build the Android app

Open the project in Android Studio and let Gradle sync, then build the `app` module. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The app requires Android API 26 or later. Chat, Agent, and Workflow run for real through the SDK (llama.cpp on the device, or a remote endpoint); only the Memory and RAG switches are still simulated. See [app/README.md](app/README.md) for how to add a model and a manual test script.

## Web preview

The web UI lives only in `app/src/main/assets/www/`. Serve that folder with any static file server (for example `python -m http.server -d app/src/main/assets/www`) and open `index.html`.

## Project docs

- [docs/getting-started.md](docs/getting-started.md) — integrate the SDK; [docs/api/](docs/api/README.md) — one guide per API; [docs/compatibility.md](docs/compatibility.md) — supported models and backends
- [docs/requirements.md](docs/requirements.md) — requirements
- [docs/architecture.md](docs/architecture.md) — architecture, diagram, and technical decisions
- [docs/backlog/](docs/backlog/README.md) — epics, stories, tasks, and milestones
- [AGENTS.md](AGENTS.md) — context and working rules for contributors and coding agents
