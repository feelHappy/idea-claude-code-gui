package com.github.claudecodegui.dbmcp.sql;

public final class SqlSafetyGuard {

    private SqlSafetyGuard() {
    }

    public static SqlClassification validateSelect(String sql, SqlSafetyPolicy policy) {
        SqlClassification classification = classifyAndCheck(sql);
        if (classification.operationType() != SqlOperationType.SELECT) {
            throw new IllegalArgumentException("db_select only accepts SELECT statements");
        }
        return classification;
    }

    public static SqlClassification validateChange(String sql, SqlSafetyPolicy policy, boolean confirmDestructive) {
        if (!policy.writeAllowed()) {
            throw new IllegalArgumentException("Write operations are disabled for this database source");
        }
        SqlClassification classification = classifyAndCheck(sql);
        if (classification.operationType() != SqlOperationType.CHANGE) {
            throw new IllegalArgumentException("db_execute_change only accepts INSERT, UPDATE, DELETE, or MERGE");
        }
        if (policy.requireWhereForUpdateDelete() && classification.destructive() && !confirmDestructive) {
            throw new IllegalArgumentException("Potentially destructive change requires confirmDestructive=true");
        }
        return classification;
    }

    public static SqlClassification validateDdl(String sql, SqlSafetyPolicy policy, boolean confirmDestructive) {
        if (!policy.ddlAllowed()) {
            throw new IllegalArgumentException("DDL operations are disabled for this database source");
        }
        SqlClassification classification = classifyAndCheck(sql);
        if (classification.operationType() != SqlOperationType.DDL) {
            throw new IllegalArgumentException("db_execute_ddl only accepts DDL statements");
        }
        if (classification.destructive() && !confirmDestructive) {
            throw new IllegalArgumentException("Destructive DDL requires confirmDestructive=true");
        }
        return classification;
    }

    private static SqlClassification classifyAndCheck(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be blank");
        }
        if (SqlStatementClassifier.containsMultipleStatements(sql)) {
            throw new IllegalArgumentException("Multiple SQL statements are not allowed");
        }
        return SqlStatementClassifier.classify(sql);
    }
}
