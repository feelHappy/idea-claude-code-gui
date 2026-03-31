package com.github.claudecodegui.dbmcp.sql;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SqlSafetyGuardTest {

    private static final SqlSafetyPolicy DEV_WRITE = new SqlSafetyPolicy(true, true, 500, 100, true);

    @Test
    public void classifySelectWithComments() {
        SqlClassification classification = SqlStatementClassifier.classify("""
                -- comment
                select * from demo
                """);

        assertEquals(SqlOperationType.SELECT, classification.operationType());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectMultipleStatements() {
        SqlSafetyGuard.validateSelect("select 1; select 2", DEV_WRITE);
    }

    @Test
    public void markDeleteWithoutWhereAsDestructive() {
        SqlClassification classification = SqlStatementClassifier.classify("delete from demo");
        assertTrue(classification.destructive());
    }

    @Test(expected = IllegalArgumentException.class)
    public void requireConfirmationForUnsafeDelete() {
        SqlSafetyGuard.validateChange("delete from demo", DEV_WRITE, false);
    }
}
