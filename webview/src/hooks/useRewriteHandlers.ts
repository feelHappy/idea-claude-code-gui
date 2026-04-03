import { useCallback, useRef } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage, ClaudeContentBlock } from '../types';
import type { Attachment } from '../components/ChatInputBox/types';
import type { RewriteRequest } from '../components/RewriteConfirmDialog';
import { rewriteMessage, retractMessage } from '../utils/bridge';
import { formatTime } from '../utils/helpers';

export interface UseRewriteHandlersOptions {
  t: TFunction;
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  currentSessionId: string | null;
  currentProvider: string;
  messages: ClaudeMessage[];
  mergedMessages: ClaudeMessage[];
  loading: boolean;
  getMessageText: (message: ClaudeMessage) => string;
  setCurrentRewriteRequest: (request: RewriteRequest | null) => void;
  setRewriteDialogOpen: (open: boolean) => void;
  setIsRewriting: (loading: boolean) => void;
  isRewriting: boolean;
}

export interface UseRewriteHandlersReturn {
  handleRewriteClick: (messageIndex: number, message: ClaudeMessage) => void;
  handleRewriteConfirm: () => void;
  handleRewriteCancel: () => void;
  handleRetractClick: () => void;
  extractAttachmentsFromMessage: (message: ClaudeMessage) => Attachment[];
}

/**
 * Check if a user message is tool-result-only (not a real user text message)
 */
function isToolResultOnlyUserMessage(msg: ClaudeMessage): boolean {
  if (msg.type !== 'user') return false;
  if ((msg.content ?? '').trim() === '[tool_result]') return true;

  const raw = msg.raw;
  if (!raw || typeof raw === 'string') return false;

  const rawObj = raw as { content?: unknown[]; message?: { content?: unknown[] } };
  const content = rawObj.content ?? rawObj.message?.content;
  if (!Array.isArray(content)) return false;

  return content.some(
    (block) => block && typeof block === 'object' && (block as { type?: string }).type === 'tool_result'
  );
}

/**
 * Extract attachments (images + files) from a message's raw content blocks
 */
export function extractAttachments(message: ClaudeMessage): Attachment[] {
  const raw = message.raw;
  if (!raw || typeof raw === 'string') return [];

  const rawObj = raw as { content?: ClaudeContentBlock[]; message?: { content?: ClaudeContentBlock[] } };
  const blocks = rawObj.content ?? rawObj.message?.content;
  if (!Array.isArray(blocks)) return [];

  const result: Attachment[] = [];
  let imageCounter = 0;

  for (const block of blocks) {
    if (!block || typeof block !== 'object') continue;
    const b = block as Record<string, unknown>;

    if (b.type === 'image') {
      // Format 1: Frontend direct format - { type: 'image', src: 'data:...;base64,...' }
      if (typeof b.src === 'string') {
        const src = b.src as string;
        const base64Match = src.match(/^data:([^;]+);base64,(.+)$/);
        if (base64Match) {
          imageCounter++;
          result.push({
            id: `rewrite-img-${Date.now()}-${imageCounter}`,
            fileName: (typeof b.alt === 'string' && b.alt) || `image-${imageCounter}.png`,
            mediaType: base64Match[1],
            data: base64Match[2],
          });
        }
      }
      // Format 2: Backend/API format - { type: 'image', source: { type: 'base64', media_type, data } }
      else if (b.source && typeof b.source === 'object') {
        const source = b.source as Record<string, unknown>;
        if (source.type === 'base64' && typeof source.data === 'string') {
          imageCounter++;
          const mediaType = typeof source.media_type === 'string' ? source.media_type : 'image/png';
          result.push({
            id: `rewrite-img-${Date.now()}-${imageCounter}`,
            fileName: `image-${imageCounter}.png`,
            mediaType,
            data: source.data,
          });
        }
      }
    }
  }

  return result;
}

