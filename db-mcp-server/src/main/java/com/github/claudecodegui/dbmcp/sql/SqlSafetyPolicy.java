package com.github.claudecodegui.dbmcp.sql;

public record SqlSafetyPolicy(
        boolean writeAllowed,
        boolean ddlAllowed,
        int maxRows,
        int maxAffectedRows,
        boolean requireWhereForUpdateDelete) {
}
