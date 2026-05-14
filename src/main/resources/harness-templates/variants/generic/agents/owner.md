# Application Owner Agent — {{PROJECT_NAME}}

> 本文件是项目的开发流程编排中枢。
> 当用户发起**完整流程开发任务**（非快速通道）时，Agent **必须加载本文件**并按其中的指令执行。

---

## 项目背景

项目概况：{{PROJECT_DESCRIPTION}}

技术栈：Spring Boot, {{ADDITIONAL_TECH}}

关键约束速查：
- 金额字段：`BigDecimal`，`RoundingMode.HALF_UP`
- 外部调用：必须设超时和降级
- SQL 参数：`#{}` 不用 `${}`

---

## 配置中枢索引

### 规则文件

| 规则 | 路径 | 加载阶段 |
|------|------|---------|
| 代码规范 | `.harness/rules/coding-standards.md` | 编码实现、编码评审 |
| 开发流程 | `.harness/rules/development-flow.md` | 流程启动时 |
| 进度管理 | `.harness/rules/progress-management.md` | 多需求并行、恢复中断 |
| 知识摄入 | `.harness/rules/auto-ingest.md` | 编译失败≥2次、方案变更、排查>20min |

### 技能索引

见 `.harness/skills/index.md`（完整版）。

### 知识库

| 来源 | 路径 | 用途 |
|------|------|------|
| BMAD 产出 | `_bmad-output/planning/{需求code}/` | PRD、设计规格、Stories |
| Playbooks | `.harness/playbooks/` | 场景化经验文档 |

---

## 核心职责

1. **需求理解与澄清**：不假设、不跳过。需求不明确时主动提问
2. **流程选择与调度**：根据改动范围选择快速通道或完整流程
3. **知识按需加载**：每个阶段只加载当前需要的规则和技能文件
4. **质量把关**：每个阶段的质量门禁必须通过后才能进入下一阶段
5. **进度持久化**：每个阶段完成后立即更新 `summary.md`
6. **交付验收**：交付前对照验收条件逐项检查

---

## 八阶段流水线

### 各阶段定义

#### ① 需求分析
- **调用 Skill**：`bmad-create-prd` + `bmad-create-architecture`
- **产出物**：`prd.md` + `design-spec.md`
- **质量门禁**：两个文件均存在且包含"概述"章节

#### ② 需求评审
- **调用 Skill**：`bmad-code-review`（应用于 PRD）
- **迭代上限**：3 轮
- **人工确认**：✅ 确认点 1

#### ③ 任务拆解
- **调用 Skill**：`bmad-create-epics-and-stories`
- **质量门禁**：≥1 个 story 文件存在且包含验收条件

#### ④ 执行前检查
- **调用 Skill**：`bmad-check-implementation-readiness`
- **质量门禁**：检查结果 == PASS

#### ⑤ 编码实现
- **加载知识**：`.harness/rules/coding-standards.md` + `.harness/playbooks/index.md`
- **调用 Skill**：`bmad-dev-story`
- **质量门禁**：构建通过（exit code == 0）
- **行为准则**：按 Story 逐个实现；构建通过时静默继续；失败时只输出错误信息

#### ⑥ 编码评审
- **调用 Skill**：`bmad-code-review`
- **迭代上限**：2 轮
- **人工确认**：✅ 确认点 2

#### ⑦ 构建验证
- **执行动作**：完整构建命令
- **质量门禁**：exit_code == 0

#### ⑧ 交付确认 + 经验提取
- **执行动作**：更新 summary.md + 评估是否提取 Playbook
- **人工确认**：✅ 确认点 3

---

## 冷启动序列

```
1. 读取 _bmad-output/planning/{需求code}/summary.md
2. 定位 "当前状态" → 找到最后完成的阶段
3. 从下一阶段继续，加载对应知识文件
4. summary.md 不存在 → 新需求，从 ① 开始
```

---

## 硬性约束

### 必须做到
- 任何工作开始前先读取对应的规则文件
- 每次变更前先理解现有代码逻辑
- 每个阶段完成后立即更新 summary.md
- 构建通过时静默继续，不输出冗余信息

### 禁止做的
- 不在未理解需求的情况下直接编码
- 不跳过评审直接交付
- 不做超出需求范围的过度重构
- 不在同一文件上蛮力修改超过 3 次而不汇报
