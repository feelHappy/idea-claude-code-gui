package com.github.claudecodegui.dbmcp.jdbc;

import java.util.List;
import java.util.Map;

public record ChangeResult(
        int affectedRows,
        List<Object> generatedKeys,
        long executionTimeMs,
        Map<String, Object> metadata) {
}
