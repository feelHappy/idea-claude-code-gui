package com.github.claudecodegui.service;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collection;
import java.util.Locale;

/**
 * Git commit message generation service.
 * Responsible for generating AI-powered commit messages.
 */
public class GitCommitMessageService {

    private static final Logger LOG = Logger.getInstance(GitCommitMessageService.class);

    private static final int MAX_DIFF_LENGTH = 8000; // Limit diff length to avoid exceeding token limits
    private static final int MAX_FILE_CONTENT_SAMPLE_LENGTH = 1200;
    private static final int MAX_CHANGED_LINES_PER_FILE = 80;
    private static final int MAX_LINE_LENGTH = 240;

    /**
     * Default model used for commit message generation.
     * Uses the Sonnet model for a balance between cost and quality.
     */
    private static final String COMMIT_MESSAGE_MODEL = "claude-sonnet-4-20250514";

    /**
     * Default AI provider.
     * Currently defaults to Claude; may be extended to read from user settings in the future.
     */
    private static final String DEFAULT_PROVIDER = "claude";

    /**
     * Built-in commit prompt (based on CCG Commits specification).
     * Users can append additional prompts via the settings page, which take priority.
     */
    private static final String BUILTIN_COMMIT_PROMPT = """
你是一个专门负责 GitHub commit 的高级程序员，请你遵循下面内容，生成高质量 commit

## 核心规则

### 基本格式
```
<type>[scope]: <description>

[body]

[footer]
```

### 输出要求
- 只输出提交消息，不添加签名、标记或元信息
- 不要包含 "Generated with Claude Code"、"Co-Authored-By" 等内容
- 不使用 emoji（除非项目规范明确要求）
- 使用祈使语气、现在时（"add" 而非 "added"）
- 主题行不超过 72 字符
- 保持简洁专业
- **必须用 `<commit></commit>` 标签包裹，标签外不要有任何内容**
- 语言默认使用中文
- 只基于下方“已选择的变更”生成，不要推断未展示的文件或行为
- 小变更优先生成单行提交；只有多项相关变更时才添加正文
- 根据主要变更路径选择 scope，避免使用过宽泛的 scope

## 提交类型映射

| Type | 描述 | 使用场景 |
|------|------|---------|
| `feat` | 新功能 | 添加新功能 |
| `fix` | Bug修复 | 修复问题 |
| `docs` | 文档 | 仅文档变更 |
| `style` | 代码风格 | 格式化、缺少分号等 |
| `refactor` | 重构 | 既不修复bug也不添加功能 |
| `perf` | 性能优化 | 性能改进 |
| `test` | 测试 | 添加或修改测试 |
| `chore` | 构建/工具 | 构建过程或工具变更 |
| `ci` | CI/CD | CI配置变更 |
| `build` | 构建系统 | 影响构建系统的变更 |
| `revert` | 回滚 | 回滚之前的提交 |

## Scope（范围）指南

Scope 应该是一个描述代码库部分的名词，在整个项目中保持一致，简短且有意义。

常见 Scope 示例：
- 模块级别：api, auth, ui, db, config, deps
- 组件级别：button, modal, header, footer
- 功能级别：parser, compiler, validator, router

## Body（正文）编写指南

Body 应该：
- 解释**是什么**变更和**为什么**变更（而不是如何变更）
- 使用项目符号列出多个变更
- 包含变更的动机
- 对比新旧行为
- 引用相关问题或决策
- 每行不超过 72 个字符

## Footer（页脚）编写指南

Footer 包含：
- Breaking Changes：BREAKING CHANGE: rename config.auth to config.authentication
- Issue 引用：Closes: #123, #124 / Fixes: #125 / Refs: #126

## 最佳实践

### 应该做的
- 使用现在时、祈使语气（"add" 而不是 "added"）
- 第一行保持在 50 个字符以内（最多 72）
- 描述的首字母大写
- 主题行末尾不加句号
- 主题和正文之间用空行分隔
- 使用正文解释是什么和为什么（而不是如何）
- 引用相关 issue 和破坏性变更

### 不应该做的
- 在一个提交中混合多个逻辑变更
- 在主题中包含实现细节
- 使用过去时（"added" 而不是 "add"）
- 创建过大的提交（难以审查）
- 提交损坏的代码（除非是 WIP）
- 包含敏感信息
""";

