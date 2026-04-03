package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionState;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles message rewrite and retract operations.
 * <ul>
 *   <li>{@code rewrite_message} — Scenario C: rewrite a user message after AI has responded.
 *       Calls rewindFiles (Claude) or truncateHistory (Codex) to roll back,
 *       then truncates SessionState and notifies the frontend.</li>
 *   <li>{@code retract_message} — Scenario A: retract the last user message before AI responds.
 *       Interrupts the session, removes the last message, and notifies the frontend.</li>
 * </ul>
 */
public class RewriteHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(RewriteHandler.class);
    private static final Gson gson = new Gson();

    private static final String[] SUPPORTED_TYPES = {
        "rewrite_message",
        "retract_message"
    };

    public RewriteHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "rewrite_message":
                LOG.info("[RewriteHandler] Handling: rewrite_message");
                handleRewriteMessage(content);
                return true;
            case "retract_message":
                LOG.info("[RewriteHandler] Handling: retract_message");
                handleRetractMessage(content);
                return true;
            default:
                return false;
        }
    }

    // ---- Scenario C: Rewrite after AI responded ----

    private void handleRewriteMessage(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject request = gson.fromJson(content, JsonObject.class);
                String sessionId = request.has("sessionId") ? request.get("sessionId").getAsString() : null;
                String userMessageId = request.has("userMessageId") ? request.get("userMessageId").getAsString() : null;
                String provider = request.has("provider") ? request.get("provider").getAsString() : "claude";
                int messageIndex = request.has("messageIndex") ? request.get("messageIndex").getAsInt() : -1;

                // Fall back to backend session ID if frontend didn't provide one
                if (sessionId == null || sessionId.isEmpty()) {
                    ClaudeSession session = context.getSession();
                    if (session != null && session.getState() != null) {
                        sessionId = session.getState().getSessionId();
                    }
                }

                if (sessionId == null || sessionId.isEmpty()) {
                    LOG.warn("[RewriteHandler] Missing sessionId");
                    sendRewriteError("Session ID is required");
                    return;
                }
                if (userMessageId == null || userMessageId.isEmpty()) {
                    LOG.warn("[RewriteHandler] Missing userMessageId");
                    sendRewriteError("User message ID is required");
                    return;
                }

                LOG.info("[RewriteHandler] Rewrite - Session: " + sessionId
                        + ", MessageId: " + userMessageId
                        + ", Provider: " + provider
                        + ", MessageIndex: " + messageIndex);

                // Interrupt any ongoing generation before rewriting
                ClaudeSession session = context.getSession();
                final String finalSessionId = sessionId;
                final int finalMessageIndex = messageIndex;
                CompletableFuture<Void> interruptFuture = (session != null)
                        ? session.interrupt()
                        : CompletableFuture.completedFuture(null);

                interruptFuture.thenRun(() -> {
                    if ("codex".equals(provider)) {
                        handleCodexRewrite(finalSessionId, userMessageId, finalMessageIndex);
                    } else {
                        handleClaudeRewrite(finalSessionId, userMessageId, finalMessageIndex);
                    }
                }).exceptionally(ex -> {
                    LOG.error("[RewriteHandler] Interrupt before rewrite failed: " + ex.getMessage(), ex);
                    // Still attempt rewrite even if interrupt fails
                    if ("codex".equals(provider)) {
                        handleCodexRewrite(finalSessionId, userMessageId, finalMessageIndex);
                    } else {
                        handleClaudeRewrite(finalSessionId, userMessageId, finalMessageIndex);
                    }
                    return null;
                });
            } catch (Exception e) {
                LOG.error("[RewriteHandler] Failed to parse rewrite request: " + e.getMessage(), e);
                sendRewriteError("Invalid rewrite request");
            }
        });
    }

    private void handleClaudeRewrite(String sessionId, String userMessageId, int messageIndex) {
        String cwd = resolveCwd();

        // When the frontend doesn't have the UUID (smart merge drops it),
        // look it up from the JSONL history and call rewindFiles with the real UUID.
        if (userMessageId.startsWith("ts-")) {
            LOG.info("[RewriteHandler] Claude rewrite with synthetic ID — looking up UUID from JSONL");
            CompletableFuture.runAsync(() -> {
                String realUuid = findUserMessageUuidByIndex(sessionId, cwd, messageIndex);
                if (realUuid != null) {
                    LOG.info("[RewriteHandler] Found real UUID from JSONL: " + realUuid);
                    doClaudeRewindAndTruncate(sessionId, realUuid, cwd, messageIndex);
                } else {
                    LOG.info("[RewriteHandler] No UUID found in JSONL, truncating only");
                    truncateAndNotify(messageIndex);
                }
            });
            return;
        }

        doClaudeRewindAndTruncate(sessionId, userMessageId, cwd, messageIndex);
    }

    private void doClaudeRewindAndTruncate(String sessionId, String userMessageId, String cwd, int messageIndex) {
        context.getClaudeSDKBridge().rewindFiles(sessionId, userMessageId, cwd)
            .thenAccept(result -> {
                boolean success = result.has("success") && result.get("success").getAsBoolean();
                LOG.info("[RewriteHandler] Claude rewindFiles result: success=" + success);

                if (!success) {
                    String error = result.has("error") ? result.get("error").getAsString() : "";
                    LOG.warn("[RewriteHandler] rewindFiles failed: " + error + ", proceeding with truncation anyway");
                }
                // Always truncate conversation regardless of rewindFiles result
                truncateAndNotify(messageIndex);
            })
            .exceptionally(ex -> {
                LOG.error("[RewriteHandler] Claude rewrite exception: " + ex.getMessage(), ex);
                // Still truncate conversation on failure so user isn't stuck
                truncateAndNotify(messageIndex);
                return null;
            });
    }

    private void handleCodexRewrite(String sessionId, String userMessageId, int messageIndex) {
        // Codex uses OpenAI server-side threads (threadId). We cannot partially truncate
        // a server-side thread, so we reset the threadId to force a new thread on the next
        // request. This prevents the AI from "remembering" the removed messages.
        LOG.info("[RewriteHandler] Codex rewrite — truncating session state at index " + messageIndex);

        ClaudeSession session = context.getSession();
        if (session != null) {
            SessionState state = session.getState();
            if (state != null) {
                LOG.info("[RewriteHandler] Resetting Codex threadId to force new thread on next request");
                state.setSessionId(null);
            }
        }

        truncateAndNotify(messageIndex);
    }

    /**
     * Truncate messages from the given index and notify the frontend.
     */
    private void truncateAndNotify(int messageIndex) {
        ClaudeSession session = context.getSession();
        if (session != null) {
            SessionState state = session.getState();
            if (state != null && messageIndex >= 0) {
                List<ClaudeSession.Message> removed = state.truncateMessagesFrom(messageIndex);
                LOG.info("[RewriteHandler] Truncated " + removed.size() + " messages from index " + messageIndex);
            }
        }

        JsonObject callbackResult = new JsonObject();
        callbackResult.addProperty("success", true);
        callbackResult.addProperty("truncateAtIndex", messageIndex);

        String json = gson.toJson(callbackResult);
        LOG.info("[RewriteHandler] Calling onRewriteResult: " + json);
        ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("onRewriteResult", escapeJs(json));
        });
    }

    private void sendRewriteError(String message) {
        JsonObject errorResult = new JsonObject();
        errorResult.addProperty("success", false);
        errorResult.addProperty("message", message);

        String json = gson.toJson(errorResult);
        ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("onRewriteResult", escapeJs(json));
        });
    }

    // ---- Scenario A: Retract before AI responds ----

    private void handleRetractMessage(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject request = gson.fromJson(content, JsonObject.class);
                String sessionId = request.has("sessionId") ? request.get("sessionId").getAsString() : null;
                String provider = request.has("provider") ? request.get("provider").getAsString() : "claude";

                // Fall back to backend session ID if frontend didn't provide one
                if (sessionId == null || sessionId.isEmpty()) {
                    ClaudeSession s = context.getSession();
                    if (s != null && s.getState() != null) {
                        sessionId = s.getState().getSessionId();
                    }
                }

                if (sessionId == null || sessionId.isEmpty()) {
                    LOG.warn("[RewriteHandler] Retract: missing sessionId");
                    sendRetractError("Session ID is required");
                    return;
                }

                ClaudeSession session = context.getSession();
                if (session == null) {
                    LOG.warn("[RewriteHandler] Retract: no active session");
                    sendRetractError("No active session");
                    return;
                }

                LOG.info("[RewriteHandler] Retract - Session: " + sessionId + ", Provider: " + provider);

                final String finalSessionId = sessionId;

                // Interrupt the current session to stop the AI from responding
                session.interrupt().thenRun(() -> {
                    // Remove the last REAL user message (skip tool_result-only messages)
                    // and all subsequent messages (assistant responses, tool_results, etc.)
                    SessionState state = session.getState();
                    if (state != null) {
                        List<ClaudeSession.Message> messages = state.getMessagesReference();
                        synchronized (messages) {
                            // Walk backwards to find the last real user message (not tool_result)
                            int lastRealUserIdx = -1;
                            for (int i = messages.size() - 1; i >= 0; i--) {
                                ClaudeSession.Message msg = messages.get(i);
                                if (msg.type == ClaudeSession.Message.Type.USER && !isToolResultOnlyMessage(msg)) {
                                    lastRealUserIdx = i;
                                    break;
                                }
                            }
                            if (lastRealUserIdx >= 0) {
                                int removed = messages.size() - lastRealUserIdx;
                                messages.subList(lastRealUserIdx, messages.size()).clear();
                                LOG.info("[RewriteHandler] Removed " + removed
                                        + " messages from index " + lastRealUserIdx + " (user + tool_results + partial responses)");
                            }
                        }
                    }

                    // Provider-specific cleanup: rewind files before notifying frontend
                    if ("claude".equals(provider)) {
                        rewindAndNotifyRetract(finalSessionId);
                    } else if ("codex".equals(provider) && state != null) {
                        // Codex has no file checkpoint API — reset threadId to force new thread
                        LOG.info("[RewriteHandler] Resetting Codex threadId after retract");
                        state.setSessionId(null);
                        sendRetractSuccess();
                    } else {
                        sendRetractSuccess();
                    }
                }).exceptionally(ex -> {
                    LOG.error("[RewriteHandler] Retract interrupt failed: " + ex.getMessage(), ex);
                    sendRetractError("Failed to interrupt session");
                    return null;
                });
            } catch (Exception e) {
                LOG.error("[RewriteHandler] Retract exception: " + e.getMessage(), e);
                sendRetractError("Retract operation failed: " + e.getMessage());
            }
        });
    }

    private void sendRetractError(String message) {
        JsonObject errorResult = new JsonObject();
        errorResult.addProperty("success", false);
        errorResult.addProperty("message", message);

        String json = gson.toJson(errorResult);
        ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("onRetractResult", escapeJs(json));
        });
    }

    // ---- Claude rewind + retract callback ----

    /**
     * Rewind files via Claude SDK, then notify the frontend.
     * Unlike the old fire-and-forget approach, this waits for rewindFiles to complete
     * (or fail) before sending the retract success callback, ensuring file changes
     * are restored before the user sees "retract succeeded".
     */
    private void rewindAndNotifyRetract(String sessionId) {
        String cwd = resolveCwd();
        CompletableFuture.runAsync(() -> {
            try {
                List<JsonObject> history = context.getClaudeSDKBridge().getSessionMessages(sessionId, cwd);
                if (history == null || history.isEmpty()) {
                    LOG.warn("[RewriteHandler] No JSONL history found for retract rewind");
                    sendRetractSuccess();
                    return;
                }

                // Walk backwards to find the last user message UUID
                String lastUserUuid = null;
                for (int i = history.size() - 1; i >= 0; i--) {
                    JsonObject msg = history.get(i);
                    if (msg.has("type") && "user".equals(msg.get("type").getAsString())
                            && msg.has("uuid") && !msg.get("uuid").isJsonNull()) {
                        lastUserUuid = msg.get("uuid").getAsString();
                        break;
                    }
                }

                if (lastUserUuid == null) {
                    LOG.warn("[RewriteHandler] No user UUID in JSONL, skipping rewind");
                    sendRetractSuccess();
                    return;
                }

                LOG.info("[RewriteHandler] Rewinding files before retract callback, uuid=" + lastUserUuid);
                context.getClaudeSDKBridge().rewindFiles(sessionId, lastUserUuid, cwd)
                    .thenAccept(result -> {
                        boolean success = result.has("success") && result.get("success").getAsBoolean();
                        LOG.info("[RewriteHandler] Retract rewind result: success=" + success);
                        if (!success) {
                            String error = result.has("error") ? result.get("error").getAsString() : "";
                            LOG.warn("[RewriteHandler] Retract rewind failed: " + error + ", proceeding anyway");
                        }
                        sendRetractSuccess();
                    })
                    .exceptionally(ex -> {
                        LOG.warn("[RewriteHandler] Retract rewind exception: " + ex.getMessage()
                                + ", proceeding with retract");
                        sendRetractSuccess();
                        return null;
                    });
            } catch (Exception e) {
                LOG.warn("[RewriteHandler] Retract rewind setup exception: " + e.getMessage());
                sendRetractSuccess();
            }
        });
    }

    /**
     * Send retract success callback to frontend.
     */
    private void sendRetractSuccess() {
        JsonObject callbackResult = new JsonObject();
        callbackResult.addProperty("success", true);

        String json = gson.toJson(callbackResult);
        LOG.info("[RewriteHandler] Calling onRetractResult: " + json);
        ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("onRetractResult", escapeJs(json));
            callJavaScript("onStreamEnd");
            callJavaScript("showLoading", "false");
        });
    }

    /**
     * Find the UUID of a user message by its index in the conversation.
     * Counts only user messages in the JSONL history to match the frontend index.
     */
    private String findUserMessageUuidByIndex(String sessionId, String cwd, int messageIndex) {
        try {
            List<JsonObject> history = context.getClaudeSDKBridge().getSessionMessages(sessionId, cwd);
            if (history == null || history.isEmpty()) {
                return null;
            }

            // The frontend rawIndex counts ALL message types (user, assistant, error, etc.)
            // in order, so we count all types here to match.
            int idx = 0;
            for (JsonObject msg : history) {
                String type = msg.has("type") ? msg.get("type").getAsString() : "";
                if (type.isEmpty()) {
                    continue;
                }
                if (idx == messageIndex && "user".equals(type)
                        && msg.has("uuid") && !msg.get("uuid").isJsonNull()) {
                    return msg.get("uuid").getAsString();
                }
                idx++;
            }

            // Fallback: walk backwards to find the last user UUID at or before messageIndex
            for (int i = Math.min(messageIndex, history.size() - 1); i >= 0; i--) {
                JsonObject msg = history.get(i);
                if (msg.has("type") && "user".equals(msg.get("type").getAsString())
                        && msg.has("uuid") && !msg.get("uuid").isJsonNull()) {
                    return msg.get("uuid").getAsString();
                }
            }
        } catch (Exception e) {
            LOG.warn("[RewriteHandler] UUID lookup from JSONL failed: " + e.getMessage());
        }
        return null;
    }

    // ---- Utilities ----

    /**
     * Check if a user message is tool-result-only (not a real user text message).
     * Tool-result messages are auto-generated during multi-turn tool use and contain
     * content like "[tool_result]" or raw content blocks with type "tool_result".
     */
    private boolean isToolResultOnlyMessage(ClaudeSession.Message msg) {
        if (msg.type != ClaudeSession.Message.Type.USER) {
            return false;
        }
        // Check display content
        String content = msg.content;
        if (content != null && content.trim().equals("[tool_result]")) {
            return true;
        }
        // Check raw content blocks for tool_result type
        if (msg.raw != null) {
            com.google.gson.JsonArray contentBlocks = null;
            if (msg.raw.has("content") && msg.raw.get("content").isJsonArray()) {
                contentBlocks = msg.raw.getAsJsonArray("content");
            } else if (msg.raw.has("message") && msg.raw.get("message").isJsonObject()) {
                JsonObject message = msg.raw.getAsJsonObject("message");
                if (message.has("content") && message.get("content").isJsonArray()) {
                    contentBlocks = message.getAsJsonArray("content");
                }
            }
            if (contentBlocks != null) {
                for (com.google.gson.JsonElement block : contentBlocks) {
                    if (block.isJsonObject()) {
                        JsonObject blockObj = block.getAsJsonObject();
                        if (blockObj.has("type") && "tool_result".equals(blockObj.get("type").getAsString())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private String resolveCwd() {
        if (context.getSession() != null) {
            String cwd = context.getSession().getCwd();
            if (cwd != null && !cwd.isEmpty()) {
                return cwd;
            }
        }
        if (context.getProject() != null) {
            return context.getProject().getBasePath();
        }
        return null;
    }
}
