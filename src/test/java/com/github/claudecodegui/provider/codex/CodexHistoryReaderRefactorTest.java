package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.provider.codex.CodexHistoryReader.CodexMessage;
import com.github.claudecodegui.provider.codex.CodexHistoryReader.ProjectStatistics;
import com.github.claudecodegui.provider.codex.CodexHistoryReader.SessionInfo;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CodexHistoryReaderRefactorTest {

    private final Gson gson = new Gson();

    @Test
    public void parserBuildsSessionInfoFromSessionFile() throws IOException {
        Path sessionsDir = Files.createTempDirectory("codex-history-parser");
        try {
            Path sessionFile = writeSessionFile(
                    sessionsDir,
                    "session-1",
                    line("2026-03-10T10:00:00Z", "session_meta", "{\"cwd\":\"/workspace/demo\",\"timestamp\":\"2026-03-10T10:00:00Z\"}"),
                    line("2026-03-10T10:01:00Z", "event_msg", "{\"type\":\"user_message\",\"message\":\"<command-name>review</command-name>\\n请帮我检查一下这个文件的改动是否安全\"}"),
                    line("2026-03-10T10:02:00Z", "response_item", "{\"type\":\"function_call\",\"name\":\"shell_command\",\"arguments\":\"{\\\"command\\\":\\\"pwd\\\"}\"}"),
                    line("2026-03-10T10:03:00Z", "response_item", "{\"type\":\"message\"}")
            );

            CodexHistoryParser parser = new CodexHistoryParser(new Gson());

            SessionInfo session = parser.parseSessionFile(sessionFile);

            assertNotNull(session);
            assertEquals("session-1", session.sessionId);
            assertEquals("/workspace/demo", session.cwd);
            assertEquals(2, session.messageCount);
            assertEquals(Instant.parse("2026-03-10T10:00:00Z").toEpochMilli(), session.firstTimestamp);
            assertEquals(Instant.parse("2026-03-10T10:03:00Z").toEpochMilli(), session.lastTimestamp);
            assertTrue(session.title.contains("review"));
            assertTrue(session.title.endsWith("..."));
            assertTrue(parser.isValidSession(session));
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    @Test
    public void readerAggregatesMultipleRolloutFilesByActualThreadId() throws IOException {
        Path sessionsDir = Files.createTempDirectory("codex-history-aggregate");
        try {
            String threadId = "test-thread-aggregate-codex-history";
            writeCustomSessionFile(
                    sessionsDir.resolve("2026/04/08/rollout-2026-04-08T03-49-17-084-" + threadId + ".jsonl"),
                    line("2026-04-08T03:49:17.084Z", "session_meta", "{\"id\":\"" + threadId + "\",\"cwd\":\"D:/WorkProject/idea-claude-code-gui\",\"timestamp\":\"2026-04-08T03:49:17.084Z\"}"),
                    line("2026-04-08T03:49:17.084Z", "event_msg", "{\"type\":\"user_message\",\"message\":\"First question\"}"),
                    line("2026-04-08T03:49:18.084Z", "response_item", "{\"type\":\"message\"}")
            );
            writeCustomSessionFile(
                    sessionsDir.resolve("2026/04/08/rollout-2026-04-08T05-46-10-449-" + threadId + ".jsonl"),
                    line("2026-04-08T05:46:10.449Z", "session_meta", "{\"id\":\"" + threadId + "\",\"cwd\":\"D:/WorkProject/idea-claude-code-gui\",\"timestamp\":\"2026-04-08T05:46:10.449Z\"}"),
                    line("2026-04-08T05:46:10.449Z", "event_msg", "{\"type\":\"user_message\",\"message\":\"Second question\"}"),
                    line("2026-04-08T05:46:11.449Z", "response_item", "{\"type\":\"message\"}")
            );

            CodexHistoryReader reader = new CodexHistoryReader(sessionsDir, gson);
            List<SessionInfo> sessions = reader.readAllSessions();

            assertEquals(1, sessions.size());
            assertEquals(threadId, sessions.get(0).sessionId);
            assertEquals("First question", sessions.get(0).title);
            assertEquals(2, sessions.get(0).messageCount);
            assertEquals(Instant.parse("2026-04-08T05:46:11.449Z").toEpochMilli(), sessions.get(0).lastTimestamp);
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    @Test
    public void sessionServiceTransformsFileViewingShellCommandToRead() throws IOException {
        Path sessionsDir = Files.createTempDirectory("codex-history-messages");
        try {
            writeSessionFile(
                    sessionsDir,
                    "session-2",
                    line("2026-03-10T10:00:00Z", "response_item", "{\"type\":\"function_call\",\"name\":\"shell_command\",\"arguments\":\"{\\\"command\\\":\\\"sed -n '1,20p' src/App.tsx\\\"}\"}"),
                    line("2026-03-10T10:01:00Z", "response_item", "{\"type\":\"function_call\",\"name\":\"shell_command\",\"arguments\":\"{\\\"command\\\":\\\"npm test\\\"}\"}")
            );

            CodexHistorySessionService service = new CodexHistorySessionService(sessionsDir, gson);
            Type listType = new TypeToken<List<CodexMessage>>() {}.getType();

            List<CodexMessage> messages = gson.fromJson(service.getSessionMessagesAsJson("session-2"), listType);

            assertEquals(2, messages.size());
            assertEquals("read", messages.get(0).payload.get("name").getAsString());
            assertEquals("shell_command", messages.get(1).payload.get("name").getAsString());
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    @Test
    public void sessionServiceLoadsMessagesFromAllRolloutFilesForSameThreadId() throws IOException {
        Path sessionsDir = Files.createTempDirectory("codex-history-session-load");
        try {
            String threadId = "test-thread-session-load-codex-history";
            writeCustomSessionFile(
                    sessionsDir.resolve("2026/04/08/rollout-2026-04-08T03-49-17-084-" + threadId + ".jsonl"),
                    line("2026-04-08T03:49:17.084Z", "session_meta", "{\"id\":\"" + threadId + "\",\"cwd\":\"/workspace/demo\"}"),
                    line("2026-04-08T03:49:18.084Z", "response_item", "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}")
            );
            writeCustomSessionFile(
                    sessionsDir.resolve("2026/04/08/rollout-2026-04-08T05-46-10-449-" + threadId + ".jsonl"),
                    line("2026-04-08T05:46:10.449Z", "session_meta", "{\"id\":\"" + threadId + "\",\"cwd\":\"/workspace/demo\"}"),
                    line("2026-04-08T05:46:11.449Z", "response_item", "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}")
            );

            CodexHistorySessionService service = new CodexHistorySessionService(sessionsDir, gson);
            Type listType = new TypeToken<List<CodexMessage>>() {}.getType();

            List<CodexMessage> messages = gson.fromJson(service.getSessionMessagesAsJson(threadId), listType);

            assertEquals(4, messages.size());
            assertEquals("2026-04-08T03:49:17.084Z", messages.get(0).timestamp);
            assertEquals("2026-04-08T05:46:11.449Z", messages.get(3).timestamp);
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    @Test
    public void usageAggregatorBuildsStatisticsFromSessionSummaries() throws IOException {
        Path sessionsDir = Files.createTempDirectory("codex-history-usage");
        try {
            writeSessionFile(
                    sessionsDir.resolve("2026/03/10"),
                    "session-3",
                    line("2026-03-10T10:00:00Z", "turn_context", "{\"model\":\"gpt-5.1\"}"),
                    line("2026-03-10T10:01:00Z", "event_msg", "{\"type\":\"user_message\",\"message\":\"Summarize test results\"}"),
                    line("2026-03-10T10:02:00Z", "event_msg", "{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":1000,\"output_tokens\":250,\"cached_input_tokens\":50}}}")
            );
            writeSessionFile(
                    sessionsDir.resolve("2026/03/16"),
                    "session-4",
                    line("2026-03-16T09:00:00Z", "turn_context", "{\"model\":\"gpt-5.1\"}"),
                    line("2026-03-16T09:01:00Z", "event_msg", "{\"type\":\"user_message\",\"message\":\"Explain the latest refactor\"}"),
                    line("2026-03-16T09:02:00Z", "event_msg", "{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":2000,\"output_tokens\":500,\"cached_input_tokens\":100}}}")
            );

            CodexUsageAggregator aggregator = new CodexUsageAggregator(sessionsDir, new CodexHistoryParser(new Gson()), new Gson());

            ProjectStatistics stats = aggregator.getProjectStatistics("all", 0);

            assertEquals("All Projects", stats.projectName);
            assertEquals(2, stats.totalSessions);
            assertEquals(3000, stats.totalUsage.inputTokens);
            assertEquals(750, stats.totalUsage.outputTokens);
            assertEquals(150, stats.totalUsage.cacheReadTokens);
            assertEquals(3900, stats.totalUsage.totalTokens);
            assertEquals(2, stats.sessions.size());
            assertEquals(2, stats.byModel.get(0).sessionCount);
            assertFalse(stats.dailyUsage.isEmpty());
            assertTrue(stats.estimatedCost > 0);
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    private Path writeSessionFile(Path parentDir, String sessionId, String... lines) throws IOException {
        Files.createDirectories(parentDir);
        Path file = parentDir.resolve(sessionId + ".jsonl");
        Files.write(file, List.of(lines));
        return file;
    }

    private Path writeCustomSessionFile(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(lines));
        return file;
    }

    private String line(String timestamp, String type, String payloadJson) {
        return "{\"timestamp\":\"" + timestamp + "\",\"type\":\"" + type + "\",\"payload\":" + payloadJson + "}";
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}
