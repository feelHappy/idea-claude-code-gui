package com.github.claudecodegui.session;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.service.RunConfigMonitorService;
import com.github.claudecodegui.terminal.TerminalMonitorService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds message payloads and provider-specific context blocks for a session.
 */
public class SessionContextService {

    private static final Logger LOG = Logger.getInstance(SessionContextService.class);
    private static final int CODEX_REPLAY_CHAR_BUDGET = 24 * 1024;

    private final Project project;
    private final int maxFileSizeBytes;

    public SessionContextService(Project project, int maxFileSizeBytes) {
        this.project = project;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public ClaudeSession.Message buildUserMessage(String normalizedInput, List<ClaudeSession.Attachment> attachments) {
        ClaudeSession.Message userMessage = new ClaudeSession.Message(ClaudeSession.Message.Type.USER, normalizedInput);

        try {
            JsonArray contentArr = new JsonArray();
            String userDisplayText = normalizedInput;

            if (attachments != null && !attachments.isEmpty()) {
                for (ClaudeSession.Attachment att : attachments) {
                    if (isImageAttachment(att)) {
                        contentArr.add(createImageBlock(att));
                    }
                }

                if (userDisplayText.isEmpty()) {
                    userDisplayText = generateAttachmentSummary(attachments);
                }
            }

            userDisplayText = processReferences(normalizedInput, "terminal", "Terminal Output", this::resolveTerminalContent);
            userDisplayText = processReferences(userDisplayText, "service", "Service Output", this::resolveServiceContent);

            contentArr.add(createTextBlock(userDisplayText));

            JsonObject messageObj = new JsonObject();
            messageObj.add("content", contentArr);
            JsonObject rawUser = new JsonObject();
            rawUser.add("message", messageObj);
            userMessage.raw = rawUser;
            userMessage.content = userDisplayText;

            LOG.info("[ClaudeSession] Created user message: content="
                    + (userDisplayText.length() > 50 ? userDisplayText.substring(0, 50) + "..." : userDisplayText)
                    + ", hasRaw=true, contentBlocks=" + contentArr.size());
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("Failed to build user message raw: " + e.getMessage());
        }

        return userMessage;
    }

    public String buildCodexContextAppend(JsonObject openedFilesJson, List<String> fileTagPaths) {
        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        List<String> terminalPaths = new ArrayList<>();
        List<String> regularFilePaths = new ArrayList<>();

        if (fileTagPaths != null && !fileTagPaths.isEmpty()) {
            for (String path : fileTagPaths) {
                if (path != null && path.startsWith("terminal://")) {
                    terminalPaths.add(path);
                } else {
                    regularFilePaths.add(path);
                }
            }
        }

        if (!terminalPaths.isEmpty()) {
            sb.append("\n\n## Active Terminal Session\n\n");
            sb.append("The user is working in the following terminal context:\n\n");
            for (String terminalPath : terminalPaths) {
                String sessionName = terminalPath.substring("terminal://".length());
                sb.append("- **Terminal**: `").append(sessionName).append("`\n");
            }
            sb.append("\nCommands should be executed in this terminal context.\n\n");
            hasContent = true;
        }

        if (!regularFilePaths.isEmpty()) {
            sb.append("\n\n## Referenced Files\n\n");
            sb.append("The following files were referenced by the user:\n\n");

            for (String filePath : regularFilePaths) {
                String fileContent = readFileContent(filePath);
                if (fileContent != null) {
                    String extension = getFileExtension(filePath);
                    sb.append("### `").append(filePath).append("`\n\n");
                    sb.append("```").append(extension).append("\n");
                    sb.append(fileContent);
                    if (!fileContent.endsWith("\n")) {
                        sb.append("\n");
                    }
                    sb.append("```\n\n");
                    hasContent = true;
                }
            }
        }

        if (openedFilesJson != null && !openedFilesJson.isJsonNull()) {
            String activeFile = null;
            if (openedFilesJson.has("active") && !openedFilesJson.get("active").isJsonNull()) {
                activeFile = openedFilesJson.get("active").getAsString();
            }

            JsonObject selection = null;
            String selectedText = null;
            Integer startLine = null;
            Integer endLine = null;

            if (openedFilesJson.has("selection") && openedFilesJson.get("selection").isJsonObject()) {
                selection = openedFilesJson.getAsJsonObject("selection");
                if (selection.has("selectedText") && !selection.get("selectedText").isJsonNull()) {
                    selectedText = selection.get("selectedText").getAsString();
                }
                if (selection.has("startLine") && selection.get("startLine").isJsonPrimitive()) {
                    startLine = selection.get("startLine").getAsInt();
                }
                if (selection.has("endLine") && selection.get("endLine").isJsonPrimitive()) {
                    endLine = selection.get("endLine").getAsInt();
                }
            }

            if (selectedText != null && !selectedText.trim().isEmpty()) {
                sb.append("\n\n## IDE Context\n\n");
                if (activeFile != null && !activeFile.trim().isEmpty()) {
                    sb.append("Active file: `").append(activeFile);
                    if (startLine != null && endLine != null) {
                        if (startLine.equals(endLine)) {
                            sb.append("#L").append(startLine);
                        } else {
                            sb.append("#L").append(startLine).append("-").append(endLine);
                        }
                    }
                    sb.append("`\n\n");
                }
                sb.append("Selected code:\n```\n");
                sb.append(selectedText);
                sb.append("\n```\n");
                sb.append("The selected code above is the primary subject of the user's question.\n");
                hasContent = true;
            } else if (activeFile != null && !activeFile.trim().isEmpty()) {
                String fileContent = readFileContent(activeFile);
                if (fileContent != null) {
                    String extension = getFileExtension(activeFile);
                    sb.append("\n\n## User's Current IDE Context\n\n");
                    sb.append("The user is viewing this file in their IDE. This is the PRIMARY SUBJECT of the user's question.\n\n");
                    sb.append("### `").append(activeFile).append("`\n\n");
                    sb.append("```").append(extension).append("\n");
                    sb.append(fileContent);
                    if (!fileContent.endsWith("\n")) {
                        sb.append("\n");
                    }
                    sb.append("```\n\n");
                    hasContent = true;
                    LOG.info("[Codex Context] Injected active file content: " + activeFile);
                }
            }
        }

        return hasContent ? sb.toString() : "";
    }

    public String buildCodexReplayPrefix(List<ClaudeSession.Message> messages, String currentInput) {
        List<ClaudeSession.Message> replayMessages = collectCodexReplayMessages(messages, currentInput);
        if (replayMessages.isEmpty()) {
            return "";
        }

        List<String> replaySegments = new ArrayList<>();
        int remainingBudget = CODEX_REPLAY_CHAR_BUDGET;
        boolean truncated = false;

        for (int i = replayMessages.size() - 1; i >= 0; i--) {
            String segment = formatCodexReplaySegment(replayMessages.get(i));
            if (segment.isEmpty()) {
                continue;
            }

            int segmentLength = segment.length() + 2;
            if (segmentLength > remainingBudget) {
                if (replaySegments.isEmpty()) {
                    replaySegments.add(0, truncateReplaySegment(segment, remainingBudget));
                }
                truncated = true;
                break;
            }

            replaySegments.add(0, segment);
            remainingBudget -= segmentLength;
        }

        if (replaySegments.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("The previous Codex thread is no longer available. Continue from the preserved conversation state below instead of starting over.\n\n");
        if (truncated) {
            sb.append("Some older conversation has been omitted to keep this replay within the prompt budget.\n\n");
        }
        sb.append("## Preserved Conversation\n\n");
        for (String segment : replaySegments) {
            sb.append(segment).append("\n\n");
        }
        sb.append("## Continue From Here\n\n");
        return sb.toString();
    }

    public List<ClaudeSession.Attachment> buildCodexReplayAttachments(List<ClaudeSession.Message> messages, String currentInput) {
        List<ClaudeSession.Attachment> attachments = new ArrayList<>();
        List<ClaudeSession.Message> replayMessages = collectCodexReplayMessages(messages, currentInput);
        int replayImageCounter = 1;

        for (ClaudeSession.Message message : replayMessages) {
            List<ClaudeSession.Attachment> extracted = extractReplayImageAttachments(message);
            for (ClaudeSession.Attachment attachment : extracted) {
                String mediaType = attachment.mediaType != null ? attachment.mediaType : "image/png";
                attachments.add(new ClaudeSession.Attachment(
                        "replay-image-" + replayImageCounter + getImageExtension(mediaType),
                        mediaType,
                        attachment.data
                ));
                replayImageCounter += 1;
            }
        }

        return attachments;
    }

    private String processReferences(
            String input,
            String protocol,
            String blockTitle,
            Function<String, String> contentResolver
    ) {
        Pattern pattern = Pattern.compile("@" + protocol + "://([a-zA-Z0-9_]+)");
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();
        int matchCount = 0;

        while (matcher.find()) {
            matchCount++;
            String safeName = matcher.group(1);
            LOG.debug("[" + protocol + "] Found mention in message: @" + protocol + "://" + safeName);
            String content = contentResolver.apply(safeName);

            if (content != null && !content.isEmpty()) {
                String block = "\n\n" + blockTitle + " (" + safeName + "):\n```\n" + content + "\n```";
                matcher.appendReplacement(result, Matcher.quoteReplacement(block));
                LOG.debug("[" + protocol + "] Successfully replaced reference for: " + safeName);
            } else {
                matcher.appendReplacement(result, "");
                LOG.debug("[" + protocol + "] Content was empty or null for: " + safeName);
            }
        }
        matcher.appendTail(result);

        if (matchCount == 0 && input.contains("@" + protocol + "://")) {
            LOG.warn("[" + protocol + "] Message contains '@" + protocol + "://' but regex did not match.");
        }

        return result.toString();
    }

    private String resolveTerminalContent(String safeName) {
        if (project == null) {
            return "";
        }

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                List<Object> widgets = TerminalMonitorService.getWidgets(project);
                LOG.debug("[Terminal] Resolving: " + safeName + ". Available widgets: " + widgets.size());

                Map<String, Integer> nameCounts = new HashMap<>();
                for (Object widget : widgets) {
                    String baseTitle = TerminalMonitorService.getWidgetTitle(widget);
                    int count = nameCounts.getOrDefault(baseTitle, 0) + 1;
                    nameCounts.put(baseTitle, count);

                    String titleText = baseTitle;
                    if (count > 1) {
                        titleText = baseTitle + " (" + count + ")";
                    }

                    String widgetSafeName = titleText.replace(" ", "_").replaceAll("[^a-zA-Z0-9_]", "");
                    LOG.debug("[Terminal] - Candidate: " + titleText + " (Safe: " + widgetSafeName + ")");

                    if (widgetSafeName.equals(safeName)) {
                        String content = TerminalMonitorService.getWidgetContent(widget);
                        LOG.debug("[Terminal] Match found! Content length: "
                                + (content != null ? content.length() : "null"));
                        return content;
                    }
                }
                LOG.debug("[Terminal] No matching terminal found for: " + safeName);
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                LOG.error("[Terminal] Error resolving terminal content: " + e.getMessage(), e);
            }
            return "";
        });
    }

    private String resolveServiceContent(String safeName) {
        if (project == null) {
            return "";
        }

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                List<RunConfigMonitorService.RunConfigInfo> configs =
                        RunConfigMonitorService.getRunConfigurations(project);
                LOG.debug("[Service] Resolving: " + safeName + ". Available configs: " + configs.size());

                for (RunConfigMonitorService.RunConfigInfo config : configs) {
                    String displayName = config.getDisplayName();
                    String widgetSafeName = displayName.replace(" ", "_").replaceAll("[^a-zA-Z0-9_]", "");
                    LOG.debug("[Service] - Candidate: " + displayName + " (Safe: " + widgetSafeName + ")");

                    if (widgetSafeName.equals(safeName)) {
                        String content = config.getContent();
                        LOG.debug("[Service] Match found! Content length: "
                                + (content != null ? content.length() : "null"));
                        return content;
                    }
                }
                LOG.debug("[Service] No matching service found for: " + safeName);
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                LOG.error("[Service] Error resolving service content: " + e.getMessage(), e);
            }
            return "";
        });
    }

    private boolean isImageAttachment(ClaudeSession.Attachment att) {
        if (att == null) {
            return false;
        }
        String mediaType = att.mediaType != null ? att.mediaType : "";
        return mediaType.startsWith("image/") && att.data != null;
    }

    private JsonObject createImageBlock(ClaudeSession.Attachment att) {
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image");

        JsonObject source = new JsonObject();
        source.addProperty("type", "base64");
        source.addProperty("media_type", att.mediaType);
        source.addProperty("data", att.data);
        imageBlock.add("source", source);

        return imageBlock;
    }

    private JsonObject createTextBlock(String text) {
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", text);
        return textBlock;
    }

    private String generateAttachmentSummary(List<ClaudeSession.Attachment> attachments) {
        int imageCount = 0;
        List<String> names = new ArrayList<>();

        for (ClaudeSession.Attachment att : attachments) {
            if (att != null && att.fileName != null && !att.fileName.isEmpty()) {
                names.add(att.fileName);
            }
            String mediaType = att != null && att.mediaType != null ? att.mediaType : "";
            if (mediaType.startsWith("image/")) {
                imageCount++;
            }
        }

        if (names.isEmpty()) {
            if (imageCount > 0) {
                return "[Uploaded " + imageCount + " image(s)]";
            }
            return "[Uploaded attachment(s)]";
        }

        if (names.size() > 3) {
            return "[Uploaded Attachments: " + String.join(", ", names.subList(0, 3)) + ", ...]";
        }
        return "[Uploaded Attachments: " + String.join(", ", names) + "]";
    }

    private String readFileContent(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists() || !file.isFile() || !file.canRead()) {
                LOG.warn("[Codex Context] File not accessible: " + filePath);
                return null;
            }

            long fileSize = file.length();
            if (fileSize > maxFileSizeBytes) {
                LOG.info("[Codex Context] File too large, reading first "
                        + (maxFileSizeBytes / 1024)
                        + "KB: " + filePath + " (" + fileSize + " bytes)");
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[maxFileSizeBytes];
                    int bytesRead = fis.read(buffer);
                    if (bytesRead > 0) {
                        return new String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                                + "\n\n... (file truncated, showing first "
                                + (maxFileSizeBytes / 1024)
                                + "KB of " + (fileSize / 1024) + "KB)";
                    }
                }
                return null;
            }

            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            LOG.info("[Codex Context] Read file content: " + filePath + " (" + fileSize + " bytes)");
            return content;
        } catch (Exception e) {
            LOG.warn("[Codex Context] Failed to read file: " + filePath + ", error: " + e.getMessage());
            return null;
        }
    }

    private String getFileExtension(String filePath) {
        if (filePath == null) {
            return "";
        }
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filePath.length() - 1) {
            return filePath.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    private String formatCodexReplaySegment(ClaudeSession.Message message) {
        if (message == null) {
            return "";
        }
        if (message.type != ClaudeSession.Message.Type.USER && message.type != ClaudeSession.Message.Type.ASSISTANT) {
            return "";
        }
        if (message.type == ClaudeSession.Message.Type.USER && isToolResultOnlyUserMessage(message)) {
            return "";
        }

        String content = extractReplayContent(message);
        if (content.isEmpty()) {
            return "";
        }

        String label = message.type == ClaudeSession.Message.Type.USER ? "User" : "Assistant";
        String attachmentNote = buildReplayAttachmentNote(message);
        return attachmentNote.isEmpty()
                ? label + ":\n" + content
                : label + ":\n" + content + "\n" + attachmentNote;
    }

    private String extractReplayContent(ClaudeSession.Message message) {
        if (message == null) {
            return "";
        }

        if (message.content != null) {
            String trimmed = message.content.trim();
            if (!trimmed.isEmpty() && !"[tool_result]".equals(trimmed)) {
                return trimmed;
            }
        }

        if (message.raw == null) {
            return "";
        }
        if (!message.raw.has("message") || !message.raw.get("message").isJsonObject()) {
            return "";
        }

        JsonObject rawMessage = message.raw.getAsJsonObject("message");
        if (!rawMessage.has("content") || !rawMessage.get("content").isJsonArray()) {
            return "";
        }

        JsonArray contentBlocks = rawMessage.getAsJsonArray("content");
        StringBuilder sb = new StringBuilder();
        for (JsonElement blockEl : contentBlocks) {
            if (!blockEl.isJsonObject()) {
                continue;
            }
            JsonObject block = blockEl.getAsJsonObject();
            if (!block.has("type") || !"text".equals(block.get("type").getAsString())) {
                continue;
            }
            if (!block.has("text") || block.get("text").isJsonNull()) {
                continue;
            }
            String text = block.get("text").getAsString().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private boolean isLiveUserPrompt(ClaudeSession.Message message, String currentInput) {
        if (message == null || message.type != ClaudeSession.Message.Type.USER) {
            return false;
        }
        if (isToolResultOnlyUserMessage(message)) {
            return false;
        }

        String content = extractReplayContent(message);
        if (content.isEmpty()) {
            return false;
        }

        String normalizedInput = currentInput != null ? currentInput.trim() : "";
        return normalizedInput.isEmpty() || content.equals(normalizedInput);
    }

    private boolean isToolResultOnlyUserMessage(ClaudeSession.Message message) {
        if (message == null || message.type != ClaudeSession.Message.Type.USER) {
            return false;
        }

        if ("[tool_result]".equals(message.content != null ? message.content.trim() : "")) {
            return true;
        }
        if (message.raw == null) {
            return false;
        }

        JsonArray contentBlocks = null;
        if (message.raw.has("content") && message.raw.get("content").isJsonArray()) {
            contentBlocks = message.raw.getAsJsonArray("content");
        } else if (message.raw.has("message") && message.raw.get("message").isJsonObject()) {
            JsonObject rawMessage = message.raw.getAsJsonObject("message");
            if (rawMessage.has("content") && rawMessage.get("content").isJsonArray()) {
                contentBlocks = rawMessage.getAsJsonArray("content");
            }
        }
        if (contentBlocks == null) {
            return false;
        }

        for (JsonElement blockEl : contentBlocks) {
            if (!blockEl.isJsonObject()) {
                continue;
            }
            JsonObject block = blockEl.getAsJsonObject();
            if (block.has("type") && "tool_result".equals(block.get("type").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private List<ClaudeSession.Message> collectCodexReplayMessages(List<ClaudeSession.Message> messages, String currentInput) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<ClaudeSession.Message> snapshot;
        synchronized (messages) {
            snapshot = new ArrayList<>(messages);
        }
        if (snapshot.isEmpty()) {
            return List.of();
        }

        int historyLimit = snapshot.size();
        if (historyLimit > 0 && isLiveUserPrompt(snapshot.get(historyLimit - 1), currentInput)) {
            historyLimit -= 1;
        }
        if (historyLimit <= 0) {
            return List.of();
        }
        return new ArrayList<>(snapshot.subList(0, historyLimit));
    }

    private String buildReplayAttachmentNote(ClaudeSession.Message message) {
        List<ClaudeSession.Attachment> attachments = extractReplayImageAttachments(message);
        if (attachments.isEmpty()) {
            return "";
        }
        int count = attachments.size();
        return count == 1
                ? "[This preserved user turn included 1 image attachment. It will be reattached with the current request.]"
                : "[This preserved user turn included " + count + " image attachments. They will be reattached with the current request.]";
    }

    private List<ClaudeSession.Attachment> extractReplayImageAttachments(ClaudeSession.Message message) {
        List<ClaudeSession.Attachment> attachments = new ArrayList<>();
        if (message == null || message.raw == null) {
            return attachments;
        }

        JsonObject rawMessage = null;
        if (message.raw.has("message") && message.raw.get("message").isJsonObject()) {
            rawMessage = message.raw.getAsJsonObject("message");
        }
        if (rawMessage == null || !rawMessage.has("content") || !rawMessage.get("content").isJsonArray()) {
            return attachments;
        }

        JsonArray contentBlocks = rawMessage.getAsJsonArray("content");
        for (JsonElement blockEl : contentBlocks) {
            if (!blockEl.isJsonObject()) {
                continue;
            }
            JsonObject block = blockEl.getAsJsonObject();
            if (!block.has("type") || !"image".equals(block.get("type").getAsString())) {
                continue;
            }

            if (block.has("source") && block.get("source").isJsonObject()) {
                JsonObject source = block.getAsJsonObject("source");
                if (!source.has("data") || source.get("data").isJsonNull()) {
                    continue;
                }
                String mediaType = source.has("media_type") && !source.get("media_type").isJsonNull()
                        ? source.get("media_type").getAsString()
                        : "image/png";
                attachments.add(new ClaudeSession.Attachment(
                        null,
                        mediaType,
                        source.get("data").getAsString()
                ));
            }
        }

        return attachments;
    }

    private String truncateReplaySegment(String segment, int maxLength) {
        if (segment == null || segment.isEmpty()) {
            return "";
        }
        if (maxLength <= 0) {
            return "";
        }
        if (segment.length() <= maxLength) {
            return segment;
        }

        String marker = "\n...[truncated]";
        int sliceLength = Math.max(0, maxLength - marker.length());
        if (sliceLength <= 0) {
            return marker.trim();
        }
        return segment.substring(0, sliceLength) + marker;
    }

    private String getImageExtension(String mediaType) {
        if (mediaType == null) {
            return ".png";
        }
        switch (mediaType.toLowerCase()) {
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            case "image/bmp":
                return ".bmp";
            case "image/svg+xml":
                return ".svg";
            case "image/png":
            default:
                return ".png";
        }
    }
}
