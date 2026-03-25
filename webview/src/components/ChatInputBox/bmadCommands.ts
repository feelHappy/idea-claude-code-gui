export type BmadCommandGroup =
  | 'agents'
  | 'analysis'
  | 'planning'
  | 'solutioning'
  | 'implementation'
  | 'utilities';

export interface BmadCommandPreset {
  id: string;
  command: string;
  description: string;
  descriptionKey: string;
  group: BmadCommandGroup;
}

export type BmadStatusState =
  | 'loading'
  | 'ready'
  | 'missing'
  | 'partial'
  | 'unsupported'
  | 'error';

export interface BmadStatus {
  state: BmadStatusState;
  installed: boolean;
  hasUpdate?: boolean;
  provider: string;
  providerLabel: string;
  toolCode: string;
  commandPrefix: string;
  installedVersion?: string;
  latestVersion?: string;
  availableCommands?: string[];
  targetSkillsDir?: string;
  projectBmadDir?: string;
  outputDir?: string;
  hasProjectCore: boolean;
  hasOutputDir: boolean;
  skillCount: number;
  coreSkillCount: number;
  message?: string;
  installCommand?: string;
  nodeAvailable?: boolean;
  nodeSupported?: boolean;
  nodeVersion?: string;
  nodePath?: string;
  error?: string;
}

