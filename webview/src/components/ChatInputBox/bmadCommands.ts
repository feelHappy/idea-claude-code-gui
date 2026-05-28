export type BmadCommandGroup = 'discover' | 'plan' | 'build' | 'tools';

export type BmadCommandLevel = 'beginner' | 'intermediate' | 'advanced';

export interface BmadCommandPreset {
  id: string;
  command: string;
  description: string;
  descriptionKey: string;
  group: BmadCommandGroup;
  level: BmadCommandLevel;
}

interface BmadCommandDefinition {
  id: string;
  primaryCommand: string;
  aliases?: string[];
  description: string;
  descriptionKey: string;
  group: BmadCommandGroup;
  level: BmadCommandLevel;
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
  // ── Discover ──────────────────────────────────────────────
  {
    id: 'bmad-agent-analyst',
    primaryCommand: 'bmad-agent-analyst',
    aliases: ['bmad-analyst'],
    description: 'Load the Analyst agent for discovery, requirement analysis, and structured project exploration.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-analyst',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-agent-pm',
    primaryCommand: 'bmad-agent-pm',
    aliases: ['bmad-pm'],
    description: 'Load the Product Manager agent to drive product planning, scope, and PRD-oriented decisions.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-pm',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-agent-architect',
    primaryCommand: 'bmad-agent-architect',
    aliases: ['bmad-architect'],
    description: 'Load the Architect agent for technical architecture, system design, and implementation guidance.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-architect',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-agent-ux-designer',
    primaryCommand: 'bmad-agent-ux-designer',
    aliases: ['bmad-ux-designer'],
    description: 'Load the UX Designer agent for interface flows, user journeys, and experience design work.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-ux-designer',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-agent-dev',
    primaryCommand: 'bmad-agent-dev',
    aliases: ['bmad-dev'],
    description: 'Load the Developer agent to implement stories, write code, and handle delivery-focused execution.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-dev',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-agent-tech-writer',
    primaryCommand: 'bmad-agent-tech-writer',
    aliases: ['bmad-tech-writer'],
    description: 'Load the Tech Writer agent to produce technical docs, explanations, and polished written outputs.',
    descriptionKey: 'chat.bmad.commands.bmad-agent-tech-writer',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-brainstorming',
    primaryCommand: 'bmad-brainstorming',
    description: 'Guide a structured brainstorming session across one or more ideation techniques.',
    descriptionKey: 'chat.bmad.commands.bmad-brainstorming',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-market-research',
    primaryCommand: 'bmad-market-research',
    description: 'Research the market, customer needs, trends, and the competitive landscape.',
    descriptionKey: 'chat.bmad.commands.bmad-market-research',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-domain-research',
    primaryCommand: 'bmad-domain-research',
    description: 'Deep-dive into the domain, terminology, workflows, and subject-matter context.',
    descriptionKey: 'chat.bmad.commands.bmad-domain-research',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-technical-research',
    primaryCommand: 'bmad-technical-research',
    description: 'Evaluate technical feasibility, solution options, and implementation approaches.',
    descriptionKey: 'chat.bmad.commands.bmad-technical-research',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-product-brief',
    primaryCommand: 'bmad-product-brief',
    aliases: ['bmad-create-product-brief'],
    description: 'Capture a new product idea as a focused brief before PRD work starts.',
    descriptionKey: 'chat.bmad.commands.bmad-product-brief',
    group: 'discover',
    level: 'beginner',
  },
  {
    id: 'bmad-prfaq',
    primaryCommand: 'bmad-prfaq',
    description: 'Use the Working Backwards PRFAQ challenge to forge and validate product concepts.',
    descriptionKey: 'chat.bmad.commands.bmad-prfaq',
    group: 'discover',
    level: 'intermediate',
  },
  {
    id: 'bmad-advanced-elicitation',
    primaryCommand: 'bmad-advanced-elicitation',
    description: 'Ask the right follow-up questions to clarify goals, constraints, and missing requirements.',
    descriptionKey: 'chat.bmad.commands.bmad-advanced-elicitation',
    group: 'discover',
    level: 'advanced',
  },

