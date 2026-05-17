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
 * 辩论编排服务。管理 Claude 和 Codex 之间的交替对抗性讨论。
 * Claude 作为方案发起者/捍卫者，Codex 作为挑战者/评审者。
 * 前 N 轮强制深入讨论，之后才允许达成共识。
 *
 * @author zyl
 * @date 2026/05/14
 */
public class DebateService implements Disposable {

    private static final Logger LOG = Logger.getInstance(DebateService.class);

    private static final int DEBATE_MAX_TURNS = 3;

    private static final String DEBATE_SYSTEM_APPEND =
            "你是一位资深技术专家，正在参与一场技术方案辩论。\n\n" +
            "## 你的角色\n" +
            "- 你是一位纯粹的技术分析师，基于提供的文本信息进行深度分析\n" +
            "- 你没有任何工具可用，也不需要工具\n" +
            "- 你的所有分析都基于方案描述和讨论记录\n\n" +
            "## 输出要求\n" +
            "- 直接输出你的完整分析内容，包括论点、论据和结论\n" +
            "- 每个论点都要有具体理由和替代方案\n" +
            "- 不要输出空内容或仅输出标题\n" +
            "- 不要尝试调用任何工具或函数\n" +
            "- 用中文回答";

    private final ClaudeSDKBridge claudeBridge;
    private final CodexSDKBridge codexBridge;
    private final DebateFileManager fileManager;
    private final DebatePromptBuilder promptBuilder;
    private final DebateResponseSanitizer sanitizer;
    private final AtomicBoolean active = new AtomicBoolean(false);

    private volatile DebateSession currentSession;
    private volatile CompletableFuture<?> currentTurnFuture;
    private BiConsumer<DebateSession.State, String> stateListener;
    private BiConsumer<String, String> roundListener;
    private BiConsumer<String, String> streamListener;

    private String claudeChannelId;
    private String claudeSessionId;
    private String codexChannelId;
    private String codexThreadId;
    private String cwd;
    private String claudeModel;
    private String claudePermissionMode;
    private String codexModel;
    private String codexPermissionMode;
    private String codexReasoningEffort;

    public DebateService(ClaudeSDKBridge claudeBridge,
                         CodexSDKBridge codexBridge,
                         String projectBasePath) {
        this.claudeBridge = claudeBridge;
        this.codexBridge = codexBridge;
        this.fileManager = new DebateFileManager(projectBasePath);
        this.promptBuilder = new DebatePromptBuilder();
        this.sanitizer = new DebateResponseSanitizer();
    }

    public void setStateListener(BiConsumer<DebateSession.State, String> listener) {
        this.stateListener = listener;
    }

    public void setRoundListener(BiConsumer<String, String> listener) {
        this.roundListener = listener;
    }

    public void setStreamListener(BiConsumer<String, String> listener) {
        this.streamListener = listener;
    }

    public void setClaudeSession(String channelId, String sessionId, String cwd,
                                   String model, String permissionMode) {
        this.claudeChannelId = channelId;
        this.claudeSessionId = sessionId;
        this.cwd = cwd;
        this.claudeModel = model;
        this.claudePermissionMode = permissionMode;
    }

    public void setCodexSession(String channelId, String threadId,
                                String model, String permissionMode, String reasoningEffort) {
        this.codexChannelId = channelId;
        this.codexThreadId = threadId;
        this.codexModel = model;
        this.codexPermissionMode = permissionMode;
        this.codexReasoningEffort = reasoningEffort;
    }

    public boolean isActive() {
        return active.get();
    }

    public DebateSession getCurrentSession() {
        return currentSession;
    }

