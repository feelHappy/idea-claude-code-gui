package com.github.claudecodegui.dbmcp.config;

public enum DatabaseMode {
    READ_ONLY(false, false),
    DEV_WRITE(true, true);

    private final boolean writeAllowed;
    private final boolean ddlAllowed;

    DatabaseMode(boolean writeAllowed, boolean ddlAllowed) {
        this.writeAllowed = writeAllowed;
        this.ddlAllowed = ddlAllowed;
    }

    public boolean writeAllowed() {
        return writeAllowed;
    }

    public boolean ddlAllowed() {
        return ddlAllowed;
    }

    public static DatabaseMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return DEV_WRITE;
        }
        return DatabaseMode.valueOf(value.trim().toUpperCase().replace('-', '_'));
    }
}
