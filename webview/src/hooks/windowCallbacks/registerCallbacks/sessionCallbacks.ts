/**
 * sessionCallbacks.ts
 *
 * Registers window bridge callbacks for session management, SDK dependency status,
 * and rewind result: setSessionId, addToast, onExportSessionData,
 * updateDependencyStatus, onRewindResult.
 */

import type { MutableRefObject } from 'react';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { downloadJSON } from '../../../utils/exportMarkdown';
import { releaseSessionTransition } from '../sessionTransition';
import { drainAndRequestDependencyStatus } from '../settingsBootstrap';
import { extractAttachments, isToolResultOnlyUserMessage } from '../../useRewriteHandlers';

/**
 * Reset all streaming-related refs and UI state.
 * Called by both rewrite and retract callbacks to prevent the subsequent
 * onStreamEnd from corrupting retained messages with stale streaming content.
 */
function resetStreamingAndLoadingState(options: UseWindowCallbacksOptions): void {
  // Clear streaming refs so onStreamEnd becomes a no-op
  options.isStreamingRef.current = false;
  options.streamingContentRef.current = '';
  options.streamingMessageIndexRef.current = -1;
  options.streamingTurnIdRef.current = -1;

  // Cancel pending throttle timers
  if (options.contentUpdateTimeoutRef.current) {
    clearTimeout(options.contentUpdateTimeoutRef.current);
    options.contentUpdateTimeoutRef.current = null;
  }
  if (options.thinkingUpdateTimeoutRef.current) {
    clearTimeout(options.thinkingUpdateTimeoutRef.current);
    options.thinkingUpdateTimeoutRef.current = null;
  }

  // Clear loading/streaming UI state
  options.setLoading(false);
  options.setLoadingStartTime(null);
  options.setStreamingActive(false);
}

