package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.ClaudeSession.Message;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.UUID;

/**
 * Codex message callback handler.
 * Processes messages returned by Codex AI.
 * Similar to ClaudeMessageHandler but handles Codex's simpler message format,
 * primarily dealing with streaming text output.
 */
public class CodexMessageHandler implements MessageCallback {
    private static final Logger LOG = Logger.getInstance(CodexMessageHandler.class);

    private final Project project;
    private final SessionState state;
    private final CallbackHandler callbackHandler;

    // Content accumulator for the current assistant message
    private final StringBuilder assistantContent = new StringBuilder();

    // Current assistant message object being processed
    private Message currentAssistantMessage = null;

    // Snapshot of the message count before this handler starts processing.
    // The current user prompt is already appended before the handler is created,
    // so replay detection must treat the tail snapshot entry carefully.
    private final int messageCountAtStart;

    // Tracks whether notifyStreamStart has been called for this turn,
    // so we only call it once and can pair it with notifyStreamEnd.
    private boolean streamStarted = false;

    /**
     * Constructor.
     */
    public CodexMessageHandler(Project project, SessionState state, CallbackHandler callbackHandler) {
        this.project = project;
        this.state = state;
        this.callbackHandler = callbackHandler;
        this.messageCountAtStart = state.getMessages().size();
    }

    /**
     * Handle a received message by dispatching to the appropriate handler based on type.
     */
    @Override
    public void onMessage(String type, String content) {
        // [FIX] Handle multiple message types
        // Codex message-service.js sends:
        // - type='assistant': contains thinking, tool_use, text
        // - type='user': contains tool_result
        LOG.debug("CodexMessageHandler.onMessage: type=" + type + ", content length=" + (content != null ? content.length() : 0));

        if ("assistant".equals(type)) {
            // Handle assistant message (thinking, tool_use, text)
            handleAssistantMessage(content);
        } else if ("user".equals(type)) {
            // Handle user message (tool_result)
            handleUserMessage(content);
        } else if ("result".equals(type)) {
            // Handle result message (usage stats, etc.)
            handleResultMessage(content);
        } else if ("session_id".equals(type)) {
            // Handle session_id/thread_id (for session recovery)
            handleSessionId(content);
        } else if ("event_msg".equals(type)) {
            handleEventMessage(content);
        } else if ("content_delta".equals(type) || "content".equals(type)) {
            // Handle streaming content delta (legacy format, kept for compatibility)
            // content_delta: streaming incremental
            // content: complete content block
            handleContentDelta(content);
        } else if ("status".equals(type)) {
            if (content != null && !content.trim().isEmpty()) {
                callbackHandler.notifyStatusMessage(content);
            }
        } else if ("codex_file_changes".equals(type)) {
            handleCodexFileChanges(content);
        } else if ("message_end".equals(type)) {
            handleMessageEnd();
        } else {
            LOG.debug("CodexMessageHandler: Unhandled message type: " + type);
        }
    }

