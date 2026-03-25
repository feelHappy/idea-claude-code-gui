/**
 * useToolkitIntegrations - Encapsulates all Bmad / GitNexus / UiUxPro state & window callbacks.
 *
 * This hook isolates **all** toolkit-addon logic so that upstream ChatInputBox files
 * require only a single hook call + prop pass-through to support the three addons.
 *
 * Bridge events (JS → Java):
 *   get_bmad_status / install_bmad
 *   get_gitnexus_status / install_gitnexus / reindex_gitnexus
 *   get_uiux_pro_status / install_uiux_pro
 *
 * Window callbacks (Java → JS):
 *   window.updateBmadStatus / bmadInstallProgress / bmadInstallResult
 *   window.updateGitNexusStatus / gitNexusInstallProgress / gitNexusInstallResult
 *   window.updateUiUxProStatus / uiUxProInstallProgress / uiUxProInstallResult
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { sendBridgeEvent, sendToJava } from '../../../utils/bridge.js';
import type { BmadToolbarProps, GitNexusToolbarProps, UiUxToolbarProps } from '../types.js';
import {
  type BmadCommandPreset,
  type BmadStatus,
  createDefaultBmadStatus,
  getBmadCommandPrefix,
  getBmadCommandPresets,
  isBmadProviderSupported,
} from '../bmadCommands.js';
import {
  type GitNexusPromptPreset,
  type GitNexusScope,
  type GitNexusStatus,
  createDefaultGitNexusStatus,
  isGitNexusProviderSupported,
  GIT_NEXUS_PROMPT_PRESETS,
} from '../gitNexusPrompts.js';
import {
  type UiUxPromptPreset,
  type UiUxStatus,
  createDefaultUiUxStatus,
  isUiUxProviderSupported,
  UI_UX_PRO_PROMPT_PRESETS,
} from '../uiUxProPrompts.js';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ToolkitIntegrationsResult {
  bmad: BmadToolbarProps | undefined;
  gitNexus: GitNexusToolbarProps | undefined;
  uiUxPro: UiUxToolbarProps | undefined;
}

interface UseToolkitIntegrationsOptions {
  /** Current AI provider id (e.g. 'claude' | 'codex') */
  currentProvider: string;
  /** Callback to insert text into the chat input (for onInsert / onInsertAndSend) */
  insertText: (text: string) => void;
  /** Callback to insert text AND immediately submit */
  insertTextAndSend: (text: string) => void;
  /** Optional toast callback from ChatInputBox */
  addToast?: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;
}

