export type UiUxPromptGroup = 'create' | 'style' | 'review' | 'system';

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
  // --- Create ---
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
    id: 'ecommerce',
    title: 'E-commerce',
    titleKey: 'chat.uiUxPro.prompts.ecommerce.title',
    prompt: 'Use UI UX Pro Max to design an e-commerce product page with conversion-optimized layout, product gallery, pricing section, trust signals, and a clear add-to-cart flow.',
    promptKey: 'chat.uiUxPro.prompts.ecommerce.prompt',
    description: 'Design a conversion-focused product page with gallery, pricing, and CTA.',
    descriptionKey: 'chat.uiUxPro.prompts.ecommerce.description',
    group: 'create',
  },
  // --- Style ---
  {
    id: 'glassmorphism',
    title: 'Glassmorphism',
    titleKey: 'chat.uiUxPro.prompts.glassmorphism.title',
    prompt: 'Use UI UX Pro Max with glassmorphism style to design a modern card-based interface. Apply frosted glass effects, subtle transparency layers, and soft shadows for depth.',
    promptKey: 'chat.uiUxPro.prompts.glassmorphism.prompt',
    description: 'Frosted glass effects with transparency layers and soft depth.',
    descriptionKey: 'chat.uiUxPro.prompts.glassmorphism.description',
    group: 'style',
  },
  {
    id: 'minimalism',
    title: 'Minimalism',
    titleKey: 'chat.uiUxPro.prompts.minimalism.title',
    prompt: 'Use UI UX Pro Max with minimalism style. Focus on generous whitespace, restrained color palette, clean typography, and only essential UI elements. Less is more.',
    promptKey: 'chat.uiUxPro.prompts.minimalism.prompt',
    description: 'Clean whitespace, restrained palette, and only essential elements.',
    descriptionKey: 'chat.uiUxPro.prompts.minimalism.description',
    group: 'style',
  },
  {
    id: 'brutalism',
    title: 'Brutalism',
    titleKey: 'chat.uiUxPro.prompts.brutalism.title',
    prompt: 'Use UI UX Pro Max with brutalism style. Bold raw typography, high-contrast colors, visible borders, unconventional layouts, and intentionally rough aesthetic.',
    promptKey: 'chat.uiUxPro.prompts.brutalism.prompt',
    description: 'Bold raw typography, high contrast, and intentionally rough aesthetic.',
    descriptionKey: 'chat.uiUxPro.prompts.brutalism.description',
    group: 'style',
  },
  {
    id: 'neumorphism',
    title: 'Neumorphism',
    titleKey: 'chat.uiUxPro.prompts.neumorphism.title',
    prompt: 'Use UI UX Pro Max with neumorphism style. Soft extruded shapes, subtle inner/outer shadows on a uniform background, creating a tactile pressed/raised feel.',
    promptKey: 'chat.uiUxPro.prompts.neumorphism.prompt',
    description: 'Soft extruded shapes with inner/outer shadows for tactile depth.',
    descriptionKey: 'chat.uiUxPro.prompts.neumorphism.description',
    group: 'style',
  },
  {
    id: 'aurora-ui',
    title: 'Aurora UI',
    titleKey: 'chat.uiUxPro.prompts.auroraUi.title',
    prompt: 'Use UI UX Pro Max with aurora UI style. Vibrant gradient backgrounds, flowing color transitions, dark base with luminous accents, and an ethereal atmospheric feel.',
    promptKey: 'chat.uiUxPro.prompts.auroraUi.prompt',
    description: 'Vibrant gradients, flowing color transitions, and ethereal atmosphere.',
    descriptionKey: 'chat.uiUxPro.prompts.auroraUi.description',
    group: 'style',
  },
  {
    id: 'bento-grid',
    title: 'Bento Grid',
    titleKey: 'chat.uiUxPro.prompts.bentoGrid.title',
    prompt: 'Use UI UX Pro Max with bento grid layout style. Asymmetric grid cards of varying sizes, clean gaps, feature highlights in larger cells, and a modern Apple-inspired presentation.',
    promptKey: 'chat.uiUxPro.prompts.bentoGrid.prompt',
    description: 'Asymmetric grid cards with varying sizes, Apple-inspired presentation.',
    descriptionKey: 'chat.uiUxPro.prompts.bentoGrid.description',
    group: 'style',
  },
  // --- Review ---
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
    id: 'color-palette',
    title: 'Color Palette',
    titleKey: 'chat.uiUxPro.prompts.colorPalette.title',
    prompt: 'Use UI UX Pro Max to generate a complete color palette for this product. Include primary, secondary, CTA, background, text, and border colors with contrast ratios and dark mode variants.',
    promptKey: 'chat.uiUxPro.prompts.colorPalette.prompt',
    description: 'Generate a full color system with contrast ratios and dark mode support.',
    descriptionKey: 'chat.uiUxPro.prompts.colorPalette.description',
    group: 'review',
  },
  {
    id: 'typography',
    title: 'Typography',
    titleKey: 'chat.uiUxPro.prompts.typography.title',
    prompt: 'Use UI UX Pro Max to recommend a font pairing for this product. Include heading and body fonts from Google Fonts, a type scale, line heights, and Tailwind CSS configuration.',
    promptKey: 'chat.uiUxPro.prompts.typography.prompt',
    description: 'Get font pairing recommendations with type scale and Tailwind config.',
    descriptionKey: 'chat.uiUxPro.prompts.typography.description',
    group: 'review',
  },
  // --- System ---
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
  {
    id: 'responsive',
    title: 'Responsive',
    titleKey: 'chat.uiUxPro.prompts.responsive.title',
    prompt: 'Use UI UX Pro Max to plan a responsive strategy for this interface. Define breakpoints, layout shifts, component adaptations, and touch-friendly adjustments for mobile through desktop.',
    promptKey: 'chat.uiUxPro.prompts.responsive.prompt',
    description: 'Plan breakpoints, layout shifts, and mobile-to-desktop adaptations.',
    descriptionKey: 'chat.uiUxPro.prompts.responsive.description',
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
