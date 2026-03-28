export type ImpeccableCommandGroup =
  | 'setup'
  | 'review'
  | 'refine'
  | 'creative';

export interface ImpeccableCommandPreset {
  id: string;
  command: string;
  description: string;
  descriptionKey: string;
  group: ImpeccableCommandGroup;
}

interface ImpeccableCommandDefinition {
  id: string;
  primaryCommand: string;
  legacyPromptName?: string;
  aliases?: string[];
  description: string;
  descriptionKey: string;
  group: ImpeccableCommandGroup;
  showByDefault?: boolean;
}

export type ImpeccableStatusState =
  | 'loading'
  | 'ready'
  | 'missing'
  | 'partial'
  | 'unsupported'
  | 'error';

export interface ImpeccableStatus {
  state: ImpeccableStatusState;
  installed: boolean;
  hasUpdate?: boolean;
  provider: string;
  providerLabel: string;
  commandPrefix: string;
  installedVersion?: string;
  latestVersion?: string;
  projectRoot?: string;
  baseSkillsDir?: string;
  legacyPromptDir?: string;
  metadataFile?: string;
  availableCommands?: string[];
  skillCount: number;
  legacyPromptCount: number;
  hasFrontendDesign: boolean;
  hasTeachCommand: boolean;
  message?: string;
  installCommand?: string;
  error?: string;
}

const IMPECCABLE_COMMAND_DEFINITIONS: ImpeccableCommandDefinition[] = [
  {
    id: 'teach-impeccable',
    primaryCommand: 'teach-impeccable',
    legacyPromptName: 'teach-impeccable',
    description: 'Load Impeccable guidance and bootstrap the design vocabulary before deeper work.',
    descriptionKey: 'chat.impeccable.commands.teach-impeccable',
    group: 'setup',
  },
  {
    id: 'audit',
    primaryCommand: 'audit',
    legacyPromptName: 'audit',
    description: 'Run a technical UI quality audit across accessibility, performance, theming, and responsiveness.',
    descriptionKey: 'chat.impeccable.commands.audit',
    group: 'review',
  },
  {
    id: 'critique',
    primaryCommand: 'critique',
    legacyPromptName: 'critique',
    description: 'Critique the current interface direction and surface design issues with prioritized feedback.',
    descriptionKey: 'chat.impeccable.commands.critique',
    group: 'review',
  },
  {
    id: 'clarify',
    primaryCommand: 'clarify',
    legacyPromptName: 'clarify',
    description: 'Clarify ambiguous design goals, constraints, and tradeoffs before making UI changes.',
    descriptionKey: 'chat.impeccable.commands.clarify',
    group: 'review',
  },
  {
    id: 'normalize',
    primaryCommand: 'normalize',
    legacyPromptName: 'normalize',
    description: 'Normalize inconsistent UI patterns into a cleaner, more systematic design baseline.',
    descriptionKey: 'chat.impeccable.commands.normalize',
    group: 'refine',
  },
  {
    id: 'harden',
    primaryCommand: 'harden',
    legacyPromptName: 'harden',
    description: 'Harden an interface for production with stronger states, accessibility, and resilience details.',
    descriptionKey: 'chat.impeccable.commands.harden',
    group: 'refine',
  },
  {
    id: 'polish',
    primaryCommand: 'polish',
    legacyPromptName: 'polish',
    description: 'Polish an existing screen with tighter spacing, hierarchy, and interaction details.',
    descriptionKey: 'chat.impeccable.commands.polish',
    group: 'refine',
  },
  {
    id: 'typeset',
    primaryCommand: 'typeset',
    legacyPromptName: 'typeset',
    description: 'Improve typography, copy flow, and layout readability for dense product interfaces.',
    descriptionKey: 'chat.impeccable.commands.typeset',
    group: 'refine',
  },
  {
    id: 'arrange',
    primaryCommand: 'arrange',
    legacyPromptName: 'arrange',
    description: 'Reorganize layouts, sections, and content hierarchy into a clearer structure.',
    descriptionKey: 'chat.impeccable.commands.arrange',
    group: 'refine',
  },
  {
    id: 'extract',
    primaryCommand: 'extract',
    legacyPromptName: 'extract',
    description: 'Extract reusable patterns or design rules from a screen or component set.',
    descriptionKey: 'chat.impeccable.commands.extract',
    group: 'refine',
  },
  {
    id: 'adapt',
    primaryCommand: 'adapt',
    legacyPromptName: 'adapt',
    description: 'Adapt an existing design direction to a new product context or brand constraint.',
    descriptionKey: 'chat.impeccable.commands.adapt',
    group: 'creative',
    showByDefault: false,
  },
  {
    id: 'distill',
    primaryCommand: 'distill',
    legacyPromptName: 'distill',
    description: 'Condense complex design feedback into a compact decision-ready summary.',
    descriptionKey: 'chat.impeccable.commands.distill',
    group: 'review',
    showByDefault: false,
  },
  {
    id: 'optimize',
    primaryCommand: 'optimize',
    legacyPromptName: 'optimize',
    description: 'Optimize UI implementation choices for maintainability and production delivery.',
    descriptionKey: 'chat.impeccable.commands.optimize',
    group: 'refine',
    showByDefault: false,
  },
  {
    id: 'quieter',
    primaryCommand: 'quieter',
    legacyPromptName: 'quieter',
    description: 'Reduce visual noise and unnecessary emphasis in busy interfaces.',
    descriptionKey: 'chat.impeccable.commands.quieter',
    group: 'creative',
    showByDefault: false,
  },
  {
    id: 'bolder',
    primaryCommand: 'bolder',
    legacyPromptName: 'bolder',
    description: 'Push the visual direction into a more opinionated and expressive design.',
    descriptionKey: 'chat.impeccable.commands.bolder',
    group: 'creative',
    showByDefault: false,
  },
  {
    id: 'colorize',
    primaryCommand: 'colorize',
    legacyPromptName: 'colorize',
    description: 'Rework color usage and visual contrast with a stronger palette strategy.',
    descriptionKey: 'chat.impeccable.commands.colorize',
    group: 'creative',
    showByDefault: false,
  },
  {
    id: 'delight',
    primaryCommand: 'delight',
    legacyPromptName: 'delight',
    description: 'Add tasteful moments of delight without turning the UI into decorative noise.',
    descriptionKey: 'chat.impeccable.commands.delight',
    group: 'creative',
    showByDefault: false,
  },
  {
    id: 'animate',
    primaryCommand: 'animate',
    legacyPromptName: 'animate',
    description: 'Improve motion design with more deliberate transitions and state changes.',
    descriptionKey: 'chat.impeccable.commands.animate',
    group: 'creative',
    showByDefault: false,
  },
  {
    id: 'overdrive',
    primaryCommand: 'overdrive',
    legacyPromptName: 'overdrive',
    description: 'Explore a maximal redesign direction when you want a more radical visual push.',
    descriptionKey: 'chat.impeccable.commands.overdrive',
    group: 'creative',
    showByDefault: false,
  },
  {
    id: 'onboard',
    primaryCommand: 'onboard',
    legacyPromptName: 'onboard',
    description: 'Design onboarding and first-run flows with clearer guidance and activation steps.',
    descriptionKey: 'chat.impeccable.commands.onboard',
    group: 'creative',
    showByDefault: false,
  },
  {
    id: 'frontend-design',
    primaryCommand: 'frontend-design',
    legacyPromptName: 'frontend-design',
    description: 'Load Impeccable design principles and anti-pattern guidance.',
    descriptionKey: 'chat.impeccable.commands.frontend-design',
    group: 'setup',
    showByDefault: false,
  },
];

