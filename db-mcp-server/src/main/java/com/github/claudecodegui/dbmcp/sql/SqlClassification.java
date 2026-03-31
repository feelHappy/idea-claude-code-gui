package com.github.claudecodegui.dbmcp.sql;

public record SqlClassification(
        SqlOperationType operationType,
        boolean destructive,
        boolean hasWhereClause,
        boolean usesCommonTableExpression) {
}
