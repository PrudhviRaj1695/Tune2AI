# Tune2AI implementation stories — based on the original architecture

This backlog follows the architecture in `Tune2AI archi.jpeg` only. It covers the Developer SDK, AI Orchestration Core, AI Services, Inference Runtime, and Native Platform layers. The optional cloud-service split from the earlier refinement is excluded.

## End-to-end outcome

An application integrates the Unified AI API, sends a chat, agent, workflow, or model request, and receives a typed result. The orchestration core routes the task to AI services and an available inference backend; the native adapters provide platform capabilities, local storage, and resource monitoring.

## Epic 1 — SDK contracts and native platform foundation

### T2AI-001 — Define shared SDK request, response, and event types

**Story:** As an application developer, I want consistent request and response types across the SDK APIs so that I can handle results, progress, and errors uniformly.

**Acceptance criteria**

- Chat, agent, workflow, and model operations use shared identifiers, configuration, result, and typed error conventions.
- Streaming events represent output deltas, tool activity, completion, and failure.
- Cancellation, deadlines, and backward-compatible API versioning are defined.

### T2AI-002 — Create the Android native adapter

**Story:** As an Android application developer, I want the SDK to access supported Android capabilities through a Kotlin/JNI adapter so that native functionality is isolated from shared runtime code.

**Acceptance criteria**

- The adapter exposes the platform operations required by inference, local storage, and resource monitoring.
- JNI boundaries map native failures to documented SDK errors.
- Adapter initialization and release follow Android application lifecycle expectations.

### T2AI-003 — Create the iOS native adapter

**Story:** As an iOS application developer, I want the SDK to access supported iOS capabilities through a Swift adapter so that native functionality is isolated from shared runtime code.

**Acceptance criteria**

- The adapter exposes the platform operations required by inference, local storage, and resource monitoring.
- Swift/native failures map to documented SDK errors.
- Adapter initialization and release follow iOS application lifecycle expectations.

### T2AI-004 — Provide the local storage interface

**Story:** As an application developer, I want a consistent local storage interface so that sessions, model metadata, and AI service state can be persisted on supported platforms.

**Acceptance criteria**

- The interface supports read, write, update, and delete operations for namespaced records.
- Storage can be cleared by namespace and reports persistence failures through typed errors.
- Stored data ownership, encryption responsibilities, and retention behavior are documented.

### T2AI-005 — Monitor device resources

**Story:** As an application developer, I want the SDK to observe device resource pressure so that inference work can respond safely to constrained devices.

**Acceptance criteria**

- Android and iOS adapters expose available memory and applicable pressure signals.
- The runtime can reject, defer, or stop work according to configured resource limits.
- The application receives a status or typed error when resource pressure changes execution.

## Epic 2 — Inference Runtime Layer

### T2AI-010 — Define the inference abstraction

**Story:** As a runtime maintainer, I want a backend-neutral inference interface so that backends can be added without changing SDK APIs or orchestration logic.

**Acceptance criteria**

- The interface supports model discovery, load/unload, capability reporting, generation, streaming, and cancellation.
- Backend errors map to stable runtime error categories.
- Adapter capabilities include supported model formats, modalities, and relevant resource requirements.

### T2AI-011 — Integrate the llama.cpp backend

**Story:** As an application developer, I want to run a compatible model through llama.cpp so that Tune2AI can provide local inference on supported devices.

**Acceptance criteria**

- The backend implements the inference abstraction.
- Model loading, token generation, streaming, cancellation, and unload are available through the shared interface.
- Unsupported model files and load/resource failures return actionable errors.

### T2AI-012 — Integrate the ONNX Runtime backend

**Story:** As an application developer, I want to run supported ONNX models through ONNX Runtime so that applications can use this backend through the same SDK contract.

**Acceptance criteria**

- The backend implements the inference abstraction and reports its supported model capabilities.
- Generation results and failures follow the shared runtime contract.
- Unsupported graphs, operators, or model configurations fail with actionable errors.

### T2AI-013 — Integrate the Core ML backend

**Story:** As an iOS application developer, I want supported models to use Core ML so that inference can use the native iOS runtime.

**Acceptance criteria**

- The backend implements the inference abstraction through the iOS native adapter.
- Model load, inference, cancellation where supported, and unload are exposed consistently.
- The runtime reports unsupported model features and platform requirements before execution where possible.

### T2AI-014 — Register and describe additional backends

**Story:** As a runtime maintainer, I want future backends to register through a documented extension point so that new runtimes do not require changes to public APIs.

**Acceptance criteria**

- Backend registration validates required operations and capabilities.
- The runtime can list available backends and explain why a requested backend is unavailable.
- A backend integration guide documents lifecycle, errors, streaming, and resource reporting.

## Epic 3 — AI Services Layer

### T2AI-020 — Manage model metadata and lifecycle

**Story:** As an application developer, I want a Model Manager to inspect, prepare, load, unload, and select available models so that model use is consistent across backends.

**Acceptance criteria**

