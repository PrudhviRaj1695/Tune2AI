package com.tune2ai.studio;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.tune2ai.sdk.Tune2AI;
import com.tune2ai.sdk.api.AgentLimits;
import com.tune2ai.sdk.api.BackendInfo;
import com.tune2ai.sdk.api.Cancellable;
import com.tune2ai.sdk.api.ModelDescriptor;
import com.tune2ai.sdk.api.ModelInfo;
import com.tune2ai.sdk.api.ModelState;
import com.tune2ai.sdk.api.RequestId;
import com.tune2ai.sdk.api.RequestOptions;
import com.tune2ai.sdk.api.ResourceSnapshot;
import com.tune2ai.sdk.api.Task;
import com.tune2ai.sdk.api.Tune2AIEvent;
import com.tune2ai.sdk.core.workflow.StepType;
import com.tune2ai.sdk.core.workflow.Transition;
import com.tune2ai.sdk.core.workflow.WorkflowDefinition;
import com.tune2ai.sdk.core.workflow.WorkflowStep;
import com.tune2ai.sdk.llama.LlamaBackend;
import com.tune2ai.sdk.remote.RemoteBackend;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The page's only door into the SDK, exposed to JavaScript as {@code window.Tune2AIBridge}.
 * Events go back through {@code window.tune2ai.onEvent(...)}. MainActivity keeps the WebView on
 * the bundled page, so no other page can reach these methods.
 */
final class StudioBridge {
    static final int IMPORT_REQUEST = 41;
    static final int FINANCE_IMPORT_REQUEST = 42;
    private static final String SESSION = "studio";
    private static final int CONTEXT_LENGTH = 4096;
    private static final String REMOTE_MODEL_ID = "remote-model";

    private final Activity activity;
    private final WebView webView;
    private final Tune2AI ai;
    private final RemoteSettings remote;
    private final FinanceDataStore financeData;
    private final File modelsDir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Cancellable> running = new ConcurrentHashMap<>();
    private final Map<String, Boolean> financeRunning = new ConcurrentHashMap<>();
    private volatile boolean pageReady;
    private volatile boolean closed;
    private JSONObject pendingFinanceSummary;
    private static final Pattern CURRENCY_AMOUNT = Pattern.compile(
            "(?i)(INR|Rs\\.?|₹|USD|US\\$|\\$|EUR|€|GBP|£)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)|([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(INR|Rs\\.?|₹|USD|US\\$|\\$|EUR|€|GBP|£)");

    StudioBridge(Activity activity, WebView webView, Tune2AI ai, RemoteSettings remote, FinanceDataStore financeData) {
        this.activity = activity;
        this.webView = webView;
        this.ai = ai;
        this.remote = remote;
        this.financeData = financeData;
        this.modelsDir = new File(activity.getFilesDir(), "models");
        io.execute(this::registerModelsInStorage);
    }

    void onPageReady() {
        pageReady = true;
        synchronized (this) {
            if (pendingFinanceSummary != null) {
                JSONObject summary = pendingFinanceSummary;
                pendingFinanceSummary = null;
                publishFinanceSummary(summary);
            }
        }
    }

    /** Receives only text the user explicitly shared from another app; raw text is never persisted. */
    void acceptSharedMessages(String text) {
        if (text == null || text.length() > 100_000) {
            notice("The shared text is too large to analyze.");
            return;
        }
        io.execute(() -> {
            try {
                JSONObject summary = summarizeSharedMessages(text);
                publishFinanceSummary(summary);
                notice("Shared messages summarized locally. Raw message text was not retained.");
            } catch (IllegalArgumentException e) {
                notice("No clear transaction alerts found. Share messages that include a currency amount and debit or credit wording.");
            }
        });
    }

    private synchronized void publishFinanceSummary(JSONObject summary) {
        if (closed) return;
        cancelFinanceRequests();
        financeData.replace(summary.toString());
        if (!pageReady) {
            pendingFinanceSummary = summary;
            return;
        }
        callJs("window.tune2ai && window.tune2ai.onFinanceSummary(" + summary + ")");
    }

