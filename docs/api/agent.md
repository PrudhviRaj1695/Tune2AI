# Agent API

`ai.agent` runs a bounded loop: the model may call tools your app registered, sees each result, and finally answers in plain text. Every model step goes through the same request path as chat, so routing, context, and limits apply to each step.

## Register tools

Tools belong to your app (decision D6): the SDK never runs downloaded or interpreted code. Register them on the builder. A tool has a lowercase name, a one-sentence description the model reads, a JSON schema for its arguments, and a handler.

```kotlin
fun addTools(builder: Tune2AI.Builder): Tune2AI.Builder = builder.addTool(
    Tool(
        name = "add",
        description = "Adds two integers.",
        parametersSchema = """{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}""",
    ) { args ->
        val numbers = Json.parseToJsonElement(args).jsonObject
        val sum = numbers.getValue("a").jsonPrimitive.int + numbers.getValue("b").jsonPrimitive.int
        """{"sum":$sum}"""
    },
)
```

- The schema's root must be an object. Supported keywords: `type` (object, string, number, integer, boolean, array), `properties`, `required`, `items`, `enum`, `description`.
- Arguments are validated against the schema before your handler runs. A bad call becomes an error result the model can recover from; it does not fail the request.
- The handler runs on a background thread and may block. It is interrupted when `timeoutMillis` (default 10 s) passes or the request is cancelled. Throwing reports an error result to the model.
- Return JSON. Plain text is wrapped as a JSON string.
- Keep results small and plainly labelled: small models misread raw fields.

## Run an agent

```kotlin
suspend fun askAgent(ai: Tune2AI, log: (String) -> Unit): String {
    val answer = StringBuilder()
    ai.agent.run(
        task = "What is 2 + 3?",
        tools = setOf("add"),
        limits = AgentLimits(maxSteps = 4, maxToolCalls = 2),
    ).collect { event ->
        when (event) {
            is Tune2AIEvent.ToolCall -> log("calling ${event.name} with ${event.argumentsJson}")
            is Tune2AIEvent.ToolResult -> log("${event.name} returned ${event.resultJson}")
            is Tune2AIEvent.TextDelta -> answer.append(event.text)
            is Tune2AIEvent.Failed ->
                if (event.error.category == ErrorCategory.LIMIT_REACHED) log("the agent ran out of budget") else throw event.error
            else -> Unit
        }
    }
    return answer.toString()
}
```

- `tools` limits which registered tools this run may call; omit it to allow all. An unknown name fails with `INVALID_REQUEST` before anything runs.
- `AgentLimits(maxSteps = 6, maxToolCalls = 5)` bound the run. Reaching either ends it with a `Failed` event whose category is `LIMIT_REACHED`.
- `options.deadlineMillis` bounds the whole run.
- The four outcomes are distinct: `Completed`, `Cancelled`, `Failed(LIMIT_REACHED)`, and `Failed` with any other category.

## Model requirements

The agent asks the model to reply with a JSON tool call. Models that follow instructions well (Qwen2.5-0.5B-Instruct and larger) work; very small models such as SmolLM2-135M usually do not. A malformed call is sent back to the model with a correction request, and counts as a step.

`ai.agent.stream(task, ..., listener = ...)` is the callback form for Java.
