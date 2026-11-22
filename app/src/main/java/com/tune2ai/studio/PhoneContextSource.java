package com.tune2ai.studio;

import com.tune2ai.sdk.api.ModelDescriptor;
import com.tune2ai.sdk.services.context.BlockingContextSource;
import com.tune2ai.sdk.services.context.ContextItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Live phone data for questions about "my latest messages", "who called", or "what's on today".
 * Search over indexed documents cannot answer those (nothing in the question matches a specific
 * message, and new ones arrive after the last sync), so when a question is about messages, calls,
 * the calendar, or notifications, this reads the newest items from the phone at that moment and
 * gives them to the model through the Context Manager. Only connections the user turned on are read.
 *
 * <p>ponytail: keyword intent detection. Replace with a small classifier or agent tools if
 * questions slip through.
 */
final class PhoneContextSource extends BlockingContextSource {
    private static final Pattern SMS = Pattern.compile("\\b(sms|texts?|texted|messages?|messaged|msgs?|otp|inbox)\\b");
    private static final Pattern CALLS = Pattern.compile("\\b(calls?|called|calling|missed|dialed|dialled|rang)\\b");
    private static final Pattern CALENDAR = Pattern.compile(
            "\\b(calendar|meetings?|events?|schedule|appointments?|agenda|today|tonight|tomorrow|week|weekend|free|busy|plans?)\\b");
    private static final Pattern NOTIFICATIONS = Pattern.compile(
            "\\b(notifications?|whatsapp|telegram|slack|gmail|e-?mails?|mails?|signal|instagram|messenger|teams|chats?)\\b");
    private static final int LIMIT = 8;

    private volatile Connections connections;

    /** Set once the SDK is built; until then this source contributes nothing. */
    void attach(Connections connections) {
        this.connections = connections;
    }

    @Override
    public String getName() {
        return "phone";
    }

    @Override
    public List<ContextItem> fetchBlocking(String query, ModelDescriptor model) {
        Connections c = connections;
        List<ContextItem> items = new ArrayList<>();
        if (c == null) return items;
        String q = query.toLowerCase(Locale.ROOT);
        boolean messages = SMS.matcher(q).find();
        if (messages && c.usable("sms")) add(items, "latest SMS", "Latest SMS messages (newest first)", c.recentSms(LIMIT, null));
        if (CALLS.matcher(q).find() && c.usable("calls")) add(items, "latest calls", "Latest calls (newest first)", c.recentCalls(LIMIT));
        if (CALENDAR.matcher(q).find() && c.usable("calendar")) add(items, "upcoming events", "Calendar from today through the next 7 days", c.upcomingEvents(7));
        if ((messages || NOTIFICATIONS.matcher(q).find()) && c.usable("notifications")) {
            add(items, "latest notifications", "Latest app notifications (newest first)", c.recentNotifications(LIMIT, query));
        }
        return items;
    }

    private static void add(List<ContextItem> items, String ref, String title, List<String> lines) {
        items.add(new ContextItem(ref, title + ":\n" + (lines.isEmpty() ? "(none)" : String.join("\n", lines))));
    }

    @Override
    public String render(List<ContextItem> items) {
        String now = new java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(new java.util.Date());
        StringBuilder sb = new StringBuilder("Live data read from the user's phone just now (" + now + "). Use it to answer questions about "
                + "their messages, calls, calendar, or notifications; if what they ask about is not in it, say so.\n\n");
        for (ContextItem item : items) sb.append(item.getText()).append("\n\n");
        return sb.toString().trim();
    }
}
