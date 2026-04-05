import { useState, useEffect, useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { RegistrySkillItem, RegistryInstallResult } from '../../types/registry';
import type { SkillsConfig } from '../../types/skill';
import { sendToJava } from '../../utils/bridge';
import { ToastContainer, type ToastMessage } from '../Toast';

type InstallScope = 'local' | 'global';

interface SkillRegistryPanelProps {
  /** 已安装的 skills 数据，用于判断是否已安装 */
  installedSkills?: SkillsConfig;
  /** 当前 provider */
  currentProvider?: string;
}

/**
 * Skill 远程仓库面板
 * 展示 Nacos Registry 中的 Skill 列表，支持搜索和安装
 */
export function SkillRegistryPanel({ installedSkills, currentProvider = 'claude' }: SkillRegistryPanelProps) {
  const { t } = useTranslation();
  const [skills, setSkills] = useState<RegistrySkillItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [installingSkills, setInstallingSkills] = useState<Set<string>>(new Set());
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const [installScope, setInstallScope] = useState<InstallScope>('local');

  // 收集所有已安装 skill 名称（不区分 scope）
  const installedNames = useMemo(() => {
    const names = new Set<string>();
    if (!installedSkills) return names;
    const maps = [installedSkills.global, installedSkills.local, installedSkills.user, installedSkills.repo];
    for (const m of maps) {
      if (m) {
        for (const skill of Object.values(m)) {
          names.add(skill.name.toLowerCase());
        }
      }
    }
    return names;
  }, [installedSkills]);

  const addToast = useCallback((message: string, type: ToastMessage['type'] = 'info') => {
    const id = `toast-${Date.now()}-${Math.random()}`;
    setToasts((prev) => [...prev, { id, message, type }]);
  }, []);

  const dismissToast = (id: string) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  };

  const loadSkills = useCallback(() => {
    setLoading(true);
    sendToJava('fetch_registry_skills', {});
  }, []);

  useEffect(() => {
    window.updateRegistrySkills = (jsonStr: string) => {
      try {
        const response = JSON.parse(jsonStr);
        let rawItems: Record<string, unknown>[] = [];
        if (Array.isArray(response)) {
          rawItems = response;
        } else if (response.data && Array.isArray(response.data)) {
          rawItems = response.data;
        } else if (response.data && Array.isArray(response.data.pageItems)) {
          rawItems = response.data.pageItems;
        } else if (response.data && Array.isArray(response.data.list)) {
          rawItems = response.data.list;
        }
        // 映射 Nacos 字段名到前端类型（注意 editingVersion/reviewingVersion 可能为 null）
        const items: RegistrySkillItem[] = rawItems.map((raw) => ({
          name: String(raw.name || ''),
          version: String(raw.editingVersion || raw.reviewingVersion || raw.version || ''),
          description: String(raw.description || ''),
          author: String(raw.author || raw.from || ''),
          tags: Array.isArray(raw.bizTags) ? raw.bizTags : raw.tags ? [].concat(raw.tags as never) : [],
          updateTime: raw.updateTime ? String(raw.updateTime) : undefined,
        }));
        setSkills(items);
      } catch (error) {
        console.error('[SkillRegistry] Failed to parse skills:', error);
        setSkills([]);
      }
      setLoading(false);
    };

    window.registrySkillInstallResult = (jsonStr: string) => {
      try {
        const result: RegistryInstallResult = JSON.parse(jsonStr);
        setInstallingSkills((prev) => {
          const next = new Set(prev);
          if (result.name) next.delete(result.name);
          return next;
        });
        if (result.success) {
          addToast(t('registry.skill.installSuccess', { name: result.name }), 'success');
          // 触发已安装列表刷新
          sendToJava('get_all_skills', {});
        } else {
          addToast(result.error || t('registry.skill.installFailed'), 'error');
        }
      } catch (error) {
        console.error('[SkillRegistry] Failed to parse install result:', error);
        setInstallingSkills(new Set());
      }
    };

    loadSkills();

    return () => {
      window.updateRegistrySkills = undefined;
      window.registrySkillInstallResult = undefined;
    };
  }, [loadSkills, addToast, t]);

  const filteredSkills = useMemo(() => {
    if (!searchQuery) return skills;
    const query = searchQuery.toLowerCase();
    return skills.filter(
      (s) =>
        s.name.toLowerCase().includes(query) ||
        (s.description && s.description.toLowerCase().includes(query)) ||
        (s.tags && s.tags.some((tag) => tag.toLowerCase().includes(query)))
    );
  }, [skills, searchQuery]);

  const handleInstall = useCallback(
    (skill: RegistrySkillItem) => {
      if (installingSkills.has(skill.name)) return;
      if (installedNames.has(skill.name.toLowerCase())) return;
      setInstallingSkills((prev) => new Set(prev).add(skill.name));
      sendToJava('install_registry_skill', {
        name: skill.name,
        version: skill.version || '',
        scope: installScope,
      });
    },
    [installingSkills, installedNames, installScope]
  );

  const handleRefresh = () => {
    loadSkills();
    addToast(t('registry.refreshed'), 'success');
  };

  const iconColors = ['#3B82F6', '#10B981', '#8B5CF6', '#F59E0B', '#EF4444', '#EC4899', '#06B6D4', '#6366F1'];
  const getIconColor = (name: string): string => {
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
    return iconColors[Math.abs(hash) % iconColors.length];
  };

  const isCodex = currentProvider === 'codex';
  const scopeProjectLabel = isCodex ? t('skills.repo') : t('registry.scopeProject');
  const scopeGlobalLabel = isCodex ? t('skills.user') : t('registry.scopeGlobal');

  return (
    <div className="registry-panel">
      {/* 工具栏 */}
      <div className="registry-toolbar">
        <div className="search-box">
          <span className="codicon codicon-search"></span>
          <input
            type="text"
            className="search-input"
            placeholder={t('registry.searchPlaceholder')}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
        {/* 安装范围选择 */}
        <div className="registry-scope-selector">
          <button
            className={`registry-scope-btn ${installScope === 'local' ? 'active' : ''}`}
            onClick={() => setInstallScope('local')}
            title={t('registry.installToProject')}
          >
            <span className="codicon codicon-desktop-download"></span>
            {scopeProjectLabel}
          </button>
          <button
            className={`registry-scope-btn ${installScope === 'global' ? 'active' : ''}`}
            onClick={() => setInstallScope('global')}
            title={t('registry.installToGlobal')}
          >
            <span className="codicon codicon-globe"></span>
            {scopeGlobalLabel}
          </button>
        </div>
        <button className="icon-btn" onClick={handleRefresh} disabled={loading} title={t('chat.refresh')}>
          <span className={`codicon codicon-refresh ${loading ? 'spinning' : ''}`}></span>
        </button>
      </div>

      {/* 列表 */}
      <div className="skill-list">
        {filteredSkills.map((skill) => {
          const isInstalled = installedNames.has(skill.name.toLowerCase());
          const isInstalling = installingSkills.has(skill.name);
          return (
            <div key={skill.name} className="skill-card">
              <div className="card-header">
                <div className="skill-icon-wrapper" style={{ color: getIconColor(skill.name) }}>
                  <span className={`codicon ${isInstalled ? 'codicon-check' : 'codicon-cloud-download'}`}></span>
                </div>
                <div className="skill-info">
                  <div className="skill-header-row">
                    <span className="skill-name">{skill.name}</span>
                    {skill.version && <span className="scope-badge">{skill.version}</span>}
                    {skill.author && (
                      <span className="scope-badge local">
                        <span className="codicon codicon-person"></span>
                        {skill.author}
                      </span>
                    )}
                    {isInstalled && (
                      <span className="scope-badge installed">
                        <span className="codicon codicon-check"></span>
                        {t('registry.installedStatus')}
                      </span>
                    )}
                  </div>
                  {skill.description && <div className="skill-path">{skill.description}</div>}
                  {skill.tags && skill.tags.length > 0 && (
                    <div className="registry-tags">
                      {skill.tags.map((tag) => (
                        <span key={tag} className="registry-tag">
                          {tag}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
                <div className="registry-actions">
                  {isInstalled ? (
                    <button className="action-btn installed-btn" disabled>
                      <span className="codicon codicon-check"></span>
                      {t('registry.installedStatus')}
                    </button>
                  ) : (
                    <button
                      className="action-btn install-btn"
                      onClick={() => handleInstall(skill)}
                      disabled={isInstalling}
                      title={installScope === 'local' ? t('registry.installToProject') : t('registry.installToGlobal')}
                    >
                      {isInstalling ? (
                        <span className="codicon codicon-loading codicon-modifier-spin"></span>
                      ) : (
                        <span className="codicon codicon-cloud-download"></span>
                      )}
                      {isInstalling ? t('registry.installing') : t('registry.install')}
                    </button>
                  )}
                </div>
              </div>
            </div>
          );
        })}

        {/* 空状态 */}
        {filteredSkills.length === 0 && !loading && (
          <div className="empty-state">
            <span className="codicon codicon-cloud"></span>
            <p>{t('registry.noSkills')}</p>
            <p className="hint">{t('registry.configureHint')}</p>
          </div>
        )}

        {/* 加载状态 */}
        {loading && (
          <div className="loading-state">
            <span className="codicon codicon-loading codicon-modifier-spin"></span>
            <p>{t('common.loading')}</p>
          </div>
        )}
      </div>

      <ToastContainer messages={toasts} onDismiss={dismissToast} />
    </div>
  );
}
