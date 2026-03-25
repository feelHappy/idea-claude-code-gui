export interface FeatureGuideCard {
  icon: string;
  title: string;
  description: string;
}

export interface FeatureGuideStep {
  title: string;
  description: string;
}

export interface FeatureGuideContent {
  title: string;
  badge: string;
  subtitle: string;
  summary: string;
  cards: FeatureGuideCard[];
  stepsTitle: string;
  steps: FeatureGuideStep[];
  notesTitle: string;
  notes: string[];
  changelogButton: string;
  closeButton: string;
}

export const FEATURE_GUIDE_CONTENT: Record<'zh' | 'en', FeatureGuideContent> = {
  zh: {
    title: 'CC AI Toolkit 功能导览',
    badge: '整合工作台',
    subtitle: '现在插件把 Claude Code、Codex Cli、BMad、UI UX Pro Max 和 GitNexus 整合在同一个输入区里。',
    summary: '你不需要再开多个独立面板。先在顶部切换引擎，再在下方切换标签，就可以在同一个窗口里完成需求规划、设计输出、仓库理解和代码协作。',
    cards: [
      {
        icon: 'codicon-symbol-interface',
        title: '双引擎切换',
        description: '顶部直接切换 Claude Code 与 Codex Cli，沿用同一套输入框、上下文和会话区域。',
      },
      {
        icon: 'codicon-hubot',
        title: 'BMad',
        description: '适合需求梳理、PRD、架构、Story、开发、QA 等流程型工作。',
      },
      {
        icon: 'codicon-symbol-color',
        title: 'UI UX Pro Max',
        description: '适合落地页、Dashboard、设计评审、组件改造和设计系统场景。',
      },
      {
        icon: 'codicon-circuit-board',
        title: 'GitNexus',
        description: '适合仓库总览、调用链、热点分析和改动影响面评估。',
      },
    ],
    stepsTitle: '建议这样开始',
    steps: [
      {
        title: '先选当前要用的引擎',
        description: '需要通用对话和稳定执行时用 Claude Code；需要更偏 CLI/代码任务的体验时切到 Codex Cli。',
      },
      {
        title: '再切换下方功能标签',
        description: 'BMad 管流程，UI UX Pro Max 管设计，GitNexus 管仓库理解与改动分析。',
      },
      {
        title: '状态异常先修复',
        description: '如果看到“未安装”或“需修复”，先点安装、修复或刷新，把当前项目补齐后再发起任务。',
      },
      {
        title: '区分插入和发送',
        description: '“插入”只把提示词放进输入框；“发送”会立刻把当前选择的内容发给正在使用的引擎。',
      },
    ],
    notesTitle: '使用说明',
    notes: [
      'BMad 更适合流程型任务，尤其是产品、研发和交付链路。',
      'UI UX Pro Max 更适合界面、体验和视觉方向相关任务。',
      'GitNexus 更适合理解仓库、追调用链和改代码前做影响分析。',
    ],
    changelogButton: '查看版本记录',
    closeButton: '开始使用',
  },
  en: {
    title: 'CC AI Toolkit Guide',
    badge: 'Unified Workspace',
    subtitle: 'Claude Code, Codex Cli, BMad, UI UX Pro Max, and GitNexus now live in one integrated input area.',
    summary: 'You no longer need multiple separate panels. Switch the engine at the top, then switch the tab below to handle planning, design, repository understanding, and coding work in the same window.',
    cards: [
      {
        icon: 'codicon-symbol-interface',
        title: 'Dual Engines',
        description: 'Switch between Claude Code and Codex Cli at the top while keeping one shared input flow and session area.',
      },
      {
        icon: 'codicon-hubot',
        title: 'BMad',
        description: 'Best for planning-heavy work such as requirements, PRDs, architecture, stories, development, and QA.',
      },
      {
        icon: 'codicon-symbol-color',
        title: 'UI UX Pro Max',
        description: 'Best for landing pages, dashboards, design review, component polish, and design system work.',
      },
      {
        icon: 'codicon-circuit-board',
        title: 'GitNexus',
        description: 'Best for repository overview, call-chain tracing, hotspot analysis, and change impact checks.',
      },
    ],
    stepsTitle: 'Recommended Flow',
    steps: [
      {
        title: 'Choose the engine first',
        description: 'Use Claude Code for general collaboration and steady execution. Switch to Codex Cli for a more CLI-oriented coding workflow.',
      },
      {
        title: 'Switch the tab below',
        description: 'Use BMad for process workflows, UI UX Pro Max for design work, and GitNexus for repo understanding and change analysis.',
      },
      {
        title: 'Fix setup issues before sending',
        description: 'If a panel shows Not Installed or Needs Repair, run Install, Repair, or Refresh first so the current project is ready.',
      },
      {
        title: 'Know Insert vs Send',
        description: 'Insert only places the prompt into the input box. Send immediately submits the current selection to the active engine.',
      },
    ],
    notesTitle: 'Usage Notes',
    notes: [
      'BMad is optimized for process-driven product and delivery work.',
      'UI UX Pro Max is optimized for interface, experience, and visual design tasks.',
      'GitNexus is optimized for reading the repo, tracing flows, and checking impact before edits.',
    ],
    changelogButton: 'View Changelog',
    closeButton: 'Start Using',
  },
};
