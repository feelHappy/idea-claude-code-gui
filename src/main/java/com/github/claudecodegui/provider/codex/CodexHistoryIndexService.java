package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.cache.SessionIndexCache;
import com.github.claudecodegui.cache.SessionIndexManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles Codex session indexing, cache coordination, and full/incremental scans.
 */
class CodexHistoryIndexService {

    private static final Logger LOG = Logger.getInstance(CodexHistoryIndexService.class);

    private final Path sessionsDir;
    private final CodexHistoryParser parser;

    CodexHistoryIndexService(Path sessionsDir, CodexHistoryParser parser) {
        this.sessionsDir = sessionsDir;
        this.parser = parser;
    }

    List<CodexHistoryReader.SessionInfo> readAllSessions() throws IOException {
        return readAllSessionsWithCache("__all__");
    }

    private List<CodexHistoryReader.SessionInfo> readAllSessionsWithCache(String cacheKey) throws IOException {
        List<CodexHistoryReader.SessionInfo> sessions = new ArrayList<>();

        if (!Files.exists(sessionsDir) || !Files.isDirectory(sessionsDir)) {
            LOG.info("[CodexHistoryReader] Codex sessions directory not found: " + sessionsDir);
            return sessions;
        }

        SessionIndexCache cache = SessionIndexCache.getInstance();
        List<CodexHistoryReader.SessionInfo> cachedSessions = cache.getCodexSessions(cacheKey, sessionsDir);
        if (cachedSessions != null) {
            LOG.info("[CodexHistoryReader] Using memory cache for " + cacheKey + ", sessions: " + cachedSessions.size());
            return cachedSessions;
        }

        SessionIndexManager indexManager = SessionIndexManager.getInstance();
        SessionIndexManager.SessionIndex index = indexManager.readCodexIndex();
        SessionIndexManager.ProjectIndex projectIndex = index.projects.get(cacheKey);
        SessionIndexManager.UpdateType updateType = indexManager.getUpdateTypeRecursive(projectIndex, sessionsDir);

        if (updateType == SessionIndexManager.UpdateType.NONE) {
            LOG.info("[CodexHistoryReader] Using file index for " + cacheKey + ", sessions: " + projectIndex.sessions.size());
            sessions = restoreSessionsFromIndex(projectIndex);
            cache.updateCodexCache(cacheKey, sessionsDir, sessions);
            return sessions;
        }

        long startTime = System.currentTimeMillis();

        if (updateType == SessionIndexManager.UpdateType.INCREMENTAL && projectIndex != null) {
            LOG.info("[CodexHistoryReader] Codex incremental updates require thread-level re-aggregation, falling back to full scan");
            sessions = scanAllSessions();
            preserveExistingTitles(projectIndex, sessions);
        } else {
            LOG.info("[CodexHistoryReader] Full scan for Codex sessions");
            sessions = scanAllSessions();
            preserveExistingTitles(projectIndex, sessions);
        }

        long scanTime = System.currentTimeMillis() - startTime;
        LOG.info("[CodexHistoryReader] Scan completed in " + scanTime + "ms, sessions: " + sessions.size());

        updateCodexIndex(index, cacheKey, sessions);
        indexManager.saveCodexIndex(index);
        cache.updateCodexCache(cacheKey, sessionsDir, sessions);

        return sessions;
    }

    private void preserveExistingTitles(
            SessionIndexManager.ProjectIndex projectIndex,
            List<CodexHistoryReader.SessionInfo> sessions
    ) {
        if (projectIndex == null || projectIndex.sessions.isEmpty()) {
            return;
        }

        Map<String, String> existingTitles = new HashMap<>();
        for (SessionIndexManager.SessionIndexEntry entry : projectIndex.sessions) {
            if (entry.title != null && !entry.title.isEmpty()) {
                existingTitles.put(entry.sessionId, entry.title);
            }
        }

        if (existingTitles.isEmpty()) {
            return;
        }

        for (CodexHistoryReader.SessionInfo session : sessions) {
            String oldTitle = existingTitles.get(session.sessionId);
            if (oldTitle != null) {
                session.title = oldTitle;
            }
        }

        LOG.info("[CodexHistoryReader] Preserved " + existingTitles.size() + " existing titles from old index");
    }

