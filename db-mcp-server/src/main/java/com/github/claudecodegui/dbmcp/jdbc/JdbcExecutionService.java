package com.github.claudecodegui.dbmcp.jdbc;

import com.github.claudecodegui.dbmcp.config.DatabaseSource;
import com.github.claudecodegui.dbmcp.sql.SqlSafetyGuard;
import com.github.claudecodegui.dbmcp.sql.SqlSafetyPolicy;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JdbcExecutionService {

    private final JdbcConnectionFactory connectionFactory;

    public JdbcExecutionService(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public Map<String, Object> ping(DatabaseSource source) throws SQLException, ClassNotFoundException {
        long startedAt = System.currentTimeMillis();
        try (Connection connection = connectionFactory.open(source)) {
            boolean valid = connection.isValid(source.connectTimeoutSec());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", valid);
            payload.put("sourceId", source.id());
            payload.put("dialect", source.dialect().name().toLowerCase());
            payload.put("schema", source.schema());
            payload.put("executionTimeMs", System.currentTimeMillis() - startedAt);
            return payload;
        }
    }

    public QueryResult listTables(DatabaseSource source, String schemaPattern, String tablePattern, boolean includeViews)
            throws SQLException, ClassNotFoundException {
        long startedAt = System.currentTimeMillis();
        try (Connection connection = connectionFactory.open(source)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String[] types = includeViews ? new String[]{"TABLE", "VIEW"} : new String[]{"TABLE"};
            try (ResultSet rs = metaData.getTables(connection.getCatalog(),
                    schemaPattern != null ? schemaPattern : source.schema(), tablePattern, types)) {
                List<String> columns = List.of("tableCatalog", "tableSchema", "tableName", "tableType", "remarks");
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    row.add(rs.getString("TABLE_CAT"));
                    row.add(rs.getString("TABLE_SCHEM"));
                    row.add(rs.getString("TABLE_NAME"));
                    row.add(rs.getString("TABLE_TYPE"));
                    row.add(rs.getString("REMARKS"));
                    rows.add(row);
                }
                return new QueryResult(columns, rows, rows.size(), System.currentTimeMillis() - startedAt, Map.of());
            }
        }
    }

    public QueryResult describeTable(DatabaseSource source, String schema, String table)
            throws SQLException, ClassNotFoundException {
        long startedAt = System.currentTimeMillis();
        try (Connection connection = connectionFactory.open(source)) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(connection.getCatalog(),
                    schema != null ? schema : source.schema(), table, null)) {
                List<String> columns = List.of(
                        "columnName", "typeName", "jdbcType", "size", "decimalDigits", "nullable", "defaultValue");
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    row.add(rs.getString("COLUMN_NAME"));
                    row.add(rs.getString("TYPE_NAME"));
                    row.add(rs.getInt("DATA_TYPE"));
                    row.add(rs.getInt("COLUMN_SIZE"));
                    row.add(rs.getInt("DECIMAL_DIGITS"));
                    row.add(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    row.add(rs.getString("COLUMN_DEF"));
                    rows.add(row);
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("schema", schema != null ? schema : source.schema());
                metadata.put("table", table);
                return new QueryResult(columns, rows, rows.size(), System.currentTimeMillis() - startedAt, metadata);
            }
        }
    }

    public QueryResult select(DatabaseSource source, String sql, List<Object> parameters, Integer requestedMaxRows)
            throws SQLException, ClassNotFoundException {
        SqlSafetyGuard.validateSelect(sql, policyFor(source));

        long startedAt = System.currentTimeMillis();
        try (Connection connection = connectionFactory.open(source);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(source.queryTimeoutSec());
            statement.setMaxRows(Math.min(source.maxRows(), requestedMaxRows == null ? source.maxRows() : requestedMaxRows));
            bindParameters(statement, parameters);

            try (ResultSet resultSet = statement.executeQuery()) {
                QueryResult result = mapQueryResult(resultSet, System.currentTimeMillis() - startedAt, Map.of());
                if (result.rowCount() > source.maxRows()) {
                    throw new IllegalArgumentException("Query exceeded maxRows limit for source " + source.id());
                }
                return result;
            }
        }
    }

    public ChangeResult executeChange(DatabaseSource source, String sql, List<Object> parameters, boolean confirmDestructive)
            throws SQLException, ClassNotFoundException {
        SqlSafetyGuard.validateChange(sql, policyFor(source), confirmDestructive);

        long startedAt = System.currentTimeMillis();
        try (Connection connection = connectionFactory.open(source)) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setQueryTimeout(source.queryTimeoutSec());
                bindParameters(statement, parameters);
                int affectedRows = statement.executeUpdate();
                if (affectedRows > source.maxAffectedRows()) {
                    connection.rollback();
                    throw new IllegalArgumentException("Affected rows exceeded maxAffectedRows limit");
                }
                List<Object> generatedKeys = readGeneratedKeys(statement.getGeneratedKeys());
                connection.commit();
                return new ChangeResult(
                        affectedRows,
                        generatedKeys,
                        System.currentTimeMillis() - startedAt,
                        Map.of("sourceId", source.id())
                );
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public ChangeResult executeDdl(DatabaseSource source, String sql, boolean confirmDestructive)
            throws SQLException, ClassNotFoundException {
        SqlSafetyGuard.validateDdl(sql, policyFor(source), confirmDestructive);

        long startedAt = System.currentTimeMillis();
        try (Connection connection = connectionFactory.open(source);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(source.queryTimeoutSec());
            statement.execute(sql);
            return new ChangeResult(
                    0,
                    List.of(),
                    System.currentTimeMillis() - startedAt,
                    Map.of("sourceId", source.id())
            );
        }
    }

    private SqlSafetyPolicy policyFor(DatabaseSource source) {
        return new SqlSafetyPolicy(
                source.mode().writeAllowed(),
                source.mode().ddlAllowed(),
                source.maxRows(),
                source.maxAffectedRows(),
                source.requireWhereForUpdateDelete()
        );
    }

    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        if (parameters == null) {
            return;
        }
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    private QueryResult mapQueryResult(ResultSet resultSet, long executionTimeMs, Map<String, Object> metadata)
            throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            columns.add(metaData.getColumnLabel(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                row.add(toJsonSafeValue(resultSet.getObject(i)));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, rows.size(), executionTimeMs, metadata);
    }

    private List<Object> readGeneratedKeys(ResultSet resultSet) throws SQLException {
        if (resultSet == null) {
            return List.of();
        }
        List<Object> keys = new ArrayList<>();
        try (resultSet) {
            while (resultSet.next()) {
                keys.add(toJsonSafeValue(resultSet.getObject(1)));
            }
        }
        return keys;
    }

    private Object toJsonSafeValue(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return value.toString();
        }
        if (value instanceof java.sql.Date
                || value instanceof java.sql.Time
                || value instanceof java.sql.Timestamp
                || value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Blob blob) {
            return "[BLOB length=" + blob.length() + "]";
        }
        if (value instanceof Clob clob) {
            return "[CLOB length=" + clob.length() + "]";
        }
        return value;
    }
}