export function useRewriteHandlers(options: UseRewriteHandlersOptions): UseRewriteHandlersReturn {
  const {
    t,
    addToast,
    currentSessionId,
    currentProvider,
    messages,
    mergedMessages,
    loading,
    getMessageText,
    setCurrentRewriteRequest,
    setRewriteDialogOpen,
    setIsRewriting,
    isRewriting,
  } = options;

  const extractAttachmentsFromMessage = useCallback((message: ClaudeMessage): Attachment[] => {
    return extractAttachments(message);
  }, []);

  /**
   * Scenario C: Rewrite after AI has responded
   * Click "rewrite" on a user message → show confirmation dialog
   */
  const handleRewriteClick = useCallback((messageIndex: number, message: ClaudeMessage) => {
    // Walk back to find a real user text message if this is tool-result-only
    let targetIndex = messageIndex;
    let targetMessage: ClaudeMessage = message;
    if (isToolResultOnlyUserMessage(message)) {
      for (let i = messageIndex - 1; i >= 0; i -= 1) {
        const candidate = mergedMessages[i];
        if (candidate.type !== 'user') continue;
        if (isToolResultOnlyUserMessage(candidate)) continue;
        targetIndex = i;
        targetMessage = candidate;
        break;
      }
    }

    // Extract UUID from message if available (Claude has UUID, Codex may not)
    const raw = targetMessage.raw;
    const uuid = typeof raw === 'object' ? (raw as Record<string, unknown>)?.uuid : undefined;

    // Use UUID if available, otherwise generate a fallback ID.
    // The backend will look up the real UUID from JSONL history when needed.
    const userMessageId = (uuid as string) || `ts-${targetMessage.timestamp || Date.now()}`;

    // Find the real index in the raw messages array (mergedMessages index may differ)
    const rawIndex = messages.findIndex(m => m === targetMessage);
    if (rawIndex < 0) {
      addToast(t('rewrite.notAvailable'), 'warning');
      return;
    }

    const messagesAfterCount = mergedMessages.length - targetIndex - 1;
    const content = targetMessage.content || getMessageText(targetMessage);
    const timestamp = targetMessage.timestamp ? formatTime(targetMessage.timestamp) : undefined;
    const attachments = extractAttachments(targetMessage);

    // Use backend session ID as fallback when frontend state is out of sync
    setCurrentRewriteRequest({
      sessionId: currentSessionId || '',
      userMessageId,
      messageIndex: rawIndex,
      messageContent: content,
      messageTimestamp: timestamp,
      messagesAfterCount,
      originalText: content,
      originalAttachments: attachments,
      provider: currentProvider,
    });
    setRewriteDialogOpen(true);
  }, [currentSessionId, currentProvider, messages, mergedMessages, getMessageText, setCurrentRewriteRequest, setRewriteDialogOpen, addToast, t]);

  /**
   * Confirm rewrite: call backend to truncate history
   */
  const handleRewriteConfirm = useCallback(() => {
    const request = (window as unknown as { __currentRewriteRequest?: RewriteRequest }).__currentRewriteRequest;
    if (!request) return;
    setIsRewriting(true);
    rewriteMessage(request.sessionId, request.userMessageId, request.provider, request.messageIndex);
  }, [setIsRewriting]);

  /**
   * Cancel rewrite
   */
  const handleRewriteCancel = useCallback(() => {
    if (isRewriting) {
      setIsRewriting(false);
    }
    setRewriteDialogOpen(false);
    setCurrentRewriteRequest(null);
  }, [isRewriting, setIsRewriting, setRewriteDialogOpen, setCurrentRewriteRequest]);

  /**
   * Scenario A: Retract last message before AI responds
   * Only available when loading=true and a recent user message exists near the tail.
   * Uses a ref guard to prevent rapid double-clicks from sending multiple retract requests.
   * The guard resets automatically when loading becomes false (retract completed or failed).
   */
  const isRetractingRef = useRef(false);
  if (!loading) isRetractingRef.current = false;
  const handleRetractClick = useCallback(() => {
    if (!loading || isRetractingRef.current) return;

    // Only allow retract if there's a user message near the tail of the conversation
    // (not just any user message deep in history)
    let lastUserIdx = -1;
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].type === 'user') { lastUserIdx = i; break; }
    }
    if (lastUserIdx < 0 || lastUserIdx < messages.length - 3) return;

    isRetractingRef.current = true;
    retractMessage(currentSessionId || '', currentProvider);
  }, [currentSessionId, currentProvider, messages, loading]);

  return {
    handleRewriteClick,
    handleRewriteConfirm,
    handleRewriteCancel,
    handleRetractClick,
    extractAttachmentsFromMessage,
  };
}
