package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.skill.CodexSkillService;
import com.github.claudecodegui.skill.SkillService;
import com.github.claudecodegui.util.PlatformUtils;
import com.github.claudecodegui.util.ToolkitVersionUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * @description BMad 安装与状态管理处理器，负责检测当前项目下的 BMad 状态并执行一键安装。
 * @author zyl
 * @date 2026/03/22
 */
public class BmadHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(BmadHandler.class);
    private static final String BMAD_PACKAGE_NAME = "bmad-method";
    private static final String BMAD_METADATA_FILE = ".cc-ai-toolkit.json";
    private static final String[] SUPPORTED_TYPES = {
            "get_bmad_status",
            "install_bmad",
            "update_bmad"
    };
    private static final Set<String> CORE_SKILL_NAMES = new HashSet<>(Arrays.asList(
            "bmad-help",
            "bmad-create-prd",
            "bmad-create-architecture",
            "bmad-create-epics-and-stories",
            "bmad-sprint-planning",
            "bmad-create-story",
            "bmad-dev-story",
            "bmad-code-review",
            "bmad-quick-dev",
            "bmad-document-project",
            "bmad-generate-project-context",
            "bmad-correct-course"
    ));
    private static final int READY_SKILL_THRESHOLD = 8;
    private static final int READY_CORE_SKILL_THRESHOLD = 5;
    private static final int BMAD_MIN_NODE_MAJOR_VERSION = 20;
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile(
            "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|[@-Z\\\\-_])"
    );
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern LOW_SIGNAL_LOG_PATTERN = Pattern.compile("^[\\s|│┌┐└┘├┤┬┴┼═─━•·oO0-9_+\\-]+$");

    /**
     * 安装互斥锁——实例级别而非 static，防止切换项目后锁泄漏。
     * 每个 BmadHandler 对应一个 IDE 项目窗口，因此实例级锁既保证
     * 同一项目不会并发安装，又不会因为项目 A 安装中切到项目 B 而永远卡锁。
     */
    private final AtomicBoolean installInProgress = new AtomicBoolean(false);

    private final Gson gson = new Gson();
    private final NodeDetector nodeDetector = NodeDetector.getInstance();
    private final EnvironmentConfigurator envConfigurator = new EnvironmentConfigurator();

    public BmadHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_bmad_status":
                handleGetStatus(content);
                return true;
            case "install_bmad":
                handleInstall(content, false);
                return true;
            case "update_bmad":
                handleInstall(content, true);
                return true;
            default:
                return false;
        }
    }

    /**
     * 查询当前 provider 对应的 BMad 状态，并回推到前端。
     */
    private void handleGetStatus(String content) {
        CompletableFuture.runAsync(() -> {
            BmadProviderConfig providerConfig = resolveProvider(content);
            JsonObject status = buildStatus(providerConfig);
            sendBmadStatus(status);
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[BmadHandler] Failed to get BMad status: " + ex.getMessage(), ex);
            sendBmadStatus(buildErrorStatus(resolveProvider(content), "Failed to check BMad status: " + ex.getMessage()));
            return null;
        });
    }

    /**
     * 使用 BMad 官方 installer 执行一键安装。
     */
    private void handleInstall(String content, boolean updateRequested) {
        BmadProviderConfig providerConfig = resolveProvider(content);
        if (providerConfig == null) {
            sendInstallResult(false, null, "BMad onboarding is only available for Claude Code and Codex.", null);
            return;
        }

        if (!installInProgress.compareAndSet(false, true)) {
            LOG.info("[BmadHandler] Ignoring duplicate BMad install request because another install is running.");
            sendInstallBusyResult(providerConfig);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String workspaceRoot = getWorkspaceRoot();
                if (workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
                    sendInstallResult(false, providerConfig, "Cannot determine the current working directory for BMad installation.", null);
                    return;
                }

                NodeDetectionResult nodeResult = detectNodeEnvironment();
                if (nodeResult == null || !nodeResult.isFound()) {
                    sendInstallResult(false, providerConfig, "Node.js 20+ is required before installing BMad.", null);
                    return;
                }

                String nodeVersion = nodeResult.getNodeVersion();
                if (!isBmadNodeVersionSupported(nodeVersion)) {
                    sendInstallResult(
                            false,
                            providerConfig,
                            "BMad requires Node.js 20 or newer. Current version: " + nodeVersion,
                            null
                    );
                    return;
                }

                String nodePath = nodeResult.getNodePath();
                String npxExecutable = resolveNpxExecutable(nodePath);
                String language = resolveInstallerLanguage(content);
                String userName = resolveInstallerUserName();

                ProcessBuilder processBuilder = new ProcessBuilder(
                        npxExecutable,
                        "bmad-method",
                        "install",
                        "--directory",
                        workspaceRoot,
                        // Force the modify/update flow so an existing _bmad install can repair
                        // missing Codex/Claude integration instead of falling back to quick-update.
                        "--action",
                        "update",
                        "--tools",
                        providerConfig.toolCode,
                        "--user-name",
                        userName,
                        "--communication-language",
                        language,
                        "--document-output-language",
                        language,
                        "--output-folder",
                        "_bmad-output",
                        "--yes"
                );
                processBuilder.directory(new File(workspaceRoot));
                processBuilder.redirectErrorStream(true);
                envConfigurator.updateProcessEnvironment(processBuilder, nodePath);
                envConfigurator.configureProjectPath(processBuilder.environment(), workspaceRoot);

                Process process = processBuilder.start();
                process.getOutputStream().close();
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
                    sendInstallResult(false, providerConfig, "BMad installation timed out after 10 minutes.", logs.toString());
                    return;
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    sendInstallResult(
                            false,
                            providerConfig,
                            "BMad installation failed with exit code " + exitCode + ".",
                            logs.toString()
                    );
                    return;
                }

                BmadProjectLayout projectLayout = resolveProjectLayout(
                        providerConfig,
                        Paths.get(workspaceRoot).toAbsolutePath().normalize()
                );
                String installedVersion = resolveInstalledVersion(projectLayout);
                if (installedVersion == null || installedVersion.isEmpty()) {
                    installedVersion = ToolkitVersionUtil.getLatestNpmVersion(
                            nodePath,
                            envConfigurator,
                            BMAD_PACKAGE_NAME,
                            LOG
                    );
                }
                persistInstalledVersion(
                        projectLayout,
                        installedVersion
                );

                sendInstallResult(
                        true,
                        providerConfig,
                        updateRequested
                                ? "BMad has been updated for " + providerConfig.providerLabel + "."
                                : "BMad has been installed for " + providerConfig.providerLabel + ".",
                        logs.toString()
                );
                sendBmadStatus(buildStatus(providerConfig));
            } catch (Exception e) {
                LOG.error("[BmadHandler] Failed to install BMad: " + e.getMessage(), e);
                sendInstallResult(false, providerConfig, "Failed to install BMad: " + e.getMessage(), null);
            } finally {
                installInProgress.set(false);
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[BmadHandler] Unexpected install error: " + ex.getMessage(), ex);
            sendInstallResult(false, providerConfig, "Failed to install BMad: " + ex.getMessage(), null);
            installInProgress.set(false);
            return null;
        });
    }

    /**
     * 构建前端消费的 BMad 状态对象。
     */
    private JsonObject buildStatus(BmadProviderConfig providerConfig) {
        if (providerConfig == null) {
            return buildErrorStatus(null, "BMad onboarding is only available for Claude Code and Codex.");
        }

        JsonObject status = new JsonObject();
        status.addProperty("provider", providerConfig.providerId);
        status.addProperty("providerLabel", providerConfig.providerLabel);
        status.addProperty("toolCode", providerConfig.toolCode);
        status.addProperty("commandPrefix", providerConfig.commandPrefix);

        String workspaceRoot = getWorkspaceRoot();
        Path workspacePath = workspaceRoot != null && !workspaceRoot.trim().isEmpty()
                ? Paths.get(workspaceRoot).toAbsolutePath().normalize()
                : null;
        BmadProjectLayout projectLayout = resolveProjectLayout(providerConfig, workspacePath);
        Path projectRoot = projectLayout.projectRoot;
        Path projectBmadDir = projectLayout.projectBmadDir;
        Path outputDir = projectLayout.outputDir;
        Path targetSkillsDir = projectLayout.targetSkillsDir;

        if (projectBmadDir != null) {
            status.addProperty("projectBmadDir", projectBmadDir.toString());
        }
        if (outputDir != null) {
            status.addProperty("outputDir", outputDir.toString());
        }
        if (targetSkillsDir != null) {
            status.addProperty("targetSkillsDir", targetSkillsDir.toString());
        }
        if (projectRoot != null) {
            status.addProperty("projectRoot", projectRoot.toString());
        }

        NodeDetectionResult nodeResult = detectNodeEnvironment();
        boolean nodeAvailable = nodeResult != null && nodeResult.isFound();
        boolean nodeSupported = nodeAvailable && isBmadNodeVersionSupported(nodeResult.getNodeVersion());

        status.addProperty("nodeAvailable", nodeAvailable);
        status.addProperty("nodeSupported", nodeSupported);
        if (nodeAvailable) {
            status.addProperty("nodeVersion", nodeResult.getNodeVersion());
            status.addProperty("nodePath", nodeResult.getNodePath());
        }

        boolean hasProjectCore = projectBmadDir != null && Files.isDirectory(projectBmadDir);
        boolean hasOutputDir = outputDir != null && Files.isDirectory(outputDir);
        String scanRoot = projectRoot != null ? projectRoot.toString() : workspaceRoot;
        Set<String> bmadSkills = collectBmadSkillNames(providerConfig, scanRoot);
        int skillCount = bmadSkills.size();
        int coreSkillCount = countCoreSkills(bmadSkills);
        String installedVersion = resolveInstalledVersion(projectLayout);
        String latestVersion = nodeAvailable && (hasProjectCore || hasOutputDir || skillCount > 0)
                ? ToolkitVersionUtil.getLatestNpmVersion(
                        nodeResult.getNodePath(),
                        envConfigurator,
                        BMAD_PACKAGE_NAME,
                        LOG
                )
                : null;

        status.addProperty("hasProjectCore", hasProjectCore);
        status.addProperty("hasOutputDir", hasOutputDir);
        status.addProperty("skillCount", skillCount);
        status.addProperty("coreSkillCount", coreSkillCount);
        status.add("availableCommands", toJsonArray(new TreeSet<>(bmadSkills)));
        status.addProperty("installed", false);
        status.addProperty("installCommand", buildInstallCommandPreview(providerConfig, workspaceRoot));
        ToolkitVersionUtil.applyVersionInfo(status, installedVersion, latestVersion);

        if (workspacePath == null) {
            status.addProperty("state", "error");
            status.addProperty("message", "Cannot determine the current working directory for BMad.");
            return status;
        }

        if (!nodeAvailable) {
            status.addProperty("state", "missing");
            status.addProperty("message", "Node.js 20+ is required before installing BMad.");
            return status;
        }

        if (!nodeSupported) {
            status.addProperty("state", "missing");
            status.addProperty("message", "BMad requires Node.js 20 or newer. Current version: " + nodeResult.getNodeVersion());
            return status;
        }

        if (hasProjectCore && skillCount >= READY_SKILL_THRESHOLD && coreSkillCount >= READY_CORE_SKILL_THRESHOLD) {
            status.addProperty("state", "ready");
            status.addProperty("installed", true);
            status.addProperty(
                    "message",
                    "BMad is ready. Detected " + skillCount + " BMad skills and the project _bmad folder."
            );
            return status;
        }

        if (hasProjectCore || hasOutputDir || skillCount > 0 || (targetSkillsDir != null && Files.isDirectory(targetSkillsDir))) {
            status.addProperty("state", "partial");
            status.addProperty(
                    "message",
                    "Found an incomplete BMad setup. Use one-click install to repair the current project."
            );
            return status;
        }

        status.addProperty("state", "missing");
        status.addProperty(
                "message",
                "BMad is not installed for this project yet. Use one-click install to set up skills and _bmad."
        );
        return status;
    }

    /**
     * 按当前目录、项目根目录及其父级标记，推导 BMad 实际安装根目录。
     */
    private BmadProjectLayout resolveProjectLayout(BmadProviderConfig providerConfig, Path workspacePath) {
        if (workspacePath == null) {
            return new BmadProjectLayout(null, null, null, null);
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

        Path projectBmadDir = bestRoot.resolve("_bmad");
        Path outputDir = bestRoot.resolve("_bmad-output");
        Path targetSkillsDir = bestRoot.resolve(providerConfig.relativeSkillsDir);
        return new BmadProjectLayout(bestRoot, projectBmadDir, outputDir, targetSkillsDir);
    }

    /**
     * 根据 BMad 核心目录、输出目录和 provider 技能目录为候选根目录打分。
     */
    private int scoreProjectRoot(BmadProviderConfig providerConfig, Path candidateRoot) {
        if (providerConfig == null || candidateRoot == null) {
            return -1;
        }

        int score = 0;
        if (Files.isDirectory(candidateRoot.resolve("_bmad"))) {
            score += 8;
        }
        if (Files.isDirectory(candidateRoot.resolve(providerConfig.relativeSkillsDir))) {
            score += 4;
        }
        if (Files.isDirectory(candidateRoot.resolve("_bmad-output"))) {
            score += 2;
        }
        return score;
    }

    /**
     * 构建异常状态，避免前端拿到空结构。
     */
    private JsonObject buildErrorStatus(BmadProviderConfig providerConfig, String message) {
        JsonObject status = new JsonObject();
        if (providerConfig != null) {
            status.addProperty("provider", providerConfig.providerId);
            status.addProperty("providerLabel", providerConfig.providerLabel);
            status.addProperty("toolCode", providerConfig.toolCode);
            status.addProperty("commandPrefix", providerConfig.commandPrefix);
        } else {
            status.addProperty("provider", "unknown");
            status.addProperty("providerLabel", "Unsupported");
            status.addProperty("toolCode", "");
            status.addProperty("commandPrefix", "");
        }
        status.addProperty("state", providerConfig == null ? "unsupported" : "error");
        status.addProperty("installed", false);
        status.addProperty("hasProjectCore", false);
        status.addProperty("hasOutputDir", false);
        status.addProperty("skillCount", 0);
        status.addProperty("coreSkillCount", 0);
        status.addProperty("hasUpdate", false);
        status.add("availableCommands", new JsonArray());
        status.addProperty("message", message);
        return status;
    }

    /**
     * 将 BMad 命令集合转换为前端可消费的有序 JsonArray。
     */
    private JsonArray toJsonArray(Set<String> commands) {
        JsonArray jsonArray = new JsonArray();
        if (commands == null) {
            return jsonArray;
        }
        for (String command : commands) {
            jsonArray.add(command);
        }
        return jsonArray;
    }

    /**
     * 统计当前 provider 下的 BMad skill 名称集合。
     */
    private Set<String> collectBmadSkillNames(BmadProviderConfig providerConfig, String workspaceRoot) {
        Set<String> skillNames = new HashSet<>();
        if (providerConfig == null) {
            return skillNames;
        }

        try {
            JsonObject skills = "codex".equals(providerConfig.providerId)
                    ? CodexSkillService.getAllSkills(workspaceRoot)
                    : SkillService.getAllSkills(workspaceRoot);
            collectBmadSkillNames(skills, skillNames);
        } catch (Exception e) {
            LOG.warn("[BmadHandler] Failed to scan skills: " + e.getMessage());
        }

        return skillNames;
    }

    /**
     * 递归提取 JsonObject 中的 BMad skill 名称。
     */
    private void collectBmadSkillNames(JsonElement element, Set<String> skillNames) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonObject()) {
            JsonObject jsonObject = element.getAsJsonObject();
            maybeAddSkillName(jsonObject, skillNames);
            for (String key : jsonObject.keySet()) {
                collectBmadSkillNames(jsonObject.get(key), skillNames);
            }
            return;
        }

        if (element.isJsonArray()) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (JsonElement child : jsonArray) {
                collectBmadSkillNames(child, skillNames);
            }
        }
    }

    /**
     * 从单个 skill 节点中抽取名称。
     */
    private void maybeAddSkillName(JsonObject jsonObject, Set<String> skillNames) {
        if (jsonObject.has("name")) {
            String name = jsonObject.get("name").getAsString();
            if (name != null && name.startsWith("bmad-")) {
                skillNames.add(name);
            }
        }

        if (jsonObject.has("path")) {
            String path = jsonObject.get("path").getAsString();
            String normalizedPath = path == null ? "" : path.replace('\\', '/');
            int markerIndex = normalizedPath.lastIndexOf("/bmad-");
            if (markerIndex >= 0) {
                String skillName = normalizedPath.substring(markerIndex + 1);
                int nextSlash = skillName.indexOf('/');
                if (nextSlash > 0) {
                    skillName = skillName.substring(0, nextSlash);
                }
                if (skillName.startsWith("bmad-")) {
                    skillNames.add(skillName);
                }
            }
        }
    }

    /**
     * 统计命中的核心 BMad skill 数量。
     */
    private int countCoreSkills(Set<String> skillNames) {
        int count = 0;
        for (String skillName : skillNames) {
            if (CORE_SKILL_NAMES.contains(skillName)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 解析当前请求要落到哪个 provider。
     */
    private BmadProviderConfig resolveProvider(String content) {
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
            return new BmadProviderConfig("codex", "Codex", "codex", "$", Paths.get(".agents", "skills"));
        }
        if ("claude".equals(normalized) || "claude-code".equals(normalized)) {
            return new BmadProviderConfig("claude", "Claude Code", "claude-code", "/", Paths.get(".claude", "skills"));
        }
        return null;
    }

    /**
     * 获取当前窗口实际使用的工作目录。
     */
    private String getWorkspaceRoot() {
        if (context.getSession() != null) {
            String cwd = context.getSession().getCwd();
            if (cwd != null && !cwd.trim().isEmpty()) {
                return cwd;
            }
        }
        if (context.getProject() != null) {
            return context.getProject().getBasePath();
        }
        return null;
    }

    /**
     * 检测并缓存 Node.js 环境。
     */
    private boolean isBmadNodeVersionSupported(String version) {
        return NodeDetector.parseMajorVersion(version) >= BMAD_MIN_NODE_MAJOR_VERSION;
    }

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
            LOG.warn("[BmadHandler] Failed to detect Node.js: " + e.getMessage());
            return null;
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
     * 根据当前系统语言推导 BMad installer 的语言参数。
     */
    private String resolveInstallerLanguage(String content) {
        String requestLanguage = resolveRequestLanguage(content);
        if (requestLanguage != null && !requestLanguage.trim().isEmpty()) {
            return isChineseLanguage(requestLanguage) ? "Chinese" : "English";
        }

        Locale locale = Locale.getDefault();
        if (locale != null && locale.getLanguage() != null && isChineseLanguage(locale.getLanguage())) {
            return "Chinese";
        }
        return "English";
    }

    /**
     * Resolve a stable installer user name so BMAD never falls back to an interactive prompt.
     */
    private String resolveInstallerUserName() {
        String[] candidates = {
                System.getenv("CODEX_USER_NAME"),
                System.getenv("BMAD_USER_NAME"),
                System.getenv("USERNAME"),
                System.getenv("USER")
        };
        for (String candidate : candidates) {
            if (candidate != null) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return "BMad";
    }

    /**
     * 从前端请求参数中解析语言配置，优先使用插件当前语言。
     */
    private String resolveRequestLanguage(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }

        try {
            JsonObject jsonObject = gson.fromJson(content, JsonObject.class);
            if (jsonObject != null && jsonObject.has("language")) {
                return jsonObject.get("language").getAsString();
            }
        } catch (Exception e) {
            LOG.warn("[BmadHandler] Failed to parse request language: " + e.getMessage());
        }
        return null;
    }

    /**
     * 判断语言代码是否应映射到中文安装输出。
     */
    private boolean isChineseLanguage(String language) {
        return language != null && language.trim().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    /**
     * 生成前端展示用的安装命令预览。
     */
    private String buildInstallCommandPreview(BmadProviderConfig providerConfig, String workspaceRoot) {
        if (providerConfig == null || workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
            return "";
        }
        String language = resolveInstallerLanguage(null);
        String userName = resolveInstallerUserName();
        return "npx bmad-method install --directory \"" + workspaceRoot + "\" --action update --tools "
                + providerConfig.toolCode
                + " --user-name \"" + userName + "\""
                + " --communication-language " + language
                + " --document-output-language " + language
                + " --output-folder _bmad-output"
                + " -y";
    }

    /**
     * Resolve the installed BMad version from the generated project manifest first,
     * then fall back to plugin metadata when the upstream installer has not written it yet.
     */
    private String resolveInstalledVersion(BmadProjectLayout projectLayout) {
        if (projectLayout == null || projectLayout.projectBmadDir == null) {
            return null;
        }

        Path manifestPath = projectLayout.projectBmadDir.resolve("_config").resolve("manifest.yaml");
        String version = ToolkitVersionUtil.readVersionFromFile(manifestPath);
        if (version != null && !version.isEmpty()) {
            return version;
        }

        Path moduleConfigPath = projectLayout.projectBmadDir.resolve("bmm").resolve("config.yaml");
        version = ToolkitVersionUtil.readVersionFromFile(moduleConfigPath);
        if (version != null && !version.isEmpty()) {
            return version;
        }

        version = ToolkitVersionUtil.readInstalledVersionMetadata(
                projectLayout.projectBmadDir.resolve(BMAD_METADATA_FILE)
        );
        if (version != null && !version.isEmpty()) {
            return version;
        }

        return null;
    }

    /**
     * Persist the resolved BMad version as a fallback for environments where upstream metadata is absent.
     */
    private void persistInstalledVersion(BmadProjectLayout projectLayout, String installedVersion) {
        if (projectLayout == null || projectLayout.projectBmadDir == null) {
            return;
        }

        ToolkitVersionUtil.writeInstalledVersionMetadata(
                projectLayout.projectBmadDir.resolve(BMAD_METADATA_FILE),
                installedVersion,
                BMAD_PACKAGE_NAME,
                LOG
        );
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
     * 推送状态回前端。
     */
    private void sendBmadStatus(JsonObject status) {
        String payload = gson.toJson(status);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateBmadStatus", escapeJs(payload))
        );
    }

    /**
     * 推送安装进度到前端。
     */
    private void sendInstallProgress(BmadProviderConfig providerConfig, String logLine) {
        JsonObject progress = new JsonObject();
        if (providerConfig != null) {
            progress.addProperty("provider", providerConfig.providerId);
        }
        progress.addProperty("log", logLine);

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.bmadInstallProgress", escapeJs(gson.toJson(progress)))
        );
    }

    /**
     * 推送安装结果到前端。
     */
    private void sendInstallResult(boolean success, BmadProviderConfig providerConfig, String message, String logs) {
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
                callJavaScript("window.bmadInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    /**
     * 推送“已有安装任务进行中”的占用结果，前端据此恢复按钮并显示提示，而不是当成失败处理。
     */
    private void sendInstallBusyResult(BmadProviderConfig providerConfig) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("busy", true);
        if (providerConfig != null) {
            result.addProperty("provider", providerConfig.providerId);
        }

        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.bmadInstallResult", escapeJs(gson.toJson(result)))
        );
    }

    /**
     * BMad provider 安装配置。
     */
    private static final class BmadProviderConfig {
        private final String providerId;
        private final String providerLabel;
        private final String toolCode;
        private final String commandPrefix;
        private final Path relativeSkillsDir;

        private BmadProviderConfig(
                String providerId,
                String providerLabel,
                String toolCode,
                String commandPrefix,
                Path relativeSkillsDir
        ) {
            this.providerId = providerId;
            this.providerLabel = providerLabel;
            this.toolCode = toolCode;
            this.commandPrefix = commandPrefix;
            this.relativeSkillsDir = relativeSkillsDir;
        }
    }

    /**
     * BMad 项目目录解析结果。
     */
    private static final class BmadProjectLayout {
        private final Path projectRoot;
        private final Path projectBmadDir;
        private final Path outputDir;
        private final Path targetSkillsDir;

        private BmadProjectLayout(Path projectRoot, Path projectBmadDir, Path outputDir, Path targetSkillsDir) {
            this.projectRoot = projectRoot;
            this.projectBmadDir = projectBmadDir;
            this.outputDir = outputDir;
            this.targetSkillsDir = targetSkillsDir;
        }
    }
}