- Model metadata includes identity, version, format, capabilities, backend compatibility, and resource requirements.
- The manager exposes availability and load state and delegates runtime operations to the inference abstraction.
- A model cannot be selected for a request when its required backend or capabilities are unavailable.

### T2AI-021 — Manage prompt templates

**Story:** As an application developer, I want reusable prompt templates so that requests can apply consistent instructions and formatting.

**Acceptance criteria**

- Templates have stable identifiers, versions, and declared input variables.
- Missing or invalid variables fail before model invocation.
- The selected template and version can be associated with the request result metadata.

### T2AI-022 — Assemble request context

**Story:** As an application developer, I want a Context Manager to combine request input, prompt templates, memory, and retrieved content so that the model receives bounded relevant context.

**Acceptance criteria**

- Context sources and their ordering are defined and configurable.
- Context and model input limits are enforced before inference.
- The manager can report included source references and content omitted due to limits.

### T2AI-023 — Provide embedding and reranking services

**Story:** As an application developer, I want text embedding and optional reranking services so that stored content can be searched for relevant context.

**Acceptance criteria**

- Embedding requests report model identity, dimensions, and input limits.
- Reranking accepts candidate items and returns a stable ranked result.
- Incompatible embedding dimensions or unavailable services return typed errors.

### T2AI-024 — Implement the RAG Engine

**Story:** As an application developer, I want to add, update, delete, and retrieve content through the RAG Engine so that model requests can use application knowledge.

**Acceptance criteria**

- Content is chunked and indexed using the embedding service.
- Retrieval returns ranked chunks with source identifiers and metadata.
- Updates and deletions prevent stale chunks from appearing in future retrieval results.
- Context Manager can include retrieved results in a model request.

### T2AI-025 — Implement the Memory Engine

**Story:** As an application developer, I want memory operations to persist and retrieve selected information so that relevant state can be reused across requests.

**Acceptance criteria**

- Memory supports explicit write, search/read, update, and delete operations.
- Memory scope and ownership are explicit and isolated between application sessions.
- Memory can be disabled, inspected, and cleared by the application.

## Epic 4 — AI Orchestration Core

### T2AI-030 — Implement the request controller

**Story:** As an SDK maintainer, I want one request controller to validate and coordinate SDK operations so that chat, agent, workflow, and model requests share lifecycle handling.

**Acceptance criteria**

- The controller validates request shape, supported options, and execution limits.
- It coordinates routing, AI services, runtime calls, cancellation, and result assembly.
- Each request has a traceable request ID and a consistent terminal state.

### T2AI-031 — Implement model and task routing

**Story:** As an application developer, I want requests routed to a compatible model and backend so that task requirements are matched to available runtime capabilities.

**Acceptance criteria**

- Routing considers requested model, task, backend availability, and declared capabilities.
- Applications can provide a preferred model/backend or allow automatic selection.
- No compatible route returns a typed error that identifies the unmet capability.

### T2AI-032 — Implement the Chat API path

**Story:** As an application developer, I want chat requests to use the request controller, context services, router, and inference runtime so that I can complete a conversation through one SDK API.

**Acceptance criteria**

- A chat request can select a model, include conversation context, and receive a result.
- Streaming output follows the shared event contract and supports cancellation.
- Errors from routing, context assembly, or inference are surfaced consistently.

### T2AI-033 — Implement the Tool Calling Engine

**Story:** As an application developer, I want tools to be registered and invoked through a Tool Calling Engine so that model-requested actions are validated and controlled.

**Acceptance criteria**

- Each tool declares an input schema and invocation handler.
- Tool inputs are validated before execution; timeouts and handler failures are represented as tool results.
- Tool availability and invocation permissions are explicit to the application.
- Tool calls and results are included in the request event stream without exposing sensitive values by default.

### T2AI-034 — Implement the Agent Engine

**Story:** As an application developer, I want an Agent Engine to coordinate model responses and tool calls so that multi-step tasks can complete through the Agent API.

**Acceptance criteria**

- The engine supports bounded model/tool iterations with a deadline and configured call limits.
- Tool calls are delegated to the Tool Calling Engine and validated before invocation.
- The agent returns a final result or a typed limit/failure state.
- Intermediate progress can be observed through the shared SDK event contract.

### T2AI-035 — Implement the Workflow Engine

**Story:** As an application developer, I want to define and execute workflows so that repeatable multi-step model and tool tasks can be run consistently.

**Acceptance criteria**

- A workflow definition supports ordered steps, inputs, transitions, and terminal results.
- Supported step types include model requests and tool calls.
- Invalid definitions fail validation before execution.
- Workflow runs expose status, result, failure reason, and cancellation behavior.

### T2AI-036 — Add workflow persistence and recovery

**Story:** As an application developer, I want workflow state persisted in local storage so that a workflow can resume after an application process interruption when configured.

**Acceptance criteria**

- Run state and completed step outputs are checkpointed using the Local Storage interface.
- The engine can resume from the last safe checkpoint without repeating completed steps unintentionally.
- The application can inspect and delete persisted workflow state.

## Epic 5 — Developer SDK Layer

### T2AI-040 — Provide the Unified AI API

