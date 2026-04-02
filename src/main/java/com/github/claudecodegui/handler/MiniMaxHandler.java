package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.skill.CodexSkillService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MiniMaxHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(MiniMaxHandler.class);
    private static final String[] SUPPORTED_TYPES = {
            "get_minimax_status",
            "install_minimax",
            "update_minimax"
    };
    private static final String METADATA_FILE = ".cc-ai-toolkit-minimax.json";
    private static final String VERSION_SOURCE = "MiniMax-AI/skills";
    private static final String BRANCH_API_URL = "https://api.github.com/repos/MiniMax-AI/skills/branches/main";
    private static final String ARCHIVE_URL = "https://codeload.github.com/MiniMax-AI/skills/zip/refs/heads/main";
    private static final String ARCHIVE_ROOT = "skills-main/";
    private static final String ARCHIVE_SKILLS_PREFIX = ARCHIVE_ROOT + "skills/";
    private static final Set<String> OFFICIAL_COMMAND_NAMES = Set.of(
            "fullstack-dev",
            "minimax-xlsx",
            "minimax-pdf",
            "minimax-docx"
    );
    private static final long REMOTE_VERSION_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static volatile String cachedLatestVersion;
    private static volatile long cachedLatestVersionCheckedAt;

    private final AtomicBoolean installInProgress = new AtomicBoolean(false);
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public MiniMaxHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_minimax_status":
                handleGetStatus(content);
                return true;
            case "install_minimax":
                handleInstall(content, false);
                return true;
            case "update_minimax":
                handleInstall(content, true);
                return true;
            default:
                return false;
        }
    }

    private void handleGetStatus(String content) {
        CompletableFuture.runAsync(() -> {
            MiniMaxProviderConfig providerConfig = resolveProvider(content);
            sendStatus(buildStatus(providerConfig));
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[MiniMaxHandler] Failed to get MiniMax status: " + ex.getMessage(), ex);
            sendStatus(buildErrorStatus(resolveProvider(content), "Failed to check MiniMax status: " + ex.getMessage()));
            return null;
        });
    }

    private void handleInstall(String content, boolean updateRequested) {
        MiniMaxProviderConfig providerConfig = resolveProvider(content);
        if (providerConfig == null) {
            sendInstallResult(false, null, "MiniMax onboarding is only available for Claude Code and Codex.", null);
            return;
        }

        if (!installInProgress.compareAndSet(false, true)) {
            LOG.info("[MiniMaxHandler] Ignoring duplicate install request because another task is running.");
            sendInstallBusyResult(providerConfig);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                Path installRoot = resolveInstallRoot();
                if (installRoot == null) {
                    sendInstallResult(false, providerConfig, "Cannot determine the current project directory for MiniMax installation.", null);
                    return;
                }

                MiniMaxProjectLayout projectLayout = resolveProjectLayout(providerConfig, installRoot);
                if (projectLayout.projectRoot == null || projectLayout.baseSkillsDir == null) {
                    sendInstallResult(false, providerConfig, "Cannot determine the current project directory for MiniMax installation.", null);
                    return;
                }

                StringBuilder logs = new StringBuilder();
                appendInstallLog(logs, providerConfig, "Downloading MiniMax skills bundle...");
                InstallBundle installBundle = downloadAndInstallOfficialBundle(providerConfig, projectLayout, logs);
                if (installBundle.installedSkills == 0) {
                    sendInstallResult(false, providerConfig, "Failed to install MiniMax because no selected skills were extracted.", logs.toString());
                    return;
                }

                persistInstalledVersion(projectLayout, installBundle.version);
                appendInstallLog(
                        logs,
                        providerConfig,
                        "Installed " + installBundle.installedSkills + " MiniMax skills into " + projectLayout.baseSkillsDir + "."
                );

                sendInstallResult(
                        true,
                        providerConfig,
                        updateRequested
                                ? "MiniMax has been updated for " + providerConfig.providerLabel + "."
                                : "MiniMax has been installed for " + providerConfig.providerLabel + ".",
                        logs.toString()
                );
                sendStatus(buildStatus(providerConfig));
            } catch (Exception e) {
                LOG.error("[MiniMaxHandler] Failed to install MiniMax: " + e.getMessage(), e);
                sendInstallResult(false, providerConfig, "Failed to install MiniMax: " + e.getMessage(), null);
            } finally {
                installInProgress.set(false);
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[MiniMaxHandler] Unexpected install error: " + ex.getMessage(), ex);
            sendInstallResult(false, providerConfig, "Failed to install MiniMax: " + ex.getMessage(), null);
            installInProgress.set(false);
            return null;
        });
    }

    private JsonObject buildStatus(MiniMaxProviderConfig providerConfig) {
        if (providerConfig == null) {
            return buildErrorStatus(null, "MiniMax onboarding is only available for Claude Code and Codex.");
        }

        JsonObject status = new JsonObject();
        status.addProperty("provider", providerConfig.providerId);
        status.addProperty("providerLabel", providerConfig.providerLabel);
        status.addProperty("commandPrefix", providerConfig.commandPrefix);
        status.addProperty("installed", false);

        Path workspacePath = getWorkspacePath();
        MiniMaxProjectLayout projectLayout = resolveProjectLayout(providerConfig, workspacePath);
        if (projectLayout.projectRoot != null) {
            status.addProperty("projectRoot", projectLayout.projectRoot.toString());
        }
        if (projectLayout.baseSkillsDir != null) {
            status.addProperty("baseSkillsDir", projectLayout.baseSkillsDir.toString());
        }
        if (projectLayout.metadataFile != null) {
            status.addProperty("metadataFile", projectLayout.metadataFile.toString());
        }
        status.addProperty("installCommand", buildInstallCommandPreview(providerConfig, projectLayout.baseSkillsDir));

        Set<String> installedSkillNames = collectInstalledSkillNames(projectLayout.baseSkillsDir);
        JsonArray availableCommands = new JsonArray();
        for (String skillName : installedSkillNames) {
            availableCommands.add(skillName);
        }

        String installedVersion = resolveInstalledVersion(projectLayout);
        String latestVersion = resolveLatestVersion();
        boolean versionTrackingMissing = installedVersion == null && !installedSkillNames.isEmpty();
        boolean hasUpdate = false;

        status.add("availableCommands", availableCommands);
        status.addProperty("skillCount", installedSkillNames.size());
        status.addProperty("selectedSkillCount", OFFICIAL_COMMAND_NAMES.size());
        if (installedVersion != null && !installedVersion.trim().isEmpty()) {
            status.addProperty("installedVersion", installedVersion.trim());
        }
        if (latestVersion != null && !latestVersion.trim().isEmpty()) {
            status.addProperty("latestVersion", latestVersion.trim());
            hasUpdate = versionTrackingMissing || !latestVersion.trim().equals(installedVersion);
        }
        status.addProperty("hasUpdate", hasUpdate);
        status.addProperty("versionTrackingMissing", versionTrackingMissing);

        if (projectLayout.projectRoot == null) {
            status.addProperty("state", "error");
            status.addProperty("message", "Cannot determine the current project directory for MiniMax.");
            return status;
        }

        if (installedSkillNames.size() == OFFICIAL_COMMAND_NAMES.size()) {
            status.addProperty("state", "ready");
            status.addProperty("installed", true);
            status.addProperty("message", "MiniMax is ready in the current project.");
            return status;
        }

        boolean baseSkillsDirExists = projectLayout.baseSkillsDir != null && Files.isDirectory(projectLayout.baseSkillsDir);
        if (baseSkillsDirExists || !installedSkillNames.isEmpty()) {
            status.addProperty("state", "partial");
            status.addProperty("message", "Found a partial MiniMax setup. Use one-click install to sync the selected skills.");
            return status;
        }

        status.addProperty("state", "missing");
        status.addProperty("message", "MiniMax is not installed for this project yet. Use one-click install to copy the selected skills.");
        return status;
    }

    private JsonObject buildErrorStatus(MiniMaxProviderConfig providerConfig, String message) {
        JsonObject status = new JsonObject();
        if (providerConfig != null) {
            status.addProperty("provider", providerConfig.providerId);
            status.addProperty("providerLabel", providerConfig.providerLabel);
            status.addProperty("commandPrefix", providerConfig.commandPrefix);
        } else {
            status.addProperty("provider", "unknown");
            status.addProperty("providerLabel", "Unsupported");
            status.addProperty("commandPrefix", "");
        }
        status.addProperty("state", providerConfig == null ? "unsupported" : "error");
        status.addProperty("installed", false);
        status.addProperty("skillCount", 0);
        status.addProperty("selectedSkillCount", OFFICIAL_COMMAND_NAMES.size());
        status.addProperty("hasUpdate", false);
        status.addProperty("versionTrackingMissing", false);
        status.add("availableCommands", new JsonArray());
        status.addProperty("message", message);
        return status;
    }

    private Path getWorkspacePath() {
        if (context.getSession() != null) {
            String cwd = context.getSession().getCwd();
            if (cwd != null && !cwd.trim().isEmpty()) {
                return Paths.get(cwd).toAbsolutePath().normalize();
            }
        }
        if (context.getProject() != null && context.getProject().getBasePath() != null) {
            return Paths.get(context.getProject().getBasePath()).toAbsolutePath().normalize();
        }
        return null;
    }

    private Path resolveInstallRoot() {
        if (context.getProject() != null && context.getProject().getBasePath() != null) {
            return Paths.get(context.getProject().getBasePath()).toAbsolutePath().normalize();
        }
        return getWorkspacePath();
    }

    private MiniMaxProjectLayout resolveProjectLayout(MiniMaxProviderConfig providerConfig, Path workspacePath) {
        if (providerConfig == null || workspacePath == null) {
            return new MiniMaxProjectLayout(null, null, null);
        }

        LinkedHashSet<Path> candidateRoots = new LinkedHashSet<>();
        Path current = workspacePath;
        Path filesystemRoot = current.getRoot();
        String repoRoot = CodexSkillService.findRepoRoot(workspacePath.toString());
        Path repoRootPath = repoRoot != null && !repoRoot.trim().isEmpty()
                ? Paths.get(repoRoot).toAbsolutePath().normalize()
                : null;
        Path projectBasePath = context.getProject() != null && context.getProject().getBasePath() != null
                ? Paths.get(context.getProject().getBasePath()).toAbsolutePath().normalize()
                : null;

        while (current != null) {
            candidateRoots.add(current);
            if (repoRootPath != null && current.equals(repoRootPath)) {
                break;
            }
            if (current.equals(filesystemRoot)) {
                break;
            }
            current = current.getParent();
        }

        if (repoRootPath != null) {
            candidateRoots.add(repoRootPath);
        }
        if (projectBasePath != null) {
            candidateRoots.add(projectBasePath);
        }

        Path bestRoot = workspacePath;
        int bestScore = scoreProjectRoot(providerConfig, workspacePath);
        for (Path candidateRoot : candidateRoots) {
            int score = scoreProjectRoot(providerConfig, candidateRoot);
            if (score > bestScore) {
                bestRoot = candidateRoot;
                bestScore = score;
            }
        }

        Path baseSkillsDir = bestRoot.resolve(providerConfig.relativeSkillsDir);
        Path metadataFile = baseSkillsDir.resolve(METADATA_FILE);
        return new MiniMaxProjectLayout(bestRoot, baseSkillsDir, metadataFile);
    }

    private int scoreProjectRoot(MiniMaxProviderConfig providerConfig, Path candidateRoot) {
        if (providerConfig == null || candidateRoot == null) {
            return -1;
        }

        int score = 0;
        Path baseSkillsDir = candidateRoot.resolve(providerConfig.relativeSkillsDir);
        if (Files.isDirectory(baseSkillsDir)) {
            score += 4;
        }
        if (!collectInstalledSkillNames(baseSkillsDir).isEmpty()) {
            score += 8;
        }
        return score;
    }

    private MiniMaxProviderConfig resolveProvider(String content) {
        String rawProvider = context.getCurrentProvider();
        if (content != null && !content.trim().isEmpty()) {
            try {
                JsonObject jsonObject = gson.fromJson(content, JsonObject.class);
                if (jsonObject != null && jsonObject.has("provider")) {
                    rawProvider = jsonObject.get("provider").getAsString();
                }
            } catch (Exception e) {
                rawProvider = content;
            }
        }

        if (rawProvider == null) {
            return null;
        }

        String normalized = rawProvider.trim().toLowerCase(Locale.ROOT);
        if ("codex".equals(normalized)) {
            return new MiniMaxProviderConfig("codex", "Codex", "$", Paths.get(".codex", "skills"));
        }
        if ("claude".equals(normalized) || "claude-code".equals(normalized)) {
            return new MiniMaxProviderConfig("claude", "Claude Code", "/", Paths.get(".claude", "skills"));
        }
        return null;
    }

    private Set<String> collectInstalledSkillNames(Path baseSkillsDir) {
        Set<String> skillNames = new TreeSet<>();
        if (baseSkillsDir == null || !Files.isDirectory(baseSkillsDir)) {
            return skillNames;
        }

        for (String commandName : OFFICIAL_COMMAND_NAMES) {
            Path skillDir = baseSkillsDir.resolve(commandName);
            if (Files.isDirectory(skillDir)
                    && (Files.isRegularFile(skillDir.resolve("SKILL.md")) || Files.isRegularFile(skillDir.resolve("skill.md")))) {
                skillNames.add(commandName);
            }
        }
        return skillNames;
    }

    private InstallBundle downloadAndInstallOfficialBundle(
            MiniMaxProviderConfig providerConfig,
            MiniMaxProjectLayout projectLayout,
            StringBuilder logs
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ARCHIVE_URL))
                .GET()
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "cc-ai-toolkit")
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " while downloading MiniMax skills bundle.");
        }

        Files.createDirectories(projectLayout.baseSkillsDir);
        removeInstalledOfficialSkills(projectLayout.baseSkillsDir);

        int installedSkills = 0;
        Set<String> installedSkillNames = new TreeSet<>();
        String version = resolveLatestVersion();

        try (InputStream inputStream = response.body();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (!entryName.startsWith(ARCHIVE_SKILLS_PREFIX)) {
                    zipInputStream.closeEntry();
                    continue;
                }

                String relativePath = entryName.substring(ARCHIVE_SKILLS_PREFIX.length());
                if (relativePath.isEmpty()) {
                    zipInputStream.closeEntry();
                    continue;
                }

                String skillName = relativePath.split("/", 2)[0];
                if (!OFFICIAL_COMMAND_NAMES.contains(skillName)) {
                    zipInputStream.closeEntry();
                    continue;
                }

                Path targetPath = projectLayout.baseSkillsDir.resolve(relativePath.replace('/', java.io.File.separatorChar))
                        .toAbsolutePath()
                        .normalize();
                ensureChildPath(targetPath, projectLayout.baseSkillsDir);

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(zipInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    if (installedSkillNames.add(skillName)) {
                        installedSkills++;
                        appendInstallLog(logs, providerConfig, "Installing skill: " + skillName);
                    }
                }
                zipInputStream.closeEntry();
            }
        }

        return new InstallBundle(installedSkills, version);
    }

    private void removeInstalledOfficialSkills(Path baseSkillsDir) throws Exception {
        if (baseSkillsDir == null || !Files.isDirectory(baseSkillsDir)) {
            return;
        }

        for (String commandName : OFFICIAL_COMMAND_NAMES) {
            Path skillDir = baseSkillsDir.resolve(commandName).toAbsolutePath().normalize();
            ensureChildPath(skillDir, baseSkillsDir);
            if (Files.exists(skillDir)) {
                deleteRecursively(skillDir);
            }
        }
    }

    private void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to delete " + current + ": " + e.getMessage(), e);
                }
            });
        }
    }

    private void ensureChildPath(Path targetPath, Path baseDir) {
        if (targetPath == null || baseDir == null) {
            throw new IllegalStateException("Cannot verify MiniMax install path.");
        }
        Path normalizedBaseDir = baseDir.toAbsolutePath().normalize();
        if (!targetPath.startsWith(normalizedBaseDir)) {
            throw new IllegalStateException("Refusing to write outside the target MiniMax skills directory.");
        }
    }

    private String buildInstallCommandPreview(MiniMaxProviderConfig providerConfig, Path baseSkillsDir) {
        if (providerConfig == null || baseSkillsDir == null) {
            return "";
        }
        return "Download selected MiniMax skills into \"" + baseSkillsDir + "\" for " + providerConfig.providerLabel + ".";
    }

    private String resolveInstalledVersion(MiniMaxProjectLayout projectLayout) {
        if (projectLayout == null || projectLayout.metadataFile == null || !Files.isRegularFile(projectLayout.metadataFile)) {
            return null;
        }
        try {
            JsonObject jsonObject = JsonParser.parseString(Files.readString(projectLayout.metadataFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (jsonObject.has("installedVersion")) {
                String version = jsonObject.get("installedVersion").getAsString();
                return version == null || version.trim().isEmpty() ? null : version.trim();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private void persistInstalledVersion(MiniMaxProjectLayout projectLayout, String installedVersion) {
        if (projectLayout == null || projectLayout.metadataFile == null || installedVersion == null || installedVersion.trim().isEmpty()) {
            return;
        }
        try {
            Path parent = projectLayout.metadataFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("installedVersion", installedVersion.trim());
            jsonObject.addProperty("packageName", VERSION_SOURCE);
            jsonObject.addProperty("updatedAt", Instant.now().toString());
            Files.writeString(projectLayout.metadataFile, gson.toJson(jsonObject), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[MiniMaxHandler] Failed to write metadata " + projectLayout.metadataFile + ": " + e.getMessage());
        }
    }

    private String resolveLatestVersion() {
        long now = System.currentTimeMillis();
        String cachedVersion = cachedLatestVersion;
        if (cachedVersion != null && now - cachedLatestVersionCheckedAt < REMOTE_VERSION_TTL_MILLIS) {
            return cachedVersion;
        }

        synchronized (MiniMaxHandler.class) {
            long cachedAge = System.currentTimeMillis() - cachedLatestVersionCheckedAt;
            if (cachedLatestVersion != null && cachedAge < REMOTE_VERSION_TTL_MILLIS) {
                return cachedLatestVersion;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(BRANCH_API_URL))
                        .GET()
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "cc-ai-toolkit")
                        .header("Accept", "application/vnd.github+json")
                        .build();
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonObject branchJson = gson.fromJson(response.body(), JsonObject.class);
                    if (branchJson != null && branchJson.has("commit")) {
                        JsonObject commit = branchJson.getAsJsonObject("commit");
                        if (commit != null && commit.has("sha")) {
                            String sha = commit.get("sha").getAsString();
                            if (sha != null && !sha.trim().isEmpty()) {
                                cachedLatestVersion = sha.trim().substring(0, Math.min(12, sha.trim().length()));
                                cachedLatestVersionCheckedAt = System.currentTimeMillis();
                                return cachedLatestVersion;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[MiniMaxHandler] Failed to fetch latest MiniMax version: " + e.getMessage());
            }

            cachedLatestVersionCheckedAt = System.currentTimeMillis();
            return cachedLatestVersion;
        }
    }

    private void appendInstallLog(StringBuilder logs, MiniMaxProviderConfig providerConfig, String line) {
        if (logs != null) {
            if (logs.length() > 0) {
                logs.append('\n');
            }
            logs.append(line);
        }
        sendInstallProgress(providerConfig, line);
    }

    private void sendStatus(JsonObject status) {
        String payload = gson.toJson(status);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateMiniMaxStatus", escapeJs(payload))
        );
    }

    private void sendInstallProgress(MiniMaxProviderConfig providerConfig, String logLine) {
        JsonObject progress = new JsonObject();
        if (providerConfig != null) {
            progress.addProperty("provider", providerConfig.providerId);
        }
        progress.addProperty("log", logLine);

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.miniMaxInstallProgress", escapeJs(gson.toJson(progress)))
        );
    }

    private void sendInstallResult(boolean success, MiniMaxProviderConfig providerConfig, String message, String logs) {
        JsonObject result = new JsonObject();
        result.addProperty("success", success);
        if (providerConfig != null) {
            result.addProperty("provider", providerConfig.providerId);
        }
        result.addProperty(success ? "message" : "error", message);
        if (logs != null && !logs.trim().isEmpty()) {
            result.addProperty("logs", logs);
        }

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.miniMaxInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    private void sendInstallBusyResult(MiniMaxProviderConfig providerConfig) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("busy", true);
        if (providerConfig != null) {
            result.addProperty("provider", providerConfig.providerId);
        }

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.miniMaxInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    private static final class MiniMaxProviderConfig {
        private final String providerId;
        private final String providerLabel;
        private final String commandPrefix;
        private final Path relativeSkillsDir;

        private MiniMaxProviderConfig(String providerId, String providerLabel, String commandPrefix, Path relativeSkillsDir) {
            this.providerId = providerId;
            this.providerLabel = providerLabel;
            this.commandPrefix = commandPrefix;
            this.relativeSkillsDir = relativeSkillsDir;
        }
    }

    private static final class MiniMaxProjectLayout {
        private final Path projectRoot;
        private final Path baseSkillsDir;
        private final Path metadataFile;

        private MiniMaxProjectLayout(Path projectRoot, Path baseSkillsDir, Path metadataFile) {
            this.projectRoot = projectRoot;
            this.baseSkillsDir = baseSkillsDir;
            this.metadataFile = metadataFile;
        }
    }

    private static final class InstallBundle {
        private final int installedSkills;
        private final String version;

        private InstallBundle(int installedSkills, String version) {
            this.installedSkills = installedSkills;
            this.version = version;
        }
    }
}