export const BMAD_COMMAND_PRESETS: BmadCommandPreset[] = [
  {
    id: 'bmad-help',
    command: 'bmad-help',
    description: 'Recommend the next BMad workflow or explain what to do next.',
    descriptionKey: 'chat.bmad.commands.bmad-help',
    group: 'utilities',
  },
  {
    id: 'bmad-analyst',
    command: 'bmad-analyst',
    description: 'Load the Analyst agent for discovery, requirement analysis, and structured project exploration.',
    descriptionKey: 'chat.bmad.commands.bmad-analyst',
    group: 'agents',
  },
  {
    id: 'bmad-pm',
    command: 'bmad-pm',
    description: 'Load the Product Manager agent to drive product planning, scope, and PRD-oriented decisions.',
    descriptionKey: 'chat.bmad.commands.bmad-pm',
    group: 'agents',
  },
  {
    id: 'bmad-architect',
    command: 'bmad-architect',
    description: 'Load the Architect agent for technical architecture, system design, and implementation guidance.',
    descriptionKey: 'chat.bmad.commands.bmad-architect',
    group: 'agents',
  },
  {
    id: 'bmad-ux-designer',
    command: 'bmad-ux-designer',
    description: 'Load the UX Designer agent for interface flows, user journeys, and experience design work.',
    descriptionKey: 'chat.bmad.commands.bmad-ux-designer',
    group: 'agents',
  },
  {
    id: 'bmad-sm',
    command: 'bmad-sm',
    description: 'Load the Scrum Master agent to coordinate story flow, sprint preparation, and delivery sequencing.',
    descriptionKey: 'chat.bmad.commands.bmad-sm',
    group: 'agents',
  },
  {
    id: 'bmad-dev',
    command: 'bmad-dev',
    description: 'Load the Developer agent to implement stories, write code, and handle delivery-focused execution.',
    descriptionKey: 'chat.bmad.commands.bmad-dev',
    group: 'agents',
  },
  {
    id: 'bmad-qa',
    command: 'bmad-qa',
    description: 'Load the QA agent for testing strategy, quality checks, and verification planning.',
    descriptionKey: 'chat.bmad.commands.bmad-qa',
    group: 'agents',
  },
  {
    id: 'bmad-tech-writer',
    command: 'bmad-tech-writer',
    description: 'Load the Tech Writer agent to produce technical docs, explanations, and polished written outputs.',
    descriptionKey: 'chat.bmad.commands.bmad-tech-writer',
    group: 'agents',
  },
  {
    id: 'bmad-quick-flow-solo-dev',
    command: 'bmad-quick-flow-solo-dev',
    description: 'Load the solo quick-flow agent for fast one-person delivery without the full multi-role sequence.',
    descriptionKey: 'chat.bmad.commands.bmad-quick-flow-solo-dev',
    group: 'agents',
  },
  {
    id: 'bmad-advanced-elicitation',
    command: 'bmad-advanced-elicitation',
    description: 'Ask the right follow-up questions to clarify goals, constraints, and missing requirements.',
    descriptionKey: 'chat.bmad.commands.bmad-advanced-elicitation',
    group: 'analysis',
  },
  {
    id: 'bmad-brainstorming',
    command: 'bmad-brainstorming',
    description: 'Guide a structured brainstorming session across one or more ideation techniques.',
    descriptionKey: 'chat.bmad.commands.bmad-brainstorming',
    group: 'analysis',
  },
  {
    id: 'bmad-market-research',
    command: 'bmad-market-research',
    description: 'Research the market, customer needs, trends, and the competitive landscape.',
    descriptionKey: 'chat.bmad.commands.bmad-market-research',
    group: 'analysis',
  },
  {
    id: 'bmad-domain-research',
    command: 'bmad-domain-research',
    description: 'Deep-dive into the domain, terminology, workflows, and subject-matter context.',
    descriptionKey: 'chat.bmad.commands.bmad-domain-research',
    group: 'analysis',
  },
  {
    id: 'bmad-technical-research',
    command: 'bmad-technical-research',
    description: 'Evaluate technical feasibility, solution options, and implementation approaches.',
    descriptionKey: 'chat.bmad.commands.bmad-technical-research',
    group: 'analysis',
  },
  {
    id: 'bmad-create-product-brief',
    command: 'bmad-create-product-brief',
    description: 'Capture a new product idea as a focused brief before PRD work starts.',
    descriptionKey: 'chat.bmad.commands.bmad-create-product-brief',
    group: 'analysis',
  },
  {
    id: 'bmad-product-brief-preview',
    command: 'bmad-product-brief-preview',
    description: 'Review the product brief and decide what to refine before moving into PRD work.',
    descriptionKey: 'chat.bmad.commands.bmad-product-brief-preview',
    group: 'analysis',
  },
  {
    id: 'bmad-create-prd',
    command: 'bmad-create-prd',
    description: 'Create a structured PRD for a new feature or project.',
    descriptionKey: 'chat.bmad.commands.bmad-create-prd',
    group: 'planning',
  },
  {
    id: 'bmad-validate-prd',
    command: 'bmad-validate-prd',
    description: 'Validate that the PRD is complete, lean, and internally consistent.',
    descriptionKey: 'chat.bmad.commands.bmad-validate-prd',
    group: 'planning',
  },
  {
    id: 'bmad-edit-prd',
    command: 'bmad-edit-prd',
    description: 'Improve and refine an existing PRD instead of starting over.',
    descriptionKey: 'chat.bmad.commands.bmad-edit-prd',
    group: 'planning',
  },
  {
    id: 'bmad-create-ux-design',
    command: 'bmad-create-ux-design',
    description: 'Plan the UX and interaction flow, especially when UI is a major part of the project.',
    descriptionKey: 'chat.bmad.commands.bmad-create-ux-design',
    group: 'planning',
  },
  {
    id: 'bmad-create-architecture',
    command: 'bmad-create-architecture',
    description: 'Produce the technical architecture and solution design.',
    descriptionKey: 'chat.bmad.commands.bmad-create-architecture',
    group: 'solutioning',
  },
  {
    id: 'bmad-create-epics-and-stories',
    command: 'bmad-create-epics-and-stories',
    description: 'Break the PRD and architecture into epics and implementable stories.',
    descriptionKey: 'chat.bmad.commands.bmad-create-epics-and-stories',
    group: 'solutioning',
  },
  {
    id: 'bmad-check-implementation-readiness',
    command: 'bmad-check-implementation-readiness',
    description: 'Check that PRD, UX, architecture, epics, and stories are aligned before delivery starts.',
    descriptionKey: 'chat.bmad.commands.bmad-check-implementation-readiness',
    group: 'solutioning',
  },
  {
    id: 'bmad-sprint-planning',
    command: 'bmad-sprint-planning',
    description: 'Turn the prepared stories into an execution-ready sprint plan.',
    descriptionKey: 'chat.bmad.commands.bmad-sprint-planning',
    group: 'implementation',
  },
  {
    id: 'bmad-sprint-status',
    command: 'bmad-sprint-status',
    description: 'Summarize current sprint progress and route to the next BMad workflow.',
    descriptionKey: 'chat.bmad.commands.bmad-sprint-status',
    group: 'implementation',
  },
  {
    id: 'bmad-create-story',
    command: 'bmad-create-story',
    description: 'Prepare the next story before implementation starts.',
    descriptionKey: 'chat.bmad.commands.bmad-create-story',
    group: 'implementation',
  },
  {
    id: 'bmad-dev-story',
    command: 'bmad-dev-story',
    description: 'Implement the current story with the expected BMad flow.',
    descriptionKey: 'chat.bmad.commands.bmad-dev-story',
    group: 'implementation',
  },
  {
    id: 'bmad-code-review',
    command: 'bmad-code-review',
    description: 'Run the BMad review step after implementation.',
    descriptionKey: 'chat.bmad.commands.bmad-code-review',
    group: 'implementation',
  },
  {
    id: 'bmad-qa-generate-e2e-tests',
    command: 'bmad-qa-generate-e2e-tests',
    description: 'Generate automated QA or E2E coverage using the project\'s existing test stack.',
    descriptionKey: 'chat.bmad.commands.bmad-qa-generate-e2e-tests',
    group: 'implementation',
  },
  {
    id: 'bmad-retrospective',
    command: 'bmad-retrospective',
    description: 'Review completed work, lessons learned, and what to do next at the end of an epic.',
    descriptionKey: 'chat.bmad.commands.bmad-retrospective',
    group: 'implementation',
  },
  {
    id: 'bmad-quick-dev',
    command: 'bmad-quick-dev',
    description: 'Use the lightweight quick-flow for small tasks and simple changes.',
    descriptionKey: 'chat.bmad.commands.bmad-quick-dev',
    group: 'utilities',
  },
  {
    id: 'bmad-quick-dev-new-preview',
    command: 'bmad-quick-dev-new-preview',
    description: 'Try the experimental quick flow that clarifies, plans, implements, reviews, and presents in one pass.',
    descriptionKey: 'chat.bmad.commands.bmad-quick-dev-new-preview',
    group: 'utilities',
  },
  {
    id: 'bmad-quick-spec',
    command: 'bmad-quick-spec',
    description: 'Create a lighter-weight spec for one-off tasks, small apps, and simple brownfield work.',
    descriptionKey: 'chat.bmad.commands.bmad-quick-spec',
    group: 'utilities',
  },
  {
    id: 'bmad-document-project',
    command: 'bmad-document-project',
    description: 'Document an existing project before deeper planning or delivery work.',
    descriptionKey: 'chat.bmad.commands.bmad-document-project',
    group: 'utilities',
  },
  {
    id: 'bmad-generate-project-context',
    command: 'bmad-generate-project-context',
    description: 'Generate lean project context for better agent grounding in brownfield repos.',
    descriptionKey: 'chat.bmad.commands.bmad-generate-project-context',
    group: 'utilities',
  },
  {
    id: 'bmad-correct-course',
    command: 'bmad-correct-course',
    description: 'Recover when scope, plan, or implementation has drifted off track.',
    descriptionKey: 'chat.bmad.commands.bmad-correct-course',
    group: 'utilities',
  },
  {
    id: 'bmad-distillator',
    command: 'bmad-distillator',
    description: 'Condense long notes, docs, or outputs into concise structured takeaways.',
    descriptionKey: 'chat.bmad.commands.bmad-distillator',
    group: 'utilities',
  },
  {
    id: 'bmad-editorial-review-prose',
    command: 'bmad-editorial-review-prose',
    description: 'Review prose for clarity, tone, readability, and wording.',
    descriptionKey: 'chat.bmad.commands.bmad-editorial-review-prose',
    group: 'utilities',
  },
  {
    id: 'bmad-editorial-review-structure',
    command: 'bmad-editorial-review-structure',
    description: 'Review document structure, sequencing, and overall information flow.',
    descriptionKey: 'chat.bmad.commands.bmad-editorial-review-structure',
    group: 'utilities',
  },
  {
    id: 'bmad-index-docs',
    command: 'bmad-index-docs',
    description: 'Generate an index of project documents to speed up later BMad workflows.',
    descriptionKey: 'chat.bmad.commands.bmad-index-docs',
    group: 'utilities',
  },
  {
    id: 'bmad-party-mode',
    command: 'bmad-party-mode',
    description: 'Switch into a looser, more creative collaboration mode for exploring bold ideas.',
    descriptionKey: 'chat.bmad.commands.bmad-party-mode',
    group: 'utilities',
  },
  {
    id: 'bmad-review-adversarial-general',
    command: 'bmad-review-adversarial-general',
    description: 'Stress-test a plan or output with adversarial review to uncover weak spots.',
    descriptionKey: 'chat.bmad.commands.bmad-review-adversarial-general',
    group: 'utilities',
  },
  {
    id: 'bmad-review-edge-case-hunter',
    command: 'bmad-review-edge-case-hunter',
    description: 'Search for edge cases, failure modes, and overlooked scenarios.',
    descriptionKey: 'chat.bmad.commands.bmad-review-edge-case-hunter',
    group: 'utilities',
  },
  {
    id: 'bmad-shard-doc',
    command: 'bmad-shard-doc',
    description: 'Split a large document into smaller, easier-to-review sections.',
    descriptionKey: 'chat.bmad.commands.bmad-shard-doc',
    group: 'utilities',
  },
];

