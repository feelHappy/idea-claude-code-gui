import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { getGitNexusAvailableScopes, type GitNexusPromptPreset } from './gitNexusPrompts.js';
import type { GitNexusToolbarProps } from './types.js';

const GROUP_LABELS: Record<GitNexusPromptPreset['group'], string> = {
  understand: 'Understand',
  change: 'Change',
};

const GROUP_ORDER: GitNexusPromptPreset['group'][] = ['understand', 'change'];

export function GitNexusBar({
  presets,
  selectedPresetId,
  status,
  installLog,
  operation = null,
  selectedScope,
  onPresetChange,
  onScopeChange,
  onInsert,
  onInsertAndSend,
  onRefresh,
  onInstall,
  onUpdate,
  onReindex,
  promptDisabled = false,
  installDisabled = false,
  updateDisabled = false,
  reindexDisabled = false,
}: GitNexusToolbarProps) {
  const { t } = useTranslation();
  const pickerRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');

  const groupedPresets = useMemo(() => {
    return presets.reduce<Record<GitNexusPromptPreset['group'], GitNexusPromptPreset[]>>(
      (acc, preset) => {
        acc[preset.group].push(preset);
        return acc;
      },
      { understand: [], change: [] }
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

    return GROUP_ORDER.reduce<Record<GitNexusPromptPreset['group'], GitNexusPromptPreset[]>>(
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
      { understand: [], change: [] }
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
  const reindexing = operation === 'reindex';
  const selectedTitle = t(selectedPreset.titleKey, { defaultValue: selectedPreset.title });
  const selectedDescription = t(selectedPreset.descriptionKey, {
    defaultValue: selectedPreset.description,
  });
  const selectedPrompt = t(selectedPreset.promptKey, { defaultValue: selectedPreset.prompt });
  const availableScopes = getGitNexusAvailableScopes(status);
  const hasMissingRuntime = status.nodeAvailable === false || status.nodeSupported === false;
  const showInsertActions = status.state === 'ready';
  const showUpdateAction = status.state === 'ready' && status.hasUpdate === true;
  const showInstallAction = status.state === 'missing' || status.state === 'partial' || status.state === 'error';
  const showReindexAction = status.state === 'ready' || status.state === 'partial';
  const installLabel = hasMissingRuntime && status.runtimeBootstrapSupported
    ? t('chat.gitNexus.installWithDeps', { defaultValue: 'Install Dependencies' })
    : status.state === 'partial'
      ? t('chat.gitNexus.repair', { defaultValue: 'Repair' })
      : t('chat.gitNexus.install', { defaultValue: 'Install' });
  const statusLabel = {
    loading: t('chat.gitNexus.status.loading', { defaultValue: 'Checking' }),
    ready: t('chat.gitNexus.status.ready', { defaultValue: 'Ready' }),
    missing: t('chat.gitNexus.status.missing', { defaultValue: 'Not Installed' }),
    partial: t('chat.gitNexus.status.partial', { defaultValue: 'Needs Repair' }),
    unsupported: t('chat.gitNexus.status.unsupported', { defaultValue: 'Unsupported' }),
    error: t('chat.gitNexus.status.error', { defaultValue: 'Error' }),
  }[status.state];

  const statusHint = (() => {
    if (busy && installLog) {
      return installLog;
    }

    if (updating) {
      return t('chat.gitNexus.updatingHint', {
        defaultValue: 'Updating GitNexus for the current repository...',
      });
    }

    if (reindexing) {
      return t('chat.gitNexus.reindexingHint', {
        defaultValue: 'Rebuilding the GitNexus repository index...',
      });
    }

    if (installing) {
      return t('chat.gitNexus.installingHint', {
        defaultValue: 'Installing GitNexus for the current repository...',
      });
    }

    if (status.state === 'ready') {
      if (status.hasUpdate && status.latestVersion) {
        return t('chat.gitNexus.updateHint', {
          defaultValue: 'Update available: v{{version}}. Click Update to refresh GitNexus and rebuild this repo index.',
          version: status.latestVersion,
        });
      }
      return t('chat.gitNexus.readyHint', {
        defaultValue: 'GitNexus is ready. Insert a repo question, or ask architecture questions directly.',
      });
    }

    if (status.state === 'loading') {
      return t('chat.gitNexus.loadingHint', {
        defaultValue: 'Checking the current repository for GitNexus...',
      });
    }

    if (status.nodeAvailable === false || status.nodeSupported === false) {
      return status.runtimeBootstrapSupported
        ? t('chat.gitNexus.nodeBootstrapHint', {
            defaultValue: 'Node.js is missing. Click Install and the plugin will try to install it automatically on Windows.',
          })
        : t('chat.gitNexus.nodeHint', {
            defaultValue: 'Install Node.js 18 or later first, then return here for one-click setup.',
          });
    }

    if (status.repositoryDetected === false) {
      return t('chat.gitNexus.repoHint', {
        defaultValue: 'GitNexus works on Git repositories. Open the repo root and retry.',
      });
    }

    if (status.state === 'missing') {
      return t('chat.gitNexus.missingHint', {
        defaultValue: 'GitNexus is not set up for this repository yet. Click Install to configure it.',
      });
    }

    if (status.state === 'partial') {
      return t('chat.gitNexus.partialHint', {
        defaultValue: 'A partial GitNexus setup was detected. Click Install or Reindex to complete it.',
      });
    }

    if (status.state === 'unsupported') {
      return t('chat.gitNexus.unsupportedHint', {
        defaultValue: 'GitNexus onboarding currently supports Claude Code and Codex only.',
      });
    }

    return t('chat.gitNexus.errorHint', {
      defaultValue: 'GitNexus status check failed. Try Refresh or reinstall.',
    });
  })();

  const detailText = t('chat.gitNexus.detailHint', {
    defaultValue: 'GitNexus builds a repository graph and MCP tools, so repo-wide reasoning becomes much more accurate.',
  });
  const scopePath = selectedScope === 'directory'
    ? status.currentDirectory
    : selectedScope === 'module'
      ? status.currentModuleRoot
      : status.projectRoot;
  const scopeSummary = selectedScope === 'directory'
    ? t('chat.gitNexus.scopeSummary.directory', {
        defaultValue: 'Current directory: {{path}}',
        path: scopePath ?? '-',
      })
    : selectedScope === 'module'
      ? t('chat.gitNexus.scopeSummary.module', {
          defaultValue: 'Current module: {{path}}',
          path: scopePath ?? '-',
        })
      : t('chat.gitNexus.scopeSummary.repo', {
          defaultValue: 'Whole repository: {{path}}',
          path: scopePath ?? '-',
        });

  return (
    <div
      className="gitnexus-bar"
      onMouseDown={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
    >
      <div className="gitnexus-main">
        <div className="gitnexus-head">
          <span className="gitnexus-badge subtle">{providerLabel}</span>
          {status.installedVersion ? (
            <span className="gitnexus-badge subtle">v{status.installedVersion}</span>
          ) : null}
          {status.hasUpdate && status.latestVersion ? (
            <span className="gitnexus-badge subtle">→ v{status.latestVersion}</span>
          ) : null}
          <span className={`gitnexus-badge state-${status.state}`}>{statusLabel}</span>
        </div>

        <div ref={pickerRef} className="gitnexus-picker-wrap">
          <button
            type="button"
            className={`gitnexus-picker${pickerOpen ? ' open' : ''}`}
            onClick={() => setPickerOpen((open) => !open)}
            disabled={promptDisabled}
            aria-label={t('chat.gitNexus.selectAria', { defaultValue: 'Select a GitNexus starter prompt' })}
            aria-expanded={pickerOpen}
          >
            <span className="gitnexus-picker-text">{selectedTitle}</span>
            <span className={`codicon codicon-chevron-${pickerOpen ? 'up' : 'down'}`} />
          </button>

          {pickerOpen ? (
            <div className="gitnexus-menu">
              <div className="gitnexus-menu-search">
                <span className="codicon codicon-search" />
                <input
                  ref={searchInputRef}
                  type="text"
                  className="gitnexus-search-input"
                  value={searchTerm}
                  onChange={(event) => setSearchTerm(event.target.value)}
                  onKeyDown={(event) => event.stopPropagation()}
                  placeholder={t('chat.gitNexus.searchPlaceholder', {
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
                    <div key={group} className="gitnexus-menu-group">
                      <div className="gitnexus-group-label">
                        {t(`chat.gitNexus.groups.${group}`, {
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
                            className={`gitnexus-option${preset.id === selectedPreset.id ? ' active' : ''}`}
                            onClick={() => {
                              onPresetChange(preset.id);
                              setPickerOpen(false);
                            }}
                          >
                            <span className="gitnexus-option-title">{title}</span>
                            <span className="gitnexus-option-desc">{description}</span>
                          </button>
                        );
                      })}
                    </div>
                  );
                })
              ) : (
                <div className="gitnexus-menu-empty">
                  {t('chat.gitNexus.searchEmpty', {
                    defaultValue: 'No matching GitNexus prompts.',
                  })}
                </div>
              )}
            </div>
          ) : null}
        </div>

        <div className="gitnexus-summary" title={selectedDescription}>
          <span className="gitnexus-name">{selectedTitle}</span>
          <span className="gitnexus-summary-text">{selectedDescription}</span>
        </div>

      </div>

      <div className="gitnexus-detail" title={detailText}>
        <span className="gitnexus-detail-text">{statusHint}</span>
        <span className="gitnexus-detail-separator">·</span>
        <span className="gitnexus-detail-text">{detailText}</span>
      </div>

      <div className="gitnexus-scope">
        <span className="gitnexus-scope-label">
          {t('chat.gitNexus.scopeLabel', { defaultValue: 'Analysis scope' })}
        </span>
        <div className="gitnexus-scope-actions">
          {(['repo', 'directory', 'module'] as const).map((scope) => {
            const enabled = availableScopes.includes(scope);
            if (!enabled && scope !== 'repo') {
              return null;
            }

            const label = t(`chat.gitNexus.scopes.${scope}`, {
              defaultValue: scope === 'repo' ? 'Repository' : scope === 'directory' ? 'Current Directory' : 'Current Module',
            });

            return (
              <button
                key={scope}
                type="button"
                className={`gitnexus-scope-chip${selectedScope === scope ? ' active' : ''}`}
                onClick={() => onScopeChange(scope)}
                disabled={!enabled}
                title={scopeSummary}
              >
                {label}
              </button>
            );
          })}
        </div>
        <span className="gitnexus-scope-text" title={scopeSummary}>{scopeSummary}</span>
      </div>

      <div className="gitnexus-preview" title={selectedPrompt}>
        <span className="gitnexus-preview-label">
          {t('chat.gitNexus.preview', { defaultValue: 'Starter prompt' })}
        </span>
        <span className="gitnexus-preview-text">{selectedPrompt}</span>
      </div>

      <div className="gitnexus-actions">
        {showInsertActions ? (
          <>
            <button
              type="button"
              className="gitnexus-button"
              onClick={onInsert}
              disabled={promptDisabled}
            >
              {t('chat.gitNexus.insert', { defaultValue: 'Insert' })}
            </button>
            <button
              type="button"
              className="gitnexus-button primary"
              onClick={onInsertAndSend}
              disabled={promptDisabled}
            >
              {t('chat.gitNexus.insertAndSend', { defaultValue: 'Send' })}
            </button>
          </>
        ) : null}

        {showInstallAction ? (
          <button
            type="button"
            className="gitnexus-button primary"
            onClick={onInstall}
            disabled={installDisabled}
          >
            {installing
              ? t('chat.gitNexus.installing', { defaultValue: 'Installing...' })
              : installLabel}
          </button>
        ) : null}

        {showUpdateAction ? (
          <button
            type="button"
            className="gitnexus-button primary"
            onClick={onUpdate}
            disabled={updateDisabled}
          >
            {updating
              ? t('settings.dependency.updating', { defaultValue: 'Updating...' })
              : t('settings.dependency.update', { defaultValue: 'Update' })}
          </button>
        ) : null}

        {showReindexAction ? (
          <button
            type="button"
            className="gitnexus-button"
            onClick={onReindex}
            disabled={reindexDisabled}
          >
            {reindexing
              ? t('chat.gitNexus.reindexing', { defaultValue: 'Reindexing...' })
              : t('chat.gitNexus.reindex', { defaultValue: 'Reindex' })}
          </button>
        ) : null}

        <button
          type="button"
          className="gitnexus-button"
          onClick={onRefresh}
          disabled={busy}
        >
          {t('chat.gitNexus.refresh', { defaultValue: 'Refresh' })}
        </button>
      </div>
    </div>
  );
}
