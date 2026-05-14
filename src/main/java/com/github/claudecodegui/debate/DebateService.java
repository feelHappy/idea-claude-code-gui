package com.github.claudecodegui.debate;

import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.ClaudeSession;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * @description 辩论编排服务。管理 Claude 和 Codex 之间的交替讨论。
 * 单窗口模式：在后台分别调用两个 provider 的 API，收集响应后写入共享文件。
 * @author zyl
 * @date 2026/05/14
 */
public class DebateService implements Disposable {

    private static final Logger LOG = Logger.getInstance(DebateService.class);

    private final ClaudeSDKBridge claudeBridge;
    private final CodexSDKBridge codexBridge;
    private final DebateFileManager fileManager;
    private final DebatePromptBuilder promptBuilder;
    private final AtomicBoolean active = new AtomicBoolean(false);

    private volatile DebateSession currentSession;
    private volatile CompletableFuture<?> currentTurnFuture;
    private BiConsumer<DebateSession.State, String> stateListener;
    private BiConsumer<String, String> roundListener;

    private String claudeChannelId;
    private String claudeSessionId;
    private String codexChannelId;
    private String codexThreadId;
    private String cwd;

    public DebateService(ClaudeSDKBridge claudeBridge,
                         CodexSDKBridge codexBridge,
                         String projectBasePath) {
        this.claudeBridge = claudeBridge;
        this.codexBridge = codexBridge;
        this.fileManager = new DebateFileManager(projectBasePath);
        this.promptBuilder = new DebatePromptBuilder();
    }

    public void setStateListener(BiConsumer<DebateSession.State, String> listener) {
        this.stateListener = listener;
    }

    public void setRoundListener(BiConsumer<String, String> listener) {
        this.roundListener = listener;
    }

    public void setClaudeSession(String channelId, String sessionId, String cwd) {
        this.claudeChannelId = channelId;
        this.claudeSessionId = sessionId;
        this.cwd = cwd;
    }

    public void setCodexSession(String channelId, String threadId) {
        this.codexChannelId = channelId;
        this.codexThreadId = threadId;
    }

    public boolean isActive() {
        return active.get();
    }

    public DebateSession getCurrentSession() {
        return currentSession;
    }

