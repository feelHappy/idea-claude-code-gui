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

interface BmadCommandDefinition {
  id: string;
  primaryCommand: string;
  aliases?: string[];
  description: string;
  descriptionKey: string;
  group: BmadCommandGroup;
  showByDefault?: boolean;
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

const BMAD_COMMAND_DEFINITIONS: BmadCommandDefinition[] = [
  {
    id: 'bmad-help',
    primaryCommand: 'bmad-help',
    description: 'Recommend the next BMad workflow or explain what to do next.',
    descriptionKey: 'chat.bmad.commands.bmad-help',
    group: 'utilities',
  },
  {
    id: 'bmad-agent-analyst',
    primaryCommand: 'bmad-agent-analyst',
    aliases: ['bmad-analyst'],
    description: 'Load the Analyst agent for discovery, requirement analysis, and structured project exploration.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-analyst',
    group: 'agents',
  },
  {
    id: 'bmad-agent-pm',
    primaryCommand: 'bmad-agent-pm',
    aliases: ['bmad-pm'],
    description: 'Load the Product Manager agent to drive product planning, scope, and PRD-oriented decisions.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-pm',
    group: 'agents',
  },
  {
    id: 'bmad-agent-architect',
    primaryCommand: 'bmad-agent-architect',
    aliases: ['bmad-architect'],
    description: 'Load the Architect agent for technical architecture, system design, and implementation guidance.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-architect',
    group: 'agents',
  },
  {
    id: 'bmad-agent-ux-designer',
    primaryCommand: 'bmad-agent-ux-designer',
    aliases: ['bmad-ux-designer'],
    description: 'Load the UX Designer agent for interface flows, user journeys, and experience design work.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-ux-designer',
    group: 'agents',
  },
  {
    id: 'bmad-agent-sm',
    primaryCommand: 'bmad-agent-sm',
    aliases: ['bmad-sm'],
    description: 'Load the Scrum Master agent to coordinate story flow, sprint preparation, and delivery sequencing.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-sm',
    group: 'agents',
  },
  {
    id: 'bmad-agent-dev',
    primaryCommand: 'bmad-agent-dev',
    aliases: ['bmad-dev'],
    description: 'Load the Developer agent to implement stories, write code, and handle delivery-focused execution.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-dev',
    group: 'agents',
  },
  {
    id: 'bmad-agent-qa',
    primaryCommand: 'bmad-agent-qa',
    aliases: ['bmad-qa'],
    description: 'Load the QA agent for testing strategy, quality checks, and verification planning.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-qa',
    group: 'agents',
  },
  {
    id: 'bmad-agent-tech-writer',
    primaryCommand: 'bmad-agent-tech-writer',
    aliases: ['bmad-tech-writer'],
    description: 'Load the Tech Writer agent to produce technical docs, explanations, and polished written outputs.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-tech-writer',
    group: 'agents',
  },
  {
    id: 'bmad-agent-quick-flow-solo-dev',
    primaryCommand: 'bmad-agent-quick-flow-solo-dev',
    aliases: ['bmad-quick-flow-solo-dev', 'bmad-master'],
    description: 'Load the solo quick-flow agent for fast one-person delivery without the full multi-role sequence.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-quick-flow-solo-dev',
    group: 'agents',
  },
  {
    id: 'bmad-advanced-elicitation',
    primaryCommand: 'bmad-advanced-elicitation',
    description: 'Ask the right follow-up questions to clarify goals, constraints, and missing requirements.',
    descriptionKey: 'chat.bmad.commands.bmad-advanced-elicitation',
    group: 'analysis',
  },
  {
    id: 'bmad-brainstorming',
    primaryCommand: 'bmad-brainstorming',
    description: 'Guide a structured brainstorming session across one or more ideation techniques.',
    descriptionKey: 'chat.bmad.commands.bmad-brainstorming',
    group: 'analysis',
  },
  {
    id: 'bmad-market-research',
    primaryCommand: 'bmad-market-research',
    description: 'Research the market, customer needs, trends, and the competitive landscape.',
    descriptionKey: 'chat.bmad.commands.bmad-market-research',
    group: 'analysis',
  },
  {
    id: 'bmad-domain-research',
    primaryCommand: 'bmad-domain-research',
    description: 'Deep-dive into the domain, terminology, workflows, and subject-matter context.',
    descriptionKey: 'chat.bmad.commands.bmad-domain-research',
    group: 'analysis',
  },
  {
    id: 'bmad-technical-research',
    primaryCommand: 'bmad-technical-research',
    description: 'Evaluate technical feasibility, solution options, and implementation approaches.',
    descriptionKey: 'chat.bmad.commands.bmad-technical-research',
    group: 'analysis',
  },
  {
    id: 'bmad-product-brief',
    primaryCommand: 'bmad-product-brief',
    aliases: ['bmad-create-product-brief'],
    description: 'Capture a new product idea as a focused brief before PRD work starts.',
    descriptionKey: 'chat.bmad.commands.bmad-product-brief',
    group: 'analysis',
  },
  {
    id: 'bmad-product-brief-preview',
    primaryCommand: 'bmad-product-brief-preview',
    description: 'Review the product brief and decide what to refine before moving into PRD work.',
    descriptionKey: 'chat.bmad.commands.bmad-product-brief-preview',
    group: 'analysis',
    showByDefault: false,
  },
  {
    id: 'bmad-create-prd',
    primaryCommand: 'bmad-create-prd',
    description: 'Create a structured PRD for a new feature or project.',
    descriptionKey: 'chat.bmad.commands.bmad-create-prd',
    group: 'planning',
  },
  {
    id: 'bmad-validate-prd',
    primaryCommand: 'bmad-validate-prd',
    description: 'Validate that the PRD is complete, lean, and internally consistent.',
    descriptionKey: 'chat.bmad.commands.bmad-validate-prd',
    group: 'planning',
  },
  {
    id: 'bmad-edit-prd',
    primaryCommand: 'bmad-edit-prd',
    description: 'Improve and refine an existing PRD instead of starting over.',
    descriptionKey: 'chat.bmad.commands.bmad-edit-prd',
    group: 'planning',
  },
  {
    id: 'bmad-create-ux-design',
    primaryCommand: 'bmad-create-ux-design',
    description: 'Plan the UX and interaction flow, especially when UI is a major part of the project.',
    descriptionKey: 'chat.bmad.commands.bmad-create-ux-design',
    group: 'planning',
  },
  {
    id: 'bmad-create-architecture',
    primaryCommand: 'bmad-create-architecture',
    description: 'Produce the technical architecture and solution design.',
    descriptionKey: 'chat.bmad.commands.bmad-create-architecture',
    group: 'solutioning',
  },
  {
    id: 'bmad-create-epics-and-stories',
    primaryCommand: 'bmad-create-epics-and-stories',
    description: 'Break the PRD and architecture into epics and implementable stories.',
    descriptionKey: 'chat.bmad.commands.bmad-create-epics-and-stories',
    group: 'solutioning',
  },
  {
    id: 'bmad-check-implementation-readiness',
    primaryCommand: 'bmad-check-implementation-readiness',
    description: 'Check that PRD, UX, architecture, epics, and stories are aligned before delivery starts.',
    descriptionKey: 'chat.bmad.commands.bmad-check-implementation-readiness',
    group: 'solutioning',
  },
  {
    id: 'bmad-sprint-planning',
    primaryCommand: 'bmad-sprint-planning',
    description: 'Turn the prepared stories into an execution-ready sprint plan.',
    descriptionKey: 'chat.bmad.commands.bmad-sprint-planning',
    group: 'implementation',
  },
  {
    id: 'bmad-sprint-status',
    primaryCommand: 'bmad-sprint-status',
    description: 'Summarize current sprint progress and route to the next BMad workflow.',
    descriptionKey: 'chat.bmad.commands.bmad-sprint-status',
    group: 'implementation',
  },
  {
    id: 'bmad-create-story',
    primaryCommand: 'bmad-create-story',
    description: 'Prepare the next story before implementation starts.',
    descriptionKey: 'chat.bmad.commands.bmad-create-story',
    group: 'implementation',
  },
  {
    id: 'bmad-dev-story',
    primaryCommand: 'bmad-dev-story',
    description: 'Implement the current story with the expected BMad flow.',
    descriptionKey: 'chat.bmad.commands.bmad-dev-story',
    group: 'implementation',
  },
  {
    id: 'bmad-code-review',
    primaryCommand: 'bmad-code-review',
    description: 'Run the BMad review step after implementation.',
    descriptionKey: 'chat.bmad.commands.bmad-code-review',
    group: 'implementation',
  },
  {
    id: 'bmad-qa-generate-e2e-tests',
    primaryCommand: 'bmad-qa-generate-e2e-tests',
    description: 'Generate automated QA or E2E coverage using the project\'s existing test stack.',
    descriptionKey: 'chat.bmad.commands.bmad-qa-generate-e2e-tests',
    group: 'implementation',
  },
  {
    id: 'bmad-retrospective',
    primaryCommand: 'bmad-retrospective',
    description: 'Review completed work, lessons learned, and what to do next at the end of an epic.',
    descriptionKey: 'chat.bmad.commands.bmad-retrospective',
    group: 'implementation',
  },
  {
    id: 'bmad-quick-dev',
    primaryCommand: 'bmad-quick-dev',
    description: 'Use the lightweight quick-flow for small tasks and simple changes.',
    descriptionKey: 'chat.bmad.commands.bmad-quick-dev',
    group: 'utilities',
  },
  {
    id: 'bmad-quick-dev-new-preview',
    primaryCommand: 'bmad-quick-dev-new-preview',
    description: 'Try the experimental quick flow that clarifies, plans, implements, reviews, and presents in one pass.',
    descriptionKey: 'chat.bmad.commands.bmad-quick-dev-new-preview',
    group: 'utilities',
    showByDefault: false,
  },
  {
    id: 'bmad-quick-spec',
    primaryCommand: 'bmad-quick-spec',
    description: 'Create a lighter-weight spec for one-off tasks, small apps, and simple brownfield work.',
    descriptionKey: 'chat.bmad.commands.bmad-quick-spec',
    group: 'utilities',
    showByDefault: false,
  },
  {
    id: 'bmad-document-project',
    primaryCommand: 'bmad-document-project',
    description: 'Document an existing project before deeper planning or delivery work.',
    descriptionKey: 'chat.bmad.commands.bmad-document-project',
    group: 'utilities',
  },
  {
    id: 'bmad-generate-project-context',
    primaryCommand: 'bmad-generate-project-context',
    description: 'Generate lean project context for better agent grounding in brownfield repos.',
    descriptionKey: 'chat.bmad.commands.bmad-generate-project-context',
    group: 'utilities',
  },
  {
    id: 'bmad-correct-course',
    primaryCommand: 'bmad-correct-course',
    description: 'Recover when scope, plan, or implementation has drifted off track.',
    descriptionKey: 'chat.bmad.commands.bmad-correct-course',
    group: 'utilities',
  },
  {
    id: 'bmad-distillator',
    primaryCommand: 'bmad-distillator',
    description: 'Condense long notes, docs, or outputs into concise structured takeaways.',
    descriptionKey: 'chat.bmad.commands.bmad-distillator',
    group: 'utilities',
  },
  {
    id: 'bmad-editorial-review-prose',
    primaryCommand: 'bmad-editorial-review-prose',
    description: 'Review prose for clarity, tone, readability, and wording.',
    descriptionKey: 'chat.bmad.commands.bmad-editorial-review-prose',
    group: 'utilities',
  },
  {
    id: 'bmad-editorial-review-structure',
    primaryCommand: 'bmad-editorial-review-structure',
    description: 'Review document structure, sequencing, and overall information flow.',
    descriptionKey: 'chat.bmad.commands.bmad-editorial-review-structure',
    group: 'utilities',
  },
  {
    id: 'bmad-index-docs',
    primaryCommand: 'bmad-index-docs',
    description: 'Generate an index of project documents to speed up later BMad workflows.',
    descriptionKey: 'chat.bmad.commands.bmad-index-docs',
    group: 'utilities',
  },
  {
    id: 'bmad-party-mode',
    primaryCommand: 'bmad-party-mode',
    description: 'Switch into a looser, more creative collaboration mode for exploring bold ideas.',
    descriptionKey: 'chat.bmad.commands.bmad-party-mode',
    group: 'utilities',
  },
  {
    id: 'bmad-review-adversarial-general',
    primaryCommand: 'bmad-review-adversarial-general',
    description: 'Stress-test a plan or output with adversarial review to uncover weak spots.',
    descriptionKey: 'chat.bmad.commands.bmad-review-adversarial-general',
    group: 'utilities',
  },
  {
    id: 'bmad-review-edge-case-hunter',
    primaryCommand: 'bmad-review-edge-case-hunter',
    description: 'Search for edge cases, failure modes, and overlooked scenarios.',
    descriptionKey: 'chat.bmad.commands.bmad-review-edge-case-hunter',
    group: 'utilities',
  },
  {
    id: 'bmad-shard-doc',
    primaryCommand: 'bmad-shard-doc',
    description: 'Split a large document into smaller, easier-to-review sections.',
    descriptionKey: 'chat.bmad.commands.bmad-shard-doc',
    group: 'utilities',
  },
];

const HIDDEN_BMAD_COMMANDS = new Set(['bmad-init']);

function createPreset(
  definition: BmadCommandDefinition,
  command = definition.primaryCommand
): BmadCommandPreset {
  return {
    id: definition.id,
    command,
    description: definition.description,
    descriptionKey: definition.descriptionKey,
    group: definition.group,
  };
}

export const BMAD_COMMAND_PRESETS: BmadCommandPreset[] = BMAD_COMMAND_DEFINITIONS
  .filter((definition) => definition.showByDefault !== false)
  .map((definition) => createPreset(definition));

const BMAD_COMMAND_DEFINITION_MAP = new Map<string, BmadCommandDefinition>();
for (const definition of BMAD_COMMAND_DEFINITIONS) {
  BMAD_COMMAND_DEFINITION_MAP.set(definition.primaryCommand, definition);
  for (const alias of definition.aliases ?? []) {
    BMAD_COMMAND_DEFINITION_MAP.set(alias, definition);
  }
}

function getCommandVariants(definition: BmadCommandDefinition): string[] {
  return [definition.primaryCommand, ...(definition.aliases ?? [])];
}

function resolveAvailableCommand(
  definition: BmadCommandDefinition,
  availableCommands: ReadonlySet<string>
): string | null {
  for (const command of getCommandVariants(definition)) {
    if (availableCommands.has(command)) {
      return command;
    }
  }
  return null;
}

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
        .filter((command) => !HIDDEN_BMAD_COMMANDS.has(command))
    )
  );

  if (normalizedCommands.length === 0) {
    return BMAD_COMMAND_PRESETS;
  }

  const availableSet = new Set(normalizedCommands);
  const consumedCommands = new Set<string>();
  const matchedPresets = BMAD_COMMAND_DEFINITIONS
    .map((definition) => {
      const matchedCommand = resolveAvailableCommand(definition, availableSet);
      if (!matchedCommand) {
        return null;
      }
      for (const variant of getCommandVariants(definition)) {
        consumedCommands.add(variant);
      }
      return createPreset(definition, matchedCommand);
    })
    .filter((preset): preset is BmadCommandPreset => preset !== null);

  const fallbackPresets = normalizedCommands
    .filter((command) => !consumedCommands.has(command))
    .map((command) => {
      const definition = BMAD_COMMAND_DEFINITION_MAP.get(command);
      return definition ? createPreset(definition, command) : createFallbackBmadPreset(command);
    });

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
