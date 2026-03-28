package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.skill.CodexSkillService;
import com.github.claudecodegui.util.ToolkitVersionUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.ByteArrayOutputStream;
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

public class ImpeccableHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(ImpeccableHandler.class);
    private static final String[] SUPPORTED_TYPES = {
            "get_impeccable_status",
            "install_impeccable",
            "update_impeccable"
    };
    private static final String METADATA_FILE = ".cc-ai-toolkit-impeccable.json";
    private static final String VERSION_SOURCE = "pbakaus/impeccable";
    private static final String PACKAGE_JSON_URL = "https://raw.githubusercontent.com/pbakaus/impeccable/main/package.json";
    private static final String ARCHIVE_URL = "https://codeload.github.com/pbakaus/impeccable/zip/refs/heads/main";
    private static final String ARCHIVE_ROOT = "impeccable-main/";
    private static final String PACKAGE_JSON_ENTRY = ARCHIVE_ROOT + "package.json";
    private static final Set<String> OFFICIAL_COMMAND_NAMES = Set.of(
            "adapt",
            "animate",
            "arrange",
            "audit",
            "bolder",
            "clarify",
            "colorize",
            "critique",
            "delight",
            "distill",
            "extract",
            "frontend-design",
            "harden",
            "normalize",
            "onboard",
            "optimize",
            "overdrive",
            "polish",
            "quieter",
            "teach-impeccable",
            "typeset"
    );
    private static final Set<String> READY_COMMAND_NAMES = Set.of(
            "teach-impeccable",
            "audit",
            "critique",
            "clarify",
            "normalize",
            "harden",
            "polish",
            "typeset",
            "arrange",
            "extract"
    );
    private static final int READY_COMMAND_THRESHOLD = 6;
    private static final long REMOTE_VERSION_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static volatile String cachedLatestVersion;
    private static volatile long cachedLatestVersionCheckedAt;

    private final AtomicBoolean installInProgress = new AtomicBoolean(false);
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public ImpeccableHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_impeccable_status":
                handleGetStatus(content);
                return true;
            case "install_impeccable":
                handleInstall(content, false);
                return true;
            case "update_impeccable":
                handleInstall(content, true);
                return true;
            default:
                return false;
        }
    }

    private void handleGetStatus(String content) {
        CompletableFuture.runAsync(() -> {
            ImpeccableProviderConfig providerConfig = resolveProvider(content);
            sendStatus(buildStatus(providerConfig));
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[ImpeccableHandler] Failed to get Impeccable status: " + ex.getMessage(), ex);
            sendStatus(buildErrorStatus(resolveProvider(content), "Failed to check Impeccable status: " + ex.getMessage()));
            return null;
        });
    }

    private void handleInstall(String content, boolean updateRequested) {
        ImpeccableProviderConfig providerConfig = resolveProvider(content);
        if (providerConfig == null) {
            sendInstallResult(false, null, "Impeccable onboarding is only available for Claude Code and Codex.", null);
            return;
        }

        if (!installInProgress.compareAndSet(false, true)) {
            LOG.info("[ImpeccableHandler] Ignoring duplicate install request because another task is running.");
            sendInstallBusyResult(providerConfig);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                Path installRoot = resolveInstallRoot();
                if (installRoot == null) {
                    sendInstallResult(false, providerConfig, "Cannot determine the current project directory for Impeccable installation.", null);
                    return;
                }

                ImpeccableProjectLayout projectLayout = resolveProjectLayout(providerConfig, installRoot);
                if (projectLayout.projectRoot == null || projectLayout.baseSkillsDir == null) {
                    sendInstallResult(false, providerConfig, "Cannot determine the current project directory for Impeccable installation.", null);
                    return;
                }

                StringBuilder logs = new StringBuilder();
                appendInstallLog(logs, providerConfig, "Downloading official Impeccable bundle...");
                InstallBundle installBundle = downloadAndInstallOfficialBundle(providerConfig, projectLayout, logs);
                if (installBundle.installedSkills == 0) {
                    sendInstallResult(false, providerConfig, "Failed to install Impeccable because no official skills were extracted.", logs.toString());
                    return;
                }

                persistInstalledVersion(projectLayout, installBundle.version);
                appendInstallLog(
                        logs,
                        providerConfig,
                        "Installed " + installBundle.installedSkills + " Impeccable skills into " + projectLayout.baseSkillsDir + "."
                );

                sendInstallResult(
                        true,
                        providerConfig,
                        updateRequested
                                ? "Impeccable has been updated for " + providerConfig.providerLabel + "."
                                : "Impeccable has been installed for " + providerConfig.providerLabel + ".",
                        logs.toString()
                );
                sendStatus(buildStatus(providerConfig));
            } catch (Exception e) {
                LOG.error("[ImpeccableHandler] Failed to install Impeccable: " + e.getMessage(), e);
                sendInstallResult(false, providerConfig, "Failed to install Impeccable: " + e.getMessage(), null);
            } finally {
                installInProgress.set(false);
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[ImpeccableHandler] Unexpected install error: " + ex.getMessage(), ex);
            sendInstallResult(false, providerConfig, "Failed to install Impeccable: " + ex.getMessage(), null);
            installInProgress.set(false);
            return null;
        });
    }

    private JsonObject buildStatus(ImpeccableProviderConfig providerConfig) {
        if (providerConfig == null) {
            return buildErrorStatus(null, "Impeccable onboarding is only available for Claude Code and Codex.");
        }

        JsonObject status = new JsonObject();
        status.addProperty("provider", providerConfig.providerId);
        status.addProperty("providerLabel", providerConfig.providerLabel);
        status.addProperty("commandPrefix", providerConfig.commandPrefix);
        status.addProperty("installed", false);

        Path workspacePath = getWorkspacePath();
        ImpeccableProjectLayout projectLayout = resolveProjectLayout(providerConfig, workspacePath);
        if (projectLayout.projectRoot != null) {
            status.addProperty("projectRoot", projectLayout.projectRoot.toString());
        }
        if (projectLayout.baseSkillsDir != null) {
            status.addProperty("baseSkillsDir", projectLayout.baseSkillsDir.toString());
        }
        if (projectLayout.legacyPromptDir != null) {
            status.addProperty("legacyPromptDir", projectLayout.legacyPromptDir.toString());
        }
        if (projectLayout.metadataFile != null) {
            status.addProperty("metadataFile", projectLayout.metadataFile.toString());
        }
        status.addProperty("installCommand", buildInstallCommandPreview(providerConfig, projectLayout.baseSkillsDir));

        Set<String> installedSkillNames = collectInstalledSkillNames(projectLayout.baseSkillsDir);
        Set<String> legacyPromptNames = collectLegacyPromptNames(projectLayout.legacyPromptDir);
        Set<String> plainAvailableNames = new TreeSet<>();
        plainAvailableNames.addAll(installedSkillNames);
        plainAvailableNames.addAll(legacyPromptNames);

        JsonArray availableCommands = new JsonArray();
        for (String skillName : new TreeSet<>(installedSkillNames)) {
            availableCommands.add(skillName);
        }
        for (String promptName : new TreeSet<>(legacyPromptNames)) {
            availableCommands.add("/prompts:" + promptName);
        }

        boolean hasFrontendDesign = plainAvailableNames.contains("frontend-design");
        boolean hasTeachCommand = plainAvailableNames.contains("teach-impeccable");
        int readyCommandCount = countReadyCommands(plainAvailableNames);
        int skillCount = installedSkillNames.size();
        int legacyPromptCount = legacyPromptNames.size();
        String installedVersion = resolveInstalledVersion(projectLayout);
        String latestVersion = resolveLatestVersion();

        status.add("availableCommands", availableCommands);
        status.addProperty("skillCount", skillCount);
        status.addProperty("legacyPromptCount", legacyPromptCount);
        status.addProperty("hasFrontendDesign", hasFrontendDesign);
        status.addProperty("hasTeachCommand", hasTeachCommand);
        ToolkitVersionUtil.applyVersionInfo(status, installedVersion, latestVersion);

        if (projectLayout.projectRoot == null) {
            status.addProperty("state", "error");
            status.addProperty("message", "Cannot determine the current project directory for Impeccable.");
            return status;
        }

        if (hasFrontendDesign && hasTeachCommand && readyCommandCount >= READY_COMMAND_THRESHOLD) {
            status.addProperty("state", "ready");
            status.addProperty("installed", true);
            status.addProperty("message", "Impeccable is ready in the current project.");
            return status;
        }

        boolean baseSkillsDirExists = projectLayout.baseSkillsDir != null && Files.isDirectory(projectLayout.baseSkillsDir);
        boolean legacyPromptDirExists = projectLayout.legacyPromptDir != null && Files.isDirectory(projectLayout.legacyPromptDir);
        if (baseSkillsDirExists || legacyPromptDirExists || skillCount > 0 || legacyPromptCount > 0) {
            status.addProperty("state", "partial");
            status.addProperty("message", "Found an incomplete Impeccable setup. Use one-click install to sync the official skills.");
            return status;
        }

        status.addProperty("state", "missing");
        status.addProperty("message", "Impeccable is not installed for this project yet. Use one-click install to copy the official skills.");
        return status;
    }

    private JsonObject buildErrorStatus(ImpeccableProviderConfig providerConfig, String message) {
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
        status.addProperty("legacyPromptCount", 0);
        status.addProperty("hasFrontendDesign", false);
        status.addProperty("hasTeachCommand", false);
        status.addProperty("hasUpdate", false);
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

    private ImpeccableProjectLayout resolveProjectLayout(ImpeccableProviderConfig providerConfig, Path workspacePath) {
        if (providerConfig == null || workspacePath == null) {
            return new ImpeccableProjectLayout(null, null, null, null);
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
        Path legacyPromptDir = providerConfig.relativeLegacyPromptDir != null
                ? bestRoot.resolve(providerConfig.relativeLegacyPromptDir)
                : null;
        Path metadataFile = baseSkillsDir.resolve(METADATA_FILE);
        return new ImpeccableProjectLayout(bestRoot, baseSkillsDir, legacyPromptDir, metadataFile);
    }

    private int scoreProjectRoot(ImpeccableProviderConfig providerConfig, Path candidateRoot) {
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
        if (providerConfig.relativeLegacyPromptDir != null) {
            Path legacyPromptDir = candidateRoot.resolve(providerConfig.relativeLegacyPromptDir);
            if (Files.isDirectory(legacyPromptDir) && !collectLegacyPromptNames(legacyPromptDir).isEmpty()) {
                score += 2;
            }
        }
        return score;
    }

    private ImpeccableProviderConfig resolveProvider(String content) {
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
            return new ImpeccableProviderConfig(
                    "codex",
                    "Codex",
                    "$",
                    Paths.get(".codex", "skills"),
                    Paths.get(".codex", "prompts")
            );
        }
        if ("claude".equals(normalized) || "claude-code".equals(normalized)) {
            return new ImpeccableProviderConfig(
                    "claude",
                    "Claude Code",
                    "/",
                    Paths.get(".claude", "skills"),
                    null
            );
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

    private Set<String> collectLegacyPromptNames(Path legacyPromptDir) {
        Set<String> promptNames = new TreeSet<>();
        if (legacyPromptDir == null || !Files.isDirectory(legacyPromptDir)) {
            return promptNames;
        }

        for (String commandName : OFFICIAL_COMMAND_NAMES) {
            Path promptFile = legacyPromptDir.resolve(commandName + ".md");
            if (Files.isRegularFile(promptFile)) {
                promptNames.add(commandName);
            }
        }
        return promptNames;
    }

    private int countReadyCommands(Set<String> availableNames) {
        int count = 0;
        for (String commandName : READY_COMMAND_NAMES) {
            if (availableNames.contains(commandName)) {
                count++;
            }
        }
        return count;
    }

    private InstallBundle downloadAndInstallOfficialBundle(
            ImpeccableProviderConfig providerConfig,
            ImpeccableProjectLayout projectLayout,
            StringBuilder logs
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ARCHIVE_URL))
                .GET()
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "cc-ai-toolkit")
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " while downloading official Impeccable bundle.");
        }

        Files.createDirectories(projectLayout.baseSkillsDir);
        removeInstalledOfficialSkills(projectLayout.baseSkillsDir);

        String archiveSkillsPrefix = ARCHIVE_ROOT + normalizeArchivePath(providerConfig.relativeSkillsDir) + "/";
        int installedSkills = 0;
        String version = null;
        Set<String> installedSkillNames = new TreeSet<>();

        try (InputStream inputStream = response.body();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (PACKAGE_JSON_ENTRY.equals(entryName)) {
                    version = readVersionFromPackageEntry(zipInputStream);
                    zipInputStream.closeEntry();
                    continue;
                }

                if (!entryName.startsWith(archiveSkillsPrefix)) {
                    zipInputStream.closeEntry();
                    continue;
                }

                String relativePath = entryName.substring(archiveSkillsPrefix.length());
                if (relativePath.isEmpty()) {
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
                    String skillName = relativePath.split("/", 2)[0];
                    if (OFFICIAL_COMMAND_NAMES.contains(skillName) && installedSkillNames.add(skillName)) {
                        installedSkills++;
                        appendInstallLog(logs, providerConfig, "Installing skill: " + skillName);
                    }
                }
                zipInputStream.closeEntry();
            }
        }

        return new InstallBundle(installedSkills, version);
    }

    private String normalizeArchivePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String readVersionFromPackageEntry(InputStream inputStream) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            JsonObject packageJson = gson.fromJson(outputStream.toString(StandardCharsets.UTF_8), JsonObject.class);
            if (packageJson != null && packageJson.has("version")) {
                String version = packageJson.get("version").getAsString();
                return version == null || version.trim().isEmpty() ? null : version.trim();
            }
        } catch (Exception e) {
            LOG.warn("[ImpeccableHandler] Failed to parse version from package.json: " + e.getMessage());
        }
        return null;
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
            throw new IllegalStateException("Cannot verify Impeccable install path.");
        }
        Path normalizedBaseDir = baseDir.toAbsolutePath().normalize();
        if (!targetPath.startsWith(normalizedBaseDir)) {
            throw new IllegalStateException("Refusing to write outside the target Impeccable skills directory.");
        }
    }

    private String buildInstallCommandPreview(ImpeccableProviderConfig providerConfig, Path baseSkillsDir) {
        if (providerConfig == null || baseSkillsDir == null) {
            return "";
        }
        return "Download official Impeccable skills into \"" + baseSkillsDir + "\" for " + providerConfig.providerLabel + ".";
    }

    private String resolveInstalledVersion(ImpeccableProjectLayout projectLayout) {
        if (projectLayout == null || projectLayout.metadataFile == null) {
            return null;
        }
        return ToolkitVersionUtil.readInstalledVersionMetadata(projectLayout.metadataFile);
    }

    private void persistInstalledVersion(ImpeccableProjectLayout projectLayout, String installedVersion) {
        if (projectLayout == null || projectLayout.metadataFile == null) {
            return;
        }
        ToolkitVersionUtil.writeInstalledVersionMetadata(
                projectLayout.metadataFile,
                installedVersion,
                VERSION_SOURCE,
                LOG
        );
    }

    private String resolveLatestVersion() {
        long now = System.currentTimeMillis();
        String cachedVersion = cachedLatestVersion;
        if (cachedVersion != null && now - cachedLatestVersionCheckedAt < REMOTE_VERSION_TTL_MILLIS) {
            return cachedVersion;
        }

        synchronized (ImpeccableHandler.class) {
            long cachedAge = System.currentTimeMillis() - cachedLatestVersionCheckedAt;
            if (cachedLatestVersion != null && cachedAge < REMOTE_VERSION_TTL_MILLIS) {
                return cachedLatestVersion;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(PACKAGE_JSON_URL))
                        .GET()
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "cc-ai-toolkit")
                        .build();
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonObject packageJson = gson.fromJson(response.body(), JsonObject.class);
                    if (packageJson != null && packageJson.has("version")) {
                        String version = packageJson.get("version").getAsString();
                        if (version != null && !version.trim().isEmpty()) {
                            cachedLatestVersion = version.trim();
                            cachedLatestVersionCheckedAt = System.currentTimeMillis();
                            return cachedLatestVersion;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[ImpeccableHandler] Failed to fetch latest Impeccable version: " + e.getMessage());
            }

            cachedLatestVersionCheckedAt = System.currentTimeMillis();
            return cachedLatestVersion;
        }
    }

    private void appendInstallLog(StringBuilder logs, ImpeccableProviderConfig providerConfig, String line) {
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
                callJavaScript("window.updateImpeccableStatus", escapeJs(payload))
        );
    }

    private void sendInstallProgress(ImpeccableProviderConfig providerConfig, String logLine) {
        JsonObject progress = new JsonObject();
        if (providerConfig != null) {
            progress.addProperty("provider", providerConfig.providerId);
        }
        progress.addProperty("log", logLine);

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.impeccableInstallProgress", escapeJs(gson.toJson(progress)))
        );
    }

    private void sendInstallResult(boolean success, ImpeccableProviderConfig providerConfig, String message, String logs) {
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
                callJavaScript("window.impeccableInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    private void sendInstallBusyResult(ImpeccableProviderConfig providerConfig) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("busy", true);
        if (providerConfig != null) {
            result.addProperty("provider", providerConfig.providerId);
        }

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.impeccableInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    private static final class ImpeccableProviderConfig {
        private final String providerId;
        private final String providerLabel;
        private final String commandPrefix;
        private final Path relativeSkillsDir;
        private final Path relativeLegacyPromptDir;

        private ImpeccableProviderConfig(
                String providerId,
                String providerLabel,
                String commandPrefix,
                Path relativeSkillsDir,
                Path relativeLegacyPromptDir
        ) {
            this.providerId = providerId;
            this.providerLabel = providerLabel;
            this.commandPrefix = commandPrefix;
            this.relativeSkillsDir = relativeSkillsDir;
            this.relativeLegacyPromptDir = relativeLegacyPromptDir;
        }
    }

    private static final class ImpeccableProjectLayout {
        private final Path projectRoot;
        private final Path baseSkillsDir;
        private final Path legacyPromptDir;
        private final Path metadataFile;

        private ImpeccableProjectLayout(Path projectRoot, Path baseSkillsDir, Path legacyPromptDir, Path metadataFile) {
            this.projectRoot = projectRoot;
            this.baseSkillsDir = baseSkillsDir;
            this.legacyPromptDir = legacyPromptDir;
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