    // XML tags used to extract the commit message
    private static final String COMMIT_TAG_START = "<commit>";
    private static final String COMMIT_TAG_END = "</commit>";

    private final Project project;
    private final CodemossSettingsService settingsService;

    /**
     * Commit message generation callback interface.
     */
    public interface CommitMessageCallback {
        void onSuccess(String commitMessage);
        void onError(String error);
    }

    public GitCommitMessageService(@NotNull Project project) {
        this.project = project;
        this.settingsService = new CodemossSettingsService();
    }

    /**
     * Generate a commit message.
     *
     * @param changes  The selected file changes
     * @param callback The callback interface
     */
    public void generateCommitMessage(
            @NotNull Collection<Change> changes,
            @NotNull CommitMessageCallback callback
    ) {
        try {
            // 1. Generate git diff
            String diff = generateGitDiff(changes);
            if (diff.isEmpty()) {
                callback.onError(ClaudeCodeGuiBundle.message("commit.noChangesFound"));
                return;
            }

            // 2. Build the full prompt (built-in + user's additional prompt + diff)
            String fullPrompt = buildFullPrompt(diff);

            // 3. Call the AI SDK
            callAIService(fullPrompt, callback);

        } catch (Exception e) {
            LOG.error("Failed to generate commit message", e);
            callback.onError(e.getMessage());
        }
    }

    /**
     * Generate git diff.
     */
    private String generateGitDiff(@NotNull Collection<Change> changes) {
        StringBuilder diff = new StringBuilder();
        appendChangeSummary(diff, changes);

        for (Change change : changes) {
            try {
                String section = buildChangeSection(change);
                if (!appendWithinLimit(diff, section)) {
                    break;
                }

            } catch (VcsException e) {
                LOG.warn("Failed to get diff for change: " + e.getMessage());
            }
        }

        return diff.toString();
    }

    private void appendChangeSummary(StringBuilder diff, @NotNull Collection<Change> changes) {
        int added = 0;
        int modified = 0;
        int deleted = 0;
        int moved = 0;

        for (Change change : changes) {
            Change.Type type = change.getType();
            if (type == Change.Type.NEW) {
                added++;
            } else if (type == Change.Type.DELETED) {
                deleted++;
            } else if (type == Change.Type.MOVED) {
                moved++;
            } else {
                modified++;
            }
        }

        diff.append("Selected change summary:\n");
        diff.append("- Files: ").append(changes.size()).append("\n");
        diff.append("- Added: ").append(added)
                .append(", Modified: ").append(modified)
                .append(", Deleted: ").append(deleted)
                .append(", Moved: ").append(moved)
                .append("\n");
    }

    private String buildChangeSection(@NotNull Change change) throws VcsException {
        StringBuilder section = new StringBuilder();
        FilePath filePath = ChangesUtil.getFilePath(change);
        Change.Type changeType = change.getType();
        ContentRevision beforeRevision = change.getBeforeRevision();
        ContentRevision afterRevision = change.getAfterRevision();

        String beforePath = getRevisionPath(beforeRevision);
        String afterPath = getRevisionPath(afterRevision);
        String displayPath = afterPath != null ? afterPath : beforePath != null ? beforePath : filePath.getPath();

        section.append("\n---\n");
        section.append("File: ").append(displayPath).append("\n");
        section.append("Status: ").append(changeType.name()).append("\n");

        if (beforePath != null && afterPath != null && !beforePath.equals(afterPath)) {
            section.append("Previous path: ").append(beforePath).append("\n");
            section.append("Current path: ").append(afterPath).append("\n");
        }

        String before = beforeRevision != null ? beforeRevision.getContent() : null;
        String after = afterRevision != null ? afterRevision.getContent() : null;

        if (changeType == Change.Type.NEW) {
            appendContentSample(section, "Added content sample:", '+', after);
        } else if (changeType == Change.Type.DELETED) {
            appendContentSample(section, "Deleted content sample:", '-', before);
        } else {
            String lineDiff = before != null && after != null ? generateSimpleDiff(before, after) : "";
            if (!lineDiff.isEmpty()) {
                section.append("Line changes:\n");
                section.append(lineDiff);
            } else if (beforePath != null && afterPath != null && !beforePath.equals(afterPath)) {
                section.append("Path changed without text content changes.\n");
            } else {
                section.append("Text diff unavailable or empty.\n");
            }
        }

        return section.toString();
    }

