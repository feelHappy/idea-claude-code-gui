# Harness Activity Log — {{PROJECT_NAME}}

> 按时间顺序记录知识体系的所有变更。Agent 在执行知识沉淀操作后追加记录。
> 对标 LLM Wiki 的 log.md 模式——提供知识演化的时间线视图。

---

## 格式

```
## [YYYY-MM-DD] {ACTION} | {target} | {summary}
```

Actions:
- `CREATE` — 新建 playbook 或知识页面
- `UPDATE` — 追加条目到已有 playbook
- `INGEST` — 从会话中提取并沉淀经验
- `LINT` — 执行知识库健康检查

---

## Log

<!-- Agent 在此行下方追加记录，每条一个二级标题 -->
