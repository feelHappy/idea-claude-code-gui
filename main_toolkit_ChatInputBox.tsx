import {
  forwardRef,
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useTranslation } from 'react-i18next';
import type {
  ChatInputBoxHandle,
  ChatInputBoxProps,
  PermissionMode,
} from './types.js';
import { ChatInputBoxHeader } from './ChatInputBoxHeader.js';
import { ChatInputBoxFooter } from './ChatInputBoxFooter.js';
import { ResizeHandles } from './ResizeHandles.js';
import {
  useTextContent,
  useFileTags,
  useTooltip,
  useKeyboardNavigation,
  useIMEComposition,
  usePasteAndDrop,
  usePromptEnhancer,
  useGlobalCallbacks,
  useInputHistory,
  useSubmitHandler,
  useKeyboardHandler,
  useNativeEventCapture,
  useControlledValueSync,
  useChatInputAttachmentsCoordinator,
  useChatInputCompletionsCoordinator,
  useChatInputSelectionController,
  useOpenSourceBannerState,
  useSpaceKeyListener,
  useResizableChatInputBox,
} from './hooks/index.js';
import { debounce } from './utils/debounce.js';
import { insertTextAtCursor, setCursorOffset } from './utils/selectionUtils.js';
import { perfTimer } from '../../utils/debug.js';
import { DEBOUNCE_TIMING } from '../../constants/performance.js';
import { sendToJava } from '../../utils/bridge.js';
import { ContextMenu } from '../ContextMenu';
import { useContextMenu, copySelection, pasteAtCursor, insertNewline } from '../../hooks/useContextMenu.js';
import { useContextMenu, copySelection, cutSelection, pasteAtCursor, insertNewline } from '../../hooks/useContextMenu.js';
import {
  BMAD_COMMAND_PRESETS,
  createDefaultBmadStatus,
  getBmadCommandPresets,
  getBmadCommandPrefix,
  isBmadProviderSupported,
  type BmadStatus,
} from './bmadCommands.js';
import {
  GIT_NEXUS_PROMPT_PRESETS,
  createDefaultGitNexusStatus,
  getGitNexusAvailableScopes,
  isGitNexusProviderSupported,
  type GitNexusScope,
  type GitNexusStatus,
} from './gitNexusPrompts.js';
import {
  UI_UX_PRO_PROMPT_PRESETS,
  createDefaultUiUxStatus,
  isUiUxProviderSupported,
  type UiUxStatus,
} from './uiUxProPrompts.js';
import './styles.css';

/**
 * ChatInputBox - Chat input component
 * Uses contenteditable div with auto height adjustment, IME handling, @ file references, / slash commands
 *
 * Performance optimizations:
 * - Uses uncontrolled mode with useImperativeHandle for minimal re-renders
 * - Debounced onInput callback to reduce parent component updates
 * - Cached getTextContent to avoid repeated DOM traversal
 */
