package com.github.claudecodegui.provider.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.dependency.DependencyManager;
import com.github.claudecodegui.provider.common.BaseSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.util.PlatformUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Codex SDK bridge.
 * Handles Java to Node.js Codex SDK communication, supports streaming responses.
 * Uses unified ai-bridge directory (shared with Claude).
 */
public class CodexSDKBridge extends BaseSDKBridge {

    // Codex API configuration
    private String baseUrl = null;
    private String apiKey = null;
    private static final String SANDBOX_MODE_WORKSPACE_WRITE = "workspace-write";
    private static final String SANDBOX_MODE_DANGER_FULL_ACCESS = "danger-full-access";
    private static final String SANDBOX_MODE_READ_ONLY = "read-only";
    private static final String APPROVAL_POLICY_NEVER = "never";
    private static final String APPROVAL_POLICY_ON_REQUEST = "on-request";
    private static final String APPROVAL_POLICY_UNTRUSTED = "untrusted";
    private static final String ENV_CODEX_APPROVAL_POLICY = "CODEX_APPROVAL_POLICY";
    private static final String ENV_CODEX_SANDBOX_MODE = "CODEX_SANDBOX_MODE";
    private static final String ENV_CODEX_SANDBOX = "CODEX_SANDBOX";
    private static final String ENV_CODEX_CI = "CODEX_CI";
    private static final String ENV_CODEX_SANDBOX_NETWORK_DISABLED = "CODEX_SANDBOX_NETWORK_DISABLED";
    private static final long MCP_STATUS_TIMEOUT_MS = 65_000;
    private static final long MCP_TOOLS_TIMEOUT_MS = 65_000;

    public CodexSDKBridge() {
        super(CodexSDKBridge.class);
    }

    // ============================================================================
    // Abstract method implementations
    // ============================================================================

    @Override
    protected String getProviderName() {
        return "codex";
    }

    @Override
    protected void configureProviderEnv(Map<String, String> env, String stdinJson) {
        env.put("CODEX_USE_STDIN", "true");
    }

