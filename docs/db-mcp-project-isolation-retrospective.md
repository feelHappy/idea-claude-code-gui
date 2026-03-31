# DB MCP Project Isolation Retrospective

## Background

The plugin added project database binding so Claude Code or Codex can operate directly on development databases through the bundled `db-mcp-server`.

In real use, two IDEA projects were opened at the same time:

- Project A used Oracle
- Project B used PostgreSQL

When Codex generated tables or executed database operations, it incorrectly routed requests to the Oracle database.

## Root Cause

The problem was not JDBC configuration corruption and not `sourceId` collision.

The real issue was a scope mismatch:

- Claude database MCP registration was already project-scoped
- Codex database MCP registration was written into global `~/.codex/config.toml`
- When multiple projects were open, Codex could see multiple project DB MCP servers at once
- Codex then selected an available DB MCP server globally, which could be the Oracle one

This means the failure happened before `sourceId` selection.

Selection order was effectively:

1. Codex chooses an MCP server
2. The chosen server uses `--source` / `sourceId` internally

If step 1 is wrong, step 2 cannot recover.

## Why `sourceId` Alone Is Not Enough

Using different `sourceId` values per project is necessary, but it does not solve cross-project isolation.

`sourceId` only distinguishes multiple data sources inside one server context.
It does not prevent Codex from choosing the wrong server when several project DB servers are globally visible.

## Final Fix

The fix was implemented at Codex runtime startup, not at save time.

### Strategy

- Keep global Codex auth and general settings usable
- Build a project-scoped `CODEX_HOME` view before launching Codex
- Copy required shared artifacts from global `~/.codex`
- Filter `mcp_servers` so only the current project's generated DB MCP server remains visible

### Result

For each Codex launch:

- current project path is known from `cwd`
- plugin prepares a project-scoped Codex home under `.codemoss/codex-project-home/...`
- filtered `config.toml` is written there
- `CODEX_HOME` is pointed to that project-scoped directory

This keeps:

- auth
- skills
- memories
- general Codex settings

while isolating project DB MCP visibility.

## Files Changed

- `src/main/java/com/github/claudecodegui/bridge/EnvironmentConfigurator.java`
- `src/main/java/com/github/claudecodegui/provider/codex/CodexSDKBridge.java`
- `src/main/java/com/github/claudecodegui/settings/CodexSettingsManager.java`
- `src/main/java/com/github/claudecodegui/settings/CodexProjectHomeManager.java`
- `src/test/java/com/github/claudecodegui/settings/CodexProjectHomeManagerTest.java`

## Problems Encountered

### 1. Scope design mistake

The first implementation assumed unique `serverId` plus unique `sourceId` was enough.
It was not enough because Codex MCP discovery was global.

### 2. Easy to misdiagnose as JDBC or schema issue

The symptom looked like configuration contamination, but the actual issue was MCP server visibility and runtime selection.

### 3. Runtime isolation must not break Codex capabilities

If `CODEX_HOME` is switched blindly, Codex may lose:

- auth
- skills
- memories
- other user settings

So the fix had to preserve shared artifacts while only filtering DB MCP visibility.

### 4. Local build environment noise

Validation was temporarily blocked by local Java toolchain issues:

- shell default Java was Java 8
- project build required Java 17+
- `db-mcp-server` submodule toolchain discovery also needed explicit JDK handling

This was a local environment issue, not a regression from the fix.

## What To Avoid Next Time

### 1. Do not mix project-scoped and global-scoped MCP registration for the same feature

If one provider is project-scoped and another is global, behavior will drift and multi-project use will become unsafe.

### 2. Do not rely on `sourceId` to solve server routing

`sourceId` solves in-server routing only.
Project isolation must be solved before tool discovery.

### 3. Design for multi-project concurrency from day one

For anything that can mutate a development database:

- project visibility must be isolated
- defaults must prefer current project context
- cross-project leakage must be treated as a safety bug

### 4. Validate with two live projects, not one

Single-project testing would not expose this class of bug.
Multi-project validation should be part of the acceptance checklist.

## Recommended Checklist For Future DB/MCP Changes

1. Check whether config is global, project-scoped, or session-scoped.
2. Verify how tool discovery happens before execution.
3. Test two projects open at the same time with different databases.
4. Test both Claude and Codex, even if only one provider was changed.
5. Confirm auth, skills, memories, and plugins still work after any config isolation change.
6. Confirm destructive operations still only target the current development database.

## Practical Rule

If the feature can create tables, alter tables, insert data, or run updates in a development database, project isolation is not optional.

The safe rule is:

`current project decides visible DB MCP server set`

not:

`all configured DB MCP servers are globally visible and the model figures it out`
