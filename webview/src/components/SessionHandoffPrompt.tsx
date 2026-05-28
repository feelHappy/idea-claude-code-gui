import type { TFunction } from 'i18next';

interface SessionHandoffPromptProps {
  usageUsedTokens?: number;
  onCopy: () => void;
  onCopyAndNewTab: () => void;
  onDismiss: () => void;
  t: TFunction;
}

export function SessionHandoffPrompt({
  usageUsedTokens,
  onCopy,
  onCopyAndNewTab,
  onDismiss,
  t,
}: SessionHandoffPromptProps) {
  const usedTokens = typeof usageUsedTokens === 'number'
    ? usageUsedTokens.toLocaleString()
    : undefined;

  return (
    <div className="session-handoff-prompt" role="status">
      <div className="session-handoff-icon" aria-hidden="true">
        <span className="codicon codicon-multiple-windows" />
      </div>
      <div className="session-handoff-copy">
        <div className="session-handoff-title">
          {t('sessionHandoff.title')}
          {usedTokens !== undefined && (
            <span className="session-handoff-usage">
              {t('sessionHandoff.usage', { tokens: usedTokens })}
            </span>
          )}
        </div>
        <div className="session-handoff-description">
          {t('sessionHandoff.description')}
        </div>
      </div>
      <div className="session-handoff-actions">
        <button type="button" className="session-handoff-secondary" onClick={onCopy}>
          <span className="codicon codicon-copy" aria-hidden="true" />
          {t('sessionHandoff.copy')}
        </button>
        <button type="button" className="session-handoff-primary" onClick={onCopyAndNewTab}>
          <span className="codicon codicon-new-window" aria-hidden="true" />
          {t('sessionHandoff.copyAndNewTab')}
        </button>
        <button
          type="button"
          className="session-handoff-dismiss"
          onClick={onDismiss}
          aria-label={t('sessionHandoff.dismiss')}
          title={t('sessionHandoff.dismiss')}
        >
          <span className="codicon codicon-close" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
