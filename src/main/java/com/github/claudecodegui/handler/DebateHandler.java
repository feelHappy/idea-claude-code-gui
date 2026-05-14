package com.github.claudecodegui.handler;

import com.github.claudecodegui.debate.DebateConfig;
import com.github.claudecodegui.debate.DebateService;
import com.github.claudecodegui.debate.DebateSession;
import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * @description Bug 辩论 Handler，管理前端与 DebateService 之间的通信。
 * @author zyl
 * @date 2026/05/14
 */
public class DebateHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(DebateHandler.class);
    private static final String[] SUPPORTED_TYPES = {
            "start_debate",
            "stop_debate",
            "get_debate_status"
    };

    private final Gson gson = new Gson();
    private volatile DebateService debateService;

    public DebateHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "start_debate":
                handleStartDebate(content);
                return true;
            case "stop_debate":
                handleStopDebate();
                return true;
            case "get_debate_status":
                handleGetStatus();
                return true;
            default:
                return false;
        }
    }

    private void handleStartDebate(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject params = JsonParser.parseString(content).getAsJsonObject();
                String topic = params.has("topic") ? params.get("topic").getAsString() : "Bug";
                String description = params.has("description") ? params.get("description").getAsString() : "";
                int maxRounds = params.has("maxRounds") ? params.get("maxRounds").getAsInt() : DebateConfig.DEFAULT_MAX_ROUNDS;

                if (description.trim().isEmpty()) {
                    sendDebateEvent("error", "Please provide a bug description.");
                    return;
                }

                DebateService service = getOrCreateService();
                if (service.isActive()) {
                    sendDebateEvent("error", "A debate is already in progress. Stop it first.");
                    return;
                }

                if (context.getSession() == null || context.getSession().getChannelId() == null) {
                    sendDebateEvent("error", "No active session. Please start a conversation first.");
                    return;
                }

                // Snapshot session info at start time to avoid mid-debate session switching issues
                String channelId = context.getSession().getChannelId();
                String sessionId = context.getSession().getSessionId();
                String cwd = context.getSession().getCwd();

                service.setClaudeSession(channelId, sessionId, cwd);
                service.setCodexSession(channelId, null);

                service.setStateListener((state, message) ->
                        ApplicationManager.getApplication().invokeLater(() -> {
                            JsonObject event = new JsonObject();
                            event.addProperty("state", state.name());
                            event.addProperty("message", message != null ? message : "");
                            if (service.getCurrentSession() != null) {
                                event.addProperty("round", service.getCurrentSession().getCurrentRound());
                                event.addProperty("maxRounds", service.getCurrentSession().getConfig().getMaxRounds());
                                if (service.getCurrentSession().getDebateFilePath() != null) {
                                    event.addProperty("filePath", service.getCurrentSession().getDebateFilePath().toString());
                                }
                            }
                            sendDebateState(event);
                        })
                );

                service.setRoundListener((provider, response) ->
                        ApplicationManager.getApplication().invokeLater(() -> {
                            JsonObject event = new JsonObject();
                            event.addProperty("provider", provider);
                            event.addProperty("content", response);
                            event.addProperty("round", service.getCurrentSession().getCurrentRound());
                            sendDebateRound(event);
                        })
                );

                DebateConfig config = new DebateConfig(maxRounds, DebateConfig.DEFAULT_TURN_TIMEOUT_MS);
                service.startDebate(topic, description, config);

            } catch (Exception e) {
                LOG.error("[DebateHandler] Failed to start debate", e);
                sendDebateEvent("error", "Failed to start debate: " + e.getMessage());
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    private void handleStopDebate() {
        if (debateService != null) {
            debateService.stopDebate();
        }
    }

    private void handleGetStatus() {
        JsonObject status = new JsonObject();
        if (debateService != null && debateService.getCurrentSession() != null) {
            DebateSession session = debateService.getCurrentSession();
            status.addProperty("active", debateService.isActive());
            status.addProperty("state", session.getState().name());
            status.addProperty("topic", session.getTopic());
            status.addProperty("round", session.getCurrentRound());
            status.addProperty("maxRounds", session.getConfig().getMaxRounds());
            if (session.getDebateFilePath() != null) {
                status.addProperty("filePath", session.getDebateFilePath().toString());
            }
            if (session.getLastError() != null) {
                status.addProperty("error", session.getLastError());
            }
        } else {
            status.addProperty("active", false);
            status.addProperty("state", "IDLE");
        }
        sendDebateState(status);
    }

    private DebateService getOrCreateService() {
        if (debateService == null) {
            String basePath = context.getProject() != null
                    ? context.getProject().getBasePath()
                    : (context.getSession() != null ? context.getSession().getCwd() : null);
            debateService = new DebateService(
                    context.getClaudeSDKBridge(),
                    context.getCodexSDKBridge(),
                    basePath
            );
            if (context.getProject() != null) {
                Disposer.register(context.getProject(), debateService);
            }
        }
        return debateService;
    }

    private void sendDebateEvent(String type, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("message", message);
        String payload = gson.toJson(event);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateDebateEvent", escapeJs(payload))
        );
    }

    private void sendDebateState(JsonObject state) {
        String payload = gson.toJson(state);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateDebateState", escapeJs(payload))
        );
    }

    private void sendDebateRound(JsonObject round) {
        String payload = gson.toJson(round);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateDebateRound", escapeJs(payload))
        );
    }
}