    @Override
    protected void processOutputLine(
            String line,
            MessageCallback callback,
            SDKResult result,
            StringBuilder assistantContent,
            boolean[] hadSendError,
            String[] lastNodeError
    ) {
        if (line.contains("[DEBUG]")) {
            LOG.debug("[Codex] " + line);
        }

        if (line.startsWith("[MESSAGE_START]")) {
            callback.onMessage("message_start", "");
        } else if (line.startsWith("[MESSAGE_END]")) {
            callback.onMessage("message_end", "");
        } else if (line.startsWith("[THREAD_ID]")) {
            String receivedThreadId = line.substring("[THREAD_ID]".length()).trim();
            callback.onMessage("session_id", receivedThreadId);
        } else if (line.startsWith("[MESSAGE]")) {
            String jsonStr = line.substring("[MESSAGE]".length()).trim();
            try {
                JsonObject msg = gson.fromJson(jsonStr, JsonObject.class);
                if (msg != null) {
                    String msgType = msg.has("type") && !msg.get("type").isJsonNull()
                            ? msg.get("type").getAsString()
                            : "unknown";

                    if ("status".equals(msgType)) {
                        String status = "";
                        if (msg.has("message") && !msg.get("message").isJsonNull()) {
                            JsonElement statusEl = msg.get("message");
                            status = statusEl.isJsonPrimitive() ? statusEl.getAsString() : statusEl.toString();
                        }
                        if (status != null && !status.isEmpty()) {
                            callback.onMessage("status", status);
                        }
                        return;
                    }

                    result.messages.add(msg);

                    if ("assistant".equals(msgType)) {
                        try {
                            String extracted = extractAssistantText(msg);
                            if (extracted != null && !extracted.isEmpty()) {
                                assistantContent.append(extracted);
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    callback.onMessage(msgType, jsonStr);
                }
            } catch (Exception ignored) {
            }
        } else if (line.startsWith("[CONTENT_DELTA]")) {
            String delta = line.substring("[CONTENT_DELTA]".length()).trim();
            assistantContent.append(delta);
            callback.onMessage("content_delta", delta);
        } else if (line.startsWith("[CONTENT]")) {
            String content = line.substring("[CONTENT]".length()).trim();
            // Avoid duplicate
            if (!assistantContent.toString().contains(content)) {
                assistantContent.append(content);
            }
            callback.onMessage("content", content);
        } else if (line.startsWith("[SEND_ERROR]")) {
            String jsonStr = line.substring("[SEND_ERROR]".length()).trim();
            String errorMessage = jsonStr;
            try {
                JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
                if (obj.has("error")) {
                    errorMessage = obj.get("error").getAsString();
                }
            } catch (Exception ignored) {
            }
            hadSendError[0] = true;
            result.success = false;
            result.error = errorMessage;
            callback.onError(errorMessage);
        }
    }

    // ============================================================================
    // Codex-specific configuration
    // ============================================================================

    /**
     * Set Codex API base URL.
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Get Codex API base URL.
     */
    public String getBaseUrl() {
        return this.baseUrl;
    }

    /**
     * Set Codex API key.
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Get Codex API key.
     */
    public String getApiKey() {
        return this.apiKey;
    }

    /**
     * Set executable permission for Codex binary.
     * Codex SDK is installed in ~/.codemoss/dependencies/codex-sdk/, not in ai-bridge.
     */
    private void setCodexExecutablePermission(File bridgeDir) {
        try {
            // Codex SDK is installed in ~/.codemoss/dependencies/codex-sdk/
            // not in ai-bridge directory
            DependencyManager depManager = new DependencyManager();
            File sdkNodeModules = depManager.getSdkNodeModulesDir("codex-sdk").toFile();
            File vendorDir = new File(sdkNodeModules, "@openai/codex-sdk/vendor");

            if (!vendorDir.exists()) {
                LOG.info("Codex vendor directory not found at: " + vendorDir.getAbsolutePath() + ", skipping permission setup");
                return;
            }

            File[] platformDirs = vendorDir.listFiles();
            if (platformDirs == null) return;

            for (File platformDir : platformDirs) {
                if (!platformDir.isDirectory()) continue;

                File codexDir = new File(platformDir, "codex");
                File codexBinary = new File(codexDir, "codex");
                File codexExe = new File(codexDir, "codex.exe");

                if (codexBinary.exists()) {
                    boolean success = codexBinary.setExecutable(true, false);
                    LOG.info("Set executable permission: " + codexBinary.getAbsolutePath() + " -> " + success);
                }
                if (codexExe.exists()) {
                    boolean success = codexExe.setExecutable(true, false);
                    LOG.info("Set executable permission: " + codexExe.getAbsolutePath() + " -> " + success);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to set executable permission: " + e.getMessage());
        }
    }

    // ============================================================================
    // Message sending
    // ============================================================================

    /**
     * Send message to Codex (streaming response).
     *
     * Note: Codex uses threadId instead of sessionId
     * Note: Codex supports images via local_image type (requires file path, not base64)
     * Note: Codex does not support system prompts, so agentPrompt is appended to user message
     */
    public CompletableFuture<SDKResult> sendMessage(
            String channelId,
            String message,
            String threadId,  // Codex uses threadId, not sessionId
            String cwd,
            List<ClaudeSession.Attachment> attachments,  // Image attachments (saved to temp files for Codex)
            String permissionMode,
            String model,
            String agentPrompt,  // Agent prompt (appended to message for Codex)
            String reasoningEffort,  // Codex reasoning effort (thinking depth)
            MessageCallback callback
    ) {
        return CompletableFuture.supplyAsync(() -> {
            SDKResult result = new SDKResult();
            StringBuilder assistantContent = new StringBuilder();
            final String[] lastNodeError = {null};
            final boolean[] hadSendError = {false};
            final List<File> tempUploadedFiles = new ArrayList<>();  // Track temp files for cleanup

            try {
                String node = nodeDetector.findNodeExecutable();
                File bridgeDir = getDirectoryResolver().findSdkDir();

                // Null check for bridgeDir
                if (bridgeDir == null || !bridgeDir.exists()) {
                    result.success = false;
                    result.error = "Bridge directory not ready or invalid";
                    return result;
                }

                // Ensure Codex SDK binary has executable permission
                setCodexExecutablePermission(bridgeDir);

                // Append agentPrompt to message if provided (Codex doesn't support system prompts)
                String finalMessage = message;
                if (agentPrompt != null && !agentPrompt.isEmpty()) {
                    finalMessage = message + "\n\n## Agent Role and Instructions\n\n" + agentPrompt;
                    LOG.info("[Agent] ✓ Appending agentPrompt to user message for Codex (length: " + agentPrompt.length() + " chars)");
                }

                CodexAttachmentPayload attachmentPayload = buildCodexAttachmentPayload(attachments, tempUploadedFiles);
                if (!attachmentPayload.stagedFilePaths.isEmpty()) {
                    finalMessage = appendUploadedFilesContext(finalMessage, attachmentPayload.stagedFilePaths);
                    LOG.info("[Codex] Added " + attachmentPayload.stagedFilePaths.size()
                            + " staged non-image attachment path(s) into prompt context");
                }

                // Build stdin input JSON
                // Note: Codex uses 'threadId' (not 'sessionId')
                JsonObject stdinInput = new JsonObject();
                stdinInput.addProperty("message", finalMessage);
                stdinInput.addProperty("threadId", threadId != null ? threadId : "");
                stdinInput.addProperty("cwd", cwd != null ? cwd : "");
                stdinInput.addProperty("permissionMode", permissionMode != null ? permissionMode : "");
                stdinInput.addProperty("model", model != null ? model : "");
                // Reasoning effort (thinking depth)
                stdinInput.addProperty("reasoningEffort", reasoningEffort != null ? reasoningEffort : "medium");
                // API configuration
                stdinInput.addProperty("baseUrl", baseUrl != null ? baseUrl : "");
                stdinInput.addProperty("apiKey", apiKey != null ? apiKey : "");

                // Process attachments for Codex (images need to be saved as temp files)
                // Codex SDK requires local file paths, not base64 data
                JsonArray attachmentsArray = attachmentPayload.imageEntries;
                if (attachmentsArray.size() > 0) {
                    stdinInput.add("attachments", attachmentsArray);
                    LOG.info("[Codex] ✓ Prepared " + attachmentsArray.size() + " image attachment(s)");
                }

                String stdinJson = gson.toJson(stdinInput);

                List<String> command = new ArrayList<>();
                command.add(node);
                command.add(new File(bridgeDir, CHANNEL_SCRIPT).getAbsolutePath());
                command.add("codex");
                command.add("send");

                File processTempDir = processManager.prepareClaudeTempDir();

                ProcessBuilder pb = new ProcessBuilder(command);

                // Set working directory
                if (cwd != null && !cwd.isEmpty() && !"undefined".equals(cwd) && !"null".equals(cwd)) {
                    File userWorkDir = new File(cwd);
                    if (userWorkDir.exists() && userWorkDir.isDirectory()) {
                        pb.directory(userWorkDir);
                    } else {
                        pb.directory(bridgeDir);
                    }
                } else {
                    pb.directory(bridgeDir);
                }

                // Configure environment variables
                Map<String, String> env = pb.environment();
                envConfigurator.configureTempDir(env, processTempDir);
                env.put("CODEX_USE_STDIN", "true");
                envConfigurator.configureProjectPath(env, cwd);

                // Set model via environment variable if specified
                if (model != null && !model.isEmpty()) {
                    env.put("CODEX_MODEL", model);
                }

                // Override user's ~/.codex/config.toml sandbox and approval settings via environment variables
                if (permissionMode != null && !permissionMode.isEmpty()) {
                    String sandboxMode = resolveCodexSandboxMode(cwd);

                    switch (permissionMode) {
                        case "bypassPermissions":
                            env.put(ENV_CODEX_SANDBOX_MODE, sandboxMode);
                            env.put(ENV_CODEX_SANDBOX, sandboxMode);
                            env.put(ENV_CODEX_APPROVAL_POLICY, APPROVAL_POLICY_NEVER);
                            break;
                        case "acceptEdits":
                        case "autoEdit":
                            env.put(ENV_CODEX_SANDBOX_MODE, sandboxMode);
                            env.put(ENV_CODEX_SANDBOX, sandboxMode);
                            env.put(ENV_CODEX_APPROVAL_POLICY, APPROVAL_POLICY_ON_REQUEST);
                            break;
                        case "plan":
                            env.put(ENV_CODEX_SANDBOX_MODE, sandboxMode);
                            env.put(ENV_CODEX_SANDBOX, sandboxMode);
                            env.put(ENV_CODEX_APPROVAL_POLICY, APPROVAL_POLICY_UNTRUSTED);
                            break;
                        default:
                            // Default mode: use configured sandbox mode with confirmation
                            env.put(ENV_CODEX_SANDBOX_MODE, sandboxMode);
                            env.put(ENV_CODEX_SANDBOX, sandboxMode);
                            env.put(ENV_CODEX_APPROVAL_POLICY, APPROVAL_POLICY_UNTRUSTED);
                            break;
                    }
                    LOG.info("[Codex] Permission env override: SANDBOX_MODE=" +
                            env.get(ENV_CODEX_SANDBOX_MODE) + ", SANDBOX=" +
                            env.get(ENV_CODEX_SANDBOX) + ", APPROVAL_POLICY=" +
                            env.get(ENV_CODEX_APPROVAL_POLICY) + " (from permissionMode=" + permissionMode +
                            ")");
                }

                pb.redirectErrorStream(true);
                envConfigurator.updateProcessEnvironment(pb, node);
                envConfigurator.configureProjectScopedCodexHome(env, cwd);

                // Configure Codex-specific env vars from ~/.codex/config.toml
                envConfigurator.configureCodexEnv(env);
                LOG.info("[Codex] Final Node permission env snapshot: CODEX_SANDBOX_MODE=" +
                        env.get(ENV_CODEX_SANDBOX_MODE) + ", CODEX_SANDBOX=" +
                        env.get(ENV_CODEX_SANDBOX) + ", CODEX_CI=" + env.get(ENV_CODEX_CI) +
                        ", CODEX_SANDBOX_NETWORK_DISABLED=" + env.get(ENV_CODEX_SANDBOX_NETWORK_DISABLED) +
                        ", CLAUDE_SESSION_ID=" + env.get("CLAUDE_SESSION_ID") +
                        ", CLAUDE_PERMISSION_DIR=" + env.get("CLAUDE_PERMISSION_DIR"));

                LOG.info("Command: " + String.join(" ", command));

                Process process = null;
                try {
                    process = pb.start();
                    processManager.registerProcess(channelId, process);

                    // Write to stdin
                    try (java.io.OutputStream stdin = process.getOutputStream()) {
                        stdin.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                        stdin.flush();
                    } catch (Exception e) {
                        LOG.warn("Failed to write stdin: " + e.getMessage());
                    }

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                        String line;
                        while ((line = reader.readLine()) != null) {
                            // Capture Node.js error logs
                            if (line.startsWith("[UNCAUGHT_ERROR]")
                                    || line.startsWith("[UNHANDLED_REJECTION]")
                                    || line.startsWith("[COMMAND_ERROR]")) {
                                LOG.warn("[Node.js ERROR] " + line);
                                lastNodeError[0] = line;
                            }
                            processOutputLine(line, callback, result, assistantContent, hadSendError, lastNodeError);
                        }
                    }

                    if (!process.waitFor(5, TimeUnit.MINUTES)) {
                        process.destroyForcibly();
                    }

                    int exitCode = process.exitValue();
                    boolean wasInterrupted = processManager.wasInterrupted(channelId);

                    result.finalResult = assistantContent.toString();
                    result.messageCount = result.messages.size();

                    if (wasInterrupted) {
                        result.success = false;
                        result.error = "User interrupted";
                        callback.onComplete(result);
                    } else if (!hadSendError[0]) {
                        result.success = exitCode == 0;
                        if (result.success) {
                            callback.onComplete(result);
                        } else {
                            String errorMsg = "Codex process exited with code: " + exitCode;
                            if (lastNodeError[0] != null && !lastNodeError[0].isEmpty()) {
                                errorMsg = errorMsg + " | Last error: " + lastNodeError[0];
                            }
                            result.error = errorMsg;
                            callback.onError(errorMsg);
                        }
                    }

                    return result;
                } finally {
                    processManager.unregisterProcess(channelId, process);
                    processManager.waitForProcessTermination(process);
                    cleanupTempImages(tempUploadedFiles);  // Cleanup temp files
                }

            } catch (Exception e) {
                result.success = false;
                result.error = e.getMessage();
                callback.onError(e.getMessage());
                cleanupTempImages(tempUploadedFiles);  // Cleanup temp files on error
                return result;
            }
        });
    }

    /**
     * Get session history messages (Codex doesn't support this, returns empty list).
     */
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        LOG.info("getSessionMessages not supported by Codex SDK");
        return new ArrayList<>();
    }

    /**
     * Gets the connection status for the specified Codex MCP servers.
     */
    public CompletableFuture<List<JsonObject>> getMcpServerStatus(List<JsonObject> servers) {
        return CompletableFuture.supplyAsync(() -> {
            Process process = null;
            long startTime = System.currentTimeMillis();
            int serverCount = servers == null ? 0 : servers.size();
            LOG.info("[CodexMcpStatus] Starting getMcpServerStatus, servers=" + serverCount);

            try {
                String node = nodeDetector.findNodeExecutable();
                File bridgeDir = getDirectoryResolver().findSdkDir();
                if (bridgeDir == null || !bridgeDir.exists()) {
                    LOG.warn("[CodexMcpStatus] Bridge directory not ready");
                    return new ArrayList<>();
                }

                JsonObject stdinInput = new JsonObject();
                JsonArray serversJson = new JsonArray();
                if (servers != null) {
                    for (JsonObject server : servers) {
                        serversJson.add(server != null ? server : new JsonObject());
                    }
                }
                stdinInput.add("servers", serversJson);
                String stdinJson = gson.toJson(stdinInput);

                List<String> command = new ArrayList<>();
                command.add(node);
                command.add(new File(bridgeDir, CHANNEL_SCRIPT).getAbsolutePath());
                command.add("codex");
                command.add("getMcpServerStatus");

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(bridgeDir);
                pb.redirectErrorStream(true);
                envConfigurator.updateProcessEnvironment(pb, node);
                pb.environment().put("CODEX_USE_STDIN", "true");

                process = pb.start();
                processManager.registerProcess("__codex_mcp_status__", process);
                final Process finalProcess = process;

                try (java.io.OutputStream stdin = process.getOutputStream()) {
                    stdin.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }

                final String[] statusJson = {null};
                final StringBuilder output = new StringBuilder();
                final CountDownLatch latch = new CountDownLatch(1);

                Thread readerThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                            if (line.startsWith("[MCP_SERVER_STATUS]")) {
                                statusJson[0] = line.substring("[MCP_SERVER_STATUS]".length()).trim();
                                break;
                            }
                        }
                    } catch (Exception e) {
                        LOG.debug("[CodexMcpStatus] Reader thread exception: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
                readerThread.start();

                latch.await(MCP_STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                long elapsed = System.currentTimeMillis() - startTime;
                if (process.isAlive()) {
                    PlatformUtils.terminateProcess(process);
                }

                List<JsonObject> markerResult = parseMcpServerStatusResponse(statusJson[0]);
                if (markerResult != null) {
                    LOG.info("[CodexMcpStatus] Got status for " + markerResult.size() + " servers in " + elapsed + "ms");
                    return markerResult;
                }

                String outputStr = output.toString().trim();
                String jsonStr = extractLastJsonLine(outputStr);
                List<JsonObject> fallbackResult = parseMcpServerStatusResponse(jsonStr);
                if (fallbackResult != null) {
                    LOG.info("[CodexMcpStatus] Parsed fallback status for " + fallbackResult.size() + " servers in " + elapsed + "ms");
                    return fallbackResult;
                }

                LOG.warn("[CodexMcpStatus] Failed to parse MCP server status response");
                return new ArrayList<>();
            } catch (Exception e) {
                LOG.error("[CodexMcpStatus] Exception: " + e.getMessage(), e);
                return new ArrayList<>();
            } finally {
                if (process != null) {
                    try {
                        if (process.isAlive()) {
                            PlatformUtils.terminateProcess(process);
                        }
                    } finally {
                        processManager.unregisterProcess("__codex_mcp_status__", process);
                    }
                }
            }
        });
    }

    /**
     * Gets the tool list for the specified Codex MCP server.
     */
    public CompletableFuture<JsonObject> getMcpServerTools(String serverId, JsonObject serverConfig) {
        return CompletableFuture.supplyAsync(() -> {
            Process process = null;
            long startTime = System.currentTimeMillis();
            LOG.info("[CodexMcpTools] Starting getMcpServerTools, serverId=" + serverId);

            try {
                String node = nodeDetector.findNodeExecutable();
                File bridgeDir = getDirectoryResolver().findSdkDir();
                if (bridgeDir == null || !bridgeDir.exists()) {
                    JsonObject errorResult = new JsonObject();
                    errorResult.addProperty("serverId", serverId);
                    errorResult.addProperty("error", "Bridge directory not ready");
                    errorResult.add("tools", new JsonArray());
                    return errorResult;
                }

                JsonObject stdinInput = new JsonObject();
                stdinInput.addProperty("serverId", serverId != null ? serverId : "");
                if (serverConfig != null) {
                    stdinInput.add("serverConfig", serverConfig);
                } else {
                    stdinInput.add("serverConfig", new JsonObject());
                }
                String stdinJson = gson.toJson(stdinInput);

                List<String> command = new ArrayList<>();
                command.add(node);
                command.add(new File(bridgeDir, CHANNEL_SCRIPT).getAbsolutePath());
                command.add("codex");
                command.add("getMcpServerTools");

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(bridgeDir);
                pb.redirectErrorStream(true);
                envConfigurator.updateProcessEnvironment(pb, node);
                pb.environment().put("CODEX_USE_STDIN", "true");

                process = pb.start();
                processManager.registerProcess("__codex_mcp_tools__", process);
                final Process finalProcess = process;

                try (java.io.OutputStream stdin = process.getOutputStream()) {
                    stdin.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }

                final String[] toolsJson = {null};
                final StringBuilder output = new StringBuilder();
                final CountDownLatch toolsLatch = new CountDownLatch(1);

                Thread readerThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                            if (line.startsWith("[MCP_SERVER_TOOLS]")) {
                                toolsJson[0] = line.substring("[MCP_SERVER_TOOLS]".length()).trim();
                                break;
                            }
                        }
                    } catch (Exception e) {
                        LOG.debug("[CodexMcpTools] Reader thread exception: " + e.getMessage());
                    } finally {
                        toolsLatch.countDown();
                    }
                });
                readerThread.start();

                toolsLatch.await(MCP_TOOLS_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                long elapsed = System.currentTimeMillis() - startTime;
                if (process.isAlive()) {
                    PlatformUtils.terminateProcess(process);
                }

                if (toolsJson[0] != null && !toolsJson[0].isEmpty()) {
                    try {
                        JsonObject result = gson.fromJson(toolsJson[0], JsonObject.class);
                        LOG.info("[CodexMcpTools] Got tools for " + serverId + " in " + elapsed + "ms");
                        return result;
                    } catch (Exception e) {
                        LOG.warn("[CodexMcpTools] Failed to parse MCP tools JSON: " + e.getMessage());
                    }
                }

                String outputStr = output.toString().trim();
                String jsonStr = extractLastJsonLine(outputStr);
                if (jsonStr != null) {
                    try {
                        JsonObject jsonResult = gson.fromJson(jsonStr, JsonObject.class);
                        if (jsonResult != null && jsonResult.has("success")) {
                            return jsonResult;
                        }
                    } catch (Exception e) {
                        LOG.debug("[CodexMcpTools] Fallback JSON parse failed: " + e.getMessage());
                    }
                }

                JsonObject errorResult = new JsonObject();
                errorResult.addProperty("serverId", serverId);
                errorResult.addProperty("error", "Failed to get tools list");
                errorResult.add("tools", new JsonArray());
                return errorResult;
            } catch (Exception e) {
                LOG.error("[CodexMcpTools] Exception: " + e.getMessage(), e);
                JsonObject errorResult = new JsonObject();
                errorResult.addProperty("serverId", serverId);
                errorResult.addProperty("error", e.getMessage());
                errorResult.add("tools", new JsonArray());
                return errorResult;
            } finally {
                if (process != null) {
                    try {
                        if (process.isAlive()) {
                            PlatformUtils.terminateProcess(process);
                        }
                    } finally {
                        processManager.unregisterProcess("__codex_mcp_tools__", process);
                    }
                }
            }
        });
    }

    /**
     * Parse the Codex MCP status bridge response into a list of server status objects.
     */
    private List<JsonObject> parseMcpServerStatusResponse(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }

        try {
            JsonObject response = gson.fromJson(jsonStr, JsonObject.class);
            if (response != null && response.has("servers") && response.get("servers").isJsonArray()) {
                List<JsonObject> servers = new ArrayList<>();
                for (JsonElement server : response.getAsJsonArray("servers")) {
                    if (server != null && server.isJsonObject()) {
                        servers.add(server.getAsJsonObject());
                    }
                }
                return servers;
            }
        } catch (Exception e) {
            LOG.debug("[CodexMcpStatus] Object parse failed: " + e.getMessage());
        }

        try {
            JsonArray response = gson.fromJson(jsonStr, JsonArray.class);
            if (response != null) {
                List<JsonObject> servers = new ArrayList<>();
                for (JsonElement server : response) {
                    if (server != null && server.isJsonObject()) {
                        servers.add(server.getAsJsonObject());
                    }
                }
                return servers;
            }
        } catch (Exception e) {
            LOG.debug("[CodexMcpStatus] Array parse failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * Extracts the last JSON object text from multi-line output.
     */
    private String extractLastJsonLine(String outputStr) {
        if (outputStr == null || outputStr.isEmpty()) {
            return null;
        }
        String[] lines = outputStr.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        if (outputStr.startsWith("{") && outputStr.endsWith("}")) {
            return outputStr;
        }
        int jsonStart = outputStr.indexOf("{");
        if (jsonStart != -1) {
            return outputStr.substring(jsonStart);
        }
        return null;
    }

    // ============================================================================
    // Utility methods
    // ============================================================================

    private static final class CodexAttachmentPayload {
        private final JsonArray imageEntries = new JsonArray();
        private final List<String> stagedFilePaths = new ArrayList<>();
    }

    /**
     * Build Codex-compatible attachment payload.
     * - image/* attachments -> local_image entries consumed by Codex SDK
     * - non-image attachments -> staged local files whose paths are injected into user prompt
     */
    private CodexAttachmentPayload buildCodexAttachmentPayload(List<ClaudeSession.Attachment> attachments, List<File> tempFiles) {
        CodexAttachmentPayload payload = new CodexAttachmentPayload();

        if (attachments == null || attachments.isEmpty()) {
            return payload;
        }

        File imageTempDir = new File(System.getProperty("java.io.tmpdir"), "codex-images");
        if (!imageTempDir.exists()) {
            imageTempDir.mkdirs();
        }

        File fileTempDir = new File(System.getProperty("java.io.tmpdir"), "codex-files");
        if (!fileTempDir.exists()) {
            fileTempDir.mkdirs();
        }

        for (ClaudeSession.Attachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }

            String mediaType = attachment.mediaType != null ? attachment.mediaType : "";
            String data = attachment.data;
            if (data == null || data.isEmpty()) {
                LOG.debug("[Codex] Skipping attachment without payload data: " + attachment.fileName);
                continue;
            }

            try {
                if (mediaType.startsWith("image/")) {
                    String extension = getImageExtension(mediaType);
                    String filename = "codex-img-" + System.currentTimeMillis() + "-" +
                            java.util.UUID.randomUUID().toString().substring(0, 8) + extension;
                    File imageFile = new File(imageTempDir, filename);
                    byte[] imageBytes = Base64.getDecoder().decode(data);
                    try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                        fos.write(imageBytes);
                    }

                    imageFile.deleteOnExit();
                    if (tempFiles != null) {
                        tempFiles.add(imageFile);
                    }

                    JsonObject imageEntry = new JsonObject();
                    imageEntry.addProperty("type", "local_image");
                    imageEntry.addProperty("path", imageFile.getAbsolutePath());
                    payload.imageEntries.add(imageEntry);
                    LOG.info("[Codex] Saved temp image: " + imageFile.getAbsolutePath()
                            + " (" + imageBytes.length + " bytes, will auto-delete)");
                } else {
                    String extension = getAttachmentExtension(attachment.fileName);
                    String filename = "codex-file-" + System.currentTimeMillis() + "-" +
                            java.util.UUID.randomUUID().toString().substring(0, 8) + extension;
                    File stagedFile = new File(fileTempDir, filename);
                    byte[] fileBytes = Base64.getDecoder().decode(data);
                    try (FileOutputStream fos = new FileOutputStream(stagedFile)) {
                        fos.write(fileBytes);
                    }

                    stagedFile.deleteOnExit();
                    if (tempFiles != null) {
                        tempFiles.add(stagedFile);
                    }
                    payload.stagedFilePaths.add(stagedFile.getAbsolutePath());
                    LOG.info("[Codex] Staged non-image attachment: " + stagedFile.getAbsolutePath()
                            + " (" + fileBytes.length + " bytes)");
                }
            } catch (Exception e) {
                LOG.warn("[Codex] Failed to process attachment: " + e.getMessage());
            }
        }

        return payload;
    }

    private String appendUploadedFilesContext(String message, List<String> stagedFilePaths) {
        if (stagedFilePaths == null || stagedFilePaths.isEmpty()) {
            return message;
        }
        String safeMessage = message != null ? message : "";
        StringBuilder sb = new StringBuilder(safeMessage);
        sb.append("\n\n## Uploaded Files\n");
        sb.append("The user uploaded non-image files. They are available at these local paths:\n");
        for (String path : stagedFilePaths) {
            sb.append("- `").append(path).append("`\n");
        }
        sb.append("\nPlease read these files directly using available tools before answering.\n");
        return sb.toString();
    }

    /**
     * Cleanup temporary image files after message send.
     */
    private void cleanupTempImages(List<File> tempFiles) {
        if (tempFiles == null || tempFiles.isEmpty()) {
            return;
        }
        for (File file : tempFiles) {
            try {
                if (file.exists() && file.delete()) {
                    LOG.debug("[Codex] Cleaned up temp image: " + file.getName());
                }
            } catch (Exception e) {
                LOG.debug("[Codex] Failed to cleanup temp image: " + e.getMessage());
            }
        }
    }

    /**
     * Get file extension from MIME type.
     */
    private String getImageExtension(String mimeType) {
        if (mimeType == null) return ".png";

        switch (mimeType.toLowerCase()) {
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            case "image/bmp":
                return ".bmp";
            case "image/svg+xml":
                return ".svg";
            case "image/png":
            default:
                return ".png";
        }
    }

    private String getAttachmentExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return ".bin";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx <= 0 || idx == fileName.length() - 1) {
            return ".bin";
        }
        String ext = fileName.substring(idx);
        if (ext.length() > 15) {
            return ".bin";
        }
        return ext;
    }

    private String extractAssistantText(JsonObject msg) {
        if (msg == null) return "";
        if (!msg.has("message") || !msg.get("message").isJsonObject()) return "";

        JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) return "";

        JsonElement contentEl = message.get("content");
        if (contentEl.isJsonPrimitive()) {
            return contentEl.getAsString();
        }
        if (!contentEl.isJsonArray()) {
            return "";
        }

        JsonArray arr = contentEl.getAsJsonArray();
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            if (!block.has("type") || block.get("type").isJsonNull()) continue;
            String type = block.get("type").getAsString();
            if ("text".equals(type) && block.has("text") && !block.get("text").isJsonNull()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(block.get("text").getAsString());
            }
        }
        return sb.toString();
    }
    /**
     * Resolve effective Codex sandbox mode from user settings.
     * Falls back to platform defaults when settings are unavailable or invalid.
     */
    private String resolveCodexSandboxMode(String cwd) {
        // Default to workspace-write (safer) on non-Windows; Windows sandbox is
        // experimental so danger-full-access is used there as a platform fallback.
        String defaultMode = PlatformUtils.isWindows()
                ? SANDBOX_MODE_DANGER_FULL_ACCESS
                : SANDBOX_MODE_WORKSPACE_WRITE;

        try {
            CodemossSettingsService settingsService = new CodemossSettingsService();
            String configured = settingsService.getCodexSandboxMode(cwd);
            if (SANDBOX_MODE_WORKSPACE_WRITE.equals(configured) || SANDBOX_MODE_DANGER_FULL_ACCESS.equals(configured)) {
                return configured;
            }
        } catch (Exception e) {
            LOG.warn("[Codex] Failed to read Codex sandbox mode config: " + e.getMessage());
        }
        return defaultMode;
    }
}
