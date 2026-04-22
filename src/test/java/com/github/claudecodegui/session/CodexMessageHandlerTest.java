package com.github.claudecodegui.session;

import com.github.claudecodegui.session.ClaudeSession.Message;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CodexMessageHandlerTest {

    @Test
    public void skipsReplayAssistantMessageByBridgeItemIdButKeepsFreshMessageWithSameText() {
        SessionState state = new SessionState();
        state.addMessage(new Message(Message.Type.ASSISTANT, "Repeated answer", createAssistantTextRaw("Repeated answer", "agent-1")));
        state.addMessage(new Message(Message.Type.USER, "Current prompt"));

        CodexMessageHandler handler = new CodexMessageHandler(null, state, new CallbackHandler());

        handler.onMessage("assistant", createAssistantTextRaw("Repeated answer", "agent-1").toString());
        handler.onMessage("assistant", createAssistantTextRaw("Repeated answer", "agent-2").toString());

        assertEquals(3, state.getMessages().size());
        assertEquals("Repeated answer", state.getMessages().get(2).content);
    }

    @Test
    public void deduplicatesToolResultsByToolUseIdInsteadOfPlaceholderContent() {
        SessionState state = new SessionState();
        state.addMessage(new Message(Message.Type.USER, "[tool_result]", createToolResultRaw("tool-1", "first output")));
        state.addMessage(new Message(Message.Type.USER, "Current prompt"));

        CodexMessageHandler handler = new CodexMessageHandler(null, state, new CallbackHandler());

        handler.onMessage("user", createToolResultRaw("tool-1", "replayed output").toString());
        handler.onMessage("user", createToolResultRaw("tool-2", "fresh output").toString());

        assertEquals(3, state.getMessages().size());
        assertEquals("[tool_result]", state.getMessages().get(2).content);
        assertEquals(
                "tool-2",
                state.getMessages().get(2).raw
                        .getAsJsonObject("message")
                        .getAsJsonArray("content")
                        .get(0)
                        .getAsJsonObject()
                        .get("tool_use_id")
                        .getAsString()
        );
    }

    @Test
    public void keepsAssistantMessageWhenSameTextArrivesWithoutStableReplayId() {
        SessionState state = new SessionState();
        state.addMessage(new Message(Message.Type.ASSISTANT, "Repeated answer", createAssistantTextRaw("Repeated answer", null)));
        state.addMessage(new Message(Message.Type.USER, "Current prompt"));

        CodexMessageHandler handler = new CodexMessageHandler(null, state, new CallbackHandler());

        handler.onMessage("assistant", createAssistantTextRaw("Repeated answer", null).toString());

        assertEquals(3, state.getMessages().size());
        assertEquals("Repeated answer", state.getMessages().get(2).content);
    }

    private static JsonObject createAssistantTextRaw(String text, String bridgeItemId) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "assistant");
        if (bridgeItemId != null) {
            root.addProperty("bridge_item_id", bridgeItemId);
        }

        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");

        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        content.add(block);

        message.add("content", content);
        root.add("message", message);
        return root;
    }

    private static JsonObject createToolResultRaw(String toolUseId, String contentText) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "user");

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");

        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("tool_use_id", toolUseId);
        block.addProperty("content", contentText);
        content.add(block);

        message.add("content", content);
        root.add("message", message);
        return root;
    }
}
