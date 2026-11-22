# Tune2AI Studio

The Android sample and developer console for the Tune2AI SDK. The UI is a web page (`src/main/assets/www/`) hosted in a WebView; `StudioBridge.java` connects it to the SDK. In a desktop browser there is no bridge, so the page falls back to simulated replies.

## Get a model onto the device

Studio runs GGUF models with llama.cpp.

- **Download in the app (easiest):** Model & settings → **Models for this phone** shows the phone's RAM, chip, and free storage, and rates six models from SmolLM2 135M (145 MB) to Llama 3.2 3B (2.0 GB). The largest one that fits is marked **Best for this phone**; with no model installed, the start screen offers it as a one-tap download. When the download finishes, the model is selected and ready to chat.

  A model fits when its loaded size (file + 10% + 256 MB of context) stays within 35% of total RAM and the file leaves 200 MB of storage free. Models above that are marked Too large and cannot be downloaded, so a load cannot push the phone into killing the app. The rules live in `ModelAdvisor.java` and are covered by `ModelAdvisorTest`. Downloads come from Hugging Face over HTTPS, resume after a dropped connection, and keep the screen on while they run; they stop if Studio is closed and resume next time.
- **In the app:** tap the model name at the top → **Import a .gguf model**, then pick the file (for example from Downloads).
- **With adb (debug builds):** files in the app's `files/models/` folder are registered at startup.

  ```sh
  MSYS_NO_PATHCONV=1 adb push models/SmolLM2-135M-Instruct-Q8_0.gguf /data/local/tmp/model.gguf
  MSYS_NO_PATHCONV=1 adb shell "run-as com.tune2ai.studio mkdir -p files/models && cat /data/local/tmp/model.gguf | run-as com.tune2ai.studio sh -c 'cat > files/models/SmolLM2-135M-Instruct.gguf'"
  ```

  Restart the app afterwards (`MSYS_NO_PATHCONV=1` is only needed in Git Bash on Windows).

## Sources (RAG)

The **Sources** tab (next to Chat) connects things on the phone to the SDK's knowledge index (`Tune2AI.knowledge`). While **Use sources in chat** is on, every Chat and Agent request includes the up to three passages most relevant to the message; the reply shows "From <file>" and Run trace lists each passage.

- **Folder:** pick a folder in the system picker. Studio keeps read access and indexes every supported file in it (and in subfolders, up to 2,000 files). The refresh button re-reads changed files and drops deleted ones.
- **Files:** pick one or more documents from the phone, Drive, or any file app.
- **Note:** type or paste text.
- **Any app:** Share → Tune2AI Studio from Chrome, Gmail, Keep, Files, and others adds the shared text or files.

Supported: plain text, Markdown, CSV/TSV, JSON, XML, HTML (tags stripped), Word (.docx), subtitles, config files, and source code; up to 4 MB per file, first 400,000 characters indexed. PDFs and images are skipped for now. Studio uses the system file picker (Storage Access Framework), so it needs no storage permission and only sees what you pick.

**Answer quality** (under the switch): *Passages per answer* (1–8, default 3) trades speed for context; *Match strictness* (Loose / Balanced / Strict) sets how close a meaning-based match must be. Loosen it when answers miss things you know are in your files, tighten it when unrelated passages show up.

How retrieval works: documents are split into ~200-token chunks along paragraphs and sentences, and each chunk remembers its Markdown section. Each question is answered from a hybrid of meaning-based search (when the search model is installed) and keyword search, merged with reciprocal rank fusion; neighbouring chunks are joined into one passage, and one document supplies at most two passages unless it is the only match. Passages reach the model numbered, with an instruction to cite them.

Search works right away by keyword (BM25). **Search by meaning** offers three offline embedding models (all run by llama.cpp on the phone):