const STATUS_BOOTSTRAP_RETRY_DELAYS_MS = [200, 900, 2500];
type BmadOperation = 'install' | 'update' | null;
type GitNexusOperation = 'install' | 'update' | 'reindex' | null;
type UiUxOperation = 'install' | 'update' | null;

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/** Safely JSON-parse a string, returning null on failure. */
function safeParse<T = unknown>(json: string): T | null {
  try {
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

function findSelectedPreset<T extends { id: string }>(presets: T[], selectedId: string): T | undefined {
  return presets.find((preset) => preset.id === selectedId) ?? presets[0];
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useToolkitIntegrations({
  currentProvider,
  insertText,
  insertTextAndSend,
  addToast,
}: UseToolkitIntegrationsOptions): ToolkitIntegrationsResult {
  const { t, i18n } = useTranslation();
  const sendJsonBridgeEvent = useCallback((event: string, payload: Record<string, unknown>) => {
    return sendBridgeEvent(event, JSON.stringify(payload));
  }, []);

  // =========================================================================
  // BMad state
  // =========================================================================
  const [bmadStatus, setBmadStatus] = useState<BmadStatus>(() => createDefaultBmadStatus(currentProvider));
  const [bmadInstallLog, setBmadInstallLog] = useState('');
  const [bmadOperation, setBmadOperation] = useState<BmadOperation>(null);
  const [bmadSelectedPresetId, setBmadSelectedPresetId] = useState('');

  // =========================================================================
  // GitNexus state
  // =========================================================================
  const [gitNexusStatus, setGitNexusStatus] = useState<GitNexusStatus>(() => createDefaultGitNexusStatus(currentProvider));
  const [gitNexusInstallLog, setGitNexusInstallLog] = useState('');
  const [gitNexusOperation, setGitNexusOperation] = useState<GitNexusOperation>(null);
  const [gitNexusSelectedPresetId, setGitNexusSelectedPresetId] = useState('');
  const [gitNexusScope, setGitNexusScope] = useState<GitNexusScope>('repo');

  // =========================================================================
  // UiUxPro state
  // =========================================================================
  const [uiUxStatus, setUiUxStatus] = useState<UiUxStatus>(() => createDefaultUiUxStatus(currentProvider));
  const [uiUxInstallLog, setUiUxInstallLog] = useState('');
  const [uiUxOperation, setUiUxOperation] = useState<UiUxOperation>(null);
  const [uiUxSelectedPresetId, setUiUxSelectedPresetId] = useState('');
  const bmadRequestOperationRef = useRef<BmadOperation>(null);
  const gitNexusRequestOperationRef = useRef<GitNexusOperation>(null);
  const uiUxRequestOperationRef = useRef<UiUxOperation>(null);

  // Refs for latest state in callbacks
  const bmadSelectedRef = useRef(bmadSelectedPresetId);
  bmadSelectedRef.current = bmadSelectedPresetId;
  const gitNexusSelectedRef = useRef(gitNexusSelectedPresetId);
  gitNexusSelectedRef.current = gitNexusSelectedPresetId;
  const uiUxSelectedRef = useRef(uiUxSelectedPresetId);
  uiUxSelectedRef.current = uiUxSelectedPresetId;
  const bmadStateRef = useRef<BmadStatus['state']>(bmadStatus.state);
  bmadStateRef.current = bmadStatus.state;
  const gitNexusStateRef = useRef<GitNexusStatus['state']>(gitNexusStatus.state);
  gitNexusStateRef.current = gitNexusStatus.state;
  const uiUxStateRef = useRef<UiUxStatus['state']>(uiUxStatus.state);
  uiUxStateRef.current = uiUxStatus.state;

  // =========================================================================
  // Derived presets lists
  // =========================================================================
  const bmadPresets: BmadCommandPreset[] = useMemo(
    () => getBmadCommandPresets(bmadStatus.availableCommands),
    [bmadStatus.availableCommands],
  );

  const gitNexusPresets: GitNexusPromptPreset[] = GIT_NEXUS_PROMPT_PRESETS;
  const uiUxPresets: UiUxPromptPreset[] = UI_UX_PRO_PROMPT_PRESETS;

  // =========================================================================
  // Provider support checks
  // =========================================================================
  const bmadSupported = isBmadProviderSupported(currentProvider);
  const gitNexusSupported = isGitNexusProviderSupported(currentProvider);
  const uiUxSupported = isUiUxProviderSupported(currentProvider);
  const toolkitUiLanguage = i18n.resolvedLanguage || i18n.language || 'en';

  useEffect(() => {
    if (bmadPresets.length === 0) {
      if (bmadSelectedPresetId) {
        setBmadSelectedPresetId('');
      }
      return;
    }

    if (!bmadPresets.some((preset) => preset.id === bmadSelectedPresetId)) {
      setBmadSelectedPresetId(bmadPresets[0].id);
    }
  }, [bmadPresets, bmadSelectedPresetId]);

  useEffect(() => {
    if (gitNexusPresets.length === 0) {
      if (gitNexusSelectedPresetId) {
        setGitNexusSelectedPresetId('');
      }
      return;
    }

    if (!gitNexusPresets.some((preset) => preset.id === gitNexusSelectedPresetId)) {
      setGitNexusSelectedPresetId(gitNexusPresets[0].id);
    }
  }, [gitNexusPresets, gitNexusSelectedPresetId]);

  useEffect(() => {
    if (uiUxPresets.length === 0) {
      if (uiUxSelectedPresetId) {
        setUiUxSelectedPresetId('');
      }
      return;
    }

    if (!uiUxPresets.some((preset) => preset.id === uiUxSelectedPresetId)) {
      setUiUxSelectedPresetId(uiUxPresets[0].id);
    }
  }, [uiUxPresets, uiUxSelectedPresetId]);

  // =========================================================================
  // Window callback registration (Java → JS)
  // =========================================================================
  useEffect(() => {
    const isCurrentProvider = (provider?: string) => !provider || provider === currentProvider;

    // --- BMad ---
    const previousUpdateBmadStatus = window.updateBmadStatus;
    const previousBmadInstallProgress = window.bmadInstallProgress;
    const previousBmadInstallResult = window.bmadInstallResult;
    window.updateBmadStatus = (json: string) => {
      const data = safeParse<Partial<BmadStatus>>(json);
      if (data) {
        const providerId = typeof data.provider === 'string' ? data.provider : currentProvider;
        if (!isCurrentProvider(providerId)) {
          return;
        }
        setBmadStatus({
          ...createDefaultBmadStatus(providerId),
          ...data,
        });
        if (data.state && data.state !== 'loading' && !bmadRequestOperationRef.current) {
          setBmadOperation(null);
        }
      }
      if (previousUpdateBmadStatus && previousUpdateBmadStatus !== window.updateBmadStatus) {
        previousUpdateBmadStatus(json);
      }
    };
    window.bmadInstallProgress = (json: string) => {
      const data = safeParse<{ provider?: string; log?: string }>(json);
      if (!isCurrentProvider(data?.provider)) {
        return;
      }
      if (data?.log) {
        setBmadInstallLog((prev) => (prev ? prev + '\n' + data.log : data.log!));
      }
      if (previousBmadInstallProgress && previousBmadInstallProgress !== window.bmadInstallProgress) {
        previousBmadInstallProgress(json);
      }
    };
    window.bmadInstallResult = (json: string) => {
      const data = safeParse<{
        success?: boolean;
        busy?: boolean;
        provider?: string;
        logs?: string;
        error?: string;
        message?: string;
      }>(json);
      if (!isCurrentProvider(data?.provider)) {
        return;
      }
      if (data) {
        const operation = bmadRequestOperationRef.current;
        const providerId = typeof data.provider === 'string' ? data.provider : currentProvider;
        const providerLabel = t(`providers.${providerId}.label`, { defaultValue: providerId });
        const detailMessage = data.logs?.trim() || data.message || data.error || '';
        bmadRequestOperationRef.current = null;
        setBmadOperation(null);
        if (data.busy) {
          const busyMessage = t('chat.bmad.installBusy', {
            defaultValue: 'Another BMad installation is already running. Please wait and retry.',
          });
          setBmadInstallLog(busyMessage);
          addToast?.(busyMessage, 'info');
          sendToJava('get_bmad_status', { provider: currentProvider, language: toolkitUiLanguage });
          return;
        }

        setBmadInstallLog(detailMessage);
        if (data.success) {
          addToast?.(
            t(operation === 'update' ? 'chat.bmad.updateSuccess' : 'chat.bmad.installSuccess', {
              defaultValue: operation === 'update'
                ? 'BMad has been updated for {{provider}}.'
                : 'BMad is ready for {{provider}}.',
              provider: providerLabel,
            }),
            'success',
          );
          sendToJava('refresh_slash_commands');
        } else {
          addToast?.(
            detailMessage
              ? t('chat.bmad.installFailedWithReason', {
                  defaultValue: 'BMad installation failed: {{reason}}',
                  reason: detailMessage,
                })
              : t('chat.bmad.installFailed', {
                  defaultValue: 'BMad installation failed',
                }),
            'error',
          );
        }
        sendToJava('get_bmad_status', { provider: currentProvider, language: toolkitUiLanguage });
      } else {
        bmadRequestOperationRef.current = null;
        setBmadOperation(null);
        addToast?.(
          t('chat.bmad.resultParseFailed', {
            defaultValue: 'Failed to process the BMad installation result.',
          }),
          'error',
        );
        sendToJava('get_bmad_status', { provider: currentProvider, language: toolkitUiLanguage });
      }
      if (previousBmadInstallResult && previousBmadInstallResult !== window.bmadInstallResult) {
        previousBmadInstallResult(json);
      }
    };

    // --- GitNexus ---
    const previousUpdateGitNexusStatus = window.updateGitNexusStatus;
    const previousGitNexusInstallProgress = window.gitNexusInstallProgress;
    const previousGitNexusInstallResult = window.gitNexusInstallResult;
    window.updateGitNexusStatus = (json: string) => {
      const data = safeParse<Partial<GitNexusStatus>>(json);
      if (data) {
        const providerId = typeof data.provider === 'string' ? data.provider : currentProvider;
        if (!isCurrentProvider(providerId)) {
          return;
        }
        setGitNexusStatus({
          ...createDefaultGitNexusStatus(providerId),
          ...data,
        });
        if (data.state && data.state !== 'loading' && !gitNexusRequestOperationRef.current) {
          setGitNexusOperation(null);
        }
      }
      if (previousUpdateGitNexusStatus && previousUpdateGitNexusStatus !== window.updateGitNexusStatus) {
        previousUpdateGitNexusStatus(json);
      }
    };
    window.gitNexusInstallProgress = (json: string) => {
      const data = safeParse<{ provider?: string; log?: string }>(json);
      if (!isCurrentProvider(data?.provider)) {
        return;
      }
      if (data?.log) {
        setGitNexusInstallLog((prev) => (prev ? prev + '\n' + data.log : data.log!));
      }
      if (previousGitNexusInstallProgress && previousGitNexusInstallProgress !== window.gitNexusInstallProgress) {
        previousGitNexusInstallProgress(json);
      }
    };
    window.gitNexusInstallResult = (json: string) => {
      const data = safeParse<{ success: boolean; busy?: boolean; provider?: string; error?: string; message?: string; logs?: string }>(json);
      if (!isCurrentProvider(data?.provider)) {
        return;
      }
      if (data) {
        const operation = gitNexusRequestOperationRef.current;
        const providerId = typeof data.provider === 'string' ? data.provider : currentProvider;
        const providerLabel = t(`providers.${providerId}.label`, { defaultValue: providerId });
        const detailMessage = data.logs?.trim() || data.message || data.error || '';
        gitNexusRequestOperationRef.current = null;
        setGitNexusOperation(null);
        if (detailMessage) {
          setGitNexusInstallLog(detailMessage);
        }
        if (data.busy) {
          addToast?.(
            t('chat.gitNexus.installBusy', {
              defaultValue: 'Another GitNexus task is already running. Please wait and retry.',
            }),
            'info',
          );
          sendJsonBridgeEvent('get_gitnexus_status', { provider: currentProvider, language: toolkitUiLanguage });
          return;
        }
        if (data.success) {
          const messageKey =
            operation === 'update'
              ? 'chat.gitNexus.updateSuccess'
              : operation === 'reindex'
                ? 'chat.gitNexus.reindexSuccess'
                : 'chat.gitNexus.installSuccess';
          addToast?.(
            t(messageKey, {
              defaultValue:
                operation === 'update'
                  ? 'GitNexus has been updated for {{provider}}.'
                  : operation === 'reindex'
                    ? 'GitNexus has rebuilt the index for {{provider}}.'
                    : 'GitNexus is ready for {{provider}}.',
              provider: providerLabel,
            }),
            'success',
          );
        } else {
          addToast?.(
            detailMessage
              ? t('chat.gitNexus.installFailedWithReason', {
                  defaultValue: 'GitNexus task failed: {{reason}}',
                  reason: detailMessage,
                })
              : t('chat.gitNexus.installFailed', {
                  defaultValue: 'GitNexus task failed',
                }),
            'error',
          );
        }
        if (!data.busy) {
          sendJsonBridgeEvent('get_gitnexus_status', { provider: currentProvider, language: toolkitUiLanguage });
        }
      } else {
        gitNexusRequestOperationRef.current = null;
        setGitNexusOperation(null);
        addToast?.(
          t('chat.gitNexus.resultParseFailed', {
            defaultValue: 'Failed to process the GitNexus task result.',
          }),
          'error',
        );
        sendJsonBridgeEvent('get_gitnexus_status', { provider: currentProvider, language: toolkitUiLanguage });
      }
      if (previousGitNexusInstallResult && previousGitNexusInstallResult !== window.gitNexusInstallResult) {
        previousGitNexusInstallResult(json);
      }
    };

    // --- UiUxPro ---
    const previousUpdateUiUxProStatus = window.updateUiUxProStatus;
    const previousUiUxProInstallProgress = window.uiUxProInstallProgress;
    const previousUiUxProInstallResult = window.uiUxProInstallResult;
    window.updateUiUxProStatus = (json: string) => {
      const data = safeParse<Partial<UiUxStatus>>(json);
      if (data) {
        const providerId = typeof data.provider === 'string' ? data.provider : currentProvider;
        if (!isCurrentProvider(providerId)) {
          return;
        }
        setUiUxStatus({
          ...createDefaultUiUxStatus(providerId),
          ...data,
        });
        if (data.state && data.state !== 'loading' && !uiUxRequestOperationRef.current) {
          setUiUxOperation(null);
        }
      }
      if (previousUpdateUiUxProStatus && previousUpdateUiUxProStatus !== window.updateUiUxProStatus) {
        previousUpdateUiUxProStatus(json);
      }
    };
    window.uiUxProInstallProgress = (json: string) => {
      const data = safeParse<{ provider?: string; log?: string }>(json);
      if (!isCurrentProvider(data?.provider)) {
        return;
      }
      if (data?.log) {
        setUiUxInstallLog((prev) => (prev ? prev + '\n' + data.log : data.log!));
      }
      if (previousUiUxProInstallProgress && previousUiUxProInstallProgress !== window.uiUxProInstallProgress) {
        previousUiUxProInstallProgress(json);
      }
    };
    window.uiUxProInstallResult = (json: string) => {
      const data = safeParse<{ success: boolean; busy?: boolean; provider?: string; error?: string; message?: string; logs?: string }>(json);
      if (!isCurrentProvider(data?.provider)) {
        return;
      }
      if (data) {
        const operation = uiUxRequestOperationRef.current;
        const providerId = typeof data.provider === 'string' ? data.provider : currentProvider;
        const providerLabel = t(`providers.${providerId}.label`, { defaultValue: providerId });
        const detailMessage = data.logs?.trim() || data.message || data.error || '';
        uiUxRequestOperationRef.current = null;
        setUiUxOperation(null);
        if (detailMessage) {
          setUiUxInstallLog(detailMessage);
        }
        if (data.busy) {
          addToast?.(
            t('chat.uiUxPro.installBusy', {
              defaultValue: 'Another UI UX Pro Max task is already running. Please wait and retry.',
            }),
            'info',
          );
          sendJsonBridgeEvent('get_uiux_pro_status', { provider: currentProvider, language: toolkitUiLanguage });
          return;
        }
        if (data.success) {
          addToast?.(
            t(operation === 'update' ? 'chat.uiUxPro.updateSuccess' : 'chat.uiUxPro.installSuccess', {
              defaultValue: operation === 'update'
                ? 'UI UX Pro Max has been updated for {{provider}}.'
                : 'UI UX Pro Max is ready for {{provider}}.',
              provider: providerLabel,
            }),
            'success',
          );
        } else {
          addToast?.(
            detailMessage
              ? t('chat.uiUxPro.installFailedWithReason', {
                  defaultValue: 'UI UX Pro Max task failed: {{reason}}',
                  reason: detailMessage,
                })
              : t('chat.uiUxPro.installFailed', {
                  defaultValue: 'UI UX Pro Max task failed',
                }),
            'error',
          );
        }
        if (!data.busy) {
          sendJsonBridgeEvent('get_uiux_pro_status', { provider: currentProvider, language: toolkitUiLanguage });
        }
      } else {
        uiUxRequestOperationRef.current = null;
        setUiUxOperation(null);
        addToast?.(
          t('chat.uiUxPro.resultParseFailed', {
            defaultValue: 'Failed to process the UI UX Pro Max task result.',
          }),
          'error',
        );
        sendJsonBridgeEvent('get_uiux_pro_status', { provider: currentProvider, language: toolkitUiLanguage });
      }
      if (previousUiUxProInstallResult && previousUiUxProInstallResult !== window.uiUxProInstallResult) {
        previousUiUxProInstallResult(json);
      }
    };

    return () => {
      window.updateBmadStatus = previousUpdateBmadStatus;
      window.bmadInstallProgress = previousBmadInstallProgress;
      window.bmadInstallResult = previousBmadInstallResult;
      window.updateGitNexusStatus = previousUpdateGitNexusStatus;
      window.gitNexusInstallProgress = previousGitNexusInstallProgress;
      window.gitNexusInstallResult = previousGitNexusInstallResult;
      window.updateUiUxProStatus = previousUpdateUiUxProStatus;
      window.uiUxProInstallProgress = previousUiUxProInstallProgress;
      window.uiUxProInstallResult = previousUiUxProInstallResult;
    };
  }, [addToast, currentProvider, sendJsonBridgeEvent, t, toolkitUiLanguage]);

  // =========================================================================
  // Initial status fetch on provider change
  // =========================================================================
  useEffect(() => {
    const cleanupFns: Array<() => void> = [];
    const bootstrapStatusRequest = (
      event: string,
      payload: Record<string, unknown>,
      stateRef: React.MutableRefObject<'loading' | 'ready' | 'missing' | 'partial' | 'unsupported' | 'error'>,
    ) => {
      sendJsonBridgeEvent(event, payload);
      const timers = STATUS_BOOTSTRAP_RETRY_DELAYS_MS.map((delay) => window.setTimeout(() => {
        if (stateRef.current === 'loading') {
          sendJsonBridgeEvent(event, payload);
        }
      }, delay));
      cleanupFns.push(() => {
        timers.forEach((timer) => window.clearTimeout(timer));
      });
    };

    if (bmadSupported) {
      const nextStatus = createDefaultBmadStatus(currentProvider);
      setBmadStatus(nextStatus);
      bmadStateRef.current = nextStatus.state;
      setBmadInstallLog('');
      setBmadOperation(null);
      bmadRequestOperationRef.current = null;
      bootstrapStatusRequest('get_bmad_status', { provider: currentProvider, language: toolkitUiLanguage }, bmadStateRef);
    } else {
      const nextStatus = createDefaultBmadStatus(currentProvider);
      setBmadStatus(nextStatus);
      bmadStateRef.current = nextStatus.state;
      setBmadInstallLog('');
      setBmadOperation(null);
      bmadRequestOperationRef.current = null;
    }
    if (gitNexusSupported) {
      const nextStatus = createDefaultGitNexusStatus(currentProvider);
      setGitNexusStatus(nextStatus);
      gitNexusStateRef.current = nextStatus.state;
      setGitNexusInstallLog('');
      setGitNexusOperation(null);
      gitNexusRequestOperationRef.current = null;
      bootstrapStatusRequest('get_gitnexus_status', { provider: currentProvider, language: toolkitUiLanguage }, gitNexusStateRef);
    } else {
      const nextStatus = createDefaultGitNexusStatus(currentProvider);
      setGitNexusStatus(nextStatus);
      gitNexusStateRef.current = nextStatus.state;
      setGitNexusInstallLog('');
      setGitNexusOperation(null);
      gitNexusRequestOperationRef.current = null;
    }
    if (uiUxSupported) {
      const nextStatus = createDefaultUiUxStatus(currentProvider);
      setUiUxStatus(nextStatus);
      uiUxStateRef.current = nextStatus.state;
      setUiUxInstallLog('');
      setUiUxOperation(null);
      uiUxRequestOperationRef.current = null;
      bootstrapStatusRequest('get_uiux_pro_status', { provider: currentProvider, language: toolkitUiLanguage }, uiUxStateRef);
    } else {
      const nextStatus = createDefaultUiUxStatus(currentProvider);
      setUiUxStatus(nextStatus);
      uiUxStateRef.current = nextStatus.state;
      setUiUxInstallLog('');
      setUiUxOperation(null);
      uiUxRequestOperationRef.current = null;
    }
    return () => {
      cleanupFns.forEach((cleanup) => cleanup());
    };
  }, [currentProvider, bmadSupported, gitNexusSupported, sendJsonBridgeEvent, toolkitUiLanguage, uiUxSupported]);

  // =========================================================================
  // BMad callbacks
  // =========================================================================
  const bmadRefresh = useCallback(() => {
    sendJsonBridgeEvent('get_bmad_status', { provider: currentProvider, language: toolkitUiLanguage });
  }, [currentProvider, sendJsonBridgeEvent, toolkitUiLanguage]);

  const bmadInstall = useCallback(() => {
    if (!bmadSupported || bmadOperation !== null || bmadRequestOperationRef.current) {
      return;
    }
    bmadRequestOperationRef.current = 'install';
    setBmadOperation('install');
    setBmadInstallLog('');
    sendJsonBridgeEvent('install_bmad', { provider: currentProvider, language: toolkitUiLanguage });
  }, [bmadOperation, bmadSupported, currentProvider, sendJsonBridgeEvent, toolkitUiLanguage]);

  const bmadUpdate = useCallback(() => {
    if (!bmadSupported || bmadOperation !== null || bmadRequestOperationRef.current || bmadStatus.hasUpdate !== true) {
      return;
    }
    bmadRequestOperationRef.current = 'update';
    setBmadOperation('update');
    setBmadInstallLog('');
    sendJsonBridgeEvent('update_bmad', { provider: currentProvider, language: toolkitUiLanguage });
  }, [bmadOperation, bmadStatus.hasUpdate, bmadSupported, currentProvider, sendJsonBridgeEvent, toolkitUiLanguage]);

  const bmadInsert = useCallback(() => {
    const preset = findSelectedPreset(bmadPresets, bmadSelectedRef.current);
    if (preset) {
      const commandPrefix = bmadStatus.commandPrefix || getBmadCommandPrefix(currentProvider);
      insertText(`${commandPrefix}${preset.command} `);
    }
  }, [bmadPresets, bmadStatus.commandPrefix, currentProvider, insertText]);

  const bmadInsertAndSend = useCallback(() => {
    const preset = findSelectedPreset(bmadPresets, bmadSelectedRef.current);
    if (preset) {
      const commandPrefix = bmadStatus.commandPrefix || getBmadCommandPrefix(currentProvider);
      insertTextAndSend(`${commandPrefix}${preset.command}`);
    }
  }, [bmadPresets, bmadStatus.commandPrefix, currentProvider, insertTextAndSend]);

  // =========================================================================
  // GitNexus callbacks
  // =========================================================================
  const gitNexusRefresh = useCallback(() => {
    sendJsonBridgeEvent('get_gitnexus_status', { provider: currentProvider, language: toolkitUiLanguage });
  }, [currentProvider, sendJsonBridgeEvent, toolkitUiLanguage]);

  const gitNexusInstall = useCallback(() => {
    if (!gitNexusSupported || gitNexusOperation !== null || gitNexusRequestOperationRef.current) {
      return;
    }
    gitNexusRequestOperationRef.current = 'install';
    setGitNexusOperation('install');
    setGitNexusInstallLog('');
    sendJsonBridgeEvent('install_gitnexus', { provider: currentProvider, language: toolkitUiLanguage });
  }, [currentProvider, gitNexusOperation, gitNexusSupported, sendJsonBridgeEvent, toolkitUiLanguage]);

  const gitNexusUpdate = useCallback(() => {
    if (!gitNexusSupported || gitNexusOperation !== null || gitNexusRequestOperationRef.current || gitNexusStatus.hasUpdate !== true) {
      return;
    }
    gitNexusRequestOperationRef.current = 'update';
    setGitNexusOperation('update');
    setGitNexusInstallLog('');
    sendJsonBridgeEvent('update_gitnexus', { provider: currentProvider, language: toolkitUiLanguage });
  }, [currentProvider, gitNexusOperation, gitNexusStatus.hasUpdate, gitNexusSupported, sendJsonBridgeEvent, toolkitUiLanguage]);

  const gitNexusReindex = useCallback(() => {
    if (!gitNexusSupported || gitNexusOperation !== null || gitNexusRequestOperationRef.current) {
      return;
    }
    gitNexusRequestOperationRef.current = 'reindex';
    setGitNexusOperation('reindex');
    setGitNexusInstallLog('');
    sendJsonBridgeEvent('reindex_gitnexus', { provider: currentProvider, language: toolkitUiLanguage });
  }, [currentProvider, gitNexusOperation, gitNexusSupported, sendJsonBridgeEvent, toolkitUiLanguage]);

  const gitNexusInsert = useCallback(() => {
    const preset = findSelectedPreset(gitNexusPresets, gitNexusSelectedRef.current);
    if (preset) {
      insertText(t(preset.promptKey, { defaultValue: preset.prompt }));
    }
  }, [gitNexusPresets, insertText, t]);

  const gitNexusInsertAndSend = useCallback(() => {
    const preset = findSelectedPreset(gitNexusPresets, gitNexusSelectedRef.current);
    if (preset) {
      insertTextAndSend(t(preset.promptKey, { defaultValue: preset.prompt }));
    }
  }, [gitNexusPresets, insertTextAndSend, t]);

  // =========================================================================
  // UiUxPro callbacks
  // =========================================================================
  const uiUxRefresh = useCallback(() => {
    sendJsonBridgeEvent('get_uiux_pro_status', { provider: currentProvider, language: toolkitUiLanguage });
  }, [currentProvider, sendJsonBridgeEvent, toolkitUiLanguage]);

  const uiUxInstall = useCallback(() => {
    if (!uiUxSupported || uiUxOperation !== null || uiUxRequestOperationRef.current) {
      return;
    }
    uiUxRequestOperationRef.current = 'install';
    setUiUxOperation('install');
    setUiUxInstallLog('');
    sendJsonBridgeEvent('install_uiux_pro', { provider: currentProvider, language: toolkitUiLanguage });
  }, [currentProvider, sendJsonBridgeEvent, toolkitUiLanguage, uiUxOperation, uiUxSupported]);

  const uiUxUpdate = useCallback(() => {
    if (!uiUxSupported || uiUxOperation !== null || uiUxRequestOperationRef.current || uiUxStatus.hasUpdate !== true) {
      return;
    }
    uiUxRequestOperationRef.current = 'update';
    setUiUxOperation('update');
    setUiUxInstallLog('');
    sendJsonBridgeEvent('update_uiux_pro', { provider: currentProvider, language: toolkitUiLanguage });
  }, [currentProvider, sendJsonBridgeEvent, toolkitUiLanguage, uiUxOperation, uiUxStatus.hasUpdate, uiUxSupported]);

  const uiUxInsert = useCallback(() => {
    const preset = findSelectedPreset(uiUxPresets, uiUxSelectedRef.current);
    if (preset) {
      insertText(t(preset.promptKey, { defaultValue: preset.prompt }));
    }
  }, [uiUxPresets, insertText, t]);

  const uiUxInsertAndSend = useCallback(() => {
    const preset = findSelectedPreset(uiUxPresets, uiUxSelectedRef.current);
    if (preset) {
      insertTextAndSend(t(preset.promptKey, { defaultValue: preset.prompt }));
    }
  }, [uiUxPresets, insertTextAndSend, t]);

  // =========================================================================
  // Assemble props objects (memoised to avoid unnecessary re-renders)
  // =========================================================================
  const bmadProps: BmadToolbarProps | undefined = useMemo(() => {
    if (!bmadSupported) return undefined;
    return {
      presets: bmadPresets,
      selectedPresetId: bmadSelectedPresetId,
      status: bmadStatus,
      installLog: bmadInstallLog,
      operation: bmadOperation,
      onPresetChange: setBmadSelectedPresetId,
      onInsert: bmadInsert,
      onInsertAndSend: bmadInsertAndSend,
      onRefresh: bmadRefresh,
      onInstall: bmadInstall,
      onUpdate: bmadUpdate,
      commandDisabled: bmadStatus.state !== 'ready',
      installDisabled: bmadOperation !== null,
      updateDisabled: bmadOperation !== null || bmadStatus.hasUpdate !== true,
    };
  }, [
    bmadSupported, bmadPresets, bmadSelectedPresetId, bmadStatus, bmadInstallLog,
    bmadInsert, bmadInsertAndSend, bmadRefresh, bmadInstall, bmadUpdate, bmadOperation,
  ]);

  const gitNexusProps: GitNexusToolbarProps | undefined = useMemo(() => {
    if (!gitNexusSupported) return undefined;
    return {
      presets: gitNexusPresets,
      selectedPresetId: gitNexusSelectedPresetId,
      status: gitNexusStatus,
      installLog: gitNexusInstallLog,
      operation: gitNexusOperation,
      selectedScope: gitNexusScope,
      onPresetChange: setGitNexusSelectedPresetId,
      onScopeChange: setGitNexusScope,
      onInsert: gitNexusInsert,
      onInsertAndSend: gitNexusInsertAndSend,
      onRefresh: gitNexusRefresh,
      onInstall: gitNexusInstall,
      onUpdate: gitNexusUpdate,
      onReindex: gitNexusReindex,
      promptDisabled: gitNexusStatus.state !== 'ready',
      installDisabled: gitNexusOperation !== null,
      updateDisabled: gitNexusOperation !== null || gitNexusStatus.hasUpdate !== true,
      reindexDisabled: gitNexusOperation !== null,
    };
  }, [
    gitNexusSupported, gitNexusPresets, gitNexusSelectedPresetId, gitNexusStatus,
    gitNexusInstallLog, gitNexusScope, gitNexusInsert, gitNexusInsertAndSend,
    gitNexusRefresh, gitNexusInstall, gitNexusUpdate, gitNexusReindex, gitNexusOperation,
  ]);

  const uiUxProProps: UiUxToolbarProps | undefined = useMemo(() => {
    if (!uiUxSupported) return undefined;
    return {
      presets: uiUxPresets,
      selectedPresetId: uiUxSelectedPresetId,
      status: uiUxStatus,
      installLog: uiUxInstallLog,
      operation: uiUxOperation,
      onPresetChange: setUiUxSelectedPresetId,
      onInsert: uiUxInsert,
      onInsertAndSend: uiUxInsertAndSend,
      onRefresh: uiUxRefresh,
      onInstall: uiUxInstall,
      onUpdate: uiUxUpdate,
      promptDisabled: uiUxStatus.state !== 'ready',
      installDisabled: uiUxOperation !== null,
      updateDisabled: uiUxOperation !== null || uiUxStatus.hasUpdate !== true,
    };
  }, [
    uiUxSupported, uiUxPresets, uiUxSelectedPresetId, uiUxStatus,
    uiUxInstallLog, uiUxInsert, uiUxInsertAndSend, uiUxRefresh,
    uiUxInstall, uiUxOperation, uiUxUpdate,
  ]);

  return { bmad: bmadProps, gitNexus: gitNexusProps, uiUxPro: uiUxProProps };
}