**Story:** As an application developer, I want one SDK entry point for all Tune2AI capabilities so that initialization and shared configuration are consistent.

**Acceptance criteria**

- The Unified AI API configures platform adapters, storage, model manager, and runtime dependencies.
- It exposes typed Chat, Agent, Workflow, and Model APIs.
- Initialization and shutdown behavior is documented and reports dependency failures clearly.

### T2AI-041 — Publish the Chat API

**Story:** As an application developer, I want a Chat API for single-turn and session-based conversations so that I can build chat experiences without calling internal components.

**Acceptance criteria**

- The API supports one-shot and session-based requests, streaming, cancellation, and typed errors.
- It exposes model selection and context options supported by the shared contracts.
- Chat session operations use the configured storage behavior.

### T2AI-042 — Publish the Agent API

**Story:** As an application developer, I want an Agent API for bounded tool-using tasks so that I can request multi-step execution through the SDK.

**Acceptance criteria**

- The API accepts task input, available tools, and execution limits.
- The application can observe progress and tool activity and cancel a run.
- The result distinguishes completion, cancellation, limit reached, and failure.

### T2AI-043 — Publish the Workflow API

**Story:** As an application developer, I want a Workflow API to define, start, inspect, cancel, and resume workflows so that multi-step processes have a stable SDK interface.

**Acceptance criteria**

- The API validates a workflow definition before starting a run.
- Run state and step results can be inspected through the SDK.
- Persistence and resume capabilities are reported according to platform configuration.

### T2AI-044 — Publish the Model API

**Story:** As an application developer, I want a Model API to discover and manage available models so that I can prepare models before sending requests.

**Acceptance criteria**

- The API lists models and their backend/capability compatibility.
- The application can prepare/load, inspect status, and unload a model.
- Model lifecycle and resource errors use the shared typed error contract.

## Epic 6 — Integration and release

### T2AI-050 — Provide an Android end-to-end sample

**Story:** As an Android developer, I want a sample application using the public SDK so that I can see how to configure Tune2AI and complete a local chat request.

**Acceptance criteria**

- The sample demonstrates initialization, model selection, chat streaming, cancellation, and cleanup.
- It displays loading, resource-limit, and error states.
- It uses only public SDK APIs.

### T2AI-051 — Provide an iOS end-to-end sample

**Story:** As an iOS developer, I want a sample application using the public SDK so that I can see how to configure Tune2AI and complete a local chat request.

**Acceptance criteria**

- The sample demonstrates initialization, model selection, chat streaming, cancellation, and cleanup.
- It displays loading, resource-limit, and error states.
- It uses only public SDK APIs.

### T2AI-052 — Document SDK integration and supported models

**Story:** As an application developer, I want platform setup and model compatibility documentation so that I can select a supported configuration and troubleshoot integration.

**Acceptance criteria**

- Documentation covers Android and iOS setup, SDK lifecycle, APIs, storage, and resource behavior.
- Supported model formats and backend compatibility are listed.
- Examples show chat, agents, workflows, model lifecycle, and typed error handling.

### T2AI-053 — Package and version SDK releases

**Story:** As a release owner, I want versioned SDK packages for Android and iOS so that applications can adopt Tune2AI predictably.

**Acceptance criteria**

- Package versions, supported OS versions, and backend matrix are identifiable in each release.
- Public API compatibility and deprecation rules are documented.
- Release notes describe new capabilities, known limitations, and migration steps.

## Recommended delivery sequence

| Milestone | Stories | Working outcome |
|---|---|---|
| 1. Contracts and platform foundation | T2AI-001–005 | Shared API contract, Android/iOS adapters, local storage, resource signals |
| 2. First local chat path | T2AI-010, 011, 020–022, 030–032, 040–041, 050 or 051 | App sends chat through the SDK and receives streamed output from a local backend |
| 3. Backend coverage | T2AI-012–014, 044 | ONNX/Core ML support and model discovery/lifecycle |
| 4. Agents and tools | T2AI-033–034, 042 | Bounded agent uses an explicitly registered tool and returns a result |
| 5. Workflows | T2AI-035–036, 043 | Workflow definitions run, report status, persist, and resume |
| 6. AI services | T2AI-023–025 | RAG, embeddings/reranking, and controllable memory feed request context |
| 7. SDK release | T2AI-051–053 and remaining samples/docs | Android and iOS integration material and versioned packages |

## Dependency notes

- T2AI-001 precedes all public API work; T2AI-002–005 provide platform capabilities used by runtime and storage.
- T2AI-010 precedes each inference backend. T2AI-020 manages models through T2AI-010.
- T2AI-030 and T2AI-031 are the shared orchestration path for Chat, Agent, and Workflow APIs.
- T2AI-021–024 feed context into T2AI-032 and can be added after the first chat path.
- T2AI-033 precedes T2AI-034 and tool-capable workflow steps in T2AI-035.
- T2AI-036 depends on T2AI-004 and T2AI-035.
- T2AI-040 composes the completed SDK-facing services; T2AI-041–044 expose each public API.
