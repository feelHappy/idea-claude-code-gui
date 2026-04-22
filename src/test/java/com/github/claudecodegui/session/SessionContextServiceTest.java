package com.github.claudecodegui.session;

import com.github.claudecodegui.session.ClaudeSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionContextServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void buildUserMessageIncludesImageBlockAndTextContent() {
        SessionContextService service = new SessionContextService(null, 1024);
        List<ClaudeSession.Attachment> attachments = List.of(
                new ClaudeSession.Attachment("diagram.png", "image/png", "base64-data")
        );

        ClaudeSession.Message message = service.buildUserMessage("Please inspect this diagram", attachments);

        assertEquals(ClaudeSession.Message.Type.USER, message.type);
        assertEquals("Please inspect this diagram", message.content);
        JsonArray content = message.raw.getAsJsonObject("message").getAsJsonArray("content");
        assertEquals(2, content.size());
        assertEquals("image", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("text", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("Please inspect this diagram",
                content.get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void buildCodexContextAppendIncludesReferencedFilesAndSelectionContext() throws Exception {
        File referencedFile = temporaryFolder.newFile("ReferencedExample.java");
        Files.writeString(referencedFile.toPath(), "class ReferencedExample {}", StandardCharsets.UTF_8);

        File activeFile = temporaryFolder.newFile("ActiveExample.java");
        Files.writeString(activeFile.toPath(), "class ActiveExample {}", StandardCharsets.UTF_8);

        JsonObject openedFilesJson = new JsonObject();
        openedFilesJson.addProperty("active", activeFile.getAbsolutePath());

        JsonObject selection = new JsonObject();
        selection.addProperty("startLine", 3);
        selection.addProperty("endLine", 5);
        selection.addProperty("selectedText", "logger.info(\"hello\");");
        openedFilesJson.add("selection", selection);

        SessionContextService service = new SessionContextService(null, 1024);

        String context = service.buildCodexContextAppend(
                openedFilesJson,
                List.of(referencedFile.getAbsolutePath(), "terminal://backend-shell")
        );

        assertTrue(context.contains("## Active Terminal Session"));
        assertTrue(context.contains("`backend-shell`"));
        assertTrue(context.contains("## Referenced Files"));
        assertTrue(context.contains(referencedFile.getAbsolutePath()));
        assertTrue(context.contains("class ReferencedExample {}"));
        assertTrue(context.contains("## IDE Context"));
        assertTrue(context.contains(activeFile.getAbsolutePath() + "#L3-5"));
        assertTrue(context.contains("logger.info(\"hello\");"));
    }

    @Test
    public void buildCodexReplayPrefixUsesPriorConversationButSkipsCurrentPromptAndToolResults() {
        SessionContextService service = new SessionContextService(null, 1024);
        ClaudeSession.Message earlierUser = new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "Original request");
        ClaudeSession.Message earlierAssistant = new ClaudeSession.Message(ClaudeSession.Message.Type.ASSISTANT, "Original answer");
        ClaudeSession.Message toolResult = new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "[tool_result]");
        toolResult.raw = createToolResultRaw("tool-1", "Applied");
        ClaudeSession.Message livePrompt = new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "Edited request");

        String replayPrefix = service.buildCodexReplayPrefix(
                List.of(earlierUser, earlierAssistant, toolResult, livePrompt),
                "Edited request"
        );

        assertTrue(replayPrefix.contains("## Preserved Conversation"));
        assertTrue(replayPrefix.contains("User:\nOriginal request"));
        assertTrue(replayPrefix.contains("Assistant:\nOriginal answer"));
        assertFalse(replayPrefix.contains("[tool_result]"));
        assertFalse(replayPrefix.contains("Edited request"));
    }

    @Test
    public void buildCodexReplayPrefixAndAttachmentsPreserveHistoricalImagesButSkipLivePromptImages() {
        SessionContextService service = new SessionContextService(null, 1024);
        ClaudeSession.Message earlierUser = createUserMessageWithImage("Inspect the diagram", "image/png", "older-image-data");
        ClaudeSession.Message livePrompt = createUserMessageWithImage("Current prompt", "image/jpeg", "live-image-data");

        String replayPrefix = service.buildCodexReplayPrefix(
                List.of(earlierUser, livePrompt),
                "Current prompt"
        );
        List<ClaudeSession.Attachment> replayAttachments = service.buildCodexReplayAttachments(
                List.of(earlierUser, livePrompt),
                "Current prompt"
        );

        assertTrue(replayPrefix.contains("Inspect the diagram"));
        assertTrue(replayPrefix.contains("reattached with the current request"));
        assertFalse(replayPrefix.contains("Current prompt"));
        assertEquals(1, replayAttachments.size());
        assertEquals("image/png", replayAttachments.get(0).mediaType);
        assertEquals("older-image-data", replayAttachments.get(0).data);
        assertEquals("replay-image-1.png", replayAttachments.get(0).fileName);
    }

    @Test
    public void buildCodexReplayPrefixReturnsEmptyForFirstTurn() {
        SessionContextService service = new SessionContextService(null, 1024);
        ClaudeSession.Message livePrompt = new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "Only prompt");

        String replayPrefix = service.buildCodexReplayPrefix(List.of(livePrompt), "Only prompt");

        assertEquals("", replayPrefix);
    }

    private JsonObject createToolResultRaw(String toolUseId, String contentText) {
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

    private ClaudeSession.Message createUserMessageWithImage(String text, String mediaType, String data) {
        ClaudeSession.Message message = new ClaudeSession.Message(ClaudeSession.Message.Type.USER, text);

        JsonArray content = new JsonArray();
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image");
        JsonObject source = new JsonObject();
        source.addProperty("type", "base64");
        source.addProperty("media_type", mediaType);
        source.addProperty("data", data);
        imageBlock.add("source", source);
        content.add(imageBlock);

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", text);
        content.add(textBlock);

        JsonObject rawMessage = new JsonObject();
        rawMessage.addProperty("role", "user");
        rawMessage.add("content", content);

        JsonObject raw = new JsonObject();
        raw.add("message", rawMessage);
        message.raw = raw;
        return message;
    }
}