    private String getRevisionPath(ContentRevision revision) {
        if (revision == null || revision.getFile() == null) {
            return null;
        }
        return revision.getFile().getPath();
    }

    private void appendContentSample(StringBuilder section, String title, char prefix, String content) {
        if (content == null || content.isEmpty()) {
            section.append(title).append(" [empty or unavailable]\n");
            return;
        }

        section.append(title).append("\n");
        String sample = content.length() > MAX_FILE_CONTENT_SAMPLE_LENGTH
                ? content.substring(0, MAX_FILE_CONTENT_SAMPLE_LENGTH)
                : content;
        for (String line : splitLines(sample)) {
            section.append(prefix).append(' ').append(limitLine(line)).append("\n");
        }
        if (content.length() > sample.length()) {
            section.append("... (file content sample truncated)\n");
        }
    }

    private boolean appendWithinLimit(StringBuilder diff, String section) {
        int remaining = MAX_DIFF_LENGTH - diff.length();
        if (remaining <= 0) {
            diff.append("\n... (diff truncated)");
            return false;
        }

        if (section.length() <= remaining) {
            diff.append(section);
            return true;
        }

        diff.append(section, 0, Math.max(0, remaining));
        diff.append("\n... (diff truncated)");
        return false;
    }

    /**
     * Generate a simple diff (showing added/removed lines).
     */
    private String generateSimpleDiff(String before, String after) {
        String[] beforeLines = splitLines(before);
        String[] afterLines = splitLines(after);

        int prefix = 0;
        while (prefix < beforeLines.length
                && prefix < afterLines.length
                && beforeLines[prefix].equals(afterLines[prefix])) {
            prefix++;
        }

        int beforeEnd = beforeLines.length - 1;
        int afterEnd = afterLines.length - 1;
        while (beforeEnd >= prefix
                && afterEnd >= prefix
                && beforeLines[beforeEnd].equals(afterLines[afterEnd])) {
            beforeEnd--;
            afterEnd--;
        }

        StringBuilder diff = new StringBuilder();
        int shownLines = 0;
        int omittedLines = 0;

        for (int i = prefix; i <= beforeEnd; i++) {
            if (shownLines < MAX_CHANGED_LINES_PER_FILE) {
                appendChangedLine(diff, '-', beforeLines[i]);
                shownLines++;
            } else {
                omittedLines++;
            }
        }

        for (int i = prefix; i <= afterEnd; i++) {
            if (shownLines < MAX_CHANGED_LINES_PER_FILE) {
                appendChangedLine(diff, '+', afterLines[i]);
                shownLines++;
            } else {
                omittedLines++;
            }
        }

        if (omittedLines > 0) {
            diff.append("... (").append(omittedLines).append(" more changed lines omitted)\n");
        }

        return diff.toString();
    }