const BMAD_COMMAND_MAP = new Map(BMAD_COMMAND_PRESETS.map((preset) => [preset.command, preset]));

function createFallbackBmadPreset(command: string): BmadCommandPreset {
  return {
    id: command,
    command,
    description: `Invoke the ${command} BMad skill installed in the current project.`,
    descriptionKey: `chat.bmad.commands.${command}`,
    group: 'utilities',
  };
}

export function getBmadCommandPresets(availableCommands?: string[]): BmadCommandPreset[] {
  const normalizedCommands = Array.from(
    new Set(
      (availableCommands ?? [])
        .filter((command): command is string => typeof command === 'string' && command.trim().length > 0)
        .map((command) => command.trim())
    )
  );

  if (normalizedCommands.length === 0) {
    return BMAD_COMMAND_PRESETS;
  }

  const availableSet = new Set(normalizedCommands);
  const matchedPresets = BMAD_COMMAND_PRESETS.filter((preset) => availableSet.has(preset.command));
  const matchedSet = new Set(matchedPresets.map((preset) => preset.command));
  const fallbackPresets = normalizedCommands
    .filter((command) => !matchedSet.has(command))
    .map((command) => BMAD_COMMAND_MAP.get(command) ?? createFallbackBmadPreset(command));

  return matchedPresets.length > 0 || fallbackPresets.length > 0
    ? [...matchedPresets, ...fallbackPresets]
    : BMAD_COMMAND_PRESETS;
}

export function isBmadProviderSupported(provider?: string): boolean {
  return provider === 'claude' || provider === 'codex';
}

export function getBmadCommandPrefix(provider?: string): string {
  if (provider === 'codex') {
    return '$';
  }
  if (provider === 'claude') {
    return '/';
  }
  return '';
}

export function getBmadProviderLabel(provider?: string): string {
  if (provider === 'codex') {
    return 'Codex';
  }
  if (provider === 'claude') {
    return 'Claude Code';
  }
  return 'Unsupported';
}

export function createDefaultBmadStatus(provider?: string): BmadStatus {
  return {
    state: isBmadProviderSupported(provider) ? 'loading' : 'unsupported',
    installed: false,
    provider: provider ?? 'claude',
    providerLabel: getBmadProviderLabel(provider),
    toolCode: provider === 'codex' ? 'codex' : provider === 'claude' ? 'claude-code' : '',
    commandPrefix: getBmadCommandPrefix(provider),
    availableCommands: [],
    hasUpdate: false,
    hasProjectCore: false,
    hasOutputDir: false,
    skillCount: 0,
    coreSkillCount: 0,
  };
}
