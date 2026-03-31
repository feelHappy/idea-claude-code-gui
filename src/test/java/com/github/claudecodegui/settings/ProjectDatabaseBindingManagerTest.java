package com.github.claudecodegui.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProjectDatabaseBindingManagerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void buildBaseServerUsesClasspathWildcardWithoutInvalidPathOnWindows() throws Exception {
        Path installDir = temporaryFolder.newFolder("db-mcp-server").toPath();
        Path libDir = Files.createDirectories(installDir.resolve("lib"));
        Files.writeString(libDir.resolve("db-mcp-server.jar"), "stub");

        JsonObject payload = new JsonObject();
        payload.addProperty("dbMcpServerPath", installDir.toString());
        payload.addProperty("jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/app_dev");
        payload.addProperty("username", "tester");
        payload.addProperty("password", "secret");

        ProjectDatabaseBinding binding = ProjectDatabaseBinding.fromJson(payload, ProjectDatabaseBinding.defaults());
        ProjectDatabaseBindingManager manager = new ProjectDatabaseBindingManager(null, null, null, null, null);

        Method method = ProjectDatabaseBindingManager.class.getDeclaredMethod(
                "buildBaseServer",
                String.class,
                ProjectDatabaseBinding.class,
                Path.class
        );
        method.setAccessible(true);

        JsonObject server = (JsonObject) method.invoke(
                manager,
                installDir.toString(),
                binding,
                installDir.resolve("project-db.json")
        );

        JsonArray args = server.getAsJsonObject("server").getAsJsonArray("args");
        String classpathArg = args.get(1).getAsString();

        assertTrue(classpathArg.endsWith("lib\\*") || classpathArg.endsWith("lib/*"));
        assertEquals("com.github.claudecodegui.dbmcp.DbMcpServerMain", args.get(2).getAsString());
        assertEquals("main", args.get(6).getAsString());
    }

    @Test
    public void buildBaseServerFallsBackToBundledInstallDirWhenPathIsBlank() throws Exception {
        Path bundledInstallDir = temporaryFolder.newFolder("bundled-db-mcp-server").toPath();
        Path libDir = Files.createDirectories(bundledInstallDir.resolve("lib"));
        Files.writeString(libDir.resolve("db-mcp-server.jar"), "stub");

        JsonObject payload = new JsonObject();
        payload.addProperty("jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/app_dev");
        payload.addProperty("username", "tester");
        payload.addProperty("password", "secret");

        ProjectDatabaseBinding binding = ProjectDatabaseBinding.fromJson(payload, ProjectDatabaseBinding.defaults());
        BundledDbMcpServerResolver resolver = new BundledDbMcpServerResolver() {
            @Override
            Path resolveBundledInstallDir() {
                return bundledInstallDir;
            }
        };
        ProjectDatabaseBindingManager manager = new ProjectDatabaseBindingManager(null, null, null, null, null, resolver);

        Method method = ProjectDatabaseBindingManager.class.getDeclaredMethod(
                "buildBaseServer",
                String.class,
                ProjectDatabaseBinding.class,
                Path.class
        );
        method.setAccessible(true);

        JsonObject server = (JsonObject) method.invoke(
                manager,
                bundledInstallDir.toString(),
                binding,
                bundledInstallDir.resolve("project-db.json")
        );

        JsonArray args = server.getAsJsonObject("server").getAsJsonArray("args");
        String classpathArg = args.get(1).getAsString();

        assertTrue(classpathArg.contains("bundled-db-mcp-server"));
        assertTrue(classpathArg.endsWith("lib\\*") || classpathArg.endsWith("lib/*"));
    }
}
