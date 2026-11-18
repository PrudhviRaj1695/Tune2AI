# E4 — AI orchestration core

Goal: one request path (controller → router → Model Manager → inference) that chat, agents, and workflows all share.

### T2AI-030 — Implement the request controller

- **Status:** Done
- **Milestone:** 2 · **Requirements:** FR-ORC-1, NFR-6, NFR-7, NFR-12 · **Depends on:** T2AI-001, T2AI-031

**Story:** As an SDK maintainer, I want one request controller to validate and coordinate SDK operations so that chat, agent, and workflow requests share lifecycle handling.

**Acceptance criteria**

- Chat, Agent, and Workflow API requests all enter through the controller; no API calls an engine directly.
- The controller validates request shape, options, and limits, assigns a `RequestId`, and enforces the deadline.
- It coordinates routing, context assembly, inference, cancellation, and result assembly.
- Model steps issued by the Agent and Workflow Engines go back through the controller.
- Every request emits exactly one terminal event (uses the guard from T2AI-001).

**Tasks**

- [x] T2AI-030.1 `core/RequestController.kt`: `execute(request): Flow<Tune2AIEvent>` running on a background dispatcher.
- [x] T2AI-030.2 Validation rules and `invalid request` errors.
- [x] T2AI-030.3 Deadline via `withTimeout` mapped to the `timeout` category.
- [x] T2AI-030.4 Hook for context assembly: `ContextAssembler` (pass-through until T2AI-022). Engines (T2AI-034, T2AI-035) will call `chat` for model steps.
- [x] T2AI-030.5 Tests (JVM, `FakeBackend`): happy path, validation failure, timeout, cancellation, backend failure; each ends in one terminal event.
- [x] T2AI-030.6 Measure controller overhead in a JVM micro-benchmark test and record it against NFR-4. Measured p95 1.4 ms (budget 50 ms). Note: the terminal guard must run before `flowOn`, or a failure drops events already emitted (found and fixed by these tests).

### T2AI-031 — Implement model and task routing

- **Status:** Done
- **Milestone:** 2 · **Requirements:** FR-ORC-2 · **Depends on:** T2AI-020

**Story:** As an application developer, I want requests routed to a compatible model and backend so that task requirements match available capabilities.

**Acceptance criteria**

- Routing considers the requested model, task type, backend availability, and declared capabilities.
- The router reads availability and compatibility from the Model Manager and keeps no model state of its own.
- An explicit preference is honoured or rejected with `no compatible route` naming the unmet capability; otherwise selection is automatic.

**Tasks**

- [x] T2AI-031.1 `core/Router.kt`: `route(task, options): Route` (model + backend) using `ModelManager.candidates`.
- [x] T2AI-031.2 Selection order: explicit preference → already loaded → smallest model meeting requirements.
- [x] T2AI-031.3 Tests: preference honoured, preference rejected with reason, loaded model preferred, no candidates.

### T2AI-032 — Implement the chat path

- **Status:** Done
- **Milestone:** 3 · **Requirements:** FR-SDK-3, FR-SDK-4 · **Depends on:** T2AI-022, T2AI-030, T2AI-031

**Story:** As an application developer, I want chat requests to use the controller, context services, router, and inference runtime so that I complete a conversation through one SDK API.

**Acceptance criteria**

- A chat request selects a model, includes conversation context, and streams a result.
- Streaming follows the shared event contract and supports cancellation.
- Routing, context, and inference errors surface consistently.

**Tasks**

- [x] T2AI-032.1 The pipeline is `RequestController.chat` (route → context assembly → `acquire` → generate → events); no separate class was needed.
- [x] T2AI-032.2 Chat message format; per-model chat templates are applied by the backend (llama.cpp reads the template from the GGUF file).
- [x] T2AI-032.3 Tests (JVM): end-to-end with `FakeBackend`, cancellation mid-stream, context trimmed report present.

### T2AI-033 — Implement the tool calling engine

- **Status:** Done
- **Milestone:** 5 · **Requirements:** FR-ORC-3, NFR-2 · **Depends on:** T2AI-001

**Story:** As an application developer, I want tools registered and invoked through a Tool Calling Engine so that model-requested actions are validated and controlled.

**Acceptance criteria**

