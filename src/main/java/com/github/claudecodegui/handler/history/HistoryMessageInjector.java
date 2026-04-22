package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.CodexMessageConverter;
import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.session.ClaudeSession;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Service for loading session messages and injecting them into the frontend.
 * Handles both Claude and Codex session loading.
 */
class HistoryMessageInjector {

    private static final Logger LOG = Logger.getInstance(HistoryMessageInjector.class);

    private final HandlerContext context;

    HistoryMessageInjector(HandlerContext context) {
        this.context = context;
    }

    /**
     * Load a history session.
     */
    void handleLoadSession(String sessionId, String currentProvider, HistoryHandler.SessionLoadCallback sessionLoadCallback) {
        String projectPath = context.getProject().getBasePath();
        if (projectPath == null) {
            LOG.warn("[HistoryHandler] Project base path is null");
            return;
        }
        LOG.info("[HistoryHandler] Loading history session: " + sessionId + " from project: " + projectPath + ", provider: " + currentProvider);

        if ("codex".equals(currentProvider)) {
            // Codex session: read session info and restore session state
            loadCodexSession(sessionId);
        } else {
            // Claude session: use existing callback mechanism
            if (sessionLoadCallback != null) {
                sessionLoadCallback.onLoadSession(sessionId, projectPath);
            } else {
                LOG.warn("[HistoryHandler] WARNING: No session load callback set");
            }
        }
    }

    /**
     * Load a Codex session.
     * Reads session messages directly and injects them into the frontend, while restoring session state.
     */
    private void loadCodexSession(String sessionId) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始加载 Codex 会话 ==========");
            LOG.info("[HistoryHandler] SessionId: " + sessionId);

