# Tune2AI Studio

The Android sample and developer console for the Tune2AI SDK. The UI is a web page (`src/main/assets/www/`) hosted in a WebView; `StudioBridge.java` connects it to the SDK. In a desktop browser there is no bridge, so the page falls back to simulated replies.

## Get a model onto the device

Studio runs GGUF models with llama.cpp. Small instruct models work well for testing, for example `SmolLM2-135M-Instruct-Q8_0.gguf` (145 MB) or `qwen2.5-0.5b-instruct-q4_k_m.gguf` (about 400 MB).

- **In the app:** Run setup → **Import a .gguf model**, then pick the file (for example from Downloads).
- **With adb (debug builds):** files in the app's `files/models/` folder are registered at startup.

  ```sh
  MSYS_NO_PATHCONV=1 adb push models/SmolLM2-135M-Instruct-Q8_0.gguf /data/local/tmp/model.gguf
  MSYS_NO_PATHCONV=1 adb shell "run-as com.tune2ai.studio mkdir -p files/models && cat /data/local/tmp/model.gguf | run-as com.tune2ai.studio sh -c 'cat > files/models/SmolLM2-135M-Instruct.gguf'"
  ```

  Restart the app afterwards (`MSYS_NO_PATHCONV=1` is only needed in Git Bash on Windows).

## Remote endpoints

Run setup → **Remote** takes any OpenAI-compatible API (base URL ending in `/v1`, API key, model name). The key stays in memory and is forgotten when the app closes. Release builds require HTTPS; debug builds also allow `http://10.0.2.2` (your development machine, as seen from the emulator) for local servers.

## Manual test script

| # | Do | Expect |
|---|---|---|
| 1 | Launch with no models | Empty state "Add a model to start." with an import button; no "Simulated" badge |
| 2 | Import a .gguf model | Toast "Imported …"; the model appears in Run setup as Ready and is selected |
| 3 | Import a non-GGUF file | Toast saying it is not a valid GGUF model; nothing registered |
| 4 | Ask "What is the capital of France?" | Route strip lights up step by step; reply streams; stats chip shows first-token time, tok/s, llama.cpp |
| 5 | Open Run trace | Real step times, router detail with the model name, context sources (prompt v3, conversation), memory from the device |
| 6 | Ask a follow-up | Context Manager lists the conversation messages; the reply uses them |
| 7 | Ask for a long story, tap Stop | Status "Cancelled" within about half a second; partial text stays; logcat `stop=cancelled` |
| 8 | Kill and relaunch the app, ask again | The model is still registered; the conversation history is still used |
| 9 | Tap New session | Chat, trace, and stored history are cleared |
| 10 | Switch to Workflow and send a message | A "Called current_time" card (the demo workflow's tool step), then a model answer; no "Simulated" badge. The Memory and RAG switches in Run setup are still simulated |
| 15 | Edit the system prompt in Run setup, send a message | The trace shows "Prompt · custom"; Reset restores the default |
| 11 | Turn on airplane mode and repeat step 4 | Works the same: inference is on-device |
| 12 | Toggle system dark mode | The app follows it |
| 14 | Agent tab with Qwen2.5-0.5B-Instruct (or larger): "Is my device ready to load another model?" | A `runtime_readiness` call reports resource signals and per-model estimates; the answer explains the recommendation and does not load or unload a model |
| 16 | In the messaging app, select transaction alerts and Share → Tune2AI Studio; ask Finance "What does this tell me?" | Finance opens with the aggregate count; the `finance_summary` tool card contains totals and categories but no raw message text, and the request routes through llama.cpp even if Remote is selected |
| 17 | Share `INR 1,250.00 debited at Metro Groceries` to Studio | Finance reports one transaction, spending of INR 1,250.00 in Groceries, and no retained message text |
| 18 | In Finance, import a UTF-8 CSV with date, description, and signed amount columns | Finance reports aggregated income, spending, net, and category totals; original rows are not shown to the agent |
| 19 | Select Remote, ask a Finance question, then tap Forget and ask again | The trace reports llama.cpp; Forget removes the shared summary and the next question asks for data |
| 20 | Inspect the merged APK permissions | No SMS, contacts, or notification access permission is present |
| 13 | Run setup → Remote: enter base URL, API key, model; Save | Hint shows the endpoint; a wrong key fails with "rejected the API key"; a right key streams with route `remote · <model>` |

Per-generation timing is logged: `adb logcat -s Tune2AI-llama:I` prints prompt tokens, tok/s, and why generation stopped.

## Agent tools

Studio's Agent tab is a device and model readiness advisor. It calls `runtime_readiness`, which combines current free memory, battery and thermal signals with each installed model's memory estimate and SDK state. It only advises; it does not load or unload models. The report is a point-in-time estimate, so the device can change before a model is loaded.

Studio's Finance tab accepts transaction messages explicitly shared from another app, or a bank statement CSV. Studio does not read the inbox and requests no SMS permission. It processes shared text in memory, discards descriptions, and exposes only income/spending/category totals through the `finance_summary` tool. Finance runs use a one-shot agent request pinned to the on-device `llama.cpp` backend, even when Remote is selected; no finance summary is stored in chat history. **Forget** or **New session** clears the summary from app memory. CSV support expects UTF-8 with date and description columns plus a signed amount, or separate debit and credit columns. Amounts without a reliable direction are excluded and counted as unclassified.

Studio also registers `device_status`, `list_models`, and `current_time` as examples of app-provided tools. Agents need a model that follows tool-calling instructions; SmolLM2-135M usually does not, Qwen2.5-0.5B-Instruct or larger does. Keep tool results small and plainly labelled: small models misread raw fields.

## Debugging the page

Debug builds enable WebView debugging: open `chrome://inspect` (or `edge://inspect`) on your computer to get DevTools for the running page.
