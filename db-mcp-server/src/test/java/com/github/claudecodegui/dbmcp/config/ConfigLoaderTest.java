package com.github.claudecodegui.dbmcp.config;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigLoaderTest {

    @Test
    public void loadSelectsRequestedSourceAndAppliesDefaults() throws Exception {
        Path configFile = Files.createTempFile("db-mcp-config", ".json");
        Files.writeString(configFile, """
                {
                  "defaultSource": "dev-pg",
                  "sources": [
                    {
                      "id": "dev-pg",
                      "dialect": "postgresql",
                      "jdbcUrl": "jdbc:postgresql://localhost:5432/app_dev",
                      "username": "tester",
                      "password": "secret"
                    },
                    {
                      "id": "dev-mysql",
                      "dialect": "mysql",
                      "mode": "read_only",
                      "jdbcUrl": "jdbc:mysql://localhost:3306/app_dev",
                      "username": "tester",
                      "password": "secret"
                    }
                  ]
                }
                """);

        ServerConfiguration configuration = ConfigLoader.load(configFile, "dev-mysql");

        assertEquals("dev-mysql", configuration.activeSource().id());
        assertEquals(DatabaseMode.READ_ONLY, configuration.activeSource().mode());
        assertEquals(500, configuration.activeSource().maxRows());
        assertTrue(configuration.sources().containsKey("dev-pg"));
    }
}
