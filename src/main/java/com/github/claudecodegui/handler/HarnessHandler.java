package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @description Harness 知识体系安装与状态管理处理器。
 * 负责检测当前项目的 .harness/ 状态，并执行一键初始化（base + variant + CLAUDE.md 注入）。
 * @author zyl
 * @date 2026/05/13
 */
public class HarnessHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(HarnessHandler.class);
    private static final String[] SUPPORTED_TYPES = {
            "get_harness_status",
            "install_harness"
    };
    private static final String HARNESS_DIR = ".harness";
    private static final String TEMPLATE_BASE = "/harness-templates/base/";
    private static final String TEMPLATE_VARIANTS = "/harness-templates/variants/";
    private static final String TEMPLATE_SHARED_PLAYBOOKS = "/harness-templates/shared-playbooks/";
    private static final String CLAUDE_MD_SNIPPET = "/harness-templates/claude-md-snippet.md";

    private static final String[] BASE_FILES = {
            "rules/auto-ingest.md",
            "rules/development-flow.md",
            "rules/progress-management.md",
            "playbooks/index.md",
            "skills/index.md",
            "templates/playbook-template.md",
            "templates/summary.md"
    };

    private static final String[] SHARED_PLAYBOOKS = {
            "easyexcel-converter.md",
            "workflow-idempotency.md"
    };

    private final AtomicBoolean installInProgress = new AtomicBoolean(false);
    private final Gson gson = new Gson();

    public HarnessHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_harness_status":
                handleGetStatus();
                return true;
            case "install_harness":
                handleInstall(content);
                return true;
            default:
                return false;
        }
    }

    private void handleGetStatus() {
        CompletableFuture.runAsync(() -> {
            JsonObject status = buildStatus();
            sendHarnessStatus(status);
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[HarnessHandler] Failed to get harness status: " + ex.getMessage(), ex);
            sendHarnessStatus(buildErrorStatus("Failed to check harness status: " + ex.getMessage()));
            return null;
        });
    }

    private void handleInstall(String content) {
        if (!installInProgress.compareAndSet(false, true)) {
            LOG.info("[HarnessHandler] Ignoring duplicate harness install request.");
            sendInstallResult(false, "Harness installation is already in progress.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String workspaceRoot = getWorkspaceRoot();
                if (workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
                    sendInstallResult(false, "Cannot determine the current working directory.");
                    return;
                }

                String variant = detectVariant(content, workspaceRoot);
                String projectName = detectProjectName(workspaceRoot);
                Path harnessDir = Paths.get(workspaceRoot, HARNESS_DIR);

                sendInstallProgress("Detecting tech stack: " + variant);

                // 1. Copy base files
                sendInstallProgress("Installing base knowledge framework...");
                for (String file : BASE_FILES) {
                    copyTemplateFile(TEMPLATE_BASE + file, harnessDir.resolve(file), projectName);
                }

                // 2. Copy variant files (coding-standards + owner agent)
                sendInstallProgress("Applying " + variant + " variant...");
                String variantPrefix = TEMPLATE_VARIANTS + variant + "/";
                copyTemplateFile(variantPrefix + "rules/coding-standards.md",
                        harnessDir.resolve("rules/coding-standards.md"), projectName);
                copyTemplateFile(variantPrefix + "agents/owner.md",
                        harnessDir.resolve("agents/owner.md"), projectName);

                // 3. Copy shared playbooks (optional, only for Java projects)
                if ("spring-boot".equals(variant)) {
                    sendInstallProgress("Installing shared playbooks...");
                    for (String playbook : SHARED_PLAYBOOKS) {
                        copyTemplateFile(TEMPLATE_SHARED_PLAYBOOKS + playbook,
                                harnessDir.resolve("playbooks/" + playbook), projectName);
                    }
                }

                // 4. Inject CLAUDE.md snippet
                sendInstallProgress("Injecting CLAUDE.md configuration...");
                injectClaudeMdSnippet(Paths.get(workspaceRoot), projectName);

                // 5. Create _bmad-output/planning directory
                Files.createDirectories(Paths.get(workspaceRoot, "_bmad-output", "planning"));

                sendInstallResult(true,
                        "Harness knowledge system installed successfully (" + variant + " variant). "
                                + "Knowledge will auto-accumulate as you develop.");
                sendHarnessStatus(buildStatus());
            } catch (Exception e) {
                LOG.error("[HarnessHandler] Failed to install harness: " + e.getMessage(), e);
                sendInstallResult(false, "Failed to install harness: " + e.getMessage());
            } finally {
                installInProgress.set(false);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    // ─── Status Building ───────────────────────────────────────────────

    private JsonObject buildStatus() {
        JsonObject status = new JsonObject();
        String workspaceRoot = getWorkspaceRoot();

        if (workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
            status.addProperty("state", "error");
            status.addProperty("message", "Cannot determine the current working directory.");
            return status;
        }

        Path harnessDir = Paths.get(workspaceRoot, HARNESS_DIR);
        boolean hasHarness = Files.isDirectory(harnessDir);
        boolean hasRules = Files.isDirectory(harnessDir.resolve("rules"));
        boolean hasPlaybooks = Files.isDirectory(harnessDir.resolve("playbooks"));
        boolean hasOwner = Files.isRegularFile(harnessDir.resolve("agents/owner.md"));
        boolean hasAutoIngest = Files.isRegularFile(harnessDir.resolve("rules/auto-ingest.md"));

        int playbookCount = countFiles(harnessDir.resolve("playbooks"), ".md") - 1; // minus index.md
        if (playbookCount < 0) playbookCount = 0;

        status.addProperty("hasHarness", hasHarness);
        status.addProperty("hasRules", hasRules);
        status.addProperty("hasPlaybooks", hasPlaybooks);
        status.addProperty("hasOwner", hasOwner);
        status.addProperty("hasAutoIngest", hasAutoIngest);
        status.addProperty("playbookCount", playbookCount);

        String detectedVariant = detectVariantFromExisting(workspaceRoot);
        status.addProperty("variant", detectedVariant);

        if (hasHarness && hasRules && hasPlaybooks && hasOwner && hasAutoIngest) {
            status.addProperty("state", "ready");
            status.addProperty("message",
                    "Harness is active (" + detectedVariant + "). " + playbookCount + " playbook(s) accumulated.");
        } else if (hasHarness) {
            status.addProperty("state", "partial");
            status.addProperty("message", "Harness directory exists but is incomplete. Consider reinstalling.");
        } else {
            status.addProperty("state", "missing");
            status.addProperty("message", "Harness not installed. Click Install to set up the knowledge system.");
        }

        return status;
    }

    private JsonObject buildErrorStatus(String message) {
        JsonObject status = new JsonObject();
        status.addProperty("state", "error");
        status.addProperty("message", message);
        return status;
    }

    // ─── Tech Stack Detection ──────────────────────────────────────────

    private String detectVariant(String content, String workspaceRoot) {
        // Check if user explicitly specified a variant
        if (content != null && !content.trim().isEmpty()) {
            try {
                JsonObject params = JsonParser.parseString(content).getAsJsonObject();
                if (params.has("variant")) {
                    return params.get("variant").getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        return detectVariantFromExisting(workspaceRoot);
    }

    private String detectVariantFromExisting(String workspaceRoot) {
        Path root = Paths.get(workspaceRoot);
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            try {
                String pom = new String(Files.readAllBytes(root.resolve("pom.xml")), StandardCharsets.UTF_8);
                if (pom.contains("spring-boot") || pom.contains("spring-cloud") || pom.contains("springframework")) {
                    return "spring-boot";
                }
            } catch (IOException ignored) {
            }
            return "generic";
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            try {
                Path gradleFile = Files.isRegularFile(root.resolve("build.gradle"))
                        ? root.resolve("build.gradle") : root.resolve("build.gradle.kts");
                String gradle = new String(Files.readAllBytes(gradleFile), StandardCharsets.UTF_8);
                if (gradle.contains("spring-boot") || gradle.contains("org.springframework")) {
                    return "spring-boot";
                }
            } catch (IOException ignored) {
            }
            return "generic";
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            try {
                String packageJson = new String(Files.readAllBytes(root.resolve("package.json")), StandardCharsets.UTF_8);
                if (packageJson.contains("\"vue\"") || packageJson.contains("\"@vue/")) {
                    return "vue";
                }
            } catch (IOException ignored) {
            }
            return "generic";
        }
        return "generic";
    }

    private String detectProjectName(String workspaceRoot) {
        Path root = Paths.get(workspaceRoot);
        // Try pom.xml artifactId
        Path pomFile = root.resolve("pom.xml");
        if (Files.isRegularFile(pomFile)) {
            try {
                String pom = new String(Files.readAllBytes(pomFile), StandardCharsets.UTF_8);
                int start = pom.indexOf("<artifactId>");
                int end = pom.indexOf("</artifactId>");
                if (start > 0 && end > start) {
                    String artifactId = pom.substring(start + 12, end).trim();
                    if (!artifactId.isEmpty() && !artifactId.startsWith("$")) {
                        return artifactId;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        // Try package.json name
        Path packageJson = root.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            try {
                String content = new String(Files.readAllBytes(packageJson), StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                if (json.has("name")) {
                    return json.get("name").getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        // Fallback to directory name
        return root.getFileName().toString();
    }

    // ─── File Operations ───────────────────────────────────────────────

    private void copyTemplateFile(String resourcePath, Path targetPath, String projectName) throws IOException {
        Files.createDirectories(targetPath.getParent());
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("[HarnessHandler] Template resource not found: " + resourcePath);
                return;
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            content = content.replace("{{PROJECT_NAME}}", projectName);
            content = content.replace("{{PROJECT_DESCRIPTION}}", "");
            content = content.replace("{{ADDITIONAL_TECH}}", "");
            content = content.replace("{{REQUIREMENT_CODE}}", "");
            Files.write(targetPath, content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void injectClaudeMdSnippet(Path projectRoot, String projectName) throws IOException {
        Path claudeMdPath = projectRoot.resolve("CLAUDE.md");
        String snippet;
        try (InputStream is = getClass().getResourceAsStream(CLAUDE_MD_SNIPPET)) {
            if (is == null) {
                LOG.warn("[HarnessHandler] CLAUDE.md snippet resource not found.");
                return;
            }
            snippet = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            snippet = snippet.replace("{{PROJECT_NAME}}", projectName);
        }

        String marker = "# Harness 知识体系";
        if (Files.isRegularFile(claudeMdPath)) {
            String existing = new String(Files.readAllBytes(claudeMdPath), StandardCharsets.UTF_8);
            if (existing.contains(marker)) {
                // Already injected, skip
                return;
            }
            // Append to existing CLAUDE.md
            String merged = existing + "\n\n---\n\n" + snippet;
            Files.write(claudeMdPath, merged.getBytes(StandardCharsets.UTF_8));
        } else {
            // Create new CLAUDE.md with snippet
            Files.write(claudeMdPath, snippet.getBytes(StandardCharsets.UTF_8));
        }
    }

    private int countFiles(Path dir, String extension) {
        if (!Files.isDirectory(dir)) return 0;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return (int) stream
                    .filter(p -> p.toString().endsWith(extension))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ─── Frontend Communication ────────────────────────────────────────

    private void sendHarnessStatus(JsonObject status) {
        String payload = gson.toJson(status);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateHarnessStatus", escapeJs(payload))
        );
    }

    private void sendInstallProgress(String message) {
        JsonObject progress = new JsonObject();
        progress.addProperty("type", "progress");
        progress.addProperty("message", message);
        String payload = gson.toJson(progress);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateHarnessInstall", escapeJs(payload))
        );
    }

    private void sendInstallResult(boolean success, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "result");
        result.addProperty("success", success);
        result.addProperty("message", message);
        String payload = gson.toJson(result);
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("window.updateHarnessInstall", escapeJs(payload))
        );
    }

    private String getWorkspaceRoot() {
        if (context.getSession() != null && context.getSession().getCwd() != null) {
            return context.getSession().getCwd();
        }
        if (context.getProject() != null && context.getProject().getBasePath() != null) {
            return context.getProject().getBasePath();
        }
        return null;
    }
}