    public void startDebate(String topic, String description, DebateConfig config) {
        if (!active.compareAndSet(false, true)) {
            notifyState(DebateSession.State.ERROR, "A debate is already in progress.");
            return;
        }

        currentSession = new DebateSession(topic, description, config);
        currentSession.setState(DebateSession.State.STARTING);
        notifyState(DebateSession.State.STARTING, null);

        CompletableFuture.runAsync(() -> {
            try {
                Path filePath = fileManager.createDebateFile(currentSession);
                currentSession.setDebateFilePath(filePath);
                executeClaudeTurn();
            } catch (Exception e) {
                LOG.error("[DebateService] Failed to start debate", e);
                transitionToError("Failed to create debate file: " + e.getMessage());
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    public void stopDebate() {
        if (currentSession == null || !active.get()) return;

        currentSession.setState(DebateSession.State.STOPPED);
        active.set(false);

        if (currentTurnFuture != null) {
            currentTurnFuture.cancel(true);
        }

        try {
            fileManager.updateStatus(currentSession.getDebateFilePath(), "stopped");
        } catch (Exception e) {
            LOG.warn("[DebateService] Failed to update status on stop", e);
        }

        notifyState(DebateSession.State.STOPPED, null);
    }

    private void executeClaudeTurn() {
        if (!active.get()) return;

        int round = currentSession.getCurrentRound() + 1;
        currentSession.setCurrentRound(round);
        currentSession.setState(DebateSession.State.AWAITING_CLAUDE);
        notifyState(DebateSession.State.AWAITING_CLAUDE, "Round " + round);

        String prompt;
        if (round == 1) {
            prompt = promptBuilder.buildFirstTurnPrompt(currentSession);
        } else {
            try {
                String discussion = fileManager.readFile(currentSession.getDebateFilePath());
                prompt = promptBuilder.buildResponsePrompt(currentSession, discussion);
            } catch (Exception e) {
                transitionToError("Failed to read debate file: " + e.getMessage());
                return;
            }
        }

        StringBuilder responseCollector = new StringBuilder();
        currentTurnFuture = claudeBridge.sendMessage(
                claudeChannelId, prompt, claudeSessionId, cwd,
                Collections.emptyList(), new MessageCallback() {
                    @Override
                    public void onMessage(String type, String content) {
                        if ("content".equals(type) || "content_delta".equals(type)) {
                            responseCollector.append(content);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        transitionToError("Claude error: " + error);
                    }

                    @Override
                    public void onComplete(SDKResult result) {
                        if (!active.get()) return;
                        String response = result.rawOutput != null ? result.rawOutput : responseCollector.toString();
                        handleClaudeResponse(response, currentSession.getCurrentRound());
                    }
                }
        ).orTimeout(currentSession.getConfig().getTurnTimeoutMs(), TimeUnit.MILLISECONDS)
         .exceptionally(ex -> {
             if (active.get()) {
                 transitionToError("Claude timed out: " + ex.getMessage());
             }
             return null;
         });
    }

    private void handleClaudeResponse(String response, int round) {
        try {
            fileManager.appendRoundEntry(currentSession.getDebateFilePath(), "Claude", round, response);
            notifyRound("Claude", response);

            if (promptBuilder.detectConsensus(response) && round > 1) {
                finishWithConsensus("Claude", round, response);
                return;
            }

            executeCodexTurn(round);
        } catch (Exception e) {
            transitionToError("Failed to write Claude response: " + e.getMessage());
        }
    }

    private void executeCodexTurn(int round) {
        if (!active.get()) return;

        currentSession.setState(DebateSession.State.AWAITING_CODEX);
        notifyState(DebateSession.State.AWAITING_CODEX, "Round " + round);

        String discussion;
        try {
            discussion = fileManager.readFile(currentSession.getDebateFilePath());
        } catch (Exception e) {
            transitionToError("Failed to read debate file: " + e.getMessage());
            return;
        }

        String prompt = promptBuilder.buildResponsePrompt(currentSession, discussion);
        StringBuilder responseCollector = new StringBuilder();

        currentTurnFuture = codexBridge.sendMessage(
                codexChannelId, prompt, codexThreadId, cwd,
                Collections.emptyList(), null, null, null, null,
                new MessageCallback() {
                    @Override
                    public void onMessage(String type, String content) {
                        if ("content".equals(type) || "content_delta".equals(type)) {
                            responseCollector.append(content);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        transitionToError("Codex error: " + error);
                    }

                    @Override
                    public void onComplete(SDKResult result) {
                        if (!active.get()) return;
                        String response = result.rawOutput != null ? result.rawOutput : responseCollector.toString();
                        handleCodexResponse(response, round);
                    }
                }
        ).orTimeout(currentSession.getConfig().getTurnTimeoutMs(), TimeUnit.MILLISECONDS)
         .exceptionally(ex -> {
             if (active.get()) {
                 transitionToError("Codex timed out: " + ex.getMessage());
             }
             return null;
         });
    }

    private void handleCodexResponse(String response, int round) {
        try {
            fileManager.appendRoundEntry(currentSession.getDebateFilePath(), "Codex", round, response);
            notifyRound("Codex", response);

            if (promptBuilder.detectConsensus(response)) {
                finishWithConsensus("Codex", round, response);
                return;
            }

            if (round >= currentSession.getConfig().getMaxRounds()) {
                currentSession.setState(DebateSession.State.MAX_ROUNDS_REACHED);
                active.set(false);
                fileManager.updateStatus(currentSession.getDebateFilePath(), "max_rounds_reached");
                notifyState(DebateSession.State.MAX_ROUNDS_REACHED, "Reached " + round + " rounds without consensus.");
                return;
            }

            executeClaudeTurn();
        } catch (Exception e) {
            transitionToError("Failed to write Codex response: " + e.getMessage());
        }
    }

    private void finishWithConsensus(String agreedBy, int round, String response) {
        currentSession.setState(DebateSession.State.CONSENSUS);
        active.set(false);

        String summary = promptBuilder.extractConsensusSummary(response);
        try {
            fileManager.writeConsensus(currentSession.getDebateFilePath(), agreedBy, round, summary);
        } catch (Exception e) {
            LOG.warn("[DebateService] Failed to write consensus", e);
        }

        notifyState(DebateSession.State.CONSENSUS, "Consensus reached by " + agreedBy + " at round " + round);
    }

    private void transitionToError(String error) {
        if (currentSession != null) {
            currentSession.setState(DebateSession.State.ERROR);
            currentSession.setLastError(error);
        }
        active.set(false);

        try {
            if (currentSession != null && currentSession.getDebateFilePath() != null) {
                fileManager.updateStatus(currentSession.getDebateFilePath(), "error");
            }
        } catch (Exception ignored) {
        }

        LOG.error("[DebateService] " + error);
        notifyState(DebateSession.State.ERROR, error);
    }

    private void notifyState(DebateSession.State state, String message) {
        if (stateListener != null) {
            stateListener.accept(state, message);
        }
    }

    private void notifyRound(String provider, String content) {
        if (roundListener != null) {
            roundListener.accept(provider, content);
        }
    }

    @Override
    public void dispose() {
        stopDebate();
    }
}
