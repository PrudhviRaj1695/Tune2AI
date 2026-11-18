# E0 — Project setup

Goal: a buildable, tested `:sdk` module and CI before any feature work, so every later story has a place to land and a check that guards it.

### T2AI-000 — Create the SDK module and CI

- **Status:** Done
- **Milestone:** 1 · **Requirements:** NFR-9, NFR-13 · **Depends on:** —

**Story:** As an SDK maintainer, I want an `:sdk` Kotlin library module with unit tests and CI so that every story lands in a known structure and is checked automatically.

**Acceptance criteria**

- `./gradlew :sdk:assembleRelease :sdk:testDebugUnitTest :app:assembleDebug` succeeds locally and in CI.
- `:sdk` has the package layout from decision D1 and a JVM unit test that runs without a device.
- `:app` depends on `:sdk`.
- A pull request runs the build and tests automatically.

**Tasks**

- [x] T2AI-000.1 Remove `android.builtInKotlin=false` from `gradle.properties` so AGP 9 built-in Kotlin compiles the SDK. Confirm `:app` still builds.
- [x] T2AI-000.2 Create `sdk/` (`com.android.library`, namespace `com.tune2ai.sdk`, minSdk 26, compileSdk 36) and add it to `settings.gradle`. Packages: `api`, `core`, `services`, `inference`, `platform`.
- [x] T2AI-000.3 Add `kotlinx-coroutines-core`, `kotlinx-coroutines-android`, and `kotlinx-serialization-json` with the serialization compiler plugin (D2), plus JUnit + `kotlinx-coroutines-test` for tests. Use a version catalog (`gradle/libs.versions.toml`).
- [x] T2AI-000.4 Add a placeholder `Tune2AI.version` constant and one JVM unit test for it.
- [x] T2AI-000.5 Add `implementation project(':sdk')` to `:app`.
- [x] T2AI-000.6 Add `.github/workflows/android.yml`: JDK 21, Gradle cache, run the command from the acceptance criteria on push and pull request.
- [x] T2AI-000.7 Add a check that fails the build if any file under `api`, `core`, `services`, or `inference` imports `android.` (a Gradle task that greps sources is enough).
