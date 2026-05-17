package com.github.claudecodegui.debate;

/**
 * 辩论 Prompt 构建器。
 * 为辩论双方构建差异化的对抗性 prompt，确保深入讨论而非快速妥协。
 *
 * @author zyl
 * @date 2026/05/14
 */
public class DebatePromptBuilder {

    /**
     * 最少辩论轮次：在此之前禁止达成共识
     */
    public static final int MIN_ROUNDS_BEFORE_CONSENSUS = 3;

    private static final String FIRST_TURN_TEMPLATE =
            "你是一位资深技术专家，正在对一个技术方案进行深度分析。\n\n" +
            "## 待讨论的方案\n\n%s\n\n" +
            "## 你的任务\n\n" +
            "给出你的完整方案分析，至少包含以下内容（每项至少 2-3 句话）：\n" +
            "1. 核心思路评价\n" +
            "2. 实现路径是否合理\n" +
            "3. 潜在风险和薄弱点（至少列出 3 个）\n" +
            "4. 关键决策点和取舍\n" +
            "5. 改进建议\n\n" +
            "## 关键约束\n\n" +
            "- 你必须在本次回复中直接输出完整分析文本，不少于 500 字\n" +
            "- 不要只输出标题或大纲，必须包含具体论述\n" +
            "- 不要调用任何工具（Read、Bash、Grep 等），不要读取任何文件\n" +
            "- 不要使用 extended thinking，直接在回复中写出你的分析\n" +
            "- 仅基于上述方案描述进行分析，现在就开始输出你的分析：";

    private static final String CHALLENGER_TEMPLATE =
            "你是一位严谨的技术评审专家，你的职责是找出方案中的漏洞和不足。\n" +
            "你不是来附和的——你必须批判性地审视每一个论点。\n\n" +
            "## 原始方案\n\n%s\n\n" +
            "## 当前讨论记录\n\n%s\n\n" +
            "## 你的任务\n\n" +
            "- 找出对方论证中至少 3 个薄弱点或未考虑的场景\n" +
            "- 对每个反对点，给出具体理由和更好的替代方案\n" +
            "- 如果对方有合理的点，可以承认，但必须指出仍然存在的问题\n\n" +
            "## 关键约束\n\n" +
            "- 以 [DISAGREE] 开头，然后逐条展开详细分析，每条不少于 3 句话\n" +
            "- 你必须在本次回复中直接输出完整分析文本，不少于 500 字\n" +
            "- 不要只输出标题或标记，必须包含具体论述\n" +
            "- 不要调用任何工具，不要读取文件，直接基于讨论记录分析\n" +
            "- 现在就开始输出你的分析：";

    private static final String DEFENDER_TEMPLATE =
            "你是方案的提出者/支持者，另一位工程师对你的方案提出了质疑。\n" +
            "你需要捍卫你的立场，同时诚实地面对有效的批评。\n\n" +
            "## 原始方案\n\n%s\n\n" +
            "## 当前讨论记录\n\n%s\n\n" +
            "## 你的任务\n\n" +
            "- 逐条回应对方的质疑，不能笼统地说\"同意\"\n" +
            "- 对于对方的有效批评：承认问题，但提出你的改进方案\n" +
            "- 对于你认为不成立的批评：给出具体反驳理由\n\n" +
            "## 关键约束\n\n" +
            "- 以 [DISAGREE] 开头，然后逐条回应，每条不少于 3 句话\n" +
            "- 你必须在本次回复中直接输出完整分析文本，不少于 500 字\n" +
            "- 不要只输出标记，必须包含具体论述\n" +
            "- 不要调用任何工具，不要读取文件\n" +
            "- 现在就开始输出你的分析：";

    private static final String CONSENSUS_ALLOWED_TEMPLATE =
            "你是一位技术专家，正在参与一场方案讨论。讨论已经进行了多轮。\n\n" +
            "## 原始方案\n\n%s\n\n" +
            "## 当前讨论记录\n\n%s\n\n" +
            "## 你的任务\n\n" +
            "仔细审视之前所有的分歧点，做出判断：\n" +
            "- 如果分歧已充分讨论，能形成双方都能接受的最终方案：\n" +
            "  以 [CONSENSUS] 开头，输出【最终方案】，逐条说明每个分歧点如何解决\n" +
            "- 如果仍有未解决的关键分歧：\n" +
            "  以 [DISAGREE] 开头，指出哪些点仍未达成一致，给出你的最终立场\n\n" +
            "## 关键约束\n\n" +
            "- 你必须在本次回复中直接输出完整分析文本，不少于 300 字\n" +
            "- 不要调用任何工具，不要读取文件\n" +
            "- 现在就开始输出你的判断：";

    public String buildFirstTurnPrompt(DebateSession session) {
        return String.format(FIRST_TURN_TEMPLATE, session.getDescription());
    }

    /**
     * 根据当前轮次和角色构建回应 prompt。
     * 前 MIN_ROUNDS_BEFORE_CONSENSUS 轮使用对抗性 prompt，之后允许共识。
     *
     * @param session 辩论会话
     * @param discussionSoFar 已有讨论内容
     * @param round 当前轮次
     * @param isInitiator 是否为发起方（用于分配攻防角色）
     */
    public String buildResponsePrompt(DebateSession session, String discussionSoFar,
                                      int round, boolean isInitiator) {
        if (round > MIN_ROUNDS_BEFORE_CONSENSUS) {
            return String.format(CONSENSUS_ALLOWED_TEMPLATE,
                    session.getDescription(), discussionSoFar);
        }

        if (isInitiator) {
            return String.format(DEFENDER_TEMPLATE,
                    session.getDescription(), discussionSoFar);
        } else {
            return String.format(CHALLENGER_TEMPLATE,
                    session.getDescription(), discussionSoFar);
        }
    }

    /**
     * 兼容旧调用（默认为挑战者角色）
     */
    public String buildResponsePrompt(DebateSession session, String discussionSoFar) {
        return buildResponsePrompt(session, discussionSoFar, 1, false);
    }

    /**
     * 检测回复是否表达了共识意愿。
     * 仅在前 200 字符内检测 [CONSENSUS] 标记。
     */
    public boolean detectConsensus(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        String head = response.substring(0, Math.min(response.length(), 200)).toUpperCase();
        return head.contains("[CONSENSUS]");
    }

    /**
     * 从共识回复中提取最终方案摘要。
     * 优先查找"最终方案"/"共识方案"等关键词后的内容。
     */
    public String extractConsensusSummary(String response) {
        String[] lines = response.split("\n");
        StringBuilder summary = new StringBuilder();
        boolean capturing = false;
        for (String line : lines) {
            if (line.contains("最终方案")
                    || line.contains("共识方案")
                    || line.contains("总结")
                    || line.contains("结论")
                    || line.contains("Final")
                    || capturing) {
                capturing = true;
                summary.append(line).append("\n");
            }
        }
        if (summary.length() == 0) {
            int maxLen = Math.min(response.length(), 800);
            return response.substring(0, maxLen);
        }
        return summary.toString().trim();
    }
}
