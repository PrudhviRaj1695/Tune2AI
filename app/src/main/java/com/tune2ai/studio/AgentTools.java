package com.tune2ai.studio;

import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;

import com.tune2ai.sdk.api.KnowledgeApi;
import com.tune2ai.sdk.api.RetrievedChunk;
import com.tune2ai.sdk.api.Tool;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tools Studio offers the agent (decision D6: host-provided tools). Read tools look things up in
 * the knowledge index and connected phone data. Action tools never act on their own: they check
 * the arguments and return "ready", the page shows a button on the reply, and only the user's tap
 * opens the system app (Messages, Calendar, Clock, Phone, share sheet) with everything filled in,
 * where the user confirms. So the agent can prepare a text or an event, but only the user sends it.
 */
final class AgentTools {
    /** Tools whose effect happens outside Studio, after the user taps the button on the reply. */
    static final Set<String> ACTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "send_sms", "add_calendar_event", "set_alarm", "call", "share")));

    private volatile Connections connections;
    private volatile KnowledgeApi knowledge;

    /** Called once the SDK is built; until then the data tools report that nothing is connected. */
    void attach(Connections connections, KnowledgeApi knowledge) {
        this.connections = connections;
        this.knowledge = knowledge;
    }

    List<Tool> tools() {
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("search_sources",
                "Searches the user's documents, notes, and connected phone data for passages about a topic.",
                schema(prop("query", "string", "What to look for, in a few words"), required("query")),
                10_000, h(args -> searchSources(new JSONObject(args).getString("query")))));
        t.add(new Tool("read_messages",
                "Reads the newest SMS messages, optionally only those from or mentioning a person or word.",
                schema(prop("from", "string", "Optional name, number, or word to filter by"), prop("limit", "integer", "How many, default 8")),
                10_000, h(args -> readMessages(new JSONObject(args)))));
        t.add(new Tool("calendar_events",
                "Lists calendar events from today for the next number of days.",
                schema(prop("days", "integer", "How many days ahead, default 7")),
                10_000, h(args -> calendar(new JSONObject(args).optInt("days", 7)))));
        t.add(new Tool("find_contact",
                "Finds a contact's phone numbers and email addresses by name.",
                schema(prop("name", "string", "The person's name"), required("name")),
                10_000, h(args -> findContact(new JSONObject(args).getString("name")))));
        t.add(new Tool("recent_calls", "Lists the most recent phone calls.",
                schema(prop("limit", "integer", "How many, default 8")),
                10_000, h(args -> recentCalls(new JSONObject(args).optInt("limit", 8)))));
        t.add(new Tool("read_notifications",
                "Reads recent notifications from other apps such as WhatsApp or Gmail, optionally from one app.",
                schema(prop("app", "string", "Optional app name, for example WhatsApp"), prop("limit", "integer", "How many, default 8")),
                10_000, h(args -> notifications(new JSONObject(args)))));
        t.add(new Tool("save_note",
                "Saves a note to the user's Sources so it can be found later.",
                schema(prop("title", "string", "Short title"), prop("text", "string", "The note"), required("title", "text")),
                10_000, h(args -> saveNote(new JSONObject(args)))));
        t.add(new Tool("send_sms",
                "Prepares a text message for the user to review and send. Use a phone number or a contact name.",
                schema(prop("to", "string", "Phone number or contact name"), prop("text", "string", "Message text"), required("to", "text")),
                10_000, h(args -> ready(new JSONObject(args), "A Review in Messages button is shown; the user sends it."))));
        t.add(new Tool("add_calendar_event",
                "Prepares a calendar event for the user to confirm. Times are local, like 2026-11-27 07:40.",
                schema(prop("title", "string", "Event title"), prop("start", "string", "Start, YYYY-MM-DD HH:MM"),
                        prop("end", "string", "Optional end, YYYY-MM-DD HH:MM"), prop("location", "string", "Optional place"), required("title", "start")),
                10_000, h(args -> {
                    JSONObject a = new JSONObject(args);
                    parseWhen(a.getString("start")); // fails now, so the model can fix the format
                    if (a.has("end")) parseWhen(a.getString("end"));
                    return ready(a, "An Add to Calendar button is shown; the user confirms it.");
                })));
        t.add(new Tool("set_alarm", "Prepares an alarm for the user to confirm.",
                schema(prop("time", "string", "Time, HH:MM in 24-hour format"), prop("label", "string", "Optional label"), required("time")),
                10_000, h(args -> {
                    JSONObject a = new JSONObject(args);
                    parseTime(a.getString("time"));
                    return ready(a, "A Set alarm button is shown; the user confirms it.");
                })));
        t.add(new Tool("call", "Prepares a phone call for the user to start. Use a phone number or a contact name.",
                schema(prop("to", "string", "Phone number or contact name"), required("to")),
                10_000, h(args -> ready(new JSONObject(args), "A Call button is shown; the user starts the call."))));
        t.add(new Tool("share", "Prepares text to share through another app, such as email or WhatsApp.",
                schema(prop("text", "string", "Text to share"), prop("subject", "string", "Optional subject"), required("text")),
                10_000, h(args -> ready(new JSONObject(args), "A Share button is shown; the user picks the app."))));
        return t;
    }

    /** A tool body that may throw; failures reach the model as a readable error result. */
    private interface Body {
        String run(String args) throws Exception;
    }

    private static com.tune2ai.sdk.api.ToolHandler h(Body body) {
        return args -> {
            try {
                return body.run(args);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        };
    }

    // ---------- Read tools ----------

    private String searchSources(String query) throws JSONException {
        KnowledgeApi k = knowledge;
        JSONArray out = new JSONArray();
        if (k == null) return out.toString();
        for (RetrievedChunk c : k.searchBlocking(query, 4)) {
            out.put(new JSONObject().put("source", c.getSourceName()).put("text", c.getText().length() > 700 ? c.getText().substring(0, 700) + "…" : c.getText()));
        }
        if (out.length() == 0) return JSONObject.quote("Nothing in the user's sources matches \"" + query + "\".");
        return out.toString();
    }

    private String readMessages(JSONObject a) {
        Connections c = need("sms");
        String from = a.optString("from").trim().toLowerCase(Locale.ROOT);
        return lines(c.recentSms(clamp(a.optInt("limit", 8)), from.isEmpty() ? null : from), "No matching SMS messages.");
    }

    private String calendar(int days) {
        return lines(need("calendar").upcomingEvents(Math.max(1, Math.min(days, 60))), "No events in that period.");
    }

    private String findContact(String name) {
        return lines(need("contacts").findContact(name), "No contact matches \"" + name + "\".");
    }

    private String recentCalls(int limit) {
        return lines(need("calls").recentCalls(clamp(limit)), "No recent calls.");
    }

    private String notifications(JSONObject a) {
        String app = a.optString("app").trim();
        return lines(need("notifications").recentNotifications(clamp(a.optInt("limit", 8)), app.isEmpty() ? "" : app),
                "No recent notifications" + (app.isEmpty() ? "." : " from " + app + "."));
    }

    private String saveNote(JSONObject a) throws JSONException {
        KnowledgeApi k = knowledge;
        if (k == null) throw new IllegalStateException("Sources are not ready yet");
        java.util.Map<String, String> meta = new java.util.HashMap<>();
        meta.put("kind", "note");
        k.addBlocking("note:" + System.currentTimeMillis(), a.getString("title"), a.getString("text"), meta);
        return JSONObject.quote("Saved to Sources as \"" + a.getString("title") + "\".");
    }

    /** The connection, if the user turned it on; otherwise a clear error the model can relay. */
    private Connections need(String id) {
        Connections c = connections;
        if (c == null || !c.usable(id)) {
            Connections.Spec spec = Connections.spec(id);
            throw new IllegalStateException((spec == null ? id : spec.name) + " is not connected. The user can connect it in the Connections tab.");
        }
        return c;
    }

    // ---------- Actions: run only when the user taps the button on the reply ----------

    /** Builds the system intent for an action tool call. Throws on bad arguments. */
    Intent intentFor(String tool, JSONObject a) throws JSONException, ParseException {
        switch (tool) {
            case "send_sms":
                return new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(resolveNumber(a.getString("to")))))
                        .putExtra("sms_body", a.getString("text"));
            case "call":
                return new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(resolveNumber(a.getString("to")))));
            case "add_calendar_event": {
                long start = parseWhen(a.getString("start"));
                long end = a.has("end") ? parseWhen(a.getString("end")) : start + 60 * 60 * 1000L;
                Intent i = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, a.getString("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end);
                if (a.has("location")) i.putExtra(CalendarContract.Events.EVENT_LOCATION, a.getString("location"));
                return i;
            }
            case "set_alarm": {
                int[] hm = parseTime(a.getString("time"));
                return new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hm[0])
                        .putExtra(AlarmClock.EXTRA_MINUTES, hm[1])
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Tune2AI"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false); // the Clock app shows it for confirmation
            }
            case "share": {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.getString("text"));
                if (a.has("subject")) send.putExtra(Intent.EXTRA_SUBJECT, a.getString("subject"));
                return Intent.createChooser(send, "Share with");
            }
            default:
                throw new JSONException("Not an action: " + tool);
        }
    }

    /** A number as given, or the first phone number of the matching contact (if contacts are connected). */
    private String resolveNumber(String to) {
        String digits = to.replaceAll("[^0-9+]", "");
        if (digits.length() >= 3 && digits.length() >= to.replaceAll("\\s", "").length() - 2) return digits;
        Connections c = connections;
        if (c != null && c.usable("contacts")) {
            String number = c.firstPhone(to);
            if (number != null) return number;
        }
        return to; // the system app will ask
    }

    // ---------- Helpers ----------

    /** Local date-time "YYYY-MM-DD HH:MM" (a "T" separator is accepted too) to epoch millis. */
    static long parseWhen(String text) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        f.setLenient(false);
        return f.parse(text.trim().replace('T', ' ')).getTime();
    }

    /** "HH:MM" (24-hour) to {hour, minute}. */
    static int[] parseTime(String text) throws ParseException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s*$").matcher(text);
        if (!m.matches()) throw new ParseException("Use HH:MM, for example 07:30", 0);
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) throw new ParseException("Not a time of day: " + text, 0);
        return new int[] {h, min};
    }

    private static String ready(JSONObject args, String note) throws JSONException {
        return new JSONObject().put("status", "ready_for_user").put("note", note).toString();
    }

    private static String lines(List<String> lines, String empty) {
        return JSONObject.quote(lines.isEmpty() ? empty : String.join("\n", lines));
    }

    private static int clamp(int n) {
        return Math.max(1, Math.min(n, 20));
    }

    private static JSONObject prop(String name, String type, String description) {
        try {
            return new JSONObject().put("name", name).put("type", type).put("description", description);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static JSONObject required(String... names) {
        try {
            return new JSONObject().put("required", new JSONArray(Arrays.asList(names)));
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** {"type":"object","properties":{…},"required":[…]} from prop() and required() parts. */
    private static String schema(JSONObject... parts) {
        try {
            JSONObject props = new JSONObject();
            JSONArray req = new JSONArray();
            for (JSONObject p : parts) {
                if (p.has("required")) {
                    req = p.getJSONArray("required");
                } else {
                    props.put(p.getString("name"), new JSONObject().put("type", p.getString("type")).put("description", p.getString("description")));
                }
            }
            JSONObject s = new JSONObject().put("type", "object").put("properties", props);
            if (req.length() > 0) s.put("required", req);
            return s.toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
