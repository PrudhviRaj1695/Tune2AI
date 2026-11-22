package com.tune2ai.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.tune2ai.sdk.api.Tool;
import com.tune2ai.sdk.core.tools.ToolCallingEngine;

import org.json.JSONObject;
import org.junit.Test;

import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

public class AgentToolsTest {
    private final Map<String, Tool> tools = new HashMap<>();

    public AgentToolsTest() {
        for (Tool t : new AgentTools().tools()) tools.put(t.getName(), t);
    }

    @Test
    public void everyToolPassesTheSdkSchemaChecks() {
        new ToolCallingEngine(new java.util.ArrayList<>(tools.values()), kotlinx.coroutines.Dispatchers.getIO()); // throws on a bad name or schema
        assertTrue(tools.keySet().containsAll(AgentTools.ACTIONS));
        assertTrue(tools.containsKey("search_sources"));
        assertTrue(tools.containsKey("read_messages"));
    }

    @Test
    public void actionsOnlyReportReadyAndNeverAct() throws Exception {
        String result = tools.get("send_sms").getHandler().call("{\"to\":\"Mom\",\"text\":\"Running late\"}");
        assertEquals("ready_for_user", new JSONObject(result).getString("status"));
    }

    @Test
    public void privacyGuardBlocksSendingCodesOut() throws Exception {
        AgentTools guarded = new AgentTools();
        guarded.attach(null, null, new com.tune2ai.sdk.api.PrivacyApi(new com.tune2ai.sdk.services.guard.PrivacyGuard()));
        Tool sms = null;
        for (Tool t : guarded.tools()) if (t.getName().equals("send_sms")) sms = t;
        try {
            sms.getHandler().call("{\"to\":\"+15550100\",\"text\":\"Your OTP is 482913\"}");
            fail("expected the guard to block it");
        } catch (SecurityException expected) {
            assertTrue(expected.getMessage().contains("one-time code"));
        }
        assertEquals("ready_for_user", new JSONObject(sms.getHandler().call("{\"to\":\"Mom\",\"text\":\"Running late\"}")).getString("status"));
    }

    @Test
    public void badTimesAreRejectedSoTheModelCanFixThem() {
        try {
            tools.get("set_alarm").getHandler().call("{\"time\":\"25:00\"}");
            fail("expected an error");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("time"));
        }
        try {
            tools.get("add_calendar_event").getHandler().call("{\"title\":\"Flight\",\"start\":\"next friday\"}");
            fail("expected an error");
        } catch (IllegalArgumentException expected) {
            // unparseable date
        }
    }

    @Test
    public void dataToolsExplainWhenNothingIsConnected() {
        try {
            tools.get("read_messages").getHandler().call("{}");
            fail("expected an error");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Connections"));
        }
    }

    @Test
    public void parsesLocalTimes() throws ParseException {
        assertEquals(AgentTools.parseWhen("2026-11-27 07:40"), AgentTools.parseWhen("2026-11-27T07:40"));
        int[] hm = AgentTools.parseTime("7:05");
        assertEquals(7, hm[0]);
        assertEquals(5, hm[1]);
    }
}
