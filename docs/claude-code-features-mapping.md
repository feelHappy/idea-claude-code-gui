# Claude Code 功能全景 × 插件集成映射

> 目标：让用户通过插件 GUI 快速上手 Claude Code 的核心能力，降低英文文档的认知门槛。

---

## 一、Claude Code 能力全景（2025.05 最新）

### 核心能力矩阵

| 能力域 | 功能 | 一句话说明 | 插件现状 | 优先级 |
|--------|------|-----------|---------|--------|
| **对话** | 流式对话 | 实时逐 token 输出 | ✅ 已集成 | — |
| **对话** | 文件附件 | @file 引用代码/图片/PDF | ✅ 已集成 | — |
| **对话** | 扩展思考 | 控制推理深度(low/medium/high) | ✅ 已集成 | — |
| **对话** | 消息重写 | 重新生成上一条回复 | ✅ 已集成 | — |
| **规划** | Plan Mode | 先规划再执行，用户审批后才动代码 | ✅ 已集成 | — |
| **规划** | TodoWrite | 任务拆解和进度追踪 | ⚠️ 后端有，UI 未暴露 | P1 |
| **技能** | Skills(/命令) | 预定义工作流一键触发 | ✅ 已集成 | — |
| **技能** | 自定义 Skill | 用户自建 .claude/skills/ | ✅ 已集成 | — |
| **工具** | MCP 服务器 | 外部工具协议集成 | ✅ 已集成 | — |
| **工具** | 工具权限控制 | 分级审批(自动/确认/拒绝) | ✅ 已集成 | — |
| **记忆** | Memory 系统 | 跨会话持久化用户偏好/项目知识 | ❌ 未集成 | P1 |
| **记忆** | CLAUDE.md | 项目级指令文件 | ✅ 已集成(Harness) | — |
| **自动化** | Hooks | 工具调用前后自动执行脚本 | ❌ 未集成 | P2 |
| **自动化** | /loop | 定时循环执行任务 | ❌ 未集成 | P2 |
| **自动化** | Cron 调度 | 定时触发 prompt | ❌ 未集成 | P3 |
| **并行** | Subagents | 派生子 agent 并行处理 | ⚠️ 内部用，未暴露 | P2 |
| **并行** | Worktree | 隔离 git 工作树并行开发 | ❌ 未集成 | P3 |
| **上下文** | 自动压缩 | 长对话自动摘要保持连续性 | ✅ 内部处理 | — |
| **上下文** | 快速模式 | Opus 加速输出(不降级模型) | ⚠️ 未暴露切换 | P2 |
| **审查** | /review | PR 代码审查 | ✅ Skill 已有 | — |
| **审查** | /security-review | 安全审查 | ✅ Skill 已有 | — |
| **初始化** | /init | 生成 CLAUDE.md | ✅ Skill 已有 | — |

---

## 二、用户痛点分析

| 痛点 | 表现 | 根因 |
|------|------|------|
| 不知道有什么功能 | 只用基础对话，不知道 /review、plan mode 等存在 | 功能散落在英文 changelog，无引导 |
| 不知道什么时候该用 | 知道有 skill 但不知道当前场景该用哪个 | 缺少场景→功能的映射 |
| 配置门槛高 | MCP、Hooks、Memory 需要手写 JSON | 无 GUI 配置界面 |
| 工作流断裂 | 做完 impact 分析后不知道下一步该干嘛 | 功能是散装的，没有串联 |
| 结果不可追溯 | 上次让 Claude 分析的结论找不到了 | Memory 未集成，知识不持久 |

---

## 三、插件集成方案（按优先级）

### P1：降低认知门槛 — 场景化引导

#### 3.1 智能命令面板（Quick Actions）

**设计思路**：不是列出所有命令让用户选，而是根据当前上下文推荐最相关的 3-5 个动作。

```
触发条件 → 推荐动作
─────────────────────────────────
打开了一个函数 → "分析影响范围" / "重构" / "写测试"
git diff 有内容 → "审查变更" / "生成 commit message" / "检测影响"
新建项目 → "初始化 CLAUDE.md" / "生成项目文档"
对话超过 10 轮 → "整理为记忆" / "生成摘要"
刚 pull 了代码 → "审查 PR" / "理解变更"
构建失败 → "排查错误" / "查看相关流程"
```

**实现方式**：
- 前端：ChatInputBox 上方增加 `<QuickActions>` 组件，横向滚动 chip 列表
- 后端：`QuickActionService.java` 监听 IDE 事件（文件打开、git 状态变化、构建结果）
- 每个 chip 点击后注入对应的 skill prompt + 当前上下文

#### 3.2 Memory 系统可视化

**用户价值**：让 Claude 记住"我是后端开发"、"这个项目用 Spring Boot"、"不要给我加注释"。

```
UI 入口：设置页 → Memory 标签
├── 用户偏好（user type）：角色、风格、禁忌
├── 项目知识（project type）：架构决策、进行中的工作
├── 反馈记录（feedback type）：纠正过的行为
└── 外部引用（reference type）：文档链接、看板地址
```

**实现方式**：
- 读取 `~/.claude/projects/<project>/memory/` 目录
- 提供 CRUD 界面（列表 + 编辑器）
- 对话中自动注入相关 memory 作为 system context
- 支持手动触发"记住这个"

#### 3.3 TodoWrite 任务面板

**用户价值**：复杂任务时，让用户看到 Claude 的执行计划和进度。

```
UI 位置：对话区右侧可折叠面板
├── 当前任务列表（pending / in_progress / completed）
├── 进度条
└── 点击任务可跳转到对应的对话位置
```