- Each tool declares a name, description, JSON input schema, timeout, and a handler (D6). Handlers take and return JSON strings (Java-friendly) and may block; they are interrupted on timeout or cancellation.
- Inputs are validated against the schema before execution; timeouts and handler failures become tool results, not crashes.
- Tools are allowed per request; a model cannot call a tool the request did not allow.
- Tool call and result events are emitted; arguments and results are redacted from logs by default.

**Tasks**

- [x] T2AI-033.1 `api/Tools.kt` (`Tool`, `ToolHandler`, `AgentLimits`); registration and validation live in `ToolCallingEngine`.
- [x] T2AI-033.2 Minimal JSON-schema validator for the subset we support (object, string, number, integer, boolean, array, enum, required) on `kotlinx.serialization.json.JsonElement`; document the subset. (Not `org.json`: it is only a stub in JVM unit tests and is not multiplatform.)
- [x] T2AI-033.3 `ToolCallingEngine.kt`: allow-list check, validate, invoke with timeout, shape result.
- [x] T2AI-033.4 Tool-call parsing from model output: bare `{"tool": …, "arguments": …}` JSON or Qwen-style `<tool_call>` tags; text that starts like a call but does not parse is reported as malformed.
- [x] T2AI-033.5 Tests: invalid input rejected, disallowed tool rejected, timeout result, handler exception result, redaction.

### T2AI-034 — Implement the agent engine

- **Status:** Done
- **Milestone:** 5 · **Requirements:** FR-ORC-4 · **Depends on:** T2AI-030, T2AI-032, T2AI-033

**Story:** As an application developer, I want an Agent Engine to coordinate model responses and tool calls so that multi-step tasks complete through the Agent API.

**Acceptance criteria**

- Iterations are bounded by max steps, max tool calls, and a deadline.
- Tool calls are delegated to the Tool Calling Engine.
- Memory and other context reach the agent only through the Context Manager; the engine never calls the Memory Engine directly.
- The run ends as completed, cancelled, limit reached, or failed; progress is observable through events.

**Tasks**

- [x] T2AI-034.1 `core/agent/AgentEngine.kt`: loop of model step (via controller) → parse tool calls → invoke → append results. Final answers stream live; steps that start like a tool call are held back so raw JSON is never shown.
- [x] T2AI-034.2 `AgentLimits` (steps, tool calls); new error category `LIMIT_REACHED`.
- [x] T2AI-034.3 Tests (scripted `FakeBackend`): tool round-trip, step limit, deadline, cancellation during a tool call.

### T2AI-035 — Implement the workflow engine

- **Status:** Todo
- **Milestone:** 6 · **Requirements:** FR-ORC-5 · **Depends on:** T2AI-030, T2AI-033

**Story:** As an application developer, I want to define and execute workflows so that repeatable multi-step model and tool tasks run consistently.

**Acceptance criteria**

- A definition has ordered steps, inputs, transitions, and terminal results.
- Step types: model request and tool call; model steps go through the controller.
- Invalid definitions (unknown step, unreachable step, cycle without a bound) fail validation before execution.
- Runs expose status, result, failure reason, and cancellation.

**Tasks**

- [ ] T2AI-035.1 `core/workflow/WorkflowDefinition.kt` (Kotlin data classes; JSON form for persistence).
- [ ] T2AI-035.2 `WorkflowValidator.kt`.
- [ ] T2AI-035.3 `WorkflowEngine.kt`: step execution, variable passing between steps, transitions.
- [ ] T2AI-035.4 Tests: linear workflow, branch, invalid definitions, cancel mid-run.

### T2AI-036 — Add workflow persistence and recovery

- **Status:** Todo
- **Milestone:** 6 · **Requirements:** FR-ORC-6 · **Depends on:** T2AI-004, T2AI-035

**Story:** As an application developer, I want workflow state persisted so that a workflow resumes after process death when configured.

**Acceptance criteria**

- Run state and completed step outputs are checkpointed in `Storage` after each step.
- Resume continues from the last checkpoint without re-running completed steps.
- The app can list, inspect, and delete persisted runs.

**Tasks**

- [ ] T2AI-036.1 Checkpoint format (run id, definition version, step index, outputs) in namespace `workflows`.
- [ ] T2AI-036.2 `WorkflowEngine.resume(runId)`; refuse when the definition version changed.
- [ ] T2AI-036.3 Tests: kill after step 2 (simulated), resume runs step 3 only; delete removes checkpoint.
