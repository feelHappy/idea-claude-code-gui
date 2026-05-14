package com.github.claudecodegui.debate;

/**
 * @description 为辩论中的每个 provider 构建 prompt。
 * Provider 不知道自己在"辩论"——只收到一个包含上下文的普通 prompt。
 * @author zyl
 * @date 2026/05/14
 */
public class DebatePromptBuilder {

    private static final String FIRST_TURN_TEMPLATE =
            "I'm debugging a problem and need your analysis.\n\n" +
            "## Problem\n\n%s\n\n" +
            "Please analyze this problem, identify the root cause, and propose a concrete solution with code if applicable.";

    private static final String RESPONSE_TURN_TEMPLATE =
            "I'm debugging a problem with input from another engineer. " +
            "Please review the discussion so far and provide your perspective.\n\n" +
            "## Problem\n\n%s\n\n" +
            "## Discussion So Far\n\n%s\n\n" +
            "Please evaluate the previous analysis. If you agree with the proposed solution, " +
            "start your response with [CONSENSUS] and then summarize the final agreed solution. " +
            "If you disagree or see issues, start with [DISAGREE] and explain why, then propose a better approach.";

    public String buildFirstTurnPrompt(DebateSession session) {
        return String.format(FIRST_TURN_TEMPLATE, session.getDescription());
    }

    public String buildResponsePrompt(DebateSession session, String discussionSoFar) {
        return String.format(RESPONSE_TURN_TEMPLATE, session.getDescription(), discussionSoFar);
    }

    public boolean detectConsensus(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        // Check for structured marker in the first 150 characters
        String head = response.substring(0, Math.min(response.length(), 150)).toUpperCase();
        return head.contains("[CONSENSUS]");
    }

    public String extractConsensusSummary(String response) {
        String[] lines = response.split("\n");
        StringBuilder summary = new StringBuilder();
        boolean capturing = false;
        for (String line : lines) {
            if (line.toLowerCase().contains("summary")
                    || line.toLowerCase().contains("总结")
                    || line.toLowerCase().contains("final solution")
                    || line.toLowerCase().contains("最终方案")
                    || capturing) {
                capturing = true;
                summary.append(line).append("\n");
            }
        }
        if (summary.length() == 0) {
            int maxLen = Math.min(response.length(), 500);
            return response.substring(0, maxLen);
        }
        return summary.toString().trim();
    }
}
