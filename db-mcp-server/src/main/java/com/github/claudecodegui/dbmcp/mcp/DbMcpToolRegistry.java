package com.github.claudecodegui.dbmcp.mcp;

import com.github.claudecodegui.dbmcp.config.ServerConfiguration;
import com.github.claudecodegui.dbmcp.jdbc.ChangeResult;
import com.github.claudecodegui.dbmcp.jdbc.JdbcConnectionFactory;
import com.github.claudecodegui.dbmcp.jdbc.JdbcExecutionService;
import com.github.claudecodegui.dbmcp.jdbc.QueryResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public final class DbMcpToolRegistry {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Logger LOG = LoggerFactory.getLogger(DbMcpToolRegistry.class);

    private final ServerConfiguration configuration;
    private final JdbcExecutionService executionService;

    public DbMcpToolRegistry(ServerConfiguration configuration) {
        this.configuration = configuration;
        this.executionService = new JdbcExecutionService(new JdbcConnectionFactory());
    }

    public List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(
                pingTool(),
                listTablesTool(),
                describeTableTool(),
                selectTool(),
                executeChangeTool(),
                executeDdlTool()
        );
    }

    private McpServerFeatures.SyncToolSpecification pingTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("db_ping")
                        .description("Test connectivity for the active development database")
                        .inputSchema(ToolSchemas.emptyObject())
                        .build())
                .callHandler((exchange, request) -> execute(() ->
                        toToolResult(executionService.ping(configuration.activeSource()))))
                .build();
    }

    private McpServerFeatures.SyncToolSpecification listTablesTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("db_list_tables")
                        .description("List tables and optionally views for the active development database")
                        .inputSchema(ToolSchemas.listTables())
                        .build())
                .callHandler((exchange, request) -> execute(() -> {
                    Map<String, Object> arguments = request.arguments();
                    QueryResult result = executionService.listTables(
                            configuration.activeSource(),
                            stringArg(arguments, "schema"),
                            stringArg(arguments, "tablePattern"),
                            booleanArg(arguments, "includeViews", false)
                    );
                    return toToolResult(toMap(result));
                }))
                .build();
    }

    private McpServerFeatures.SyncToolSpecification describeTableTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("db_describe_table")
                        .description("Describe columns for a table in the active development database")
                        .inputSchema(ToolSchemas.describeTable())
                        .build())
                .callHandler((exchange, request) -> execute(() -> {
                    Map<String, Object> arguments = request.arguments();
                    QueryResult result = executionService.describeTable(
                            configuration.activeSource(),
                            stringArg(arguments, "schema"),
                            requiredStringArg(arguments, "table")
                    );
                    return toToolResult(toMap(result));
                }))
                .build();
    }

    private McpServerFeatures.SyncToolSpecification selectTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("db_select")
                        .description("Run a single SELECT statement against the active development database")
                        .inputSchema(ToolSchemas.select())
                        .build())
                .callHandler((exchange, request) -> execute(() -> {
                    Map<String, Object> arguments = request.arguments();
                    QueryResult result = executionService.select(
                            configuration.activeSource(),
                            requiredStringArg(arguments, "sql"),
                            listArg(arguments, "parameters"),
                            intArg(arguments, "maxRows")
                    );
                    return toToolResult(toMap(result));
                }))
                .build();
    }

    private McpServerFeatures.SyncToolSpecification executeChangeTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("db_execute_change")
                        .description("Run a single INSERT, UPDATE, DELETE, or MERGE statement against the active development database")
                        .inputSchema(ToolSchemas.executeChange())
                        .build())
                .callHandler((exchange, request) -> execute(() -> {
                    Map<String, Object> arguments = request.arguments();
                    ChangeResult result = executionService.executeChange(
                            configuration.activeSource(),
                            requiredStringArg(arguments, "sql"),
                            listArg(arguments, "parameters"),
                            booleanArg(arguments, "confirmDestructive", false)
                    );
                    return toToolResult(toMap(result));
                }))
                .build();
    }

    private McpServerFeatures.SyncToolSpecification executeDdlTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("db_execute_ddl")
                        .description("Run a single CREATE, ALTER, DROP, or TRUNCATE statement against the active development database")
                        .inputSchema(ToolSchemas.executeDdl())
                        .build())
                .callHandler((exchange, request) -> execute(() -> {
                    Map<String, Object> arguments = request.arguments();
                    ChangeResult result = executionService.executeDdl(
                            configuration.activeSource(),
                            requiredStringArg(arguments, "sql"),
                            booleanArg(arguments, "confirmDestructive", false)
                    );
                    return toToolResult(toMap(result));
                }))
                .build();
    }

    private static CallToolResult toToolResult(Map<String, Object> payload) {
        String text = GSON.toJson(payload);
        return CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(payload)
                .build();
    }

    private static CallToolResult execute(Callable<CallToolResult> callable) {
        try {
            return callable.call();
        } catch (IllegalArgumentException e) {
            return errorResult(e.getMessage(), e);
        } catch (SQLException e) {
            return errorResult("Database error: " + e.getMessage(), e);
        } catch (Exception e) {
            return errorResult("Unexpected server error: " + e.getMessage(), e);
        }
    }

    private static CallToolResult errorResult(String message, Exception exception) {
        LOG.warn("db-mcp tool execution failed: {}", message, exception);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("error", message);
        return CallToolResult.builder()
                .addTextContent(GSON.toJson(payload))
                .structuredContent(payload)
                .isError(true)
                .build();
    }

    private static Map<String, Object> toMap(QueryResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("columns", result.columns());
        payload.put("rows", result.rows());
        payload.put("rowCount", result.rowCount());
        payload.put("executionTimeMs", result.executionTimeMs());
        payload.put("metadata", result.metadata());
        return payload;
    }

    private static Map<String, Object> toMap(ChangeResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("affectedRows", result.affectedRows());
        payload.put("generatedKeys", result.generatedKeys());
        payload.put("executionTimeMs", result.executionTimeMs());
        payload.put("metadata", result.metadata());
        return payload;
    }

    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String requiredStringArg(Map<String, Object> arguments, String key) {
        String value = stringArg(arguments, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?>) {
            return (List<Object>) value;
        }
        throw new IllegalArgumentException("Argument " + key + " must be an array");
    }

    private static boolean booleanArg(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer intArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : Integer.parseInt(String.valueOf(value));
    }
}
