package com.github.claudecodegui.cache;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CodexSessionIndexRefreshTest {

    @Test
    public void codexCacheInvalidatesWhenExistingSessionFileChanges() throws Exception {
        Path sessionsDir = Files.createTempDirectory("codex-cache-refresh");
        SessionIndexCache cache = SessionIndexCache.getInstance();
        try {
            cache.clearAll();
            Path sessionFile = writeSessionFile(sessionsDir, "session-1", "{\"type\":\"session_meta\"}");

            List<String> sessions = List.of("session-1");
            cache.updateCodexCache("__all__", sessionsDir, sessions);

            assertEquals(sessions, cache.getCodexSessions("__all__", sessionsDir));

            FileTime originalTime = Files.getLastModifiedTime(sessionFile);
            Files.writeString(sessionFile, "{\"type\":\"response_item\"}\n", java.nio.file.StandardOpenOption.APPEND);
            Files.setLastModifiedTime(sessionFile, FileTime.fromMillis(originalTime.toMillis() + 2_000));

            assertNull(cache.getCodexSessions("__all__", sessionsDir));
        } finally {
            cache.clearAll();
            deleteDirectory(sessionsDir);
        }
    }

    @Test
    public void codexIndexRequiresFullRefreshWhenExistingSessionFileChanges() throws Exception {
        Path sessionsDir = Files.createTempDirectory("codex-index-refresh");
        try {
            Path sessionFile = writeSessionFile(sessionsDir, "session-1", "{\"type\":\"session_meta\"}");
            long initialModified = Files.getLastModifiedTime(sessionFile).toMillis();

            SessionIndexManager.ProjectIndex projectIndex = new SessionIndexManager.ProjectIndex();
            projectIndex.fileCount = 1;
            projectIndex.lastDirScanTime = initialModified;
            projectIndex.sessions.add(new SessionIndexManager.SessionIndexEntry());

            SessionIndexManager manager = SessionIndexManager.getInstance();
            assertEquals(SessionIndexManager.UpdateType.NONE, manager.getUpdateTypeRecursive(projectIndex, sessionsDir));

            Files.writeString(sessionFile, "{\"type\":\"response_item\"}\n", java.nio.file.StandardOpenOption.APPEND);
            Files.setLastModifiedTime(sessionFile, FileTime.fromMillis(initialModified + 2_000));

            assertEquals(SessionIndexManager.UpdateType.FULL, manager.getUpdateTypeRecursive(projectIndex, sessionsDir));
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    private Path writeSessionFile(Path sessionsDir, String sessionId, String... lines) throws IOException {
        Path dayDir = sessionsDir.resolve("2026").resolve("04").resolve("02");
        Files.createDirectories(dayDir);
        Path sessionFile = dayDir.resolve(sessionId + ".jsonl");
        Files.write(sessionFile, List.of(lines));
        return sessionFile;
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
