package com.tune2ai.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public class ChatStoreTest {
    private File dir;
    private ChatStore store;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("chats").toFile();
        store = new ChatStore(dir);
    }

    private static String chat(String id, String title, long updatedAt, String... texts) throws Exception {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < texts.length; i++) {
            messages.put(new JSONObject().put("role", i % 2 == 0 ? "user" : "assistant").put("text", texts[i]));
        }
        return new JSONObject().put("id", id).put("title", title).put("updatedAt", updatedAt).put("messages", messages).toString();
    }

    @Test
    public void savedChatsListNewestFirstWithPreview() throws Exception {
        store.save(chat("c1", "Trip", 100, "When is my flight?", "Friday at **07:40** from Terminal 2."));
        store.save(chat("c2", "Budget", 200, "Make a budget"));
        JSONArray list = new JSONArray(store.list());
        assertEquals("c2", list.getJSONObject(0).getString("id"));
        JSONObject trip = list.getJSONObject(1);
        assertEquals("Trip", trip.getString("title"));
        assertEquals(2, trip.getInt("count"));
        assertEquals("Friday at 07:40 from Terminal 2.", trip.getString("preview"));
        assertEquals("When is my flight?", new JSONObject(store.load("c1")).getJSONArray("messages").getJSONObject(0).getString("text"));
    }

    @Test
    public void savingAgainUpdatesTheSameChat() throws Exception {
        store.save(chat("c1", "Trip", 100, "Q1", "A1"));
        store.save(chat("c1", "Trip", 300, "Q1", "A1", "Q2", "A2"));
        JSONArray list = new JSONArray(store.list());
        assertEquals(1, list.length());
        assertEquals(4, list.getJSONObject(0).getInt("count"));
        assertEquals(300, list.getJSONObject(0).getLong("updatedAt"));
    }

    @Test
    public void renameAndDelete() throws Exception {
        store.save(chat("c1", "Trip", 100, "Q1", "A1"));
        store.rename("c1", "Lisbon trip");
        assertEquals("Lisbon trip", new JSONArray(store.list()).getJSONObject(0).getString("title"));
        assertTrue(new JSONObject(store.load("c1")).getBoolean("renamed"));
        assertTrue(store.delete("c1"));
        assertEquals(0, new JSONArray(store.list()).length());
        assertEquals("null", store.load("c1"));
        assertFalse(store.delete("c1"));
    }

    @Test
    public void emptyChatsAreNotSaved() throws Exception {
        store.save(chat("c1", "", 100));
        assertEquals(0, new JSONArray(store.list()).length());
    }

    @Test
    public void idsCannotEscapeTheFolder() throws Exception {
        assertFalse(ChatStore.validId("../secrets"));
        assertFalse(ChatStore.validId("A/B"));
        assertEquals("null", store.load("../index"));
        try {
            store.save(chat("../x", "t", 1, "q"));
            throw new AssertionError("expected a refusal");
        } catch (org.json.JSONException expected) {
            // refused
        }
        assertFalse(new File(dir.getParentFile(), "x.json").exists());
    }
}
