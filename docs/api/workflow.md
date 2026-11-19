# Workflow API

`ai.workflow` runs a repeatable, multi-step task: ordered model and tool steps, values passed between them, and branches. Runs are checkpointed after every step, so a run can resume after the app is killed without repeating finished steps.

## Define a workflow

A `WorkflowDefinition` has an `id`, a `version`, the named `inputs` a run must supply, and its `steps`. Execution starts at the first step and follows transitions until a step has none; that step's output is the run's result.

```kotlin
val greetWorkflow = WorkflowDefinition(
    id = "greet",
    version = 1,
    inputs = listOf("name"),
    steps = listOf(
        WorkflowStep(
            id = "ask",
            type = StepType.MODEL,
            prompt = "Greet {name} in one sentence.",
            output = "greeting",
            transitions = listOf(Transition("shout")),
        ),
        WorkflowStep(
            id = "shout",
            type = StepType.TOOL,
            tool = "upper",
            arguments = """{"text":"{greeting}"}""",
        ),
    ),
)
```

- A `MODEL` step sends `prompt` through the same request path as chat. A `TOOL` step calls a tool registered on the builder with `addTool`, with `arguments` as JSON.
- `{name}` placeholders in prompts and arguments are filled from the run's variables: the inputs, plus each earlier step's result stored under its `output` (the step id if you do not set one).
- `transitions` are tried in order. `Transition("next")` is unconditional; `Transition("next", whenVar = "decision", equals = "urgent")` is taken only when that variable has that value. Put unconditional transitions last, as the "else" branch. A step with no matching transition ends the run.
- Definitions are plain data and serialize to JSON (`kotlinx.serialization`).

## Validate and run

```kotlin
suspend fun runGreeting(ai: Tune2AI): String {
    val problems = ai.workflow.validate(greetWorkflow)
    check(problems.isEmpty()) { "Invalid workflow: $problems" }
    var result = ""
    ai.workflow.start(greetWorkflow, inputs = mapOf("name" to "Ada")).collect { event ->
        if (event is Tune2AIEvent.TextDelta) result += event.text
    }
    return result
}
```

`validate` returns every problem found: unknown transition targets, steps that cannot be reached, cycles (a definition has no loop bound, so any cycle is rejected), unregistered tools, and references to variables nothing defines. `start` validates again and ends with a `Failed(INVALID_REQUEST)` event for an invalid definition or missing inputs, before running anything.

The stream reports progress per step, `ToolCall` and `ToolResult` for tool steps, the final output as text, and one terminal event. A failing tool step fails the run with `TOOL_FAILURE`. `options.deadlineMillis` bounds the whole run.

## Inspect, resume, and delete

A run is identified by its `RequestId` (the `requestId` on every event). Pass your own to `start` to know it in advance.

```kotlin
suspend fun resumeUnfinished(ai: Tune2AI): List<String> {
    val resumed = mutableListOf<String>()
    for (run in ai.workflow.list().filter { it.canResume && it.definitionId == greetWorkflow.id }) {
        ai.workflow.resume(greetWorkflow, RequestId(run.runId)).collect { }
        resumed += run.runId
    }
    return resumed
}
```

- `status(runId)` and `list()` return `WorkflowRunStatus`: whether the run completed, the next step, the variables so far, and `canResume`.
- `resume(definition, runId)` continues from the last checkpoint without re-running finished steps. Resuming with a different `version` than the checkpoint was made with is refused (`INVALID_REQUEST`): bump `version` whenever you change a definition, and finish or delete old runs first.
- Checkpoints are never deleted automatically. Delete finished runs yourself:

```kotlin
suspend fun deleteFinishedRuns(ai: Tune2AI) {
    ai.workflow.list().filter { !it.canResume }.forEach { ai.workflow.delete(it.runId) }
}
```

## Cancelling

Cancel a `start`/`resume` flow by cancelling its collection. For the callback form, `ai.workflow.stream(...)` returns a `Cancellable`, and `ai.workflow.cancel(runId)` stops a run started that way. A cancelled run keeps the checkpoint from its last finished step and can be resumed from there (a run cancelled during its first step has no checkpoint yet).
