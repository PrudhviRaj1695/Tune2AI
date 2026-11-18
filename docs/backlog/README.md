# Tune2AI backlog

Requirements: [../requirements.md](../requirements.md). Architecture and decisions D1–D6: [../architecture.md](../architecture.md).

## Epics

| Epic | File | Stories |
|---|---|---|
| E0 — Project setup | [E0-project-setup.md](E0-project-setup.md) | T2AI-000 |
| E1 — SDK contracts and native platform | [E1-contracts-platform.md](E1-contracts-platform.md) | T2AI-001–005 |
| E2 — Inference runtime | [E2-inference-runtime.md](E2-inference-runtime.md) | T2AI-010–015 |
| E3 — AI services | [E3-ai-services.md](E3-ai-services.md) | T2AI-020–025 |
| E4 — AI orchestration core | [E4-orchestration.md](E4-orchestration.md) | T2AI-030–036 |
| E5 — Developer SDK APIs | [E5-sdk-api.md](E5-sdk-api.md) | T2AI-040–044 |
| E6 — Integration and release | [E6-integration-release.md](E6-integration-release.md) | T2AI-050–054 |

## Milestones

Work milestones in order. Within a milestone, follow each story's **Depends on**.

| # | Milestone | Stories | Working outcome |
|---|---|---|---|
| 1 | Contracts and Android foundation | T2AI-000, 001, 002, 004, 005 | `:sdk` module builds in CI; shared types, Android adapter, storage, resource signals |
| 2 | First tokens | T2AI-010, 011, 020, 030, 031 | An instrumented test streams tokens from llama.cpp through controller → router → Model Manager → inference |
| 3 | Android chat path | T2AI-021, 022, 032, 040, 041, 050 | Tune2AI Studio chats through the public SDK; simulated replies removed |
| 4 | Backend coverage | T2AI-012, 014, 015, 044, 054 | ONNX Runtime, optional Remote API (SDK + Studio settings), Model API |
| 5 | Agents and tools | T2AI-033, 034, 042 | Bounded agent calls a registered tool |
| 6 | Workflows | T2AI-035, 036, 043 | Workflows run, persist, and resume |
| 7 | AI services | T2AI-023, 024, 025 | Embeddings, RAG, and memory feed context |
| 8 | iOS | T2AI-003, 013, 051 | iOS adapter, Core ML, iOS sample (after Kotlin Multiplatform conversion, D1) |
| 9 | Release | T2AI-052, 053 | Docs and versioned packages |

## Conventions

- **Status** values: `Todo`, `In progress`, `Blocked (<reason>)`, `Deferred (<decision>)`, `Done`. Status lives only in the story; see all with `grep -rn "Status:" docs/backlog`.
- **Tasks** are checkboxes with IDs (`T2AI-001.2`). Tick a task when its code is merged and verified.
- A story is `Done` when every task is ticked and the Definition of Done in [AGENTS.md](../../AGENTS.md#definition-of-done) holds.
- Requirement IDs (`FR-…`, `NFR-…`) link stories back to [requirements.md](../requirements.md). Keep both sides in sync when adding or splitting stories.

## Adding a story

Add it to the right epic file, take the next free ID in that epic's range, and add it to a milestone above.

```markdown
### T2AI-0XX — <title>

- **Status:** Todo
- **Milestone:** <n> · **Requirements:** FR-… · **Depends on:** T2AI-…

**Story:** As a <user>, I want <capability> so that <outcome>.

**Acceptance criteria**

- <observable, testable statement>

**Tasks**

- [ ] T2AI-0XX.1 <one reviewable change, naming the module/package>
- [ ] T2AI-0XX.2 Tests: <what proves the acceptance criteria>
```
