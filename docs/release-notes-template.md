# Release notes template

Copy this into `CHANGELOG.md` as a new `## [x.y.z] - YYYY-MM-DD` section, under `## [Unreleased]`. The release workflow publishes the section whose heading matches the tag, so the heading must read exactly `## [x.y.z]`.

Release checklist:

1. Set `VERSION_NAME` in `gradle.properties` and `SDK_VERSION` in `sdk/src/main/kotlin/com/tune2ai/sdk/api/Version.kt` to the same value (`VersionTest` checks this).
2. Run `./gradlew apiDump` if the public API changed, review the diff of `*/api/*.api`, and commit it. A breaking change needs a major version (or a minor version before 1.0) and a migration note.
3. Re-measure the NFR table below. Do not carry old numbers forward.
4. Fill in the sections below and commit `CHANGELOG.md`.
5. Tag the commit `vX.Y.Z` and push the tag. The release workflow builds, tests, checks the size and the API dump, publishes to GitHub Packages, and creates the GitHub release.

```markdown
## [x.y.z] - YYYY-MM-DD

One sentence on what this release is.

### Added / Changed / Fixed / Removed

- ...

### Capabilities

- What an app can do now, by API.

### Backend compatibility

| Backend | Format | Tasks | Platforms |
|---|---|---|---|
| ... | | | |

Models checked: list the models you ran, and say which are expected to work but untested.

### Measured non-functional requirements

| NFR | Target | Measured | How |
|---|---|---|---|
| NFR-1 Privacy | No network without a remote backend | permissions in each published AAR manifest | |
| NFR-4 SDK overhead | p95 at most 50 ms | | test name, device or host |
| NFR-5 First token | within 100 ms | | test name, device or host |
| NFR-7 Terminal event, cancellation | one terminal event; stop within 500 ms | | |
| NFR-10 Size | tune2ai-sdk AAR at most 1 MB | bytes from `./gradlew :sdk:checkAarSize` | |

Say where each number was measured (host or device) and with what.

### Known limitations

- Honest list: what does not work yet, what was not measured.

### Migration

- Every breaking change and what to do about it. "None" if there are none.
```