export const ChatInputBox = memo(forwardRef<ChatInputBoxHandle, ChatInputBoxProps>(
  (
    {
      isLoading = false,
      selectedModel = 'claude-sonnet-4-6',
      permissionMode = 'bypassPermissions',
      currentProvider = 'claude',
      usagePercentage = 0,
      usageUsedTokens,
      usageMaxTokens,
      showUsage = true,
      attachments: externalAttachments,
      placeholder = '', // Will be passed from parent via t('chat.inputPlaceholder')
      disabled = false,
      value,
      onSubmit,
      onStop,
      onInput,
      onAddAttachment,
      onRemoveAttachment,
      onModeSelect,
      onModelSelect,
      onProviderSelect,
      reasoningEffort = 'medium',
      onReasoningChange,
      activeFile,
      selectedLines,
      onClearContext,
      alwaysThinkingEnabled,
      onToggleThinking,
      streamingEnabled,
      onStreamingEnabledChange,
      sendShortcut = 'enter',
      selectedAgent,
      onAgentSelect,
      onOpenAgentSettings,
      onOpenPromptSettings,
      onOpenModelSettings,
      hasMessages = false,
      onRewind,
      statusPanelExpanded = true,
      onToggleStatusPanel,
      sdkInstalled = true, // Default to true to avoid disabling input box on initial state
      sdkStatusLoading = false, // SDK status loading state
      onInstallSdk,
      addToast,
      messageQueue,
      onRemoveFromQueue,
      autoOpenFileEnabled,
      onAutoOpenFileEnabledChange,
    }: ChatInputBoxProps,
    ref: React.ForwardedRef<ChatInputBoxHandle>
  ) => {
    const { t, i18n } = useTranslation();

    // Open source banner state (show once, dismiss permanently)
    const BANNER_DISMISSED_KEY = 'openSourceBannerDismissed';
    const [showOpenSourceBanner, setShowOpenSourceBanner] = useState(
      () => !localStorage.getItem(BANNER_DISMISSED_KEY)
    );
    const [selectedBmadPresetId, setSelectedBmadPresetId] = useState(
      BMAD_COMMAND_PRESETS[0]?.id ?? ''
    );
    const [selectedUiUxPresetId, setSelectedUiUxPresetId] = useState(
      UI_UX_PRO_PROMPT_PRESETS[0]?.id ?? ''
    );
    const [selectedGitNexusPresetId, setSelectedGitNexusPresetId] = useState(
      GIT_NEXUS_PROMPT_PRESETS[0]?.id ?? ''
    );
    const [selectedGitNexusScope, setSelectedGitNexusScope] = useState<GitNexusScope>('repo');
    const [bmadStatus, setBmadStatus] = useState<BmadStatus>(() =>
      createDefaultBmadStatus(currentProvider)
    );
    const [gitNexusStatus, setGitNexusStatus] = useState<GitNexusStatus>(() =>
      createDefaultGitNexusStatus(currentProvider)
    );
    const [uiUxStatus, setUiUxStatus] = useState<UiUxStatus>(() =>
      createDefaultUiUxStatus(currentProvider)
    );
    const [bmadInstallLog, setBmadInstallLog] = useState('');
    const [gitNexusInstallLog, setGitNexusInstallLog] = useState('');
    const [uiUxInstallLog, setUiUxInstallLog] = useState('');
    const [isBmadInstalling, setIsBmadInstalling] = useState(false);
    const [isGitNexusInstalling, setIsGitNexusInstalling] = useState(false);
    const [isUiUxInstalling, setIsUiUxInstalling] = useState(false);
    const handleDismissOpenSourceBanner = useCallback(() => {
      localStorage.setItem(BANNER_DISMISSED_KEY, 'true');
      setShowOpenSourceBanner(false);
    }, []);

    // Internal attachments state (if not provided externally)
    const [internalAttachments, setInternalAttachments] = useState<Attachment[]>([]);
    const attachments = externalAttachments ?? internalAttachments;

    // Attachment persistence hook - auto-save/restore attachments from localStorage
    const { clearDraft: clearAttachmentsDraft } = useAttachmentPersistence({
      attachments: internalAttachments,
      isControlled: externalAttachments !== undefined,
      onRestore: setInternalAttachments,
    });

    // Input element refs and state
    const containerRef = useRef<HTMLDivElement>(null);
    const editableRef = useRef<HTMLDivElement>(null);
    const editableWrapperRef = useRef<HTMLDivElement>(null);
    const submittedOnEnterRef = useRef(false);
    const completionSelectedRef = useRef(false);
    const bmadInstallRequestRef = useRef(false);
    const gitNexusInstallRequestRef = useRef(false);
    const uiUxInstallRequestRef = useRef(false);
    const closeAllCompletionsRef = useRef<() => void>(() => {});
    const handleInputRef = useRef<() => void>(() => {});
    const [hasContent, setHasContent] = useState(false);

    // Flag to track if we're updating from external value
    const isExternalUpdateRef = useRef(false);

    // Shared composing state ref - created early so it can be used by detectAndTriggerCompletion
    // This ref is synced with useIMEComposition's isComposingRef
    const sharedComposingRef = useRef(false);

    // Text content hook
    const { getTextContent, invalidateCache } = useTextContent({ editableRef });

    // Close all completions helper
    const closeAllCompletions = useCallback(() => {
      closeAllCompletionsRef.current();
    }, []);

    // File tags hook
    const { renderFileTags, pathMappingRef, justRenderedTagRef, extractFileTags, setCursorAfterPath } = useFileTags({
      editableRef,
      getTextContent,
      onCloseCompletions: closeAllCompletions,
    });

    // Tooltip hook
    const { tooltip, handleMouseOver, handleMouseLeave } = useTooltip();

    // Context menu hook
    const ctxMenu = useContextMenu();

    /**
     * Clear input box
     */
    const clearInput = useCallback(() => {
      if (editableRef.current) {
        editableRef.current.innerHTML = '';
        editableRef.current.style.height = 'auto';
        setHasContent(false);
        // Notify parent component that input is cleared
        onInput?.('');
      }
    }, [onInput]);

    /**
     * Adjust input box height
     * Let contenteditable element expand naturally (height: auto),
     * outer container (.input-editable-wrapper) controls scrolling via max-height and overflow-y.
     * This avoids double scrollbar issue from outer + inner element scrolling.
     */
    const adjustHeight = useCallback(() => {
      const el = editableRef.current;
      if (!el) return;

      // Ensure height is auto, expanded by content
      el.style.height = 'auto';
      // Hide inner scrollbar, completely rely on outer container scrolling
      el.style.overflowY = 'hidden';
    }, []);

    // Create debounced version of renderFileTags
    const debouncedRenderFileTags = useMemo(
      () => debounce(renderFileTags, DEBOUNCE_TIMING.FILE_TAG_RENDERING_MS),
      [renderFileTags]
    );

    const {
      fileCompletion,
      commandCompletion,
      agentCompletion,
      promptCompletion,
      dollarCommandCompletion,
      inlineCompletion,
      debouncedDetectCompletion,
      syncInlineCompletion,
      setRenderFileTags,
    } = useChatInputCompletionsCoordinator({
      editableRef,
      sharedComposingRef,
      justRenderedTagRef,
      getTextContent,
      pathMappingRef,
      setCursorAfterPath,
      closeAllCompletionsRef,
      handleInputRef,
      currentProvider,
      onAgentSelect,
      onOpenAgentSettings,
      onOpenPromptSettings,
    });

    // Performance optimization: Debounced onInput callback
    // Reduces parent component re-renders during rapid typing
    // Also skips during IME composition to prevent parent re-renders that cause JCEF stutter
    const debouncedOnInput = useMemo(
      () =>
        debounce((text: string) => {
          // Skip if this is an external value update to avoid loops
          if (isExternalUpdateRef.current) {
            isExternalUpdateRef.current = false;
            return;
          }
          // Skip during active IME composition to prevent parent re-renders
          // that can disrupt Korean/CJK input in JCEF environments.
          // The update will be triggered after compositionEnd via handleInput.
          if (sharedComposingRef.current) {
            return;
          }
          onInput?.(text);
        }, DEBOUNCE_TIMING.ON_INPUT_CALLBACK_MS),
      [onInput]
    );

    /**
     * Handle input event (optimized: use debounce to reduce performance overhead)
     */
    const handleInput = useCallback(
      () => {
        const timer = perfTimer('handleInput');

        // Only trust our own isComposingRef for IME state detection.
        // JCEF's InputEvent.isComposing is unreliable (can be false during active
        // composition, or true after compositionEnd). Our ref is set synchronously
        // by compositionStart/End and keyCode 229 detection, making it the sole
        // reliable source of truth.
        if (isComposingRef.current) {
          return;
        }

        // Cancel any pending compositionEnd fallback timeout.
        // The normal input event path handles state sync, so the fallback
        // (which would redundantly call handleInput again) is no longer needed.
        // This prevents: 1) double handleInput calls, 2) debouncedOnInput timer
        // reset that delays parent notification by an extra 100ms.
        cancelPendingFallback();

        // Invalidate cache since content changed
        invalidateCache();
        timer.mark('invalidateCache');

        const text = getTextContent();
        timer.mark('getTextContent');

        // Remove zero-width and other invisible characters before checking if empty, ensure placeholder shows when only zero-width characters remain
        const cleanText = text.replace(/[\u200B-\u200D\uFEFF]/g, '');
        const isEmpty = !cleanText.trim();

        // If content is empty, clear innerHTML to ensure :empty pseudo-class works (show placeholder)
        if (isEmpty && editableRef.current) {
          editableRef.current.innerHTML = '';
        }

        // Adjust height
        adjustHeight();
        timer.mark('adjustHeight');

        // Trigger completion detection and state update
        debouncedDetectCompletion();
        setHasContent(!isEmpty);

        // Update inline history completion
        syncInlineCompletion(text);

        // Notify parent component (use debounced version to reduce re-renders)
        // If determined empty (only zero-width characters), pass empty string to parent
        debouncedOnInput(isEmpty ? '' : text);

        timer.end();
      },
      [
        getTextContent,
        adjustHeight,
        debouncedDetectCompletion,
        debouncedOnInput,
        invalidateCache,
        syncInlineCompletion,
      ]
    );

    useEffect(() => {
      handleInputRef.current = handleInput;
    }, [handleInput]);

    // IME composition hook (ref-only, no React state to avoid re-renders during composition)
    const {
      isComposingRef,
      lastCompositionEndTimeRef,
      handleCompositionStart: rawHandleCompositionStart,
      handleCompositionEnd: rawHandleCompositionEnd,
      cancelPendingFallback,
    } = useIMEComposition({
      handleInput,
    });

    // Wrap composition handlers to sync sharedComposingRef (used by completion detection)
    // Both refs are now set synchronously — no RAF, no race conditions.
    const handleCompositionStart = useCallback(() => {
      rawHandleCompositionStart();
      sharedComposingRef.current = true;
    }, [rawHandleCompositionStart]);

    const handleCompositionEnd = useCallback(() => {
      rawHandleCompositionEnd();
      sharedComposingRef.current = false;
    }, [rawHandleCompositionEnd]);

    useEffect(() => {
      setRenderFileTags(renderFileTags);
    }, [renderFileTags, setRenderFileTags]);

    const { record: recordInputHistory, handleKeyDown: handleHistoryKeyDown } = useInputHistory({
      editableRef,
      getTextContent,
      handleInput,
    });

    // Keyboard navigation hook
    const { handleMacCursorMovement } = useKeyboardNavigation({
      editableRef,
      handleInput,
    });

    /**
     * Handle keyboard down event (for detecting space to trigger file tag rendering)
     * Optimized: use debounce for delayed rendering
     */
    const handleKeyDownForTagRendering = useCallback(
      (e: KeyboardEvent) => {
        // If space key pressed, use debounce for delayed file tag rendering
        if (e.key === ' ') {
          debouncedRenderFileTags();
        }
      },
      [debouncedRenderFileTags]
    );

    const handleSubmit = useSubmitHandler({
      getTextContent,
      invalidateCache,
      attachments,
      isLoading,
      sdkStatusLoading,
      sdkInstalled,
      currentProvider,
      clearInput,
      cancelPendingInput: () => {
        debouncedOnInput.cancel();
      },
      externalAttachments,
      setInternalAttachments,
      clearAttachmentsDraft,
      fileCompletion,
      commandCompletion,
      agentCompletion,
      promptCompletion,
      dollarCommandCompletion,
      recordInputHistory,
      onSubmit,
      onInstallSdk,
      addToast,
      t,
    });

    // Prompt enhancer hook
    const {
      isEnhancing,
      showEnhancerDialog,
      originalPrompt,
      enhancedPrompt,
      handleEnhancePrompt,
      handleUseEnhancedPrompt,
      handleKeepOriginalPrompt,
      handleCloseEnhancerDialog,
    } = usePromptEnhancer({
      editableRef,
      getTextContent,
      selectedModel,
      setHasContent,
      onInput,
    });

    const {
      focusInput,
      applyInlineCompletion,
      handleCtxMenuCut,
      handleClearFileContext,
      handleRequestEnableFileContext,
    } = useChatInputSelectionController({
      ref,
      editableRef,
      getTextContent,
      invalidateCache,
      isExternalUpdateRef,
      setHasContent,
      adjustHeight,
      clearInput,
      hasContent,
      extractFileTags,
      inlineCompletion,
      handleInput,
      ctxMenu,
      onClearContext,
      onAutoOpenFileEnabledChange,
    });

    const { onKeyDown: handleKeyDown, onKeyUp: handleKeyUp } = useKeyboardHandler({
      isComposingRef,
      lastCompositionEndTimeRef,
      sendShortcut,
      sdkStatusLoading,
      sdkInstalled,
      fileCompletion,
      commandCompletion,
      agentCompletion,
      promptCompletion,
      dollarCommandCompletion,
      handleMacCursorMovement,
      handleHistoryKeyDown,
      // Inline completion: Tab key applies suggestion
      inlineCompletion: inlineCompletion.hasSuggestion ? {
        applySuggestion: applyInlineCompletion,
      } : undefined,
      completionSelectedRef,
      submittedOnEnterRef,
      handleSubmit,
    });

    useControlledValueSync({
      value,
      editableRef,
      isComposingRef,
      isExternalUpdateRef,
      getTextContent,
      setHasContent,
      adjustHeight,
      invalidateCache,
    });

    useNativeEventCapture({
      editableRef,
      isComposingRef,
      lastCompositionEndTimeRef,
      sendShortcut,
      fileCompletion,
      commandCompletion,
      agentCompletion,
      promptCompletion,
      dollarCommandCompletion,
      completionSelectedRef,
      submittedOnEnterRef,
      handleSubmit,
      handleEnhancePrompt,
    });

    // Listen for IDEA shortcut send event (dispatched by window.execContextAction)
    useEffect(() => {
      const handler = () => {
        if (!isLoading && !isComposingRef.current) {
          handleSubmit();
        }
      };
      document.addEventListener('ideaSend', handler);
      return () => document.removeEventListener('ideaSend', handler);
    }, [handleSubmit, isLoading]);

    // Paste and drop hook
    const { handlePaste, handleDragOver, handleDrop } = usePasteAndDrop({
      editableRef,
      pathMappingRef,
      getTextContent,
      adjustHeight,
      renderFileTags,
      setHasContent,
      setInternalAttachments,
      onInput,
      closeAllCompletions,
      handleInput,
      flushInput: () => {
        debouncedOnInput.flush();
      },
    });

    /**
     * Handle mode select
     */
    const handleModeSelect = useCallback(
      (mode: PermissionMode) => {
        onModeSelect?.(mode);
      },
      [onModeSelect]
    );

    /**
     * Handle model select
     */
    const handleModelSelect = useCallback(
      (modelId: string) => {
        onModelSelect?.(modelId);
      },
      [onModelSelect]
    );

    /**
     * Focus input box
     */
    const focusInput = useCallback(() => {
      editableRef.current?.focus();
    }, []);

    const focusInputToEnd = useCallback(() => {
      if (!editableRef.current) {
        return;
      }

      editableRef.current.focus();
      const range = document.createRange();
      const selection = window.getSelection();
      range.selectNodeContents(editableRef.current);
      range.collapse(false);
      selection?.removeAllRanges();
      selection?.addRange(range);
    }, []);

    useChatInputImperativeHandle({
      ref,
      editableRef,
      getTextContent,
      invalidateCache,
      isExternalUpdateRef,
      setHasContent,
      adjustHeight,
      focusInput,
      clearInput,
      hasContent,
      extractFileTags,
    });

    // Global callbacks hook
    useGlobalCallbacks({
      editableRef,
      pathMappingRef,
      getTextContent,
      adjustHeight,
      renderFileTags,
      setHasContent,
      onInput,
      closeAllCompletions,
      focusInput,
    });

    const bmadUiLanguage = i18n.resolvedLanguage || i18n.language || 'en';
    const bmadPresets = useMemo(
      () => getBmadCommandPresets(bmadStatus.availableCommands),
      [bmadStatus.availableCommands]
    );
    const gitNexusPresets = GIT_NEXUS_PROMPT_PRESETS;
    const uiUxProPresets = UI_UX_PRO_PROMPT_PRESETS;

    const requestBmadStatus = useCallback(() => {
      if (!isBmadProviderSupported(currentProvider)) {
        setBmadStatus(createDefaultBmadStatus(currentProvider));
        setBmadInstallLog('');
        setIsBmadInstalling(false);
        bmadInstallRequestRef.current = false;
        return;
      }

      setBmadStatus(createDefaultBmadStatus(currentProvider));
      setBmadInstallLog('');
      sendToJava('get_bmad_status', {
        provider: currentProvider,
        language: bmadUiLanguage,
      });
    }, [bmadUiLanguage, currentProvider]);

    const handleInstallBmad = useCallback(() => {
      if (!isBmadProviderSupported(currentProvider) || isBmadInstalling || bmadInstallRequestRef.current) {
        return;
      }

      const providerLabel = t(`providers.${currentProvider}.label`, {
        defaultValue: currentProvider,
      });
      bmadInstallRequestRef.current = true;
      setIsBmadInstalling(true);
      setBmadInstallLog(t('chat.bmad.installStart', {
        defaultValue: 'Installing BMad for {{provider}}...',
        provider: providerLabel,
      }));
      sendToJava('install_bmad', {
        provider: currentProvider,
        language: bmadUiLanguage,
      });
    }, [bmadUiLanguage, currentProvider, isBmadInstalling, t]);

    const requestGitNexusStatus = useCallback(() => {
      if (!isGitNexusProviderSupported(currentProvider)) {
        setGitNexusStatus(createDefaultGitNexusStatus(currentProvider));
        setGitNexusInstallLog('');
        setIsGitNexusInstalling(false);
        gitNexusInstallRequestRef.current = false;
        return;
      }

      setGitNexusStatus(createDefaultGitNexusStatus(currentProvider));
      setGitNexusInstallLog('');
      sendToJava('get_gitnexus_status', {
        provider: currentProvider,
        language: bmadUiLanguage,
      });
    }, [bmadUiLanguage, currentProvider]);

    const handleInstallGitNexus = useCallback((forceReindex = false) => {
      if (!isGitNexusProviderSupported(currentProvider) || isGitNexusInstalling || gitNexusInstallRequestRef.current) {
        return;
      }

      const providerLabel = t(`providers.${currentProvider}.label`, {
        defaultValue: currentProvider,
      });
      gitNexusInstallRequestRef.current = true;
      setIsGitNexusInstalling(true);
      setGitNexusInstallLog(t(forceReindex ? 'chat.gitNexus.reindexStart' : 'chat.gitNexus.installStart', {
        defaultValue: forceReindex
          ? 'Rebuilding the GitNexus index for {{provider}}...'
          : 'Installing GitNexus for {{provider}}...',
        provider: providerLabel,
      }));
      sendToJava(forceReindex ? 'reindex_gitnexus' : 'install_gitnexus', {
        provider: currentProvider,
        language: bmadUiLanguage,
      });
    }, [bmadUiLanguage, currentProvider, isGitNexusInstalling, t]);

    const requestUiUxStatus = useCallback(() => {
      if (!isUiUxProviderSupported(currentProvider)) {
        setUiUxStatus(createDefaultUiUxStatus(currentProvider));
        setUiUxInstallLog('');
        setIsUiUxInstalling(false);
        uiUxInstallRequestRef.current = false;
        return;
      }

      setUiUxStatus(createDefaultUiUxStatus(currentProvider));
      setUiUxInstallLog('');
      sendToJava('get_uiux_pro_status', {
        provider: currentProvider,
        language: bmadUiLanguage,
      });
    }, [bmadUiLanguage, currentProvider]);

    const handleInstallUiUx = useCallback(() => {
      if (!isUiUxProviderSupported(currentProvider) || isUiUxInstalling || uiUxInstallRequestRef.current) {
        return;
      }

      const providerLabel = t(`providers.${currentProvider}.label`, {
        defaultValue: currentProvider,
      });
      uiUxInstallRequestRef.current = true;
      setIsUiUxInstalling(true);
      setUiUxInstallLog(t('chat.uiUxPro.installStart', {
        defaultValue: 'Installing UI UX Pro Max for {{provider}}...',
        provider: providerLabel,
      }));
      sendToJava('install_uiux_pro', {
        provider: currentProvider,
        language: bmadUiLanguage,
      });
    }, [bmadUiLanguage, currentProvider, isUiUxInstalling, t]);

    useEffect(() => {
      const previousUpdateBmadStatus = window.updateBmadStatus;
      const previousBmadInstallProgress = window.bmadInstallProgress;
      const previousBmadInstallResult = window.bmadInstallResult;

      window.updateBmadStatus = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr) as Partial<BmadStatus>;
          const nextProvider = typeof data.provider === 'string'
            ? data.provider
            : currentProvider;
          setBmadStatus({
            ...createDefaultBmadStatus(nextProvider),
            ...data,
          });
        } catch (error) {
          console.error('[ChatInputBox] Failed to parse BMad status:', error);
        }

        if (
          previousUpdateBmadStatus &&
          previousUpdateBmadStatus !== window.updateBmadStatus
        ) {
          previousUpdateBmadStatus(jsonStr);
        }
      };

      window.bmadInstallProgress = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr) as { log?: string };
          setIsBmadInstalling(true);
          if (data.log) {
            setBmadInstallLog(data.log);
            setBmadStatus((prev) => ({
              ...prev,
              message: data.log,
            }));
          }
        } catch (error) {
          console.error('[ChatInputBox] Failed to parse BMad install progress:', error);
        }

        if (
          previousBmadInstallProgress &&
          previousBmadInstallProgress !== window.bmadInstallProgress
        ) {
          previousBmadInstallProgress(jsonStr);
        }
      };

      window.bmadInstallResult = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr) as {
            success?: boolean;
            busy?: boolean;
            provider?: string;
            message?: string;
            error?: string;
          };
          const providerId = typeof data.provider === 'string'
            ? data.provider
            : currentProvider;
          const providerLabel = t(`providers.${providerId}.label`, {
            defaultValue: providerId,
          });
          const toastMessage = data.message || data.error;

          if (data.busy) {
            bmadInstallRequestRef.current = false;
            setIsBmadInstalling(false);
            setBmadInstallLog(t('chat.bmad.installBusy', {
              defaultValue: 'Another BMad installation is already running. Please wait and retry.',
            }));
            addToast?.(
              t('chat.bmad.installBusy', {
                defaultValue: 'Another BMad installation is already running. Please wait and retry.',
              }),
              'info'
            );
            requestBmadStatus();
            return;
          }

          bmadInstallRequestRef.current = false;
          setIsBmadInstalling(false);
          setBmadInstallLog(toastMessage ?? '');

          if (data.success) {
            addToast?.(
              t('chat.bmad.installSuccess', {
                defaultValue: 'BMad is ready for {{provider}}.',
                provider: providerLabel,
              }),
              'success'
            );
            sendToJava('refresh_slash_commands');
          } else {
            addToast?.(
              toastMessage
                ? t('chat.bmad.installFailedWithReason', {
                    defaultValue: 'BMad installation failed: {{reason}}',
                    reason: toastMessage,
                  })
                : t('chat.bmad.installFailed', {
                    defaultValue: 'BMad installation failed',
                  }),
              'error'
            );
          }

          requestBmadStatus();
        } catch (error) {
          console.error('[ChatInputBox] Failed to parse BMad install result:', error);
          bmadInstallRequestRef.current = false;
          setIsBmadInstalling(false);
          addToast?.(
            t('chat.bmad.resultParseFailed', {
              defaultValue: 'Failed to process the BMad installation result.',
            }),
            'error'
          );
          requestBmadStatus();
        }

        if (
          previousBmadInstallResult &&
          previousBmadInstallResult !== window.bmadInstallResult
        ) {
          previousBmadInstallResult(jsonStr);
        }
      };

      requestBmadStatus();

      return () => {
        window.updateBmadStatus = previousUpdateBmadStatus;
        window.bmadInstallProgress = previousBmadInstallProgress;
        window.bmadInstallResult = previousBmadInstallResult;
      };
    }, [addToast, currentProvider, requestBmadStatus, t]);

    useEffect(() => {
      const previousUpdateGitNexusStatus = window.updateGitNexusStatus;
      const previousGitNexusInstallProgress = window.gitNexusInstallProgress;
      const previousGitNexusInstallResult = window.gitNexusInstallResult;

      window.updateGitNexusStatus = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr) as Partial<GitNexusStatus>;
          const nextProvider = typeof data.provider === 'string'
            ? data.provider
            : currentProvider;
          if (data.state && data.state !== 'loading' && !gitNexusInstallRequestRef.current) {
            setIsGitNexusInstalling(false);
          }
          setGitNexusStatus({
            ...createDefaultGitNexusStatus(nextProvider),
            ...data,
          });
        } catch (error) {
          console.error('[ChatInputBox] Failed to parse GitNexus status:', error);
        }

        if (
          previousUpdateGitNexusStatus &&
          previousUpdateGitNexusStatus !== window.updateGitNexusStatus
        ) {
          previousUpdateGitNexusStatus(jsonStr);
        }
      };

      window.gitNexusInstallProgress = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr) as { log?: string };
          setIsGitNexusInstalling(true);
          if (data.log) {
            setGitNexusInstallLog(data.log);
            setGitNexusStatus((prev) => ({
              ...prev,
              message: data.log,
            }));
          }
        } catch (error) {
          console.error('[ChatInputBox] Failed to parse GitNexus install progress:', error);
        }

        if (
          previousGitNexusInstallProgress &&
          previousGitNexusInstallProgress !== window.gitNexusInstallProgress
        ) {
          previousGitNexusInstallProgress(jsonStr);
        }
      };

      window.gitNexusInstallResult = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr) as {
            success?: boolean;
            busy?: boolean;
            provider?: string;
            message?: string;
            error?: string;
            logs?: string;
          };
          const providerId = typeof data.provider === 'string'
            ? data.provider
            : currentProvider;
          const providerLabel = t(`providers.${providerId}.label`, {
            defaultValue: providerId,
          });
          const toastMessage = data.message || data.error;
          const detailMessage = data.logs?.trim() || toastMessage || '';

          if (data.busy) {
            gitNexusInstallRequestRef.current = false;
            setIsGitNexusInstalling(false);
            setGitNexusInstallLog(t('chat.gitNexus.installBusy', {
              defaultValue: 'Another GitNexus task is already running. Please wait and retry.',
            }));
            addToast?.(
              t('chat.gitNexus.installBusy', {
                defaultValue: 'Another GitNexus task is already running. Please wait and retry.',
              }),
              'info'
            );
            requestGitNexusStatus();
            return;
          }

          gitNexusInstallRequestRef.current = false;
          setIsGitNexusInstalling(false);
          setGitNexusInstallLog(detailMessage);

          if (data.success) {
            addToast?.(
              t('chat.gitNexus.installSuccess', {
                defaultValue: 'GitNexus is ready for {{provider}}.',
                provider: providerLabel,
              }),
              'success'
            );
          } else {
            addToast?.(
              toastMessage
                ? t('chat.gitNexus.installFailedWithReason', {
                    defaultValue: 'GitNexus installation failed: {{reason}}',
                    reason: toastMessage,
                  })
                : t('chat.gitNexus.installFailed', {
                    defaultValue: 'GitNexus installation failed',
                  }),
              'error'
            );
          }

          requestGitNexusStatus();
        } catch (error) {
          console.error('[ChatInputBox] Failed to parse GitNexus install result:', error);
          gitNexusInstallRequestRef.current = false;
          setIsGitNexusInstalling(false);
          addToast?.(
            t('chat.gitNexus.resultParseFailed', {
              defaultValue: 'Failed to process the GitNexus installation result.',
            }),
            'error'
          );
          requestGitNexusStatus();
        }

        if (
          previousGitNexusInstallResult &&
          previousGitNexusInstallResult !== window.gitNexusInstallResult
        ) {
          previousGitNexusInstallResult(jsonStr);
        }
      };

      requestGitNexusStatus();

      return () => {
        window.updateGitNexusStatus = previousUpdateGitNexusStatus;
        window.gitNexusInstallProgress = previousGitNexusInstallProgress;
        window.gitNexusInstallResult = previousGitNexusInstallResult;
      };
    }, [addToast, currentProvider, requestGitNexusStatus, t]);

    useEffect(() => {
      if (isGitNexusProviderSupported(currentProvider)) {
        requestGitNexusStatus();
      }
    }, [activeFile, currentProvider, requestGitNexusStatus]);

    useEffect(() => {
      const previousUpdateUiUxProStatus = window.updateUiUxProStatus;
      const previousUiUxProInstallProgress = window.uiUxProInstallProgress;
      const previousUiUxProInstallResult = window.uiUxProInstallResult;

      window.updateUiUxProStatus = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr) as Partial<UiUxStatus>;
          const nextProvider = typeof data.provider === 'string'
            ? data.provider
            : currentProvider;
          setUiUxStatus({
            ...createDefaultUiUxStatus(nextProvider),
            ...data,
          });
        } catch (error) {
          console.error('[ChatInputBox] Failed to parse UI UX Pro Max status:', error);
        }

        if (
          previousUpdateUiUxProStatus &&
          previousUpdateUiUxProStatus !== window.updateUiUxProStatus
        ) {
          previousUpdateUiUxProStatus(jsonStr);
        }
      };

      window.uiUxProInstallProgress = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr) as { log?: string };
          setIsUiUxInstalling(true);
          if (data.log) {
            setUiUxInstallLog(data.log);
            setUiUxStatus((prev) => ({
              ...prev,
              message: data.log,
            }));
          }
        } catch (error) {
          console.error('[ChatInputBox] Failed to parse UI UX Pro Max install progress:', error);
        }

        if (
          previousUiUxProInstallProgress &&
          previousUiUxProInstallProgress !== window.uiUxProInstallProgress
        ) {
          previousUiUxProInstallProgress(jsonStr);
        }
      };

      window.uiUxProInstallResult = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr) as {
            success?: boolean;
            busy?: boolean;
            provider?: string;
            message?: string;
            error?: string;
          };
          const providerId = typeof data.provider === 'string'
            ? data.provider
            : currentProvider;
          const providerLabel = t(`providers.${providerId}.label`, {
            defaultValue: providerId,
          });
          const toastMessage = data.message || data.error;

          if (data.busy) {
            uiUxInstallRequestRef.current = false;
            setIsUiUxInstalling(false);
            setUiUxInstallLog(t('chat.uiUxPro.installBusy', {
              defaultValue: 'Another UI UX Pro Max installation is already running. Please wait and retry.',
            }));
            addToast?.(
              t('chat.uiUxPro.installBusy', {
                defaultValue: 'Another UI UX Pro Max installation is already running. Please wait and retry.',
              }),
              'info'
            );
            requestUiUxStatus();
            return;
          }

          uiUxInstallRequestRef.current = false;
          setIsUiUxInstalling(false);
          setUiUxInstallLog(toastMessage ?? '');

          if (data.success) {
            addToast?.(
              t('chat.uiUxPro.installSuccess', {
                defaultValue: 'UI UX Pro Max is ready for {{provider}}.',
                provider: providerLabel,
              }),
              'success'
            );
          } else {
            addToast?.(
              toastMessage
                ? t('chat.uiUxPro.installFailedWithReason', {
                    defaultValue: 'UI UX Pro Max installation failed: {{reason}}',
                    reason: toastMessage,
                  })
                : t('chat.uiUxPro.installFailed', {
                    defaultValue: 'UI UX Pro Max installation failed',
                  }),
              'error'
            );
          }

          requestUiUxStatus();
        } catch (error) {
          console.error('[ChatInputBox] Failed to parse UI UX Pro Max install result:', error);
          uiUxInstallRequestRef.current = false;
          setIsUiUxInstalling(false);
          addToast?.(
            t('chat.uiUxPro.resultParseFailed', {
              defaultValue: 'Failed to process the UI UX Pro Max installation result.',
            }),
            'error'
          );
          requestUiUxStatus();
        }

        if (
          previousUiUxProInstallResult &&
          previousUiUxProInstallResult !== window.uiUxProInstallResult
        ) {
          previousUiUxProInstallResult(jsonStr);
        }
      };

      requestUiUxStatus();

      return () => {
        window.updateUiUxProStatus = previousUpdateUiUxProStatus;
        window.uiUxProInstallProgress = previousUiUxProInstallProgress;
        window.uiUxProInstallResult = previousUiUxProInstallResult;
      };
    }, [addToast, currentProvider, requestUiUxStatus, t]);

    useSpaceKeyListener({ editableRef, onKeyDown: handleKeyDownForTagRendering });

    const {
      isResizing: isResizingInputBox,
      containerStyle,
      editableWrapperStyle,
      getHandleProps,
      nudge,
    } = useResizableChatInputBox({
      containerRef,
      editableWrapperRef,
    });

    const handleCtxMenuCut = useCallback(() => {
      if (!editableRef.current) return;
      cutSelection(ctxMenu.savedRange, ctxMenu.selectedText, editableRef.current, ctxMenu.targetFileTag);
      handleInput();
    }, [ctxMenu.savedRange, ctxMenu.selectedText, ctxMenu.targetFileTag, handleInput]);

    // Combined callback: clear file context AND disable autoOpenFile
    const handleClearFileContext = useCallback(() => {
      onClearContext?.();
      onAutoOpenFileEnabledChange?.(false);
    }, [onClearContext, onAutoOpenFileEnabledChange]);

    // Callback for enabling file context from placeholder
    const handleRequestEnableFileContext = useCallback(() => {
      onAutoOpenFileEnabledChange?.(true);
    }, [onAutoOpenFileEnabledChange]);

    const selectedBmadPreset = useMemo(
      () => bmadPresets.find((preset) => preset.id === selectedBmadPresetId) ?? bmadPresets[0],
      [bmadPresets, selectedBmadPresetId]
    );
    const selectedGitNexusPreset = useMemo(
      () => gitNexusPresets.find((preset) => preset.id === selectedGitNexusPresetId) ?? gitNexusPresets[0],
      [gitNexusPresets, selectedGitNexusPresetId]
    );
    const availableGitNexusScopes = useMemo(
      () => getGitNexusAvailableScopes(gitNexusStatus),
      [gitNexusStatus]
    );
    const selectedUiUxPreset = useMemo(
      () => uiUxProPresets.find((preset) => preset.id === selectedUiUxPresetId) ?? uiUxProPresets[0],
      [selectedUiUxPresetId, uiUxProPresets]
    );
    const isBmadSupported = isBmadProviderSupported(currentProvider);
    const isGitNexusSupported = isGitNexusProviderSupported(currentProvider);
    const isUiUxSupported = isUiUxProviderSupported(currentProvider);
    const bmadCommandPrefix = bmadStatus.commandPrefix || getBmadCommandPrefix(currentProvider);
    const isBmadReady = bmadStatus.state === 'ready';
    const isGitNexusReady = gitNexusStatus.state === 'ready';
    const isUiUxReady = uiUxStatus.state === 'ready';

    useEffect(() => {
      if (bmadPresets.length === 0) {
        return;
      }

      if (!bmadPresets.some((preset) => preset.id === selectedBmadPresetId)) {
        setSelectedBmadPresetId(bmadPresets[0].id);
      }
    }, [bmadPresets, selectedBmadPresetId]);

    useEffect(() => {
      if (uiUxProPresets.length === 0) {
        return;
      }

      if (!uiUxProPresets.some((preset) => preset.id === selectedUiUxPresetId)) {
        setSelectedUiUxPresetId(uiUxProPresets[0].id);
      }
    }, [selectedUiUxPresetId, uiUxProPresets]);

    useEffect(() => {
      if (gitNexusPresets.length === 0) {
        return;
      }

      if (!gitNexusPresets.some((preset) => preset.id === selectedGitNexusPresetId)) {
        setSelectedGitNexusPresetId(gitNexusPresets[0].id);
      }
    }, [gitNexusPresets, selectedGitNexusPresetId]);

    useEffect(() => {
      if (!availableGitNexusScopes.includes(selectedGitNexusScope)) {
        setSelectedGitNexusScope('repo');
      }
    }, [availableGitNexusScopes, selectedGitNexusScope]);

    const insertBmadCommand = useCallback((submitAfterInsert: boolean) => {
      if (!editableRef.current || !selectedBmadPreset || !isBmadReady || !bmadCommandPrefix) {
        return;
      }

      const selection = window.getSelection();
      const hasEditableSelection = !!selection &&
        selection.rangeCount > 0 &&
        editableRef.current.contains(selection.anchorNode);

      if (!hasEditableSelection) {
        focusInputToEnd();
      }

      const currentValue = getTextContent();
      const hasContentAlready = currentValue.trim().length > 0;
      const needsSpacing = hasContentAlready && !hasEditableSelection;
      const prefix = needsSpacing
        ? (currentValue.endsWith('\n') ? '\n' : '\n\n')
        : '';
      const insertionText = `${prefix}${bmadCommandPrefix}${selectedBmadPreset.command} `;

      insertTextAtCursor(insertionText, editableRef.current);
      handleInput();
      editableRef.current.focus();

      if (submitAfterInsert) {
        requestAnimationFrame(() => {
          handleSubmit();
        });
      }
    }, [
      selectedBmadPreset,
      isBmadReady,
      bmadCommandPrefix,
      focusInputToEnd,
      getTextContent,
      handleInput,
      handleSubmit,
    ]);

    const insertUiUxPrompt = useCallback((submitAfterInsert: boolean) => {
      if (!editableRef.current || !selectedUiUxPreset || !isUiUxReady) {
        return;
      }

      const selection = window.getSelection();
      const hasEditableSelection = !!selection &&
        selection.rangeCount > 0 &&
        editableRef.current.contains(selection.anchorNode);

      if (!hasEditableSelection) {
        focusInputToEnd();
      }

      const currentValue = getTextContent();
      const hasContentAlready = currentValue.trim().length > 0;
      const needsSpacing = hasContentAlready && !hasEditableSelection;
      const prefix = needsSpacing
        ? (currentValue.endsWith('\n') ? '\n' : '\n\n')
        : '';
      const promptText = t(selectedUiUxPreset.promptKey, {
        defaultValue: selectedUiUxPreset.prompt,
      });
      const insertionText = `${prefix}${promptText} `;

      insertTextAtCursor(insertionText, editableRef.current);
      handleInput();
      editableRef.current.focus();

      if (submitAfterInsert) {
        requestAnimationFrame(() => {
          handleSubmit();
        });
      }
    }, [
      focusInputToEnd,
      getTextContent,
      handleInput,
      handleSubmit,
      isUiUxReady,
      selectedUiUxPreset,
      t,
    ]);

    const buildScopedGitNexusPrompt = useCallback(() => {
      if (!selectedGitNexusPreset) {
        return '';
      }

      const basePrompt = t(selectedGitNexusPreset.promptKey, {
        defaultValue: selectedGitNexusPreset.prompt,
      });
      if (selectedGitNexusScope === 'directory' && gitNexusStatus.currentDirectory) {
        return t('chat.gitNexus.scopePrompt.directory', {
          defaultValue: 'Limit the analysis to this directory: {{path}}\n\n{{prompt}}',
          path: gitNexusStatus.currentDirectory,
          prompt: basePrompt,
        });
      }
      if (selectedGitNexusScope === 'module' && gitNexusStatus.currentModuleRoot) {
        return t('chat.gitNexus.scopePrompt.module', {
          defaultValue: 'Limit the analysis to this module root: {{path}}\n\n{{prompt}}',
          path: gitNexusStatus.currentModuleRoot,
          prompt: basePrompt,
        });
      }
      if (selectedGitNexusScope === 'repo' && gitNexusStatus.projectRoot) {
        return t('chat.gitNexus.scopePrompt.repo', {
          defaultValue: 'Analyze the whole repository rooted at: {{path}}\n\n{{prompt}}',
          path: gitNexusStatus.projectRoot,
          prompt: basePrompt,
        });
      }
      return basePrompt;
    }, [gitNexusStatus.currentDirectory, gitNexusStatus.currentModuleRoot, gitNexusStatus.projectRoot, selectedGitNexusPreset, selectedGitNexusScope, t]);

    const insertGitNexusPrompt = useCallback((submitAfterInsert: boolean) => {
      if (!editableRef.current || !selectedGitNexusPreset || !isGitNexusReady) {
        return;
      }

      const selection = window.getSelection();
      const hasEditableSelection = !!selection &&
        selection.rangeCount > 0 &&
        editableRef.current.contains(selection.anchorNode);

      if (!hasEditableSelection) {
        focusInputToEnd();
      }

      const currentValue = getTextContent();
      const hasContentAlready = currentValue.trim().length > 0;
      const needsSpacing = hasContentAlready && !hasEditableSelection;
      const prefix = needsSpacing
        ? (currentValue.endsWith('\n') ? '\n' : '\n\n')
        : '';
      const promptText = buildScopedGitNexusPrompt();
      const insertionText = `${prefix}${promptText} `;

      insertTextAtCursor(insertionText, editableRef.current);
      handleInput();
      editableRef.current.focus();

      if (submitAfterInsert) {
        requestAnimationFrame(() => {
          handleSubmit();
        });
      }
    }, [
      focusInputToEnd,
      getTextContent,
      handleInput,
      handleSubmit,
      buildScopedGitNexusPrompt,
      isGitNexusReady,
      selectedGitNexusPreset,
    ]);

    return (
      <div
        className={`chat-input-box ${isResizingInputBox ? 'is-resizing' : ''}`}
        onClick={focusInput}
        ref={containerRef}
        style={containerStyle}
      >
        <ResizeHandles getHandleProps={getHandleProps} nudge={nudge} />

        <ChatInputBoxHeader
          sdkStatusLoading={sdkStatusLoading}
          sdkInstalled={sdkInstalled}
          currentProvider={currentProvider}
          onInstallSdk={onInstallSdk}
          t={t}
          attachments={attachments}
          onRemoveAttachment={handleRemoveAttachment}
          activeFile={activeFile}
          selectedLines={selectedLines}
          usagePercentage={usagePercentage}
          usageUsedTokens={usageUsedTokens}
          usageMaxTokens={usageMaxTokens}
          showUsage={showUsage}
          onClearContext={handleClearFileContext}
          onAddAttachment={handleAddAttachment}
          selectedAgent={selectedAgent}
          onClearAgent={() => onAgentSelect?.(null)}
          hasMessages={hasMessages}
          onRewind={onRewind}
          statusPanelExpanded={statusPanelExpanded}
          onToggleStatusPanel={onToggleStatusPanel}
          messageQueue={messageQueue}
          onRemoveFromQueue={onRemoveFromQueue}
          showOpenSourceBanner={showOpenSourceBanner}
          onDismissOpenSourceBanner={handleDismissOpenSourceBanner}
          autoOpenFileEnabled={autoOpenFileEnabled}
          onRequestEnableFileContext={handleRequestEnableFileContext}
        />

        {/* Input area */}
        <div
          ref={editableWrapperRef}
          className="input-editable-wrapper"
          onMouseOver={handleMouseOver}
          onMouseLeave={handleMouseLeave}
          style={editableWrapperStyle}
        >
          <div
            ref={editableRef}
            className="input-editable"
            contentEditable={!disabled}
            spellCheck={false}
            data-placeholder={placeholder}
            data-completion-suffix={inlineCompletion.suffix || ''}
            onInput={() => {
              // Don't pass browser's isComposing — it's unreliable in JCEF.
              // isComposingRef (set by compositionStart/End + keyCode 229) is the
              // sole source of truth for IME state.
              handleInput();
            }}
            onKeyDown={handleKeyDown}
            onKeyUp={handleKeyUp}
            onBeforeInput={(e) => {
              const inputType =
                'inputType' in e.nativeEvent
                  ? (e.nativeEvent as InputEvent).inputType
                  : undefined;
              if (inputType === 'insertParagraph') {
                e.preventDefault();
                // If item was just selected in completion menu with enter, don't send message
                if (completionSelectedRef.current) {
                  completionSelectedRef.current = false;
                  return;
                }
                // Don't send message when completion menu is open
                if (
                  fileCompletion.isOpen ||
                  commandCompletion.isOpen ||
                  agentCompletion.isOpen ||
                  promptCompletion.isOpen ||
                  dollarCommandCompletion.isOpen
                ) {
                  return;
                }
                // Only allow submit when not loading and not in IME composition
                if (!isLoading && !isComposingRef.current) {
                  handleSubmit();
                }
              }
              // Fix: Remove delete key special handling during IME
              // Let browser naturally handle delete operations, sync state uniformly after compositionend
            }}
            onCompositionStart={handleCompositionStart}
            onCompositionEnd={handleCompositionEnd}
            onPaste={handlePaste}
            onDragOver={handleDragOver}
            onDrop={handleDrop}
            onContextMenu={ctxMenu.open}
            suppressContentEditableWarning
          />
          {ctxMenu.visible && (
            <ContextMenu
              x={ctxMenu.x}
              y={ctxMenu.y}
              onClose={ctxMenu.close}
              items={[
                { label: t('contextMenu.copy', 'Copy'), action: () => copySelection(ctxMenu.savedRange, ctxMenu.selectedText), disabled: !ctxMenu.hasSelection },
                { label: t('contextMenu.cut', 'Cut'), action: handleCtxMenuCut, disabled: !ctxMenu.hasSelection },
                { label: t('contextMenu.paste', 'Paste'), action: () => { if (editableRef.current) { pasteAtCursor(ctxMenu.savedRange, editableRef.current, handleInput); } } },
                { separator: true },
                { label: t('contextMenu.newline', 'Insert Newline'), action: () => { if (editableRef.current) { insertNewline(ctxMenu.savedRange, editableRef.current); handleInput(); } } },
              ]}
            />
          )}
        </div>

        <ChatInputBoxFooter
          disabled={disabled}
          hasInputContent={hasContent || attachments.length > 0}
          isLoading={isLoading}
          isEnhancing={isEnhancing}
          selectedModel={selectedModel}
          permissionMode={permissionMode}
          currentProvider={currentProvider}
          reasoningEffort={reasoningEffort}
          onSubmit={handleSubmit}
          onStop={onStop}
          onModeSelect={handleModeSelect}
          onModelSelect={handleModelSelect}
          onProviderSelect={onProviderSelect}
          onReasoningChange={onReasoningChange}
          onEnhancePrompt={handleEnhancePrompt}
          alwaysThinkingEnabled={alwaysThinkingEnabled}
          onToggleThinking={onToggleThinking}
          streamingEnabled={streamingEnabled}
          onStreamingEnabledChange={onStreamingEnabledChange}
          selectedAgent={selectedAgent}
          onAgentSelect={(agent) => onAgentSelect?.(agent)}
          onOpenAgentSettings={onOpenAgentSettings}
          onAddModel={onOpenModelSettings}
          bmad={isBmadSupported ? {
            presets: bmadPresets,
            selectedPresetId: selectedBmadPresetId,
            status: bmadStatus,
            installLog: bmadInstallLog,
            onPresetChange: setSelectedBmadPresetId,
            onInsert: () => insertBmadCommand(false),
            onInsertAndSend: () => insertBmadCommand(true),
            onRefresh: requestBmadStatus,
            onInstall: handleInstallBmad,
            commandDisabled: disabled || isLoading || sdkStatusLoading || !sdkInstalled,
            installDisabled: disabled || isLoading || isBmadInstalling,
            installing: isBmadInstalling,
          } : undefined}
          uiUxPro={isUiUxSupported ? {
            presets: uiUxProPresets,
            selectedPresetId: selectedUiUxPresetId,
            status: uiUxStatus,
            installLog: uiUxInstallLog,
            onPresetChange: setSelectedUiUxPresetId,
            onInsert: () => insertUiUxPrompt(false),
            onInsertAndSend: () => insertUiUxPrompt(true),
            onRefresh: requestUiUxStatus,
            onInstall: handleInstallUiUx,
            promptDisabled: disabled || isLoading || sdkStatusLoading || !sdkInstalled,
            installDisabled: disabled || isLoading || isUiUxInstalling,
            installing: isUiUxInstalling,
          } : undefined}
          gitNexus={isGitNexusSupported ? {
            presets: gitNexusPresets,
            selectedPresetId: selectedGitNexusPresetId,
            status: gitNexusStatus,
            installLog: gitNexusInstallLog,
            selectedScope: selectedGitNexusScope,
            onPresetChange: setSelectedGitNexusPresetId,
            onScopeChange: setSelectedGitNexusScope,
            onInsert: () => insertGitNexusPrompt(false),
            onInsertAndSend: () => insertGitNexusPrompt(true),
            onRefresh: requestGitNexusStatus,
            onInstall: () => handleInstallGitNexus(false),
            onReindex: () => handleInstallGitNexus(true),
            promptDisabled: disabled || isLoading || sdkStatusLoading || !sdkInstalled,
            installDisabled: disabled || isLoading || isGitNexusInstalling,
            installing: isGitNexusInstalling,
          } : undefined}
          onClearAgent={() => onAgentSelect?.(null)}
          fileCompletion={fileCompletion}
          commandCompletion={commandCompletion}
          agentCompletion={agentCompletion}
          promptCompletion={promptCompletion}
          dollarCommandCompletion={dollarCommandCompletion}
          tooltip={tooltip}
          promptEnhancer={{
            isOpen: showEnhancerDialog,
            isLoading: isEnhancing,
            originalPrompt,
            enhancedPrompt,
            onUseEnhanced: handleUseEnhancedPrompt,
            onKeepOriginal: handleKeepOriginalPrompt,
            onClose: handleCloseEnhancerDialog,
          }}
          t={t}
        />
      </div>
    );
  }
));

// Display name for React DevTools
ChatInputBox.displayName = 'ChatInputBox';

export default ChatInputBox;
