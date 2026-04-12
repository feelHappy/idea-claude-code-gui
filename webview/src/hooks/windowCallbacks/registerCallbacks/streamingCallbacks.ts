/**
 * streamingCallbacks.ts
 *
 * Registers window bridge callbacks for streaming:
 * onStreamStart, onContentDelta, onThinkingDelta, onStreamEnd, onPermissionDenied.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { sendBridgeEvent } from '../../../utils/bridge';
import { THROTTLE_INTERVAL } from '../../useStreamingMessages';

export function registerStreamingCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    setMessages,
    setStreamingActive,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    setExpandedThinking,
    streamingContentRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    autoExpandedThinkingKeysRef,
    streamingTextSegmentsRef,
    activeTextSegmentIndexRef,
    streamingThinkingSegmentsRef,
    activeThinkingSegmentIndexRef,
    seenToolUseCountRef,
    streamingMessageIndexRef,
    streamingTurnIdRef,
    turnIdCounterRef,
    lastContentUpdateRef,
    contentUpdateTimeoutRef,
    lastThinkingUpdateRef,
    thinkingUpdateTimeoutRef,
    getOrCreateStreamingAssistantIndex,
    patchAssistantForStreaming,
  } = options;

  const findStreamingAssistantIndex = (messages: any[]): number => {
    // Fast path: check the cached index first (set by onStreamStart / getOrCreateStreamingAssistantIndex)
    const cached = streamingMessageIndexRef.current;
    if (cached >= 0 && cached < messages.length && messages[cached]?.type === 'assistant') {
      const turnId = streamingTurnIdRef.current;
      if (turnId <= 0 || messages[cached].__turnId === turnId) {
        return cached;
      }
    }

    // Scan by turnId
    const turnId = streamingTurnIdRef.current;
    if (turnId > 0) {
      for (let i = messages.length - 1; i >= 0; i -= 1) {
        if (messages[i]?.type === 'assistant' && messages[i].__turnId === turnId) {
          return i;
        }
      }
    }

    // Scan by isStreaming flag
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      if (messages[i]?.type === 'assistant' && messages[i].isStreaming) {
        return i;
      }
    }

    // No greedy fallback — matching an arbitrary old assistant message is a bug,
    // not a feature (caused the retract content-corruption issue).
    return -1;
  };

  window.onStreamStart = () => {
    if (window.__sessionTransitioning) return;
    streamingContentRef.current = '';
    isStreamingRef.current = true;
    // Default to backend streaming render mode.  This means updateMessages()
    // will process the full message list (including text content) during streaming.
    // When the first onContentDelta arrives (Claude mode), we switch to false so
    // that content delivery is handled by the delta path instead.  Codex does not
    // emit onContentDelta, so it stays in backend render mode throughout.
    useBackendStreamingRenderRef.current = true;
    autoExpandedThinkingKeysRef.current.clear();
    setStreamingActive(true);
    streamingTextSegmentsRef.current = [];
    activeTextSegmentIndexRef.current = -1;
    streamingThinkingSegmentsRef.current = [];
    activeThinkingSegmentIndexRef.current = -1;
    seenToolUseCountRef.current = 0;

    // FIX: Always reset streamingMessageIndexRef regardless of backend streaming mode
    streamingMessageIndexRef.current = -1;
    turnIdCounterRef.current += 1;
    streamingTurnIdRef.current = turnIdCounterRef.current;
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (last?.type === 'assistant' && last?.isStreaming) {
        streamingMessageIndexRef.current = prev.length - 1;
        const updated = [...prev];
        updated[prev.length - 1] = { ...updated[prev.length - 1], __turnId: streamingTurnIdRef.current };
        return updated;
      }
      streamingMessageIndexRef.current = prev.length;
      return [
        ...prev,
        {
          type: 'assistant',
          content: '',
          isStreaming: true,
          timestamp: new Date().toISOString(),
          __turnId: streamingTurnIdRef.current,
        },
      ];
    });
  };

  window.onContentDelta = (delta: string) => {
    if (window.__sessionTransitioning) return;
    if (!isStreamingRef.current) return;

    // First delta arrival: switch from backend streaming render to delta mode.
    // This ensures Claude (which uses onContentDelta) no longer relies on
    // updateMessages for text content, while Codex (no deltas) stays in
    // backend render mode where updateMessages delivers content directly.
    if (useBackendStreamingRenderRef.current) {
      useBackendStreamingRenderRef.current = false;
    }

    streamingContentRef.current += delta;
    activeThinkingSegmentIndexRef.current = -1;

    if (activeTextSegmentIndexRef.current < 0) {
      activeTextSegmentIndexRef.current = streamingTextSegmentsRef.current.length;
      streamingTextSegmentsRef.current.push('');
    }
    streamingTextSegmentsRef.current[activeTextSegmentIndexRef.current] += delta;

    const now = Date.now();
    const timeSinceLastUpdate = now - lastContentUpdateRef.current;

    const updateMessages = () => {
      const currentContent = streamingContentRef.current;
      setMessages((prev) => {
        const newMessages = [...prev];
        let idx: number;
        if (useBackendStreamingRenderRef.current) {
          idx = streamingMessageIndexRef.current;
          // Index is still -1: backend hasn't created the assistant via updateMessages yet
          if (idx < 0) return prev;
        } else {
          idx = getOrCreateStreamingAssistantIndex(newMessages);
        }

        if (idx >= 0 && newMessages[idx]?.type === 'assistant') {
          newMessages[idx] = patchAssistantForStreaming({
            ...newMessages[idx],
            content: currentContent,
            isStreaming: true,
          });
        }
        return newMessages;
      });
    };

    if (timeSinceLastUpdate >= THROTTLE_INTERVAL) {
      lastContentUpdateRef.current = now;
      updateMessages();
    } else {
      if (!contentUpdateTimeoutRef.current) {
        const remainingTime = THROTTLE_INTERVAL - timeSinceLastUpdate;
        contentUpdateTimeoutRef.current = setTimeout(() => {
          contentUpdateTimeoutRef.current = null;
          lastContentUpdateRef.current = Date.now();
          updateMessages();
        }, remainingTime);
      }
    }
  };

  window.onThinkingDelta = (delta: string) => {
    if (window.__sessionTransitioning) return;
    if (!isStreamingRef.current) return;

    // Same as onContentDelta: switch to delta mode on first thinking delta.
    if (useBackendStreamingRenderRef.current) {
      useBackendStreamingRenderRef.current = false;
    }

    activeTextSegmentIndexRef.current = -1;

    let forceUpdate = false;
    if (activeThinkingSegmentIndexRef.current < 0) {
      activeThinkingSegmentIndexRef.current = streamingThinkingSegmentsRef.current.length;
      streamingThinkingSegmentsRef.current.push('');
      forceUpdate = true;
    }
    streamingThinkingSegmentsRef.current[activeThinkingSegmentIndexRef.current] += delta;

    const now = Date.now();
    const timeSinceLastUpdate = now - lastThinkingUpdateRef.current;

    const updateMessages = () => {
      setMessages((prev) => {
        const newMessages = [...prev];
        let idx: number;
        if (useBackendStreamingRenderRef.current) {
          idx = streamingMessageIndexRef.current;
          if (idx < 0) return prev;
        } else {
          idx = getOrCreateStreamingAssistantIndex(newMessages);
        }

        if (idx >= 0 && newMessages[idx]?.type === 'assistant') {
          newMessages[idx] = patchAssistantForStreaming({
            ...newMessages[idx],
            isStreaming: true,
          });
        }
        return newMessages;
      });
    };

    if (forceUpdate || timeSinceLastUpdate >= THROTTLE_INTERVAL) {
      lastThinkingUpdateRef.current = now;
      updateMessages();
    } else {
      if (!thinkingUpdateTimeoutRef.current) {
        const remainingTime = THROTTLE_INTERVAL - timeSinceLastUpdate;
        thinkingUpdateTimeoutRef.current = setTimeout(() => {
          thinkingUpdateTimeoutRef.current = null;
          lastThinkingUpdateRef.current = Date.now();
          updateMessages();
        }, remainingTime);
      }
    }
  };

  window.onStreamEnd = () => {
    if (window.__sessionTransitioning) return;
    // Notify backend about stream completion for tab status indicator
    sendBridgeEvent('tab_status_changed', JSON.stringify({ status: 'completed' }));

    // If streaming was already cleared (e.g. by onRetractResult), skip message
    // processing to avoid corrupting retained messages. Only perform UI cleanup.
    if (!isStreamingRef.current) {
      setStreamingActive(false);
      setLoading(false);
      setLoadingStartTime(null);
      setIsThinking(false);
      return;
    }

    // Clear pending throttle timeouts — their content is already in streamingContentRef
    if (contentUpdateTimeoutRef.current) {
      clearTimeout(contentUpdateTimeoutRef.current);
      contentUpdateTimeoutRef.current = null;
    }
    if (thinkingUpdateTimeoutRef.current) {
      clearTimeout(thinkingUpdateTimeoutRef.current);
      thinkingUpdateTimeoutRef.current = null;
    }

    // Snapshot keys that need collapsing BEFORE they are cleared inside the updater.
    const keysToCollapse = new Set(autoExpandedThinkingKeysRef.current);

    // Flush final content AND clear streaming refs inside the same updater.
    // This ensures any previously queued setMessages updater (e.g. from
    // updateMessages) still reads valid refs when it executes, because React
    // processes updaters in enqueue order.
    setMessages((prev) => {
      let newMessages = prev;
      let idx = streamingMessageIndexRef.current;
      if (!(idx >= 0 && idx < prev.length && prev[idx]?.type === 'assistant')) {
        idx = findStreamingAssistantIndex(prev);
      }

      if (prev.length > 0 && idx >= 0 && idx < prev.length && prev[idx]?.type === 'assistant') {
        const finalContent = streamingContentRef.current;
        newMessages = [...prev];
        if (useBackendStreamingRenderRef.current) {
          // Backend streaming mode (Codex): content was delivered via updateMessages,
          // not via streamingContentRef. patchAssistantForStreaming would overwrite the
          // content with streamingContentRef.current (empty for Codex) and clear raw
          // blocks, erasing all backend-delivered content. Just clear isStreaming.
          newMessages[idx] = { ...newMessages[idx], isStreaming: false };
        } else {
          // Delta streaming mode (Claude): flush final content from streamingContentRef.
          const finalizedAssistant = patchAssistantForStreaming({
            ...newMessages[idx],
            content: finalContent || newMessages[idx].content,
            isStreaming: true,
          });
          newMessages[idx] = {
            ...finalizedAssistant,
            content: finalContent || finalizedAssistant.content,
            isStreaming: false,
          };
        }
      } else if (streamingContentRef.current) {
        newMessages = [
          ...prev,
          {
            type: 'assistant',
            content: streamingContentRef.current,
            isStreaming: false,
            timestamp: new Date().toISOString(),
            __turnId: streamingTurnIdRef.current > 0 ? streamingTurnIdRef.current : undefined,
          },
        ];
      }

      // Clear all streaming refs AFTER flushing content, inside the updater
      isStreamingRef.current = false;
      useBackendStreamingRenderRef.current = false;
      streamingMessageIndexRef.current = -1;
      streamingTurnIdRef.current = -1;
      streamingContentRef.current = '';
      streamingTextSegmentsRef.current = [];
      activeTextSegmentIndexRef.current = -1;
      streamingThinkingSegmentsRef.current = [];
      activeThinkingSegmentIndexRef.current = -1;
      seenToolUseCountRef.current = 0;
      autoExpandedThinkingKeysRef.current.clear();

      return newMessages;
    });

    // Collapse auto-expanded thinking blocks using the pre-clear snapshot
    if (setExpandedThinking && keysToCollapse.size > 0) {
      setExpandedThinking((prev) => {
        const next = { ...prev };
        keysToCollapse.forEach((key) => {
          next[key] = false;
        });
        return next;
      });
    }

    // React state (not ref) — React batches this with setMessages automatically
    setStreamingActive(false);

    // FIX: onStreamEnd is the authoritative signal that streaming has ended.
    // Reset loading state here to prevent race conditions where showLoading("false")
    // arrives before onStreamEnd and gets ignored by the isStreamingRef guard,
    // while the flush callback's showLoading("false") may be delayed or lost
    // (e.g., due to slow message serialization or multi-hop async chains).
    setLoading(false);
    setLoadingStartTime(null);
    setIsThinking(false);
  };

  // Permission denied callback — marks incomplete tool calls as "interrupted"
  window.onPermissionDenied = () => {
    if (!window.__deniedToolIds) {
      window.__deniedToolIds = new Set<string>();
    }

    const idsToAdd: string[] = [];

    setMessages((currentMessages) => {
      try {
        for (let i = currentMessages.length - 1; i >= 0; i--) {
          const msg = currentMessages[i];
          if (msg.type === 'assistant' && msg.raw) {
            const rawObj = typeof msg.raw === 'string' ? JSON.parse(msg.raw) : msg.raw;
            const content = rawObj.content || rawObj.message?.content;

            if (Array.isArray(content)) {
              const toolUses = content.filter(
                (block: { type?: string; id?: string }) =>
                  block.type === 'tool_use' && block.id,
              ) as Array<{ type: string; id: string; name?: string }>;

              if (toolUses.length > 0) {
                const nextMsg = currentMessages[i + 1];
                const existingResultIds = new Set<string>();

                if (nextMsg?.type === 'user' && nextMsg.raw) {
                  const nextRaw =
                    typeof nextMsg.raw === 'string' ? JSON.parse(nextMsg.raw) : nextMsg.raw;
                  const nextContent = nextRaw.content || nextRaw.message?.content;
                  if (Array.isArray(nextContent)) {
                    nextContent.forEach((block: { type?: string; tool_use_id?: string }) => {
                      if (block.type === 'tool_result' && block.tool_use_id) {
                        existingResultIds.add(block.tool_use_id);
                      }
                    });
                  }
                }

                for (const tu of toolUses) {
                  if (!existingResultIds.has(tu.id)) {
                    idsToAdd.push(tu.id);
                  }
                }

                break;
              }
            }
          }
        }
      } catch (e) {
        console.error('[Frontend] Error in onPermissionDenied:', e);
      }

      return [...currentMessages];
    });

    for (const id of idsToAdd) {
      window.__deniedToolIds!.add(id);
    }
  };
}
