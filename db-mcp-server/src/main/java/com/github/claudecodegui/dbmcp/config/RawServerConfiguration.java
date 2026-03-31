package com.github.claudecodegui.dbmcp.config;

import java.util.ArrayList;
import java.util.List;

final class RawServerConfiguration {
    String defaultSource;
    List<RawDatabaseSource> sources = new ArrayList<>();
}
