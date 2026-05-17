import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  formatImpeccableCommand,
  type ImpeccableCommandPreset,
} from './impeccableCommands.js';
import type { ImpeccableToolbarProps } from './types.js';

const GROUP_LABELS: Record<ImpeccableCommandPreset['group'], string> = {
  create: 'Create',
  evaluate: 'Evaluate',
  refine: 'Refine',
  harden: 'Harden',
  system: 'System',
};

const GROUP_ORDER: ImpeccableCommandPreset['group'][] = ['create', 'evaluate', 'refine', 'harden', 'system'];

export function ImpeccableBar({
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
}: ImpeccableToolbarProps) {
  const { t } = useTranslation();
  const pickerRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');

  const groupedPresets = useMemo(() => {
    return presets.reduce<Record<ImpeccableCommandPreset['group'], ImpeccableCommandPreset[]>>(
      (acc, preset) => {
        acc[preset.group].push(preset);
        return acc;
      },
      { create: [], evaluate: [], refine: [], harden: [], system: [] },
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

    return GROUP_ORDER.reduce<Record<ImpeccableCommandPreset['group'], ImpeccableCommandPreset[]>>(
      (acc, group) => {
        acc[group] = groupedPresets[group].filter((preset) => {
          const description = t(preset.descriptionKey, {
            defaultValue: preset.description,
          });
          const commandLabel = formatImpeccableCommand(preset.command, status.provider);
          const searchText = `${commandLabel} ${description}`.toLowerCase();
          return searchText.includes(normalizedSearch);
        });
        return acc;
      },
      { create: [], evaluate: [], refine: [], harden: [], system: [] },
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
  const renderedCommand = formatImpeccableCommand(selectedPreset.command, status.provider);
  const selectedDescription = t(selectedPreset.descriptionKey, {
    defaultValue: selectedPreset.description,
  });
  const showInsertActions = status.state === 'ready';
  const showUpdateAction = status.state === 'ready' && status.hasUpdate === true;
  const showInstallAction =
    status.state === 'missing' || status.state === 'partial' || status.state === 'error';
  const installLabel = status.state === 'partial'
    ? t('chat.impeccable.repair', { defaultValue: 'Repair' })
    : t('chat.impeccable.install', { defaultValue: 'Install' });
  const statusLabel = {
    loading: t('chat.impeccable.status.loading', { defaultValue: 'Checking' }),
    ready: t('chat.impeccable.status.ready', { defaultValue: 'Ready' }),
    missing: t('chat.impeccable.status.missing', { defaultValue: 'Not Installed' }),
    partial: t('chat.impeccable.status.partial', { defaultValue: 'Needs Repair' }),
    unsupported: t('chat.impeccable.status.unsupported', { defaultValue: 'Unsupported' }),
    error: t('chat.impeccable.status.error', { defaultValue: 'Error' }),
  }[status.state];

  const statusHint = (() => {
    if (busy && installLog) {
      return installLog;
    }

    if (updating) {
      return t('chat.impeccable.updatingHint', {
        defaultValue: 'Updating Impeccable for the current project...',
      });
    }

    if (installing) {
      return t('chat.impeccable.installingHint', {
        defaultValue: 'Installing Impeccable for the current project...',
      });
    }

    if (status.state === 'ready') {
      if (status.hasUpdate && status.latestVersion) {
        return t('chat.impeccable.updateHint', {
          defaultValue: 'Update available: v{{version}}. Click Update to sync the project skills.',
          version: status.latestVersion,
        });
      }
      return t('chat.impeccable.readyHint', {
        defaultValue: 'Use Impeccable for design audits, normalization, and targeted UI refinement.',
      });
    }

    if (status.state === 'loading') {
      return t('chat.impeccable.loadingHint', {
        defaultValue: 'Checking the current project for Impeccable...',
      });
    }

    if (status.state === 'partial') {
      return t('chat.impeccable.partialHint', {
        defaultValue: 'Found an incomplete Impeccable setup. Click Repair to sync the official skill bundle.',
      });
    }

    if (status.state === 'missing') {
      return t('chat.impeccable.missingHint', {
        defaultValue: 'Impeccable is not set up for this project yet. Click Install to copy the official skills.',
      });
    }

    if (status.state === 'unsupported') {
      return t('chat.impeccable.unsupportedHint', {
        defaultValue: 'Impeccable onboarding currently supports Claude Code and Codex only.',
      });
    }

    return t('chat.impeccable.errorHint', {
      defaultValue: 'Impeccable status check failed. Try Refresh or reinstall.',
    });
  })();

  const detailText = status.state === 'error' && status.message && status.message !== statusHint
    ? status.message
    : t('chat.impeccable.detailHint', {
      defaultValue: 'Best fit: auditing and tightening existing product interfaces instead of free-form redesigns.',
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
            <span className="impeccable-badge subtle">v{status.installedVersion}</span>
          ) : null}
          {status.hasUpdate && status.latestVersion ? (
            <span className="impeccable-badge subtle">→v{status.latestVersion}</span>
          ) : null}
          <span className={`impeccable-badge state-${status.state}`}>{statusLabel}</span>
        </div>

        <div ref={pickerRef} className="impeccable-picker-wrap">
          <button
            type="button"
            className={`impeccable-picker${pickerOpen ? ' open' : ''}`}
            onClick={() => setPickerOpen((open) => !open)}
            disabled={commandDisabled}
            aria-label={t('chat.impeccable.selectAria', { defaultValue: 'Select an Impeccable command' })}
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
                  placeholder={t('chat.impeccable.searchPlaceholder', {
                    defaultValue: 'Search commands or descriptions',
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
                        {t(`chat.impeccable.groups.${group}`, {
                          defaultValue: GROUP_LABELS[group],
                        })}
                      </div>
                      {items.map((preset) => {
                        const description = t(preset.descriptionKey, {
                          defaultValue: preset.description,
                        });

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
                            <span className="impeccable-option-title">
                              {formatImpeccableCommand(preset.command, status.provider)}
                            </span>
                            <span className="impeccable-option-desc">{description}</span>
                          </button>
                        );
                      })}
                    </div>
                  );
                })
              ) : (
                <div className="impeccable-menu-empty">
                  {t('chat.impeccable.searchEmpty', {
                    defaultValue: 'No matching Impeccable commands.',
                  })}
                </div>
              )}
            </div>
          ) : null}
        </div>

        <div className="impeccable-summary" title={summaryText}>
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
                {t('chat.impeccable.insert', { defaultValue: 'Insert' })}
              </button>
              <button
                type="button"
                className="impeccable-button primary"
                onClick={onInsertAndSend}
                disabled={commandDisabled}
              >
                {t('chat.impeccable.insertAndSend', { defaultValue: 'Send' })}
              </button>
            </>
          ) : null}

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
              className="impeccable-button"
              onClick={onUpdate}
              disabled={updateDisabled}
            >
              {t('chat.impeccable.update', { defaultValue: 'Update' })}
            </button>
          ) : null}

          <button
            type="button"
            className="impeccable-button"
            onClick={onRefresh}
            disabled={busy}
          >
            {t('chat.impeccable.refresh', { defaultValue: 'Refresh' })}
          </button>
        </div>
      </div>

      <div className="impeccable-detail">
        <span className="impeccable-detail-label">
          {t('chat.impeccable.statusTitle', { defaultValue: 'Status' })}
        </span>
        <span className="impeccable-detail-text">{statusHint}</span>
      </div>

      <div className="impeccable-preview">
        <span className="impeccable-preview-label">
          {t('chat.impeccable.preview', { defaultValue: 'Focus' })}
        </span>
        <span className="impeccable-preview-text">{detailText}</span>
      </div>
    </div>
  );
}

export default ImpeccableBar;
