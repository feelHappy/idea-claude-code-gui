package com.github.claudecodegui.debate;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 辩论响应净化器。
 * 检测并过滤模型回显的内部编排规则，防止 orchestrator prompt 泄露到公开 transcript。
 *
 * @author zyl
 * @date 2026/05/16
 */
public class DebateResponseSanitizer {

    private static final List<String> ORCHESTRATION_MARKERS = Arrays.asList(
            "重要规则：",
            "重要规则:",
            "禁止使用任何工具",
            "不读文件、不执行代码",
            "仅基于上述方案描述进行分析",
            "前 " + DebatePromptBuilder.MIN_ROUNDS_BEFORE_CONSENSUS + " 轮必须深入讨论",
            "前 " + DebatePromptBuilder.MIN_ROUNDS_BEFORE_CONSENSUS + " 轮禁止达成共识",
            "以 [DISAGREE] 开头，然后逐条",
            "以 [CONSENSUS] 开头，然后输出"
    );

    private static final Pattern RULE_BLOCK_PATTERN = Pattern.compile(
            "(?m)^重要规则[：:]\\s*\\n(- .+\\n)+",
            Pattern.MULTILINE
    );

    private static final Pattern NOTE_LINE_PATTERN = Pattern.compile(
            "(?m)^注意：当前是第 \\d+ 轮.*$"
    );

    public int detectLeakage(String response) {
        if (response == null || response.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String marker : ORCHESTRATION_MARKERS) {
            if (response.contains(marker)) {
                count++;
            }
        }
        return count;
    }

    public boolean isSevereLeakage(String response) {
        return detectLeakage(response) >= 3;
    }

    public String sanitize(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }

        String result = response;
        result = RULE_BLOCK_PATTERN.matcher(result).replaceAll("");
        result = NOTE_LINE_PATTERN.matcher(result).replaceAll("");

        for (String marker : ORCHESTRATION_MARKERS) {
            if (result.contains(marker)) {
                result = removeLine(result, marker);
            }
        }

        return result.replaceAll("(?m)^\\s*\\n{3,}", "\n\n").trim();
    }

    private String removeLine(String text, String marker) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.contains(marker)) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}