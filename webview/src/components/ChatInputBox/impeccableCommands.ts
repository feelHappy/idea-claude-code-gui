export type ImpeccableCommandGroup =
  | 'create'
  | 'evaluate'
  | 'refine'
  | 'harden'
  | 'system';

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
  // --- Create: Build from scratch ---
  {
    id: 'craft',
    primaryCommand: 'craft',
    description: 'Design and build in one flow — the fastest path from idea to implementation.',
    descriptionKey: 'chat.impeccable.commands.craft',
    group: 'create',
  },
  {
    id: 'shape',
    primaryCommand: 'shape',
    description: 'Discovery-based design brief — clarify goals and constraints before building.',
    descriptionKey: 'chat.impeccable.commands.shape',
    group: 'create',
  },
  {
    id: 'impeccable',
    primaryCommand: 'impeccable',
    legacyPromptName: 'impeccable',
    description: 'Core design intelligence — load the full Impeccable skill context.',
    descriptionKey: 'chat.impeccable.commands.impeccable',
    group: 'create',
    showByDefault: false,
  },
  {
    id: 'teach-impeccable',
    primaryCommand: 'teach-impeccable',
    legacyPromptName: 'teach-impeccable',
    description: 'Train Impeccable on your project brand, tokens, and conventions.',
    descriptionKey: 'chat.impeccable.commands.teach-impeccable',
    group: 'create',
    showByDefault: false,
  },
  // --- Evaluate: Assess quality ---
  {
    id: 'audit',
    primaryCommand: 'audit',
    legacyPromptName: 'audit',
    description: 'Five-dimension quality check — accessibility, performance, theming, responsiveness, semantics.',
    descriptionKey: 'chat.impeccable.commands.audit',
    group: 'evaluate',
  },
  {
    id: 'critique',
    primaryCommand: 'critique',
    legacyPromptName: 'critique',
    description: 'Design review with scoring — surface issues and prioritize what to fix first.',
    descriptionKey: 'chat.impeccable.commands.critique',
    group: 'evaluate',
  },
  // --- Refine: Improve specific dimensions ---
  {
    id: 'polish',
    primaryCommand: 'polish',
    legacyPromptName: 'polish',
    description: 'Final refinement pass — tighten spacing, hierarchy, and interaction details.',
    descriptionKey: 'chat.impeccable.commands.polish',
    group: 'refine',
  },
  {
    id: 'layout',
    primaryCommand: 'layout',
    description: 'Fix spacing and visual rhythm — alignment, gaps, and content flow.',
    descriptionKey: 'chat.impeccable.commands.layout',
    group: 'refine',
  },
  {
    id: 'typeset',
    primaryCommand: 'typeset',
    legacyPromptName: 'typeset',
    description: 'Fix typography — font sizes, line heights, and reading rhythm.',
    descriptionKey: 'chat.impeccable.commands.typeset',
    group: 'refine',
  },
  {
    id: 'colorize',
    primaryCommand: 'colorize',
    legacyPromptName: 'colorize',
    description: 'Rework color usage — stronger palette strategy and better contrast.',
    descriptionKey: 'chat.impeccable.commands.colorize',
    group: 'refine',
  },
  {
    id: 'bolder',
    primaryCommand: 'bolder',
    legacyPromptName: 'bolder',
    description: 'Increase visual impact — make the design more opinionated and expressive.',
    descriptionKey: 'chat.impeccable.commands.bolder',
    group: 'refine',
  },
  {
    id: 'quieter',
    primaryCommand: 'quieter',
    legacyPromptName: 'quieter',
    description: 'Reduce visual noise — strip unnecessary emphasis from busy interfaces.',
    descriptionKey: 'chat.impeccable.commands.quieter',
    group: 'refine',
  },
  {
    id: 'animate',
    primaryCommand: 'animate',
    legacyPromptName: 'animate',
    description: 'Add motion that conveys state — deliberate transitions and feedback.',
    descriptionKey: 'chat.impeccable.commands.animate',
    group: 'refine',
  },
  {
    id: 'delight',
    primaryCommand: 'delight',
    legacyPromptName: 'delight',
    description: 'Add personality — tasteful moments of delight without decorative noise.',
    descriptionKey: 'chat.impeccable.commands.delight',
    group: 'refine',
    showByDefault: false,
  },
  {
    id: 'overdrive',
    primaryCommand: 'overdrive',
    legacyPromptName: 'overdrive',
    description: 'Advanced effects — shaders, physics, and radical visual experiments.',
    descriptionKey: 'chat.impeccable.commands.overdrive',
    group: 'refine',
    showByDefault: false,
  },
  // --- Harden: Production readiness ---
  {
    id: 'harden',
    primaryCommand: 'harden',
    legacyPromptName: 'harden',
    description: 'Production-ready — edge cases, i18n, error states, and resilience.',
    descriptionKey: 'chat.impeccable.commands.harden',
    group: 'harden',
  },
  {
    id: 'adapt',
    primaryCommand: 'adapt',
    legacyPromptName: 'adapt',
    description: 'Cross-device responsiveness — adapt the design to different viewports.',
    descriptionKey: 'chat.impeccable.commands.adapt',
    group: 'harden',
  },
  {
    id: 'onboard',
    primaryCommand: 'onboard',
    legacyPromptName: 'onboard',
    description: 'First-run and empty states — guide new users through activation.',
    descriptionKey: 'chat.impeccable.commands.onboard',
    group: 'harden',
  },
  {
    id: 'optimize',
    primaryCommand: 'optimize',
    legacyPromptName: 'optimize',
    description: 'Performance diagnostics — bundle size, render cost, and load speed.',
    descriptionKey: 'chat.impeccable.commands.optimize',
    group: 'harden',
  },
  {
    id: 'clarify',
    primaryCommand: 'clarify',
    legacyPromptName: 'clarify',
    description: 'Rewrite confusing UX copy — labels, errors, and microcopy.',
    descriptionKey: 'chat.impeccable.commands.clarify',
    group: 'harden',
  },
  {
    id: 'distill',
    primaryCommand: 'distill',
    legacyPromptName: 'distill',
    description: 'Strip to essence — remove complexity without losing meaning.',
    descriptionKey: 'chat.impeccable.commands.distill',
    group: 'harden',
    showByDefault: false,
  },
  // --- System: Design infrastructure ---
  {
    id: 'extract',
    primaryCommand: 'extract',
    legacyPromptName: 'extract',
    description: 'Pull reusable components and design tokens from existing screens.',
    descriptionKey: 'chat.impeccable.commands.extract',
    group: 'system',
  },
  {
    id: 'document',
    primaryCommand: 'document',
    description: 'Generate a DESIGN.md specification for the current interface.',
    descriptionKey: 'chat.impeccable.commands.document',
    group: 'system',
  },
  {
    id: 'live',
    primaryCommand: 'live',
    description: 'Browser-based iteration — live preview with variant exploration.',
    descriptionKey: 'chat.impeccable.commands.live',
    group: 'system',
    showByDefault: false,
  },
  {
    id: 'frontend-design',
    primaryCommand: 'frontend-design',
    legacyPromptName: 'frontend-design',
    description: 'Load Impeccable design principles and anti-pattern guidance.',
    descriptionKey: 'chat.impeccable.commands.frontend-design',
    group: 'system',
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
