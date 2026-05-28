package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Saves a compact progress file before the frontend asks the user to switch context.
 */
public class SessionHandoffHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(SessionHandoffHandler.class);
    private static final String[] SUPPORTED_TYPES = {"save_session_handoff_progress"};
    private static final String HANDOFF_PROGRESS_PATH = "_bmad-output/planning/session-handoff-progress.md";

    private final Gson gson = new Gson();

    public SessionHandoffHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        if (!"save_session_handoff_progress".equals(type)) {
            return false;
        }
        handleSaveProgress(content);
        return true;
    }

    private void handleSaveProgress(String content) {
        CompletableFuture.runAsync(() -> {
            String requestId = null;
            try {
                JsonObject payload = JsonParser.parseString(content).getAsJsonObject();
                requestId = getString(payload, "requestId");
                String markdown = getString(payload, "content");
                if (markdown == null || markdown.trim().isEmpty()) {
                    sendResult(false, null, requestId, "Progress content is empty.");
                    return;
                }

                Path workspaceRoot = resolveWorkspaceRoot();
                if (workspaceRoot == null) {
                    sendResult(false, null, requestId, "Cannot resolve workspace root.");
                    return;
                }

                Path progressPath = workspaceRoot.resolve(HANDOFF_PROGRESS_PATH).normalize();
                if (!progressPath.startsWith(workspaceRoot)) {
                    sendResult(false, null, requestId, "Resolved handoff path is outside workspace.");
                    return;
                }

                Files.createDirectories(progressPath.getParent());
                String contentToWrite = withGeneratedTimestamp(markdown);
                Files.writeString(progressPath, contentToWrite, StandardCharsets.UTF_8);
                LOG.info("[SessionHandoffHandler] Saved handoff progress: " + progressPath);

                sendResult(true, progressPath.toString(), requestId, null);
            } catch (Exception e) {
                LOG.warn("[SessionHandoffHandler] Failed to save handoff progress: " + e.getMessage(), e);
                sendResult(false, null, requestId, e.getMessage());
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    private Path resolveWorkspaceRoot() {
        String cwd = context.getSession() != null ? context.getSession().getCwd() : null;
        if (cwd == null || cwd.trim().isEmpty()) {
            cwd = context.getProject() != null ? context.getProject().getBasePath() : null;
        }
        if (cwd == null || cwd.trim().isEmpty()) {
            return null;
        }
        return Paths.get(cwd).toAbsolutePath().normalize();
    }

    private String withGeneratedTimestamp(String markdown) {
        String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now());
        return "<!-- Generated: " + timestamp + " -->\n\n" + markdown;
    }

    private String getString(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }

    private void sendResult(boolean success, String path, String requestId, String error) {
        JsonObject result = new JsonObject();
        result.addProperty("success", success);
        if (path != null) {
            result.addProperty("path", path);
        }
        if (requestId != null) {
            result.addProperty("requestId", requestId);
        }
        if (error != null) {
            result.addProperty("error", error);
        }

        String payload = gson.toJson(result);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.onSessionHandoffProgressSaved", escapeJs(payload))
        );
    }
}
