export type MiniMaxCommandGroup = 'core' | 'documents';

export interface MiniMaxCommandPreset {
  id: string;
  command: string;
  description: string;
  descriptionKey: string;
  group: MiniMaxCommandGroup;
}

interface MiniMaxCommandDefinition {
  id: string;
  primaryCommand: string;
  description: string;
  descriptionKey: string;
  group: MiniMaxCommandGroup;
}

export type MiniMaxStatusState =
  | 'loading'
  | 'ready'
  | 'missing'
  | 'partial'
  | 'unsupported'
  | 'error';

export interface MiniMaxStatus {
  state: MiniMaxStatusState;
  installed: boolean;
  hasUpdate?: boolean;
  versionTrackingMissing?: boolean;
  provider: string;
  providerLabel: string;
  commandPrefix: string;
  installedVersion?: string;
  latestVersion?: string;
  projectRoot?: string;
  baseSkillsDir?: string;
  metadataFile?: string;
  availableCommands?: string[];
  skillCount: number;
  selectedSkillCount: number;
  message?: string;
  installCommand?: string;
  error?: string;
}

const MINIMAX_COMMAND_DEFINITIONS: MiniMaxCommandDefinition[] = [
  {
    id: 'fullstack-dev',
    primaryCommand: 'fullstack-dev',
    description: 'Handle business workflows, APIs, permissions, validation, and finance-oriented process changes across the stack.',
    descriptionKey: 'chat.minimax.commands.fullstack-dev',
    group: 'core',
  },
  {
    id: 'minimax-xlsx',
    primaryCommand: 'minimax-xlsx',
    description: 'Design or refactor Excel import/export flows, mapping rules, template validation, and reconciliation outputs.',
    descriptionKey: 'chat.minimax.commands.minimax-xlsx',
    group: 'documents',
  },
  {
    id: 'minimax-pdf',
    primaryCommand: 'minimax-pdf',
    description: 'Generate or refine PDF-based vouchers, statements, receipts, and print-ready financial documents.',
    descriptionKey: 'chat.minimax.commands.minimax-pdf',
    group: 'documents',
  },
  {
    id: 'minimax-docx',
    primaryCommand: 'minimax-docx',
    description: 'Produce DOCX files such as notices, contracts, and policy-style supporting business documents.',
    descriptionKey: 'chat.minimax.commands.minimax-docx',
    group: 'documents',
  },
];

function resolveAvailableCommand(
  definition: MiniMaxCommandDefinition,
  availableCommandSet: Set<string>,
): string | null {
  return availableCommandSet.has(definition.primaryCommand) ? definition.primaryCommand : null;
}

export function getMiniMaxCommandPrefix(provider?: string): string {
  return provider === 'codex' ? '$' : '/';
}

export function formatMiniMaxCommand(command: string, provider?: string): string {
  if (!command) {
    return '';
  }
  if (command.startsWith('$') || command.startsWith('/')) {
    return command;
  }
  return `${getMiniMaxCommandPrefix(provider)}${command}`;
}

export function getMiniMaxCommandPresets(availableCommands?: string[]): MiniMaxCommandPreset[] {
  const availableCommandSet = new Set(availableCommands ?? []);
  return MINIMAX_COMMAND_DEFINITIONS.map((definition) => ({
    id: definition.id,
    command: resolveAvailableCommand(definition, availableCommandSet) ?? definition.primaryCommand,
    description: definition.description,
    descriptionKey: definition.descriptionKey,
    group: definition.group,
  }));
}

export function createDefaultMiniMaxStatus(provider?: string): MiniMaxStatus {
  const supported = provider === 'claude' || provider === 'codex';
  return {
    state: supported ? 'loading' : 'unsupported',
    installed: false,
    provider: provider ?? 'claude',
    providerLabel: provider === 'codex' ? 'Codex' : provider === 'claude' ? 'Claude Code' : 'Unsupported',
    commandPrefix: getMiniMaxCommandPrefix(provider),
    hasUpdate: false,
    versionTrackingMissing: false,
    availableCommands: [],
    skillCount: 0,
    selectedSkillCount: MINIMAX_COMMAND_DEFINITIONS.length,
  };
}

export function isMiniMaxProviderSupported(provider?: string): boolean {
  return provider === 'claude' || provider === 'codex';
}
