package com.github.claudecodegui.dbmcp.jdbc;

import com.github.claudecodegui.dbmcp.config.DatabaseSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class JdbcConnectionFactory {

    public Connection open(DatabaseSource source) throws SQLException, ClassNotFoundException {
        if (source.driverClass() != null) {
            Class.forName(source.driverClass());
        }

        Properties properties = new Properties();
        properties.setProperty("user", JdbcCredentialResolver.resolveUsername(source));
        properties.setProperty("password", JdbcCredentialResolver.resolvePassword(source));
        properties.setProperty("loginTimeout", String.valueOf(source.connectTimeoutSec()));

        DriverManager.setLoginTimeout(source.connectTimeoutSec());
        return DriverManager.getConnection(source.jdbcUrl(), properties);
    }
}