  // ── Plan ──────────────────────────────────────────────────
  {
    id: 'bmad-prd',
    primaryCommand: 'bmad-prd',
    aliases: ['bmad-create-prd', 'bmad-edit-prd', 'bmad-validate-prd'],
    description: 'Create, update, or validate a PRD — one skill handles the full lifecycle.',
    descriptionKey: 'chat.bmad.commands.bmad-prd',
    group: 'plan',
    level: 'beginner',
  },
  {
    id: 'bmad-ux',
    primaryCommand: 'bmad-ux',
    aliases: ['bmad-create-ux-design'],
    description: 'Plan UX patterns and design specifications, producing DESIGN.md and EXPERIENCE.md.',
    descriptionKey: 'chat.bmad.commands.bmad-ux',
    group: 'plan',
    level: 'intermediate',
  },
  {
    id: 'bmad-create-architecture',
    primaryCommand: 'bmad-create-architecture',
    description: 'Produce the technical architecture and solution design.',
    descriptionKey: 'chat.bmad.commands.bmad-create-architecture',
    group: 'plan',
    level: 'intermediate',
  },
  {
    id: 'bmad-create-epics-and-stories',
    primaryCommand: 'bmad-create-epics-and-stories',
    description: 'Break the PRD and architecture into epics and implementable stories.',
    descriptionKey: 'chat.bmad.commands.bmad-create-epics-and-stories',
    group: 'plan',
    level: 'intermediate',
  },
  {
    id: 'bmad-check-implementation-readiness',
    primaryCommand: 'bmad-check-implementation-readiness',
    description: 'Check that PRD, UX, architecture, epics, and stories are aligned before delivery starts.',
    descriptionKey: 'chat.bmad.commands.bmad-check-implementation-readiness',
    group: 'plan',
    level: 'intermediate',
  },

  // ── Build ─────────────────────────────────────────────────
  {
    id: 'bmad-sprint-planning',
    primaryCommand: 'bmad-sprint-planning',
    description: 'Turn the prepared stories into an execution-ready sprint plan.',
    descriptionKey: 'chat.bmad.commands.bmad-sprint-planning',
    group: 'build',
    level: 'intermediate',
  },
  {
    id: 'bmad-sprint-status',
    primaryCommand: 'bmad-sprint-status',
    description: 'Summarize current sprint progress and route to the next BMad workflow.',
    descriptionKey: 'chat.bmad.commands.bmad-sprint-status',
    group: 'build',
    level: 'intermediate',
  },
  {
    id: 'bmad-create-story',
    primaryCommand: 'bmad-create-story',
    description: 'Prepare the next story before implementation starts.',
    descriptionKey: 'chat.bmad.commands.bmad-create-story',
    group: 'build',
    level: 'intermediate',
  },
  {
    id: 'bmad-dev-story',
    primaryCommand: 'bmad-dev-story',
    description: 'Implement the current story with the expected BMad flow.',
    descriptionKey: 'chat.bmad.commands.bmad-dev-story',
    group: 'build',
    level: 'intermediate',
  },
  {
    id: 'bmad-code-review',
    primaryCommand: 'bmad-code-review',
    description: 'Run the BMad review step after implementation.',
    descriptionKey: 'chat.bmad.commands.bmad-code-review',
    group: 'build',
    level: 'intermediate',
  },
  {
    id: 'bmad-investigate',
    primaryCommand: 'bmad-investigate',
    description: 'Forensic case investigation for bug triage, root cause analysis, and unfamiliar code exploration.',
    descriptionKey: 'chat.bmad.commands.bmad-investigate',
    group: 'build',
    level: 'beginner',
  },
  {
    id: 'bmad-checkpoint-preview',
    primaryCommand: 'bmad-checkpoint-preview',
    description: 'LLM-assisted human-in-the-loop review of changes before proceeding.',
    descriptionKey: 'chat.bmad.commands.bmad-checkpoint-preview',
    group: 'build',
    level: 'intermediate',
  },
  {
    id: 'bmad-qa-generate-e2e-tests',
    primaryCommand: 'bmad-qa-generate-e2e-tests',
    description: 'Generate automated QA or E2E coverage using the project\'s existing test stack.',
    descriptionKey: 'chat.bmad.commands.bmad-qa-generate-e2e-tests',
    group: 'build',
    level: 'intermediate',
  },
  {
    id: 'bmad-retrospective',
    primaryCommand: 'bmad-retrospective',
    description: 'Review completed work, lessons learned, and what to do next at the end of an epic.',
    descriptionKey: 'chat.bmad.commands.bmad-retrospective',
    group: 'build',
    level: 'intermediate',
  },