---

### P2：提升效率 — 工作流串联

#### 3.4 工作流模板（Workflow Templates）

**设计思路**：把散装功能串成完整流程，用户选一个场景，自动走完多步。

| 工作流 | 步骤 | 涉及功能 |
|--------|------|----------|
| 安全重构 | impact → 确认 → edit → detect_changes → test | GitNexus + Edit + Bash |
| PR 提交 | detect_changes → review → commit → push | GitNexus + Git + Review |
| Bug 修复 | trace error → find root cause → fix → test | Debug + Edit + Test |
| 新功能开发 | plan → implement → test → review | Plan Mode + Edit + Test + Review |
| 代码理解 | query flows → context → read source | GitNexus + Read |

**实现方式**：
- 定义 workflow JSON schema（步骤、条件、分支）
- `WorkflowEngine.java` 按步骤驱动，每步结果决定下一步
- 前端显示当前步骤 + 进度 + 可跳过/回退

#### 3.5 Hooks 可视化配置

**用户价值**：不用手写 JSON，GUI 配置"保存文件时自动 lint"、"提交前自动 review"。

```
UI 入口：设置页 → Hooks 标签
├── PreToolUse hooks（工具调用前）
├── PostToolUse hooks（工具调用后）
├── 预设模板：
│   ├── 保存时自动格式化
│   ├── 提交前安全检查
│   ├── 编辑后自动运行测试
│   └── 自定义...
└── Hook 执行日志
```

#### 3.6 Subagent 可视化

**用户价值**：看到 Claude 派生了哪些子任务在并行处理。

```
UI 位置：对话区顶部状态栏
├── 主 agent 状态
├── 子 agent 列表（名称 + 状态 + 耗时）
└── 点击展开子 agent 的输出
```

---

### P3：高级能力 — 自动化与并行

#### 3.7 /loop 定时任务面板

- GUI 配置循环间隔和触发 prompt
- 显示历次执行结果
- 一键暂停/恢复

#### 3.8 Worktree 并行开发

- 可视化当前活跃的 worktree
- 一键创建/切换/合并
- 与 IDE 的 VCS 面板联动

---

## 四、用户上手路径设计

### 4.1 新手引导（Onboarding Flow）

```
第一次打开插件
  ↓
Step 1: 选择你的角色
  □ 后端开发  □ 前端开发  □ 全栈  □ 架构师  □ 其他
  → 自动写入 Memory（user type）
  ↓
Step 2: 检测项目环境
  自动识别：语言、框架、构建工具、测试框架
  → 自动生成 CLAUDE.md 建议
  ↓
Step 3: 推荐首次体验
  "试试让 Claude 理解你的项目" → 触发 /init 或 GitNexus 索引
  ↓
Step 4: 展示 Quick Actions
  "这些是根据你当前状态推荐的操作，随时可用"
```

### 4.2 渐进式功能解锁

不要一次暴露所有功能。按使用频次分层：

```
Layer 1（默认可见）：对话、文件引用、模型切换、Quick Actions
Layer 2（使用 3 天后）：Skills、Plan Mode、GitNexus
Layer 3（使用 1 周后）：Memory、Hooks、Workflow Templates
Layer 4（主动开启）：/loop、Worktree、Cron、Subagent 可视化
```

### 4.3 中文化策略

| 层面 | 做法 |
|------|------|
| 命令名 | 保留英文（/review），但加中文描述（"代码审查"） |
| 提示文案 | 全中文，口语化（"让我看看改了什么" 而不是 "Detect Changes"） |
| 错误信息 | 中文 + 建议下一步操作 |
| 文档 | 插件内嵌中文使用指南，按场景组织 |
| Tooltip | 每个按钮 hover 显示"这是什么 + 什么时候用" |

---

## 五、实现路线图

```
Phase 1（2 周）— 降低门槛
├── Quick Actions 组件（场景化推荐）
├── Memory 读取 & 显示（只读）
└── TodoWrite 任务面板（只读展示）

Phase 2（3 周）— 提升效率
├── Memory CRUD 界面
├── Workflow Templates（3 个核心流程）
├── Quick Actions 智能推荐引擎
└── 新手引导流程

Phase 3（3 周）— 高级能力
├── Hooks GUI 配置
├── Subagent 状态可视化
├── /loop 面板
└── 渐进式功能解锁机制

Phase 4（2 周）— 打磨
├── Worktree 可视化
├── 工作流模板市场（用户共享）
└── 使用数据分析 & 推荐优化
```

---

## 六、技术实现要点

### Quick Actions 架构

```
IDE Events (file open, git change, build result)
  ↓
QuickActionService.java（事件监听 + 规则引擎）
  ↓
JSON-IPC → webview
  ↓
<QuickActions> 组件渲染 chip 列表
  ↓
用户点击 → 注入 prompt + context → 发送消息
```

### Memory 集成架构

```
~/.claude/projects/<hash>/memory/*.md
  ↓
MemoryService.java（文件监听 + 解析 frontmatter）
  ↓
对话发送时：MemoryInjector 筛选相关 memory → 注入 system context
  ↓
UI：MemoryPanel 组件（列表 + 编辑 + 搜索）
```

### Workflow Engine 架构

```
workflow-templates/*.json（步骤定义）
  ↓
WorkflowEngine.java（状态机驱动）
  ↓
每步：构造 prompt → 发送 → 解析结果 → 决定下一步
  ↓
UI：WorkflowProgress 组件（步骤条 + 当前状态 + 可干预）
```

