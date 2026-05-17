# Harness Knowledge System — {{PROJECT_NAME}}

> Installed by CC AI Toolkit. This file provides structured context for Codex agents.

## Cold-Start Sequence (MANDATORY — execute before any work)

1. Check `_bmad-output/planning/` for existing progress files
2. If any `progress.md` has `[下一步]` entries → resume from that point
3. New task → immediately create `_bmad-output/planning/{id}/progress.md` with `## [YYYY-MM-DD] goal`
4. Read `.harness/rules/development-flow.md` → decide fast-track or full pipeline

**Skipping this sequence is a violation.**

## Progress Tracking (ENFORCED — all tasks including single-file bug fixes)

File: `_bmad-output/planning/{id}/progress.md`

| Trigger | Action |
|---------|--------|
| Session start | Append `## [YYYY-MM-DD] goal` |
| Logic unit done | Append `- [完成] what (which files)` |
| Blocked | Append `- [阻塞] problem and resolution` |
| Session end | Append `- [下一步] remaining` + changed file list |

Before ending a session: verify progress.md has been updated. If not, write it now.

## Rules (load by context)

| Rule | Path | When to load |
|------|------|--------------|
| Coding Standards | `.harness/rules/coding-standards.md` | Before writing code |
| Development Flow | `.harness/rules/development-flow.md` | Task start |
| Progress Management | `.harness/rules/progress-management.md` | Every task |
| Knowledge Ingest | `.harness/rules/auto-ingest.md` | Build fail ≥2x, plan change, debug >20min |

## Experience Library (Playbooks)

- **Index**: `.harness/playbooks/index.md` — check before coding, match scenarios to existing playbooks
- **Template**: `.harness/templates/playbook-template.md` — for extracting new experience

## Full Pipeline Orchestration

For multi-file features or interface changes, load `.harness/agents/owner.md` (8-stage pipeline with 3 human checkpoints).

## Skills

See `.harness/skills/index.md` for the complete skill registry.

## Behavioral Constraints

- Build passes → continue silently. Build fails → output error only, fix immediately.
- Same file modified 3+ times without resolution → stop, report blocker, propose alternative.
- Never code without understanding the requirement first.
- After task completion → evaluate knowledge ingest per `.harness/rules/auto-ingest.md`.
- Append activity to `.harness/log.md` when creating or updating playbooks.
