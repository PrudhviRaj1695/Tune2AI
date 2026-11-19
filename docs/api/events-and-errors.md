# Events and errors

Chat, agent, and workflow requests all report through one stream of `Tune2AIEvent`, and fail with one family of typed errors.

## The event contract

- Every event carries the `requestId` of the request that produced it, so logs and UI can correlate them.
- A stream emits zero or more non-terminal events, then **exactly one terminal event**, then completes.
- Terminal events: `Completed(usage)`, `Cancelled`, `Failed(error)`.

| Event | Meaning |
|---|---|
| `Progress(stage, detail, sources)` | The request reached a stage: `RECEIVED`, `VALIDATED`, `ROUTED`, `CONTEXT_ASSEMBLED`, `GENERATING`. At `CONTEXT_ASSEMBLED`, `sources` lists what went into the model input and what was omitted. |
| `TextDelta(text)` | A piece of output. Concatenate in order. |
| `ToolCall(callId, name, argumentsJson)` | The model asked for a tool (agents) or a workflow tool step ran. |
| `ToolResult(callId, name, resultJson, isError)` | A tool finished. Failures and timeouts are results (`isError`), not stream failures. |
| `Completed(usage)` | Done. `usage` has output tokens, time to first token, and total time. |
| `Cancelled` | The request was cancelled. Cancellation is never reported as `Failed`. |
| `Failed(error)` | The request failed; `error` is a `Tune2AIException`. |

New event types may be added in minor versions, so keep an `else` branch in a `when` over events.

## Errors

Every failure is a `Tune2AIException`. Match on the subclass, or on `category`, whose names are stable (they cross the Studio JavaScript bridge and appear in logs).

| Category | Raised when |
|---|---|
| `INVALID_REQUEST` | The request is malformed: empty or blank message, last message not from the user, `maxTokens` at or above the context length, unknown tool, invalid workflow, unknown source id. |
| `NO_COMPATIBLE_ROUTE` | No registered model and backend can run it. The message names what is missing (no models, a model that does not support the task, an unavailable backend). |
| `MODEL_UNAVAILABLE` | A model file is missing, is not a valid model, or the backend cannot load it. |
| `RESOURCE_LIMIT` | The device's memory, temperature, or battery limits (`ResourceLimits`) prevented or stopped the work. |
| `BACKEND_FAILURE` | The backend failed while running: a native error, a network failure, or a remote API error. |
| `TOOL_FAILURE` | A workflow tool step failed. (Agent tool failures are results the model sees, not request failures.) |
| `STORAGE_FAILURE` | Local storage could not read or write. A write never fails silently. |
| `TIMEOUT` | The request exceeded `RequestOptions.deadlineMillis`. |
| `LIMIT_REACHED` | An agent or workflow used its step or tool-call budget. |

Handle them all with an exhaustive `when`; the compiler tells you when a new subclass appears:

```kotlin
fun advice(error: Tune2AIException): String = when (error) {
    is Tune2AIException.InvalidRequest -> "Fix the request: ${error.message}"
    is Tune2AIException.NoCompatibleRoute -> "No model can run this: ${error.message}"
    is Tune2AIException.ModelUnavailable -> "Model problem: ${error.message}"
    is Tune2AIException.ResourceLimit -> "Wait for the device to cool down or free memory: ${error.message}"
    is Tune2AIException.BackendFailure -> "The backend failed: ${error.message}"
    is Tune2AIException.ToolFailure -> "A tool failed: ${error.message}"
    is Tune2AIException.StorageFailure -> "Storage failed: ${error.message}"
    is Tune2AIException.Timeout -> "The request took longer than its deadline"
    is Tune2AIException.LimitReached -> "The agent or workflow used its budget: ${error.message}"
}
```

The SDK keeps credentials out of error messages and logs. Messages can include ids you chose (source, workflow, tool, and model ids) and a tool's own error text, so do not put secrets in those.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `NO_COMPATIBLE_ROUTE`, "No models are registered" | Register a model with `ai.models.register`. |
| `NO_COMPATIBLE_ROUTE` naming a model | The model file is missing, its format has no backend, or it does not fit in memory. `ai.models.list()` shows its `Unavailable` reason. |
| `MODEL_UNAVAILABLE`, "does not support the chat template" | llama.cpp cannot format chats for that model. Try a different GGUF build or an instruct model. |
| `INVALID_REQUEST`, "maxTokens ... below the model's context length" | Lower `maxTokens` or register a larger `contextLength`. |
| `RESOURCE_LIMIT` right after launch | Free memory is below `minFreeMemoryBytes` plus the model's `memoryBytes`; use a smaller model or lower the limit. |
| Agent never calls a tool | The model is too small to follow the tool-call format; try Qwen2.5-0.5B-Instruct or larger. |
| `INVALID_REQUEST`, "The index was built with embedding model ..." | You changed embedding models. Call `ai.rag.clear()` and add the content again. |
