package com.github.claudecodegui.debate;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @description 辩论文件管理器，负责创建/读写共享 Markdown 辩论文件。
 * @author zyl
 * @date 2026/05/14
 */
public class DebateFileManager {

    private static final Logger LOG = Logger.getInstance(DebateFileManager.class);
    private static final DateTimeFormatter FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String projectBasePath;

    public DebateFileManager(String projectBasePath) {
        this.projectBasePath = projectBasePath;
    }

    public Path createDebateFile(DebateSession session) throws IOException {
        String sanitizedTopic = sanitize(session.getTopic());
        String fileName = FILE_NAME_FORMAT.format(session.getCreatedAt()) + "-" + sanitizedTopic + ".md";
        Path debatesDir = Paths.get(projectBasePath, DebateConfig.DEBATES_DIR);
        Files.createDirectories(debatesDir);
        Path filePath = debatesDir.resolve(fileName);

        StringBuilder sb = new StringBuilder();
        sb.append("# Bug Debate: ").append(session.getTopic()).append("\n\n");
        sb.append("## Metadata\n");
        sb.append("- **Created**: ").append(DISPLAY_FORMAT.format(session.getCreatedAt())).append("\n");
        sb.append("- **Status**: in_progress\n");
        sb.append("- **Max Rounds**: ").append(session.getConfig().getMaxRounds()).append("\n");
        sb.append("- **Participants**: Claude, Codex\n\n");
        sb.append("## Problem Description\n\n");
        sb.append(session.getDescription()).append("\n\n---\n");

        Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));
        LOG.info("[DebateFileManager] Created debate file: " + filePath);
        return filePath;
    }

    public void appendRoundEntry(Path filePath, String provider, int round, String content) throws IOException {
        appendRoundEntry(filePath, provider, round, content, null);
    }

    /**
     * 写入辩论轮次内容。
     * visibleText 写入公开 transcript，rawResponse 写入 debug trace 文件。
     *
     * @param filePath 辩论文件路径
     * @param provider 参与者名称
     * @param round 轮次
     * @param visibleText 净化后的公开内容
     * @param rawResponse 原始响应（可选，写入 debug trace）
     */
    public void appendRoundEntry(Path filePath, String provider, int round,
                                 String visibleText, String rawResponse) throws IOException {
        String existing = readFile(filePath);
        StringBuilder sb = new StringBuilder(existing);

        if (!existing.endsWith("\n")) {
            sb.append("\n");
        }

        boolean isFirstEntryOfRound = !existing.contains("## Round " + round);
        if (isFirstEntryOfRound) {
            sb.append("\n## Round ").append(round).append("\n");
        }

        sb.append("\n### ").append(provider).append(" (Round ").append(round).append(")\n\n");
        sb.append(visibleText.trim()).append("\n");

        Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));

        if (rawResponse != null && !rawResponse.equals(visibleText)) {
            appendDebugTrace(filePath, provider, round, rawResponse);
        }
    }

    /**
     * 将原始响应写入独立的 debug trace 文件。
     * 文件名为辩论文件名 + .debug.md 后缀。
     */
    private void appendDebugTrace(Path debateFilePath, String provider, int round,
                                  String rawResponse) throws IOException {
        String debugFileName = debateFilePath.getFileName().toString().replace(".md", ".debug.md");
        Path debugPath = debateFilePath.getParent().resolve(debugFileName);

        StringBuilder sb = new StringBuilder();
        if (Files.exists(debugPath)) {
            sb.append(new String(Files.readAllBytes(debugPath), StandardCharsets.UTF_8));
        } else {
            sb.append("# Debug Trace\n\n");
            sb.append("> Raw model responses before sanitization. Not for display.\n\n---\n");
        }

        sb.append("\n\n## ").append(provider).append(" Round ").append(round).append(" (raw)\n\n");
        sb.append(rawResponse.trim()).append("\n");

        Files.write(debugPath, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void writeConsensus(Path filePath, String agreedBy, int round, String summary) throws IOException {
        String existing = readFile(filePath);
        StringBuilder sb = new StringBuilder(existing);

        sb.append("\n---\n\n## Consensus\n\n");
        sb.append("**Agreed by**: ").append(agreedBy).append(" (Round ").append(round).append(")\n\n");
        sb.append("**Summary**: ").append(summary.trim()).append("\n");

        String updated = sb.toString().replace("- **Status**: in_progress", "- **Status**: consensus");
        Files.write(filePath, updated.getBytes(StandardCharsets.UTF_8));
    }

    public void updateStatus(Path filePath, String newStatus) throws IOException {
        String content = readFile(filePath);
        String updated = content.replace("- **Status**: in_progress", "- **Status**: " + newStatus);
        Files.write(filePath, updated.getBytes(StandardCharsets.UTF_8));
    }

    public String readFile(Path filePath) throws IOException {
        return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
    }

    private String sanitize(String topic) {
        String sanitized = topic.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\-_]", "-");
        if (sanitized.length() > 40) {
            sanitized = sanitized.substring(0, 40);
        }
        return sanitized.replaceAll("-+$", "");
    }
}