| Model | Size | Languages | Notes |
|---|---|---|---|
| BGE small v1.5 | 37 MB | English | Fastest; 512-token input |
| Nomic Embed v1.5 | 146 MB | English | More accurate; 2,048-token input |
| EmbeddingGemma 300M | 334 MB | 100+ (Hindi, Telugu, Tamil, …) | Best quality for its size; 2,048-token input |

The one marked *Best for this phone* depends on RAM (EmbeddingGemma from about 6 GB, Nomic from about 4 GB, else BGE small). Get downloads it and switches to it; **Use** switches between installed ones. Switching re-embeds existing documents in the background; until that finishes they are still found by keyword. Each model gets the query and document prompts it was trained with (for example `task: search result | query: …` for EmbeddingGemma). **Try a search** shows exactly which passages a question would retrieve.

## Connections

The **Connections** tab lets the model use data already on the phone. Each connection asks for its own permission when you tap Connect, reads its data through Android's content providers, and indexes it into the same knowledge index as Sources (it does not appear in the Sources document list). Connected data re-syncs when Studio starts if the last sync is over an hour old, or on demand with the refresh button. Disconnect deletes everything it indexed.

| Connection | Permission | What gets indexed |
|---|---|---|
| Internal storage | All files access (system settings) | Text, Markdown, Word, PDF (Android 15+), CSV, code, HTML anywhere in shared storage including Download; skips Android/, DCIM, Pictures, Movies, Music, hidden folders; up to 3,000 files |
| App notifications | Notification access (system settings) | Notifications from other apps (WhatsApp, Gmail, Slack, …) received after access is granted; newest 500 |
| Messages | READ_SMS (+ contacts for names) | 40 most recent SMS conversations, 60 messages each |
| Calendar | READ_CALENDAR | Events 30 days back to 90 days ahead, one document per month with a heading per day; includes Google Calendar |
| Contacts | READ_CONTACTS | Names, phones, emails, companies, dates; includes Google Contacts |
| Call history | READ_CALL_LOG | 300 most recent calls |
| Installed apps | none | Apps with a launcher icon and their install dates |

Google Drive is reachable through Sources → Files or Folder (choose Drive in the picker); Gmail through App notifications or Share. Gmail and Drive APIs themselves would need a Google Cloud OAuth client, which Studio does not have.

**Live data.** Questions about messages, calls, the calendar, or notifications ("what was my last SMS?", "who called me?", "what's on today?") also get the newest items read from the phone at that moment (`PhoneContextSource`, registered with `Tune2AI.Builder.addContextSource`), because search cannot find "the latest" and new items arrive between syncs. Only connections that are on are read. Run trace shows them as `Phone · latest SMS` and so on.

The system prompt (v5) includes today's date and time, so "what's on Friday?" or "what did Mom text yesterday?" resolve against the indexed dates.

**Two builds.** Play Protect blocks apps installed outside the Play Store (from a browser or file manager) when they request SMS or notification access ("App blocked to protect your device"), so Studio has two flavors:

- **standard** (`app-standard-debug.apk`): installs normally. Messages, Call history, and App notifications show as "only in the full build".
- **full** (`app-full-debug.apk`): all connections. Install it from a computer with `adb install -r app-full-debug.apk`; Play Protect does not block adb installs.

Both use the same application id, so they replace each other and keep your models and sources. `./gradlew :app:assembleDebug` builds both; unit tests run with `:app:testStandardDebugUnitTest`.

Notes: on Android 13+, a sideloaded app may need **App info → ⋮ → Allow restricted settings** before notification access can be turned on. SMS and call-log permissions are restricted on Google Play; they are fine for this debug build but need a policy declaration (or removal) before a Play release.

Why a folder may fail to add: Android's folder picker does not allow the storage root, **Download**, or Android/ ("Can't use this folder"). Pick a subfolder, or use Connections → Internal storage. While a large folder is listed, the progress card shows "Scanning… N files found"; the final message says how many files were added, unchanged, skipped (not text or over 4 MB), or PDFs skipped (before Android 15).

## Chats