function resolveAvailableCommand(
  definition: ImpeccableCommandDefinition,
  availableCommandSet: Set<string>,
): string | null {
  const candidates = [
    definition.primaryCommand,
    ...(definition.aliases ?? []),
    ...(definition.legacyPromptName ? [`/prompts:${definition.legacyPromptName}`] : []),
  ];

  for (const candidate of candidates) {
    if (availableCommandSet.has(candidate)) {
      return candidate;
    }
  }

  return null;
}

export function getImpeccableCommandPrefix(provider?: string): string {
  return provider === 'codex' ? '$' : '/';
}

export function formatImpeccableCommand(command: string, provider?: string): string {
  if (!command) {
    return '';
  }
  if (command.startsWith('$') || command.startsWith('/')) {
    return command;
  }
  return `${getImpeccableCommandPrefix(provider)}${command}`;
}

export function getImpeccableCommandPresets(availableCommands?: string[]): ImpeccableCommandPreset[] {
  const availableCommandSet = new Set(availableCommands ?? []);

  return IMPECCABLE_COMMAND_DEFINITIONS
    .filter((definition) => {
      if (definition.showByDefault !== false) {
        return true;
      }
      return resolveAvailableCommand(definition, availableCommandSet) !== null;
    })
    .map((definition) => ({
      id: definition.id,
      command: resolveAvailableCommand(definition, availableCommandSet) ?? definition.primaryCommand,
      description: definition.description,
      descriptionKey: definition.descriptionKey,
      group: definition.group,
    }));
}

export function createDefaultImpeccableStatus(provider?: string): ImpeccableStatus {
  const supported = provider === 'claude' || provider === 'codex';
  return {
    state: supported ? 'loading' : 'unsupported',
    installed: false,
    provider: provider ?? 'claude',
    providerLabel: provider === 'codex' ? 'Codex' : provider === 'claude' ? 'Claude Code' : 'Unsupported',
    commandPrefix: getImpeccableCommandPrefix(provider),
    hasUpdate: false,
    availableCommands: [],
    skillCount: 0,
    legacyPromptCount: 0,
    hasFrontendDesign: false,
    hasTeachCommand: false,
  };
}

export function isImpeccableProviderSupported(provider?: string): boolean {
  return provider === 'claude' || provider === 'codex';
}
