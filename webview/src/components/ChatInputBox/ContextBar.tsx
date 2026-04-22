import React, { useRef, useState, useEffect, useCallback, memo } from 'react';
import { useTranslation } from 'react-i18next';
import { getFileIcon } from '../../utils/fileIcons';
import { TokenIndicator } from './TokenIndicator';
import type { SelectedAgent } from './types';

interface ContextBarProps {
  activeFile?: string;
  selectedLines?: string;
  percentage?: number;
  usedTokens?: number;
  maxTokens?: number;
  showUsage?: boolean;
  onClearFile?: () => void;
  onAddAttachment?: (files: FileList) => void;
  selectedAgent?: SelectedAgent | null;
  onClearAgent?: () => void;
  /** Current provider (for conditional rendering) */
  currentProvider?: string;
  /** Whether there are messages (for rewind button visibility) */
  hasMessages?: boolean;
  /** Rewind callback */
  onRewind?: () => void;
  /** Whether StatusPanel is expanded */
  statusPanelExpanded?: boolean;
  /** Toggle StatusPanel expand/collapse */
  onToggleStatusPanel?: () => void;
  /** Whether input panel is collapsed */
  inputPanelCollapsed?: boolean;
  /** Toggle input panel collapse */
  onToggleInputPanelCollapse?: () => void;
  /** Whether auto open file is enabled */
  autoOpenFileEnabled?: boolean;
  /** Callback to enable file context (called from placeholder click) */
  onRequestEnableFileContext?: () => void;
}

