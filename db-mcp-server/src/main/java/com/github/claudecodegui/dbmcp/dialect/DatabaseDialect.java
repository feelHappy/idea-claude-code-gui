package com.github.claudecodegui.dbmcp.dialect;

public enum DatabaseDialect {
    POSTGRESQL,
    MYSQL,
    ORACLE;

    public static DatabaseDialect from(String configuredValue, String jdbcUrl) {
        if (configuredValue != null && !configuredValue.isBlank()) {
            String normalized = configuredValue.trim().toLowerCase();
            return switch (normalized) {
                case "postgres", "postgresql" -> POSTGRESQL;
                case "mysql" -> MYSQL;
                case "oracle" -> ORACLE;
                default -> throw new IllegalArgumentException("Unsupported dialect: " + configuredValue);
            };
        }

        if (jdbcUrl != null) {
            String normalizedUrl = jdbcUrl.toLowerCase();
            if (normalizedUrl.startsWith("jdbc:postgresql:")) {
                return POSTGRESQL;
            }
            if (normalizedUrl.startsWith("jdbc:mysql:")) {
                return MYSQL;
            }
            if (normalizedUrl.startsWith("jdbc:oracle:")) {
                return ORACLE;
            }
        }

        throw new IllegalArgumentException("Unable to infer dialect from configuration");
    }
}
