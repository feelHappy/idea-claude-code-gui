import { useImperativeHandle } from 'react';
import type { ForwardedRef, MutableRefObject } from 'react';
import type { Attachment, ChatInputBoxHandle, FileTagInfo } from '../types.js';

export interface UseChatInputImperativeHandleOptions {
  ref: ForwardedRef<ChatInputBoxHandle>;
  editableRef: React.RefObject<HTMLDivElement | null>;
  getTextContent: () => string;
  invalidateCache: () => void;
  isExternalUpdateRef: MutableRefObject<boolean>;
  setHasContent: (hasContent: boolean) => void;
  adjustHeight: () => void;
  focusInput: () => void;
  clearInput: () => void;
  hasContent: boolean;
  extractFileTags: () => FileTagInfo[];
  setAttachments: (attachments: Attachment[]) => void;
  onInput?: (content: string) => void;
}

/**
 * useChatInputImperativeHandle - Exposes an imperative API for the input box
 *
 * Keeps the parent API stable without forcing additional re-renders.
 */
export function useChatInputImperativeHandle({
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
  setAttachments,
  onInput,
}: UseChatInputImperativeHandleOptions): void {
  useImperativeHandle(
    ref,
    () => ({
      getValue: () => {
        invalidateCache();
        return getTextContent();
      },
      setValue: (newValue: string) => {
        if (!editableRef.current) return;
        isExternalUpdateRef.current = true;
        editableRef.current.innerText = newValue;
        setHasContent(!!newValue.trim());
        adjustHeight();
        invalidateCache();

        if (newValue) {
          const range = document.createRange();
          const selection = window.getSelection();
          if (!selection) return;

          range.selectNodeContents(editableRef.current);
          range.collapse(false);
          selection.removeAllRanges();
          selection.addRange(range);
        }
      },
      focus: focusInput,
      clear: clearInput,
      hasContent: () => hasContent,
      getFileTags: extractFileTags,
      refill: (text: string, attachments?: Attachment[]) => {
        if (!editableRef.current) return;
        isExternalUpdateRef.current = true;
        editableRef.current.innerText = text;
        setHasContent(!!text.trim() || (Array.isArray(attachments) && attachments.length > 0));
        adjustHeight();
        invalidateCache();
        if (attachments && attachments.length > 0) {
          setAttachments(attachments);
        }
        onInput?.(text);
        if (text) {
          const range = document.createRange();
          const selection = window.getSelection();
          if (!selection) return;
          range.selectNodeContents(editableRef.current);
          range.collapse(false);
          selection.removeAllRanges();
          selection.addRange(range);
        }
        focusInput();
      },
    }),
    [
      getTextContent,
      invalidateCache,
      editableRef,
      isExternalUpdateRef,
      setHasContent,
      adjustHeight,
      focusInput,
      clearInput,
      hasContent,
      extractFileTags,
      setAttachments,
      onInput,
    ]
  );
}

