/**
 * messageCallbacks.ts
 *
 * Registers window bridge callbacks for message management:
 * updateMessages, updateStatus, showLoading, showThinkingStatus,
 * setHistoryData, clearMessages, addErrorMessage, addHistoryMessage,
 * historyLoadComplete, addUserMessage.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { ClaudeMessage } from '../../../types';
import { sendBridgeEvent } from '../../../utils/bridge';
import {
  appendOptimisticMessageIfMissing,
  ensureStreamingAssistantInList,
  findCurrentTurnAssistantIndex,
  preserveLastAssistantIdentity,
  preserveStreamingAssistantContent,
} from '../messageSync';
import { releaseSessionTransition } from '../sessionTransition';

const isTruthy = (v: unknown) => v === true || v === 'true';
const hasRenderableText = (msg: ClaudeMessage | undefined): boolean =>
  typeof msg?.content === 'string' && msg.content.trim().length > 0;

const hasToolUseBlocks = (
  msg: ClaudeMessage | undefined,
  extractRawBlocks: (raw: ClaudeMessage['raw']) => Array<Record<string, unknown>>,
): boolean => extractRawBlocks(msg?.raw).some((block) => block?.type === 'tool_use');

/** Safety fallback timer for showLoading(false) suppressed during streaming. */
let loadingFalseFallbackTimer: ReturnType<typeof setTimeout> | null = null;