    private String[] splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return new String[0];
        }
        return normalized.split("\n", -1);
    }

    private void appendChangedLine(StringBuilder diff, char prefix, String line) {
        diff.append(prefix).append(' ').append(limitLine(line)).append("\n");
    }

    private String limitLine(String line) {
        if (line == null || line.length() <= MAX_LINE_LENGTH) {
            return line == null ? "" : line;
        }
        return line.substring(0, MAX_LINE_LENGTH) + " ...";
    }

    /**
     * Get the user's additional prompt (optional).
     */
    private String getUserAdditionalPrompt() {
        try {
            String userPrompt = settingsService.getCommitPrompt();
            // Return empty if the user hasn't configured a prompt or set the default value
            if (userPrompt == null || userPrompt.trim().isEmpty()) {
                return "";
            }
            // Treat the old default value as not configured
            if (userPrompt.equals("你是一个commit提交专员，请你阅读git记录，帮我生成commit记录")) {
                return "";
            }
            return userPrompt.trim();
        } catch (Exception e) {
            LOG.warn("Failed to get user additional prompt from settings", e);
            return "";
        }
    }

    /**
     * Build the full prompt.
     * Logic: built-in prompt + user's additional prompt (takes priority) + git diff.
     */
    private String buildFullPrompt(String diff) {
        StringBuilder prompt = new StringBuilder();

        // 1. Built-in commit prompt
        prompt.append(BUILTIN_COMMIT_PROMPT);

        // 2. User's additional prompt (if any, takes priority)
        String userAdditionalPrompt = getUserAdditionalPrompt();
        if (!userAdditionalPrompt.isEmpty()) {
            prompt.append("\n\n## 用户附加要求（优先遵循）\n\n");
            prompt.append("以下是用户的额外要求，请在生成 commit message 时优先考虑这些要求：\n\n");
            prompt.append(userAdditionalPrompt);
        }

        // 3. Git diff content
        prompt.append("\n\n---\n\n");
        prompt.append("以下是 git diff 信息，请根据以上规则生成 commit message：\n\n");
        prompt.append("```diff\n");
        prompt.append(diff);
        prompt.append("\n```");

        // 4. Output format requirements (enforce XML tag wrapping for easy parsing)
        prompt.append("\n\n【输出格式要求 - 必须严格遵守】\n");
        prompt.append("请将 commit message 用 XML 标签包裹输出，格式如下：\n");
        prompt.append("<commit>\n");
        prompt.append("type(scope): description\n");
        prompt.append("\n");
        prompt.append("optional body\n");
        prompt.append("</commit>\n\n");
        prompt.append("重要规则：\n");
        prompt.append("1. 必须使用 <commit> 和 </commit> 标签包裹\n");
        prompt.append("2. 标签外不要有任何其他内容（不要分析、不要解释、不要说明）\n");
        prompt.append("3. 不要把 diff 截断提示、文件状态说明当成提交内容本身\n");

        return prompt.toString();
    }

    /**
     * Call the AI service.
     */
    private void callAIService(String prompt, CommitMessageCallback callback) {
        // Get the current provider
        String currentProvider = getCurrentProvider();

        if ("codex".equals(currentProvider)) {
            callCodexAPI(prompt, callback);
        } else {
            callClaudeAPI(prompt, callback);
        }
    }

    /**
     * Get the current provider.
     *
     * Note: Currently always returns the default provider (claude).
     * In the future, this can be extended to read the user's preferred provider from settings or session state.
     */
    private String getCurrentProvider() {
        // Future extension: read the user's default provider from CodemossSettingsService
        // e.g.: return settingsService.getDefaultProvider();
        return DEFAULT_PROVIDER;
    }

    /**
     * Call the Claude API.
     */
    private void callClaudeAPI(String prompt, CommitMessageCallback callback) {
        ClaudeSDKBridge bridge = new ClaudeSDKBridge();
        try {
            // Simple callback handler
            StringBuilder result = new StringBuilder();

            // Use the 12-parameter sendMessage overload:
            // - model: COMMIT_MESSAGE_MODEL (Sonnet model)
            // - streaming: false (non-streaming, returns complete result at once)
            // - disableThinking: true (disable thinking mode to avoid verbose reasoning output)
            bridge.sendMessage(
                "git-commit-message",      // channelId
                prompt,                     // message
                null,                       // sessionId (null = new session)
                project.getBasePath(),      // cwd
                null,                       // attachments (not needed)
                null,                       // permissionMode (use default)
                COMMIT_MESSAGE_MODEL,       // model (Sonnet)
                null,                       // openedFiles
                null,                       // agentPrompt
                false,                      // streaming (non-streaming mode)
                true,                       // disableThinking (disable thinking mode)
                new MessageCallback() {
                    @Override
                    public void onMessage(String type, String content) {
                        // Only collect assistant content, ignore thinking/reasoning
                        if ("content".equals(type) || "assistant".equals(type) || "text".equals(type)) {
                            // Skip thinking content (typically starts with specific markers)
                            if (!isThinkingContent(content)) {
                                result.append(content);
                            }
                        }
                    }

                    @Override
                    public void onError(String error) {
                        bridge.shutdownDaemon();
                        callback.onError(error);
                    }

                    @Override
                    public void onComplete(SDKResult sdkResult) {
                        bridge.shutdownDaemon();
                        String commitMessage = result.length() > 0
                                ? result.toString().trim()
                                : sdkResult.finalResult.trim();

                        if (commitMessage.isEmpty()) {
                            callback.onError(ClaudeCodeGuiBundle.message("commit.emptyMessage"));
                        } else {
                            callback.onSuccess(cleanupCommitMessage(commitMessage));
                        }
                    }
                }
            );
        } catch (Exception e) {
            bridge.shutdownDaemon();
            LOG.error("Failed to call Claude API", e);
            callback.onError(ClaudeCodeGuiBundle.message("commit.callApiFailed") + ": " + e.getMessage());
        }
    }

    /**
     * Call the Codex API.
     */
    private void callCodexAPI(String prompt, CommitMessageCallback callback) {
        CodexSDKBridge bridge = new CodexSDKBridge();
        try {
            // Simple callback handler
            StringBuilder result = new StringBuilder();

            // CodexSDKBridge.sendMessage requires 10 parameters:
            // (channelId, message, threadId, cwd, attachments, permissionMode, model, agentPrompt, reasoningEffort, callback)
            bridge.sendMessage(
                "git-commit-message",      // channelId
                prompt,                     // message
                null,                       // threadId (null = new session)
                project.getBasePath(),      // cwd
                null,                       // attachments (not needed)
                null,                       // permissionMode (use default)
                null,                       // model (use default)
                null,                       // agentPrompt (not needed)
                null,                       // reasoningEffort (use default)
                new MessageCallback() {
                    @Override
                    public void onMessage(String type, String content) {
                        // Only collect assistant content, ignore thinking/reasoning
                        if ("content".equals(type) || "assistant".equals(type) || "text".equals(type)) {
                            // Skip thinking content
                            if (!isThinkingContent(content)) {
                                result.append(content);
                            }
                        }
                    }

                    @Override
                    public void onError(String error) {
                        bridge.cleanupAllProcesses();
                        callback.onError(error);
                    }

                    @Override
                    public void onComplete(SDKResult sdkResult) {
                        bridge.cleanupAllProcesses();
                        String commitMessage = result.length() > 0
                                ? result.toString().trim()
                                : sdkResult.finalResult.trim();

                        if (commitMessage.isEmpty()) {
                            callback.onError(ClaudeCodeGuiBundle.message("commit.emptyMessage"));
                        } else {
                            callback.onSuccess(cleanupCommitMessage(commitMessage));
                        }
                    }
                }
            );
        } catch (Exception e) {
            bridge.cleanupAllProcesses();
            LOG.error("Failed to call Codex API", e);
            callback.onError(ClaudeCodeGuiBundle.message("commit.callApiFailed") + ": " + e.getMessage());
        }
    }

    /**
     * Clean up and extract the commit message.
     * Prioritizes extraction from XML tags, with multiple format fallbacks.
     */
    private String cleanupCommitMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }

        String cleaned = message.trim();

        // 0. Remove thinking markers first (e.g. "Thinking >" etc.)
        cleaned = removeThinkingMarkers(cleaned);

        // 1. First try to extract from <commit>...</commit> tags
        String taggedCommit = extractCommitTagContent(cleaned);
        if (taggedCommit != null) {
            return normalizeCommitMessage(taggedCommit);
        }

        // 2. Fallback: try to extract from markdown code blocks
        String codeBlock = extractFirstCodeBlock(cleaned);
        if (codeBlock != null) {
            String codeBlockTaggedCommit = extractCommitTagContent(codeBlock);
            return normalizeCommitMessage(codeBlockTaggedCommit != null ? codeBlockTaggedCommit : codeBlock);
        }

        // 3. Fallback: try to extract the first conventional commit formatted line
        // Format: type(scope): description or type: description
        String[] lines = cleaned.split("\n");
        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            String trimmedLine = line.trim();
            if (isConventionalCommitLine(trimmedLine)) {
                // After finding the first line, continue collecting the body until hitting analysis sections
                StringBuilder result = new StringBuilder(trimmedLine);
                boolean inBody = false;

                for (int i = idx + 1; i < lines.length; i++) {
                    String nextLine = lines[i].trim();
                    // Stop when hitting analysis keywords
                    if (isAnalysisSection(nextLine)) {
                        break;
                    }
                    // Empty line indicates body start
                    if (nextLine.isEmpty()) {
                        inBody = true;
                        result.append("\n");
                        continue;
                    }
                    // Collect body content
                    if (inBody && !nextLine.startsWith("#") && !nextLine.startsWith("*")) {
                        result.append("\n").append(nextLine);
                    } else if (!inBody) {
                        // Not in body yet but hit a non-empty line, means single-line commit
                        break;
                    }
                }
                return normalizeCommitMessage(result.toString());
            }
        }

        // 4. Last resort fallback: return the first few lines of raw content (excluding analysis sections)
        StringBuilder fallback = new StringBuilder();
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (isAnalysisSection(trimmedLine)) {
                break;
            }
            if (!trimmedLine.isEmpty()) {
                fallback.append(trimmedLine).append("\n");
            }
            // Take at most 5 lines
            if (fallback.toString().split("\n").length >= 5) {
                break;
            }
        }

        return normalizeCommitMessage(fallback.toString());
    }

    private String extractCommitTagContent(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        String tagStart = COMMIT_TAG_START.toLowerCase(Locale.ROOT);
        String tagEnd = COMMIT_TAG_END.toLowerCase(Locale.ROOT);
        int startIdx = lower.indexOf(tagStart);
        int endIdx = lower.indexOf(tagEnd, startIdx + tagStart.length());

        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            return message.substring(startIdx + COMMIT_TAG_START.length(), endIdx);
        }
        return null;
    }

    private String extractFirstCodeBlock(String message) {
        int codeBlockStart = message.indexOf("```");
        if (codeBlockStart == -1) {
            return null;
        }

        int contentStart = message.indexOf('\n', codeBlockStart);
        if (contentStart == -1) {
            return null;
        }

        int codeBlockEnd = message.indexOf("```", contentStart + 1);
        if (codeBlockEnd == -1) {
            return null;
        }

        return message.substring(contentStart + 1, codeBlockEnd);
    }

    private String normalizeCommitMessage(String message) {
        String normalized = convertLiteralNewlines(message == null ? "" : message.trim());
        normalized = normalized.replace(COMMIT_TAG_START, "").replace(COMMIT_TAG_END, "");

        StringBuilder result = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Generated with Claude Code")
                    || trimmed.toLowerCase(Locale.ROOT).startsWith("co-authored-by:")) {
                continue;
            }
            result.append(line).append("\n");
        }

        return stripSurroundingQuotes(result.toString().trim());
    }

    private String stripSurroundingQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    /**
     * Convert literal \n characters to actual newlines and clean up excess blank lines.
     */
    private String convertLiteralNewlines(String text) {
        if (text == null) {
            return null;
        }
        // Convert literal \n (two characters) to actual newlines
        String result = text.replace("\\n", "\n");

        // Remove leading blank lines
        result = result.replaceFirst("^\\n+", "");

        // Collapse multiple consecutive blank lines into a single one (preserve the conventional commit title/body separator)
        result = result.replaceAll("\\n{3,}", "\n\n");

        return result.trim();
    }

    /**
     * Check whether a line follows the conventional commit format.
     */
    private boolean isConventionalCommitLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        // Match: feat:, fix:, refactor:, feat(scope):, etc.
        String[] types = {"feat", "fix", "refactor", "docs", "test", "chore", "perf", "ci", "style", "build", "revert"};
        for (String type : types) {
            if (line.startsWith(type + ":") || line.startsWith(type + "(")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a line belongs to an analysis section (content to exclude).
     */
    private boolean isAnalysisSection(String line) {
        if (line == null) {
            return false;
        }
        String[] analysisKeywords = {
            "分析说明", "变更特征", "分析：", "说明：", "解释：", "备注：",
            "Analysis:", "Explanation:", "Note:", "---", "===",
            "1. 类型", "2. Scope", "3. 描述", "4. Body",
            "• ", "- 无需", "- 不涉及"
        };
        for (String keyword : analysisKeywords) {
            if (line.contains(keyword)) {
                return true;
            }
        }
        // Check for numbered list items (e.g. "1. xxx")
        if (line.matches("^\\d+\\.\\s+.*")) {
            return true;
        }
        return false;
    }

    /**
     * Check whether the content is a thinking/reasoning block.
     * Only matches explicit XML-style thinking tags since disableThinking is already set.
     */
    private boolean isThinkingContent(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.contains("<thinking>") || trimmed.contains("</thinking>");
    }

    /**
     * Remove thinking XML tags and their content from the response.
     */
    private String removeThinkingMarkers(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        String result = content;

        while (result.contains("<thinking>") && result.contains("</thinking>")) {
            int start = result.indexOf("<thinking>");
            int end = result.indexOf("</thinking>") + "</thinking>".length();
            if (start < end) {
                result = result.substring(0, start) + result.substring(end);
            } else {
                break;
            }
        }

        return result.replaceFirst("^\\s*\\n+", "").trim();
    }
}
