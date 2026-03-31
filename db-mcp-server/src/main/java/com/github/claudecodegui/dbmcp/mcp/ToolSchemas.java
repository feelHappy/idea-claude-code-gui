package com.github.claudecodegui.dbmcp.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

public final class ToolSchemas {

    private ToolSchemas() {
    }

    public static McpSchema.JsonSchema emptyObject() {
        return new McpSchema.JsonSchema("object", Map.of(), List.of(), Boolean.FALSE, null, null);
    }

    public static McpSchema.JsonSchema listTables() {
        return new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "schema", stringSchema("Optional schema filter"),
                        "tablePattern", stringSchema("Optional table name pattern"),
                        "includeViews", booleanSchema("Include views in the result")
                ),
                List.of(),
                Boolean.FALSE,
                null,
                null
        );
    }

    public static McpSchema.JsonSchema describeTable() {
        return new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "schema", stringSchema("Optional schema name"),
                        "table", stringSchema("Table name to inspect")
                ),
                List.of("table"),
                Boolean.FALSE,
                null,
                null
        );
    }

    public static McpSchema.JsonSchema select() {
        return new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "sql", stringSchema("Single SELECT statement with optional ? placeholders"),
                        "parameters", arraySchema("Positional bind values"),
                        "maxRows", integerSchema("Optional per-call max rows cap")
                ),
                List.of("sql"),
                Boolean.FALSE,
                null,
                null
        );
    }

    public static McpSchema.JsonSchema executeChange() {
        return new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "sql", stringSchema("Single INSERT, UPDATE, DELETE, or MERGE statement"),
                        "parameters", arraySchema("Positional bind values"),
                        "confirmDestructive", booleanSchema("Required for destructive statements")
                ),
                List.of("sql"),
                Boolean.FALSE,
                null,
                null
        );
    }

    public static McpSchema.JsonSchema executeDdl() {
        return new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "sql", stringSchema("Single CREATE, ALTER, DROP, or TRUNCATE statement"),
                        "confirmDestructive", booleanSchema("Required for DROP or TRUNCATE")
                ),
                List.of("sql"),
                Boolean.FALSE,
                null,
                null
        );
    }

    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> booleanSchema(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> integerSchema(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> arraySchema(String description) {
        return Map.of("type", "array", "description", description);
    }
}