export function registerMessageCallbacks(
  options: UseWindowCallbacksOptions,
  resetTransientUiState: () => void,
): void {
  const {
    addToast,
    setMessages,
    setStatus,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    setHistoryData,
    userPausedRef,
    isUserAtBottomRef,
    messagesContainerRef,
    suppressNextStatusToastRef,
    streamingContentRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    activeTextSegmentIndexRef,
    activeThinkingSegmentIndexRef,
    seenToolUseCountRef,
    streamingMessageIndexRef,
    streamingTurnIdRef,
    findLastAssistantIndex,
    extractRawBlocks,
    patchAssistantForStreaming,
  } = options;

  const ensureStreamingAssistantPreserved = (prevList: ClaudeMessage[], resultList: ClaudeMessage[]): ClaudeMessage[] => {
    const { list, streamingIndex } = ensureStreamingAssistantInList(
      prevList,
      resultList,
      isStreamingRef.current,
      streamingTurnIdRef.current,
    );
    if (streamingIndex >= 0) {
      streamingMessageIndexRef.current = streamingIndex;
    }
    return list;
  };
  const findActiveTurnAssistantIndex = (messages: ClaudeMessage[]): number =>
    findCurrentTurnAssistantIndex(messages, findLastAssistantIndex);

  window.updateMessages = (json) => {
    // During session transition, ignore message updates from stale session
    // callbacks to prevent cleared messages from being restored
    if (window.__sessionTransitioning) return;

    try {
      const parsed = JSON.parse(json) as ClaudeMessage[];

      setMessages((prev) => {
        // If streaming is active, delegate to the streaming logic
        if (isStreamingRef.current) {
          if (useBackendStreamingRenderRef.current) {
            let smartMerged = parsed.map((newMsg, i) => {
              if (i === parsed.length - 1) return newMsg;
              if (i < prev.length) {
                const oldMsg = prev[i];
                if (
                  oldMsg.timestamp === newMsg.timestamp &&
                  oldMsg.type === newMsg.type &&
                  oldMsg.content === newMsg.content
                ) {
                  return oldMsg;
                }
              }
              return newMsg;
            });

            smartMerged = preserveLastAssistantIdentity(prev, smartMerged, findActiveTurnAssistantIndex);
            smartMerged = preserveStreamingAssistantContent(
              prev,
              smartMerged,
              isStreamingRef,
              streamingContentRef,
              findActiveTurnAssistantIndex,
              patchAssistantForStreaming,
            );
            const result = appendOptimisticMessageIfMissing(prev, smartMerged);

            // FIX: In Claude mode, update streamingMessageIndexRef so that
            // onContentDelta knows which assistant message to update.
            let lastAssistantIdx = findActiveTurnAssistantIndex(result);
            // Verify the found assistant belongs to the current streaming turn
            if (lastAssistantIdx >= 0 && streamingTurnIdRef.current > 0 &&
                result[lastAssistantIdx].__turnId !== streamingTurnIdRef.current) {
              // Scan for the correct turn ID match (from end, consistent with findLastAssistantIndex)
              for (let i = result.length - 1; i >= 0; i--) {
                if (result[i].type === 'assistant' && result[i].__turnId === streamingTurnIdRef.current) {
                  lastAssistantIdx = i;
                  break;
                }
              }
            }
            if (lastAssistantIdx >= 0) {
              streamingMessageIndexRef.current = lastAssistantIdx;

              // Always stamp __turnId so ensureStreamingAssistantPreserved can find it,
              // even before any content delta arrives (streamingContentRef may be empty).
              if (result[lastAssistantIdx]?.__turnId !== streamingTurnIdRef.current) {
                result[lastAssistantIdx] = {
                  ...result[lastAssistantIdx],
                  __turnId: streamingTurnIdRef.current,
                };
              }

              // FIX: If there is buffered streaming content (onContentDelta may
              // fire before updateMessages), apply it to the assistant message
              // immediately to prevent content loss.
              if (streamingContentRef.current && result[lastAssistantIdx]?.type === 'assistant') {
                const backendContent = result[lastAssistantIdx].content || '';
                if (streamingContentRef.current.length >= backendContent.length) {
                  result[lastAssistantIdx] = patchAssistantForStreaming({
                    ...result[lastAssistantIdx],
                    content: streamingContentRef.current,
                    isStreaming: true,
                  });
                } else {
                  // Backend has more complete content; sync buffer
                  streamingContentRef.current = backendContent;
                }
              }
            }

            return ensureStreamingAssistantPreserved(prev, result);
          }

          const lastAssistantIdx = findActiveTurnAssistantIndex(parsed);
          if (lastAssistantIdx < 0) {
            return ensureStreamingAssistantPreserved(prev, appendOptimisticMessageIfMissing(prev, parsed));
          }
        }

        // Non-streaming case (or streaming hasn't started yet)
        if (!isStreamingRef.current) {
          // Smart merge: reuse old message objects for performance
          let smartMerged = parsed.map((newMsg, i) => {
            if (i === parsed.length - 1) return newMsg;
            if (i < prev.length) {
              const oldMsg = prev[i];
              if (
                oldMsg.timestamp === newMsg.timestamp &&
                oldMsg.type === newMsg.type &&
                oldMsg.content === newMsg.content
              ) {
                return oldMsg;
              }
            }
            return newMsg;
          });

          // Guard: prevent stale backend snapshot from overwriting finalized
          // streaming content. After onStreamEnd the backend's 50ms coalescer
          // may still deliver an older snapshot whose content is shorter than
          // what the frontend already rendered.
          const prevLastIdx = findActiveTurnAssistantIndex(prev);
          const mergedLastIdx = findActiveTurnAssistantIndex(smartMerged);
          if (
            prevLastIdx >= 0 && mergedLastIdx >= 0 &&
            prev[prevLastIdx].type === 'assistant' &&
            smartMerged[mergedLastIdx].type === 'assistant'
          ) {
            const prevContent = prev[prevLastIdx].content || '';
            const mergedContent = smartMerged[mergedLastIdx].content || '';
            // Only preserve older assistant text when both snapshots are text-bearing
            // responses. Tool-use assistant messages are intentionally text-empty and
            // must not be overwritten with the previous commentary, otherwise the UI
            // shows A + tool + A even though Codex only emitted one commentary block.
            if (
              prevContent.length > mergedContent.length &&
              prev[prevLastIdx].timestamp === smartMerged[mergedLastIdx].timestamp &&
              hasRenderableText(prev[prevLastIdx]) &&
              hasRenderableText(smartMerged[mergedLastIdx]) &&
              !hasToolUseBlocks(prev[prevLastIdx], extractRawBlocks) &&
              !hasToolUseBlocks(smartMerged[mergedLastIdx], extractRawBlocks)
            ) {
              smartMerged = [...smartMerged];
              smartMerged[mergedLastIdx] = prev[prevLastIdx];
            }
          }

          smartMerged = preserveLastAssistantIdentity(prev, smartMerged, findActiveTurnAssistantIndex);
          return ensureStreamingAssistantPreserved(prev, appendOptimisticMessageIfMissing(prev, smartMerged));
        }

        // Streaming + !useBackendStreamingRender: only update on structural changes.
        // The delta channel (onContentDelta) handles text content, so backend
        // updateMessages is only needed when new tool_use blocks appear or new
        // user messages (with tool_result) are added.
        const lastAssistantIdx = findActiveTurnAssistantIndex(parsed);
        if (lastAssistantIdx < 0) {
          return ensureStreamingAssistantPreserved(prev, appendOptimisticMessageIfMissing(prev, parsed));
        }

        const lastAssistant = parsed[lastAssistantIdx];
        const lastAssistantBlocks = extractRawBlocks(lastAssistant.raw);
        const toolUseCount = lastAssistantBlocks.filter((b) => b?.type === 'tool_use').length;
        if (toolUseCount < seenToolUseCountRef.current) {
          seenToolUseCountRef.current = toolUseCount;
        }
        const hasNewToolUse = toolUseCount > seenToolUseCountRef.current;
        const messageCountChanged = parsed.length !== prev.length;

        if (!hasNewToolUse && !messageCountChanged) {
          return prev;
        }

        if (hasNewToolUse) {
          seenToolUseCountRef.current = toolUseCount;
          activeTextSegmentIndexRef.current = -1;
          activeThinkingSegmentIndexRef.current = -1;
        }

        let patched = [...parsed];
        patched = appendOptimisticMessageIfMissing(prev, patched);
        patched = preserveLastAssistantIdentity(prev, patched, findActiveTurnAssistantIndex);
        patched = preserveStreamingAssistantContent(
          prev,
          patched,
          isStreamingRef,
          streamingContentRef,
          findActiveTurnAssistantIndex,
          patchAssistantForStreaming,
        );

        const patchedAssistantIdx = findActiveTurnAssistantIndex(patched);
        if (patchedAssistantIdx >= 0 && patched[patchedAssistantIdx]?.type === 'assistant') {
          streamingMessageIndexRef.current = patchedAssistantIdx;
          patched[patchedAssistantIdx] = patchAssistantForStreaming({
            ...patched[patchedAssistantIdx],
            __turnId: streamingTurnIdRef.current,
          });
        }

        return ensureStreamingAssistantPreserved(prev, patched);
      });
    } catch (error) {
      console.error('[Frontend] Failed to parse messages:', error);
    }
  };

  window.updateStatus = (text) => {
    // Do not release the transition guard from generic status updates.
    setStatus(text);
    if (suppressNextStatusToastRef.current) {
      suppressNextStatusToastRef.current = false;
      return;
    }
    addToast(text);
  };

  window.showLoading = (value) => {
    const isLoading = isTruthy(value);

    // During streaming, onStreamEnd is the authoritative signal for loading=false.
    // However, don't silently discard — schedule a safety fallback in case
    // onStreamEnd is delayed or lost (e.g., retract/rewrite race).
    if (!isLoading && isStreamingRef.current) {
      if (!loadingFalseFallbackTimer) {
        loadingFalseFallbackTimer = window.setTimeout(() => {
          loadingFalseFallbackTimer = null;
          // Only force loading=false if streaming has already ended
          if (!isStreamingRef.current) {
            setLoading(false);
            setLoadingStartTime(null);
            sendBridgeEvent('tab_loading_changed', JSON.stringify({ loading: false }));
          }
        }, 3000);
      }
      return;
    }

    // Cancel any pending fallback when a definitive loading signal arrives
    if (loadingFalseFallbackTimer) {
      clearTimeout(loadingFalseFallbackTimer);
      loadingFalseFallbackTimer = null;
    }

    // Notify backend about loading state change for tab indicator
    sendBridgeEvent('tab_loading_changed', JSON.stringify({ loading: isLoading }));

    setLoading((prevLoading) => {
      if (isLoading) {
        if (!prevLoading) {
          setLoadingStartTime(Date.now());
        }
      } else {
        setLoadingStartTime(null);
      }
      return isLoading;
    });
  };

  window.showThinkingStatus = (value) => setIsThinking(isTruthy(value));
  window.setHistoryData = (data) => setHistoryData(data);

  window.clearMessages = () => {
    window.__deniedToolIds?.clear();
    resetTransientUiState();
    setMessages([]);
  };

  window.addErrorMessage = (message) => {
    addToast(message, 'error');
  };

  window.addHistoryMessage = (message: ClaudeMessage) => {
    if (window.__sessionTransitioning) return;
    setMessages((prev) => [...prev, message]);
  };

  // History load complete callback — triggers Markdown re-rendering
  // and resets streaming state to ensure a clean slate for new interactions.
  window.historyLoadComplete = () => {
    releaseSessionTransition();

    // Reset streaming-related refs so that stale turn IDs / content buffers
    // from a previous session do not leak into the next user interaction.
    // Without this, preserveLastAssistantIdentity and
    // preserveStreamingAssistantContent may incorrectly merge old content
    // into new responses when the user sends a message in a restored session.
    streamingTurnIdRef.current = 0;
    streamingMessageIndexRef.current = -1;
    streamingContentRef.current = '';
    isStreamingRef.current = false;
    seenToolUseCountRef.current = 0;
    activeTextSegmentIndexRef.current = -1;
    activeThinkingSegmentIndexRef.current = -1;

    setMessages((prev) => {
      if (prev.length === 0) return prev;
      const updated = [...prev];
      updated[updated.length - 1] = { ...updated[updated.length - 1] };
      return updated;
    });
  };

  window.addUserMessage = (content: string) => {
    if (window.__sessionTransitioning) return;
    const userMessage: ClaudeMessage = {
      type: 'user',
      content: content || '',
      timestamp: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, userMessage]);
    userPausedRef.current = false;
    isUserAtBottomRef.current = true;
    requestAnimationFrame(() => {
      if (messagesContainerRef.current) {
        messagesContainerRef.current.scrollTop = messagesContainerRef.current.scrollHeight;
      }
    });
  };

}
