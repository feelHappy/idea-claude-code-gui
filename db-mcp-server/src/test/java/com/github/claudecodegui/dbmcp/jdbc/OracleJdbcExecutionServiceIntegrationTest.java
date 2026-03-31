package com.github.claudecodegui.dbmcp.jdbc;

import com.github.claudecodegui.dbmcp.config.DatabaseSource;
import com.github.claudecodegui.dbmcp.dialect.DatabaseDialect;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.testcontainers.oracle.OracleContainer;

import java.time.Duration;
import java.util.Locale;

public class OracleJdbcExecutionServiceIntegrationTest extends AbstractJdbcExecutionServiceIntegrationTest {

    private static OracleContainer container;

    @BeforeClass
    public static void startContainer() {
        Assume.assumeTrue("Docker is required for Oracle integration tests", isDockerAvailable());
        try {
            container = new OracleContainer("gvenzl/oracle-free:slim-faststart")
                    .withUsername("test")
                    .withPassword("test")
                    .withStartupTimeout(Duration.ofMinutes(5));
            container.start();
        } catch (Exception e) {
            Assume.assumeNoException("Failed to start Oracle Free container", e);
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
                "oracle-it",
                DatabaseDialect.ORACLE,
                container.getJdbcUrl(),
                "oracle.jdbc.OracleDriver",
                container.getUsername(),
                container.getPassword(),
                container.getUsername().toUpperCase(Locale.ROOT)
        );
    }

    @Override
    protected String createTableSql(String tableName) {
        return "create table " + physicalTableName(tableName)
                + "(id number primary key, name varchar2(50), amount number(10,2))";
    }

    @Override
    protected String physicalTableName(String tableName) {
        return tableName.toUpperCase(Locale.ROOT);
    }
}