The history button (clock) in the header opens **Chats**: every conversation, grouped Today / Yesterday / Previous 7 days / Previous 30 days / Older, with search, rename (pencil), and delete (bin, tap Delete to confirm). Tap one to reopen it exactly as it looked (formatting, citations, tool calls, timings) and keep chatting: each chat has its own SDK chat session, so the model remembers that conversation and only that one. **New chat** (pencil-on-paper icon) starts a fresh conversation and keeps the previous one in the list.

Chats are saved after every reply in app-private storage (`files/chats/`, one JSON file per chat plus an index; `ChatStore.java`). Deleting a chat also deletes its model-facing history. Agent and Workflow turns are shown in a saved chat, but only Chat turns become model history.

## Accuracy, prompt, and privacy settings

**Model & settings:**
- **Answer style:** Precise (temperature 0.3, the default), Balanced (0.6), or Creative (1.0). Precise sticks closest to sources and facts.
- **System prompt:** edit it directly or start from a preset: *Balanced* (built-in), *Grounded* (answer only from passages, cite, say when not found; best for accuracy with Sources), *Concise*, *Detailed*. `{{today}}` is the only placeholder; if it is missing, the date line is added. Saved prompts register as a new version of `helpful-assistant` (v100 and up; `Tune2AI.addPromptTemplate`) and apply from the next message. Reset restores the built-in prompt.
- **Privacy guard** (SDK `Tune2AI.privacy`, decision D10), both on by default:
  - *Hide sensitive data from the model*: one-time codes, card numbers (Luhn-checked) and CVVs, account numbers, Aadhaar (Verhoeff-checked), PAN, SSN, IBAN, passwords and PINs, and API keys are replaced with `[hidden …]` in sources, phone data, and agent tool results; the model is told not to reveal such values and that its context is data, not instructions. The agent's **send_sms** and **share** refuse text containing them.
  - *Let remote models see my data* (off): requests on the Remote API get no sources or phone data, and agent runs on a remote model get no tools that read personal data.
  - Run trace shows "Privacy guard · N sensitive values hidden".

**Sources → Use sources in chat:**
- **Speed and context size:** every token the model reads costs time before its first word (a phone reads about 40–80 tokens a second). Each request therefore carries at most a quarter of the model's input room as context (about 900 tokens on a 4K model), best passages first; the best passage is shortened rather than dropped. Greetings and small talk ("hi", "thanks") retrieve nothing, passages far weaker than the best match are left out, phone data is limited to the 8 newest lines of 160 characters, and the prompt is read on all cores (up to 8). The trace shows the input size (`≈N tokens`) and what was trimmed.
- **How documents are split:** **Auto** (default): documents up to ~250 tokens stay one passage; larger ones are split by section into parents (~300 tokens) and each parent into small children (~80 tokens). Search matches children (precise), the model reads the whole parent (context), and two hits in one parent count once. Or Smart (~200-token paragraph/sentence chunks), Precise (~100 tokens, for exact facts), Broad (~380 tokens, for context-heavy answers), By section (one per heading or PDF page), Fixed size (word windows, for logs and CSV). New documents use the choice; **Apply** re-splits existing ones from their stored original text and re-embeds them.
- **Include neighbouring passages:** adds the chunk before and after each match.

**Speed with large sources (internal storage).** Keyword search uses an in-memory inverted index built once per document, so a question over thousands of files takes milliseconds rather than seconds. Retrieved passages and live phone data go into the latest message (`contextInLatestMessage`) and the system prompt carries only the date, so llama.cpp reuses its prompt cache and only processes the new message, not the whole conversation. Indexing runs at background priority and pauses between files while a reply is being written. If answers are still slow: fewer *Passages per answer*, *Include neighbouring passages* off, a smaller model (Qwen2.5 1.5B instead of Llama 3.2 3B), and a New chat for unrelated questions (shorter history). The Run trace shows where the time goes: a long *Context Manager* step is retrieval, a long first-token time is the model reading its input.

