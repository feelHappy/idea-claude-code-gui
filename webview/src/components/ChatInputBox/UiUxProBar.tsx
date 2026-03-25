import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UiUxPromptPreset } from './uiUxProPrompts.js';
import type { UiUxToolbarProps } from './types.js';

const GROUP_LABELS: Record<UiUxPromptPreset['group'], string> = {
  create: 'Create',
  review: 'Review',
  system: 'System',
};

const GROUP_ORDER: UiUxPromptPreset['group'][] = ['create', 'review', 'system'];

export function UiUxProBar({
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
  promptDisabled = false,
  installDisabled = false,
  updateDisabled = false,
}: UiUxToolbarProps) {
  const { t } = useTranslation();
  const pickerRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');

  const groupedPresets = useMemo(() => {
    return presets.reduce<Record<UiUxPromptPreset['group'], UiUxPromptPreset[]>>(
      (acc, preset) => {
        acc[preset.group].push(preset);
        return acc;
      },
      { create: [], review: [], system: [] }
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

    return GROUP_ORDER.reduce<Record<UiUxPromptPreset['group'], UiUxPromptPreset[]>>(
      (acc, group) => {
        acc[group] = groupedPresets[group].filter((preset) => {
          const title = t(preset.titleKey, { defaultValue: preset.title });
          const description = t(preset.descriptionKey, { defaultValue: preset.description });
          const prompt = t(preset.promptKey, { defaultValue: preset.prompt });
          const searchText = `${title} ${description} ${prompt}`.toLowerCase();
          return searchText.includes(normalizedSearch);
        });
        return acc;
      },
      { create: [], review: [], system: [] }
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
    defaultValue: status.providerLabel || status.provider,
  });
  const busy = operation !== null;
  const installing = operation === 'install';
  const updating = operation === 'update';
  const selectedTitle = t(selectedPreset.titleKey, { defaultValue: selectedPreset.title });
  const selectedDescription = t(selectedPreset.descriptionKey, {
    defaultValue: selectedPreset.description,
  });
  const selectedPrompt = t(selectedPreset.promptKey, { defaultValue: selectedPreset.prompt });
  const hasMissingRuntime =
    status.nodeAvailable === false ||
    status.nodeSupported === false ||
    status.pythonAvailable === false ||
    status.pythonSupported === false;
  const showInsertActions = status.state === 'ready';
  const showUpdateAction = status.state === 'ready' && status.hasUpdate === true;
  const showInstallAction =
    status.state === 'missing' || status.state === 'partial' || status.state === 'error';
  const installLabel = hasMissingRuntime && status.runtimeBootstrapSupported
    ? t('chat.uiUxPro.installWithDeps', { defaultValue: 'Install Dependencies' })
    : status.state === 'partial'
    ? t('chat.uiUxPro.repair', { defaultValue: 'Repair' })
    : t('chat.uiUxPro.install', { defaultValue: 'Install' });
  const statusLabel = {
    loading: t('chat.uiUxPro.status.loading', { defaultValue: 'Checking' }),
    ready: t('chat.uiUxPro.status.ready', { defaultValue: 'Ready' }),
    missing: t('chat.uiUxPro.status.missing', { defaultValue: 'Not Installed' }),
    partial: t('chat.uiUxPro.status.partial', { defaultValue: 'Needs Repair' }),
    unsupported: t('chat.uiUxPro.status.unsupported', { defaultValue: 'Unsupported' }),
    error: t('chat.uiUxPro.status.error', { defaultValue: 'Error' }),
  }[status.state];

  const statusHint = (() => {
    if (busy && installLog) {
      return installLog;
    }

    if (updating) {
      return t('chat.uiUxPro.updatingHint', {
        defaultValue: 'Updating UI UX Pro Max for the current project...',
      });
    }

    if (installing) {
      return t('chat.uiUxPro.installingHint', {
        defaultValue: 'Installing UI UX Pro Max for the current project...',
      });
    }

    if (status.state === 'ready') {
      if (status.hasUpdate && status.latestVersion) {
        return t('chat.uiUxPro.updateHint', {
          defaultValue: 'Update available: v{{version}}. Click Update to refresh the installed skill bundle.',
          version: status.latestVersion,
        });
      }
      return t('chat.uiUxPro.readyHint', {
        defaultValue: 'Install complete. Insert a starter prompt, or describe your UI/UX task naturally.',
      });
    }

    if (status.state === 'loading') {
      return t('chat.uiUxPro.loadingHint', {
        defaultValue: 'Checking the current project for UI UX Pro Max...',
      });
    }

    if (status.nodeAvailable === false || status.nodeSupported === false) {
      return status.runtimeBootstrapSupported
        ? t('chat.uiUxPro.nodeBootstrapHint', {
            defaultValue: 'Node.js is missing. Click Install and the plugin will try to install it automatically on Windows.',
          })
        : t('chat.uiUxPro.nodeHint', {
            defaultValue: 'Install Node.js 18 or later first, then return here for one-click setup.',
          });
    }

    if (status.pythonAvailable === false || status.pythonSupported === false) {
      return status.runtimeBootstrapSupported
        ? t('chat.uiUxPro.pythonBootstrapHint', {
            defaultValue: 'Python 3.x is missing. Click Install and the plugin will try to install it automatically on Windows.',
          })
        : t('chat.uiUxPro.pythonHint', {
            defaultValue: 'Install Python 3.x first, then return here for one-click setup.',
          });
    }

    if (status.state === 'missing') {
      return t('chat.uiUxPro.missingHint', {
        defaultValue: 'UI UX Pro Max is not set up for this project yet. Click Install to prepare it.',
      });
    }

    if (status.state === 'partial') {
      return t('chat.uiUxPro.partialHint', {
        defaultValue: 'A partial UI UX Pro Max setup was detected. Click Repair to complete it.',
      });
    }

    if (status.state === 'unsupported') {
      return t('chat.uiUxPro.unsupportedHint', {
        defaultValue: 'UI UX Pro Max onboarding currently supports Claude Code and Codex only.',
      });
    }

    return t('chat.uiUxPro.errorHint', {
      defaultValue: 'UI UX Pro Max status check failed. Try Refresh or reinstall.',
    });
  })();

  const autoTriggerHint = status.explicitCommand
    ? t('chat.uiUxPro.explicitHint', {
        defaultValue: 'Auto-trigger works with natural UI/UX requests. Codex can also invoke {{command}} explicitly.',
        command: status.explicitCommand,
      })
    : t('chat.uiUxPro.autoTriggerHint', {
        defaultValue: 'Install once, then describe UI/UX needs naturally to activate the skill.',
      });
  const detailText = status.state === 'error' && status.message && status.message !== statusHint
    ? status.message
    : autoTriggerHint;

  return (
    <div
      className="uiux-pro-bar"
      onMouseDown={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
    >
      <div className="uiux-pro-main">
        <div className="uiux-pro-head">
          <span className="uiux-pro-badge subtle">{providerLabel}</span>
          {status.installedVersion ? (
            <span className="uiux-pro-badge subtle">v{status.installedVersion}</span>
          ) : null}
          {status.hasUpdate && status.latestVersion ? (
            <span className="uiux-pro-badge subtle">→ v{status.latestVersion}</span>
          ) : null}
          <span className={`uiux-pro-badge state-${status.state}`}>{statusLabel}</span>
        </div>

        <div ref={pickerRef} className="uiux-pro-picker-wrap">
          <button
            type="button"
            className={`uiux-pro-picker${pickerOpen ? ' open' : ''}`}
            onClick={() => setPickerOpen((open) => !open)}
            disabled={promptDisabled}
            aria-label={t('chat.uiUxPro.selectAria', { defaultValue: 'Select a UI UX Pro Max starter prompt' })}
            aria-expanded={pickerOpen}
          >
            <span className="uiux-pro-picker-text">{selectedTitle}</span>
            <span className={`codicon codicon-chevron-${pickerOpen ? 'up' : 'down'}`} />
          </button>

          {pickerOpen ? (
            <div className="uiux-pro-menu">
              <div className="uiux-pro-menu-search">
                <span className="codicon codicon-search" />
                <input
                  ref={searchInputRef}
                  type="text"
                  className="uiux-pro-search-input"
                  value={searchTerm}
                  onChange={(event) => setSearchTerm(event.target.value)}
                  onKeyDown={(event) => event.stopPropagation()}
                  placeholder={t('chat.uiUxPro.searchPlaceholder', {
                    defaultValue: 'Search scenes or descriptions',
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
                    <div key={group} className="uiux-pro-menu-group">
                      <div className="uiux-pro-group-label">
                        {t(`chat.uiUxPro.groups.${group}`, {
                          defaultValue: GROUP_LABELS[group],
                        })}
                      </div>
                      {items.map((preset) => {
                        const title = t(preset.titleKey, { defaultValue: preset.title });
                        const description = t(preset.descriptionKey, {
                          defaultValue: preset.description,
                        });

                        return (
                          <button
                            key={preset.id}
                            type="button"
                            className={`uiux-pro-option${preset.id === selectedPreset.id ? ' active' : ''}`}
                            onClick={() => {
                              onPresetChange(preset.id);
                              setPickerOpen(false);
                            }}
                          >
                            <span className="uiux-pro-option-title">{title}</span>
                            <span className="uiux-pro-option-desc">{description}</span>
                          </button>
                        );
                      })}
                    </div>
                  );
                })
              ) : (
                <div className="uiux-pro-menu-empty">
                  {t('chat.uiUxPro.searchEmpty', {
                    defaultValue: 'No matching UI UX Pro Max prompts.',
                  })}
                </div>
              )}
            </div>
          ) : null}
        </div>

        <div className="uiux-pro-summary" title={selectedDescription}>
          <span className="uiux-pro-name">{selectedTitle}</span>
          <span className="uiux-pro-summary-text">{selectedDescription}</span>
        </div>

      </div>

      <div className="uiux-pro-detail" title={detailText}>
        <span className="uiux-pro-detail-text">{statusHint}</span>
        <span className="uiux-pro-detail-separator">·</span>
        <span className="uiux-pro-detail-text">{detailText}</span>
      </div>

      <div className="uiux-pro-preview" title={selectedPrompt}>
        <span className="uiux-pro-preview-label">
          {t('chat.uiUxPro.preview', { defaultValue: 'Starter prompt' })}
        </span>
        <span className="uiux-pro-preview-text">{selectedPrompt}</span>
      </div>

      <div className="uiux-pro-actions">
        {showInsertActions ? (
          <>
            <button
              type="button"
              className="uiux-pro-button"
              onClick={onInsert}
              disabled={promptDisabled}
            >
              {t('chat.uiUxPro.insert', { defaultValue: 'Insert' })}
            </button>
            <button
              type="button"
              className="uiux-pro-button primary"
              onClick={onInsertAndSend}
              disabled={promptDisabled}
            >
              {t('chat.uiUxPro.insertAndSend', { defaultValue: 'Send' })}
            </button>
          </>
        ) : null}

        {showInstallAction ? (
          <button
            type="button"
            className="uiux-pro-button primary"
            onClick={onInstall}
            disabled={installDisabled}
          >
            {installing
              ? t('chat.uiUxPro.installing', { defaultValue: 'Installing...' })
              : installLabel}
          </button>
        ) : null}

        {showUpdateAction ? (
          <button
            type="button"
            className="uiux-pro-button primary"
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
          className="uiux-pro-button"
          onClick={onRefresh}
          disabled={busy}
        >
          {t('chat.uiUxPro.refresh', { defaultValue: 'Refresh' })}
        </button>
      </div>
    </div>
  );
}
