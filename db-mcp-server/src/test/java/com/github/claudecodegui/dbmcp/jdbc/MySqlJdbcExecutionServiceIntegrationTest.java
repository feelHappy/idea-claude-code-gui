package com.github.claudecodegui.dbmcp.jdbc;

import com.github.claudecodegui.dbmcp.config.DatabaseSource;
import com.github.claudecodegui.dbmcp.dialect.DatabaseDialect;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.testcontainers.containers.MySQLContainer;

public class MySqlJdbcExecutionServiceIntegrationTest extends AbstractJdbcExecutionServiceIntegrationTest {

    private static MySQLContainer<?> container;

    @BeforeClass
    public static void startContainer() {
        Assume.assumeTrue("Docker is required for MySQL integration tests", isDockerAvailable());
        try {
            container = new MySQLContainer<>("mysql:8.0.36")
                    .withDatabaseName("dbmcp")
                    .withUsername("test")
                    .withPassword("test");
            container.start();
        } catch (Exception e) {
            Assume.assumeNoException("Failed to start MySQL container", e);
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
                "mysql-it",
                DatabaseDialect.MYSQL,
                container.getJdbcUrl(),
                "com.mysql.cj.jdbc.Driver",
                container.getUsername(),
                container.getPassword(),
                null
        );
    }

    @Override
    protected String createTableSql(String tableName) {
        return "create table " + tableName + "(id integer primary key, name varchar(50), amount decimal(10,2))";
    }
}
