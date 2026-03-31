package com.github.claudecodegui.dbmcp.jdbc;

import com.github.claudecodegui.dbmcp.config.DatabaseSource;
import com.github.claudecodegui.dbmcp.dialect.DatabaseDialect;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresqlJdbcExecutionServiceIntegrationTest extends AbstractJdbcExecutionServiceIntegrationTest {

    private static PostgreSQLContainer<?> container;

    @BeforeClass
    public static void startContainer() {
        Assume.assumeTrue("Docker is required for PostgreSQL integration tests", isDockerAvailable());
        try {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("dbmcp")
                    .withUsername("test")
                    .withPassword("test");
            container.start();
        } catch (Exception e) {
            Assume.assumeNoException("Failed to start PostgreSQL container", e);
        }
    }

    @AfterClass
    public static void stopContainer() {
        if (container != null) {
            container.stop();
        }
    }

    @Override
    protected DatabaseSource createSource() {
        return buildSource(
                "postgres-it",
                DatabaseDialect.POSTGRESQL,
                container.getJdbcUrl(),
                "org.postgresql.Driver",
                container.getUsername(),
                container.getPassword(),
                "public"
        );
    }

    @Override
    protected String createTableSql(String tableName) {
        return "create table " + tableName + "(id integer primary key, name varchar(50), amount decimal(10,2))";
    }
}
