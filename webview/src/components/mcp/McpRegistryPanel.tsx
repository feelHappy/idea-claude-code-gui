import { useState, useEffect, useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { RegistryMcpItem, RegistryInstallResult } from '../../types/registry';
import { sendToJava } from '../../utils/bridge';
import { ToastContainer, type ToastMessage } from '../Toast';

/**
 * MCP 远程仓库面板
 * 展示 Nacos Registry 中的 MCP 列表，支持搜索和安装（远程 HTTP endpoint）
 */
export function McpRegistryPanel() {
  const { t } = useTranslation();
  const [mcps, setMcps] = useState<RegistryMcpItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [installingMcps, setInstallingMcps] = useState<Set<string>>(new Set());
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const addToast = useCallback((message: string, type: ToastMessage['type'] = 'info') => {
    const id = `toast-${Date.now()}-${Math.random()}`;
    setToasts((prev) => [...prev, { id, message, type }]);
  }, []);

  const dismissToast = (id: string) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  };

  const loadMcps = useCallback(() => {
    setLoading(true);
    sendToJava('fetch_registry_mcps', {});
  }, []);

  useEffect(() => {
    window.updateRegistryMcps = (jsonStr: string) => {
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
        // 映射 Nacos 字段名到前端类型
        const items: RegistryMcpItem[] = rawItems.map((raw) => ({
          name: String(raw.name || raw.serverName || ''),
          version: String(raw.editingVersion || raw.version || ''),
          description: String(raw.description || ''),
          endpoint: String(raw.endpoint || raw.serverEndpoint || raw.frontendEndpoint || ''),
          author: String(raw.author || raw.from || ''),
          tags: Array.isArray(raw.bizTags) ? raw.bizTags : raw.tags ? [].concat(raw.tags as never) : [],
          updateTime: raw.updateTime ? String(raw.updateTime) : undefined,
        }));
        setMcps(items);
      } catch (error) {
        console.error('[McpRegistry] Failed to parse mcps:', error);
        setMcps([]);
      }
      setLoading(false);
    };

    window.registryMcpInstallResult = (jsonStr: string) => {
      try {
        const result: RegistryInstallResult = JSON.parse(jsonStr);
        setInstallingMcps((prev) => {
          const next = new Set(prev);
          if (result.name) next.delete(result.name);
          return next;
        });
        if (result.success) {
          addToast(t('registry.mcp.installSuccess', { name: result.name }), 'success');
          // 触发已安装列表刷新
          sendToJava('get_mcp_servers', {});
        } else {
          addToast(result.error || t('registry.mcp.installFailed'), 'error');
        }
      } catch (error) {
        console.error('[McpRegistry] Failed to parse install result:', error);
        setInstallingMcps(new Set());
      }
    };

    loadMcps();

    return () => {
      window.updateRegistryMcps = undefined;
      window.registryMcpInstallResult = undefined;
    };
  }, [loadMcps, addToast, t]);

  const filteredMcps = useMemo(() => {
    if (!searchQuery) return mcps;
    const query = searchQuery.toLowerCase();
    return mcps.filter(
      (m) =>
        m.name.toLowerCase().includes(query) ||
        (m.description && m.description.toLowerCase().includes(query)) ||
        (m.tags && m.tags.some((tag) => tag.toLowerCase().includes(query)))
    );
  }, [mcps, searchQuery]);

  const handleInstall = useCallback(
    (mcp: RegistryMcpItem) => {
      if (installingMcps.has(mcp.name)) return;
      setInstallingMcps((prev) => new Set(prev).add(mcp.name));
      sendToJava('install_registry_mcp', {
        name: mcp.name,
        endpoint: mcp.endpoint,
        description: mcp.description || '',
      });
    },
    [installingMcps]
  );

  const handleRefresh = () => {
    loadMcps();
    addToast(t('registry.refreshed'), 'success');
  };

  const iconColors = ['#3B82F6', '#10B981', '#8B5CF6', '#F59E0B', '#EF4444', '#EC4899', '#06B6D4', '#6366F1'];
  const getIconColor = (name: string): string => {
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
    return iconColors[Math.abs(hash) % iconColors.length];
  };

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
        <button className="icon-btn" onClick={handleRefresh} disabled={loading} title={t('chat.refresh')}>
          <span className={`codicon codicon-refresh ${loading ? 'spinning' : ''}`}></span>
        </button>
      </div>

      {/* 列表 */}
      <div className="server-list">
        {filteredMcps.map((mcp) => (
          <div key={mcp.name} className="mcp-registry-card">
            <div className="mcp-registry-card-header">
              <div className="mcp-registry-icon" style={{ color: getIconColor(mcp.name) }}>
                <span className="codicon codicon-cloud"></span>
              </div>
              <div className="mcp-registry-info">
                <div className="mcp-registry-name-row">
                  <span className="mcp-registry-name">{mcp.name}</span>
                  {mcp.version && <span className="mcp-registry-version">{mcp.version}</span>}
                  <span className="mcp-registry-type-badge">HTTP</span>
                  {mcp.author && (
                    <span className="mcp-registry-author">
                      <span className="codicon codicon-person"></span>
                      {mcp.author}
                    </span>
                  )}
                </div>
                {mcp.description && <div className="mcp-registry-desc">{mcp.description}</div>}
                {mcp.endpoint && (
                  <div className="mcp-registry-endpoint">
                    <span className="codicon codicon-link"></span>
                    {mcp.endpoint}
                  </div>
                )}
                {mcp.tags && mcp.tags.length > 0 && (
                  <div className="registry-tags">
                    {mcp.tags.map((tag) => (
                      <span key={tag} className="registry-tag">
                        {tag}
                      </span>
                    ))}
                  </div>
                )}
              </div>
              <div className="registry-actions">
                <button
                  className="action-btn install-btn"
                  onClick={() => handleInstall(mcp)}
                  disabled={installingMcps.has(mcp.name)}
                  title={t('registry.install')}
                >
                  {installingMcps.has(mcp.name) ? (
                    <span className="codicon codicon-loading codicon-modifier-spin"></span>
                  ) : (
                    <span className="codicon codicon-cloud-download"></span>
                  )}
                  {t('registry.install')}
                </button>
              </div>
            </div>
          </div>
        ))}

        {/* 空状态 */}
        {filteredMcps.length === 0 && !loading && (
          <div className="empty-state">
            <span className="codicon codicon-cloud"></span>
            <p>{t('registry.noMcps')}</p>
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
