# db-mcp-server

Standalone MCP server for development-database access across PostgreSQL, MySQL, and Oracle.

Current scope:

- STDIO MCP server for Claude Code / Codex style clients
- JDBC-based execution layer
- Development-safe SQL guardrails
- Generic metadata access via `DatabaseMetaData`

Initial tools:

- `db_ping`
- `db_list_tables`
- `db_describe_table`
- `db_select`
- `db_execute_change`
- `db_execute_ddl`

Runtime notes:

- Requires Java 17+
- Bundles runtime JDBC drivers for PostgreSQL, MySQL, and Oracle
- `driverClass` is optional; the server infers a default from `dialect`

Example launcher:

```bash
java -jar db-mcp-server.jar --config /path/to/db-mcp.json --source dev-pg
```

Example config:

```json
{
  "defaultSource": "dev-pg",
  "sources": [
    {
      "id": "dev-pg",
      "dialect": "postgresql",
      "mode": "dev_write",
      "jdbcUrl": "jdbc:postgresql://127.0.0.1:5432/app_dev",
      "usernameEnv": "APP_DEV_DB_USER",
      "passwordEnv": "APP_DEV_DB_PASS",
      "schema": "public",
      "connectTimeoutSec": 5,
      "queryTimeoutSec": 30,
      "maxRows": 500,
      "maxAffectedRows": 5000,
      "requireWhereForUpdateDelete": true
    }
  ]
}
```
