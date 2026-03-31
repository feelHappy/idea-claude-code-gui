package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the bundled db-mcp-server installDist directory from the installed plugin.
 */
class BundledDbMcpServerResolver {

    private static final Logger LOG = Logger.getInstance(BundledDbMcpServerResolver.class);
    private static final String DB_MCP_DIR_NAME = "db-mcp-server";
    private static final String PLUGIN_DIR_NAME = "cc-ai-toolkit";

    Path resolveBundledInstallDir() {
        List<Path> candidates = new ArrayList<>();
        addPluginCandidates(candidates);
        addClasspathCandidates(candidates);

        for (Path candidate : candidates) {
            if (isValidInstallDir(candidate)) {
                LOG.debug("[BundledDbMcpServerResolver] Resolved bundled db-mcp-server: " + candidate);
                return candidate;
            }
        }

        return null;
    }

    private void addPluginCandidates(List<Path> candidates) {
        try {
            PluginId pluginId = PluginId.getId(PlatformUtils.getPluginId());
            IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(pluginId);
            if (descriptor != null) {
                addCandidate(candidates, descriptor.getPluginPath().resolve(DB_MCP_DIR_NAME));
            }
        } catch (Throwable t) {
            LOG.debug("[BundledDbMcpServerResolver] Cannot resolve bundled path from descriptor: " + t.getMessage());
        }

        try {
            String pluginsRoot = PathManager.getPluginsPath();
            if (pluginsRoot != null && !pluginsRoot.isBlank()) {
                addCandidate(candidates, Paths.get(pluginsRoot, PLUGIN_DIR_NAME, DB_MCP_DIR_NAME));
                addCandidate(candidates, Paths.get(pluginsRoot, PlatformUtils.getPluginId(), DB_MCP_DIR_NAME));
            }

            String systemPath = PathManager.getSystemPath();
            if (systemPath != null && !systemPath.isBlank()) {
                Path sandboxPlugins = Paths.get(systemPath, "plugins");
                addCandidate(candidates, sandboxPlugins.resolve(PLUGIN_DIR_NAME).resolve(DB_MCP_DIR_NAME));
                addCandidate(candidates, sandboxPlugins.resolve(PlatformUtils.getPluginId()).resolve(DB_MCP_DIR_NAME));
            }
        } catch (Throwable t) {
            LOG.debug("[BundledDbMcpServerResolver] Cannot resolve bundled path from IDE paths: " + t.getMessage());
        }
    }

    private void addClasspathCandidates(List<Path> candidates) {
        try {
            CodeSource codeSource = BundledDbMcpServerResolver.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return;
            }

            Path location = Paths.get(codeSource.getLocation().toURI());
            Path current = Files.isDirectory(location) ? location : location.getParent();
            while (current != null) {
                addCandidate(candidates, current.resolve(DB_MCP_DIR_NAME));

                String name = current.getFileName() != null ? current.getFileName().toString() : "";
                if (PLUGIN_DIR_NAME.equals(name) || PlatformUtils.getPluginId().equals(name)) {
                    break;
                }
                current = current.getParent();
            }
        } catch (Throwable t) {
            LOG.debug("[BundledDbMcpServerResolver] Cannot resolve bundled path from classpath: " + t.getMessage());
        }
    }

    private void addCandidate(List<Path> candidates, Path candidate) {
        if (candidate == null) {
            return;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!candidates.contains(normalized)) {
            candidates.add(normalized);
        }
    }

    private boolean isValidInstallDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }

        Path libDir = dir.resolve("lib");
        if (!Files.isDirectory(libDir)) {
            return false;
        }

        try (var stream = Files.list(libDir)) {
            return stream.anyMatch(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar"));
        } catch (Exception e) {
            LOG.debug("[BundledDbMcpServerResolver] Failed to inspect bundled db-mcp-server: " + e.getMessage());
            return false;
        }
    }
}
