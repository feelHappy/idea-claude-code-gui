package com.github.claudecodegui.dbmcp.jdbc;

import com.github.claudecodegui.dbmcp.config.DatabaseMode;
import com.github.claudecodegui.dbmcp.config.DatabaseSource;
import com.github.claudecodegui.dbmcp.dialect.DatabaseDialect;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public abstract class AbstractJdbcExecutionServiceIntegrationTest {

    protected JdbcExecutionService service;
    protected DatabaseSource source;

    @Before
    public void setUp() {
        service = new JdbcExecutionService(new JdbcConnectionFactory());
        source = createSource();
    }

    @Test
    public void pingListTablesAndDescribeTable() throws Exception {
        String tableName = logicalTableName();
        service.executeDdl(source, createTableSql(tableName), false);

        Map<String, Object> ping = service.ping(source);
        QueryResult tables = service.listTables(source, null, physicalTableName(tableName), false);
        QueryResult describe = service.describeTable(source, source.schema(), physicalTableName(tableName));

        assertTrue((Boolean) ping.get("ok"));
        assertEquals(source.id(), ping.get("sourceId"));
        assertEquals(source.dialect().name().toLowerCase(Locale.ROOT), ping.get("dialect"));
        assertEquals(1, tables.rowCount());
        assertEquals(3, describe.rowCount());
    }

    @Test
    public void executeInsertUpdateDeleteLifecycle() throws Exception {
        String tableName = logicalTableName();
        service.executeDdl(source, createTableSql(tableName), false);

        ChangeResult insert = service.executeChange(
                source,
                "insert into " + physicalTableName(tableName) + "(id, name, amount) values(?, ?, ?)",
                List.of(1, "alice", BigDecimal.valueOf(12.34)),
                false
        );
        QueryResult afterInsert = service.select(
                source,
                "select id, name, amount from " + physicalTableName(tableName) + " where id = ?",
                List.of(1),
                10
        );
        ChangeResult update = service.executeChange(
                source,
                "update " + physicalTableName(tableName) + " set name = ?, amount = ? where id = ?",
                List.of("bob", BigDecimal.valueOf(22.50), 1),
                false
        );
        QueryResult afterUpdate = service.select(
                source,
                "select id, name, amount from " + physicalTableName(tableName) + " where id = ?",
                List.of(1),
                10
        );
        ChangeResult delete = service.executeChange(
                source,
                "delete from " + physicalTableName(tableName) + " where id = ?",
                List.of(1),
                false
        );
        QueryResult afterDelete = service.select(
                source,
                "select id, name, amount from " + physicalTableName(tableName) + " where id = ?",
                List.of(1),
                10
        );

        assertEquals(1, insert.affectedRows());
        assertTrue(insert.generatedKeys().isEmpty());
        assertEquals(1, afterInsert.rowCount());
        assertEquals("alice", afterInsert.rows().get(0).get(1));
        assertEquals(0, new BigDecimal(afterInsert.rows().get(0).get(2).toString())
                .compareTo(BigDecimal.valueOf(12.34)));

        assertEquals(1, update.affectedRows());
        assertEquals(1, afterUpdate.rowCount());
        assertEquals("bob", afterUpdate.rows().get(0).get(1));
        assertEquals(0, new BigDecimal(afterUpdate.rows().get(0).get(2).toString())
                .compareTo(BigDecimal.valueOf(22.50)));

        assertEquals(1, delete.affectedRows());
        assertEquals(0, afterDelete.rowCount());
        assertFalse(afterDelete.rows().iterator().hasNext());
    }

    protected abstract DatabaseSource createSource();

    protected abstract String createTableSql(String tableName);

    protected String physicalTableName(String tableName) {
        return tableName;
    }

    private String logicalTableName() {
        return "demo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
    }

    protected DatabaseSource buildSource(
            String id,
            DatabaseDialect dialect,
            String jdbcUrl,
            String driverClass,
            String username,
            String password,
            String schema) {
        return new DatabaseSource(
                id,
                dialect,
                DatabaseMode.DEV_WRITE,
                jdbcUrl,
                driverClass,
                username,
                null,
                password,
                null,
                schema,
                5,
                30,
                200,
                50,
                true
        );
    }

    protected static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
