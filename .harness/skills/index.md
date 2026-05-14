# Skills 索引 — idea-claude-code-gui

> 本文件是所有可用 Skill 的中央索引。Agent 根据当前阶段和任务类型，按此表加载对应 Skill。
> **本文件始终加载**（L1 常驻层），具体 Skill 内容按需读取。

---

## BMAD 流程 Skills

| Skill | 触发场景 | 产出 |
|-------|---------|------|
| `bmad-create-prd` | 需求分析阶段（①） | `prd.md` |
| `bmad-create-architecture` | 需求分析阶段（①） | `design-spec.md` |
| `bmad-create-epics-and-stories` | 任务拆解阶段（③） | `stories/story-*.md` |
| `bmad-check-implementation-readiness` | 执行前检查（④） | 通过/不通过 |
| `bmad-dev-story` | 编码实现阶段（⑤） | 代码实现 |
| `bmad-code-review` | 代码审查阶段（②⑥） | 审查报告 |

---

## 编码规范 Skills

| Skill | 触发场景 | 说明 |
|-------|---------|------|
| *(按项目技术栈填充)* | — | — |

---

## MCP 插件 Skills

| Skill | 触发场景 |
|-------|---------|
| `context7` | 需要最新 API/框架文档 |
| `code-review` | PR review、代码质量 |

---

## GitNexus Skills（如已安装）

| Skill | 触发场景 |
|-------|---------|
| `gitnexus-exploring` | "How does X work?" |
| `gitnexus-impact-analysis` | "What breaks if I change X?" |
| `gitnexus-debugging` | "Why is X failing?" |
| `gitnexus-refactoring` | Rename/extract/split/refactor |
