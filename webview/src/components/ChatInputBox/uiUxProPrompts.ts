export type UiUxPromptGroup = 'create' | 'review' | 'system';

export interface UiUxPromptPreset {
  id: string;
  title: string;
  titleKey: string;
  prompt: string;
  promptKey: string;
  description: string;
  descriptionKey: string;
  group: UiUxPromptGroup;
}

export type UiUxStatusState =
  | 'loading'
  | 'ready'
  | 'missing'
  | 'partial'
  | 'unsupported'
  | 'error';

export interface UiUxStatus {
  state: UiUxStatusState;
  installed: boolean;
  hasUpdate?: boolean;
  provider: string;
  providerLabel: string;
  installedVersion?: string;
  latestVersion?: string;
  projectRoot?: string;
  skillDir?: string;
  skillDirExists: boolean;
  skillManifestExists: boolean;
  explicitCommand?: string;
  message?: string;
  installCommand?: string;
  nodeAvailable?: boolean;
  nodeSupported?: boolean;
  nodeVersion?: string;
  nodePath?: string;
  pythonAvailable?: boolean;
  pythonSupported?: boolean;
  pythonVersion?: string;
  pythonCommand?: string;
  runtimeBootstrapSupported?: boolean;
  error?: string;
}

export const UI_UX_PRO_PROMPT_PRESETS: UiUxPromptPreset[] = [
  {
    id: 'landing-page',
    title: 'Landing Page',
    titleKey: 'chat.uiUxPro.prompts.landingPage.title',
    prompt: 'Use UI UX Pro Max to design a premium SaaS landing page with clear hierarchy, strong visual contrast, compelling sections, and production-ready frontend implementation details.',
    promptKey: 'chat.uiUxPro.prompts.landingPage.prompt',
    description: 'Generate a polished marketing or product landing page with strong visual direction.',
    descriptionKey: 'chat.uiUxPro.prompts.landingPage.description',
    group: 'create',
  },
  {
    id: 'dashboard',
    title: 'Dashboard',
    titleKey: 'chat.uiUxPro.prompts.dashboard.title',
    prompt: 'Use UI UX Pro Max to design an analytics dashboard with meaningful information hierarchy, sharp card layout, responsive charts, and a practical component structure for implementation.',
    promptKey: 'chat.uiUxPro.prompts.dashboard.prompt',
    description: 'Plan a dashboard UI with layout, visual rhythm, and implementable component ideas.',
    descriptionKey: 'chat.uiUxPro.prompts.dashboard.description',
    group: 'create',
  },
  {
    id: 'mobile-app',
    title: 'Mobile App',
    titleKey: 'chat.uiUxPro.prompts.mobileApp.title',
    prompt: 'Use UI UX Pro Max to design a mobile-first application flow, including the main screens, interaction patterns, accessibility considerations, and a clean design language.',
    promptKey: 'chat.uiUxPro.prompts.mobileApp.prompt',
    description: 'Create mobile product flows and screen-level UI guidance.',
    descriptionKey: 'chat.uiUxPro.prompts.mobileApp.description',
    group: 'create',
  },
  {
    id: 'design-review',
    title: 'Design Review',
    titleKey: 'chat.uiUxPro.prompts.designReview.title',
    prompt: 'Use UI UX Pro Max to review the current page or component for layout, hierarchy, spacing, typography, accessibility, and UX issues, then give concrete redesign suggestions.',
    promptKey: 'chat.uiUxPro.prompts.designReview.prompt',
    description: 'Review an existing page or component and produce actionable UI/UX fixes.',
    descriptionKey: 'chat.uiUxPro.prompts.designReview.description',
    group: 'review',
  },
  {
    id: 'component-refactor',
    title: 'Component Refactor',
    titleKey: 'chat.uiUxPro.prompts.componentRefactor.title',
    prompt: 'Use UI UX Pro Max to refactor this component into a cleaner, more consistent UI with better states, spacing, responsiveness, and clearer interaction feedback.',
    promptKey: 'chat.uiUxPro.prompts.componentRefactor.prompt',
    description: 'Improve one existing component without redesigning the whole product.',
    descriptionKey: 'chat.uiUxPro.prompts.componentRefactor.description',
    group: 'review',
  },
  {
    id: 'design-system',
    title: 'Design System',
    titleKey: 'chat.uiUxPro.prompts.designSystem.title',
    prompt: 'Use UI UX Pro Max to define a lightweight design system for this product, including color tokens, typography scale, spacing, component rules, and implementation guidance.',
    promptKey: 'chat.uiUxPro.prompts.designSystem.prompt',
    description: 'Set up reusable tokens, UI rules, and component standards for a product.',
    descriptionKey: 'chat.uiUxPro.prompts.designSystem.description',
    group: 'system',
  },
];

export function createDefaultUiUxStatus(provider?: string): UiUxStatus {
  const supported = provider === 'claude' || provider === 'codex';
  return {
    state: supported ? 'loading' : 'unsupported',
    installed: false,
    provider: provider ?? 'claude',
    providerLabel: provider === 'codex' ? 'Codex' : provider === 'claude' ? 'Claude Code' : 'Unsupported',
    hasUpdate: false,
    skillDirExists: false,
    skillManifestExists: false,
    explicitCommand: provider === 'codex' ? '$ui-ux-pro-max' : '',
    runtimeBootstrapSupported: typeof navigator !== 'undefined' && navigator.userAgent.includes('Windows'),
  };
}

export function isUiUxProviderSupported(provider?: string): boolean {
  return provider === 'claude' || provider === 'codex';
}
