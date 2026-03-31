package com.github.claudecodegui.dbmcp.jdbc;

import com.github.claudecodegui.dbmcp.config.DatabaseMode;
import com.github.claudecodegui.dbmcp.config.DatabaseSource;
import com.github.claudecodegui.dbmcp.dialect.DatabaseDialect;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class JdbcExecutionServiceTest {

    private DatabaseSource source;
    private JdbcExecutionService service;

    @Before
    public void setUp() throws Exception {
        source = new DatabaseSource(
                "h2-dev",
                DatabaseDialect.POSTGRESQL,
                DatabaseMode.DEV_WRITE,
                "jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                "sa",
                null,
                "",
                null,
                "PUBLIC",
                5,
                30,
                200,
                50,
                true
        );
        service = new JdbcExecutionService(new JdbcConnectionFactory());
        service.executeDdl(source, "create table demo(id int primary key, name varchar(50))", false);
    }

    @Test
    public void executeChangeAndSelect() throws Exception {
        ChangeResult change = service.executeChange(
                source,
                "insert into demo(id, name) values(?, ?)",
                List.of(1, "alice"),
                false
        );

        QueryResult query = service.select(source, "select id, name from demo where id = ?", List.of(1), 10);

        assertEquals(1, change.affectedRows());
        assertEquals(1, query.rowCount());
        assertEquals("alice", query.rows().get(0).get(1));
    }
}
