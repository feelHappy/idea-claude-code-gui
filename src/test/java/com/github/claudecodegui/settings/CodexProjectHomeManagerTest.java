package com.github.claudecodegui.settings;

import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexProjectHomeManagerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void filterConfigForProjectRemovesOtherProjectsDbServers() throws Exception {
        Path projectA = temporaryFolder.newFolder("project-a").toPath();
        Path projectB = temporaryFolder.newFolder("project-b").toPath();

        CodexProjectHomeManager manager = new CodexProjectHomeManager(
                new Gson(),
                temporaryFolder.newFolder("codex-project-homes").toPath()
        );

        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> mcpServers = new LinkedHashMap<>();
        mcpServers.put("db-project-a", buildDbServer(projectA));
        mcpServers.put("db-project-b", buildDbServer(projectB));
        mcpServers.put("filesystem", buildNonDbServer());
        config.put("mcp_servers", mcpServers);

        Map<String, Object> filtered = manager.filterConfigForProject(config, projectA);

        @SuppressWarnings("unchecked")
        Map<String, Object> filteredMcpServers = (Map<String, Object>) filtered.get("mcp_servers");
        assertTrue(filteredMcpServers.containsKey("db-project-a"));
        assertFalse(filteredMcpServers.containsKey("db-project-b"));
        assertTrue(filteredMcpServers.containsKey("filesystem"));
    }

    @Test
    public void prepareProjectScopedHomeCopiesAuthAndSkills() throws Exception {
        Path globalCodexHome = temporaryFolder.newFolder("global-codex-home").toPath();
        Path configRoot = temporaryFolder.newFolder("codemoss-root").toPath();
        Path project = temporaryFolder.newFolder("project-main").toPath();

        Files.writeString(globalCodexHome.resolve("auth.json"), "{\"token\":\"abc\"}");
        Files.createDirectories(globalCodexHome.resolve("skills"));
        Files.writeString(globalCodexHome.resolve("skills").resolve("demo.md"), "skill");

        CodexSettingsManager sourceSettings = new CodexSettingsManager(new Gson(), globalCodexHome);
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> mcpServers = new LinkedHashMap<>();
        mcpServers.put("db-project-main", buildDbServer(project));
        mcpServers.put("db-other", buildDbServer(temporaryFolder.newFolder("other-project").toPath()));
        config.put("mcp_servers", mcpServers);
        sourceSettings.writeConfigToml(config);

        CodexProjectHomeManager manager = new CodexProjectHomeManager(new Gson(), configRoot);
        String scopedHome = manager.prepareProjectScopedHome(project.toString(), globalCodexHome.toString());

        Path scopedHomePath = Path.of(scopedHome);
        assertTrue(Files.isRegularFile(scopedHomePath.resolve("auth.json")));
        assertTrue(Files.isRegularFile(scopedHomePath.resolve("skills").resolve("demo.md")));

        CodexSettingsManager scopedSettings = new CodexSettingsManager(new Gson(), scopedHomePath);
        Map<String, Object> scopedConfig = scopedSettings.readConfigToml();
        @SuppressWarnings("unchecked")
        Map<String, Object> scopedMcpServers = (Map<String, Object>) scopedConfig.get("mcp_servers");
        assertEquals(1, scopedMcpServers.size());
        assertTrue(scopedMcpServers.containsKey("db-project-main"));
    }

    private static Map<String, Object> buildDbServer(Path projectPath) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("command", "java");
        server.put("args", List.of(
                "-cp",
                "db-mcp-server/lib/*",
                "com.github.claudecodegui.dbmcp.DbMcpServerMain",
                "--config",
                "config.json",
                "--source",
                "main"
        ));
        server.put("cwd", projectPath.toString());
        server.put("enabled", true);
        return server;
    }

    private static Map<String, Object> buildNonDbServer() {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("command", "npx");
        server.put("args", List.of("-y", "@modelcontextprotocol/server-filesystem"));
        server.put("cwd", "D:/shared");
        return server;
    }
}
