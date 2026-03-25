import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { getBmadProviderLabel, type BmadCommandPreset } from './bmadCommands.js';
import type { BmadToolbarProps } from './types.js';

const GROUP_LABELS: Record<BmadCommandPreset['group'], string> = {
  agents: 'Agents',
  analysis: 'Analysis',
  planning: 'Planning',
  solutioning: 'Solutioning',
  implementation: 'Implementation',
  utilities: 'Utilities',
};

const GROUP_ORDER: BmadCommandPreset['group'][] = [
  'agents',
  'analysis',
  'planning',
  'solutioning',
  'implementation',
  'utilities',
];

function getPresetKind(preset: BmadCommandPreset): 'agent' | 'workflow' {
  return preset.group === 'agents' ? 'agent' : 'workflow';
}

export function BmadCommandBar({
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
}: BmadToolbarProps) {
  const { t } = useTranslation();
  const pickerRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');

  const groupedPresets = useMemo(() => {
    return presets.reduce<Record<BmadCommandPreset['group'], BmadCommandPreset[]>>(
      (acc, preset) => {
        acc[preset.group].push(preset);
        return acc;
      },
      { agents: [], analysis: [], planning: [], solutioning: [], implementation: [], utilities: [] }
    );
  }, [presets]);

  const selectedPreset = useMemo(
    () => presets.find((preset) => preset.id === selectedPresetId) ?? presets[0],
    [presets, selectedPresetId]
  );

  const filteredGroupedPresets = useMemo(() => {
    const normalizedSearch = searchTerm.trim().toLowerCase();
    if (!normalizedSearch) {
      return groupedPresets;
    }

    return GROUP_ORDER.reduce<Record<BmadCommandPreset['group'], BmadCommandPreset[]>>(
      (acc, group) => {
        acc[group] = groupedPresets[group].filter((preset) => {
          const description = t(preset.descriptionKey, {
            defaultValue: preset.description,
          });
          const searchText = `${preset.command} ${description}`.toLowerCase();
          return searchText.includes(normalizedSearch);
        });
        return acc;
      },
      { agents: [], analysis: [], planning: [], solutioning: [], implementation: [], utilities: [] }
    );
  }, [groupedPresets, searchTerm, t]);

  const hasFilteredPresets = useMemo(
    () => GROUP_ORDER.some((group) => filteredGroupedPresets[group].length > 0),
    [filteredGroupedPresets]
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
    defaultValue: status.providerLabel || getBmadProviderLabel(status.provider),
  });
  const busy = operation !== null;
  const installing = operation === 'install';
  const updating = operation === 'update';
  const renderedCommand = `${status.commandPrefix}${selectedPreset.command}`;
  const selectedDescription = t(selectedPreset.descriptionKey, {
    defaultValue: selectedPreset.description,
  });
  const selectedPresetKind = getPresetKind(selectedPreset);
  const selectedPresetKindLabel = t(`chat.bmad.kinds.${selectedPresetKind}`, {
    defaultValue: selectedPresetKind === 'agent' ? 'Agent' : 'Workflow',
  });
  const installReady = status.nodeAvailable !== false && status.nodeSupported !== false;
  const showInsertActions = status.state === 'ready';
  const showUpdateAction = status.state === 'ready' && status.hasUpdate === true;
  const showInstallAction =
    installReady && (status.state === 'missing' || status.state === 'partial' || status.state === 'error');
  const installLabel = status.state === 'partial'
    ? t('chat.bmad.repair', { defaultValue: 'Repair' })
    : t('chat.bmad.install', { defaultValue: 'Install' });
  const statusLabel = {
    loading: t('chat.bmad.status.loading', { defaultValue: 'Checking' }),
    ready: t('chat.bmad.status.ready', { defaultValue: 'Ready' }),
    missing: t('chat.bmad.status.missing', { defaultValue: 'Not Installed' }),
    partial: t('chat.bmad.status.partial', { defaultValue: 'Needs Repair' }),
    unsupported: t('chat.bmad.status.unsupported', { defaultValue: 'Unsupported' }),
    error: t('chat.bmad.status.error', { defaultValue: 'Error' }),
  }[status.state];

  const statusHint = (() => {
    if (busy && installLog) {
      return installLog;
    }

    if (updating) {
      return t('chat.bmad.updatingHint', {
        defaultValue: 'Updating BMad for the current project...',
      });
    }

    if (installing) {
      return t('chat.bmad.installingHint', {
        defaultValue: 'Installing BMad for the current project...',
      });
    }

    if (status.state === 'ready') {
      if (status.hasUpdate && status.latestVersion) {
        return t('chat.bmad.updateHint', {
          defaultValue: 'Update available: v{{version}}. Click Update to sync the current project.',
          version: status.latestVersion,
        });
      }
      return t('chat.bmad.readyHint', {
        defaultValue: 'Choose a workflow, then click Insert or Send.',
      });
    }

    if (status.state === 'loading') {
      return t('chat.bmad.loadingHint', {
        defaultValue: 'Checking the current project for a BMad setup...',
      });
    }

    if (status.state === 'missing') {
      if (status.nodeAvailable === false || status.nodeSupported === false) {
        return t('chat.bmad.nodeHint', {
          defaultValue: 'Install Node.js 18 or later first, then return here for one-click setup.',
        });
      }
      return t('chat.bmad.missingHint', {
        defaultValue: 'BMad is not set up for this project yet. Click Install to prepare it.',
      });
    }

    if (status.state === 'partial') {
      return t('chat.bmad.partialHint', {
        defaultValue: 'A partial BMad setup was detected. Click Repair to complete it.',
      });
    }

    if (status.state === 'unsupported') {
      return t('chat.bmad.unsupportedHint', {
        defaultValue: 'BMad onboarding currently supports Claude Code and Codex only.',
      });
    }

    return t('chat.bmad.errorHint', {
      defaultValue: 'BMad status check failed. Try Refresh or reinstall.',
    });
  })();

  const detailText = status.state === 'error' && status.message && status.message !== statusHint
    ? status.message
    : '';
  const summaryText = status.state === 'ready' ? selectedDescription : statusHint;

  return (
    <div
      className="bmad-command-bar"
      onMouseDown={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
    >
      <div className="bmad-command-main">
        <div className="bmad-command-head">
          <span className="bmad-command-badge subtle">{providerLabel}</span>
          {status.installedVersion ? (
            <span className="bmad-command-badge subtle">v{status.installedVersion}</span>
          ) : null}
          {status.hasUpdate && status.latestVersion ? (
            <span className="bmad-command-badge subtle">→ v{status.latestVersion}</span>
          ) : null}
          <span className={`bmad-command-badge state-${status.state}`}>{statusLabel}</span>
        </div>

        <div ref={pickerRef} className="bmad-command-picker-wrap">
          <button
            type="button"
            className={`bmad-command-picker${pickerOpen ? ' open' : ''}`}
            onClick={() => setPickerOpen((open) => !open)}
            disabled={commandDisabled}
            aria-label={t('chat.bmad.selectAria', { defaultValue: 'Select a BMad workflow' })}
            aria-expanded={pickerOpen}
          >
            <span className="bmad-command-picker-text">{renderedCommand}</span>
            <span className={`codicon codicon-chevron-${pickerOpen ? 'up' : 'down'}`} />
          </button>

          {pickerOpen ? (
            <div className="bmad-command-menu">
              <div className="bmad-command-menu-search">
                <span className="codicon codicon-search" />
                <input
                  ref={searchInputRef}
                  type="text"
                  className="bmad-command-search-input"
                  value={searchTerm}
                  onChange={(event) => setSearchTerm(event.target.value)}
                  onKeyDown={(event) => event.stopPropagation()}
                  placeholder={t('chat.bmad.searchPlaceholder', {
                    defaultValue: 'Search commands',
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
                    <div key={group} className="bmad-command-menu-group">
                      <div className="bmad-command-group-label">
                        {t(`chat.bmad.groups.${group}`, {
                          defaultValue: GROUP_LABELS[group],
                        })}
                      </div>
                      {items.map((preset) => {
                        const description = t(preset.descriptionKey, {
                          defaultValue: preset.description,
                        });
                        const presetKind = getPresetKind(preset);
                        const presetKindLabel = t(`chat.bmad.kinds.${presetKind}`, {
                          defaultValue: presetKind === 'agent' ? 'Agent' : 'Workflow',
                        });

                        return (
                          <button
                            key={preset.id}
                            type="button"
                            className={`bmad-command-option${preset.id === selectedPreset.id ? ' active' : ''}`}
                            onClick={() => {
                              onPresetChange(preset.id);
                              setPickerOpen(false);
                            }}
                          >
                            <span className="bmad-command-option-heading">
                              <span className="bmad-command-option-title">
                                {status.commandPrefix}{preset.command}
                              </span>
                              <span className={`bmad-command-kind-badge kind-${presetKind}`}>
                                {presetKindLabel}
                              </span>
                            </span>
                            <span className="bmad-command-option-desc">{description}</span>
                          </button>
                        );
                      })}
                    </div>
                  );
                })
              ) : (
                <div className="bmad-command-menu-empty">
                  {t('chat.bmad.searchEmpty', {
                    defaultValue: 'No matching BMad commands.',
                  })}
                </div>
              )}
            </div>
          ) : null}
        </div>

        <div className="bmad-command-summary" title={summaryText}>
          <span className="bmad-command-name">{renderedCommand}</span>
          <span className={`bmad-command-kind-badge kind-${selectedPresetKind}`}>
            {selectedPresetKindLabel}
          </span>
          <span className="bmad-command-summary-text">{summaryText}</span>
        </div>

        <div className="bmad-command-actions">
          {showInsertActions ? (
            <>
              <button
                type="button"
                className="bmad-command-button"
                onClick={onInsert}
                disabled={commandDisabled}
              >
                {t('chat.bmad.insert', { defaultValue: 'Insert' })}
              </button>
              <button
                type="button"
                className="bmad-command-button primary"
                onClick={onInsertAndSend}
                disabled={commandDisabled}
              >
                {t('chat.bmad.insertAndSend', { defaultValue: 'Send' })}
              </button>
            </>
          ) : null}

          {showInstallAction ? (
            <button
              type="button"
              className="bmad-command-button primary"
              onClick={onInstall}
              disabled={installDisabled}
            >
              {installing
                ? t('chat.bmad.installing', { defaultValue: 'Installing...' })
                : installLabel}
            </button>
          ) : null}

          {showUpdateAction ? (
            <button
              type="button"
              className="bmad-command-button primary"
              onClick={onUpdate}
              disabled={updateDisabled}
            >
              {updating
                ? t('settings.dependency.updating', { defaultValue: 'Updating...' })
                : t('settings.dependency.update', { defaultValue: 'Update' })}
            </button>
          ) : null}

          <button
            type="button"
            className="bmad-command-button"
            onClick={onRefresh}
            disabled={busy}
          >
            {t('chat.bmad.refresh', { defaultValue: 'Refresh' })}
          </button>
        </div>
      </div>

      {detailText ? (
        <div className="bmad-command-detail" title={detailText}>
          {detailText}
        </div>
      ) : null}
    </div>
  );
}
