package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.ClaudeSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class HistoryMessageInjectorTest {

    @Test
    public void createBackendStateMessageForHistoryBuildsUserMessageFromEventMsg() {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "user_message");
        payload.addProperty("message", "Recover this prompt");

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "event_msg");
        msg.add("payload", payload);

        ClaudeSession.Message backendMessage = HistoryMessageInjector.createBackendStateMessageForHistory(msg);

        assertNotNull(backendMessage);
        assertEquals(ClaudeSession.Message.Type.USER, backendMessage.type);
        assertEquals("Recover this prompt", backendMessage.content);
        assertEquals(
                "Recover this prompt",
                backendMessage.raw
                        .getAsJsonObject("message")
                        .getAsJsonArray("content")
                        .get(0)
                        .getAsJsonObject()
                        .get("text")
                        .getAsString()
        );
    }

    @Test
    public void createBackendStateMessageForHistoryPreservesToolResultResponseItems() {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "function_call_output");
        payload.addProperty("id", "item-1");
        payload.addProperty("call_id", "tool-1");
        payload.add("content", new JsonArray());

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "response_item");
        msg.add("payload", payload);

        ClaudeSession.Message backendMessage = HistoryMessageInjector.createBackendStateMessageForHistory(msg);

        assertNotNull(backendMessage);
        assertEquals(ClaudeSession.Message.Type.USER, backendMessage.type);
        assertEquals(
                "tool-1",
                backendMessage.raw
                        .getAsJsonObject("message")
                        .getAsJsonArray("content")
                        .get(0)
                        .getAsJsonObject()
                        .get("tool_use_id")
                        .getAsString()
        );
    }
}
