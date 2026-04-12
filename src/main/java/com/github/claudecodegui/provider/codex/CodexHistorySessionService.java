package com.github.claudecodegui.provider.codex;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads raw Codex session messages and normalizes tool calls for the webview.
 */
class CodexHistorySessionService {

    private static final Logger LOG = Logger.getInstance(CodexHistorySessionService.class);

    private final Path sessionsDir;
    private final Gson gson;

    CodexHistorySessionService(Path sessionsDir, Gson gson) {
        this.sessionsDir = sessionsDir;
        this.gson = gson;
    }

    String getSessionMessagesAsJson(String sessionId) {
        try {
            List<Path> sessionFiles = findSessionFiles(sessionId);
            if (sessionFiles.isEmpty()) {
                LOG.warn("[CodexHistoryReader] Session file not found for: " + sessionId);
                return gson.toJson(new ArrayList<>());
            }

            List<CodexHistoryReader.CodexMessage> messages = new ArrayList<>();
            for (Path sessionFile : sessionFiles) {
                try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) {
                            continue;
                        }

                        try {
                            CodexHistoryReader.CodexMessage msg = gson.fromJson(line, CodexHistoryReader.CodexMessage.class);
                            if (msg != null) {
                                messages.add(transformFunctionCall(msg));
                            }
                        } catch (Exception e) {
                            LOG.debug("[CodexHistoryReader] Failed to parse message: " + e.getMessage());
                        }
                    }
                }
            }

            messages.sort(Comparator.comparing(
                    (CodexHistoryReader.CodexMessage msg) -> msg.timestamp != null ? msg.timestamp : ""
            ));
            return gson.toJson(messages);
        } catch (Exception e) {
            LOG.error("[CodexHistoryReader] Failed to read session messages: " + e.getMessage(), e);
            return gson.toJson(new ArrayList<>());
        }
    }

    private List<Path> findSessionFiles(String sessionId) throws IOException {
        if (!Files.exists(sessionsDir)) {
            return new ArrayList<>();
        }

        try (Stream<Path> paths = Files.walk(sessionsDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jsonl"))
                    .filter(path -> matchesSessionId(path, sessionId))
                    .sorted()
                    .toList();
        }
    }

    private boolean matchesSessionId(Path path, String sessionId) {
        String fileName = path.getFileName().toString();
        if (fileName.equals(sessionId + ".jsonl")
                || fileName.startsWith(sessionId + ".")
                || fileName.endsWith("-" + sessionId + ".jsonl")) {
            return true;
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                CodexHistoryReader.CodexMessage msg = gson.fromJson(line, CodexHistoryReader.CodexMessage.class);
                if (msg == null || !"session_meta".equals(msg.type) || msg.payload == null) {
                    continue;
                }
                return msg.payload.has("id")
                        && !msg.payload.get("id").isJsonNull()
                        && sessionId.equals(msg.payload.get("id").getAsString());
            }
        } catch (Exception e) {
            LOG.debug("[CodexHistoryReader] Failed to inspect session file: " + path + " - " + e.getMessage());
        }

        return false;
    }

    private CodexHistoryReader.CodexMessage transformFunctionCall(CodexHistoryReader.CodexMessage msg) {
        if (msg == null || !"response_item".equals(msg.type) || msg.payload == null) {
            return msg;
        }

        JsonObject payload = msg.payload;
        if (!payload.has("type") || !"function_call".equals(payload.get("type").getAsString())) {
            return msg;
        }
        if (!payload.has("name") || !"shell_command".equals(payload.get("name").getAsString())) {
            return msg;
        }
        if (!payload.has("arguments")) {
            return msg;
        }

        try {
            String argumentsStr = payload.get("arguments").getAsString();
            JsonObject arguments = gson.fromJson(argumentsStr, JsonObject.class);

            if (arguments != null && arguments.has("command")) {
                String command = arguments.get("command").getAsString();
                if (isFileViewingCommand(command)) {
                    payload.addProperty("name", "read");
                    LOG.debug("[CodexHistoryReader] Transformed shell_command to read for: " + command);
                }
            }
        } catch (Exception e) {
            LOG.debug("[CodexHistoryReader] Failed to parse arguments: " + e.getMessage());
        }

        return msg;
    }

    private boolean isFileViewingCommand(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        String trimmed = command.trim();
        return trimmed.matches("^(pwd|ls|cat|head|tail|tree|file|stat)\\b.*")
                || trimmed.matches("^sed\\s+-n\\s+.*");
    }
}
