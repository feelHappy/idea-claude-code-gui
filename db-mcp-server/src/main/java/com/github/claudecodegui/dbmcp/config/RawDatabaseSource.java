package com.github.claudecodegui.dbmcp.config;

final class RawDatabaseSource {
    String id;
    String dialect;
    String mode;
    String jdbcUrl;
    String driverClass;
    String username;
    String usernameEnv;
    String password;
    String passwordEnv;
    String schema;
    Integer connectTimeoutSec;
    Integer queryTimeoutSec;
    Integer maxRows;
    Integer maxAffectedRows;
    Boolean requireWhereForUpdateDelete;
}
