import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  formatMiniMaxCommand,
  type MiniMaxCommandPreset,
} from './minimaxCommands.js';
import type { MiniMaxToolbarProps } from './types.js';

const GROUP_LABELS: Record<MiniMaxCommandPreset['group'], string> = {
  core: 'Core',
  documents: 'Documents',
};

const GROUP_ORDER: MiniMaxCommandPreset['group'][] = ['core', 'documents'];

export function MiniMaxBar({
  presets,
  selectedPresetId,
  status,
  installLog,
  operation = null,
  onPresetChange,
  onInsert,
  onInsertAndSend,
  onRefresh,
  onInstall,
  onUpdate,
  commandDisabled = false,
  installDisabled = false,
  updateDisabled = false,
}: MiniMaxToolbarProps) {
  const { t } = useTranslation();
  const pickerRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');

  const groupedPresets = useMemo(() => {
    return presets.reduce<Record<MiniMaxCommandPreset['group'], MiniMaxCommandPreset[]>>(
      (acc, preset) => {
        acc[preset.group].push(preset);
        return acc;
      },
      { core: [], documents: [] },
    );
  }, [presets]);

  const selectedPreset = useMemo(
    () => presets.find((preset) => preset.id === selectedPresetId) ?? presets[0],
    [presets, selectedPresetId],
  );

  const filteredGroupedPresets = useMemo(() => {
    const normalizedSearch = searchTerm.trim().toLowerCase();
    if (!normalizedSearch) {
      return groupedPresets;
    }

    return GROUP_ORDER.reduce<Record<MiniMaxCommandPreset['group'], MiniMaxCommandPreset[]>>(
      (acc, group) => {
        acc[group] = groupedPresets[group].filter((preset) => {
          const description = t(preset.descriptionKey, {
            defaultValue: preset.description,
          });
          const commandLabel = formatMiniMaxCommand(preset.command, status.provider);
          const searchText = `${commandLabel} ${description}`.toLowerCase();
          return searchText.includes(normalizedSearch);
        });
        return acc;
      },
      { core: [], documents: [] },
    );
  }, [groupedPresets, searchTerm, status.provider, t]);

  const hasFilteredPresets = useMemo(
    () => GROUP_ORDER.some((group) => filteredGroupedPresets[group].length > 0),
    [filteredGroupedPresets],
  );

  useEffect(() => {
    if (!pickerOpen) {
      setSearchTerm('');
      return undefined;
    }

    requestAnimationFrame(() => {
      searchInputRef.current?.focus();
      searchInputRef.current?.select();
    });

    const handleClickOutside = (event: MouseEvent) => {
      if (!pickerRef.current?.contains(event.target as Node)) {
        setPickerOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [pickerOpen]);

  if (!selectedPreset) {
    return null;
  }

  const providerLabel = t(`providers.${status.provider}.label`, {
    defaultValue: status.providerLabel || status.provider,
  });
  const busy = operation !== null;
  const installing = operation === 'install';
  const updating = operation === 'update';
  const renderedCommand = formatMiniMaxCommand(selectedPreset.command, status.provider);
  const selectedDescription = t(selectedPreset.descriptionKey, {
    defaultValue: selectedPreset.description,
  });
  const showInsertActions = status.state === 'ready';
  const showUpdateAction = status.state === 'ready' && status.hasUpdate === true;
  const showInstallAction =
    status.state === 'missing' || status.state === 'partial' || status.state === 'error';
  const installLabel = status.state === 'partial'
    ? t('chat.minimax.repair', { defaultValue: 'Repair' })
    : t('chat.minimax.install', { defaultValue: 'Install' });
  const statusLabel = {
    loading: t('chat.minimax.status.loading', { defaultValue: 'Checking' }),
    ready: t('chat.minimax.status.ready', { defaultValue: 'Ready' }),
    missing: t('chat.minimax.status.missing', { defaultValue: 'Not Installed' }),
    partial: t('chat.minimax.status.partial', { defaultValue: 'Needs Repair' }),
    unsupported: t('chat.minimax.status.unsupported', { defaultValue: 'Unsupported' }),
    error: t('chat.minimax.status.error', { defaultValue: 'Error' }),
  }[status.state];

  const statusHint = (() => {
    if (busy && installLog) {
      return installLog;
    }

    if (updating) {
      return t('chat.minimax.updatingHint', {
        defaultValue: 'Updating MiniMax skills for the current project...',
      });
    }

    if (installing) {
      return t('chat.minimax.installingHint', {
        defaultValue: 'Installing MiniMax skills for the current project...',
      });
    }

    if (status.state === 'ready') {
      if (status.versionTrackingMissing) {
        return t('chat.minimax.versionTrackingHint', {
          defaultValue: 'MiniMax skills are ready, but version tracking is missing. Click Update once to resync the tracked skills.',
        });
      }
      if (status.hasUpdate && status.latestVersion) {
        return t('chat.minimax.updateHint', {
          defaultValue: 'Update available: {{version}}. Click Update to resync the selected MiniMax skills.',
          version: status.latestVersion,
        });
      }
      return t('chat.minimax.readyHint', {
        defaultValue: 'Use MiniMax for business workflow implementation and document-heavy finance tasks.',
      });
    }

    if (status.state === 'loading') {
      return t('chat.minimax.loadingHint', {
        defaultValue: 'Checking the current project for MiniMax skills...',
      });
    }

    if (status.state === 'partial') {
      return t('chat.minimax.partialHint', {
        defaultValue: 'Found a partial MiniMax setup. Click Repair to sync the selected skills bundle.',
      });
    }

    if (status.state === 'missing') {
      return t('chat.minimax.missingHint', {
        defaultValue: 'MiniMax is not set up for this project yet. Click Install to copy the selected skills.',
      });
    }

    if (status.state === 'unsupported') {
      return t('chat.minimax.unsupportedHint', {
        defaultValue: 'MiniMax onboarding currently supports Claude Code and Codex only.',
      });
    }

    return t('chat.minimax.errorHint', {
      defaultValue: 'MiniMax status check failed. Try Refresh or reinstall.',
    });
  })();

  const detailText = status.state === 'error' && status.message && status.message !== statusHint
    ? status.message
    : t('chat.minimax.detailHint', {
      defaultValue: 'Best fit: Vue-based finance systems that need workflow changes, spreadsheet processing, and PDF or DOCX outputs.',
    });
  const summaryText = status.state === 'ready' ? selectedDescription : statusHint;

  return (
    <div
      className="impeccable-bar"
      onMouseDown={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
    >
      <div className="impeccable-main">
        <div className="impeccable-head">
          <span className="impeccable-badge subtle">{providerLabel}</span>
          {status.installedVersion ? (
            <span className="impeccable-badge subtle">{status.installedVersion}</span>
          ) : null}
          {status.hasUpdate && status.latestVersion && !status.versionTrackingMissing ? (
            <span className="impeccable-badge subtle">-&gt; {status.latestVersion}</span>
          ) : null}
          <span className={`impeccable-badge state-${status.state}`}>{statusLabel}</span>
        </div>

        <div ref={pickerRef} className="impeccable-picker-wrap">
          <button
            type="button"
            className={`impeccable-picker${pickerOpen ? ' open' : ''}`}
            onClick={() => setPickerOpen((open) => !open)}
            disabled={commandDisabled}
            aria-label={t('chat.minimax.selectAria', { defaultValue: 'Select a MiniMax skill' })}
            aria-expanded={pickerOpen}
          >
            <span className="impeccable-picker-text">{renderedCommand}</span>
            <span className={`codicon codicon-chevron-${pickerOpen ? 'up' : 'down'}`} />
          </button>

          {pickerOpen ? (
            <div className="impeccable-menu">
              <div className="impeccable-menu-search">
                <span className="codicon codicon-search" />
                <input
                  ref={searchInputRef}
                  type="text"
                  className="impeccable-search-input"
                  value={searchTerm}
                  onChange={(event) => setSearchTerm(event.target.value)}
                  onKeyDown={(event) => event.stopPropagation()}
                  placeholder={t('chat.minimax.searchPlaceholder', {
                    defaultValue: 'Search skills or descriptions',
                  })}
                />
              </div>

              {hasFilteredPresets ? (
                GROUP_ORDER.map((group) => {
                  const items = filteredGroupedPresets[group];
                  if (items.length === 0) {
                    return null;
                  }

                  return (
                    <div key={group} className="impeccable-menu-group">
                      <div className="impeccable-group-label">
                        {t(`chat.minimax.groups.${group}`, {
                          defaultValue: GROUP_LABELS[group],
                        })}
                      </div>
                      {items.map((preset) => {
                        const description = t(preset.descriptionKey, {
                          defaultValue: preset.description,
                        });
                        const commandLabel = formatMiniMaxCommand(preset.command, status.provider);

                        return (
                          <button
                            key={preset.id}
                            type="button"
                            className={`impeccable-option${preset.id === selectedPreset.id ? ' active' : ''}`}
                            onClick={() => {
                              onPresetChange(preset.id);
                              setPickerOpen(false);
                            }}
                          >
                            <span className="impeccable-option-title">{commandLabel}</span>
                            <span className="impeccable-option-desc">{description}</span>
                          </button>
                        );
                      })}
                    </div>
                  );
                })
              ) : (
                <div className="impeccable-menu-empty">
                  {t('chat.minimax.searchEmpty', {
                    defaultValue: 'No matching MiniMax skills.',
                  })}
                </div>
              )}
            </div>
          ) : null}
        </div>

        <div className="impeccable-summary" title={selectedDescription}>
          <span className="impeccable-name">{renderedCommand}</span>
          <span className="impeccable-summary-text">{summaryText}</span>
        </div>

        <div className="impeccable-actions">
          {showInsertActions ? (
            <>
              <button
                type="button"
                className="impeccable-button"
                onClick={onInsert}
                disabled={commandDisabled}
              >
                {t('chat.minimax.insert', { defaultValue: 'Insert' })}
              </button>
              <button
                type="button"
                className="impeccable-button primary"
                onClick={onInsertAndSend}
                disabled={commandDisabled}
              >
                {t('chat.minimax.insertAndSend', { defaultValue: 'Insert & Send' })}
              </button>
            </>
          ) : null}

          <button
            type="button"
            className="impeccable-button"
            onClick={onRefresh}
            disabled={busy}
          >
            {t('chat.minimax.refresh', { defaultValue: 'Refresh' })}
          </button>

          {showInstallAction ? (
            <button
              type="button"
              className="impeccable-button primary"
              onClick={onInstall}
              disabled={installDisabled}
            >
              {installLabel}
            </button>
          ) : null}

          {showUpdateAction ? (
            <button
              type="button"
              className="impeccable-button primary"
              onClick={onUpdate}
              disabled={updateDisabled}
            >
              {t('chat.minimax.update', { defaultValue: 'Update' })}
            </button>
          ) : null}
        </div>
      </div>

      <div className="impeccable-detail">
        <span className="impeccable-detail-label">
          {t('chat.minimax.statusTitle', { defaultValue: 'Status' })}
        </span>
        <span className="impeccable-detail-text">{statusHint}</span>
      </div>

      <div className="impeccable-preview">
        <span className="impeccable-preview-label">
          {t('chat.minimax.preview', { defaultValue: 'Focus' })}
        </span>
        <span className="impeccable-preview-text">{detailText}</span>
      </div>
    </div>
  );
}

export default MiniMaxBar;
