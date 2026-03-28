# Marketplace 页面文案草稿

以下内容用于 JetBrains Marketplace 上架页，可直接复用或按你的品牌口径微调。

## Plugin Name

`CC AI Toolkit`

说明：

- 名称不要包含 `Plugin`、`IntelliJ`、`JetBrains`
- 建议保持在 30 个字符以内

## Short Description

Unified Claude Code, Codex, BMad, Design, and GitNexus workflows inside one IntelliJ tool window.

## Full Description

```html
<p><strong>CC AI Toolkit</strong> brings multiple AI-assisted workflows into a single IntelliJ tool window.</p>
<ul>
  <li><strong>Claude Code and Codex</strong>: switch engines inside the same chat workspace.</li>
  <li><strong>BMad</strong>: run planning, requirements, architecture, development, and QA workflows.</li>
  <li><strong>Design</strong>: generate prompts for dashboards, landing pages, design reviews, and design system work.</li>
  <li><strong>GitNexus</strong>: inspect repository structure, trace call chains, and assess change impact before editing.</li>
  <li><strong>IDE integration</strong>: send file paths, code selections, images, and project context directly from the IDE.</li>
  <li><strong>Session controls</strong>: use history, favorites, export, diff review, rewind, and permission controls in one place.</li>
</ul>
<p>The plugin does not silently scan credentials. Provider credentials and local configuration sources require explicit user action or authorization.</p>
<p>Users can connect directly to official provider endpoints or configure their own proxy/base URL in provider settings.</p>
```

## Suggested Tags

- AI
- Code Generation
- Chat
- Productivity
- Tools Integration

最终标签仍以 Marketplace 上传页里实际可选项为准。

## Suggested Links

- Source Code URL
  - `https://github.com/feelHappy/idea-claude-code-gui`
- Support URL
  - `https://github.com/feelHappy/idea-claude-code-gui/issues`
- Documentation URL
  - `https://github.com/feelHappy/idea-claude-code-gui#readme`
- Privacy Policy URL
  - 指向你公开可访问的 `PRIVACY.md`
- License / EULA URL
  - 指向你公开可访问的 `LICENSE` 或 `EULA.md`

## Screenshot Plan

- 截图 1：主聊天界面，展示 Claude Code / Codex 切换
- 截图 2：BMad 标签页，展示命令选择和发送
- 截图 3：Design 标签页，展示设计提示词或设计工作流
- 截图 4：GitNexus 标签页，展示仓库分析入口
- 截图 5：Provider 设置页，展示多供应商与连接方式说明

## 审核备注建议

如果 JetBrains 审核要求补充说明，可以提供：

```text
This plugin provides an IntelliJ UI layer over user-configured AI providers and local workflow integrations.
It sends prompts and context only after explicit user action.
It does not silently scan the local disk for credentials.
Provider credentials are entered manually or accessed only after explicit user authorization.
```