    /** Starts a chat request. Events are tagged with the page's own {@code id}. */
    @JavascriptInterface
    public void send(String requestJson) {
        try {
            JSONObject req = new JSONObject(requestJson);
            String id = req.getString("id");
            String system = req.optString("system", "").trim();
            RequestOptions options = new RequestOptions(
                    req.isNull("model") ? null : req.getString("model"),
                    req.isNull("backend") ? null : req.getString("backend"),
                    120_000L,
                    req.optInt("maxTokens", 512),
                    (float) req.optDouble("temperature", 0.7),
                    system.isEmpty() ? null : system);
            String prompt = req.getString("prompt");
            String mode = req.optString("mode");
            Cancellable handle;
            if ("Agent".equals(mode)) {
                String task = "You are Tune2AI Studio's device and model readiness advisor. "
                        + "Call runtime_readiness before answering. Explain which installed models appear safe to load, "
                        + "which should wait, and why. This is an estimate; never claim a model was loaded. "
                        + "Do not load or unload models. User request: " + prompt;
                handle = ai.getAgent().stream(task, Collections.singleton("runtime_readiness"),
                        new AgentLimits(), options, event -> deliver(id, event));
            } else if ("Finance".equals(mode)) {
                if (!financeData.hasSummary()) {
                    notice("Share transaction messages or import a statement CSV before asking a finance question.");
                    return;
                }
                String financePrompt = "You are Tune2AI Studio's read-only personal finance insights agent. "
                        + "Call finance_summary first. Explain only what the aggregated totals support, keep currencies "
                        + "separate, and state that currency conversion was not performed. Do not give investment, tax, "
                        + "or credit advice, and never invent transactions. User question: " + prompt;
                RequestOptions localOptions = new RequestOptions(null, LlamaBackend.ID, 120_000L,
                        req.optInt("maxTokens", 512), (float) req.optDouble("temperature", 0.3),
                        "Answer using the aggregated transaction summary returned by the finance_summary tool only.");
                handle = ai.getAgent().stream(financePrompt, Collections.singleton("finance_summary"),
                        new AgentLimits(), localOptions, event -> deliver(id, event));
            } else if ("Workflow".equals(mode)) {
                handle = ai.getWorkflow().stream(demoWorkflow(), Collections.singletonMap("input", prompt),
                        options, new RequestId(id), event -> deliver(id, event));
            } else {
                handle = ai.getChat().session(SESSION).stream(prompt, options, event -> deliver(id, event));
            }
            running.put(id, handle);
            if ("Finance".equals(mode)) financeRunning.put(id, Boolean.TRUE);
        } catch (JSONException e) {
            notice("Could not read the request: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void cancel(String id) {
        Cancellable handle = running.remove(id);
        if (handle != null) handle.cancel();
    }

    @JavascriptInterface
    public void newSession() {
        for (Cancellable handle : running.values()) handle.cancel();
        running.clear();
        financeRunning.clear();
        ai.getChat().session(SESSION).clearAsync();
        financeData.clear();
    }

    @JavascriptInterface
    public void clearFinance() {
        cancelFinanceRequests();
        financeData.clear();
    }

    private void cancelFinanceRequests() {
        for (String id : financeRunning.keySet()) {
            Cancellable handle = running.remove(id);
            financeRunning.remove(id);
            if (handle != null) handle.cancel();
        }
    }

    /**
     * The demo workflow for the Workflow tab: a {@code current_time} tool step whose output feeds a
     * model step that answers the user's message. Shows a real multi-step run — tool call, variable
     * passing, and a model answer — through {@code ai.workflow}, checkpointed to storage per step.
     */
    private WorkflowDefinition demoWorkflow() {
        WorkflowStep time = new WorkflowStep("time", StepType.TOOL, null, "current_time", "{}", "now",
                Collections.singletonList(new Transition("answer", null, null)));
        WorkflowStep answer = new WorkflowStep("answer", StepType.MODEL,
                "The current time is {now}.\n\nAnswer concisely: {input}", null, null, "answer",
                Collections.emptyList());
        return new WorkflowDefinition("studio-demo", 1,
                Collections.singletonList("input"), java.util.Arrays.asList(time, answer));
    }

    /**
     * Sets the remote endpoint. Returns an error message, or "" on success. The key stays in memory;
     * an empty key keeps the current one.
     */
    @JavascriptInterface
    public String setRemote(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String url = o.optString("baseUrl").trim();
            String model = o.optString("model").trim();
            String key = o.optString("apiKey").trim();
            if (url.isEmpty() || model.isEmpty()) return "Enter the API base URL and a model name.";
            String invalid = RemoteBackend.validateBaseUrl(url);
            if (invalid != null) return invalid;
            remote.baseUrl = url;
            remote.model = model;
            if (!key.isEmpty()) remote.apiKey = key;
            io.execute(() -> {
                ai.getModels().registerBlocking(new ModelDescriptor(REMOTE_MODEL_ID, model, RemoteBackend.FORMAT, model,
                        0, 8192, Collections.singleton(Task.CHAT), Collections.emptySet(), "1"));
                modelsChanged();
            });
            return "";
        } catch (JSONException e) {
            return "Could not read the remote settings.";
        }
    }

    /** Remote endpoint status. Never includes the API key itself. */
    @JavascriptInterface
    public String remoteStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("baseUrl", remote.baseUrl == null ? "" : remote.baseUrl);
            o.put("model", remote.model == null ? "" : remote.model);
            o.put("hasKey", remote.apiKey != null && !remote.apiKey.isEmpty());
            for (BackendInfo b : ai.backends()) {
                if (b.getId().equals("remote") && b.getUnavailableReason() != null) o.put("unavailable", b.getUnavailableReason());
            }
        } catch (JSONException ignored) {
            // values above are always valid
        }
        return o.toString();
    }

    /** Registered models and their state, as a JSON array. */
    @JavascriptInterface
    public String listModels() {
        JSONArray out = new JSONArray();
        for (ModelInfo info : ai.getModels().listBlocking()) {
            ModelDescriptor m = info.getDescriptor();
            ModelState state = info.getState();
            JSONObject o = new JSONObject();
            try {
                o.put("id", m.getId());
                o.put("name", m.getName());
                o.put("format", m.getFormat());
                o.put("sizeBytes", new File(m.getPath()).length());
                o.put("memoryBytes", m.getMemoryBytes());
                o.put("state", state == null ? "Unloaded" : state.getClass().getSimpleName());
                if (state instanceof ModelState.Unavailable) o.put("reason", ((ModelState.Unavailable) state).getReason());
                if (state instanceof ModelState.Failed) o.put("reason", ((ModelState.Failed) state).getReason());
                out.put(o);
            } catch (JSONException ignored) {
                // keys and values above are always valid
            }
        }
        return out.toString();
    }

    @JavascriptInterface
    public String resources() {
        ResourceSnapshot s = ai.resources();
        JSONObject o = new JSONObject();
        try {
            o.put("availableBytes", s.getAvailableMemoryBytes());
            o.put("totalBytes", s.getTotalMemoryBytes());
            o.put("lowMemory", s.getLowMemory());
            o.put("thermal", s.getThermal().name());
            o.put("batteryPercent", s.getBatteryPercent() == null ? JSONObject.NULL : s.getBatteryPercent());
        } catch (JSONException ignored) {
            // values above are always valid
        }
        return o.toString();
    }

    /** Opens the system file picker for a .gguf model. */
    @JavascriptInterface
    public void importModel() {
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            activity.startActivityForResult(intent, IMPORT_REQUEST);
        });
    }

    /** Opens a user-mediated document picker for a statement CSV; no SMS permission is requested. */
    @JavascriptInterface
    public void importFinanceCsv() {
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            activity.startActivityForResult(intent, FINANCE_IMPORT_REQUEST);
        });
    }

    private JSONObject summarizeSharedMessages(String text) {
        Map<String, CurrencyTotals> currencies = new LinkedHashMap<>();
        int accepted = 0;
        int skipped = 0;
        String[] lines = text.split("[\\r\\n]+");
        for (int i = 0; i < Math.min(lines.length, 500); i++) {
            String line = lines[i];
            Matcher matcher = CURRENCY_AMOUNT.matcher(line);
            if (!matcher.find()) continue;
            String currency = matcher.group(1) != null ? matcher.group(1) : matcher.group(4);
            String rawAmount = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (matcher.find()) { skipped++; continue; } // Skip balance-plus-transaction messages rather than guess.
            BigDecimal amount = decimal(rawAmount);
            if (amount == null || amount.signum() == 0) { skipped++; continue; }
            String normalized = line.toLowerCase(Locale.ROOT);
            boolean expense = containsAny(normalized, "debited", "debit", "spent", "purchase", "paid", "withdrawn", "charged", "payment of");
            boolean income = containsAny(normalized, "credited", "credit", "received", "deposited", "refund", "salary", "cashback");
            if (expense == income) { skipped++; continue; }
            String code = normalizeCurrency(currency);
            CurrencyTotals totals = currencies.computeIfAbsent(code, ignored -> new CurrencyTotals());
            BigDecimal absolute = amount.abs().setScale(2, RoundingMode.HALF_UP);
            if (expense) {
                totals.spending = totals.spending.add(absolute);
                String category = categorize(line);
                SpendBucket bucket = totals.categories.computeIfAbsent(category, ignored -> new SpendBucket());
                bucket.total = bucket.total.add(absolute);
                bucket.count++;
            } else totals.income = totals.income.add(absolute);
            accepted++;
        }
        if (accepted == 0) throw new IllegalArgumentException("no clear transactions");
        JSONObject out = new JSONObject();
        try {
            out.put("source", "User-shared messages");
            out.put("transactionCount", accepted);
            out.put("unclassifiedCount", skipped);
            out.put("currencyNote", "Currencies are kept separate; no conversion was performed.");
            JSONArray groups = new JSONArray();
            for (Map.Entry<String, CurrencyTotals> entry : currencies.entrySet()) {
                CurrencyTotals totals = entry.getValue();
                JSONObject group = new JSONObject();
                group.put("currency", entry.getKey());
                group.put("income", money(totals.income));
                group.put("spending", money(totals.spending));
                group.put("net", money(totals.income.subtract(totals.spending)));
                JSONArray categories = new JSONArray();
                for (Map.Entry<String, SpendBucket> category : totals.categories.entrySet()) {
                    JSONObject item = new JSONObject();
                    item.put("category", category.getKey());
                    item.put("spending", money(category.getValue().total));
                    item.put("transactions", category.getValue().count);
                    categories.put(item);
                }
                group.put("spendingByCategory", categories);
                groups.put(group);
            }
            out.put("currencyGroups", groups);
        } catch (JSONException ignored) {
            // Summary fields are fixed labels and numeric strings only.
        }
        return out;
    }

    private static final class CurrencyTotals {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal spending = BigDecimal.ZERO;
        Map<String, SpendBucket> categories = new LinkedHashMap<>();
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String normalizeCurrency(String symbol) {
        String s = symbol.toUpperCase(Locale.ROOT).replace(".", "");
        if (s.equals("₹")) return "INR";
        if (s.equals("US$")) return "USD";
        if (s.equals("$")) return "$";
        if (s.equals("RS")) return "Rs";
        if (s.equals("€")) return "EUR";
        if (s.equals("£")) return "GBP";
        return s;
    }

    void onFinanceImportResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        io.execute(() -> {
            if (!displayName(uri).toLowerCase(Locale.ROOT).endsWith(".csv")) {
                notice("Choose a bank statement saved as a .csv file.");
                return;
            }
            try {
                JSONObject summary = summarizeFinanceCsv(uri);
                summary.put("source", "Bank statement CSV");
                publishFinanceSummary(summary);
                notice("Imported a statement summary. Raw rows were not retained.");
            } catch (IOException | IllegalArgumentException | JSONException e) {
                notice("Could not read this CSV. Use UTF-8 with date, description, and signed amount columns, or separate debit and credit columns.");
            }
        });
    }

    /** Parses rows in memory, emits aggregates only, and never writes the selected statement to disk. */
    private JSONObject summarizeFinanceCsv(Uri uri) throws IOException {
        final int maxRows = 25_000;
        final int maxChars = 5_000_000;
        LinkedHashMap<String, SpendBucket> spending = new LinkedHashMap<>();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        int transactions = 0;
        int ambiguous = 0;
        int totalChars = 0;
        int dateColumn = -1, descriptionColumn = -1, amountColumn = -1, debitColumn = -1, creditColumn = -1, typeColumn = -1;
        boolean hasHeader = false;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("file unavailable");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalChars += line.length();
                    if (totalChars > maxChars) throw new IllegalArgumentException("file too large");
                    List<String> cells = parseCsvLine(line);
                    if (!hasHeader) {
                        hasHeader = true;
                        for (int i = 0; i < cells.size(); i++) {
                            String header = cells.get(i).replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
                            if (header.equals("date") || header.contains("transaction date") || header.contains("posting date")) dateColumn = i;
                            if (header.contains("description") || header.contains("narration") || header.contains("merchant") || header.contains("details")) descriptionColumn = i;
                            if (header.equals("amount") || header.contains("transaction amount")) amountColumn = i;
                            if (header.contains("debit") || header.contains("withdrawal") || header.contains("paid out")) debitColumn = i;
                            if (header.contains("credit") || header.contains("deposit") || header.contains("paid in")) creditColumn = i;
                            if (header.equals("type") || header.contains("transaction type") || header.equals("dr/cr")) typeColumn = i;
                        }
                        if (dateColumn < 0 || descriptionColumn < 0 || (amountColumn < 0 && debitColumn < 0 && creditColumn < 0)) {
                            throw new IllegalArgumentException("missing required columns");
                        }
                        continue;
                    }
                    if (line.trim().isEmpty()) continue;
                    if (++transactions > maxRows) throw new IllegalArgumentException("too many rows");
                    String description = cell(cells, descriptionColumn);
                    BigDecimal signedAmount = null;
                    if (debitColumn >= 0 || creditColumn >= 0) {
                        BigDecimal debit = decimal(cell(cells, debitColumn));
                        BigDecimal credit = decimal(cell(cells, creditColumn));
                        if (debit != null && debit.signum() != 0) signedAmount = debit.abs().negate();
                        else if (credit != null && credit.signum() != 0) signedAmount = credit.abs();
                    } else {
                        String raw = cell(cells, amountColumn);
                        signedAmount = decimal(raw);
                        if (signedAmount != null && signedAmount.signum() > 0 && typeColumn >= 0) {
                            String kind = cell(cells, typeColumn).toLowerCase(Locale.ROOT);
                            if (kind.contains("debit") || kind.contains("withdraw") || kind.contains("purchase") || kind.contains("payment")) signedAmount = signedAmount.negate();
                            else if (kind.contains("credit") || kind.contains("deposit") || kind.contains("refund") || kind.contains("salary")) { /* income */ }
                            else signedAmount = null;
                        } else if (signedAmount != null && signedAmount.signum() > 0) {
                            signedAmount = null; // An unsigned amount has no reliable income/expense direction.
                        }
                    }
                    if (signedAmount == null || signedAmount.signum() == 0) { ambiguous++; continue; }
                    BigDecimal amount = signedAmount.abs().setScale(2, RoundingMode.HALF_UP);
                    if (signedAmount.signum() > 0) income = income.add(amount);
                    else {
                        expenses = expenses.add(amount);
                        String category = categorize(description);
                        SpendBucket bucket = spending.computeIfAbsent(category, ignored -> new SpendBucket());
                        bucket.total = bucket.total.add(amount);
                        bucket.count++;
                    }
                }
            }
        }
        if (!hasHeader || transactions <= ambiguous) throw new IllegalArgumentException("no classifiable transaction rows");
        JSONObject out = new JSONObject();
        try {
            out.put("transactionCount", transactions - ambiguous);
            out.put("unclassifiedDirectionCount", ambiguous);
            out.put("income", income.setScale(2, RoundingMode.HALF_UP).toPlainString());
            out.put("spending", expenses.setScale(2, RoundingMode.HALF_UP).toPlainString());
            out.put("net", income.subtract(expenses).setScale(2, RoundingMode.HALF_UP).toPlainString());
            out.put("currencyNote", "Amounts are in the statement's currency; no conversion was performed.");
            JSONArray categories = new JSONArray();
            for (Map.Entry<String, SpendBucket> entry : spending.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("category", entry.getKey());
                item.put("spending", entry.getValue().total.setScale(2, RoundingMode.HALF_UP).toPlainString());
                item.put("transactions", entry.getValue().count);
                categories.put(item);
            }
            out.put("spendingByCategory", categories);
        } catch (JSONException ignored) {
            // Values are JSON primitives from the parser and fixed category names.
        }
        return out;
    }

    private static final class SpendBucket {
        BigDecimal total = BigDecimal.ZERO;
        int count;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else cell.append(c);
        }
        if (quoted) throw new IllegalArgumentException("unclosed quote");
        cells.add(cell.toString().trim());
        return cells;
    }

    private static String cell(List<String> cells, int index) {
        return index < 0 || index >= cells.size() ? "" : cells.get(index).trim();
    }

    private static BigDecimal decimal(String value) {
        if (value.isEmpty() || value.equals("-") || value.equalsIgnoreCase("n/a")) return null;
        boolean parenthesized = value.startsWith("(") && value.endsWith(")");
        String cleaned = value.replace(",", "").replace("$", "").replace("€", "").replace("£", "")
                .replace("₹", "").replace("(", "").replace(")", "").replace(" ", "");
        try {
            BigDecimal amount = new BigDecimal(cleaned);
            return parenthesized ? amount.abs().negate() : amount;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String categorize(String description) {
        String s = description.toLowerCase(Locale.ROOT);
        if (containsAny(s, "rent", "mortgage", "property", "housing")) return "Housing";
        if (containsAny(s, "grocery", "grocer", "supermarket", "market", "produce")) return "Groceries";
        if (containsAny(s, "restaurant", "cafe", "coffee", "food", "dining", "takeaway")) return "Dining";
        if (containsAny(s, "uber", "lyft", "transit", "metro", "fuel", "gas station", "parking")) return "Transport";
        if (containsAny(s, "electric", "water bill", "internet", "utility", "phone bill")) return "Utilities";
        if (containsAny(s, "pharmacy", "hospital", "clinic", "medical", "health")) return "Health";
        if (containsAny(s, "subscription", "streaming", "membership")) return "Subscriptions";
        if (containsAny(s, "fee", "service charge", "interest charge")) return "Fees";
        if (containsAny(s, "transfer", "payment to", "payment sent")) return "Transfers";
        return "Other";
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    void onImportResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        notice("Importing model…");
        io.execute(() -> importFrom(uri));
    }

    private void importFrom(Uri uri) {
        String name = displayName(uri);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            notice("Pick a .gguf model file. \"" + name + "\" is not one.");
            return;
        }
        File target = new File(modelsDir, name);
        File partial = new File(modelsDir, name + ".partial");
        modelsDir.mkdirs();
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(partial)) {
            if (in == null) throw new IOException("The file could not be opened");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        } catch (IOException e) {
            partial.delete();
            notice("Import failed: " + e.getMessage());
            return;
        }
        if (!isGguf(partial) || !partial.renameTo(target)) {
            partial.delete();
            notice("\"" + name + "\" is not a valid GGUF model.");
            return;
        }
        ai.getModels().registerBlocking(descriptorFor(target));
        notice("Imported " + name + ".");
        modelsChanged();
    }

    /** Registers .gguf files already in app storage (imported earlier, or copied in with adb run-as). */
    private void registerModelsInStorage() {
        File[] files = modelsDir.listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".gguf"));
        if (files == null) return;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (ModelInfo info : ai.getModels().listBlocking()) known.add(info.getDescriptor().getId());
        boolean added = false;
        for (File f : files) {
            ModelDescriptor d = descriptorFor(f);
            if (!known.contains(d.getId()) && isGguf(f)) {
                ai.getModels().registerBlocking(d);
                added = true;
            }
        }
        if (added) modelsChanged();
    }

    private ModelDescriptor descriptorFor(File file) {
        String base = file.getName().substring(0, file.getName().length() - ".gguf".length());
        return new ModelDescriptor(
                base.toLowerCase(Locale.ROOT),
                base.replace('-', ' ').replace('_', ' '),
                "gguf",
                file.getPath(),
                LlamaBackend.estimateMemoryBytes(file, CONTEXT_LENGTH),
                CONTEXT_LENGTH,
                Collections.singleton(Task.CHAT),
                Collections.emptySet(),
                "1");
    }

    private void deliver(String id, Tune2AIEvent event) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("requestId", event.getRequestId().getValue());
            if (event instanceof Tune2AIEvent.Progress) {
                Tune2AIEvent.Progress p = (Tune2AIEvent.Progress) event;
                o.put("type", "progress");
                o.put("stage", p.getStage().name());
                o.put("detail", p.getDetail());
                o.put("sources", new JSONArray(p.getSources()));
            } else if (event instanceof Tune2AIEvent.TextDelta) {
                o.put("type", "delta");
                o.put("text", ((Tune2AIEvent.TextDelta) event).getText());
            } else if (event instanceof Tune2AIEvent.Completed) {
                Tune2AIEvent.Completed c = (Tune2AIEvent.Completed) event;
                o.put("type", "completed");
                o.put("tokens", c.getUsage().getOutputTokens());
                o.put("firstTokenMillis", c.getUsage().getFirstTokenMillis() == null ? JSONObject.NULL : c.getUsage().getFirstTokenMillis());
                o.put("totalMillis", c.getUsage().getTotalMillis());
            } else if (event instanceof Tune2AIEvent.Cancelled) {
                o.put("type", "cancelled");
            } else if (event instanceof Tune2AIEvent.Failed) {
                Tune2AIEvent.Failed f = (Tune2AIEvent.Failed) event;
                o.put("type", "failed");
                o.put("category", f.getError().getCategory().name());
                o.put("message", f.getError().getMessage());
            } else if (event instanceof Tune2AIEvent.ToolCall) {
                Tune2AIEvent.ToolCall c = (Tune2AIEvent.ToolCall) event;
                o.put("type", "tool-call");
                o.put("callId", c.getCallId());
                o.put("name", c.getName());
                o.put("arguments", c.getArgumentsJson());
            } else if (event instanceof Tune2AIEvent.ToolResult) {
                Tune2AIEvent.ToolResult r = (Tune2AIEvent.ToolResult) event;
                o.put("type", "tool-result");
                o.put("callId", r.getCallId());
                o.put("name", r.getName());
                o.put("result", r.getResultJson());
                o.put("isError", r.isError());
            } else {
                return;
            }
        } catch (JSONException e) {
            return;
        }
        if (event instanceof Tune2AIEvent.Terminal) {
            running.remove(id);
            financeRunning.remove(id);
            modelsChanged(); // load state may have changed
        }
        callJs("window.tune2ai && window.tune2ai.onEvent(" + o + ")");
    }

    private void notice(String text) {
        callJs("window.tune2ai && window.tune2ai.onNotice(" + JSONObject.quote(text) + ")");
    }

    private void modelsChanged() {
        callJs("window.tune2ai && window.tune2ai.onModelsChanged()");
    }

    private void callJs(String script) {
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private String displayName(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall back to the URI's last segment
        }
        String last = uri.getLastPathSegment();
        return last == null ? "model.gguf" : new File(last).getName();
    }

    private static boolean isGguf(File file) {
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            return in.read(magic) == 4 && magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    void close() {
        closed = true;
        for (Cancellable handle : running.values()) handle.cancel();
        io.shutdownNow();
        financeData.clear();
    }
}