export const ContextBar: React.FC<ContextBarProps> = memo(({
  activeFile,
  selectedLines,
  percentage = 0,
  usedTokens,
  maxTokens,
  showUsage = true,
  onClearFile,
  onAddAttachment,
  selectedAgent,
  onClearAgent,
  currentProvider = 'claude',
  hasMessages = false,
  onRewind,
  statusPanelExpanded = true,
  onToggleStatusPanel,
  inputPanelCollapsed = false,
  onToggleInputPanelCollapse,
  autoOpenFileEnabled = false,
  onRequestEnableFileContext,
}) => {
  const { t } = useTranslation();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const [showEnablePopover, setShowEnablePopover] = useState(false);

  // Reset popover state when autoOpenFileEnabled changes
  useEffect(() => {
    if (autoOpenFileEnabled) {
      setShowEnablePopover(false);
    }
  }, [autoOpenFileEnabled]);

  // Click outside or Escape to close popover
  useEffect(() => {
    if (!showEnablePopover) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) {
        setShowEnablePopover(false);
      }
    };
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setShowEnablePopover(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, [showEnablePopover]);

  const handleAttachClick = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    fileInputRef.current?.click();
  }, []);

  const handleFileChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      onAddAttachment?.(e.target.files);
    }
    e.target.value = '';
  }, [onAddAttachment]);

  const handlePlaceholderClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setShowEnablePopover(true);
  }, []);

  const handlePopoverCancel = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setShowEnablePopover(false);
  }, []);

  const handlePopoverConfirm = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setShowEnablePopover(false);
    onRequestEnableFileContext?.();
  }, [onRequestEnableFileContext]);

  // Extract filename from path
  const getFileName = (path: string) => {
    return path.split(/[/\\]/).pop() || path;
  };

  const getFileIconSvg = (path: string) => {
    const fileName = getFileName(path);
    const extension = fileName.indexOf('.') !== -1 ? fileName.split('.').pop() : '';
    return getFileIcon(extension, fileName);
  };

  const displayText = activeFile ? (
    selectedLines ? `${getFileName(activeFile)}#${selectedLines}` : getFileName(activeFile)
  ) : '';

  const fullDisplayText = activeFile ? (
    selectedLines ? `${activeFile}#${selectedLines}` : activeFile
  ) : '';

  const renderInputPanelToggleIcon = (collapsed: boolean) => {
    if (collapsed) {
      // Collapsed state: show "expand" metaphor (panel grows downward)
      return (
        <svg
          className="input-panel-toggle-icon"
          viewBox="0 0 16 16"
          width="14"
          height="14"
          aria-hidden="true"
        >
          <path
            d="M3 3.75A.75.75 0 0 1 3.75 3h8.5a.75.75 0 0 1 0 1.5h-8.5A.75.75 0 0 1 3 3.75ZM3 7.75A.75.75 0 0 1 3.75 7h8.5a.75.75 0 0 1 0 1.5h-8.5A.75.75 0 0 1 3 7.75Z"
            fill="currentColor"
          />
          <path
            d="M8 12.8a.6.6 0 0 1-.43-.18l-2.35-2.4a.6.6 0 1 1 .86-.84L8 11.33l1.92-1.95a.6.6 0 1 1 .86.84l-2.35 2.4a.6.6 0 0 1-.43.18Z"
            fill="currentColor"
          />
        </svg>
      );
    }

    // Expanded state: show "collapse" metaphor (panel folds up)
    return (
      <svg
        className="input-panel-toggle-icon"
        viewBox="0 0 16 16"
        width="14"
        height="14"
        aria-hidden="true"
      >
        <path
          d="M3 12.25a.75.75 0 0 1 .75-.75h8.5a.75.75 0 0 1 0 1.5h-8.5a.75.75 0 0 1-.75-.75ZM3 8.25a.75.75 0 0 1 .75-.75h8.5a.75.75 0 0 1 0 1.5h-8.5A.75.75 0 0 1 3 8.25Z"
          fill="currentColor"
        />
        <path
          d="M8 3.2c.16 0 .31.07.43.18l2.35 2.4a.6.6 0 0 1-.86.84L8 4.67 6.08 6.62a.6.6 0 1 1-.86-.84l2.35-2.4A.6.6 0 0 1 8 3.2Z"
          fill="currentColor"
        />
      </svg>
    );
  };

  return (
    <div className="context-bar">
      {/* Tool Icons Group */}
      <div className="context-tools">
        <div
          className="context-tool-btn"
          onClick={handleAttachClick}
          title="Add attachment"
        >
          <span className="codicon codicon-attach" />
        </div>

        {/* Token Indicator */}
        {showUsage && (
          <div className="context-token-indicator">
            <TokenIndicator
              percentage={percentage}
              usedTokens={usedTokens}
              maxTokens={maxTokens}
              size={14}
            />
          </div>
        )}
        
        {/* Hidden file input */}
        <input
          ref={fileInputRef}
          type="file"
          multiple
          className="hidden-file-input"
          onChange={handleFileChange}
          style={{ display: 'none' }}
        />
        
        <div className="context-tool-divider" />
      </div>

      {/* Selected Agent Chip */}
      {selectedAgent && (
        <div 
          className="context-item has-tooltip" 
          data-tooltip={selectedAgent.name}
          style={{ cursor: 'default' }}
        >
          <span 
            className="codicon codicon-robot" 
            style={{ marginRight: 4 }}
          />
          <span className="context-text">
            <span dir="ltr">
              {selectedAgent.name.length > 3 
                ? `${selectedAgent.name.slice(0, 3)}...` 
                : selectedAgent.name}
            </span>
          </span>
          <span 
            className="codicon codicon-close context-close" 
            onClick={onClearAgent}
            title="Remove agent"
          />
        </div>
      )}

      {/* Active Context Chip or Empty Placeholder */}
      {displayText ? (
        <div
          className="context-item has-tooltip"
          data-tooltip={fullDisplayText}
          style={{ cursor: 'default' }}
        >
          {activeFile && (
            <span
              className="context-file-icon"
              style={{
                marginRight: 4,
                display: 'inline-flex',
                alignItems: 'center',
                width: 16,
                height: 16
              }}
              dangerouslySetInnerHTML={{ __html: getFileIconSvg(activeFile) }}
            />
          )}
          <span className="context-text">
            <span dir="ltr">{displayText}</span>
          </span>
          <span
            className="codicon codicon-close context-close"
            onClick={onClearFile}
            title="Remove file context"
          />
        </div>
      ) : !autoOpenFileEnabled && (
        <div className="context-file-placeholder-wrapper" ref={popoverRef}>
          <button
            className="context-file-placeholder"
            onClick={handlePlaceholderClick}
            title={t('fileContext.placeholder')}
            type="button"
          >
            <span className="codicon codicon-file" />
            <span className="placeholder-text">{t('fileContext.placeholder')}</span>
          </button>

          {showEnablePopover && (
            <div className="file-context-confirm-popover">
              <div className="popover-title">{t('fileContext.enableTitle')}</div>
              <div className="popover-description">{t('fileContext.enableDescription')}</div>
              <div className="popover-actions">
                <button
                  className="popover-btn popover-btn-cancel"
                  onClick={handlePopoverCancel}
                >
                  {t('fileContext.cancel')}
                </button>
                <button
                  className="popover-btn popover-btn-confirm"
                  onClick={handlePopoverConfirm}
                >
                  {t('fileContext.enable')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Right side tools - StatusPanel toggle and Rewind button */}
      <div className="context-tools-right">
        {/* StatusPanel expand/collapse toggle - always visible */}
        {onToggleStatusPanel && (
          <button
            className={`context-tool-btn status-panel-toggle has-tooltip ${statusPanelExpanded ? 'expanded' : 'collapsed'}`}
            onClick={onToggleStatusPanel}
            type="button"
            data-tooltip={statusPanelExpanded ? t('statusPanel.collapse') : t('statusPanel.expand')}
          >
            <span className={`codicon ${statusPanelExpanded ? 'codicon-chevron-down' : 'codicon-layers'}`} />
          </button>
        )}

        {onToggleInputPanelCollapse && (
          <button
            className={`context-tool-btn input-panel-toggle has-tooltip ${inputPanelCollapsed ? 'collapsed' : 'expanded'}`}
            onClick={onToggleInputPanelCollapse}
            type="button"
            data-tooltip={
              inputPanelCollapsed
                ? t('inputPanel.expand', { defaultValue: 'Expand input panel' })
                : t('inputPanel.collapse', { defaultValue: 'Collapse input panel' })
            }
          >
            {renderInputPanelToggleIcon(inputPanelCollapsed)}
          </button>
        )}

        {/* Rewind button */}
        {currentProvider === 'claude' && onRewind && (
          <button
            className="context-tool-btn has-tooltip"
            onClick={onRewind}
            disabled={!hasMessages}
            type="button"
            data-tooltip={t('rewind.tooltip')}
          >
            <span className="codicon codicon-discard" />
          </button>
        )}
      </div>
    </div>
  );
});
