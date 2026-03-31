package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;

import java.util.Locale;

final class ProjectDatabaseBinding {

    static final String DEFAULT_SOURCE_ID = "main";
    static final String DEFAULT_DIALECT = "postgresql";
    static final String DEFAULT_MODE = "dev-write";
    static final int DEFAULT_MAX_ROWS = 200;
    static final int DEFAULT_MAX_AFFECTED_ROWS = 50;

    private final boolean enabled;
    private final String dbMcpServerPath;
    private final String sourceId;
    private final String dialect;
    private final String mode;
    private final String jdbcUrl;
    private final String schema;
    private final String username;
    private final String password;
    private final String usernameEnv;
    private final String passwordEnv;
    private final int maxRows;
    private final int maxAffectedRows;
    private final boolean requireWhereForUpdateDelete;

    private ProjectDatabaseBinding(
            boolean enabled,
            String dbMcpServerPath,
            String sourceId,
            String dialect,
            String mode,
            String jdbcUrl,
            String schema,
            String username,
            String password,
            String usernameEnv,
            String passwordEnv,
            int maxRows,
            int maxAffectedRows,
            boolean requireWhereForUpdateDelete) {
        this.enabled = enabled;
        this.dbMcpServerPath = dbMcpServerPath;
        this.sourceId = sourceId;
        this.dialect = dialect;
        this.mode = mode;
        this.jdbcUrl = jdbcUrl;
        this.schema = schema;
        this.username = username;
        this.password = password;
        this.usernameEnv = usernameEnv;
        this.passwordEnv = passwordEnv;
        this.maxRows = maxRows;
        this.maxAffectedRows = maxAffectedRows;
        this.requireWhereForUpdateDelete = requireWhereForUpdateDelete;
    }

    static ProjectDatabaseBinding defaults() {
        return new ProjectDatabaseBinding(
                false,
                "",
                DEFAULT_SOURCE_ID,
                DEFAULT_DIALECT,
                DEFAULT_MODE,
                "",
                "",
                "",
                "",
                "",
                "",
                DEFAULT_MAX_ROWS,
                DEFAULT_MAX_AFFECTED_ROWS,
                true
        );
    }

    static ProjectDatabaseBinding fromJson(JsonObject json, ProjectDatabaseBinding fallback) {
        ProjectDatabaseBinding base = fallback == null ? defaults() : fallback;
        if (json == null) {
            return base;
        }

        return new ProjectDatabaseBinding(
                getBoolean(json, "enabled", base.enabled()),
                getString(json, "dbMcpServerPath", base.dbMcpServerPath()),
                normalizeSourceId(getString(json, "sourceId", base.sourceId())),
                normalizeDialect(getString(json, "dialect", base.dialect())),
                normalizeMode(getString(json, "mode", base.mode())),
                trimToEmpty(getString(json, "jdbcUrl", base.jdbcUrl())),
                trimToEmpty(getString(json, "schema", base.schema())),
                trimToEmpty(getString(json, "username", base.username())),
                getPassword(json, base.password()),
                trimToEmpty(getString(json, "usernameEnv", base.usernameEnv())),
                trimToEmpty(getString(json, "passwordEnv", base.passwordEnv())),
                getInt(json, "maxRows", base.maxRows()),
                getInt(json, "maxAffectedRows", base.maxAffectedRows()),
                getBoolean(json, "requireWhereForUpdateDelete", base.requireWhereForUpdateDelete())
        );
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("dbMcpServerPath", dbMcpServerPath);
        json.addProperty("sourceId", sourceId);
        json.addProperty("dialect", dialect);
        json.addProperty("mode", mode);
        json.addProperty("jdbcUrl", jdbcUrl);
        json.addProperty("schema", schema);
        json.addProperty("username", username);
        json.addProperty("password", password);
        json.addProperty("usernameEnv", usernameEnv);
        json.addProperty("passwordEnv", passwordEnv);
        json.addProperty("maxRows", maxRows);
        json.addProperty("maxAffectedRows", maxAffectedRows);
        json.addProperty("requireWhereForUpdateDelete", requireWhereForUpdateDelete);
        return json;
    }

    boolean enabled() {
        return enabled;
    }

    String dbMcpServerPath() {
        return dbMcpServerPath;
    }

    String sourceId() {
        return sourceId;
    }

    String dialect() {
        return dialect;
    }

    String mode() {
        return mode;
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    String schema() {
        return schema;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    String usernameEnv() {
        return usernameEnv;
    }

    String passwordEnv() {
        return passwordEnv;
    }

    int maxRows() {
        return maxRows;
    }

    int maxAffectedRows() {
        return maxAffectedRows;
    }

    boolean requireWhereForUpdateDelete() {
        return requireWhereForUpdateDelete;
    }

    private static boolean getBoolean(JsonObject json, String key, boolean defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : defaultValue;
    }

    private static int getInt(JsonObject json, String key, int defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : defaultValue;
    }

    private static String getString(JsonObject json, String key, String defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : defaultValue;
    }

    private static String getPassword(JsonObject json, String defaultValue) {
        if (!json.has("password") || json.get("password").isJsonNull()) {
            return defaultValue;
        }
        return json.get("password").getAsString();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeSourceId(String value) {
        String normalized = trimToEmpty(value);
        return normalized.isEmpty() ? DEFAULT_SOURCE_ID : normalized;
    }

    private static String normalizeDialect(String value) {
        String normalized = trimToEmpty(value).toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? DEFAULT_DIALECT : normalized;
    }

    private static String normalizeMode(String value) {
        String normalized = trimToEmpty(value).toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.isEmpty() ? DEFAULT_MODE : normalized;
    }
}