  // ── Tools ─────────────────────────────────────────────────
  {
    id: 'bmad-help',
    primaryCommand: 'bmad-help',
    description: 'Recommend the next BMad workflow or explain what to do next.',
    descriptionKey: 'chat.bmad.commands.bmad-help',
    group: 'tools',
    level: 'beginner',
  },
  {
    id: 'bmad-quick-dev',
    primaryCommand: 'bmad-quick-dev',
    description: 'Use the lightweight quick-flow for small tasks and simple changes.',
    descriptionKey: 'chat.bmad.commands.bmad-quick-dev',
    group: 'tools',
    level: 'beginner',
  },
  {
    id: 'bmad-spec',
    primaryCommand: 'bmad-spec',
    aliases: ['bmad-quick-spec'],
    description: 'Distill any intent into the SPEC kernel — the canonical contract for downstream work.',
    descriptionKey: 'chat.bmad.commands.bmad-spec',
    group: 'tools',
    level: 'intermediate',
  },
  {
    id: 'bmad-customize',
    primaryCommand: 'bmad-customize',
    description: 'Customize BMad agent behavior and workflow settings via TOML overrides.',
    descriptionKey: 'chat.bmad.commands.bmad-customize',
    group: 'tools',
    level: 'advanced',
  },
  {
    id: 'bmad-document-project',
    primaryCommand: 'bmad-document-project',
    description: 'Document an existing project before deeper planning or delivery work.',
    descriptionKey: 'chat.bmad.commands.bmad-document-project',
    group: 'tools',
    level: 'beginner',
  },
  {
    id: 'bmad-generate-project-context',
    primaryCommand: 'bmad-generate-project-context',
    description: 'Generate lean project context for better agent grounding in brownfield repos.',
    descriptionKey: 'chat.bmad.commands.bmad-generate-project-context',
    group: 'tools',
    level: 'beginner',
  },
  {
    id: 'bmad-correct-course',
    primaryCommand: 'bmad-correct-course',
    description: 'Recover when scope, plan, or implementation has drifted off track.',
    descriptionKey: 'chat.bmad.commands.bmad-correct-course',
    group: 'tools',
    level: 'intermediate',
  },
  {
    id: 'bmad-party-mode',
    primaryCommand: 'bmad-party-mode',
    description: 'Orchestrate multi-agent discussions for diverse perspectives on your project.',
    descriptionKey: 'chat.bmad.commands.bmad-party-mode',
    group: 'tools',
    level: 'advanced',
  },
  {
    id: 'bmad-review-adversarial-general',
    primaryCommand: 'bmad-review-adversarial-general',
    description: 'Stress-test a plan or output with adversarial review to uncover weak spots.',
    descriptionKey: 'chat.bmad.commands.bmad-review-adversarial-general',
    group: 'tools',
    level: 'advanced',
  },
  {
    id: 'bmad-review-edge-case-hunter',
    primaryCommand: 'bmad-review-edge-case-hunter',
    description: 'Search for edge cases, failure modes, and overlooked scenarios.',
    descriptionKey: 'chat.bmad.commands.bmad-review-edge-case-hunter',
    group: 'tools',
    level: 'advanced',
  },
  {
    id: 'bmad-editorial-review-prose',
    primaryCommand: 'bmad-editorial-review-prose',
    description: 'Review prose for clarity, tone, readability, and wording.',
    descriptionKey: 'chat.bmad.commands.bmad-editorial-review-prose',
    group: 'tools',
    level: 'advanced',
  },
  {
    id: 'bmad-editorial-review-structure',
    primaryCommand: 'bmad-editorial-review-structure',
    description: 'Review document structure, sequencing, and overall information flow.',
    descriptionKey: 'chat.bmad.commands.bmad-editorial-review-structure',
    group: 'tools',
    level: 'advanced',
  },
  {
    id: 'bmad-index-docs',
    primaryCommand: 'bmad-index-docs',
    description: 'Generate an index of project documents to speed up later BMad workflows.',
    descriptionKey: 'chat.bmad.commands.bmad-index-docs',
    group: 'tools',
    level: 'advanced',
  },
  {
    id: 'bmad-shard-doc',
    primaryCommand: 'bmad-shard-doc',
    description: 'Split a large document into smaller, easier-to-review sections.',
    descriptionKey: 'chat.bmad.commands.bmad-shard-doc',
    group: 'tools',
    level: 'advanced',
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
    level: definition.level,
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
    group: 'tools',
    level: 'intermediate',
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