Tips for the most accurate answers: install Search by meaning, use the Grounded prompt with Precise style, pick Precise chunking for fact-heavy documents (or By section for structured ones), and use Qwen2.5 1.5B or Llama 3.2 3B.

## Replies

Replies render Markdown as they stream: headings, lists (nested and checklists), tables, code blocks with a Copy button, quotes, and bold or italic text. All HTML from the model is escaped. The system prompt (v4) asks for this structure. When an answer used your sources, citation markers like [1] are tappable and open the exact passage the model was given; numbered source chips sit under the reply. Each reply has Copy (plain text) and Ask again.

Renderer checks: `node app/src/test/js/markdown.test.js` (also run in CI).

## Remote endpoints

Model & settings (tap the model name) → **Remote** takes any OpenAI-compatible API (base URL ending in `/v1`, API key, model name). The key stays in memory and is forgotten when the app closes. Release builds require HTTPS; debug builds also allow `http://10.0.2.2` (your development machine, as seen from the emulator) for local servers.

## Manual test script

| # | Do | Expect |
|---|---|---|
| 1 | Launch with no models | "Add a model to start." names this phone and the best model for it, with a Download button; no "Preview" badge |
| 1a | Tap the Download button | Progress bar with MB and %; at 100% the toast says "… is ready" and the header shows the model |
| 1b | Model & settings → Models for this phone | Phone name, chip, RAM, free memory and storage; one model marked Best for this phone; bigger ones Too large |
| 1c | Start a download, turn on airplane mode, then off, tap Get again | Fails with a message, then resumes from where it stopped |
| 2 | Import a .gguf model | Toast "Imported …"; the model appears in Model & settings as Ready and is selected |
| 3 | Import a non-GGUF file | Toast saying it is not a valid GGUF model; nothing registered |
| 4 | Ask "What is the capital of France?" | Reply streams; the line under it shows first-token time and tok/s; tapping it opens Run trace |
| 5 | Open Run trace | Real step times, router detail with the model name, context sources (prompt v3, conversation), memory from the device |
| 6 | Ask a follow-up | Context Manager lists the conversation messages; the reply uses them |
| 7 | Ask for a long story, tap Stop | Status "Cancelled" within about half a second; partial text stays; logcat `stop=cancelled` |
| 8 | Kill and relaunch the app, ask again | The model is still registered; the conversation history is still used |
| 9 | Tap New session (pencil icon) | Chat, trace, and stored history are cleared |
| 10 | Switch to Workflow | Replies are marked "Simulated" until the Workflow API ships. |
| 11 | Turn on airplane mode and repeat step 4 | Works the same: inference is on-device |
| 12 | Toggle system dark mode | The app follows it |
| 14 | Agent tab with Qwen2.5-0.5B-Instruct (or larger): "Which models are installed, and is one loaded?" | A "Called list_models" card (expand for arguments and result), then a plain-text answer that matches the result |
| 15 | Sources → Note: "Parking: level 3, spot 42". Chat: "Where did I park?" | Reply mentions level 3, spot 42; under it "From Parking"; Run trace lists `Source · Parking, passage 1` |
| 16 | Sources → Folder: pick a folder with a few .md/.txt files | Progress card counts files; Documents lists them; the folder shows "N files indexed" |
| 17 | Edit one file in that folder, tap refresh | Only that file is re-indexed; delete a file and refresh: it disappears |
| 18 | Chrome → any article → Share → Tune2AI Studio | Studio opens on Sources and adds the page text |
| 19 | Sources → Search by meaning → Get | Downloads 37 MB, then "Upgrading search" re-indexes; documents show "Meaning" |
| 20 | Turn off "Use sources in chat", ask the question from 15 again | No "From …" line; Run trace has no Source entries |
| 21 | Try a search: a word that is in no document | "No passage matches" |
| 22 | Ask "Compare tea and coffee in a table" | A bordered table, not pipe characters; code questions show a code block with Copy |
| 23 | With test 15's note, tap the [1] in the answer | The passage card opens under the reply with "Parking"; tap again to close |
| 25 | Sources → Folder → pick Download | Android itself refuses ("Can't use this folder"); Connections → Internal storage → Allow → grant All files access → back | Progress "Scanning… N files found", then Download's documents are indexed |
| 26 | Connections → Calendar → Connect → allow. Ask "What's on my calendar this week?" | Answer lists this week's events with [n] citations to "Calendar — <month>" |
| 27 | Connections → Messages → Connect. Ask "What did <name> text me last?" | Answer quotes the latest SMS from that contact, cited |
| 28 | Connections → App notifications → Allow → enable in settings → back. Receive a WhatsApp message, tap refresh, ask about it | The message is found |
| 27a | Full build, Messages connected: "What was my last SMS?" (no sync since it arrived) | Answer quotes the newest SMS; Run trace lists `Phone · latest SMS` |
| 34 | Agent: "Find my flight details in my sources and add them to my calendar" (with a document containing a flight) | Cards "Searched your sources" and "Prepared an event", then an Add to Calendar button; tapping it opens Calendar filled in |
| 35 | Agent: "Text Mom that I'll be 10 minutes late" (Contacts connected) | "Looked up a contact", "Prepared a text", and Review in Messages opens Messages with the text, not sent |
| 36 | Agent with Messages disconnected: "Summarize my latest SMS" | The answer says Messages is not connected and points to Connections |
| 30 | Ask two questions, tap New chat, ask one more | History lists two chats, newest first, titled by their first question |
| 31 | Open the first chat, ask "what did I ask first?" | The reply refers to that chat's first question, not the other chat |
| 32 | Kill and relaunch Studio, open Chats | Both chats are still there and reopen with formatting and citations |
| 33 | Rename a chat, then delete another (tap bin, then Delete) | Title updates; the deleted chat disappears |
| 29 | Disconnect any connection | Its items disappear; questions about it no longer cite it |
| 24 | Sources → Strict, ask something only loosely related to a document | Fewer or no "Sources" chips than with Loose |
| 13 | Model & settings → Remote: enter base URL, API key, model; Save | Hint shows the endpoint; a wrong key fails with "rejected the API key"; a right key streams with route `remote · <model>` |