    /**
     * Handle an error from the SDK.
     */
    @Override
    public void onError(String error) {
        // End streaming on error so that the coalescer flushes any buffered
        // messages before the error state is applied.
        endStreamIfStarted();

        // Suppress user-initiated abort errors (triggered by retract/interrupt)
        if (error != null && (error.contains("aborted by user")
                || error.contains("Request aborted"))) {
            LOG.info("Suppressing user-abort error: " + error);
            state.setError(null);
            state.setBusy(false);
            state.setLoading(false);
            callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            return;
        }

        state.setError(error);
        state.setBusy(false);
        state.setLoading(false);

        Message errorMessage = new Message(Message.Type.ERROR, error);
        state.addMessage(errorMessage);
        callbackHandler.notifyMessageUpdate(state.getMessages());
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    /**
     * Handle completion of a response turn.
     */
    @Override
    public void onComplete(SDKResult result) {
        // Safety net: end streaming if message_end was never received
        // (e.g., process terminated abruptly).
        endStreamIfStarted();

        state.setBusy(false);
        state.setLoading(false);
        state.updateLastModifiedTime();
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    // ===== Private methods =====

    /**
     * Ensure notifyStreamStart is called exactly once per turn.
     * This activates the StreamMessageCoalescer's streaming mode and
     * notifies the frontend so loading/streaming UI state is correct.
     */
    private void ensureStreamStarted() {
        if (!streamStarted) {
            streamStarted = true;
            callbackHandler.notifyStreamStart();
            LOG.debug("Codex stream started (notifyStreamStart called)");
        }
    }

    /**
     * End the stream if it was started. Calls notifyStreamEnd exactly once,
     * which triggers StreamMessageCoalescer.flush(forceDelivery=true) to
     * guarantee the final message update reaches the frontend.
     */
    private void endStreamIfStarted() {
        if (streamStarted) {
            streamStarted = false;
            callbackHandler.notifyStreamEnd();
            LOG.debug("Codex stream ended (notifyStreamEnd called)");
        }
    }

    /**
     * If the assistant message contains a codex_session_patch tool_use block, schedule applying
     * that patch through IntelliJ's Document / WriteCommandAction before the VFS asyncRefresh
     * fires. This makes the change visible in the editor (dirty marker, undo history, git gutter).
     *
     * The invokeLater call is queued to the EDT before onStateChange's asyncRefresh invokeLater,
     * so the document write always happens first.
     */
    private void scheduleCodexPatchToDocument(com.google.gson.JsonObject msgJson) {
        try {
            if (project == null || !msgJson.has("message") || !msgJson.get("message").isJsonObject()) {
                return;
            }
            com.google.gson.JsonObject message = msgJson.getAsJsonObject("message");
            if (!message.has("content") || !message.get("content").isJsonArray()) {
                return;
            }
            com.google.gson.JsonArray content = message.getAsJsonArray("content");
            for (int i = 0; i < content.size(); i++) {
                com.google.gson.JsonElement el = content.get(i);
                if (!el.isJsonObject()) continue;
                com.google.gson.JsonObject block = el.getAsJsonObject();
                if (!block.has("type") || !"tool_use".equals(block.get("type").getAsString())) continue;
                if (!block.has("input") || !block.get("input").isJsonObject()) continue;

                com.google.gson.JsonObject input = block.getAsJsonObject("input");
                if (!input.has("source") || !"codex_session_patch".equals(input.get("source").getAsString())) {
                    continue;
                }

                String toolName = block.has("name") ? block.get("name").getAsString() : "";
                String filePath = input.has("file_path") ? input.get("file_path").getAsString() : null;
                String oldString = input.has("old_string") ? input.get("old_string").getAsString() : "";
                String newString = input.has("new_string") ? input.get("new_string").getAsString() : "";
                boolean replaceAll = input.has("replace_all") && input.get("replace_all").getAsBoolean();

                if (filePath == null || filePath.isEmpty()) continue;

                final String fp = filePath;
                final String os = oldString;
                final String ns = newString;
                final boolean ra = replaceAll;
                final boolean isWrite = "write".equals(toolName);

                ApplicationManager.getApplication().invokeLater(() ->
                        applyEditPatchToDocument(fp, os, ns, ra, isWrite)
                );
            }
        } catch (Exception e) {
            LOG.debug("scheduleCodexPatchToDocument failed: " + e.getMessage());
        }
    }

    /**
     * Apply a Codex patch to an IntelliJ Document via WriteCommandAction.
     * Must run on the EDT. Called before VFS asyncRefresh so the Document still holds
     * the pre-patch content (old_string is still present).
     *
     * For "write" (new file), old_string is empty — we just refresh the VirtualFile so
     * IntelliJ picks up the newly created file without needing a WriteCommandAction.
     */
    private void applyEditPatchToDocument(String filePath, String oldString, String newString, boolean replaceAll, boolean isWrite) {
        try {
            // Use findFileByPath (no I/O refresh) to get VirtualFile with stale-but-existing metadata.
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(filePath.replace('\\', '/'));
            if (vFile == null) {
                // File may not yet be indexed (e.g., newly created). Refresh to register it.
                vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.replace('\\', '/'));
                if (vFile == null) {
                    LOG.debug("Codex patch: VirtualFile not found for: " + filePath);
                    return;
                }
                // Newly created file: refresh is enough; no write action needed.
                vFile.refresh(false, false);
                return;
            }

            if (isWrite || oldString.isEmpty()) {
                // New-file write: Codex already wrote the content. Just refresh VFS so the
                // editor picks it up; do not try to WriteCommandAction (nothing to replace).
                vFile.refresh(false, false);
                return;
            }

            Document document = FileDocumentManager.getInstance().getDocument(vFile);
            if (document == null) {
                LOG.debug("Codex patch: no Document for: " + filePath);
                return;
            }
            if (!document.isWritable()) {
                LOG.debug("Codex patch: Document is read-only: " + filePath);
                return;
            }

            String docText = document.getText();
            int firstIndex = docText.indexOf(oldString);
            if (firstIndex < 0) {
                // old_string not found — Document was already refreshed from disk.
                // Just do a VFS refresh so the editor shows the current on-disk content.
                vFile.refresh(false, false);
                LOG.debug("Codex patch: old_string not found in Document (already refreshed?): " + filePath);
                return;
            }

            WriteCommandAction.runWriteCommandAction(project, "Codex Edit", null, () -> {
                try {
                    if (replaceAll) {
                        String text = document.getText();
                        StringBuilder sb = new StringBuilder(text);
                        int idx = sb.indexOf(oldString);
                        while (idx >= 0) {
                            sb.replace(idx, idx + oldString.length(), newString);
                            idx = sb.indexOf(oldString, idx + newString.length());
                        }
                        document.setText(sb.toString());
                    } else {
                        int idx = document.getText().indexOf(oldString);
                        if (idx >= 0) {
                            document.replaceString(idx, idx + oldString.length(), newString);
                        }
                    }
                    LOG.debug("Codex patch applied via WriteCommandAction: " + filePath);
                } catch (Exception e) {
                    LOG.warn("Codex patch WriteCommandAction failed for " + filePath + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.warn("applyEditPatchToDocument failed for " + filePath + ": " + e.getMessage());
        }
    }

    /**
     * Handle a complete assistant message in JSON format.
     * Contains thinking, tool_use, text, and other content types.
     */
    private void handleAssistantMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);

            // Apply v0.1.3-codex filtering logic
            Message parsed = parseServerMessage(msgJson, Message.Type.ASSISTANT);
            if (parsed == null) {
                LOG.debug("Codex assistant message filtered out");
                return;
            }

            // Deduplicate replayed events from previous turns.
            if (isReplayDuplicateOfHistory(parsed)) {
                LOG.debug("Codex assistant message skipped (duplicate of existing message)");
                return;
            }

            // For codex_session_patch tool_use messages: schedule document write via IntelliJ's
            // WriteCommandAction BEFORE the VFS asyncRefresh runs. This registers the change in
            // the undo stack and marks the file as unsaved (dirty) in the editor.
            scheduleCodexPatchToDocument(msgJson);

            // Signal stream start on the first non-duplicate assistant message.
            ensureStreamStarted();

            state.addMessage(parsed);
            callbackHandler.notifyMessageUpdate(state.getMessages());
        } catch (Exception e) {
            LOG.warn("Failed to parse assistant message: " + e.getMessage());
        }
    }

    /**
     * Handle a user message (primarily tool_result).
     */
    private void handleUserMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);

            // Apply v0.1.3-codex filtering logic
            Message parsed = parseServerMessage(msgJson, Message.Type.USER);
            if (parsed == null) {
                LOG.debug("Codex user message filtered out");
                return;
            }

            // Deduplicate replayed tool_result messages from previous turns.
            if (isReplayDuplicateOfHistory(parsed)) {
                LOG.debug("Codex user message skipped (duplicate of existing message)");
                return;
            }

            ensureStreamStarted();

            state.addMessage(parsed);
            callbackHandler.notifyMessageUpdate(state.getMessages());

            LOG.debug("Codex user message (tool_result) added");
        } catch (Exception e) {
            LOG.warn("Failed to parse user message: " + e.getMessage());
        }
    }

    /**
     * Handle the session_id (Codex thread ID) for session recovery.
     */
    private void handleSessionId(String threadId) {
        if (threadId != null && !threadId.trim().isEmpty()) {
            state.setSessionId(threadId);
            callbackHandler.notifySessionIdReceived(threadId);
            LOG.info("Captured Codex thread ID: " + threadId);
        }
    }

    /**
     * Handle the result message containing usage statistics.
     */
    private void handleResultMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);
            if (msgJson == null || !msgJson.has("usage") || !msgJson.get("usage").isJsonObject()) {
                return;
            }

            com.google.gson.JsonObject usage = msgJson.getAsJsonObject("usage");
            boolean updated = attachUsageToLastAssistant(usage);
            if (updated) {
                callbackHandler.notifyMessageUpdate(state.getMessages());
                LOG.info("Codex usage applied from result message");
            } else {
                LOG.debug("Codex usage received but no assistant message to attach");
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse Codex result message: " + e.getMessage());
        }
    }

    /**
     * Handle event_msg containing token_count and other events.
     */
    private void handleEventMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);
            if (msgJson == null || !msgJson.has("payload") || !msgJson.get("payload").isJsonObject()) {
                return;
            }

            com.google.gson.JsonObject payload = msgJson.getAsJsonObject("payload");
            if (!payload.has("type") || !"token_count".equals(payload.get("type").getAsString())) {
                return;
            }

            if (!payload.has("info") || payload.get("info").isJsonNull() || !payload.get("info").isJsonObject()) {
                return;
            }

            com.google.gson.JsonObject info = payload.getAsJsonObject("info");
            if (!info.has("total_token_usage") || !info.get("total_token_usage").isJsonObject()) {
                return;
            }

            com.google.gson.JsonObject totalUsage = info.getAsJsonObject("total_token_usage");
            int inputTokens = totalUsage.has("input_tokens") ? totalUsage.get("input_tokens").getAsInt() : 0;
            int outputTokens = totalUsage.has("output_tokens") ? totalUsage.get("output_tokens").getAsInt() : 0;
            int cachedInputTokens = totalUsage.has("cached_input_tokens") ? totalUsage.get("cached_input_tokens").getAsInt() : 0;

            com.google.gson.JsonObject usage = new com.google.gson.JsonObject();
            usage.addProperty("input_tokens", inputTokens);
            usage.addProperty("output_tokens", outputTokens);
            usage.addProperty("cache_read_input_tokens", cachedInputTokens);
            usage.addProperty("cache_creation_input_tokens", 0);

            boolean updated = attachUsageToLastAssistant(usage);
            if (updated) {
                callbackHandler.notifyMessageUpdate(state.getMessages());
                LOG.debug("Codex token_count applied: input=" + inputTokens + ", output=" + outputTokens + ", cached=" + cachedInputTokens);
            } else {
                LOG.debug("Codex token_count received but no assistant message to attach");
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse Codex event_msg: " + e.getMessage());
        }
    }

    /**
     * Attach usage data to the last assistant message's raw field for frontend display.
     */
    private boolean attachUsageToLastAssistant(com.google.gson.JsonObject usage) {
        java.util.List<Message> messages = state.getMessagesReference();
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message msg = messages.get(i);
                if (msg.type == Message.Type.ASSISTANT && msg.raw != null) {
                    msg.raw.add("usage", usage);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Parse a server message with full filtering and parsing logic (ported from v0.1.3-codex).
     */
    private Message parseServerMessage(com.google.gson.JsonObject msg, Message.Type messageType) {
        // Filter out isMeta messages (e.g., "Caveat: The messages below were generated...")
        if (msg.has("isMeta") && msg.get("isMeta").getAsBoolean()) {
            return null;
        }

        // Filter out command messages (containing <command-name> or <local-command-stdout> tags)
        if (msg.has("message") && msg.get("message").isJsonObject()) {
            com.google.gson.JsonObject message = msg.getAsJsonObject("message");
            if (message.has("content")) {
                com.google.gson.JsonElement contentElement = message.get("content");
                String contentStr = null;

                if (contentElement.isJsonPrimitive()) {
                    contentStr = contentElement.getAsString();
                } else if (contentElement.isJsonArray()) {
                    // Check text content in the array
                    com.google.gson.JsonArray contentArray = contentElement.getAsJsonArray();
                    for (int i = 0; i < contentArray.size(); i++) {
                        com.google.gson.JsonElement element = contentArray.get(i);
                        if (element.isJsonObject()) {
                            com.google.gson.JsonObject block = element.getAsJsonObject();
                            if (block.has("type") && "text".equals(block.get("type").getAsString()) &&
                                block.has("text")) {
                                contentStr = block.get("text").getAsString();
                                break;
                            }
                        }
                    }
                }

                // Filter out content with command tags (allow user input containing <command-message>)
                if (contentStr != null) {
                    boolean hasCommandMessage = contentStr.contains("<command-message>") &&
                        contentStr.contains("</command-message>");
                    if (!hasCommandMessage && (
                        contentStr.contains("<command-name>") ||
                        contentStr.contains("<local-command-stdout>") ||
                        contentStr.contains("<local-command-stderr>") ||
                        contentStr.contains("<command-args>")
                    )) {
                        return null;
                    }
                }
            }
        }

        String content = extractMessageContent(msg);

        // Special handling for user messages: preserve tool_result even if content is empty
        if (messageType == Message.Type.USER) {
            if (content == null || content.trim().isEmpty()) {
                // Check if it contains a tool_result
                if (msg.has("message") && msg.get("message").isJsonObject()) {
                    com.google.gson.JsonObject message = msg.getAsJsonObject("message");
                    if (message.has("content") && message.get("content").isJsonArray()) {
                        com.google.gson.JsonArray contentArray = message.getAsJsonArray("content");
                        for (int i = 0; i < contentArray.size(); i++) {
                            com.google.gson.JsonElement element = contentArray.get(i);
                            if (element.isJsonObject()) {
                                com.google.gson.JsonObject block = element.getAsJsonObject();
                                if (block.has("type") && "tool_result".equals(block.get("type").getAsString())) {
                                    // Contains tool_result; keep this message with placeholder content
                                    Message result = new Message(Message.Type.USER, "[tool_result]");
                                    result.raw = msg;
                                    return result;
                                }
                            }
                        }
                    }
                }
                return null;
            }
        }

        // Create message and preserve the original JSON
        Message result = new Message(messageType, content != null ? content : "");
        result.raw = msg;
        return result;
    }

    /**
     * Extract message content (ported from v0.1.3-codex).
     */
    private String extractMessageContent(com.google.gson.JsonObject msg) {
        if (!msg.has("message")) {
            // Try to get content directly from the top level (some message formats may differ)
            if (msg.has("content")) {
                return extractContentFromElement(msg.get("content"));
            }
            return "";
        }

        com.google.gson.JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return "";
        }

        // Get the content element
        com.google.gson.JsonElement contentElement = message.get("content");
        return extractContentFromElement(contentElement);
    }

    /**
     * Extract content from a JsonElement (ported from v0.1.3-codex).
     */
    private String extractContentFromElement(com.google.gson.JsonElement contentElement) {
        // String format
        if (contentElement.isJsonPrimitive()) {
            return contentElement.getAsString();
        }

        // Array format
        if (contentElement.isJsonArray()) {
            com.google.gson.JsonArray contentArray = contentElement.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            boolean hasContent = false;

            for (int i = 0; i < contentArray.size(); i++) {
                com.google.gson.JsonElement element = contentArray.get(i);
                if (element.isJsonObject()) {
                    com.google.gson.JsonObject block = element.getAsJsonObject();
                    String blockType = (block.has("type") && !block.get("type").isJsonNull())
                        ? block.get("type").getAsString()
                        : null;

                    // Handle different content block types
                    if ("text".equals(blockType) && block.has("text") && !block.get("text").isJsonNull()) {
                        String text = block.get("text").getAsString();
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(text);
                        hasContent = true;
                    } else if ("tool_use".equals(blockType)) {
                        // Skip tool_use, don't display tool usage text
                    } else if ("tool_result".equals(blockType)) {
                        // Tool result - skip display as it provides no direct value to the user
                        // and is typically long and already reflected in the assistant's response
                    } else if ("thinking".equals(blockType)) {
                        // Skip thinking block, don't display fixed text
                    } else if ("image".equals(blockType)) {
                        // Skip image block, don't display fixed text
                    }
                } else if (element.isJsonPrimitive()) {
                    // In some cases, array elements may be plain strings
                    String text = element.getAsString();
                    if (text != null && !text.trim().isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(text);
                        hasContent = true;
                    }
                }
            }

            return sb.toString();
        }

        // Object format (special cases)
        if (contentElement.isJsonObject()) {
            com.google.gson.JsonObject contentObj = contentElement.getAsJsonObject();
            // Try to extract the text field
            if (contentObj.has("text") && !contentObj.get("text").isJsonNull()) {
                return contentObj.get("text").getAsString();
            }
            LOG.warn("Content is an object but has no 'text' field: " + contentObj.toString());
        }

        return "";
    }

    /**
     * Handle content delta in streaming mode.
     */
    private void handleContentDelta(String content) {
        // Empty content check (compatible with v0.1.3-codex)
        if (content == null || content.isEmpty()) {
            return;
        }

        ensureStreamStarted();
        assistantContent.append(content);

        if (currentAssistantMessage == null) {
            currentAssistantMessage = new Message(Message.Type.ASSISTANT, assistantContent.toString());
            state.addMessage(currentAssistantMessage);
        } else {
            currentAssistantMessage.content = assistantContent.toString();
        }

        callbackHandler.notifyMessageUpdate(state.getMessages());
    }

    /**
     * Handle the end of a message.
     */
    private void handleMessageEnd() {
        // End streaming first so that flush(forceDelivery=true) delivers
        // the final message update before the state change resets loading.
        endStreamIfStarted();

        state.setBusy(false);
        state.setLoading(false);
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    private static final class CodexDetectedFileChange {
        private final String filePath;
        private final String status;
        private final String oldContent;
        private final String newContent;

        private CodexDetectedFileChange(String filePath, String status, String oldContent, String newContent) {
            this.filePath = filePath;
            this.status = status;
            this.oldContent = oldContent;
            this.newContent = newContent;
        }
    }

    /**
     * Handle the codex_file_changes message emitted by the JS layer after turn.completed.
     * The message carries the list of file paths (absolute) modified during this turn,
     * as determined by git diff (excluding pre-existing uncommitted changes).
     *
     * For each file we:
     *   1. Apply the disk change via WriteCommandAction → undo history + dirty marker in editor
     *   2. Emit a synthetic edit tool_use + tool_result so the frontend "编辑" tab can show it
     */
    private void handleCodexFileChanges(String jsonContent) {
        if (project == null || project.isDisposed()) {
            return;
        }
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msg = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);
            if (msg == null || !msg.has("files") || !msg.get("files").isJsonArray()) {
                return;
            }
            java.util.List<CodexDetectedFileChange> fileChanges = new java.util.ArrayList<>();
            com.google.gson.JsonArray filesArr = msg.getAsJsonArray("files");
            for (com.google.gson.JsonElement el : filesArr) {
                CodexDetectedFileChange fileChange = parseCodexDetectedFileChange(el);
                if (fileChange != null) {
                    fileChanges.add(fileChange);
                }
            }
            if (fileChanges.isEmpty()) {
                return;
            }
            LOG.info("handleCodexFileChanges: " + fileChanges.size() + " file(s) from JS layer");

            ApplicationManager.getApplication().invokeLater(() -> {
                boolean anyChanges = false;
                for (CodexDetectedFileChange fileChange : fileChanges) {
                    if (applyAndEmitFileChange(fileChange)) {
                        anyChanges = true;
                    }
                }
                if (anyChanges) {
                    callbackHandler.notifyMessageUpdate(state.getMessages());
                }
            });
        } catch (Exception e) {
            LOG.warn("handleCodexFileChanges failed: " + e.getMessage());
        }
    }

    private CodexDetectedFileChange parseCodexDetectedFileChange(com.google.gson.JsonElement element) {
        try {
            if (element == null || element.isJsonNull()) {
                return null;
            }
            if (element.isJsonPrimitive()) {
                String filePath = element.getAsString();
                return filePath != null && !filePath.trim().isEmpty()
                        ? new CodexDetectedFileChange(filePath, null, null, null)
                        : null;
            }
            if (!element.isJsonObject()) {
                return null;
            }

            com.google.gson.JsonObject obj = element.getAsJsonObject();
            String filePath = firstNonBlank(obj, "path", "file_path", "filePath");
            if (filePath == null) {
                return null;
            }
            return new CodexDetectedFileChange(
                    filePath,
                    normalizeFileStatus(firstNonBlank(obj, "status")),
                    firstNullable(obj, "old_content", "oldContent"),
                    firstNullable(obj, "new_content", "newContent")
            );
        } catch (Exception e) {
            LOG.debug("Failed to parse codex detected file change: " + e.getMessage());
            return null;
        }
    }

    private String firstNonBlank(com.google.gson.JsonObject obj, String... keys) {
        String value = firstNullable(obj, keys);
        return value != null && !value.trim().isEmpty() ? value : null;
    }

    private String firstNullable(com.google.gson.JsonObject obj, String... keys) {
        if (obj == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
                continue;
            }
            return obj.get(key).getAsString();
        }
        return null;
    }

    private String normalizeFileStatus(String status) {
        return "A".equals(status) ? "A" : (status != null ? "M" : null);
    }

    private String inferFileStatus(CodexDetectedFileChange change, String oldContent, String newContent) {
        if (change != null && "A".equals(change.status)) {
            return "A";
        }
        return ((oldContent == null || oldContent.isEmpty()) && newContent != null && !newContent.isEmpty()) ? "A" : "M";
    }

    /**
     * For a single Codex-modified file:
     *   - Refresh VirtualFile from disk
     *   - If an editor Document is open and differs from disk: apply via WriteCommandAction
     *   - Emit synthetic edit tool_use + tool_result messages for the frontend
     *
     * @return true if the file was processed successfully
     */
    private boolean applyAndEmitFileChange(CodexDetectedFileChange fileChange) {
        if (fileChange == null || fileChange.filePath == null || fileChange.filePath.trim().isEmpty()) {
            return false;
        }
        String filePath = fileChange.filePath;
        try {
            String normalizedPath = filePath.replace('\\', '/');
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath);
            if (vFile == null || !vFile.isValid()) {
                vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath);
            }

            Document document = (vFile != null && vFile.isValid())
                    ? FileDocumentManager.getInstance().getDocument(vFile)
                    : null;
            String oldContent = fileChange.oldContent != null
                    ? fileChange.oldContent
                    : (document != null ? document.getText() : "");
            String newContent = fileChange.newContent;

            if (newContent == null) {
                if (vFile == null || !vFile.isValid()) {
                    LOG.debug("codex_file_changes: VirtualFile not found and no payload content: " + filePath);
                    return false;
                }
                try {
                    byte[] bytes = vFile.contentsToByteArray();
                    Charset charset = vFile.getCharset();
                    newContent = new String(bytes, charset != null ? charset : java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOG.debug("codex_file_changes: cannot read disk content: " + filePath);
                    return false;
                }
            }

            // Apply via WriteCommandAction only if the file is open in an editor and differs.
            if (document != null && document.isWritable() && !document.getText().equals(newContent)) {
                final String finalNewContent = newContent;
                WriteCommandAction.runWriteCommandAction(project, "Codex Edit", null, () -> {
                    document.setText(finalNewContent);
                });
                LOG.info("codex_file_changes: WriteCommandAction applied for: " + filePath);
            } else if (vFile != null && vFile.isValid()) {
                vFile.refresh(false, false);
            }

            // Always emit synthetic messages so the "编辑" tab shows the file.
            emitSyntheticEditMessages(filePath, oldContent, newContent, inferFileStatus(fileChange, oldContent, newContent));
            return true;
        } catch (Exception e) {
            LOG.warn("applyAndEmitFileChange failed for " + filePath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Emit a synthetic edit tool_use (assistant) + tool_result (user) message pair.
     * The frontend's useFileChanges hook uses these to populate the "编辑" tab.
     */
    private void emitSyntheticEditMessages(String filePath, String oldContent, String newContent, String status) {
        String toolUseId = "codex_detected_" + UUID.randomUUID().toString().substring(0, 8);
        boolean isAddedFile = "A".equals(status);

        // tool_use (assistant)
        com.google.gson.JsonObject toolUseBlock = new com.google.gson.JsonObject();
        toolUseBlock.addProperty("type", "tool_use");
        toolUseBlock.addProperty("id", toolUseId);
        toolUseBlock.addProperty("name", isAddedFile ? "write" : "edit");
        com.google.gson.JsonObject input = new com.google.gson.JsonObject();
        input.addProperty("file_path", filePath);
        if (isAddedFile) {
            input.addProperty("content", newContent);
        } else {
            input.addProperty("old_string", oldContent);
            input.addProperty("new_string", newContent);
        }
        toolUseBlock.add("input", input);
        com.google.gson.JsonArray toolUseContent = new com.google.gson.JsonArray();
        toolUseContent.add(toolUseBlock);
        com.google.gson.JsonObject toolUseMessage = new com.google.gson.JsonObject();
        toolUseMessage.addProperty("role", "assistant");
        toolUseMessage.add("content", toolUseContent);
        com.google.gson.JsonObject toolUseRaw = new com.google.gson.JsonObject();
        toolUseRaw.addProperty("type", "assistant");
        toolUseRaw.add("message", toolUseMessage);
        Message tuMsg = new Message(Message.Type.ASSISTANT, "");
        tuMsg.raw = toolUseRaw;
        state.addMessage(tuMsg);

        // tool_result (user)
        com.google.gson.JsonObject resultBlock = new com.google.gson.JsonObject();
        resultBlock.addProperty("type", "tool_result");
        resultBlock.addProperty("tool_use_id", toolUseId);
        resultBlock.addProperty("is_error", false);
        resultBlock.addProperty("content", "Applied");
        com.google.gson.JsonArray resultContent = new com.google.gson.JsonArray();
        resultContent.add(resultBlock);
        com.google.gson.JsonObject resultMessage = new com.google.gson.JsonObject();
        resultMessage.addProperty("role", "user");
        resultMessage.add("content", resultContent);
        com.google.gson.JsonObject resultRaw = new com.google.gson.JsonObject();
        resultRaw.addProperty("type", "user");
        resultRaw.add("message", resultMessage);
        Message trMsg = new Message(Message.Type.USER, "[tool_result]");
        trMsg.raw = resultRaw;
        state.addMessage(trMsg);
    }

    /**
     * Check if a parsed message is a replay of a message that existed before this turn started.
     * The history snapshot includes the live user prompt that triggered this send, so only the
     * pre-turn portion of the snapshot is considered for deduplication.
     */
    private boolean isReplayDuplicateOfHistory(Message newMsg) {
        if (newMsg == null) {
            return false;
        }

        java.util.List<Message> messages = state.getMessagesReference();
        synchronized (messages) {
            int historyUpperBound = getReplayHistoryUpperBound(messages);
            if (historyUpperBound <= 0) {
                return false;
            }

            String newBridgeItemId = extractBridgeItemId(newMsg.raw);
            String newToolUseId = extractToolUseId(newMsg.raw);
            String newToolResultId = extractToolResultId(newMsg.raw);

            for (int i = 0; i < historyUpperBound; i++) {
                Message existing = messages.get(i);
                if (existing.type != newMsg.type) {
                    continue;
                }

                if (newBridgeItemId != null) {
                    String existingBridgeItemId = extractBridgeItemId(existing.raw);
                    if (newBridgeItemId.equals(existingBridgeItemId)) {
                        // Codex CLI reuses "item_0" as the item ID for agent_message items
                        // across ALL turns. So a bridge_item_id match alone is not sufficient
                        // for assistant messages — we must also compare content to distinguish
                        // a genuine replay from a brand-new response in a later turn.
                        if (newMsg.type == Message.Type.ASSISTANT) {
                            String newContent = newMsg.content != null ? newMsg.content.trim() : "";
                            String existingContent = existing.content != null ? existing.content.trim() : "";
                            if (!newContent.equals(existingContent)) {
                                LOG.debug("bridge_item_id match (" + newBridgeItemId + ") but content differs — treating as new message");
                                continue;
                            }
                        }
                        LOG.debug("Replay duplicate detected at index " + i + " (bridge item id match: " + newBridgeItemId + ")");
                        return true;
                    }
                }

                if (newMsg.type == Message.Type.USER) {
                    if (newToolResultId == null) {
                        continue;
                    }
                    String existingToolResultId = extractToolResultId(existing.raw);
                    if (newToolResultId.equals(existingToolResultId)) {
                        LOG.debug("Replay duplicate detected at index " + i + " (tool_result id match: " + newToolResultId + ")");
                        return true;
                    }
                    continue;
                }

                if (newToolUseId != null) {
                    String existingToolUseId = extractToolUseId(existing.raw);
                    if (newToolUseId.equals(existingToolUseId)) {
                        LOG.debug("Replay duplicate detected at index " + i + " (tool_use id match: " + newToolUseId + ")");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Determine the slice of history that existed before this send started.
     */
    private int getReplayHistoryUpperBound(java.util.List<Message> messages) {
        int upperBound = Math.min(messageCountAtStart, messages.size());
        if (upperBound <= 0) {
            return 0;
        }

        Message snapshotTail = messages.get(upperBound - 1);
        if (isLiveUserPrompt(snapshotTail)) {
            return upperBound - 1;
        }
        return upperBound;
    }

    /**
     * The last snapshot entry is usually the live user prompt that kicked off this turn.
     */
    private boolean isLiveUserPrompt(Message message) {
        if (message == null || message.type != Message.Type.USER) {
            return false;
        }
        if ("[tool_result]".equals(message.content)) {
            return false;
        }
        return message.content != null && !message.content.trim().isEmpty();
    }

    /**
     * Extract tool_use ID from a raw assistant message, if present.
     */
    private String extractBridgeItemId(com.google.gson.JsonObject raw) {
        try {
            if (raw != null && raw.has("bridge_item_id") && !raw.get("bridge_item_id").isJsonNull()) {
                String itemId = raw.get("bridge_item_id").getAsString();
                return itemId != null && !itemId.trim().isEmpty() ? itemId : null;
            }
        } catch (Exception e) {
            LOG.debug("Failed to extract bridge item ID: " + e.getMessage());
        }
        return null;
    }

    /**
     * Extract tool_use ID from a raw assistant message, if present.
     */
    private String extractToolUseId(com.google.gson.JsonObject raw) {
        try {
            com.google.gson.JsonObject message = raw.has("message") ? raw.getAsJsonObject("message") : null;
            if (message == null || !message.has("content") || !message.get("content").isJsonArray()) {
                return null;
            }
            com.google.gson.JsonArray content = message.getAsJsonArray("content");
            for (int i = 0; i < content.size(); i++) {
                com.google.gson.JsonElement el = content.get(i);
                if (el.isJsonObject()) {
                    com.google.gson.JsonObject block = el.getAsJsonObject();
                    if (block.has("type") && "tool_use".equals(block.get("type").getAsString())
                            && block.has("id") && !block.get("id").isJsonNull()) {
                        return block.get("id").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to extract tool_use ID: " + e.getMessage());
        }
        return null;
    }

    /**
     * Extract tool_result.tool_use_id from a raw user message, if present.
     */
    private String extractToolResultId(com.google.gson.JsonObject raw) {
        try {
            com.google.gson.JsonObject message = raw.has("message") ? raw.getAsJsonObject("message") : null;
            if (message == null || !message.has("content") || !message.get("content").isJsonArray()) {
                return null;
            }
            com.google.gson.JsonArray content = message.getAsJsonArray("content");
            for (int i = 0; i < content.size(); i++) {
                com.google.gson.JsonElement el = content.get(i);
                if (!el.isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject block = el.getAsJsonObject();
                if (block.has("type") && "tool_result".equals(block.get("type").getAsString())
                        && block.has("tool_use_id") && !block.get("tool_use_id").isJsonNull()) {
                    return block.get("tool_use_id").getAsString();
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to extract tool_result ID: " + e.getMessage());
        }
        return null;
    }
}
