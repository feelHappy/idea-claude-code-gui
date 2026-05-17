package com.github.claudecodegui.debate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * @description 辩论会话状态模型
 * @author zyl
 * @date 2026/05/14
 */
public class DebateSession {

    public enum State {
        IDLE,
        STARTING,
        AWAITING_CLAUDE,
        AWAITING_CODEX,
        CONSENSUS,
        MAX_ROUNDS_REACHED,
        STOPPED,
        ERROR
    }

    private final String debateId;
    private String topic;
    private String description;
    private State state;
    private int currentRound;
    private String currentCorrelationId;
    private DebateConfig config;
    private Path debateFilePath;
    private LocalDateTime createdAt;
    private String lastError;

    public DebateSession(String topic, String description, DebateConfig config) {
        this.debateId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.topic = topic;
        this.description = description;
        this.config = config;
        this.state = State.IDLE;
        this.currentRound = 0;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return state == State.STARTING
                || state == State.AWAITING_CLAUDE
                || state == State.AWAITING_CODEX;
    }

    public boolean isTerminal() {
        return state == State.CONSENSUS
                || state == State.MAX_ROUNDS_REACHED
                || state == State.STOPPED
                || state == State.ERROR;
    }

    public String getTopic() { return topic; }
    public String getDescription() { return description; }
    public String getDebateId() { return debateId; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
    public String getCurrentCorrelationId() { return currentCorrelationId; }

    /**
     * 为当前轮次生成新的 correlationId，格式：debateId-round-participant-uuid8
     */
    public String newCorrelationId(int round, String participant) {
        this.currentCorrelationId = debateId + "-r" + round + "-" + participant.toLowerCase()
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        return this.currentCorrelationId;
    }

    public DebateConfig getConfig() { return config; }
    public Path getDebateFilePath() { return debateFilePath; }
    public void setDebateFilePath(Path debateFilePath) { this.debateFilePath = debateFilePath; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
