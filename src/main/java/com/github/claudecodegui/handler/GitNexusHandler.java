package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.skill.CodexSkillService;
import com.github.claudecodegui.util.EditorFileUtils;
import com.github.claudecodegui.util.PlatformUtils;
import com.github.claudecodegui.util.ToolkitVersionUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * @description GitNexus 安装与索引管理处理器，负责检测仓库图谱状态并执行一键安装、重建索引。
 * @author zyl
 * @date 2026/03/23
 */
public class GitNexusHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(GitNexusHandler.class);
    private static final String GITNEXUS_PACKAGE_NAME = "gitnexus";
    private static final String GITNEXUS_METADATA_FILE = ".cc-ai-toolkit.json";
    private static final String[] SUPPORTED_TYPES = {
            "get_gitnexus_status",
            "install_gitnexus",
            "update_gitnexus",
            "reindex_gitnexus"
    };
    /**
     * 安装互斥锁——实例级别而非 static，防止切换项目后锁泄漏。
     */
    private final AtomicBoolean installInProgress = new AtomicBoolean(false);
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile(
            "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|[@-Z\\\\-_])"
    );
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern LOW_SIGNAL_LOG_PATTERN = Pattern.compile("^[\\s|│┌┐└┘├┤┬┴┼═─━•·oO0-9_+\\-]+$");
    private static final Pattern KNOWN_FATAL_INSTALL_LOG_PATTERN = Pattern.compile(
            "(?i)(npm ERR!|EBADENGINE|unsupported engine|glob.*(?:too old|版本太旧)|requires Node\\.js)"
    );
    private static final long FATAL_LOG_GRACE_SECONDS = 5L;

    private final Gson gson = new Gson();
    private final NodeDetector nodeDetector = NodeDetector.getInstance();
    private final EnvironmentConfigurator envConfigurator = new EnvironmentConfigurator();

    public GitNexusHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_gitnexus_status":
                handleGetStatus(content);
                return true;
            case "install_gitnexus":
                handleInstall(content, false, false);
                return true;
            case "update_gitnexus":
                handleInstall(content, false, true);
                return true;
            case "reindex_gitnexus":
                handleInstall(content, true, false);
                return true;
            default:
                return false;
        }
    }

    /**
     * 查询当前 provider 对应的 GitNexus 状态，并回推到前端。
     */
    private void handleGetStatus(String content) {
        CompletableFuture.runAsync(() -> {
            GitNexusProviderConfig providerConfig = resolveProvider(content);
            JsonObject status = buildStatus(providerConfig);
            sendStatus(status);
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[GitNexusHandler] Failed to get GitNexus status: " + ex.getMessage(), ex);
            sendStatus(buildErrorStatus(resolveProvider(content), "Failed to check GitNexus status: " + ex.getMessage()));
            return null;
        });
    }

    /**
     * 一键安装或强制重建 GitNexus 索引。
     */
    private void handleInstall(String content, boolean forceReindex, boolean updateRequested) {
        GitNexusProviderConfig providerConfig = resolveProvider(content);
        if (providerConfig == null) {
            sendInstallResult(false, null, "GitNexus onboarding is only available for Claude Code and Codex.", null);
            return;
        }

        if (!installInProgress.compareAndSet(false, true)) {
            LOG.info("[GitNexusHandler] Ignoring duplicate GitNexus request because another task is running.");
            sendInstallBusyResult(providerConfig);
            return;
        }

        CompletableFuture.runAsync(() -> {
            StringBuilder logs = new StringBuilder();
            try {
                Path repoRoot = resolveRepoRoot();
                if (repoRoot == null) {
                    sendInstallResult(false, providerConfig, "GitNexus requires a Git repository. Open the repo root and retry.", null);
                    return;
                }

                NodeDetectionResult nodeResult = ensureNodeRuntime(providerConfig, logs);
                if (nodeResult == null || !nodeResult.isFound()) {
                    sendInstallResult(false, providerConfig, "Node.js 18+ is required before installing GitNexus.", logs.toString());
                    return;
                }
                if (!NodeDetector.isVersionSupported(nodeResult.getNodeVersion())) {
                    sendInstallResult(
                            false,
                            providerConfig,
                            "GitNexus requires Node.js 18 or newer. Current version: " + nodeResult.getNodeVersion(),
                            logs.toString()
                    );
                    return;
                }

                String nodePath = nodeResult.getNodePath();
                String npxExecutable = resolveNpxExecutable(nodePath);

                sendInstallProgress(providerConfig, "Running gitnexus setup...");
                int setupExitCode = runLoggedProcess(
                        createCommand(npxExecutable, "-y", "gitnexus@latest", "setup"),
                        repoRoot,
                        nodePath,
                        providerConfig,
                        logs,
                        10
                );
                if (setupExitCode != 0) {
                    sendInstallResult(false, providerConfig, "GitNexus setup failed.", logs.toString());
                    return;
                }

                sendInstallProgress(providerConfig, forceReindex
                        ? "Rebuilding the GitNexus index for the current repository..."
                        : "Indexing the current repository with GitNexus...");
                List<String> analyzeCommand = new ArrayList<>();
                analyzeCommand.add(npxExecutable);
                analyzeCommand.add("-y");
                analyzeCommand.add("gitnexus@latest");
                analyzeCommand.add("analyze");
                analyzeCommand.add("--skills");
                if (forceReindex) {
                    analyzeCommand.add("--force");
                }

                int analyzeExitCode = runLoggedProcess(
                        analyzeCommand,
                        repoRoot,
                        nodePath,
                        providerConfig,
                        logs,
                        20
                );
                if (analyzeExitCode != 0) {
                    sendInstallResult(false, providerConfig, "GitNexus analyze failed.", logs.toString());
                    return;
                }

                String installedVersion = ToolkitVersionUtil.getLatestNpmVersion(
                        nodePath,
                        envConfigurator,
                        GITNEXUS_PACKAGE_NAME,
                        LOG
                );
                persistInstalledVersion(repoRoot, installedVersion);

                sendInstallResult(
                        true,
                        providerConfig,
                        updateRequested
                                ? "GitNexus has been updated for " + providerConfig.providerLabel + "."
                                : forceReindex
                                ? "GitNexus has rebuilt the index for " + providerConfig.providerLabel + "."
                                : "GitNexus has been installed for " + providerConfig.providerLabel + ".",
                        logs.toString()
                );
                sendStatus(buildStatus(providerConfig));
            } catch (Exception e) {
                LOG.error("[GitNexusHandler] Failed to install GitNexus: " + e.getMessage(), e);
                String combinedLogs = logs.toString().trim();
                sendInstallResult(
                        false,
                        providerConfig,
                        "Failed to install GitNexus: " + e.getMessage(),
                        combinedLogs.isEmpty() ? null : combinedLogs
                );
            } finally {
                installInProgress.set(false);
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[GitNexusHandler] Unexpected install error: " + ex.getMessage(), ex);
            sendInstallResult(false, providerConfig, "Failed to install GitNexus: " + ex.getMessage(), null);
            installInProgress.set(false);
            return null;
        });
    }

    /**
     * 构建前端消费的 GitNexus 状态对象。
     */
    private JsonObject buildStatus(GitNexusProviderConfig providerConfig) {
        if (providerConfig == null) {
            return buildErrorStatus(null, "GitNexus onboarding is only available for Claude Code and Codex.");
        }

        JsonObject status = new JsonObject();
        status.addProperty("provider", providerConfig.providerId);
        status.addProperty("providerLabel", providerConfig.providerLabel);
        status.addProperty("installed", false);
        status.addProperty("runtimeBootstrapSupported", PlatformUtils.isWindows());

        Path repoRoot = resolveRepoRoot();
        Path workspacePath = getWorkspacePath();
        Path indexDir = repoRoot != null ? repoRoot.resolve(".gitnexus") : null;
        Path registryFile = Paths.get(PlatformUtils.getHomeDirectory(), ".gitnexus", "registry.json");
        GitNexusScopeContext scopeContext = resolveScopeContext(repoRoot);

        if (workspacePath != null) {
            status.addProperty("workspaceRoot", workspacePath.toString());
        }
        if (repoRoot != null) {
            status.addProperty("projectRoot", repoRoot.toString());
        }
        if (indexDir != null) {
            status.addProperty("indexDir", indexDir.toString());
        }
        if (scopeContext.currentFile != null) {
            status.addProperty("currentFile", scopeContext.currentFile.toString());
        }
        if (scopeContext.currentDirectory != null) {
            status.addProperty("currentDirectory", scopeContext.currentDirectory.toString());
        }
        if (scopeContext.currentModuleRoot != null) {
            status.addProperty("currentModuleRoot", scopeContext.currentModuleRoot.toString());
        }
        status.addProperty("installCommand", buildInstallCommandPreview(repoRoot));

        NodeDetectionResult nodeResult = detectNodeEnvironment();
        boolean nodeAvailable = nodeResult != null && nodeResult.isFound();
        boolean nodeSupported = nodeAvailable && NodeDetector.isVersionSupported(nodeResult.getNodeVersion());
        status.addProperty("nodeAvailable", nodeAvailable);
        status.addProperty("nodeSupported", nodeSupported);
        if (nodeAvailable) {
            status.addProperty("nodeVersion", nodeResult.getNodeVersion());
            status.addProperty("nodePath", nodeResult.getNodePath());
        }

        boolean repositoryDetected = repoRoot != null;
        boolean indexDirExists = indexDir != null && Files.isDirectory(indexDir);
        boolean registryExists = Files.isRegularFile(registryFile);
        String installedVersion = resolveInstalledVersion(indexDir);
        String latestVersion = nodeAvailable && (indexDirExists || registryExists)
                ? ToolkitVersionUtil.getLatestNpmVersion(
                        nodeResult.getNodePath(),
                        envConfigurator,
                        GITNEXUS_PACKAGE_NAME,
                        LOG
                )
                : null;
        status.addProperty("repositoryDetected", repositoryDetected);
        status.addProperty("indexDirExists", indexDirExists);
        status.addProperty("registryExists", registryExists);
        status.addProperty("setupHintCommand", "npx -y gitnexus@latest setup");
        status.addProperty("analyzeHintCommand", "npx -y gitnexus@latest analyze --skills");
        ToolkitVersionUtil.applyVersionInfo(status, installedVersion, latestVersion);

        if (!repositoryDetected) {
            status.addProperty("state", "error");
            status.addProperty("message", "GitNexus requires a Git repository. Open the repo root and retry.");
            return status;
        }

        if (!nodeAvailable) {
            status.addProperty("state", "missing");
            status.addProperty("message", "Node.js 18+ is required before installing GitNexus.");
            return status;
        }

        if (!nodeSupported) {
            status.addProperty("state", "missing");
            status.addProperty("message", "GitNexus requires Node.js 18 or newer. Current version: " + nodeResult.getNodeVersion());
            return status;
        }

        if (indexDirExists && registryExists) {
            status.addProperty("state", "ready");
            status.addProperty("installed", true);
            status.addProperty("message", "GitNexus is ready. The repository index is available for repo-wide analysis.");
            return status;
        }

        if (indexDirExists || registryExists) {
            status.addProperty("state", "partial");
            status.addProperty("message", "Found a partial GitNexus setup. Use install or rebuild to complete the current repository.");
            return status;
        }

        status.addProperty("state", "missing");
        status.addProperty("message", "GitNexus is not set up for this repository yet. Click Install to configure MCP and build the repo index.");
        return status;
    }

    /**
     * 构建异常状态，避免前端拿到空结构。
     */
    private JsonObject buildErrorStatus(GitNexusProviderConfig providerConfig, String message) {
        JsonObject status = new JsonObject();
        if (providerConfig != null) {
            status.addProperty("provider", providerConfig.providerId);
            status.addProperty("providerLabel", providerConfig.providerLabel);
        } else {
            status.addProperty("provider", "unknown");
            status.addProperty("providerLabel", "Unsupported");
        }
        status.addProperty("state", providerConfig == null ? "unsupported" : "error");
        status.addProperty("installed", false);
        status.addProperty("runtimeBootstrapSupported", PlatformUtils.isWindows());
        status.addProperty("repositoryDetected", false);
        status.addProperty("indexDirExists", false);
        status.addProperty("registryExists", false);
        status.addProperty("nodeAvailable", false);
        status.addProperty("nodeSupported", false);
        status.addProperty("hasUpdate", false);
        status.addProperty("message", message);
        return status;
    }

    /**
     * 优先解析当前 Git 仓库根目录。
     */
    private Path resolveRepoRoot() {
        Path workspacePath = getWorkspacePath();
        if (workspacePath == null) {
            return null;
        }

        String repoRoot = CodexSkillService.findRepoRoot(workspacePath.toString());
        if (repoRoot != null && !repoRoot.trim().isEmpty()) {
            return Paths.get(repoRoot).toAbsolutePath().normalize();
        }

        if (Files.isDirectory(workspacePath.resolve(".git"))) {
            return workspacePath;
        }
        return null;
    }

    /**
     * 解析当前活动文件对应的目录和模块根目录。
     */
    private GitNexusScopeContext resolveScopeContext(Path repoRoot) {
        String activeFilePath = EditorFileUtils.getCurrentEditorFilePath(context.getProject());
        if (activeFilePath == null || activeFilePath.trim().isEmpty()) {
            return GitNexusScopeContext.empty();
        }

        try {
            Path currentFile = Paths.get(activeFilePath).toAbsolutePath().normalize();
            Path currentDirectory = Files.isDirectory(currentFile) ? currentFile : currentFile.getParent();
            Path currentModuleRoot = resolveModuleRoot(currentFile, repoRoot);
            return new GitNexusScopeContext(currentFile, currentDirectory, currentModuleRoot);
        } catch (Exception e) {
            LOG.debug("[GitNexusHandler] Failed to resolve current scope context: " + e.getMessage());
            return GitNexusScopeContext.empty();
        }
    }

    /**
     * 优先使用 IDEA Module 根目录，失败时回退到仓库内模块规则。
     */
    private Path resolveModuleRoot(Path currentFile, Path repoRoot) {
        Path moduleRoot = resolveIdeaModuleRoot(currentFile);
        if (moduleRoot != null) {
            return moduleRoot;
        }
        return resolveHeuristicModuleRoot(currentFile, repoRoot);
    }

    /**
     * 通过 IDEA 的模块模型解析模块根目录。
     */
    private Path resolveIdeaModuleRoot(Path currentFile) {
        if (context.getProject() == null) {
            return null;
        }

        try {
            return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<Path>) () -> {
                String systemIndependentPath = currentFile.toString().replace('\\', '/');
                VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(systemIndependentPath);
                if (virtualFile == null) {
                    return null;
                }

                Module module = ModuleUtilCore.findModuleForFile(virtualFile, context.getProject());
                if (module == null) {
                    return null;
                }

                VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
                Path bestRoot = null;
                for (VirtualFile contentRoot : contentRoots) {
                    if (contentRoot == null) {
                        continue;
                    }
                    Path candidate = Paths.get(contentRoot.getPath()).toAbsolutePath().normalize();
                    if (!currentFile.startsWith(candidate)) {
                        continue;
                    }
                    if (bestRoot == null || candidate.getNameCount() > bestRoot.getNameCount()) {
                        bestRoot = candidate;
                    }
                }
                return bestRoot;
            });
        } catch (Exception e) {
            LOG.debug("[GitNexusHandler] Failed to resolve IDEA module root: " + e.getMessage());
            return null;
        }
    }

    /**
     * 当 IDEA 模块模型不可用时，按构建文件和常见目录规则推导模块根目录。
     */
    private Path resolveHeuristicModuleRoot(Path currentFile, Path repoRoot) {
        if (repoRoot == null || currentFile == null || !currentFile.startsWith(repoRoot)) {
            return null;
        }

        Path cursor = Files.isDirectory(currentFile) ? currentFile : currentFile.getParent();
        while (cursor != null && cursor.startsWith(repoRoot)) {
            if (Files.exists(cursor.resolve("pom.xml"))
                    || Files.exists(cursor.resolve("build.gradle"))
                    || Files.exists(cursor.resolve("build.gradle.kts"))
                    || Files.exists(cursor.resolve(".iml"))) {
                return cursor;
            }
            if (cursor.equals(repoRoot)) {
                break;
            }
            cursor = cursor.getParent();
        }

        try {
            Path relative = repoRoot.relativize(currentFile);
            if (relative.getNameCount() >= 2) {
                String first = relative.getName(0).toString().toLowerCase(Locale.ROOT);
                if ("modules".equals(first) || "module".equals(first)) {
                    return repoRoot.resolve(relative.getName(0)).resolve(relative.getName(1)).normalize();
                }
            }
            if (relative.getNameCount() >= 1) {
                return repoRoot.resolve(relative.getName(0)).normalize();
            }
        } catch (Exception e) {
            LOG.debug("[GitNexusHandler] Failed to resolve heuristic module root: " + e.getMessage());
        }
        return repoRoot;
    }

    /**
     * 获取当前窗口实际使用的工作目录。
     */
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

    /**
     * 解析当前请求要落到哪个 provider。
     */
    private GitNexusProviderConfig resolveProvider(String content) {
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
            return new GitNexusProviderConfig("codex", "Codex");
        }
        if ("claude".equals(normalized) || "claude-code".equals(normalized)) {
            return new GitNexusProviderConfig("claude", "Claude Code");
        }
        return null;
    }

    /**
     * 检测并缓存 Node.js 环境。
     */
    private NodeDetectionResult detectNodeEnvironment() {
        try {
            NodeDetectionResult cached = nodeDetector.getCachedDetectionResult();
            if (cached != null && cached.isFound()) {
                return cached;
            }

            String nodePath = nodeDetector.findNodeExecutable();
            if (nodePath != null && !nodePath.trim().isEmpty()) {
                NodeDetectionResult verified = nodeDetector.verifyAndCacheNodePath(nodePath);
                if (verified.isFound()) {
                    return verified;
                }
            }

            NodeDetectionResult detected = nodeDetector.detectNodeWithDetails();
            if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                return nodeDetector.verifyAndCacheNodePath(detected.getNodePath());
            }
            return detected;
        } catch (Exception e) {
            LOG.warn("[GitNexusHandler] Failed to detect Node.js: " + e.getMessage());
            return null;
        }
    }

    /**
     * 检测并在 Windows 下自动补齐 Node.js 运行时。
     */
    private NodeDetectionResult ensureNodeRuntime(GitNexusProviderConfig providerConfig, StringBuilder logs) throws Exception {
        NodeDetectionResult nodeResult = detectNodeEnvironment();
        if (nodeResult != null && nodeResult.isFound() && NodeDetector.isVersionSupported(nodeResult.getNodeVersion())) {
            return nodeResult;
        }

        if (!PlatformUtils.isWindows()) {
            return nodeResult;
        }

        if (!isCommandAvailable("winget")) {
            logs.append("Auto-install requires winget on Windows.\n");
            return nodeResult;
        }

        sendInstallProgress(providerConfig, "Node.js is missing. Starting automatic installation...");
        int exitCode = runLoggedProcess(
                createCommand(
                        "winget",
                        "install",
                        "--id",
                        "OpenJS.NodeJS.LTS",
                        "-e",
                        "--accept-package-agreements",
                        "--accept-source-agreements",
                        "--disable-interactivity",
                        "--silent"
                ),
                null,
                null,
                providerConfig,
                logs,
                20
        );
        if (exitCode != 0) {
            return nodeResult;
        }
        nodeDetector.clearCache();
        return detectNodeEnvironment();
    }

    /**
     * 检查命令是否可用。
     */
    private boolean isCommandAvailable(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcess(process);
            }
        }
    }

    /**
     * 从 Node.js 所在目录或 PATH 中解析 npx 可执行文件。
     */
    private String resolveNpxExecutable(String nodePath) {
        String executableName = PlatformUtils.isWindows() ? "npx.cmd" : "npx";

        if (nodePath != null && !nodePath.trim().isEmpty() && !"node".equalsIgnoreCase(nodePath.trim())) {
            File nodeFile = new File(nodePath);
            File nodeDir = nodeFile.getParentFile();
            if (nodeDir != null) {
                File npxFile = new File(nodeDir, executableName);
                if (npxFile.exists()) {
                    return npxFile.getAbsolutePath();
                }
            }
        }

        String pathEnv = PlatformUtils.getPathEnv();
        if (pathEnv != null && !pathEnv.isEmpty()) {
            String[] entries = pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator));
            for (String entry : entries) {
                if (entry == null || entry.trim().isEmpty()) {
                    continue;
                }
                File candidate = new File(entry, executableName);
                if (candidate.exists()) {
                    return candidate.getAbsolutePath();
                }
            }
        }

        return executableName;
    }

    /**
     * 生成前端展示用的安装命令预览。
     */
    private String buildInstallCommandPreview(Path repoRoot) {
        if (repoRoot == null) {
            return "";
        }
        return "cd \"" + repoRoot + "\" && npx -y gitnexus@latest setup && npx -y gitnexus@latest analyze --skills";
    }

    /**
     * Read plugin-managed GitNexus install metadata for the current repository.
     */
    private String resolveInstalledVersion(Path indexDir) {
        if (indexDir == null) {
            return null;
        }
        return ToolkitVersionUtil.readInstalledVersionMetadata(indexDir.resolve(GITNEXUS_METADATA_FILE));
    }

    /**
     * Persist the last successful GitNexus package version for this repository.
     */
    private void persistInstalledVersion(Path repoRoot, String installedVersion) {
        if (repoRoot == null || installedVersion == null || installedVersion.isEmpty()) {
            return;
        }

        ToolkitVersionUtil.writeInstalledVersionMetadata(
                repoRoot.resolve(".gitnexus").resolve(GITNEXUS_METADATA_FILE),
                installedVersion,
                GITNEXUS_PACKAGE_NAME,
                LOG
        );
    }

    /**
     * 运行命令并推送清洗后的日志。
     */
    private int runLoggedProcess(
            List<String> command,
            Path workingDirectory,
            String nodePath,
            GitNexusProviderConfig providerConfig,
            StringBuilder logs,
            int timeoutMinutes
    ) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            processBuilder.directory(workingDirectory.toFile());
        }
        processBuilder.redirectErrorStream(true);
        if (nodePath != null && !nodePath.trim().isEmpty()) {
            envConfigurator.updateProcessEnvironment(processBuilder, nodePath);
            envConfigurator.configureProjectPath(processBuilder.environment(), workingDirectory != null ? workingDirectory.toString() : null);
        }

        Process process = processBuilder.start();
        AtomicLong lastLogAt = new AtomicLong(System.nanoTime());
        AtomicBoolean fatalLogDetected = new AtomicBoolean(false);
        String[] lastProgressLine = {null};
        CompletableFuture<Void> logReaderFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String sanitizedLine = sanitizeLogLine(line);
                    if (sanitizedLine.isEmpty()) {
                        continue;
                    }

                    lastLogAt.set(System.nanoTime());
                    if (!sanitizedLine.equals(lastProgressLine[0])) {
                        logs.append(sanitizedLine).append('\n');
                        sendInstallProgress(providerConfig, sanitizedLine);
                        lastProgressLine[0] = sanitizedLine;
                    }
                    if (isKnownFatalInstallLogLine(sanitizedLine)) {
                        fatalLogDetected.set(true);
                    }
                }
            } catch (Exception e) {
                if (process.isAlive()) {
                    LOG.debug("[GitNexusHandler] Failed while reading GitNexus process output: " + e.getMessage());
                }
            }
        }, AppExecutorUtil.getAppExecutorService());

        long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes);
        while (true) {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                break;
            }

            long now = System.nanoTime();
            if (now >= deadlineNanos) {
                PlatformUtils.terminateProcess(process);
                waitForLogReader(logReaderFuture);
                throw new IllegalStateException("Command timed out: " + String.join(" ", command));
            }

            if (fatalLogDetected.get()
                    && now - lastLogAt.get() >= TimeUnit.SECONDS.toNanos(FATAL_LOG_GRACE_SECONDS)) {
                PlatformUtils.terminateProcess(process);
                waitForLogReader(logReaderFuture);
                throw new IllegalStateException("Command stopped after a fatal GitNexus install error. See logs for details.");
            }
        }

        waitForLogReader(logReaderFuture);
        return process.exitValue();
    }

    /**
     * 等待日志读取线程退出，避免主流程被标准输出阻塞。
     */
    private void waitForLogReader(CompletableFuture<Void> logReaderFuture) {
        try {
            logReaderFuture.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            logReaderFuture.cancel(true);
        }
    }

    /**
     * 构建命令列表。
     */
    private List<String> createCommand(String... parts) {
        List<String> command = new ArrayList<>();
        for (String part : parts) {
            command.add(part);
        }
        return command;
    }

    /**
     * 清洗终端日志中的 ANSI 控制字符和低价值终端绘制残片。
     */
    private String sanitizeLogLine(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }

        String sanitized = ANSI_ESCAPE_PATTERN.matcher(line).replaceAll("");
        sanitized = CONTROL_CHAR_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = sanitized.replace('\u00A0', ' ').trim();
        if (sanitized.isEmpty()) {
            return "";
        }

        String compact = sanitized
                .replace("│", "")
                .replace("|", "")
                .replace("•", "")
                .replace("·", "")
                .trim();
        if (compact.isEmpty() || LOW_SIGNAL_LOG_PATTERN.matcher(sanitized).matches()) {
            return "";
        }

        return sanitized;
    }

    /**
     * 识别典型的环境兼容性报错，避免 CLI 卡住时一直占用安装状态。
     */
    private boolean isKnownFatalInstallLogLine(String sanitizedLine) {
        return sanitizedLine != null && KNOWN_FATAL_INSTALL_LOG_PATTERN.matcher(sanitizedLine).find();
    }

    /**
     * 推送状态回前端。
     */
    private void sendStatus(JsonObject status) {
        String payload = gson.toJson(status);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateGitNexusStatus", escapeJs(payload))
        );
    }

    /**
     * 推送安装进度到前端。
     */
    private void sendInstallProgress(GitNexusProviderConfig providerConfig, String logLine) {
        JsonObject progress = new JsonObject();
        if (providerConfig != null) {
            progress.addProperty("provider", providerConfig.providerId);
        }
        progress.addProperty("log", logLine);

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.gitNexusInstallProgress", escapeJs(gson.toJson(progress)))
        );
    }

    /**
     * 推送安装结果到前端。
     */
    private void sendInstallResult(boolean success, GitNexusProviderConfig providerConfig, String message, String logs) {
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
                callJavaScript("window.gitNexusInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    /**
     * 推送“已有安装任务进行中”的占用结果。
     */
    private void sendInstallBusyResult(GitNexusProviderConfig providerConfig) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("busy", true);
        if (providerConfig != null) {
            result.addProperty("provider", providerConfig.providerId);
        }

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.gitNexusInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    /**
     * GitNexus provider 安装配置。
     */
    private static final class GitNexusProviderConfig {
        private final String providerId;
        private final String providerLabel;

        private GitNexusProviderConfig(String providerId, String providerLabel) {
            this.providerId = providerId;
            this.providerLabel = providerLabel;
        }
    }

    /**
     * 当前编辑文件的 GitNexus 范围上下文。
     */
    private static final class GitNexusScopeContext {
        private final Path currentFile;
        private final Path currentDirectory;
        private final Path currentModuleRoot;

        private GitNexusScopeContext(Path currentFile, Path currentDirectory, Path currentModuleRoot) {
            this.currentFile = currentFile;
            this.currentDirectory = currentDirectory;
            this.currentModuleRoot = currentModuleRoot;
        }

        private static GitNexusScopeContext empty() {
            return new GitNexusScopeContext(null, null, null);
        }
    }
}
