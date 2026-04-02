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
    setLoading,
    setLoadingStartTime,
    setStreamingActive,
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
        // Truncate frontend messages
        const truncateAt: number | undefined = result.truncateAtIndex;
        if (truncateAt !== undefined && truncateAt >= 0) {
          setMessages((prev) => prev.slice(0, truncateAt));
        }
        // Refill the input box with original content from the stored request
        const request = (window as unknown as { __currentRewriteRequest?: { originalText?: string; originalAttachments?: unknown[] } }).__currentRewriteRequest;
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
        // Remove the last user message from frontend and capture its content for refill
        setMessages((prev) => {
          if (prev.length > 0 && prev[prev.length - 1].type === 'user') {
            const lastMsg = prev[prev.length - 1];
            const text = lastMsg.content || '';
            // Schedule refill after state update (microtask to avoid calling during render)
            queueMicrotask(() => {
              if (chatInputRef?.current?.refill && text) {
                chatInputRef.current.refill(text);
              }
            });
            return prev.slice(0, -1);
          }
          return prev;
        });
        // Stop loading state
        setLoading(false);
        setLoadingStartTime(null);
        setStreamingActive(false);
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
