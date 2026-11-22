package com.tune2ai.studio;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the most recent notifications from other apps (WhatsApp, Gmail, Slack, …) in a small file in
 * app-private storage, so the "App notifications" connection can make them searchable. Android only
 * delivers notifications here after the user grants notification access in system settings.
 * Nothing leaves the phone. ponytail: a JSON-lines file trimmed to {@link #KEEP} entries; move to the
 * SDK's Storage if other connectors need shared retention rules.
 */
public final class StudioNotificationListener extends NotificationListenerService {
    static final int KEEP = 500;
    private static final String FILE = "notifications.jsonl";
    private static final Object LOCK = new Object();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName()) || sbn.isOngoing()) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return; // the individual messages arrive separately
        Bundle extras = n.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence text = big != null ? big : extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text == null || text.toString().trim().isEmpty()) return;
        try {
            JSONObject o = new JSONObject()
                    .put("app", appLabel(this, sbn.getPackageName()))
                    .put("package", sbn.getPackageName())
                    .put("title", title == null ? "" : title.toString())
                    .put("text", text.toString())
                    .put("time", sbn.getPostTime());
            append(this, o.toString());
        } catch (JSONException | IOException ignored) {
            // a lost notification is not worth crashing the listener for
        }
    }

    static String appLabel(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static void append(Context context, String line) throws IOException {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((line.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (file.length() > 512 * 1024) { // trim to the newest KEEP entries
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                List<String> keep = lines.subList(Math.max(0, lines.size() - KEEP), lines.size());
                Files.write(file.toPath(), keep, StandardCharsets.UTF_8);
            }
        }
    }

    /** Stored notifications, oldest first. */
    static List<JSONObject> read(Context context) {
        List<JSONObject> out = new ArrayList<>();
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE);
            if (!file.isFile()) return out;
            try {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    try {
                        out.add(new JSONObject(line));
                    } catch (JSONException ignored) {
                        // skip a damaged line
                    }
                }
            } catch (IOException ignored) {
                // unreadable file: nothing to index
            }
        }
        return out.subList(Math.max(0, out.size() - KEEP), out.size());
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            new File(context.getFilesDir(), FILE).delete();
        }
    }
}
