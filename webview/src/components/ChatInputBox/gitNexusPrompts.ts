export type GitNexusPromptGroup = 'understand' | 'change';

export interface GitNexusPromptPreset {
  id: string;
  title: string;
  titleKey: string;
  prompt: string;
  promptKey: string;
  description: string;
  descriptionKey: string;
  group: GitNexusPromptGroup;
}

export type GitNexusStatusState =
  | 'loading'
  | 'ready'
  | 'missing'
  | 'partial'
  | 'unsupported'
  | 'error';

export interface GitNexusStatus {
  state: GitNexusStatusState;
  installed: boolean;
  hasUpdate?: boolean;
  versionTrackingMissing?: boolean;
  provider: string;
  providerLabel: string;
  installedVersion?: string;
  latestVersion?: string;
  workspaceRoot?: string;
  projectRoot?: string;
  indexDir?: string;
  currentFile?: string;
  currentDirectory?: string;
  currentModuleRoot?: string;
  repositoryDetected: boolean;
  indexDirExists: boolean;
  registryExists: boolean;
  installCommand?: string;
  setupHintCommand?: string;
  analyzeHintCommand?: string;
  nodeAvailable?: boolean;
  nodeSupported?: boolean;
  nodeVersion?: string;
  nodePath?: string;
  runtimeBootstrapSupported?: boolean;
  /** Index directory size in megabytes, calculated by the backend. */
  indexSizeMb?: number;
  message?: string;
  error?: string;
}

export type GitNexusScope = 'repo' | 'directory' | 'module';

export const GIT_NEXUS_PROMPT_PRESETS: GitNexusPromptPreset[] = [
  {
    id: 'repo-overview',
    title: 'Repo Overview',
    titleKey: 'chat.gitNexus.prompts.repoOverview.title',
    prompt: 'Use GitNexus to map this repository: summarize the main modules, boundaries, key dependencies, and the files or folders I should read first.',
    promptKey: 'chat.gitNexus.prompts.repoOverview.prompt',
    description: 'Quickly build a mental model of the whole repository.',
    descriptionKey: 'chat.gitNexus.prompts.repoOverview.description',
    group: 'understand',
  },
  {
    id: 'call-chain',
    title: 'Call Chain',
    titleKey: 'chat.gitNexus.prompts.callChain.title',
    prompt: 'Use GitNexus to trace the call chain for the feature or function I mention, from entry point to downstream services, files, and important dependencies.',
    promptKey: 'chat.gitNexus.prompts.callChain.prompt',
    description: 'Trace request flow, service chain, and key file links.',
    descriptionKey: 'chat.gitNexus.prompts.callChain.description',
    group: 'understand',
  },
  {
    id: 'architecture-hotspots',
    title: 'Hotspots',
    titleKey: 'chat.gitNexus.prompts.hotspots.title',
    prompt: 'Use GitNexus to identify architecture hotspots in this repository, including highly coupled modules, change-prone areas, and parts that look risky for future maintenance.',
    promptKey: 'chat.gitNexus.prompts.hotspots.prompt',
    description: 'Find coupling, risky modules, and places that deserve refactoring attention.',
    descriptionKey: 'chat.gitNexus.prompts.hotspots.description',
    group: 'understand',
  },
  {
    id: 'change-impact',
    title: 'Change Impact',
    titleKey: 'chat.gitNexus.prompts.changeImpact.title',
    prompt: 'Use GitNexus to analyze the impact of changing the module, class, or API I mention, including likely affected files, upstream callers, downstream consumers, and test areas.',
    promptKey: 'chat.gitNexus.prompts.changeImpact.prompt',
    description: 'Estimate what will break or need updates before making a change.',
    descriptionKey: 'chat.gitNexus.prompts.changeImpact.description',
    group: 'change',
  },
];

export function createDefaultGitNexusStatus(provider?: string): GitNexusStatus {
  const supported = provider === 'claude' || provider === 'codex';
  return {
    state: supported ? 'loading' : 'unsupported',
    installed: false,
    provider: provider ?? 'claude',
    providerLabel: provider === 'codex' ? 'Codex' : provider === 'claude' ? 'Claude Code' : 'Unsupported',
    hasUpdate: false,
    versionTrackingMissing: false,
    repositoryDetected: false,
    indexDirExists: false,
    registryExists: false,
    runtimeBootstrapSupported: typeof navigator !== 'undefined' && navigator.userAgent.includes('Windows'),
  };
}

export function isGitNexusProviderSupported(provider?: string): boolean {
  return provider === 'claude' || provider === 'codex';
}

export function getGitNexusAvailableScopes(status: GitNexusStatus): GitNexusScope[] {
  const scopes: GitNexusScope[] = ['repo'];
  if (status.currentDirectory) {
    scopes.push('directory');
  }
  if (status.currentModuleRoot) {
    scopes.push('module');
  }
  return scopes;
}
