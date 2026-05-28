import type { ClaudeContentBlock, ClaudeMessage, ToolResultBlock } from '../types';

const MAX_GOAL_CHARS = 900;
const MAX_PROGRESS_CHARS = 1200;
const MAX_RECENT_USER_MESSAGES = 4;
const MAX_ASSISTANT_MESSAGES = 3;
const MAX_TOOL_NAMES = 8;
const DEFAULT_HANDOFF_TOKEN_THRESHOLD = 3_000_000;

export interface SessionHandoffOptions {
  sessionTitle: string;
  sessionId?: string | null;
  provider?: string;
  model?: string;
  usagePercentage?: number;
  usageUsedTokens?: number;
  usageMaxTokens?: number;
  activeFile?: string;
  selectedLines?: string;
}

export function shouldRecommendSessionHandoff(
  usageUsedTokens?: number,
  hasMessages = false,
  threshold = DEFAULT_HANDOFF_TOKEN_THRESHOLD,
): boolean {
  return hasMessages && typeof usageUsedTokens === 'number' && usageUsedTokens >= threshold;
}

export function buildSessionHandoffMarkdown(
  messages: ClaudeMessage[],
  options: SessionHandoffOptions,
): string {
  const userGoals = messages
    .filter((message) => message.type === 'user')
    .map((message) => summarizeText(getHumanText(message), MAX_GOAL_CHARS))
    .filter(Boolean);
  const assistantProgress = messages
    .filter((message) => message.type === 'assistant')
    .map((message) => summarizeText(getHumanText(message), MAX_PROGRESS_CHARS))
    .filter(Boolean);
  const toolNames = collectToolNames(messages);
  const latestUserGoal = userGoals[userGoals.length - 1] ||
    '未能从当前会话中提取到明确的最近用户目标，请先查看当前打开文件和 git diff。';
  const latestAssistantProgress = assistantProgress[assistantProgress.length - 1] ||
    '未能提取到可靠的最近助手进展，请先检查工作树和相关文档。';
  const previousUserGoals = userGoals.slice(-MAX_RECENT_USER_MESSAGES, -1);
  const previousAssistantProgress = assistantProgress.slice(-MAX_ASSISTANT_MESSAGES, -1);

  return [
    '# 会话接力进度',
    '',
    '## 接力说明',
    '',
    '- ✅ 已检测到当前会话累计 token 较高，需要切换到新上下文继续。',
    '- ✅ 本文件由插件在显示接力提醒前生成，用于让新 agent 精准恢复现场。',
    '- 新 agent 请先阅读本文件，再检查当前工作树；不要从头重新分析无关上下文。',
    '',
    '## 会话状态',
    '',
    `- ✅ 当前会话：${options.sessionTitle || '未命名会话'}`,
    options.sessionId ? `- ✅ 旧会话 ID：${options.sessionId}` : '',
    `- ✅ 当前模型/供应商：${options.provider || '未知'} / ${options.model || '未知'}`,
    `- ✅ 上下文用量：${formatUsage(options)}`,
    `- ✅ 当前打开文件：${formatActiveFile(options.activeFile, options.selectedLines)}`,
    '',
    '## 用户最近目标',
    '',
    `- ✅ ${latestUserGoal}`,
    '',
    '## 已完成进展',
    '',
    `- ✅ ${latestAssistantProgress}`,
    '',
    previousUserGoals.length > 0 ? '## 此前相关要求' : null,
    previousUserGoals.length > 0 ? '' : null,
    ...previousUserGoals.map((goal) => `- ✅ ${goal}`),
    '',
    previousAssistantProgress.length > 0 ? '## 此前相关进展' : null,
    previousAssistantProgress.length > 0 ? '' : null,
    ...previousAssistantProgress.map((progress) => `- ✅ ${progress}`),
    '',
    '## 新 agent 接手清单',
    '',
    '- [ ] 运行 `git status --short`，区分用户已有改动和接下来新增改动。',
    '- [ ] 阅读当前打开文件，以及工作树里新增或修改过的交接/进度文档。',
    '- [ ] 如果要改代码，先按项目要求执行 GitNexus impact analysis。',
    '- [ ] 继续完成“用户最近目标”里尚未完成的部分，优先做最小可验证改动。',
    '- [ ] 完成后运行相关类型检查、构建或测试，并记录结果。',
    toolNames.length > 0 ? '' : null,
    toolNames.length > 0 ? '## 工具线索' : null,
    toolNames.length > 0 ? '' : null,
    toolNames.length > 0 ? `- ✅ 旧会话中使用过：${toolNames.join('、')}。这只是线索，不要复制旧工具输出。` : null,
  ].filter((line): line is string => line != null).join('\n');
}