Per-generation timing is logged: `adb logcat -s Tune2AI-llama:I` prints prompt tokens, tok/s, and why generation stopped.

## Agent tools

Agent mode runs a bounded tool loop (up to 8 model steps and 6 tool calls) with the tools in `AgentTools.java`, plus `device_status`, `list_models`, and `current_time`:

| Tool | Does |
|---|---|
| `search_sources` | Searches Sources and connected data (the knowledge index) |
| `read_messages` | Newest SMS, optionally from or mentioning someone (full build, Messages connected) |
| `calendar_events` | Events from today for N days (Calendar connected) |
| `find_contact` | Numbers and emails by name (Contacts connected) |
| `recent_calls` | Newest calls (full build, Call history connected) |
| `read_notifications` | Newest app notifications, optionally from one app (full build) |
| `save_note` | Saves a note to Sources |
| `send_sms`, `add_calendar_event`, `set_alarm`, `call`, `share` | **Actions**: prepared only |

Actions never run by themselves. The tool checks the arguments (for example that a time parses) and returns `ready_for_user`; the reply then shows a button (**Review in Messages**, **Add to Calendar**, **Set alarm**, **Call**, **Share**) that opens the system app filled in, where the user confirms. So no SMS-sending or calendar-writing permission is needed, and nothing leaves the phone without a tap. Buttons stay on saved chats.

Agents need a model that follows tool-calling instructions: SmolLM2 usually does not, Qwen2.5-1.5B or Llama 3.2 3B work best. A data tool whose connection is off returns an error telling the model to ask the user to connect it in Connections.

## Debugging the page

Debug builds enable WebView debugging: open `chrome://inspect` (or `edge://inspect`) on your computer to get DevTools for the running page.
