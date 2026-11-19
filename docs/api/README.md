# API guides

One page per API. Every `kotlin` block here is compiled and run by the SDK's tests (`sdk/src/docsTest`), and `DocsSnippetsTest` fails if a block in the docs differs from that code.

| Page | Covers |
|---|---|
| [chat.md](chat.md) | `ai.chat`: one-shot and session chat, options, cancellation, callbacks |
| [agent.md](agent.md) | `ai.agent` and registering tools |
| [workflow.md](workflow.md) | `ai.workflow`: definitions, validation, checkpoints, resume |
| [models.md](models.md) | `ai.models` and `ai.backends()` |
| [knowledge.md](knowledge.md) | `ai.memory`, `ai.embeddings`, `ai.rag` |
| [events-and-errors.md](events-and-errors.md) | The event stream, error categories, troubleshooting |

Start with [../getting-started.md](../getting-started.md). Supported models and backends: [../compatibility.md](../compatibility.md).

## Reference

Generate the full API reference (Dokka) with:

```sh
./gradlew :dokkaGenerate
```

The HTML is written to `build/dokka/html/index.html` and covers `:sdk`, `:sdk-llama`, and `:sdk-remote`.

## Changing an example

1. Edit the function in `sdk/src/docsTest/kotlin/com/tune2ai/sdk/docs/*Examples.kt`.
2. Copy it into the doc page unchanged.
3. Run `./gradlew :sdk:testDebugUnitTest --tests "com.tune2ai.sdk.docs.*"`.

A block that needs a module the SDK tests do not depend on (for example `LlamaBackend`) is tagged `kotlin nocheck`.
