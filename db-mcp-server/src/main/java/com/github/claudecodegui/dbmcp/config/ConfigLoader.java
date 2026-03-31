package com.github.claudecodegui.dbmcp.config;

import com.github.claudecodegui.dbmcp.dialect.DatabaseDialect;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigLoader {

    private static final Gson GSON = new GsonBuilder().create();

    private ConfigLoader() {
    }

    public static ServerConfiguration load(Path configPath, String requestedSourceId) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            RawServerConfiguration raw = GSON.fromJson(reader, RawServerConfiguration.class);
            if (raw == null || raw.sources == null || raw.sources.isEmpty()) {
                throw new IllegalArgumentException("No database sources configured");
            }

            Map<String, DatabaseSource> sources = new LinkedHashMap<>();
            for (RawDatabaseSource rawSource : raw.sources) {
                DatabaseSource source = toSource(rawSource);
                if (sources.put(source.id(), source) != null) {
                    throw new IllegalArgumentException("Duplicate source id: " + source.id());
                }
            }

            String activeId = requestedSourceId != null && !requestedSourceId.isBlank()
                    ? requestedSourceId
                    : raw.defaultSource;
            if (activeId == null || activeId.isBlank()) {
                activeId = sources.keySet().iterator().next();
            }

            DatabaseSource activeSource = sources.get(activeId);
            if (activeSource == null) {
                throw new IllegalArgumentException("Unknown source id: " + activeId);
            }

            return new ServerConfiguration(sources, activeSource);
        }
    }

    private static DatabaseSource toSource(RawDatabaseSource raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Null source entry");
        }
        if (raw.id == null || raw.id.isBlank()) {
            throw new IllegalArgumentException("Database source id is required");
        }
        if (raw.jdbcUrl == null || raw.jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl is required for source " + raw.id);
        }

        DatabaseDialect dialect = DatabaseDialect.from(raw.dialect, raw.jdbcUrl);
        DatabaseMode mode = DatabaseMode.fromString(raw.mode);

        return new DatabaseSource(
                raw.id.trim(),
                dialect,
                mode,
                raw.jdbcUrl.trim(),
                normalize(raw.driverClass) != null ? normalize(raw.driverClass) : defaultDriverClass(dialect),
                normalize(raw.username),
                normalize(raw.usernameEnv),
                normalize(raw.password),
                normalize(raw.passwordEnv),
                normalize(raw.schema),
                defaultIfNull(raw.connectTimeoutSec, 5),
                defaultIfNull(raw.queryTimeoutSec, 30),
                defaultIfNull(raw.maxRows, 500),
                defaultIfNull(raw.maxAffectedRows, 5000),
                raw.requireWhereForUpdateDelete == null || raw.requireWhereForUpdateDelete
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int defaultIfNull(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String defaultDriverClass(DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL -> "org.postgresql.Driver";
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case ORACLE -> "oracle.jdbc.OracleDriver";
        };
    }
}
