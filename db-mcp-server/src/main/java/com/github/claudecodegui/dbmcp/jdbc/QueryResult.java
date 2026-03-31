package com.github.claudecodegui.dbmcp.jdbc;

import java.util.List;
import java.util.Map;

public record QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        int rowCount,
        long executionTimeMs,
        Map<String, Object> metadata) {
}
