package com.github.claudecodegui.settings;

import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds a project-scoped CODEX_HOME so Codex only sees the current project's
 * generated database MCP server while preserving the user's global auth/settings.
 */
public class CodexProjectHomeManager {

    private static final Logger LOG = Logger.getInstance(CodexProjectHomeManager.class);
    private static final String PROJECT_HOME_DIR_NAME = "codex-project-home";
    private static final String DB_MCP_MAIN_CLASS = "com.github.claudecodegui.dbmcp.DbMcpServerMain";
    private static final List<String> COPIED_FILES = List.of(
            "auth.json",
            "version.json",
            "cap_sid",
            ".personality_migration"
    );
    private static final List<String> COPIED_DIRECTORIES = List.of("skills", "memories");

    private final Gson gson;
    private final Path projectHomesRoot;

    public CodexProjectHomeManager() {
        this(new Gson(), new ConfigPathManager().getConfigDir().resolve(PROJECT_HOME_DIR_NAME));
    }

    CodexProjectHomeManager(Gson gson, Path projectHomesRoot) {
        this.gson = gson;
        this.projectHomesRoot = projectHomesRoot;
    }

    public String prepareProjectScopedHome(String projectPath, String codexHome) {
        Path normalizedProjectPath = normalizePath(projectPath);
        Path normalizedCodexHome = normalizePath(codexHome);
        if (normalizedProjectPath == null || normalizedCodexHome == null) {
            return codexHome;
        }

        Path projectHome = projectHomesRoot.resolve(buildProjectKey(normalizedProjectPath));
        try {
            Files.createDirectories(projectHome);
            syncSharedArtifacts(normalizedCodexHome, projectHome);

            CodexSettingsManager sourceSettings = new CodexSettingsManager(gson, normalizedCodexHome);
            Map<String, Object> sourceConfig = sourceSettings.readConfigToml();
            Map<String, Object> filteredConfig = filterConfigForProject(sourceConfig, normalizedProjectPath);

            CodexSettingsManager projectSettings = new CodexSettingsManager(gson, projectHome);
            projectSettings.writeConfigToml(filteredConfig);
            return projectHome.toString();
        } catch (Exception e) {
            LOG.warn("[CodexProjectHomeManager] Failed to prepare project-scoped CODEX_HOME for "
                    + normalizedProjectPath + ": " + e.getMessage());
            return codexHome;
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> filterConfigForProject(Map<String, Object> sourceConfig, Path projectPath) {
        Map<String, Object> filtered = deepCopyMap(sourceConfig);
        Object mcpServersObj = filtered.get("mcp_servers");
        if (!(mcpServersObj instanceof Map<?, ?> mcpServers)) {
            return filtered;
        }

        Map<String, Object> filteredMcpServers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mcpServers.entrySet()) {
            String serverId = String.valueOf(entry.getKey());
            Object serverConfigObj = entry.getValue();
            if (!(serverConfigObj instanceof Map<?, ?> serverConfig)) {
                filteredMcpServers.put(serverId, serverConfigObj);
                continue;
            }

            if (!isProjectDatabaseServer(serverConfig) || belongsToProject(serverConfig, projectPath)) {
                filteredMcpServers.put(serverId, deepCopyMap((Map<String, Object>) serverConfig));
            }
        }
        filtered.put("mcp_servers", filteredMcpServers);
        return filtered;
    }

    private void syncSharedArtifacts(Path sourceHome, Path projectHome) throws IOException {
        for (String fileName : COPIED_FILES) {
            copyIfPresent(sourceHome.resolve(fileName), projectHome.resolve(fileName));
        }
        for (String directoryName : COPIED_DIRECTORIES) {
            copyDirectoryIfPresent(sourceHome.resolve(directoryName), projectHome.resolve(directoryName));
        }
    }

    private void copyIfPresent(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private void copyDirectoryIfPresent(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(path -> {
                try {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else if (Files.isRegularFile(path)) {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private boolean isProjectDatabaseServer(Map<?, ?> serverConfig) {
        Object command = serverConfig.get("command");
        if (command == null) {
            return false;
        }
        Object argsObj = serverConfig.get("args");
        if (!(argsObj instanceof List<?> args)) {
            return false;
        }
        for (Object arg : args) {
            if (DB_MCP_MAIN_CLASS.equals(String.valueOf(arg))) {
                return true;
            }
        }
        return false;
    }

    private boolean belongsToProject(Map<?, ?> serverConfig, Path projectPath) {
        Object cwd = serverConfig.get("cwd");
        if (cwd == null) {
            return false;
        }
        Path serverProjectPath = normalizePath(String.valueOf(cwd));
        return projectPath.equals(serverProjectPath);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return deepCopyMap((Map<String, Object>) mapValue);
        }
        if (value instanceof List<?> listValue) {
            List<Object> copy = new ArrayList<>();
            for (Object item : listValue) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }

    private String buildProjectKey(Path projectPath) {
        String baseName = projectPath.getFileName() != null
                ? projectPath.getFileName().toString()
                : "project";
        String slug = baseName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (slug.isBlank()) {
            slug = "project";
        }
        return slug + "-" + shortHash(projectPath.toString());
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed, 0, 6);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash project path", e);
        }
    }

    private Path normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Path.of(path).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }
}
