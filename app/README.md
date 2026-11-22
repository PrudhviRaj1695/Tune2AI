# Tune2AI Studio

The Android sample and developer console for the Tune2AI SDK. The UI is a web page (`src/main/assets/www/`) hosted in a WebView; `StudioBridge.java` connects it to the SDK. In a desktop browser there is no bridge, so the page falls back to simulated replies.

## Get a model onto the device

Studio runs GGUF models with llama.cpp. Small instruct models work well for testing, for example `SmolLM2-135M-Instruct-Q8_0.gguf` (145 MB) or `qwen2.5-0.5b-instruct-q4_k_m.gguf` (about 400 MB).

- **In the app:** tap the model name at the top → **Import a .gguf model**, then pick the file (for example from Downloads).
- **With adb (debug builds):** files in the app's `files/models/` folder are registered at startup.

  ```sh
  MSYS_NO_PATHCONV=1 adb push models/SmolLM2-135M-Instruct-Q8_0.gguf /data/local/tmp/model.gguf
  MSYS_NO_PATHCONV=1 adb shell "run-as com.tune2ai.studio mkdir -p files/models && cat /data/local/tmp/model.gguf | run-as com.tune2ai.studio sh -c 'cat > files/models/SmolLM2-135M-Instruct.gguf'"
  ```

  Restart the app afterwards (`MSYS_NO_PATHCONV=1` is only needed in Git Bash on Windows).

## Remote endpoints

Model & settings (tap the model name) → **Remote** takes any OpenAI-compatible API (base URL ending in `/v1`, API key, model name). The key stays in memory and is forgotten when the app closes. Release builds require HTTPS; debug builds also allow `http://10.0.2.2` (your development machine, as seen from the emulator) for local servers.

## Manual test script

| # | Do | Expect |
|---|---|---|
| 1 | Launch with no models | Empty state "Add a model to start." with an import button; no "Preview" badge |
| 2 | Import a .gguf model | Toast "Imported …"; the model appears in Model & settings as Ready and is selected |
| 3 | Import a non-GGUF file | Toast saying it is not a valid GGUF model; nothing registered |
| 4 | Ask "What is the capital of France?" | Reply streams; the line under it shows first-token time and tok/s; tapping it opens Run trace |
| 5 | Open Run trace | Real step times, router detail with the model name, context sources (prompt v3, conversation), memory from the device |
| 6 | Ask a follow-up | Context Manager lists the conversation messages; the reply uses them |
| 7 | Ask for a long story, tap Stop | Status "Cancelled" within about half a second; partial text stays; logcat `stop=cancelled` |
| 8 | Kill and relaunch the app, ask again | The model is still registered; the conversation history is still used |
| 9 | Tap New session (pencil icon) | Chat, trace, and stored history are cleared |
| 10 | Switch to Workflow | Replies are marked "Simulated" until the Workflow API ships. Finance is a Chat preset and runs through the Chat API |
| 11 | Turn on airplane mode and repeat step 4 | Works the same: inference is on-device |
| 12 | Toggle system dark mode | The app follows it |
| 14 | Agent tab with Qwen2.5-0.5B-Instruct (or larger): "Which models are installed, and is one loaded?" | A "Called list_models" card (expand for arguments and result), then a plain-text answer that matches the result |
| 13 | Model & settings → Remote: enter base URL, API key, model; Save | Hint shows the endpoint; a wrong key fails with "rejected the API key"; a right key streams with route `remote · <model>` |

Per-generation timing is logged: `adb logcat -s Tune2AI-llama:I` prints prompt tokens, tok/s, and why generation stopped.

## Agent tools

Studio registers three demo tools in `MainActivity`: `device_status`, `list_models`, and `current_time`. Agents need a model that follows tool-calling instructions; SmolLM2-135M usually does not, Qwen2.5-0.5B-Instruct or larger does. Keep tool results small and plainly labelled: small models misread raw fields.

## Debugging the page

Debug builds enable WebView debugging: open `chrome://inspect` (or `edge://inspect`) on your computer to get DevTools for the running page.
