import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type { Attachment } from './ChatInputBox/types';

export interface RewriteRequest {
  sessionId: string;
  userMessageId: string;
  messageIndex: number;
  messageContent: string;
  messageTimestamp?: string;
  messagesAfterCount: number;
  originalText: string;
  originalAttachments: Attachment[];
  provider: string;
}

interface RewriteConfirmDialogProps {
  isOpen: boolean;
  request: RewriteRequest | null;
  isLoading?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

const RewriteConfirmDialog = ({
  isOpen,
  request,
  isLoading = false,
  onConfirm,
  onCancel,
}: RewriteConfirmDialogProps) => {
  const { t } = useTranslation();

  useEffect(() => {
    if (isOpen) {
      const handleEscape = (e: KeyboardEvent) => {
        if (e.key === 'Escape') {
          onCancel();
        }
      };
      window.addEventListener('keydown', handleEscape);
      return () => window.removeEventListener('keydown', handleEscape);
    }
  }, [isOpen, onCancel]);

  // Sync request to window for the confirm handler to read
  useEffect(() => {
    (window as unknown as Record<string, unknown>).__currentRewriteRequest = request;
    return () => {
      (window as unknown as Record<string, unknown>).__currentRewriteRequest = undefined;
    };
  }, [request]);

  if (!isOpen || !request) {
    return null;
  }

  const displayContent = request.messageContent.length > 50
    ? `${request.messageContent.substring(0, 50)}...`
    : request.messageContent;

  const isCodex = request.provider === 'codex';

  return (
    <div className="confirm-dialog-overlay" onClick={onCancel}>
      <div className="confirm-dialog rewind-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="confirm-dialog-header">
          <h3 className="confirm-dialog-title">
            <span className="rewind-icon">&#x270F;</span> {t('rewrite.confirmTitle')}
          </h3>
        </div>
        <div className="confirm-dialog-body">
          {isLoading ? (
            <div className="rewind-loading">
              <span className="codicon codicon-loading codicon-modifier-spin rewind-loading-icon" />
              <span className="rewind-loading-text">{t('rewrite.rewriting')}</span>
            </div>
          ) : (
            <>
              <div className="rewind-target">
                <div className="rewind-target-label">{t('rewrite.title')}:</div>
                <div className="rewind-target-message">
                  {request.messageTimestamp && (
                    <span className="rewind-timestamp">[{request.messageTimestamp}]</span>
                  )}
                  <span className="rewind-content">"{displayContent}"</span>
                </div>
                {request.originalAttachments.length > 0 && (
                  <div className="rewrite-attachments-hint">
                    +{request.originalAttachments.length} attachment(s)
                  </div>
                )}
              </div>

              <div className="rewind-warning">
                <div className="rewind-warning-icon">&#x26A0;</div>
                <div className="rewind-warning-content">
                  <div className="rewind-warning-title">{t('rewind.impact', 'Impact')}:</div>
                  <ul className="rewind-warning-list">
                    <li>
                      {isCodex
                        ? t('rewrite.confirmDescriptionCodex')
                        : t('rewrite.confirmDescription')}
                    </li>
                    {request.messagesAfterCount > 0 && (
                      <li>
                        {t('rewrite.messagesWillBeRemoved', { count: request.messagesAfterCount + 1 })}
                      </li>
                    )}
                  </ul>
                </div>
              </div>
            </>
          )}
        </div>
        <div className="confirm-dialog-footer">
          {isLoading ? (
            <button className="confirm-dialog-button cancel-button" onClick={onCancel}>
              {t('common.close', 'Close')}
            </button>
          ) : (
            <>
              <button className="confirm-dialog-button cancel-button" onClick={onCancel}>
                {t('common.cancel', 'Cancel')}
              </button>
              <button
                className="confirm-dialog-button confirm-button rewind-confirm-button"
                onClick={onConfirm}
                autoFocus
              >
                {t('rewrite.confirmButton')}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default RewriteConfirmDialog;