            try {
                CodexHistoryReader codexReader = new CodexHistoryReader();
                String messagesJson = codexReader.getSessionMessagesAsJson(sessionId);
                JsonArray messages = JsonParser.parseString(messagesJson).getAsJsonArray();

                LOG.info("[HistoryHandler] 读取到 " + messages.size() + " 条 Codex 消息");

                // Extract session metadata and restore session state
                String[] sessionMeta = extractSessionMeta(messages);
                String threadIdToUse = sessionMeta[0] != null ? sessionMeta[0] : sessionId;
                String cwd = sessionMeta[1];

                context.getSession().setSessionInfo(threadIdToUse, cwd);
                LOG.info("[HistoryHandler] 恢复 Codex 会话状态: threadId=" + threadIdToUse + " (from sessionId=" + sessionId + "), cwd=" + cwd);

                // Clear current messages and release session transition guard
                // so that addHistoryMessage() calls below are not blocked.
                // The guard was set by beginSessionTransition() in the frontend;
                // for Claude sessions setSessionId() releases it, but Codex
                // sessions inject messages directly via addHistoryMessage().
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.executeJavaScriptOnEDT(
                        "if (window.clearMessages) { window.clearMessages(); } " +
                        "window.__sessionTransitioning = false; " +
                        "window.__sessionTransitionToken = null;"
                    );
                });

                // Sync history to backend state BEFORE frontend injection.
                // This populates state.messages with the correct bridge_item_id /
                // tool_use id / tool_result id so that CodexMessageHandler's
                // isReplayDuplicateOfHistory can deduplicate events that the SDK
                // replays when the thread is resumed for a new turn.
                for (int i = 0; i < messages.size(); i++) {
                    JsonObject msg = messages.get(i).getAsJsonObject();
                    addToBackendStateForDedup(msg);
                }
                LOG.info("[HistoryHandler] 同步 " + context.getSession().getState().getMessages().size()
                        + " 条消息到后端 state 用于去重");

                // Convert Codex messages to frontend format and inject one by one
                for (int i = 0; i < messages.size(); i++) {
                    JsonObject msg = messages.get(i).getAsJsonObject();
                    processAndInjectCodexMessage(msg);
                }

                // Notify frontend that history messages have finished loading, trigger Markdown re-rendering
                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "if (window.historyLoadComplete) { " +
                                            "  try { " +
                                            "    window.historyLoadComplete(); " +
                                            "  } catch(e) { " +
                                            "    console.error('[HistoryHandler] historyLoadComplete callback failed:', e); " +
                                            "  } " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });

                LOG.info("[HistoryHandler] ========== Codex 会话加载完成 ==========");

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 加载 Codex 会话失败: " + e.getMessage(), e);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String errorMsg = context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误");
                    String jsCode = "if (window.addErrorMessage) { " +
                                            "  window.addErrorMessage('加载 Codex 会话失败: " + errorMsg + "'); " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }

    /**
     * Extract Codex session metadata (threadId and cwd).
     *
     * @return String[2]: [0]=actualThreadId, [1]=cwd
     */
    private String[] extractSessionMeta(JsonArray messages) {
        String cwd = null;
        String actualThreadId = null;

        for (int i = 0; i < messages.size(); i++) {
            JsonObject msg = messages.get(i).getAsJsonObject();
            if (msg.has("type") && "session_meta".equals(msg.get("type").getAsString())) {
                if (msg.has("payload")) {
                    JsonObject payload = msg.getAsJsonObject("payload");
                    if (payload.has("cwd")) {
                        cwd = payload.get("cwd").getAsString();
                    }
                    if (payload.has("id")) {
                        actualThreadId = payload.get("id").getAsString();
                    }
                    break;
                }
            }
        }

        return new String[]{actualThreadId, cwd};
    }

    /**
     * Process and inject a single Codex message into the frontend.
     * Handles both response_item (assistant/tool messages) and event_msg (user messages).
     */
    private void processAndInjectCodexMessage(JsonObject msg) {
        if (!msg.has("type")) {
            return;
        }

        String msgType = msg.get("type").getAsString();
        JsonObject payload = msg.has("payload") ? msg.getAsJsonObject("payload") : null;
        if (payload == null || !payload.has("type")) {
            return;
        }

        String payloadType = payload.get("type").getAsString();
        String timestamp = msg.has("timestamp") ? msg.get("timestamp").getAsString() : null;
        JsonObject frontendMsg = null;

        if ("event_msg".equals(msgType)) {
            // Handle user messages from event_msg type
            if ("user_message".equals(payloadType)) {
                String message = payload.has("message") ? payload.get("message").getAsString() : "";
                if (!message.isEmpty()) {
                    frontendMsg = new JsonObject();
                    frontendMsg.addProperty("type", "user");
                    frontendMsg.addProperty("content", message);
                    if (timestamp != null) {
                        frontendMsg.addProperty("timestamp", timestamp);
                    }
                }
            }
            // Skip other event_msg types (task_complete, etc.)
        } else if ("response_item".equals(msgType)) {
            if ("message".equals(payloadType)) {
                frontendMsg = CodexMessageConverter.convertCodexMessageToFrontend(payload, timestamp);
            } else if ("function_call".equals(payloadType)) {
                frontendMsg = CodexMessageConverter.convertFunctionCallToToolUse(payload, timestamp);
            } else if ("function_call_output".equals(payloadType)) {
                frontendMsg = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, timestamp);
            } else if ("custom_tool_call".equals(payloadType)) {
                frontendMsg = CodexMessageConverter.convertCustomToolCallToToolUse(payload, timestamp);
            }
        }

        if (frontendMsg != null) {
            injectMessageToFrontend(frontendMsg);
        }
    }

    /**
     * Add a history message to the backend SessionState for replay deduplication.
     * Creates a Message with the correct raw format so that
     * CodexMessageHandler.isReplayDuplicateOfHistory can match replayed SDK events
     * by bridge_item_id, tool_use id, or tool_result tool_use_id.
     */
    private void addToBackendStateForDedup(JsonObject msg) {
        ClaudeSession.Message backendMsg = createBackendStateMessageForHistory(msg);
        if (backendMsg != null) {
            context.getSession().getState().addMessage(backendMsg);
        }
    }

    static ClaudeSession.Message createBackendStateMessageForHistory(JsonObject msg) {
        if (msg == null || !msg.has("type")) {
            return null;
        }

        String msgType = msg.get("type").getAsString();
        JsonObject payload = msg.has("payload") ? msg.getAsJsonObject("payload") : null;
        if (payload == null || !payload.has("type")) {
            return null;
        }

        if ("event_msg".equals(msgType) && "user_message".equals(payload.get("type").getAsString())) {
            String userMessageText = payload.has("message") && !payload.get("message").isJsonNull()
                    ? payload.get("message").getAsString()
                    : "";
            if (userMessageText.isEmpty()) {
                return null;
            }

            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", userMessageText);
            JsonArray content = new JsonArray();
            content.add(textBlock);

            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.add("content", content);

            JsonObject raw = new JsonObject();
            raw.add("message", message);

            ClaudeSession.Message backendMsg = new ClaudeSession.Message(
                    ClaudeSession.Message.Type.USER,
                    userMessageText
            );
            backendMsg.raw = raw;
            return backendMsg;
        }

        if (!"response_item".equals(msgType)) {
            return null;
        }

        String payloadType = payload.get("type").getAsString();
        String payloadId = payload.has("id") && !payload.get("id").isJsonNull()
                ? payload.get("id").getAsString() : null;

        ClaudeSession.Message.Type messageType;
        JsonObject raw = new JsonObject();

        switch (payloadType) {
            case "message": {
                String role = payload.has("role") ? payload.get("role").getAsString() : "assistant";
                messageType = "user".equals(role)
                        ? ClaudeSession.Message.Type.USER
                        : ClaudeSession.Message.Type.ASSISTANT;
                if (payloadId != null) {
                    raw.addProperty("bridge_item_id", payloadId);
                }
                JsonObject message = new JsonObject();
                message.addProperty("role", role);
                if (payload.has("content")) {
                    message.add("content",
                            CodexMessageConverter.convertToClaudeContentBlocks(payload.get("content")));
                }
                raw.add("message", message);
                break;
            }
            case "function_call": {
                messageType = ClaudeSession.Message.Type.ASSISTANT;
                if (payloadId != null) {
                    raw.addProperty("bridge_item_id", payloadId);
                }
                String callId = payload.has("call_id") && !payload.get("call_id").isJsonNull()
                        ? payload.get("call_id").getAsString() : (payloadId != null ? payloadId : "");
                JsonObject toolUse = new JsonObject();
                toolUse.addProperty("type", "tool_use");
                toolUse.addProperty("id", callId);
                toolUse.addProperty("name",
                        payload.has("name") ? payload.get("name").getAsString() : "unknown");
                JsonArray content = new JsonArray();
                content.add(toolUse);
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                message.add("content", content);
                raw.add("message", message);
                break;
            }
            case "function_call_output": {
                messageType = ClaudeSession.Message.Type.USER;
                if (payloadId != null) {
                    raw.addProperty("bridge_item_id", payloadId);
                }
                String callId = payload.has("call_id") && !payload.get("call_id").isJsonNull()
                        ? payload.get("call_id").getAsString() : "";
                JsonObject toolResult = new JsonObject();
                toolResult.addProperty("type", "tool_result");
                toolResult.addProperty("tool_use_id", callId);
                JsonArray content = new JsonArray();
                content.add(toolResult);
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.add("content", content);
                raw.add("message", message);
                break;
            }
            default:
                return null;
        }

        String contentText = CodexMessageConverter.extractContentAsString(payload.get("content"));
        ClaudeSession.Message backendMsg = new ClaudeSession.Message(
                messageType, contentText != null ? contentText : "");
        backendMsg.raw = raw;
        return backendMsg;
    }

    /**
     * Inject a message into the frontend.
     */
    private void injectMessageToFrontend(JsonObject frontendMsg) {
        String msgJson = new Gson().toJson(frontendMsg);
        String base64Json = java.util.Base64.getEncoder().encodeToString(
                msgJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ApplicationManager.getApplication().invokeLater(() -> {
            String jsCode = "if (window.addHistoryMessage) { " +
                                    "  try { " +
                                    "    var base64Str = '" + base64Json + "'; " +
                                    "    var binaryStr = atob(base64Str); " +
                                    "    var bytes = new Uint8Array(binaryStr.length); " +
                                    "    for (var i = 0; i < binaryStr.length; i++) { bytes[i] = binaryStr.charCodeAt(i); } " +
                                    "    var msgStr = new TextDecoder('utf-8').decode(bytes); " +
                                    "    var msg = JSON.parse(msgStr); " +
                                    "    window.addHistoryMessage(msg); " +
                                    "  } catch(e) { " +
                                    "    console.error('[HistoryHandler] Failed to parse/add message:', e); " +
                                    "  } " +
                                    "} else { " +
                                    "  console.warn('[HistoryHandler] addHistoryMessage not available'); " +
                                    "}";
            context.executeJavaScriptOnEDT(jsCode);
        });
    }
}
