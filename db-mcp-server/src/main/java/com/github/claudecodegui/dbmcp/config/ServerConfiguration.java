package com.github.claudecodegui.dbmcp.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ServerConfiguration {

    private final Map<String, DatabaseSource> sources;
    private final DatabaseSource activeSource;

    public ServerConfiguration(Map<String, DatabaseSource> sources, DatabaseSource activeSource) {
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        this.activeSource = activeSource;
    }

    public Map<String, DatabaseSource> sources() {
        return sources;
    }

    public DatabaseSource activeSource() {
        return activeSource;
    }
}
