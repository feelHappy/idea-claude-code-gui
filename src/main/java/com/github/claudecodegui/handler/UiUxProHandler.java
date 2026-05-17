package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.skill.CodexSkillService;
import com.github.claudecodegui.util.PlatformUtils;
import com.github.claudecodegui.util.ToolkitVersionUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description UI UX Pro Max 安装与状态管理处理器，负责检测当前项目下的技能状态并执行一键安装。
 * @author zyl
 * @date 2026/03/23
 */
public class UiUxProHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(UiUxProHandler.class);
    private static final String UI_UX_PRO_PACKAGE_NAME = "uipro-cli";
    private static final String UI_UX_PRO_METADATA_FILE = ".cc-ai-toolkit.json";
    private static final String[] SUPPORTED_TYPES = {
            "get_uiux_pro_status",
            "install_uiux_pro",
            "update_uiux_pro"
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
    private static final Pattern PYTHON_VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final String[] WINDOWS_PYTHON_PATHS = {
            "%LOCALAPPDATA%\\Programs\\Python\\Python313\\python.exe",
            "%LOCALAPPDATA%\\Programs\\Python\\Python312\\python.exe",
            "%LOCALAPPDATA%\\Programs\\Python\\Python311\\python.exe",
            "%LOCALAPPDATA%\\Programs\\Python\\Python310\\python.exe",
            "%LOCALAPPDATA%\\Programs\\Python\\Launcher\\py.exe",
            "C:\\Program Files\\Python313\\python.exe",
            "C:\\Program Files\\Python312\\python.exe",
            "C:\\Program Files\\Python311\\python.exe",
            "C:\\Program Files\\Python310\\python.exe"
    };

    private final Gson gson = new Gson();
    private final NodeDetector nodeDetector = NodeDetector.getInstance();
    private final EnvironmentConfigurator envConfigurator = new EnvironmentConfigurator();

    public UiUxProHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_uiux_pro_status":
                handleGetStatus(content);
                return true;
            case "install_uiux_pro":
                handleInstall(content, false);
                return true;
            case "update_uiux_pro":
                handleInstall(content, true);
                return true;
            default:
                return false;
        }
    }

    /**
     * 查询当前 provider 对应的 UI UX Pro Max 状态，并回推到前端。
     */
    private void handleGetStatus(String content) {
        CompletableFuture.runAsync(() -> {
            UiUxProviderConfig providerConfig = resolveProvider(content);
            JsonObject status = buildStatus(providerConfig);
            sendStatus(status);
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[UiUxProHandler] Failed to get UI UX Pro Max status: " + ex.getMessage(), ex);
            sendStatus(buildErrorStatus(resolveProvider(content), "Failed to check UI UX Pro Max status: " + ex.getMessage()));
            return null;
        });
    }

    /**
     * 使用 UIPro CLI 执行一键安装。
     */
    private void handleInstall(String content, boolean updateRequested) {
        UiUxProviderConfig providerConfig = resolveProvider(content);
        if (providerConfig == null) {
            sendInstallResult(false, null, "UI UX Pro Max onboarding is only available for Claude Code and Codex.", null);
            return;
        }

        if (!installInProgress.compareAndSet(false, true)) {
            LOG.info("[UiUxProHandler] Ignoring duplicate UI UX Pro Max install request because another install is running.");
            sendInstallBusyResult(providerConfig);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                Path installRoot = resolveInstallRoot();
                if (installRoot == null) {
                    sendInstallResult(false, providerConfig, "Cannot determine the current project directory for UI UX Pro Max installation.", null);
                    return;
                }

                PrerequisiteCheckResult prerequisiteResult = ensurePrerequisites(providerConfig);
                String prerequisiteLogs = prerequisiteResult.logs;
                NodeDetectionResult nodeResult = prerequisiteResult.nodeResult;
                PythonDetectionResult pythonResult = prerequisiteResult.pythonResult;

                if (prerequisiteResult.errorMessage != null && !prerequisiteResult.errorMessage.isEmpty()) {
                    sendInstallResult(false, providerConfig, prerequisiteResult.errorMessage, prerequisiteLogs);
                    return;
                }
                if (!NodeDetector.isVersionSupported(nodeResult.getNodeVersion())) {
                    sendInstallResult(
                            false,
                            providerConfig,
                            "UI UX Pro Max requires Node.js 18 or newer. Current version: " + nodeResult.getNodeVersion(),
                            prerequisiteLogs
                    );
                    return;
                }

                if (!pythonResult.available) {
                    sendInstallResult(false, providerConfig, "Python 3.x is required before installing UI UX Pro Max.", prerequisiteLogs);
                    return;
                }
                if (!pythonResult.supported) {
                    sendInstallResult(
                            false,
                            providerConfig,
                            "UI UX Pro Max requires Python 3.x. Current version: " + pythonResult.version,
                            prerequisiteLogs
                    );
                    return;
                }

                String nodePath = nodeResult.getNodePath();
                String npxExecutable = resolveNpxExecutable(nodePath);

                ProcessBuilder processBuilder = new ProcessBuilder(
                        npxExecutable,
                        "-y",
                        "uipro-cli@latest",
                        "init",
                        "--ai",
                        providerConfig.aiCode
                );
                processBuilder.directory(installRoot.toFile());
                processBuilder.redirectErrorStream(true);
                envConfigurator.updateProcessEnvironment(processBuilder, nodePath);
                envConfigurator.configureProjectPath(processBuilder.environment(), installRoot.toString());
                processBuilder.environment().put("PYTHONUTF8", "1");

                Process process = processBuilder.start();
                StringBuilder logs = new StringBuilder();
                String lastProgressLine = null;

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String sanitizedLine = sanitizeInstallLogLine(line);
                        if (!sanitizedLine.isEmpty() && !sanitizedLine.equals(lastProgressLine)) {
                            logs.append(sanitizedLine).append('\n');
                            sendInstallProgress(providerConfig, sanitizedLine);
                            lastProgressLine = sanitizedLine;
                        }
                    }
                }

                boolean finished = process.waitFor(10, TimeUnit.MINUTES);
                if (!finished) {
                    PlatformUtils.terminateProcess(process);
                    sendInstallResult(false, providerConfig, "UI UX Pro Max installation timed out after 10 minutes.", logs.toString());
                    return;
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    sendInstallResult(
                            false,
                            providerConfig,
                            "UI UX Pro Max installation failed with exit code " + exitCode + ".",
                            logs.toString()
                    );
                    return;
                }

                String combinedLogs = combineLogs(prerequisiteLogs, logs.toString());
                UiUxProjectLayout projectLayout = resolveProjectLayout(providerConfig, installRoot);
                String installedVersion = resolveInstalledVersion(projectLayout);
                if (installedVersion == null || installedVersion.isEmpty()) {
                    installedVersion = ToolkitVersionUtil.getLatestNpmVersion(
                            nodePath,
                            envConfigurator,
                            UI_UX_PRO_PACKAGE_NAME,
                            LOG
                    );
                }
                persistInstalledVersion(projectLayout, installedVersion);

                sendInstallResult(
                        true,
                        providerConfig,
                        updateRequested
                                ? "UI UX Pro Max has been updated for " + providerConfig.providerLabel + "."
                                : "UI UX Pro Max has been installed for " + providerConfig.providerLabel + ".",
                        combinedLogs
                );
                sendStatus(buildStatus(providerConfig));
            } catch (Exception e) {
                LOG.error("[UiUxProHandler] Failed to install UI UX Pro Max: " + e.getMessage(), e);
                sendInstallResult(false, providerConfig, "Failed to install UI UX Pro Max: " + e.getMessage(), null);
            } finally {
                installInProgress.set(false);
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[UiUxProHandler] Unexpected install error: " + ex.getMessage(), ex);
            sendInstallResult(false, providerConfig, "Failed to install UI UX Pro Max: " + ex.getMessage(), null);
            installInProgress.set(false);
            return null;
        });
    }

    /**
     * 构建前端消费的 UI UX Pro Max 状态对象。
     */
    private JsonObject buildStatus(UiUxProviderConfig providerConfig) {
        if (providerConfig == null) {
            return buildErrorStatus(null, "UI UX Pro Max onboarding is only available for Claude Code and Codex.");
        }

        JsonObject status = new JsonObject();
        status.addProperty("provider", providerConfig.providerId);
        status.addProperty("providerLabel", providerConfig.providerLabel);
        status.addProperty("installed", false);

        Path workspacePath = resolveInstallRoot();
        UiUxProjectLayout projectLayout = resolveProjectLayout(providerConfig, workspacePath);
        if (projectLayout.projectRoot != null) {
            status.addProperty("projectRoot", projectLayout.projectRoot.toString());
        }
        if (projectLayout.skillDir != null) {
            status.addProperty("skillDir", projectLayout.skillDir.toString());
        }
        status.addProperty("installCommand", buildInstallCommandPreview(providerConfig, resolveInstallRoot()));
        if (providerConfig.explicitCommand != null && !providerConfig.explicitCommand.isEmpty()) {
            status.addProperty("explicitCommand", providerConfig.explicitCommand);
        }

        NodeDetectionResult nodeResult = detectNodeEnvironment();
        boolean nodeAvailable = nodeResult != null && nodeResult.isFound();
        boolean nodeSupported = nodeAvailable && NodeDetector.isVersionSupported(nodeResult.getNodeVersion());
        status.addProperty("nodeAvailable", nodeAvailable);
        status.addProperty("nodeSupported", nodeSupported);
        status.addProperty("runtimeBootstrapSupported", PlatformUtils.isWindows());
        if (nodeAvailable) {
            status.addProperty("nodeVersion", nodeResult.getNodeVersion());
            status.addProperty("nodePath", nodeResult.getNodePath());
        }

        PythonDetectionResult pythonResult = detectPythonEnvironment();
        status.addProperty("pythonAvailable", pythonResult.available);
        status.addProperty("pythonSupported", pythonResult.supported);
        if (pythonResult.version != null) {
            status.addProperty("pythonVersion", pythonResult.version);
        }
        if (pythonResult.command != null) {
            status.addProperty("pythonCommand", pythonResult.command);
        }

        boolean skillDirExists = projectLayout.skillDir != null && Files.isDirectory(projectLayout.skillDir);
        boolean skillManifestExists = skillDirExists && Files.isRegularFile(projectLayout.skillDir.resolve("SKILL.md"));
        boolean baseDirExists = projectLayout.baseSkillsDir != null && Files.isDirectory(projectLayout.baseSkillsDir);
        String installedVersion = resolveInstalledVersion(projectLayout);
        String latestVersion = nodeAvailable && (skillDirExists || baseDirExists)
                ? ToolkitVersionUtil.getLatestNpmVersion(
                        nodeResult.getNodePath(),
                        envConfigurator,
                        UI_UX_PRO_PACKAGE_NAME,
                        LOG
                )
                : null;
        status.addProperty("skillDirExists", skillDirExists);
        status.addProperty("skillManifestExists", skillManifestExists);
        ToolkitVersionUtil.applyVersionInfo(status, installedVersion, latestVersion);

        if (workspacePath == null) {
            status.addProperty("state", "error");
            status.addProperty("message", "Cannot determine the current project directory for UI UX Pro Max.");
            return status;
        }

        if (!nodeAvailable) {
            status.addProperty("state", "missing");
            status.addProperty("message", "Node.js 18+ is required before installing UI UX Pro Max.");
            return status;
        }

        if (!nodeSupported) {
            status.addProperty("state", "missing");
            status.addProperty("message", "UI UX Pro Max requires Node.js 18 or newer. Current version: " + nodeResult.getNodeVersion());
            return status;
        }

        if (!pythonResult.available) {
            status.addProperty("state", "missing");
            status.addProperty("message", "Python 3.x is required before installing UI UX Pro Max.");
            return status;
        }

        if (!pythonResult.supported) {
            status.addProperty("state", "missing");
            status.addProperty("message", "UI UX Pro Max requires Python 3.x. Current version: " + pythonResult.version);
            return status;
        }

        if (skillDirExists && skillManifestExists) {
            status.addProperty("state", "ready");
            status.addProperty("installed", true);
            status.addProperty("message", "UI UX Pro Max is ready in the current project.");
            return status;
        }

        if (skillDirExists || baseDirExists) {
            status.addProperty("state", "partial");
            status.addProperty("message", "Found an incomplete UI UX Pro Max setup. Use one-click install to repair the current project.");
            return status;
        }

        status.addProperty("state", "missing");
        status.addProperty("message", "UI UX Pro Max is not installed for this project yet. Use one-click install to prepare it.");
        return status;
    }

    /**
     * 构建异常状态，避免前端拿到空结构。
     */
    private JsonObject buildErrorStatus(UiUxProviderConfig providerConfig, String message) {
        JsonObject status = new JsonObject();
        if (providerConfig != null) {
            status.addProperty("provider", providerConfig.providerId);
            status.addProperty("providerLabel", providerConfig.providerLabel);
            if (providerConfig.explicitCommand != null && !providerConfig.explicitCommand.isEmpty()) {
                status.addProperty("explicitCommand", providerConfig.explicitCommand);
            }
        } else {
            status.addProperty("provider", "unknown");
            status.addProperty("providerLabel", "Unsupported");
        }
        status.addProperty("state", providerConfig == null ? "unsupported" : "error");
        status.addProperty("installed", false);
        status.addProperty("skillDirExists", false);
        status.addProperty("skillManifestExists", false);
        status.addProperty("nodeAvailable", false);
        status.addProperty("nodeSupported", false);
        status.addProperty("pythonAvailable", false);
        status.addProperty("pythonSupported", false);
        status.addProperty("hasUpdate", false);
        status.addProperty("message", message);
        return status;
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
     * 安装目录优先使用 IDEA 项目根目录，没有时退回会话工作目录。
     */
    private Path resolveInstallRoot() {
        if (context.getProject() != null && context.getProject().getBasePath() != null) {
            return Paths.get(context.getProject().getBasePath()).toAbsolutePath().normalize();
        }
        return getWorkspacePath();
    }

    /**
     * 按当前目录、项目根目录及其父级标记，推导 UI UX Pro Max 实际安装根目录。
     */
    private UiUxProjectLayout resolveProjectLayout(UiUxProviderConfig providerConfig, Path workspacePath) {
        if (providerConfig == null || workspacePath == null) {
            return new UiUxProjectLayout(null, null, null);
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

        Path baseSkillsDir = bestRoot.resolve(providerConfig.relativeBaseSkillsDir);
        Path skillDir = bestRoot.resolve(providerConfig.relativeSkillDir);
        return new UiUxProjectLayout(bestRoot, baseSkillsDir, skillDir);
    }

    /**
     * 根据技能根目录和 skill 目录为候选根目录打分。
     */
    private int scoreProjectRoot(UiUxProviderConfig providerConfig, Path candidateRoot) {
        if (providerConfig == null || candidateRoot == null) {
            return -1;
        }

        int score = 0;
        if (Files.isDirectory(candidateRoot.resolve(providerConfig.relativeBaseSkillsDir))) {
            score += 4;
        }
        if (Files.isDirectory(candidateRoot.resolve(providerConfig.relativeSkillDir))) {
            score += 8;
        }
        if (Files.isRegularFile(candidateRoot.resolve(providerConfig.relativeSkillDir).resolve("SKILL.md"))) {
            score += 4;
        }
        return score;
    }

    /**
     * 解析当前请求要落到哪个 provider。
     */
    private UiUxProviderConfig resolveProvider(String content) {
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
            return new UiUxProviderConfig(
                    "codex",
                    "Codex",
                    "codex",
                    Paths.get(".codex", "skills"),
                    Paths.get(".codex", "skills", "ui-ux-pro-max"),
                    "$ui-ux-pro-max"
            );
        }
        if ("claude".equals(normalized) || "claude-code".equals(normalized)) {
            return new UiUxProviderConfig(
                    "claude",
                    "Claude Code",
                    "claude",
                    Paths.get(".claude", "skills"),
                    Paths.get(".claude", "skills", "ui-ux-pro-max"),
                    ""
            );
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
            LOG.warn("[UiUxProHandler] Failed to detect Node.js: " + e.getMessage());
            return null;
        }
    }

    /**
     * 检测 Python 3 环境，支持 py、python、python3 三种入口。
     */
    private PythonDetectionResult detectPythonEnvironment() {
        PythonDetectionResult result = tryDetectPython(new String[]{"py", "-3", "--version"}, "py -3");
        if (result.available) {
            return result;
        }

        result = tryDetectPython(new String[]{"python", "--version"}, "python");
        if (result.available) {
            return result;
        }

        result = tryDetectPython(new String[]{"python3", "--version"}, "python3");
        if (result.available) {
            return result;
        }

        if (PlatformUtils.isWindows()) {
            for (String templatePath : WINDOWS_PYTHON_PATHS) {
                String pythonPath = expandWindowsEnvVars(templatePath);
                result = tryDetectPython(new String[]{pythonPath, "--version"}, pythonPath);
                if (result.available) {
                    return result;
                }
            }
        }

        return PythonDetectionResult.unavailable();
    }

    /**
     * 运行单次 Python 版本探测命令。
     */
    private PythonDetectionResult tryDetectPython(String[] command, String displayCommand) {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.environment().put("PYTHONUTF8", "1");
            process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcess(process);
                return PythonDetectionResult.unavailable();
            }

            String versionOutput = output.toString().trim();
            Matcher matcher = PYTHON_VERSION_PATTERN.matcher(versionOutput);
            if (process.exitValue() == 0 && matcher.find()) {
                int major = Integer.parseInt(matcher.group(1));
                return new PythonDetectionResult(true, major >= 3, matcher.group(), displayCommand);
            }
        } catch (Exception e) {
            LOG.debug("[UiUxProHandler] Python detection failed for command " + displayCommand + ": " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcess(process);
            }
        }
        return PythonDetectionResult.unavailable();
    }

    /**
     * 检测并在必要时自动补齐 UI UX Pro Max 的运行时环境。
     */
    private PrerequisiteCheckResult ensurePrerequisites(UiUxProviderConfig providerConfig) {
        StringBuilder logs = new StringBuilder();
        NodeDetectionResult nodeResult = detectNodeEnvironment();
        PythonDetectionResult pythonResult = detectPythonEnvironment();

        boolean nodeMissing = nodeResult == null || !nodeResult.isFound() || !NodeDetector.isVersionSupported(nodeResult.getNodeVersion());
        boolean pythonMissing = !pythonResult.available || !pythonResult.supported;

        if (!nodeMissing && !pythonMissing) {
            return new PrerequisiteCheckResult(nodeResult, pythonResult, null, logs.toString());
        }

        if (!PlatformUtils.isWindows()) {
            if (nodeMissing) {
                return new PrerequisiteCheckResult(nodeResult, pythonResult, "Node.js 18+ is required before installing UI UX Pro Max.", logs.toString());
            }
            return new PrerequisiteCheckResult(nodeResult, pythonResult, "Python 3.x is required before installing UI UX Pro Max.", logs.toString());
        }

        if (!isCommandAvailable("winget")) {
            return new PrerequisiteCheckResult(
                    nodeResult,
                    pythonResult,
                    "Auto-install requires winget on Windows. Please install the missing runtime manually, then retry.",
                    logs.toString()
            );
        }

        if (nodeMissing) {
            String message = installWindowsRuntimeWithWinget(
                    providerConfig,
                    "OpenJS.NodeJS.LTS",
                    "Node.js LTS",
                    logs
            );
            if (message != null) {
                return new PrerequisiteCheckResult(nodeResult, pythonResult, message, logs.toString());
            }
            nodeDetector.clearCache();
            nodeResult = detectNodeEnvironment();
        }

        if (pythonMissing) {
            String message = installWindowsRuntimeWithWinget(
                    providerConfig,
                    "Python.Python.3.12",
                    "Python 3.12",
                    logs
            );
            if (message != null) {
                return new PrerequisiteCheckResult(nodeResult, pythonResult, message, logs.toString());
            }
            pythonResult = detectPythonEnvironment();
        }

        if (nodeResult == null || !nodeResult.isFound()) {
            return new PrerequisiteCheckResult(nodeResult, pythonResult, "Failed to verify Node.js after auto-install.", logs.toString());
        }
        if (!pythonResult.available) {
            return new PrerequisiteCheckResult(nodeResult, pythonResult, "Failed to verify Python after auto-install.", logs.toString());
        }

        return new PrerequisiteCheckResult(nodeResult, pythonResult, null, logs.toString());
    }

    /**
     * 使用 winget 安装 Windows 运行时。
     */
    private String installWindowsRuntimeWithWinget(
            UiUxProviderConfig providerConfig,
            String packageId,
            String packageLabel,
            StringBuilder logs
    ) {
        sendInstallProgress(providerConfig, "Missing " + packageLabel + ". Starting automatic installation...");
        List<String> command = new ArrayList<>();
        command.add("winget");
        command.add("install");
        command.add("--id");
        command.add(packageId);
        command.add("-e");
        command.add("--accept-package-agreements");
        command.add("--accept-source-agreements");
        command.add("--disable-interactivity");
        command.add("--silent");

        try {
            int exitCode = runLoggedProcess(command, null, null, providerConfig, logs, 20);
            if (exitCode != 0) {
                return "Auto-install failed while installing " + packageLabel + " via winget.";
            }
        } catch (Exception e) {
            LOG.warn("[UiUxProHandler] Failed to auto-install " + packageLabel + ": " + e.getMessage(), e);
            return "Auto-install failed while installing " + packageLabel + " via winget.";
        }
        sendInstallProgress(providerConfig, packageLabel + " installation completed. Re-checking environment...");
        return null;
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
     * 运行命令并推送清洗后的日志。
     */
    private int runLoggedProcess(
            List<String> command,
            Path workingDirectory,
            String nodePath,
            UiUxProviderConfig providerConfig,
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
        }
        processBuilder.environment().put("PYTHONUTF8", "1");

        Process process = processBuilder.start();
        String lastProgressLine = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String sanitizedLine = sanitizeInstallLogLine(line);
                if (!sanitizedLine.isEmpty() && !sanitizedLine.equals(lastProgressLine)) {
                    logs.append(sanitizedLine).append('\n');
                    sendInstallProgress(providerConfig, sanitizedLine);
                    lastProgressLine = sanitizedLine;
                }
            }
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            PlatformUtils.terminateProcess(process);
            throw new IllegalStateException("Command timed out: " + String.join(" ", command));
        }

        return process.exitValue();
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
     * 展开 Windows 环境变量占位符。
     */
    private String expandWindowsEnvVars(String path) {
        if (path == null) {
            return null;
        }

        String result = path;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            result = result.replace("%LOCALAPPDATA%", localAppData);
        }
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null) {
            result = result.replace("%USERPROFILE%", userProfile);
        }
        return result;
    }

    /**
     * 生成前端展示用的安装命令预览。
     */
    private String buildInstallCommandPreview(UiUxProviderConfig providerConfig, Path installRoot) {
        if (providerConfig == null || installRoot == null) {
            return "";
        }
        return "cd \"" + installRoot + "\" && npx -y uipro-cli@latest init --ai " + providerConfig.aiCode;
    }

    /**
     * Read plugin-managed UI UX Pro Max install metadata from the generated skill directory.
     */
    private String resolveInstalledVersion(UiUxProjectLayout projectLayout) {
        Path metadataPath = resolveMetadataPath(projectLayout);
        return ToolkitVersionUtil.readInstalledVersionMetadata(metadataPath);
    }

    /**
     * Persist the last successful UI UX Pro Max package version for this project.
     */
    private void persistInstalledVersion(UiUxProjectLayout projectLayout, String installedVersion) {
        Path metadataPath = resolveMetadataPath(projectLayout);
        if (metadataPath == null || installedVersion == null || installedVersion.isEmpty()) {
            return;
        }

        ToolkitVersionUtil.writeInstalledVersionMetadata(
                metadataPath,
                installedVersion,
                UI_UX_PRO_PACKAGE_NAME,
                LOG
        );
    }

    private Path resolveMetadataPath(UiUxProjectLayout projectLayout) {
        if (projectLayout == null) {
            return null;
        }
        if (projectLayout.skillDir != null) {
            return projectLayout.skillDir.resolve(UI_UX_PRO_METADATA_FILE);
        }
        return null;
    }

    /**
     * 清洗安装日志中的 ANSI 控制字符和低价值终端绘制残片，避免前端显示乱码。
     */
    private String sanitizeInstallLogLine(String line) {
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
     * 合并前置环境安装日志与 UIPro 本体安装日志。
     */
    private String combineLogs(String first, String second) {
        if ((first == null || first.trim().isEmpty()) && (second == null || second.trim().isEmpty())) {
            return "";
        }
        if (first == null || first.trim().isEmpty()) {
            return second;
        }
        if (second == null || second.trim().isEmpty()) {
            return first;
        }
        return first.trim() + '\n' + second.trim();
    }

    /**
     * 推送状态回前端。
     */
    private void sendStatus(JsonObject status) {
        String payload = gson.toJson(status);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateUiUxProStatus", escapeJs(payload))
        );
    }

    /**
     * 推送安装进度到前端。
     */
    private void sendInstallProgress(UiUxProviderConfig providerConfig, String logLine) {
        JsonObject progress = new JsonObject();
        if (providerConfig != null) {
            progress.addProperty("provider", providerConfig.providerId);
        }
        progress.addProperty("log", logLine);

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.uiUxProInstallProgress", escapeJs(gson.toJson(progress)))
        );
    }

    /**
     * 推送安装结果到前端。
     */
    private void sendInstallResult(boolean success, UiUxProviderConfig providerConfig, String message, String logs) {
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
                callJavaScript("window.uiUxProInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    /**
     * 推送“已有安装任务进行中”的占用结果。
     */
    private void sendInstallBusyResult(UiUxProviderConfig providerConfig) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("busy", true);
        if (providerConfig != null) {
            result.addProperty("provider", providerConfig.providerId);
        }

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.uiUxProInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    /**
     * UI UX Pro Max provider 安装配置。
     */
    private static final class UiUxProviderConfig {
        private final String providerId;
        private final String providerLabel;
        private final String aiCode;
        private final Path relativeBaseSkillsDir;
        private final Path relativeSkillDir;
        private final String explicitCommand;

        private UiUxProviderConfig(
                String providerId,
                String providerLabel,
                String aiCode,
                Path relativeBaseSkillsDir,
                Path relativeSkillDir,
                String explicitCommand
        ) {
            this.providerId = providerId;
            this.providerLabel = providerLabel;
            this.aiCode = aiCode;
            this.relativeBaseSkillsDir = relativeBaseSkillsDir;
            this.relativeSkillDir = relativeSkillDir;
            this.explicitCommand = explicitCommand;
        }
    }

    /**
     * UI UX Pro Max 项目目录解析结果。
     */
    private static final class UiUxProjectLayout {
        private final Path projectRoot;
        private final Path baseSkillsDir;
        private final Path skillDir;

        private UiUxProjectLayout(Path projectRoot, Path baseSkillsDir, Path skillDir) {
            this.projectRoot = projectRoot;
            this.baseSkillsDir = baseSkillsDir;
            this.skillDir = skillDir;
        }
    }

    /**
     * Python 环境检测结果。
     */
    private static final class PythonDetectionResult {
        private final boolean available;
        private final boolean supported;
        private final String version;
        private final String command;

        private PythonDetectionResult(boolean available, boolean supported, String version, String command) {
            this.available = available;
            this.supported = supported;
            this.version = version;
            this.command = command;
        }

        private static PythonDetectionResult unavailable() {
            return new PythonDetectionResult(false, false, null, null);
        }
    }

    /**
     * 运行时检测与自动安装结果。
     */
    private static final class PrerequisiteCheckResult {
        private final NodeDetectionResult nodeResult;
        private final PythonDetectionResult pythonResult;
        private final String errorMessage;
        private final String logs;

        private PrerequisiteCheckResult(
                NodeDetectionResult nodeResult,
                PythonDetectionResult pythonResult,
                String errorMessage,
                String logs
        ) {
            this.nodeResult = nodeResult;
            this.pythonResult = pythonResult;
            this.errorMessage = errorMessage;
            this.logs = logs;
        }
    }
}
