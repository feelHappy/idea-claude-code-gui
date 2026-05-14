package com.github.claudecodegui.debate;

import java.nio.file.Path;
import java.time.LocalDateTime;

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

    private String topic;
    private String description;
    private State state;
    private int currentRound;
    private DebateConfig config;
    private Path debateFilePath;
    private LocalDateTime createdAt;
    private String lastError;

    public DebateSession(String topic, String description, DebateConfig config) {
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
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
    public DebateConfig getConfig() { return config; }
    public Path getDebateFilePath() { return debateFilePath; }
    public void setDebateFilePath(Path debateFilePath) { this.debateFilePath = debateFilePath; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
