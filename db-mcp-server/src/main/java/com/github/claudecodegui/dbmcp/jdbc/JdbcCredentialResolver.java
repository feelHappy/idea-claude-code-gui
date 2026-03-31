package com.github.claudecodegui.dbmcp.jdbc;

import com.github.claudecodegui.dbmcp.config.DatabaseSource;

public final class JdbcCredentialResolver {

    private JdbcCredentialResolver() {
    }

    public static String resolveUsername(DatabaseSource source) {
        return resolveValue(source.username(), source.usernameEnv(), "username", source.id(), false);
    }

    public static String resolvePassword(DatabaseSource source) {
        return resolveValue(source.password(), source.passwordEnv(), "password", source.id(), true);
    }

    private static String resolveValue(
            String inlineValue, String envKey, String fieldName, String sourceId, boolean allowBlankInline) {
        if (inlineValue != null && (allowBlankInline || !inlineValue.isBlank())) {
            return inlineValue;
        }
        if (envKey != null && !envKey.isBlank()) {
            String envValue = System.getenv(envKey);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
            throw new IllegalArgumentException("Missing environment variable " + envKey + " for " + fieldName
                    + " of source " + sourceId);
        }
        throw new IllegalArgumentException("Missing " + fieldName + " for source " + sourceId);
    }
}