    private List<CodexHistoryReader.SessionInfo> restoreSessionsFromIndex(SessionIndexManager.ProjectIndex projectIndex) {
        List<CodexHistoryReader.SessionInfo> sessions = new ArrayList<>();
        for (SessionIndexManager.SessionIndexEntry entry : projectIndex.sessions) {
            CodexHistoryReader.SessionInfo session = new CodexHistoryReader.SessionInfo();
            session.sessionId = entry.sessionId;
            session.title = entry.title;
            session.messageCount = entry.messageCount;
            session.lastTimestamp = entry.lastTimestamp;
            session.firstTimestamp = entry.firstTimestamp;
            session.cwd = entry.cwd;
            sessions.add(session);
        }
        return sessions;
    }

    private void updateCodexIndex(
            SessionIndexManager.SessionIndex index,
            String cacheKey,
            List<CodexHistoryReader.SessionInfo> sessions
    ) {
        SessionIndexManager.ProjectIndex projectIndex = new SessionIndexManager.ProjectIndex();
        projectIndex.lastDirScanTime = System.currentTimeMillis();
        projectIndex.fileCount = countSessionFiles();

        for (CodexHistoryReader.SessionInfo session : sessions) {
            SessionIndexManager.SessionIndexEntry entry = new SessionIndexManager.SessionIndexEntry();
            entry.sessionId = session.sessionId;
            entry.title = session.title;
            entry.messageCount = session.messageCount;
            entry.lastTimestamp = session.lastTimestamp;
            entry.firstTimestamp = session.firstTimestamp;
            entry.cwd = session.cwd;
            projectIndex.sessions.add(entry);
        }

        index.projects.put(cacheKey, projectIndex);
    }

    private int countSessionFiles() {
        try (Stream<Path> paths = Files.walk(sessionsDir)) {
            return (int) paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jsonl"))
                    .count();
        } catch (IOException e) {
            LOG.warn("[CodexHistoryReader] Failed to count Codex session files: " + e.getMessage());
            return 0;
        }
    }

    private List<CodexHistoryReader.SessionInfo> scanAllSessions() throws IOException {
        List<CodexHistoryReader.SessionInfo> parsedSessions = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(sessionsDir)) {
            List<Path> jsonlFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jsonl"))
                    .filter(CodexHistoryParser::isNonEmptyFile)
                    .collect(Collectors.toList());

            LOG.info("[CodexHistoryReader] Found " + jsonlFiles.size() + " Codex session files");

            for (Path sessionFile : jsonlFiles) {
                try {
                    CodexHistoryReader.SessionInfo session = parser.parseSessionFile(sessionFile);
                    if (session != null && parser.isValidSession(session)) {
                        parsedSessions.add(session);
                    }
                } catch (Exception e) {
                    LOG.warn("[CodexHistoryReader] Failed to parse session file: " + sessionFile + " - " + e.getMessage());
                }
            }
        }

        List<CodexHistoryReader.SessionInfo> sessions = aggregateSessionsById(parsedSessions);
        sessions.sort((a, b) -> Long.compare(b.lastTimestamp, a.lastTimestamp));
        LOG.info("[CodexHistoryReader] Successfully loaded " + sessions.size() + " valid Codex sessions");
        return sessions;
    }

    private List<CodexHistoryReader.SessionInfo> aggregateSessionsById(List<CodexHistoryReader.SessionInfo> parsedSessions) {
        Map<String, CodexHistoryReader.SessionInfo> aggregated = new HashMap<>();

        for (CodexHistoryReader.SessionInfo session : parsedSessions) {
            if (session == null || session.sessionId == null || session.sessionId.isEmpty()) {
                continue;
            }

            CodexHistoryReader.SessionInfo existing = aggregated.get(session.sessionId);
            if (existing == null) {
                aggregated.put(session.sessionId, session);
                continue;
            }

            existing.messageCount += session.messageCount;
            existing.lastTimestamp = Math.max(existing.lastTimestamp, session.lastTimestamp);

            if (existing.firstTimestamp == 0 || (session.firstTimestamp > 0 && session.firstTimestamp < existing.firstTimestamp)) {
                existing.firstTimestamp = session.firstTimestamp;
                if (session.title != null && !session.title.isEmpty()) {
                    existing.title = session.title;
                }
            }

            if ((existing.cwd == null || existing.cwd.isEmpty()) && session.cwd != null && !session.cwd.isEmpty()) {
                existing.cwd = session.cwd;
            }
        }

        return aggregated.values().stream()
                .sorted(Comparator.comparingLong((CodexHistoryReader.SessionInfo session) -> session.lastTimestamp).reversed())
                .collect(Collectors.toList());
    }

}
