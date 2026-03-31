package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Function;

/**
 * Manages per-project development database bindings and generates MCP launchers.
 */
public class ProjectDatabaseBindingManager {

    private static final Logger LOG = Logger.getInstance(ProjectDatabaseBindingManager.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_KEY = "projectDatabases";
    private static final String GENERATED_DIR_NAME = "project-db-mcp";
    private static final String SERVER_MAIN_CLASS = "com.github.claudecodegui.dbmcp.DbMcpServerMain";
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int QUERY_TIMEOUT_SEC = 30;
    private static final int CODEX_STARTUP_TIMEOUT_SEC = 30;
    private static final int CODEX_TOOL_TIMEOUT_SEC = 120;

    private final ConfigPathManager pathManager;
    private final Function<Void, JsonObject> configReader;
    private final java.util.function.Consumer<JsonObject> configWriter;
    private final McpServerManager mcpServerManager;
    private final CodexMcpServerManager codexMcpServerManager;
    private final BundledDbMcpServerResolver bundledDbMcpServerResolver;

    public ProjectDatabaseBindingManager(
            ConfigPathManager pathManager,
            Function<Void, JsonObject> configReader,
            java.util.function.Consumer<JsonObject> configWriter,
            McpServerManager mcpServerManager,
            CodexMcpServerManager codexMcpServerManager) {
        this(
                pathManager,
                configReader,
                configWriter,
                mcpServerManager,
                codexMcpServerManager,
                new BundledDbMcpServerResolver()
        );
    }

    ProjectDatabaseBindingManager(
            ConfigPathManager pathManager,
            Function<Void, JsonObject> configReader,
            java.util.function.Consumer<JsonObject> configWriter,
            McpServerManager mcpServerManager,
            CodexMcpServerManager codexMcpServerManager,
            BundledDbMcpServerResolver bundledDbMcpServerResolver) {
        this.pathManager = pathManager;
        this.configReader = configReader;
        this.configWriter = configWriter;
        this.mcpServerManager = mcpServerManager;
        this.codexMcpServerManager = codexMcpServerManager;
        this.bundledDbMcpServerResolver = bundledDbMcpServerResolver;
    }

    public JsonObject getProjectDatabaseBinding(String projectPath) {
        ProjectDatabaseBinding binding = loadBinding(configReader.apply(null), projectPath);
        return buildResponse(projectPath, binding);
    }

    public JsonObject upsertProjectDatabaseBinding(String projectPath, JsonObject payload) throws IOException {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("Project path is required");
        }

        JsonObject config = configReader.apply(null);
        JsonObject bindings = ensureBindings(config);

        ProjectDatabaseBinding existing = loadBinding(config, projectPath);
        ProjectDatabaseBinding binding = ProjectDatabaseBinding.fromJson(payload, existing);

        validate(binding);

        bindings.add(projectPath, binding.toJson());
        configWriter.accept(config);

        Path generatedConfigPath = writeGeneratedServerConfig(projectPath, binding);
        upsertClaudeLauncher(projectPath, binding, generatedConfigPath);
        upsertCodexLauncher(projectPath, binding, generatedConfigPath);

        LOG.info("[ProjectDatabaseBindingManager] Saved database binding for project: " + projectPath);
        return buildResponse(projectPath, binding);
    }

