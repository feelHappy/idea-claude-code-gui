package com.github.claudecodegui.util;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Toolkit version utilities for project-scoped open-source integrations.
 */
public final class ToolkitVersionUtil {

    private static final Pattern COMMENT_VERSION_PATTERN = Pattern.compile("(?m)^#\\s*Version:\\s*([0-9A-Za-z._-]+)\\s*$");
    private static final Pattern YAML_VERSION_PATTERN = Pattern.compile("(?m)^\\s*version:\\s*([0-9A-Za-z._-]+)\\s*$");
    private static final Pattern VERSION_PART_PATTERN = Pattern.compile("^(\\d+)");
    private static final long VERSION_CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final Map<String, CachedVersion> NPM_VERSION_CACHE = new ConcurrentHashMap<>();

    private ToolkitVersionUtil() {
    }

    /**
     * Resolve the npm executable path from the node runtime or PATH.
     */
    public static String resolveNpmExecutable(String nodePath) {
        String executableName = PlatformUtils.isWindows() ? "npm.cmd" : "npm";

        if (nodePath != null && !nodePath.trim().isEmpty() && !"node".equalsIgnoreCase(nodePath.trim())) {
            File nodeFile = new File(nodePath);
            File nodeDir = nodeFile.getParentFile();
            if (nodeDir != null) {
                File npmFile = new File(nodeDir, executableName);
                if (npmFile.exists()) {
                    return npmFile.getAbsolutePath();
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
     * Query npm registry for the latest package version.
     */
    public static String getLatestNpmVersion(
            String nodePath,
            EnvironmentConfigurator envConfigurator,
            String packageName,
            Logger log
    ) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return null;
        }

        CachedVersion cachedVersion = NPM_VERSION_CACHE.get(packageName);
        long now = System.currentTimeMillis();
        if (cachedVersion != null && now - cachedVersion.checkedAtMillis < VERSION_CACHE_TTL_MILLIS) {
            return cachedVersion.version;
        }

        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    resolveNpmExecutable(nodePath),
                    "view",
                    packageName,
                    "version"
            );
            processBuilder.redirectErrorStream(true);
            if (envConfigurator != null && nodePath != null && !nodePath.trim().isEmpty()) {
                envConfigurator.updateProcessEnvironment(processBuilder, nodePath);
            }

            process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line.trim());
                }
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcess(process);
                return null;
            }

            if (process.exitValue() == 0) {
                String version = output.toString().trim();
                if (!version.isEmpty()) {
                    NPM_VERSION_CACHE.put(packageName, new CachedVersion(version, now));
                    return version;
                }
                return null;
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("[ToolkitVersionUtil] Failed to query npm version for " + packageName + ": " + e.getMessage());
            }
        } finally {
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcess(process);
            }
        }

        return null;
    }

    /**
     * Read a version value from a YAML-like manifest file.
     */
    public static String readVersionFromFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Matcher commentMatcher = COMMENT_VERSION_PATTERN.matcher(content);
            if (commentMatcher.find()) {
                return commentMatcher.group(1).trim();
            }

            Matcher yamlMatcher = YAML_VERSION_PATTERN.matcher(content);
            if (yamlMatcher.find()) {
                return yamlMatcher.group(1).trim();
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
    }

    /**
     * Read plugin-managed installed version metadata.
     */
    public static String readInstalledVersionMetadata(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }

        try {
            JsonObject jsonObject = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (jsonObject.has("installedVersion")) {
                String version = jsonObject.get("installedVersion").getAsString();
                return version == null || version.trim().isEmpty() ? null : version.trim();
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
    }

    /**
     * Persist plugin-managed installed version metadata.
     */
    public static void writeInstalledVersionMetadata(Path path, String installedVersion, String sourcePackage, Logger log) {
        if (path == null || installedVersion == null || installedVersion.trim().isEmpty()) {
            return;
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("installedVersion", installedVersion.trim());
            if (sourcePackage != null && !sourcePackage.trim().isEmpty()) {
                jsonObject.addProperty("packageName", sourcePackage.trim());
            }
            jsonObject.addProperty("updatedAt", Instant.now().toString());
            Files.writeString(path, jsonObject.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (log != null) {
                log.warn("[ToolkitVersionUtil] Failed to write toolkit metadata " + path + ": " + e.getMessage());
            }
        }
    }

    /**
     * Compare semantic-like versions.
     *
     * @return negative if installedVersion < latestVersion
     */
    public static int compareVersions(String installedVersion, String latestVersion) {
        if (installedVersion == null || latestVersion == null) {
            return 0;
        }

        String left = stripVersionPrefix(installedVersion);
        String right = stripVersionPrefix(latestVersion);
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");

        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            int leftNumber = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int rightNumber = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            if (leftNumber != rightNumber) {
                return leftNumber - rightNumber;
            }
        }

        return 0;
    }

    /**
     * Apply version fields to a status payload.
     */
    public static void applyVersionInfo(JsonObject status, String installedVersion, String latestVersion) {
        if (status == null) {
            return;
        }

        status.addProperty("hasUpdate", false);
        if (installedVersion != null && !installedVersion.trim().isEmpty()) {
            status.addProperty("installedVersion", installedVersion.trim());
        }
        if (latestVersion != null && !latestVersion.trim().isEmpty()) {
            status.addProperty("latestVersion", latestVersion.trim());
            if (installedVersion != null && !installedVersion.trim().isEmpty()) {
                status.addProperty("hasUpdate", compareVersions(installedVersion, latestVersion) < 0);
            }
        }
    }

    private static String stripVersionPrefix(String version) {
        String trimmed = version.trim();
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
    }

    private static int parseVersionPart(String part) {
        Matcher matcher = VERSION_PART_PATTERN.matcher(part);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    private static final class CachedVersion {
        private final String version;
        private final long checkedAtMillis;

        private CachedVersion(String version, long checkedAtMillis) {
            this.version = version;
            this.checkedAtMillis = checkedAtMillis;
        }
    }
}
