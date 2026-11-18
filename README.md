# Tune2AI Studio

Tune2AI Studio is a responsive developer console prototype packaged as a lightweight Android WebView app.

## Build the Android app

Open the project in Android Studio and let Gradle sync, then build the `app` module. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The app requires Android API 26 or later. Playground replies are currently simulated; no model backend is connected yet.

## Web preview

The web UI lives only in `app/src/main/assets/www/`. Serve that folder with any static file server (for example `python -m http.server -d app/src/main/assets/www`) and open `index.html`.

## Project docs

- [docs/requirements.md](docs/requirements.md) — requirements
- [docs/architecture.md](docs/architecture.md) — architecture, diagram, and technical decisions
- [docs/backlog/](docs/backlog/README.md) — epics, stories, tasks, and milestones
- [AGENTS.md](AGENTS.md) — context and working rules for contributors and coding agents