    private ProjectDatabaseBinding loadBinding(JsonObject config, String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return ProjectDatabaseBinding.defaults();
        }
        if (config == null || !config.has(CONFIG_KEY) || !config.get(CONFIG_KEY).isJsonObject()) {
            return ProjectDatabaseBinding.defaults();
        }
        JsonObject bindings = config.getAsJsonObject(CONFIG_KEY);
        if (!bindings.has(projectPath) || !bindings.get(projectPath).isJsonObject()) {
            return ProjectDatabaseBinding.defaults();
        }
        return ProjectDatabaseBinding.fromJson(bindings.getAsJsonObject(projectPath), ProjectDatabaseBinding.defaults());
    }

    private JsonObject ensureBindings(JsonObject config) {
        if (!config.has(CONFIG_KEY) || !config.get(CONFIG_KEY).isJsonObject()) {
            config.add(CONFIG_KEY, new JsonObject());
        }
        return config.getAsJsonObject(CONFIG_KEY);
    }

    private void validate(ProjectDatabaseBinding binding) {
        if (!binding.sourceId().matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("sourceId only supports letters, digits, dot, underscore, and hyphen");
        }
        if (!"postgresql".equals(binding.dialect())
                && !"mysql".equals(binding.dialect())
                && !"oracle".equals(binding.dialect())) {
            throw new IllegalArgumentException("Unsupported dialect: " + binding.dialect());
        }
        if (!"dev-write".equals(binding.mode()) && !"read-only".equals(binding.mode())) {
            throw new IllegalArgumentException("Unsupported database mode: " + binding.mode());
        }
        if (binding.jdbcUrl().isBlank()) {
            throw new IllegalArgumentException("JDBC URL is required");
        }
        if (binding.username().isBlank() && binding.usernameEnv().isBlank()) {
            throw new IllegalArgumentException("Username or username environment variable is required");
        }
        if (binding.password().isBlank() && binding.passwordEnv().isBlank()) {
            throw new IllegalArgumentException("Password or password environment variable is required");
        }
        if (binding.maxRows() <= 0) {
            throw new IllegalArgumentException("maxRows must be greater than 0");
        }
        if (binding.maxAffectedRows() <= 0) {
            throw new IllegalArgumentException("maxAffectedRows must be greater than 0");
        }

        resolveEffectiveInstallDir(binding);
    }

    private JsonObject buildResponse(String projectPath, ProjectDatabaseBinding binding) {
        JsonObject response = binding.toJson();
        response.addProperty("projectPath", projectPath == null ? "" : projectPath);

        String serverId = buildServerId(projectPath);
        response.addProperty("serverId", serverId);
        Path generatedConfigPath = getGeneratedConfigPath(projectPath);
        response.addProperty("generatedConfigPath", Files.exists(generatedConfigPath) ? generatedConfigPath.toString() : "");
        response.addProperty("javaExecutable", resolveJavaExecutable().toString());

        Path bundledInstallDir = bundledDbMcpServerResolver.resolveBundledInstallDir();
        response.addProperty("bundledDbMcpServerAvailable", bundledInstallDir != null);

        try {
            Path resolvedInstallDir = resolveEffectiveInstallDir(binding);
            response.addProperty("resolvedDbMcpInstallDir", resolvedInstallDir.toString());
            response.addProperty("usingBundledDbMcpServer", binding.dbMcpServerPath().isBlank() && bundledInstallDir != null);
        } catch (Exception ignored) {
            response.addProperty("resolvedDbMcpInstallDir", "");
            response.addProperty("usingBundledDbMcpServer", false);
        }

        return response;
    }

    private Path writeGeneratedServerConfig(String projectPath, ProjectDatabaseBinding binding) throws IOException {
        Path configPath = getGeneratedConfigPath(projectPath);
        Files.createDirectories(configPath.getParent());

        JsonObject source = new JsonObject();
        source.addProperty("id", binding.sourceId());
        source.addProperty("dialect", binding.dialect());
        source.addProperty("mode", binding.mode());
        source.addProperty("jdbcUrl", binding.jdbcUrl());
        if (!binding.username().isBlank()) {
            source.addProperty("username", binding.username());
        }
        if (!binding.password().isBlank()) {
            source.addProperty("password", binding.password());
        }
        if (!binding.usernameEnv().isBlank()) {
            source.addProperty("usernameEnv", binding.usernameEnv());
        }
        if (!binding.passwordEnv().isBlank()) {
            source.addProperty("passwordEnv", binding.passwordEnv());
        }

        String effectiveSchema = determineSchema(binding);
        if (!effectiveSchema.isBlank()) {
            source.addProperty("schema", effectiveSchema);
        }
        source.addProperty("connectTimeoutSec", CONNECT_TIMEOUT_SEC);
        source.addProperty("queryTimeoutSec", QUERY_TIMEOUT_SEC);
        source.addProperty("maxRows", binding.maxRows());
        source.addProperty("maxAffectedRows", binding.maxAffectedRows());
        source.addProperty("requireWhereForUpdateDelete", binding.requireWhereForUpdateDelete());

        JsonArray sources = new JsonArray();
        sources.add(source);

        JsonObject root = new JsonObject();
        root.addProperty("defaultSource", binding.sourceId());
        root.add("sources", sources);

        writeStringAtomically(configPath, GSON.toJson(root));
        return configPath;
    }

    private void upsertClaudeLauncher(String projectPath, ProjectDatabaseBinding binding, Path generatedConfigPath)
            throws IOException {
        JsonObject server = buildBaseServer(projectPath, binding, generatedConfigPath);
        JsonObject apps = new JsonObject();
        apps.addProperty("claude", true);
        apps.addProperty("codex", false);
        apps.addProperty("gemini", false);
        server.add("apps", apps);

        mcpServerManager.upsertProjectScopedMcpServer(server, projectPath);
    }

    private void upsertCodexLauncher(String projectPath, ProjectDatabaseBinding binding, Path generatedConfigPath)
            throws IOException {
        JsonObject server = buildBaseServer(projectPath, binding, generatedConfigPath);
        JsonObject apps = new JsonObject();
        apps.addProperty("claude", false);
        apps.addProperty("codex", true);
        apps.addProperty("gemini", false);
        server.add("apps", apps);
        server.addProperty("startup_timeout_sec", CODEX_STARTUP_TIMEOUT_SEC);
        server.addProperty("tool_timeout_sec", CODEX_TOOL_TIMEOUT_SEC);

        codexMcpServerManager.upsertMcpServer(server);
    }

    private JsonObject buildBaseServer(String projectPath, ProjectDatabaseBinding binding, Path generatedConfigPath) {
        JsonObject server = new JsonObject();
        server.addProperty("id", buildServerId(projectPath));
        server.addProperty("name", buildServerName(projectPath));
        server.addProperty("enabled", binding.enabled());

        JsonObject serverSpec = new JsonObject();
        serverSpec.addProperty("type", "stdio");
        serverSpec.addProperty("command", resolveJavaExecutable().toString());
        serverSpec.addProperty("cwd", projectPath);
        String classpathWildcard = resolveEffectiveInstallDir(binding).resolve("lib") + File.separator + "*";

        JsonArray args = new JsonArray();
        args.add("-cp");
        args.add(classpathWildcard);
        args.add(SERVER_MAIN_CLASS);
        args.add("--config");
        args.add(generatedConfigPath.toString());
        args.add("--source");
        args.add(binding.sourceId());
        serverSpec.add("args", args);

        server.add("server", serverSpec);
        return server;
    }

    private Path resolveEffectiveInstallDir(ProjectDatabaseBinding binding) {
        if (binding != null && binding.dbMcpServerPath() != null && !binding.dbMcpServerPath().isBlank()) {
            return resolveInstallDir(binding.dbMcpServerPath());
        }

        Path bundledInstallDir = bundledDbMcpServerResolver.resolveBundledInstallDir();
        if (bundledInstallDir != null) {
            return bundledInstallDir;
        }

        throw new IllegalArgumentException("db-mcp-server path is required when bundled server is unavailable");
    }

    private Path getGeneratedConfigPath(String projectPath) {
        return pathManager.getConfigDir()
                .resolve(GENERATED_DIR_NAME)
                .resolve(buildServerId(projectPath) + ".json");
    }

    private String determineSchema(ProjectDatabaseBinding binding) {
        if (!binding.schema().isBlank()) {
            return binding.schema();
        }
        if ("postgresql".equals(binding.dialect())) {
            return "public";
        }
        if ("oracle".equals(binding.dialect()) && !binding.username().isBlank()) {
            return binding.username().toUpperCase(Locale.ROOT);
        }
        return "";
    }

    private Path resolveInstallDir(String configuredPath) {
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("db-mcp-server path does not exist: " + path);
        }

        Path installDir;
        if (Files.isDirectory(path)) {
            if (Files.isDirectory(path.resolve("lib"))) {
                installDir = path;
            } else if ("bin".equalsIgnoreCase(path.getFileName().toString()) && path.getParent() != null) {
                installDir = path.getParent();
            } else if ("lib".equalsIgnoreCase(path.getFileName().toString()) && path.getParent() != null) {
                installDir = path.getParent();
            } else {
                throw new IllegalArgumentException("db-mcp-server path must point to installDist root, bin, or lib directory");
            }
        } else {
            Path parent = path.getParent();
            if (parent == null || parent.getParent() == null) {
                throw new IllegalArgumentException("Unable to resolve db-mcp-server install directory from: " + path);
            }
            installDir = parent.getParent();
        }

        Path libDir = installDir.resolve("lib");
        if (!Files.isDirectory(libDir)) {
            throw new IllegalArgumentException("db-mcp-server install directory is missing lib/: " + installDir);
        }

        try (var stream = Files.list(libDir)) {
            boolean hasJar = stream.anyMatch(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar"));
            if (!hasJar) {
                throw new IllegalArgumentException("db-mcp-server lib/ does not contain runtime jars: " + libDir);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to inspect db-mcp-server lib directory: " + libDir, e);
        }

        return installDir;
    }

    private Path resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isBlank()) {
            Path candidate = Path.of(javaHome, "bin", PlatformUtils.isWindows() ? "java.exe" : "java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of(PlatformUtils.isWindows() ? "java.exe" : "java");
    }

    private String buildServerId(String projectPath) {
        String baseName = projectPath == null || projectPath.isBlank()
                ? "project"
                : Path.of(projectPath).getFileName().toString();
        String slug = baseName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (slug.isBlank()) {
            slug = "project";
        }
        return "db-" + slug + "-" + shortHash(projectPath == null ? "global" : projectPath);
    }

    private String buildServerName(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return "Development Database";
        }
        return "Development Database (" + Path.of(projectPath).getFileName() + ")";
    }

    private String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash project path", e);
        }
    }

    private void writeStringAtomically(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return;
        }

        Path tempFile = Files.createTempFile(parent, target.getFileName().toString() + "-", ".tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
