import type { TFunction } from 'i18next';
import type { Attachment, SelectedAgent, QueuedMessage } from './types.js';
import { AttachmentList } from './AttachmentList.js';
import { ContextBar } from './ContextBar.js';
import { MessageQueue } from './MessageQueue.js';

export function ChatInputBoxHeader({
  sdkStatusLoading,
  sdkInstalled,
  currentProvider,
  onInstallSdk,
  t,
  attachments,
  onRemoveAttachment,
  activeFile,
  selectedLines,
  usagePercentage,
  usageUsedTokens,
  usageMaxTokens,
  showUsage,
  onClearContext,
  onAddAttachment,
  selectedAgent,
  onClearAgent,
  hasMessages,
  onRewind,
  statusPanelExpanded,
  onToggleStatusPanel,
  inputPanelCollapsed,
  onToggleInputPanelCollapse,
  messageQueue,
  onRemoveFromQueue,
  showOpenSourceBanner,
  onDismissOpenSourceBanner,
  autoOpenFileEnabled,
  onRequestEnableFileContext,
}: {
  sdkInstalled: boolean;
  sdkStatusLoading: boolean;
  currentProvider: string;
  onInstallSdk?: () => void;
  t: TFunction;
  attachments: Attachment[];
  onRemoveAttachment: (id: string) => void;
  activeFile?: string;
  selectedLines?: string;
  usagePercentage: number;
  usageUsedTokens?: number;
  usageMaxTokens?: number;
  showUsage: boolean;
  onClearContext?: () => void;
  onAddAttachment: (files: FileList) => void;
  selectedAgent?: SelectedAgent | null;
  onClearAgent: () => void;
  hasMessages: boolean;
  onRewind?: () => void;
  statusPanelExpanded: boolean;
  onToggleStatusPanel?: () => void;
  inputPanelCollapsed: boolean;
  onToggleInputPanelCollapse?: () => void;
  messageQueue?: QueuedMessage[];
  onRemoveFromQueue?: (id: string) => void;
  showOpenSourceBanner?: boolean;
  onDismissOpenSourceBanner?: () => void;
  autoOpenFileEnabled?: boolean;
  onRequestEnableFileContext?: () => void;
}) {
  return (
    <>
      {!inputPanelCollapsed && showOpenSourceBanner && (
        <div className="open-source-banner">
          <span className="banner-text">{t('chat.openSourceBanner')}</span>
          <button
            className="banner-close"
            aria-label="Close"
            onClick={(e) => {
              e.stopPropagation();
              onDismissOpenSourceBanner?.();
            }}
          >
            &#x2715;
          </button>
        </div>
      )}

      {/* SDK status loading or not installed warning bar */}
      {!inputPanelCollapsed && (sdkStatusLoading || !sdkInstalled) && (
        <div className={`sdk-warning-bar ${sdkStatusLoading ? 'sdk-loading' : ''}`}>
          <span
            className={`codicon ${sdkStatusLoading ? 'codicon-loading codicon-modifier-spin' : 'codicon-warning'}`}
          />
          <span className="sdk-warning-text">
            {sdkStatusLoading
              ? t('chat.sdkStatusLoading')
              : t('chat.sdkNotInstalled', {
                  provider: currentProvider === 'codex' ? 'Codex' : 'Claude Code',
                })}
          </span>
          {!sdkStatusLoading && (
            <button
              className="sdk-install-btn"
              onClick={(e) => {
                e.stopPropagation();
                onInstallSdk?.();
              }}
            >
              {t('chat.goInstallSdk')}
            </button>
          )}
        </div>
      )}

      {/* Message queue */}
      {!inputPanelCollapsed && messageQueue && messageQueue.length > 0 && (
        <MessageQueue
          queue={messageQueue}
          onRemove={onRemoveFromQueue ?? (() => {})}
        />
      )}

      {/* Attachment list */}
      {!inputPanelCollapsed && attachments.length > 0 && (
        <AttachmentList attachments={attachments} onRemove={onRemoveAttachment} />
      )}

      {/* Context bar (Top Control Bar) */}
      <ContextBar
        activeFile={activeFile}
        selectedLines={selectedLines}
        percentage={usagePercentage}
        usedTokens={usageUsedTokens}
        maxTokens={usageMaxTokens}
        showUsage={showUsage}
        onClearFile={onClearContext}
        onAddAttachment={onAddAttachment}
        selectedAgent={selectedAgent}
        onClearAgent={onClearAgent}
        currentProvider={currentProvider}
        hasMessages={hasMessages}
        onRewind={onRewind}
        statusPanelExpanded={statusPanelExpanded}
        onToggleStatusPanel={onToggleStatusPanel}
        inputPanelCollapsed={inputPanelCollapsed}
        onToggleInputPanelCollapse={onToggleInputPanelCollapse}
        autoOpenFileEnabled={autoOpenFileEnabled}
        onRequestEnableFileContext={onRequestEnableFileContext}
      />
    </>
  );
}