export function registerSessionAndSdkCallbacks(
  options: UseWindowCallbacksOptions,
  tRef: MutableRefObject<UseWindowCallbacksOptions['t']>,
): void {
  const {
    addToast,
    setCurrentSessionId,
    setSdkStatus,
    setSdkStatusLoaded,
    setIsRewinding,
    setRewindDialogOpen,
    setCurrentRewindRequest,
    setIsRewriting,
    setRewriteDialogOpen,
    setCurrentRewriteRequest,
    setMessages,
    chatInputRef,
    customSessionTitleRef,
    currentSessionIdRef,
    updateHistoryTitle,
  } = options;

  window.setSessionId = (sessionId: string) => {
    const oldId = currentSessionIdRef.current;
    releaseSessionTransition();
    setCurrentSessionId(sessionId);

    // B-011 + B-014: Persist custom title under the real SDK session ID.
    // NOTE: We intentionally do NOT delete the old ID's title to prevent
    // data loss when Codex creates new threads for continued conversations.
    // Orphaned title entries are harmless and cleaned up on session deletion.
    const title = customSessionTitleRef.current;
    if (title && oldId !== sessionId) {
      updateHistoryTitle(sessionId, title);
    }
  };

  window.addToast = (message, type) => {
    addToast(message, type as 'info' | 'success' | 'warning' | 'error' | undefined);
  };

  window.onExportSessionData = (json) => {
    try {
      const data = JSON.parse(json);
      if (data.sessionId && data.messages) {
        const exportContent = JSON.stringify(data, null, 2);
        const sanitizedTitle = (data.title || 'session')
          .replace(/[<>:"/\\|?*]/g, '_')
          .replace(/\s+/g, '_')
          .substring(0, 50);
        const filename = `${sanitizedTitle}_${data.sessionId.substring(0, 8)}.json`;
        downloadJSON(exportContent, filename);
      } else if (data.error) {
        addToast(data.error, 'error');
      } else {
        addToast(tRef.current('history.exportFailed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to process export data:', error);
      addToast(tRef.current('history.exportFailed'), 'error');
    }
  };

  // =========================================================================
  // SDK Status Callbacks
  // =========================================================================

  const originalUpdateDependencyStatus = window.updateDependencyStatus;
  window.updateDependencyStatus = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setSdkStatus(data);
      setSdkStatusLoaded(true);
    } catch (error) {
      console.error('[Frontend] Failed to parse dependency status:', error);
    }
    if (
      originalUpdateDependencyStatus &&
      originalUpdateDependencyStatus !== window.updateDependencyStatus
    ) {
      originalUpdateDependencyStatus(jsonStr);
    }
  };
  (window as unknown as Record<string, unknown>)._appUpdateDependencyStatus =
    window.updateDependencyStatus;

  drainAndRequestDependencyStatus();

  // =========================================================================
  // Rewind Result Callback
  // =========================================================================

  window.onRewindResult = (json: string) => {
    try {
      const result = JSON.parse(json);
      setIsRewinding(false);
      if (result.success) {
        setRewindDialogOpen(false);
        setCurrentRewindRequest(null);
        window.addToast?.(tRef.current('rewind.success'), 'success');
      } else {
        window.addToast?.(result.message || tRef.current('rewind.failed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse rewind result:', error);
      setIsRewinding(false);
      setRewindDialogOpen(false);
      setCurrentRewindRequest(null);
      window.addToast?.(tRef.current('rewind.parseError'), 'error');
    }
  };

  // =========================================================================
  // Rewrite Result Callback
  // =========================================================================

  window.onRewriteResult = (json: string) => {
    try {
      const result = JSON.parse(json);
      setIsRewriting(false);
      if (result.success) {
        setRewriteDialogOpen(false);
        resetStreamingAndLoadingState(options);

        // Prefer backend-provided truncation index; fall back to the stored request
        const request = (window as unknown as { __currentRewriteRequest?: { messageIndex?: number; originalText?: string; originalAttachments?: unknown[] } }).__currentRewriteRequest;
        const truncateAt: number | undefined = result.truncateAtIndex ?? request?.messageIndex;

        if (truncateAt !== undefined && truncateAt >= 0) {
          setMessages((prev) => prev.slice(0, truncateAt));
        }

        // Refill the input box with original content
        const originalText = request?.originalText || '';
        const originalAttachments = request?.originalAttachments || [];
        if (chatInputRef?.current?.refill) {
          chatInputRef.current.refill(originalText, originalAttachments as import('../../../components/ChatInputBox/types').Attachment[]);
        }
        setCurrentRewriteRequest(null);
        window.addToast?.(tRef.current('rewrite.success'), 'success');
      } else {
        window.addToast?.(result.message || tRef.current('rewrite.failed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse rewrite result:', error);
      setIsRewriting(false);
      setRewriteDialogOpen(false);
      setCurrentRewriteRequest(null);
      window.addToast?.(tRef.current('rewrite.parseError'), 'error');
    }
  };

  // =========================================================================
  // Retract Result Callback
  // =========================================================================

  window.onRetractResult = (json: string) => {
    try {
      const result = JSON.parse(json);
      if (result.success) {
        resetStreamingAndLoadingState(options);

        // Remove the last REAL user message (skip tool_result-only messages)
        // and all subsequent messages (assistant responses, tool_results, etc.)
        setMessages((prev) => {
          // Walk backwards to find the last real user message (not tool_result)
          let realUserIdx = -1;
          for (let i = prev.length - 1; i >= 0; i--) {
            if (prev[i].type === 'user' && !isToolResultOnlyUserMessage(prev[i])) {
              realUserIdx = i;
              break;
            }
          }
          if (realUserIdx >= 0) {
            const realMsg = prev[realUserIdx];
            const text = realMsg.content || '';
            const attachments = extractAttachments(realMsg);
            // Schedule refill after state update (microtask to avoid calling during render)
            queueMicrotask(() => {
              if (chatInputRef?.current?.refill && (text || attachments.length > 0)) {
                chatInputRef.current.refill(text, attachments.length > 0 ? attachments : undefined);
              }
            });
            return prev.slice(0, realUserIdx);
          }
          return prev;
        });
        window.addToast?.(tRef.current('rewrite.retractSuccess'), 'success');
      } else {
        window.addToast?.(result.message || tRef.current('rewrite.retractFailed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse retract result:', error);
      window.addToast?.(tRef.current('rewrite.parseError'), 'error');
    }
  };
}