export function buildSessionHandoffClipboardText(progressPath: string): string {
  return [
    '请继续接手当前任务，不要从头开始。',
    '',
    `先阅读进度追踪文件：${progressPath}`,
    '',
    '文件里已经整理了当前目标、已完成进展、上下文用量、当前文件和新 agent 接手清单。',
    '阅读后先检查 `git status --short`，再按文件中的待办继续推进。',
  ].join('\n');
}

function formatUsage(options: SessionHandoffOptions): string {
  const percentage = typeof options.usagePercentage === 'number'
    ? `${Math.round(options.usagePercentage)}%`
    : '未知';
  if (typeof options.usageUsedTokens === 'number' && typeof options.usageMaxTokens === 'number') {
    return `${percentage}（${options.usageUsedTokens.toLocaleString()} / ${options.usageMaxTokens.toLocaleString()} tokens）`;
  }
  return percentage;
}

function formatActiveFile(activeFile?: string, selectedLines?: string): string {
  if (!activeFile) return '无';
  return selectedLines ? `${activeFile} (${selectedLines})` : activeFile;
}

function collectToolNames(messages: ClaudeMessage[]): string[] {
  const names = new Set<string>();

  for (const message of messages) {
    for (const block of getContentBlocks(message)) {
      if (block.type === 'tool_use' && block.name) {
        names.add(block.name);
      }
    }
  }

  return Array.from(names).slice(-MAX_TOOL_NAMES);
}

function getHumanText(message?: ClaudeMessage): string {
  if (!message) return '';
  if (typeof message.content === 'string' && isUsefulHumanText(message.content)) {
    return message.content.trim();
  }

  const blocks = getContentBlocks(message);
  const parts = blocks
    .map((block) => {
      if (block.type === 'text') return block.text || '';
      return '';
    })
    .filter(isUsefulHumanText);

  return parts.join('\n\n');
}

function getContentBlocks(message: ClaudeMessage): Array<ClaudeContentBlock | ToolResultBlock> {
  const raw = message.raw;
  if (!raw) {
    return message.content ? [{ type: 'text', text: message.content }] : [];
  }
  if (typeof raw === 'string') return [{ type: 'text', text: raw }];
  if (Array.isArray(raw.content)) return raw.content;
  if (typeof raw.content === 'string') return [{ type: 'text', text: raw.content }];
  if (Array.isArray(raw.message?.content)) return raw.message.content;
  if (typeof raw.message?.content === 'string') return [{ type: 'text', text: raw.message.content }];
  return message.content ? [{ type: 'text', text: message.content }] : [];
}

function summarizeText(text: string, maxChars: number): string {
  const normalized = (text || '').replace(/\r\n/g, '\n').trim();
  if (normalized.length <= maxChars) return normalized;
  return `${normalized.slice(0, maxChars).trim()}\n\n...（已截断，剩余 ${normalized.length - maxChars} 个字符）`;
}

function isUsefulHumanText(text?: string): boolean {
  if (!text) return false;
  const trimmed = text.trim();
  return Boolean(trimmed) &&
    trimmed !== '[tool_result]' &&
    trimmed !== '[tool_use]' &&
    !trimmed.startsWith('[tool_result') &&
    !trimmed.startsWith('[tool_use');
}
