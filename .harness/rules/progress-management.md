# 进度管理规则

> 确保跨会话的工作连续性。每个需求维护一个进度快照作为 Single Source of Truth。

---

## 进度快照位置

```
_bmad-output/planning/{需求编号}/summary.md
```

---

## 更新时机

- 每个阶段完成后**立即**更新 summary.md
- 方案变更时更新"未解决问题"和"剩余任务"
- 新会话恢复时先读取 summary.md 确认当前位置

---

## 冷启动序列（新会话恢复未完成需求）

```
1. 读取 _bmad-output/planning/{需求编号}/summary.md
2. 定位 "当前状态" 字段 → 找到最后完成的阶段
3. 检查该阶段的产出物是否完整
4. 从下一阶段继续工作，加载对应的知识文件
5. 若 summary.md 不存在 → 这是新需求，从 ① 开始
```

---

## 多需求并行开发

- 每个需求独立维护 summary.md
- 共享文件串行修改，私有文件可并行
- 需要改共享表/接口时，在 summary.md 标注 `[Data Contract Change]`

---

## 进度快照格式

见 `.harness/templates/summary.md` 模板。
