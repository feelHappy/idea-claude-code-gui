package com.github.claudecodegui.dbmcp.config;

import com.github.claudecodegui.dbmcp.dialect.DatabaseDialect;

public record DatabaseSource(
        String id,
        DatabaseDialect dialect,
        DatabaseMode mode,
        String jdbcUrl,
        String driverClass,
        String username,
        String usernameEnv,
        String password,
        String passwordEnv,
        String schema,
        int connectTimeoutSec,
        int queryTimeoutSec,
        int maxRows,
        int maxAffectedRows,
        boolean requireWhereForUpdateDelete) {
}
