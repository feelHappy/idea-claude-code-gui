package com.github.claudecodegui.debate;

/**
 * 辩论配置参数
 *
 * @author zyl
 * @date 2026/05/14
 */
public class DebateConfig {

    /** 默认最大轮次 */
    public static final int DEFAULT_MAX_ROUNDS = 5;
    /** 默认单轮超时时间（毫秒） */
    public static final long DEFAULT_TURN_TIMEOUT_MS = 10 * 60 * 1000L;
    /** 辩论记录存储目录 */
    public static final String DEBATES_DIR = ".codemoss/debates";

    private int maxRounds;
    private long turnTimeoutMs;

    public DebateConfig() {
        this(DEFAULT_MAX_ROUNDS, DEFAULT_TURN_TIMEOUT_MS);
    }

    public DebateConfig(int maxRounds, long turnTimeoutMs) {
        this.maxRounds = maxRounds;
        this.turnTimeoutMs = turnTimeoutMs;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public long getTurnTimeoutMs() {
        return turnTimeoutMs;
    }

    public void setTurnTimeoutMs(long turnTimeoutMs) {
        this.turnTimeoutMs = turnTimeoutMs;
    }
}
