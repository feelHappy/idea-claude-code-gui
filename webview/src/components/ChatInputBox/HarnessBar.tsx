import { useState } from 'react';
import { useTranslation } from 'react-i18next';

export interface HarnessStatus {
  state: 'loading' | 'ready' | 'partial' | 'missing' | 'error';
  hasHarness?: boolean;
  hasRules?: boolean;
  hasPlaybooks?: boolean;
  hasOwner?: boolean;
  hasAutoIngest?: boolean;
  playbookCount?: number;
  variant?: string;
  message?: string;
}

export interface HarnessToolbarProps {
  status: HarnessStatus;
  installLog: string;
  onRefresh: () => void;
  onInstall: () => void;
  installDisabled?: boolean;
}

const STATE_CLASSES: Record<HarnessStatus['state'], string> = {
  loading: 'state-loading',
  ready: 'state-ready',
  partial: 'state-missing',
  missing: 'state-missing',
  error: 'state-error',
};

export function HarnessBar({
  status,
  installLog,
  onRefresh,
  onInstall,
  installDisabled = false,
}: HarnessToolbarProps) {
  const { t } = useTranslation();
  const [showLog, setShowLog] = useState(false);

  const stateLabel = t(`chat.harness.status.${status.state}`, {
    defaultValue: status.state,
  });

  const description =
    status.state === 'ready'
      ? t('chat.harness.readyHint', {
          defaultValue: 'Knowledge system active. Playbooks auto-accumulate as you develop.',
        })
      : status.state === 'missing'
        ? t('chat.harness.missingHint', {
            defaultValue:
              'Install the Harness knowledge system to enable auto-learning from your development.',
          })
        : status.state === 'partial'
          ? t('chat.harness.partialHint', {
              defaultValue: 'Harness directory exists but is incomplete.',
            })
          : status.message ||
            t('chat.harness.loadingHint', { defaultValue: 'Checking harness status...' });

  return (
    <div className="bmad-command-bar harness-bar">
      <div className="bmad-command-main">
        <div className="bmad-command-head">
          <span className={`bmad-command-badge ${STATE_CLASSES[status.state]}`}>
            {stateLabel}
          </span>
          {status.variant && status.state === 'ready' && (
            <span className="bmad-command-badge" style={{ opacity: 0.7 }}>
              {status.variant}
            </span>
          )}
          {status.playbookCount != null && status.playbookCount > 0 && (
            <span className="bmad-command-badge" style={{ opacity: 0.7 }}>
              {status.playbookCount} {t('chat.harness.playbooks', { defaultValue: 'playbook(s)' })}
            </span>
          )}
          <span style={{ flex: 1 }} />
          <span className="bmad-command-head-title">
            {t('chat.harness.title', { defaultValue: 'Harness' })}
          </span>
        </div>
        <div className="bmad-command-summary">{description}</div>
      </div>

      {installLog && (
        <div className="bmad-command-feedback">
          <div
            className="bmad-command-detail muted"
            style={{ cursor: 'pointer' }}
            onClick={() => setShowLog(!showLog)}
          >
            {showLog
              ? t('chat.harness.hideLog', { defaultValue: '▼ Hide log' })
              : t('chat.harness.showLog', { defaultValue: '▶ Show log' })}
          </div>
          {showLog && (
            <pre
              style={{
                fontSize: '11px',
                maxHeight: '120px',
                overflow: 'auto',
                margin: '4px 0 0',
                padding: '6px',
                background: 'var(--vscode-textBlockQuote-background, rgba(0,0,0,0.1))',
                borderRadius: '4px',
                whiteSpace: 'pre-wrap',
              }}
            >
              {installLog}
            </pre>
          )}
        </div>
      )}

      <div className="bmad-command-actions">
        {(status.state === 'missing' || status.state === 'partial') && (
          <button
            className="bmad-command-button primary"
            onClick={onInstall}
            disabled={installDisabled}
          >
            <i className="codicon codicon-cloud-download" />
            {status.state === 'partial'
              ? t('chat.harness.repair', { defaultValue: 'Repair' })
              : t('chat.harness.install', { defaultValue: 'Install' })}
          </button>
        )}
        <button className="bmad-command-button" onClick={onRefresh}>
          <i className="codicon codicon-refresh" />
          {t('chat.harness.refresh', { defaultValue: 'Refresh' })}
        </button>
      </div>
    </div>
  );
}
