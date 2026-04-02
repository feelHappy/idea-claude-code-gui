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

                if ("codex".equals(provider)) {
                    handleCodexRewrite(sessionId, userMessageId, messageIndex);
                } else {
                    handleClaudeRewrite(sessionId, userMessageId, messageIndex);
                }
            } catch (Exception e) {
                LOG.error("[RewriteHandler] Failed to parse rewrite request: " + e.getMessage(), e);
                sendRewriteError("Invalid rewrite request");
            }
        });
    }

    private void handleClaudeRewrite(String sessionId, String userMessageId, int messageIndex) {
        String cwd = resolveCwd();

        // Use rewindFiles to restore file state, then truncate session state
        context.getClaudeSDKBridge().rewindFiles(sessionId, userMessageId, cwd)
            .thenAccept(result -> {
                boolean success = result.has("success") && result.get("success").getAsBoolean();
                LOG.info("[RewriteHandler] Claude rewindFiles result: success=" + success);

                if (success) {
                    truncateAndNotify(messageIndex);
                } else {
                    // Even if rewind failed (e.g. no checkpoint), still truncate the conversation
                    // so the user can re-enter their message
                    String error = result.has("error") ? result.get("error").getAsString() : "";
                    LOG.warn("[RewriteHandler] rewindFiles failed: " + error + ", proceeding with truncation anyway");
                    truncateAndNotify(messageIndex);
                }
            })
            .exceptionally(ex -> {
                LOG.error("[RewriteHandler] Claude rewrite exception: " + ex.getMessage(), ex);
                // Still truncate conversation on failure so user isn't stuck
                truncateAndNotify(messageIndex);
                return null;
            });
    }

    private void handleCodexRewrite(String sessionId, String userMessageId, int messageIndex) {
        // Codex has no rewindFiles equivalent — no file checkpoints.
        // We only need to truncate the session state.
        // The Codex JSONL truncation (for thread resumption) will be handled by ai-bridge
        // in a future enhancement. For now, we proceed with frontend truncation.
        LOG.info("[RewriteHandler] Codex rewrite — truncating session state at index " + messageIndex);
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

                if (sessionId == null || sessionId.isEmpty()) {
                    LOG.warn("[RewriteHandler] Retract: missing sessionId");
                    sendRetractError("Session ID is required");
                    return;
                }

                LOG.info("[RewriteHandler] Retract - Session: " + sessionId + ", Provider: " + provider);

                // Interrupt the current session to stop the AI from responding
                context.getSession().interrupt().thenRun(() -> {
                    // Remove the last user message from session state
                    SessionState state = context.getSession().getState();
                    if (state != null) {
                        List<ClaudeSession.Message> messages = state.getMessagesReference();
                        if (!messages.isEmpty()) {
                            ClaudeSession.Message lastMsg = messages.get(messages.size() - 1);
                            if (lastMsg.type == ClaudeSession.Message.Type.USER) {
                                messages.remove(messages.size() - 1);
                                LOG.info("[RewriteHandler] Removed last user message from session state");
                            }
                        }
                    }

                    JsonObject callbackResult = new JsonObject();
                    callbackResult.addProperty("success", true);

                    String json = gson.toJson(callbackResult);
                    LOG.info("[RewriteHandler] Calling onRetractResult: " + json);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        callJavaScript("onRetractResult", escapeJs(json));
                        callJavaScript("onStreamEnd");
                        callJavaScript("showLoading", "false");
                    });
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

    // ---- Utilities ----

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