    public void startDebate(String topic, String description, DebateConfig config) {
        if (!active.compareAndSet(false, true)) {
            notifyState(DebateSession.State.ERROR, "已有辩论正在进行中。");
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
                transitionToError("创建辩论文件失败: " + e.getMessage());
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
        String correlationId = currentSession.newCorrelationId(round, "claude");
        notifyState(DebateSession.State.AWAITING_CLAUDE, "Round " + round);

        String prompt;
        if (round == 1) {
            prompt = promptBuilder.buildFirstTurnPrompt(currentSession);
        } else {
            try {
                String discussion = fileManager.readFile(currentSession.getDebateFilePath());
                prompt = promptBuilder.buildResponsePrompt(currentSession, discussion, round, true);
            } catch (Exception e) {
                transitionToError("读取辩论文件失败: " + e.getMessage());
                return;
            }
        }

        StringBuilder responseCollector = new StringBuilder();
        AtomicBoolean turnCompleted = new AtomicBoolean(false);
        currentTurnFuture = claudeBridge.sendMessage(
                claudeChannelId, prompt, claudeSessionId, null, cwd,
                Collections.emptyList(), claudePermissionMode, claudeModel, null,
                DEBATE_SYSTEM_APPEND, null, Boolean.TRUE, null, DEBATE_MAX_TURNS, true,
                new MessageCallback() {
                    @Override
                    public void onMessage(String type, String content) {
                        if (turnCompleted.get()) {
                            return;
                        }
                        if ("content".equals(type) || "content_delta".equals(type)
                                || "thinking".equals(type) || "thinking_delta".equals(type)) {
                            responseCollector.append(content);
                            if ("content".equals(type) || "content_delta".equals(type)) {
                                notifyStream("Claude", content);
                            }
                        } else if ("message_end".equals(type) && responseCollector.length() > 0) {
                            completeClaudeTurn(turnCompleted, responseCollector, round, null);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        failTurn(turnCompleted, "Claude 错误: " + error);
                    }

                    @Override
                    public void onComplete(SDKResult result) {
                        completeClaudeTurn(turnCompleted, responseCollector, round, result);
                    }
                }
        ).orTimeout(currentSession.getConfig().getTurnTimeoutMs(), TimeUnit.MILLISECONDS)
         .exceptionally(ex -> {
             completeOrFailTimedOutTurn("Claude", turnCompleted, responseCollector, round, ex);
             return null;
         });
    }

    private void handleClaudeResponse(String response, int round) {
        try {
            String visibleText = sanitizeAndStore("Claude", round, response);
            notifyRound("Claude", visibleText);

            if (round > DebatePromptBuilder.MIN_ROUNDS_BEFORE_CONSENSUS
                    && promptBuilder.detectConsensus(response)) {
                finishWithConsensus("Claude", round, response);
                return;
            }

            executeCodexTurn(round);
        } catch (Exception e) {
            transitionToError("写入 Claude 回复失败: " + e.getMessage());
        }
    }

    private void executeCodexTurn(int round) {
        if (!active.get()) return;

        currentSession.setState(DebateSession.State.AWAITING_CODEX);
        String correlationId = currentSession.newCorrelationId(round, "codex");
        notifyState(DebateSession.State.AWAITING_CODEX, "Round " + round);

        String discussion;
        try {
            discussion = fileManager.readFile(currentSession.getDebateFilePath());
        } catch (Exception e) {
            transitionToError("读取辩论文件失败: " + e.getMessage());
            return;
        }

        String prompt = promptBuilder.buildResponsePrompt(currentSession, discussion, round, false);
        StringBuilder responseCollector = new StringBuilder();
        AtomicBoolean turnCompleted = new AtomicBoolean(false);

        currentTurnFuture = codexBridge.sendMessage(
                codexChannelId, prompt, codexThreadId, cwd,
                Collections.emptyList(), codexPermissionMode, codexModel, null, codexReasoningEffort,
                new MessageCallback() {
                    @Override
                    public void onMessage(String type, String content) {
                        if (turnCompleted.get()) {
                            return;
                        }
                        if ("content".equals(type) || "content_delta".equals(type)) {
                            responseCollector.append(content);
                            notifyStream("Codex", content);
                        } else if ("message_end".equals(type) && responseCollector.length() > 0) {
                            completeCodexTurn(turnCompleted, responseCollector, round, null);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        failTurn(turnCompleted, "Codex 错误: " + error);
                    }

                    @Override
                    public void onComplete(SDKResult result) {
                        completeCodexTurn(turnCompleted, responseCollector, round, result);
                    }
                }
        ).orTimeout(currentSession.getConfig().getTurnTimeoutMs(), TimeUnit.MILLISECONDS)
         .exceptionally(ex -> {
             completeOrFailTimedOutTurn("Codex", turnCompleted, responseCollector, round, ex);
             return null;
         });
    }

    private void handleCodexResponse(String response, int round) {
        try {
            String visibleText = sanitizeAndStore("Codex", round, response);
            notifyRound("Codex", visibleText);

            if (round > DebatePromptBuilder.MIN_ROUNDS_BEFORE_CONSENSUS
                    && promptBuilder.detectConsensus(response)) {
                finishWithConsensus("Codex", round, response);
                return;
            }

            if (round >= currentSession.getConfig().getMaxRounds()) {
                currentSession.setState(DebateSession.State.MAX_ROUNDS_REACHED);
                active.set(false);
                fileManager.updateStatus(currentSession.getDebateFilePath(), "max_rounds_reached");
                notifyState(DebateSession.State.MAX_ROUNDS_REACHED,
                        "已达最大轮次 " + round + " 轮，未达成共识。");
                return;
            }

            executeClaudeTurn();
        } catch (Exception e) {
            transitionToError("写入 Codex 回复失败: " + e.getMessage());
        }
    }

    /**
     * 净化响应并存储。严重泄露时标记该轮失败但不中断辩论。
     *
     * @return 净化后的 visibleText
     */
    private String sanitizeAndStore(String provider, int round, String rawResponse) throws Exception {
        String visibleText;
        if (sanitizer.isSevereLeakage(rawResponse)) {
            LOG.warn("[DebateService] Severe orchestration leakage detected in " + provider
                    + " round " + round + ", sanitizing");
            visibleText = sanitizer.sanitize(rawResponse);
        } else if (sanitizer.detectLeakage(rawResponse) > 0) {
            visibleText = sanitizer.sanitize(rawResponse);
        } else {
            visibleText = rawResponse;
        }

        fileManager.appendRoundEntry(currentSession.getDebateFilePath(), provider, round,
                visibleText, rawResponse);
        return visibleText;
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

        notifyState(DebateSession.State.CONSENSUS, agreedBy + " 在第 " + round + " 轮达成共识");
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

    private void completeClaudeTurn(AtomicBoolean turnCompleted, StringBuilder responseCollector,
                                    int round, SDKResult result) {
        if (!active.get() || !turnCompleted.compareAndSet(false, true)) {
            return;
        }
        handleClaudeResponse(collectResponse(result, responseCollector), round);
    }

    private void completeCodexTurn(AtomicBoolean turnCompleted, StringBuilder responseCollector,
                                   int round, SDKResult result) {
        if (!active.get() || !turnCompleted.compareAndSet(false, true)) {
            return;
        }
        handleCodexResponse(collectResponse(result, responseCollector), round);
    }

    private void failTurn(AtomicBoolean turnCompleted, String error) {
        if (active.get() && turnCompleted.compareAndSet(false, true)) {
            transitionToError(error);
        }
    }

    private void completeOrFailTimedOutTurn(String provider, AtomicBoolean turnCompleted,
                                            StringBuilder responseCollector, int round, Throwable ex) {
        if (responseCollector.length() > 0) {
            LOG.warn("[DebateService] " + provider + " future 超时但已有输出；"
                    + "使用已收集的响应作为第 " + round + " 轮结果");
            if ("Claude".equals(provider)) {
                completeClaudeTurn(turnCompleted, responseCollector, round, null);
            } else {
                completeCodexTurn(turnCompleted, responseCollector, round, null);
            }
            return;
        }
        failTurn(turnCompleted, buildTimeoutMessage(provider, ex));
    }

    private String collectResponse(SDKResult result, StringBuilder responseCollector) {
        if (result != null && result.finalResult != null && !result.finalResult.isEmpty()) {
            return result.finalResult;
        }
        return responseCollector.toString();
    }

    private String buildTimeoutMessage(String provider, Throwable ex) {
        String detail = null;
        if (ex != null) {
            detail = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        }
        if (detail != null && !detail.trim().isEmpty()) {
            return provider + " 超时: " + detail;
        }
        long seconds = currentSession != null
                ? TimeUnit.MILLISECONDS.toSeconds(currentSession.getConfig().getTurnTimeoutMs())
                : 0;
        if (seconds > 0) {
            return provider + " 超时（" + seconds + " 秒）";
        }
        return provider + " 超时";
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

    private void notifyStream(String provider, String delta) {
        if (streamListener != null) {
            streamListener.accept(provider, delta);
        }
    }

    @Override
    public void dispose() {
        stopDebate();
    }
}
