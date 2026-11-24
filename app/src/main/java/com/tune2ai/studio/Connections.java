package com.tune2ai.studio;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.CalendarContract;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;

import com.tune2ai.sdk.api.KnowledgeApi;
import com.tune2ai.sdk.api.KnowledgeSource;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Connections tab: data on the phone the model can use as context. Each connection reads its data
 * through the system's own content providers (so Google Calendar and Google Contacts come along with
 * the device calendar and contacts), turns it into readable documents, and keeps them in the SDK's
 * knowledge index under its own id. Chat then retrieves the relevant parts like any other source.
 *
 * Everything stays on the phone. Each connection needs the user's permission and can be removed,
 * which deletes what it indexed. Call every method that reads data from a background thread.
 *
 * ponytail: Studio-only for now; move the readers into an SDK platform module once apps other than
 * Studio need them.
 */
final class Connections {
    /** One connection: what it reads, and the permission that unlocks it. */
    static final class Spec {
        final String id;
        final String name;
        final String description;
        final String icon;
        final String[] permissions;   // runtime permissions; empty when special access or none is needed
        final String group;

        Spec(String id, String name, String description, String icon, String group, String... permissions) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.icon = icon;
            this.group = group;
            this.permissions = permissions;
        }
    }

    static final List<Spec> ALL = Collections.unmodifiableList(java.util.Arrays.asList(
            new Spec("storage", "Internal storage", "Documents, notes, PDFs, and other text files anywhere in the phone's storage, including Download.", "i-folder", "phone"),
            new Spec("notifications", "App notifications", "New messages from WhatsApp, Gmail, Slack, Telegram, and any other app, as they arrive.", "i-bell", "apps"),
            new Spec("sms", "Messages", "Your SMS conversations, newest first.", "i-message", "phone", Manifest.permission.READ_SMS),
            new Spec("calendar", "Calendar", "Events from 30 days ago to 90 days ahead, including Google Calendar.", "i-calendar", "google", Manifest.permission.READ_CALENDAR),
            new Spec("contacts", "Contacts", "Names, numbers, emails, and companies, including Google Contacts.", "i-person", "google", Manifest.permission.READ_CONTACTS),
            new Spec("calls", "Call history", "Recent calls with who, when, and how long.", "i-phone", "phone", Manifest.permission.READ_CALL_LOG),
            new Spec("apps", "Installed apps", "Which apps are on this phone and when they were installed.", "i-grid", "phone")));

    static Spec spec(String id) {
        for (Spec s : ALL) if (s.id.equals(id)) return s;
        return null;
    }

    /** A document a connection produces. */
    static final class Doc {
        final String id;
        final String name;
        final String text;

        Doc(String id, String name, String text) {
            this.id = id;
            this.name = name;
            this.text = text;
        }
    }

    private static final long AUTO_SYNC_AFTER = TimeUnit.HOURS.toMillis(1);
    private final Context context;
    private final KnowledgeApi knowledge;
    private final KnowledgeConnector files;
    private final SharedPreferences prefs;

    Connections(Context context, KnowledgeApi knowledge, KnowledgeConnector files) {
        this.context = context.getApplicationContext();
        this.knowledge = knowledge;
        this.files = files;
        this.prefs = this.context.getSharedPreferences("connections", Context.MODE_PRIVATE);
    }

    // ---------- Access ----------

    /**
     * Whether this build can offer [spec] at all. The "standard" flavor leaves out SMS, call history,
     * and the notification listener, because Play Protect blocks sideloaded apps that request them.
     */
    boolean available(Spec spec) {
        PackageManager pm = context.getPackageManager();
        if (spec.id.equals("notifications")) {
            try {
                pm.getServiceInfo(new ComponentName(context, StudioNotificationListener.class), 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        if (spec.permissions.length == 0) return true;
        try {
            String[] declared = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            java.util.List<String> list = declared == null ? Collections.emptyList() : java.util.Arrays.asList(declared);
            for (String p : spec.permissions) if (!list.contains(p)) return false;
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    boolean granted(Spec spec) {
        switch (spec.id) {
            case "storage":
                return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
                        : context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            case "notifications":
                String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
                return enabled != null && enabled.contains(new ComponentName(context, StudioNotificationListener.class).flattenToString());
            default:
                for (String p : spec.permissions) {
                    if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
                }
                return true;
        }
    }

    /** Runtime permissions to request for [spec], or null when access is granted in system settings instead. */
    String[] runtimePermissions(Spec spec) {
        if (spec.id.equals("storage")) return Build.VERSION.SDK_INT >= 30 ? null : new String[] {Manifest.permission.READ_EXTERNAL_STORAGE};
        if (spec.id.equals("notifications")) return null;
        if (spec.id.equals("sms")) return new String[] {Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS}; // contacts: names, optional
        return spec.permissions;
    }

    /** The system settings screen that grants special access for [spec]. */
    Intent settingsIntent(Spec spec) {
        if (spec.id.equals("storage") && Build.VERSION.SDK_INT >= 30) {
            return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.getPackageName()));
        }
        if (spec.id.equals("notifications")) {
            if (Build.VERSION.SDK_INT >= 30) {
                return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(context, StudioNotificationListener.class).flattenToString());
            }
            return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName()));
    }

    boolean enabled(String id) {
        return prefs.getBoolean("on:" + id, false);
    }

    void setEnabled(String id, boolean on) {
        prefs.edit().putBoolean("on:" + id, on).apply();
    }

    long lastSync(String id) {
        return prefs.getLong("sync:" + id, 0);
    }

    /** Connections that are on, still permitted, and not synced within the last hour. */
    List<Spec> dueForSync() {
        List<Spec> due = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Spec s : ALL) {
            if (available(s) && enabled(s.id) && granted(s) && now - lastSync(s.id) > AUTO_SYNC_AFTER) due.add(s);
        }
        return due;
    }

    /** Indexed documents per connection id. */
    Map<String, Integer> counts() {
        Map<String, Integer> counts = new HashMap<>();
        for (KnowledgeSource s : knowledge.sourcesBlocking()) {
            String c = s.getMetadata().get("connector");
            if (c != null) counts.merge(c, 1, Integer::sum);
        }
        return counts;
    }

    // ---------- Sync ----------

    /** Re-reads one connection and replaces what it indexed before. Returns documents indexed. */
    KnowledgeConnector.Summary sync(Spec spec, KnowledgeConnector.Progress progress) {
        KnowledgeConnector.Summary summary;
        if (spec.id.equals("storage")) {
            summary = files.syncStorage(progress);
        } else {
            summary = new KnowledgeConnector.Summary();
            progress.update(0, 0, "Reading " + spec.name.toLowerCase(Locale.ROOT) + "…");
            List<Doc> docs = read(spec);
            Set<String> keep = new HashSet<>();
            int done = 0;
            for (Doc d : docs) {
                progress.update(done++, docs.size(), d.name);
                Map<String, String> meta = new HashMap<>();
                meta.put("connector", spec.id);
                meta.put("kind", "connector");
                knowledge.addBlocking(d.id, d.name, d.text, meta);
                keep.add(d.id);
                summary.added++;
            }
            for (KnowledgeSource s : knowledge.sourcesBlocking()) {
                if (spec.id.equals(s.getMetadata().get("connector")) && !keep.contains(s.getId())) knowledge.removeBlocking(s.getId());
            }
            progress.update(docs.size(), docs.size(), "");
        }
        prefs.edit().putLong("sync:" + spec.id, System.currentTimeMillis()).apply();
        return summary;
    }

    /** Turns a connection off and deletes everything it indexed. */
    void disconnect(Spec spec) {
        setEnabled(spec.id, false);
        prefs.edit().remove("sync:" + spec.id).apply();
        if (spec.id.equals("storage")) files.clearStorage();
        if (spec.id.equals("notifications")) StudioNotificationListener.clear(context);
        for (KnowledgeSource s : knowledge.sourcesBlocking()) {
            if (spec.id.equals(s.getMetadata().get("connector"))) knowledge.removeBlocking(s.getId());
        }
    }

    private List<Doc> read(Spec spec) {
        switch (spec.id) {
            case "sms": return readSms();
            case "calendar": return readCalendar();
            case "contacts": return readContacts();
            case "calls": return readCalls();
            case "apps": return readApps();
            case "notifications": return readNotifications();
            default: return Collections.emptyList();
        }
    }

    /** On, permitted, and part of this build. */
    boolean usable(String id) {
        Spec spec = spec(id);
        return spec != null && available(spec) && enabled(id) && granted(spec);
    }

    // ---------- Live reads for PhoneContextSource: the newest items, read at question time ----------

    /** Newest SMS across all conversations, newest first. */
    List<String> recentSms(int limit, String mentioning) {
        String[] cols = {Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE};
        List<String> out = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, cols, null, null, Telephony.Sms.DATE + " DESC")) {
            while (c != null && c.moveToNext() && out.size() < limit) {
                String address = nullToEmpty(c.getString(0));
                String who = names.computeIfAbsent(address, this::contactName);
                String body = nullToEmpty(c.getString(1)).replace('\n', ' ').trim();
                if (mentioning != null && !(who + " " + body).toLowerCase(Locale.ROOT).contains(mentioning)) continue;
                boolean sent = c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT;
                out.add("- " + fmt(WHEN, c.getLong(2)) + " · " + (sent ? "Me → " + who : who) + ": " + truncate(body, 160));
            }
        }
        return out;
    }

    /**
     * Newest SMS that mention any of {@code terms} (in the sender's name or number, or the text), best match first:
     * messages naming more of the terms come first, newest first among equals. Looks through the newest
     * {@link #SMS_SCAN} messages, so "my HDFC OTP" finds the HDFC message even when it is not among the latest few.
     */
    List<String> smsMatching(int limit, List<String> terms) {
        String[] cols = {Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE};
        List<String> lines = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        int scanned = 0;
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, cols, null, null, Telephony.Sms.DATE + " DESC")) {
            while (c != null && c.moveToNext() && scanned++ < SMS_SCAN && lines.size() < limit * 4) {
                String address = nullToEmpty(c.getString(0));
                String who = names.computeIfAbsent(address, this::contactName);
                String body = nullToEmpty(c.getString(1)).replace('\n', ' ').trim();
                String hay = (who + " " + address + " " + body).toLowerCase(Locale.ROOT);
                int score = 0;
                for (String t : terms) if (hay.contains(t)) score++;
                if (score == 0) continue;
                boolean sent = c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT;
                lines.add("- " + fmt(WHEN, c.getLong(2)) + " · " + (sent ? "Me → " + who : who) + ": " + truncate(body, 160));
                scores.add(score);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) order.add(i);
        order.sort((x, y) -> Integer.compare(scores.get(y), scores.get(x))); // stable: newest first among equals
        List<String> out = new ArrayList<>();
        for (int i = 0; i < order.size() && out.size() < limit; i++) out.add(lines.get(order.get(i)));
        return out;
    }

    /** How many of the newest SMS a question-time search reads. */
    private static final int SMS_SCAN = 1500;

    List<String> recentCalls(int limit) {
        List<String> out = new ArrayList<>();
        for (Doc d : readCalls()) {
            for (String line : d.text.split("\n")) if (line.startsWith("- ") && out.size() < limit) out.add(line);
        }
        return out;
    }

    /** Events from the start of today through [days] days ahead, in order. */
    List<String> upcomingEvents(int days) {
        java.util.Calendar start = java.util.Calendar.getInstance();
        start.set(java.util.Calendar.HOUR_OF_DAY, 0);
        start.set(java.util.Calendar.MINUTE, 0);
        start.set(java.util.Calendar.SECOND, 0);
        long from = start.getTimeInMillis();
        long to = from + TimeUnit.DAYS.toMillis(days);
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(uri, from);
        android.content.ContentUris.appendId(uri, to);
        String[] cols = {CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY};
        List<String> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(uri.build(), cols, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext() && out.size() < 40) {
                long begin = c.getLong(0);
                String when = c.getInt(4) == 1 ? fmt(DAY, begin) + ", all day" : fmt(WHEN, begin) + "–" + fmt(TIME, c.getLong(1));
                String where = c.getString(3);
                out.add("- " + when + ": " + nullToEmpty(c.getString(2)) + (where == null || where.trim().isEmpty() ? "" : " · at " + where.trim()));
            }
        }
        return out;
    }

    /** Contacts whose name contains [name]: one line each with phones, emails, and company. */
    List<String> findContact(String name) {
        List<String> out = new ArrayList<>();
        String needle = name.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return out;
        for (Doc d : readContacts()) {
            for (String line : d.text.split("\n")) {
                if (line.startsWith("- ") && line.toLowerCase(Locale.ROOT).contains(needle) && out.size() < 10) out.add(line);
            }
        }
        return out;
    }

    /** First phone number of the first contact whose name contains [name], or null. */
    String firstPhone(String name) {
        Uri uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name.trim()));
        try (Cursor c = context.getContentResolver().query(uri, new String[] {ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // no match or provider unavailable
        }
        return null;
    }

    /** Newest notifications, optionally only from apps whose name appears in the question. */
    List<String> recentNotifications(int limit, String question) {
        List<JSONObject> all = StudioNotificationListener.read(context);
        Set<String> apps = new HashSet<>();
        for (JSONObject n : all) apps.add(n.optString("app"));
        String q = question.toLowerCase(Locale.ROOT);
        Set<String> named = new HashSet<>();
        for (String app : apps) if (!app.isEmpty() && q.contains(app.toLowerCase(Locale.ROOT))) named.add(app);
        List<String> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
            JSONObject n = all.get(i);
            if (!named.isEmpty() && !named.contains(n.optString("app"))) continue;
            String title = n.optString("title");
            out.add("- " + fmt(WHEN, n.optLong("time")) + " · " + n.optString("app") + " · " + (title.isEmpty() ? "" : title + ": ")
                    + truncate(n.optString("text").replace('\n', ' '), 160));
        }
        return out;
    }

    // ---------- Readers: each returns plain, dated, self-explaining text ----------

    private static final SimpleDateFormat WHEN = new SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault());
    private static final SimpleDateFormat DAY = new SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault());
    private static final SimpleDateFormat MONTH = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private static synchronized String fmt(SimpleDateFormat f, long millis) {
        return f.format(new Date(millis));
    }

    /** Most recent 40 conversations, last 60 messages each, one document per conversation. */
    private List<Doc> readSms() {
        Map<Long, List<String>> threads = new LinkedHashMap<>();
        Map<Long, String> who = new HashMap<>();
        String[] cols = {Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE};
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, cols, null, null, Telephony.Sms.DATE + " DESC")) {
            while (c != null && c.moveToNext()) {
                long thread = c.getLong(0);
                if (!threads.containsKey(thread) && threads.size() >= 40) continue;
                List<String> lines = threads.computeIfAbsent(thread, k -> new ArrayList<>());
                if (lines.size() >= 60) continue;
                String address = c.getString(1);
                if (!who.containsKey(thread)) who.put(thread, contactName(address));
                boolean sent = c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_SENT;
                lines.add(fmt(WHEN, c.getLong(3)) + " · " + (sent ? "Me" : who.get(thread)) + ": " + nullToEmpty(c.getString(2)).trim());
            }
        }
        List<Doc> docs = new ArrayList<>();
        for (Map.Entry<Long, List<String>> e : threads.entrySet()) {
            List<String> lines = e.getValue();
            Collections.reverse(lines); // oldest first reads naturally
            String name = "SMS with " + who.get(e.getKey());
            docs.add(new Doc("conn:sms:" + e.getKey(), name, "# SMS text messages with " + who.get(e.getKey()) + "\n\n" + String.join("\n", lines)));
        }
        return docs;
    }

    /** One document per month, with a heading per day, so "what's on Friday" finds the right day. */
    private List<Doc> readCalendar() {
        long now = System.currentTimeMillis();
        long from = now - TimeUnit.DAYS.toMillis(30);
        long to = now + TimeUnit.DAYS.toMillis(90);
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(uri, from);
        android.content.ContentUris.appendId(uri, to);
        String[] cols = {
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.DESCRIPTION,
        };
        Map<String, Map<String, List<String>>> months = new LinkedHashMap<>();
        try (Cursor c = context.getContentResolver().query(uri.build(), cols, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext()) {
                long begin = c.getLong(0);
                long end = c.getLong(1);
                boolean allDay = c.getInt(4) == 1;
                StringBuilder line = new StringBuilder("- ");
                line.append(allDay ? "All day" : fmt(TIME, begin) + "–" + fmt(TIME, end)).append(": ").append(nullToEmpty(c.getString(2)));
                String where = c.getString(3);
                if (where != null && !where.trim().isEmpty()) line.append(" · at ").append(where.trim());
                String cal = c.getString(5);
                if (cal != null) line.append(" · ").append(cal).append(" calendar");
                String about = c.getString(6);
                if (about != null && !about.trim().isEmpty()) line.append(" · ").append(truncate(about.trim().replace('\n', ' '), 200));
                months.computeIfAbsent(fmt(MONTH, begin), k -> new LinkedHashMap<>())
                        .computeIfAbsent(fmt(DAY, begin), k -> new ArrayList<>()).add(line.toString());
            }
        }
        List<Doc> docs = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> m : months.entrySet()) {
            StringBuilder text = new StringBuilder("# Calendar — ").append(m.getKey()).append("\n\n");
            for (Map.Entry<String, List<String>> d : m.getValue().entrySet()) {
                text.append("## ").append(d.getKey()).append("\n\n").append(String.join("\n", d.getValue())).append("\n\n");
            }
            docs.add(new Doc("conn:calendar:" + m.getKey(), "Calendar — " + m.getKey(), text.toString()));
        }
        return docs;
    }

    /** One document listing every contact on its own line. */
    private List<Doc> readContacts() {
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, List<String>> details = new HashMap<>();
        String[] cols = {
                ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA4,
        };
        String where = ContactsContract.Data.MIMETYPE + " IN (?,?,?,?)";
        String[] args = {
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        };
        try (Cursor c = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI, cols, where, args,
                ContactsContract.Data.DISPLAY_NAME + " ASC")) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                names.putIfAbsent(id, nullToEmpty(c.getString(1)));
                String mime = c.getString(2);
                String value = nullToEmpty(c.getString(3)).trim();
                if (value.isEmpty()) continue;
                String label = mime.equals(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) ? "phone "
                        : mime.equals(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) ? "email "
                        : mime.equals(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE) ? "birthday/date "
                        : "works at ";
                String title = mime.equals(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE) ? nullToEmpty(c.getString(4)).trim() : "";
                List<String> list = details.computeIfAbsent(id, k -> new ArrayList<>());
                String item = label + value + (title.isEmpty() ? "" : " (" + title + ")");
                if (!list.contains(item)) list.add(item);
            }
        }
        if (names.isEmpty()) return Collections.emptyList();
        StringBuilder text = new StringBuilder("# Contacts\n\n");
        for (Map.Entry<Long, String> e : names.entrySet()) {
            List<String> d = details.getOrDefault(e.getKey(), Collections.emptyList());
            text.append("- ").append(e.getValue()).append(d.isEmpty() ? "" : ": " + String.join("; ", d)).append('\n');
        }
        return Collections.singletonList(new Doc("conn:contacts", "Contacts", text.toString()));
    }

    private List<Doc> readCalls() {
        String[] cols = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION};
        List<String> lines = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols, null, null, CallLog.Calls.DATE + " DESC")) {
            while (c != null && c.moveToNext() && lines.size() < 300) {
                String name = c.getString(1);
                String number = nullToEmpty(c.getString(0));
                String who = name != null && !name.isEmpty() ? name + " (" + number + ")" : number;
                int type = c.getInt(2);
                String kind = type == CallLog.Calls.OUTGOING_TYPE ? "Outgoing call to " : type == CallLog.Calls.MISSED_TYPE ? "Missed call from "
                        : type == CallLog.Calls.REJECTED_TYPE ? "Declined call from " : "Incoming call from ";
                long seconds = c.getLong(4);
                lines.add("- " + fmt(WHEN, c.getLong(3)) + " · " + kind + who + (seconds > 0 ? " · " + (seconds / 60) + " min " + (seconds % 60) + " s" : ""));
            }
        }
        if (lines.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(new Doc("conn:calls", "Call history", "# Call history (newest first)\n\n" + String.join("\n", lines)));
    }

    /** Apps with a launcher icon (visible through the manifest's queries element; no QUERY_ALL_PACKAGES). */
    private List<Doc> readApps() {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String> lines = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
            String pkg = r.activityInfo.packageName;
            String installed = "";
            try {
                installed = " · installed " + fmt(DAY, pm.getPackageInfo(pkg, 0).firstInstallTime);
            } catch (PackageManager.NameNotFoundException ignored) {
                // uninstalled while listing
            }
            lines.add("- " + r.loadLabel(pm) + " (" + pkg + ")" + installed);
        }
        Collections.sort(lines, String.CASE_INSENSITIVE_ORDER);
        if (lines.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(new Doc("conn:apps", "Installed apps", "# Installed apps\n\n" + String.join("\n", lines)));
    }

    /** One document per app, newest notifications last. */
    private List<Doc> readNotifications() {
        Map<String, List<String>> byApp = new LinkedHashMap<>();
        for (JSONObject n : StudioNotificationListener.read(context)) {
            String app = n.optString("app", n.optString("package"));
            String title = n.optString("title");
            byApp.computeIfAbsent(app, k -> new ArrayList<>()).add("- " + fmt(WHEN, n.optLong("time")) + " · "
                    + (title.isEmpty() ? "" : title + ": ") + n.optString("text"));
        }
        List<Doc> docs = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byApp.entrySet()) {
            String name = "Notifications from " + e.getKey();
            docs.add(new Doc("conn:notifications:" + e.getKey(), name, "# " + name + "\n\n" + String.join("\n", e.getValue())));
        }
        return docs;
    }

    private String contactName(String address) {
        if (address == null) return "Unknown";
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return address;
        Uri lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        try (Cursor c = context.getContentResolver().query(lookup, new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0) + " (" + address + ")";
        } catch (RuntimeException ignored) {
            // short codes and alphanumeric senders cannot be looked up
        }
        return address;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
