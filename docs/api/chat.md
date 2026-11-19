# Chat API

`ai.chat` sends messages to a model and streams the reply. Every request goes through the same path: validation, routing to a compatible model and backend, context assembly, then generation.

## One message

`ai.chat.send(message)` returns a `Flow<Tune2AIEvent>`: progress events, `TextDelta` pieces, then exactly one terminal event.

```kotlin
suspend fun askOnce(ai: Tune2AI): String {
    val answer = StringBuilder()
    ai.chat.send("What is the capital of France?").collect { event ->
        when (event) {
            is Tune2AIEvent.TextDelta -> answer.append(event.text)
            is Tune2AIEvent.Failed -> throw event.error
            else -> Unit
        }
    }
    return answer.toString()
}
```

## A conversation

`ai.chat.session(id)` keeps history in local storage under `id`. Only completed exchanges are stored (a cancelled or failed request leaves the history unchanged), and the Context Manager drops the oldest turns when the history no longer fits the model's context.

```kotlin
suspend fun converse(ai: Tune2AI): List<ChatMessage> {
    val session = ai.chat.session("support-thread")
    session.send("My name is Ada.").collect { }
    session.send("What is my name?").collect { }
    return session.history()
}
```

`session.clear()` forgets the conversation.

## Options

`RequestOptions` is shared by chat, agent, and workflow requests.

| Option | Default | Meaning |
|---|---|---|
| `model` | automatic | Model id to use. A model that cannot run the request fails with `NO_COMPATIBLE_ROUTE`, naming why. |
| `backend` | automatic | Backend id to use, for example `"remote"`. |
| `deadlineMillis` | 120000 | The whole request, including loading a model. Exceeding it ends with `TIMEOUT`. |
| `maxTokens` | 512 | Reply length limit. Must be below the model's context length. |
| `temperature` | 0.7 | 0 to 2. 0 is deterministic. |
| `system` | none | A system prompt for this request. Replaces the default prompt template. |
| `promptTemplate`, `promptVariables` | default template | Use a registered, versioned template instead. |

```kotlin
fun pirateOptions() = RequestOptions(
    model = "qwen2.5-0.5b",
    temperature = 1.0f,
    maxTokens = 256,
    deadlineMillis = 60_000,
    system = "You are a pirate. Answer in pirate speak.",
)
```

With no preference, the router prefers a model that is already loaded, then the smallest one that fits.

## Cancelling

Cancel by cancelling the collector. Here the flow stops after the first sentence, and the model stops generating:

```kotlin
suspend fun firstSentence(ai: Tune2AI): String {
    val text = StringBuilder()
    ai.chat.send("Tell me a long story").takeWhile { event ->
        if (event is Tune2AIEvent.TextDelta) text.append(event.text)
        !text.contains('.')
    }.collect { }
    return text.toString()
}
```

Cancellation stops backend work within 500 ms.

## Callbacks (Java and non-coroutine code)

`stream(...)` delivers events to an `EventListener` on a background thread and returns a `Cancellable`. Exactly one terminal event is delivered, including `Cancelled` after `cancel()`.

```kotlin
fun startFromJavaStyleCode(ai: Tune2AI, onEvent: (Tune2AIEvent) -> Unit): Cancellable =
    ai.chat.stream("Hello", listener = EventListener { onEvent(it) })
```

From Java: `ai.getChat().stream("Hello", event -> ...)` and `handle.cancel()`.
