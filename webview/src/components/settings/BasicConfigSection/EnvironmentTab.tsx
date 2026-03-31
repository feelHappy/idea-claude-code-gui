import styles from './style.module.less';
import { useTranslation } from 'react-i18next';
import {
  createEmptyProjectDatabaseBinding,
  type ProjectDatabaseBinding,
} from '../projectDatabaseBinding';

export interface EnvironmentTabProps {
  nodePath: string;
  onNodePathChange: (path: string) => void;
  onSaveNodePath: () => void;
  savingNodePath: boolean;
  nodeVersion?: string | null;
  minNodeVersion?: number;
  workingDirectory?: string;
  onWorkingDirectoryChange?: (dir: string) => void;
  onSaveWorkingDirectory?: () => void;
  savingWorkingDirectory?: boolean;
  projectDatabaseBinding?: ProjectDatabaseBinding;
  onProjectDatabaseBindingChange?: <K extends keyof ProjectDatabaseBinding>(
    key: K,
    value: ProjectDatabaseBinding[K]
  ) => void;
  onSaveProjectDatabaseBinding?: () => void;
  savingProjectDatabaseBinding?: boolean;
}

const EnvironmentTab = ({
  nodePath,
  onNodePathChange,
  onSaveNodePath,
  savingNodePath,
  nodeVersion,
  minNodeVersion = 18,
  workingDirectory = '',
  onWorkingDirectoryChange = () => {},
  onSaveWorkingDirectory = () => {},
  savingWorkingDirectory = false,
  projectDatabaseBinding = createEmptyProjectDatabaseBinding(),
  onProjectDatabaseBindingChange = () => {},
  onSaveProjectDatabaseBinding = () => {},
  savingProjectDatabaseBinding = false,
}: EnvironmentTabProps) => {
  const { t } = useTranslation();
  const dbBindingBaseKey = 'settings.basic.databaseBinding';

  // Parse the major version number
  const parseMajorVersion = (version: string | null | undefined): number => {
    if (!version) return 0;
    const versionStr = version.startsWith('v') ? version.substring(1) : version;
    const dotIndex = versionStr.indexOf('.');
    if (dotIndex > 0) {
      return parseInt(versionStr.substring(0, dotIndex), 10) || 0;
    }
    return parseInt(versionStr, 10) || 0;
  };

  const majorVersion = parseMajorVersion(nodeVersion);
  const isVersionTooLow = nodeVersion && majorVersion > 0 && majorVersion < minNodeVersion;
  const showBundledPathHint = projectDatabaseBinding.bundledDbMcpServerAvailable;

  return (
    <div className={styles.tabContent}>
      {/* Node.js path configuration */}
      <div className={styles.nodePathSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-terminal" />
          <span className={styles.fieldLabel}>{t('settings.basic.nodePath.label')}</span>
          {nodeVersion && (
            <span className={`${styles.versionBadge} ${isVersionTooLow ? styles.versionBadgeError : styles.versionBadgeOk}`}>
              {nodeVersion}
            </span>
          )}
        </div>
        {isVersionTooLow && (
          <div className={styles.versionWarning}>
            <span className="codicon codicon-warning" />
            {t('settings.basic.nodePath.versionTooLow', { minVersion: minNodeVersion })}
          </div>
        )}
        <div className={styles.nodePathInputWrapper}>
          <input
            type="text"
            className={styles.nodePathInput}
            placeholder={t('settings.basic.nodePath.placeholder')}
            value={nodePath}
            onChange={(e) => onNodePathChange(e.target.value)}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveNodePath}
            disabled={savingNodePath}
          >
            {savingNodePath && (
              <span
                className="codicon codicon-loading codicon-modifier-spin"
              />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>
            {t('settings.basic.nodePath.hint')} <code>{t('settings.basic.nodePath.hintCommand')}</code> {t('settings.basic.nodePath.hintText')}
          </span>
        </small>
      </div>

      {/* Working directory configuration */}
      <div className={styles.workingDirSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-folder" />
          <span className={styles.fieldLabel}>{t('settings.basic.workingDirectory.label')}</span>
        </div>
        <div className={styles.nodePathInputWrapper}>
          <input
            type="text"
            className={styles.nodePathInput}
            placeholder={t('settings.basic.workingDirectory.placeholder')}
            value={workingDirectory}
            onChange={(e) => onWorkingDirectoryChange(e.target.value)}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveWorkingDirectory}
            disabled={savingWorkingDirectory}
          >
            {savingWorkingDirectory && (
              <span
                className="codicon codicon-loading codicon-modifier-spin"
              />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>
            {t('settings.basic.workingDirectory.hint')}
          </span>
        </small>
      </div>

      <div className={styles.databaseSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-database" />
          <span className={styles.fieldLabel}>{t(`${dbBindingBaseKey}.label`)}</span>
        </div>
        <p className={styles.sectionHint}>
          {t(`${dbBindingBaseKey}.description`)}
        </p>

        <label className={styles.checkboxRow}>
          <input
            type="checkbox"
            checked={projectDatabaseBinding.enabled}
            onChange={(e) => onProjectDatabaseBindingChange('enabled', e.target.checked)}
          />
          <span>{t(`${dbBindingBaseKey}.enabled`)}</span>
        </label>

        <div className={styles.databaseGrid}>
          <div className={`${styles.databaseField} ${styles.databaseFieldWide}`}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.dbMcpServerPath.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="text"
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                placeholder={t(`${dbBindingBaseKey}.fields.dbMcpServerPath.placeholder`)}
                value={projectDatabaseBinding.dbMcpServerPath}
                onChange={(e) => onProjectDatabaseBindingChange('dbMcpServerPath', e.target.value)}
              />
              {showBundledPathHint && (
                <small className={styles.databaseFieldMeta}>
                  {t(`${dbBindingBaseKey}.fields.dbMcpServerPath.optionalHint`)}
                </small>
              )}
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.sourceId.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="text"
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                placeholder={t(`${dbBindingBaseKey}.fields.sourceId.placeholder`)}
                value={projectDatabaseBinding.sourceId}
                onChange={(e) => onProjectDatabaseBindingChange('sourceId', e.target.value)}
              />
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.dialect.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <select
                className={`${styles.languageSelect} ${styles.databaseSelect}`}
                value={projectDatabaseBinding.dialect}
                onChange={(e) => onProjectDatabaseBindingChange('dialect', e.target.value as ProjectDatabaseBinding['dialect'])}
              >
                <option value="postgresql">{t(`${dbBindingBaseKey}.fields.dialect.options.postgresql`)}</option>
                <option value="mysql">{t(`${dbBindingBaseKey}.fields.dialect.options.mysql`)}</option>
                <option value="oracle">{t(`${dbBindingBaseKey}.fields.dialect.options.oracle`)}</option>
              </select>
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.mode.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <select
                className={`${styles.languageSelect} ${styles.databaseSelect}`}
                value={projectDatabaseBinding.mode}
                onChange={(e) => onProjectDatabaseBindingChange('mode', e.target.value as ProjectDatabaseBinding['mode'])}
              >
                <option value="dev-write">{t(`${dbBindingBaseKey}.fields.mode.options.devWrite`)}</option>
                <option value="read-only">{t(`${dbBindingBaseKey}.fields.mode.options.readOnly`)}</option>
              </select>
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.schema.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="text"
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                placeholder={t(`${dbBindingBaseKey}.fields.schema.placeholder`)}
                value={projectDatabaseBinding.schema}
                onChange={(e) => onProjectDatabaseBindingChange('schema', e.target.value)}
              />
            </div>
          </div>

          <div className={`${styles.databaseField} ${styles.databaseFieldWide}`}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.jdbcUrl.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="text"
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                placeholder={t(`${dbBindingBaseKey}.fields.jdbcUrl.placeholder`)}
                value={projectDatabaseBinding.jdbcUrl}
                onChange={(e) => onProjectDatabaseBindingChange('jdbcUrl', e.target.value)}
              />
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.username.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="text"
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                placeholder={t(`${dbBindingBaseKey}.fields.username.placeholder`)}
                value={projectDatabaseBinding.username}
                onChange={(e) => onProjectDatabaseBindingChange('username', e.target.value)}
              />
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.password.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="password"
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                placeholder={t(`${dbBindingBaseKey}.fields.password.placeholder`)}
                value={projectDatabaseBinding.password}
                onChange={(e) => onProjectDatabaseBindingChange('password', e.target.value)}
              />
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.usernameEnv.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="text"
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                placeholder={t(`${dbBindingBaseKey}.fields.usernameEnv.placeholder`)}
                value={projectDatabaseBinding.usernameEnv}
                onChange={(e) => onProjectDatabaseBindingChange('usernameEnv', e.target.value)}
              />
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.passwordEnv.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="text"
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                placeholder={t(`${dbBindingBaseKey}.fields.passwordEnv.placeholder`)}
                value={projectDatabaseBinding.passwordEnv}
                onChange={(e) => onProjectDatabaseBindingChange('passwordEnv', e.target.value)}
              />
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.maxRows.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="number"
                min={1}
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                value={projectDatabaseBinding.maxRows}
                onChange={(e) => onProjectDatabaseBindingChange('maxRows', Number.parseInt(e.target.value, 10) || 1)}
              />
            </div>
          </div>

          <div className={styles.databaseField}>
            <label className={styles.databaseFieldLabel}>{t(`${dbBindingBaseKey}.fields.maxAffectedRows.label`)}</label>
            <div className={styles.databaseFieldControl}>
              <input
                type="number"
                min={1}
                className={`${styles.nodePathInput} ${styles.databaseInput}`}
                value={projectDatabaseBinding.maxAffectedRows}
                onChange={(e) => onProjectDatabaseBindingChange('maxAffectedRows', Number.parseInt(e.target.value, 10) || 1)}
              />
            </div>
          </div>
        </div>

        <label className={styles.checkboxRow}>
          <input
            type="checkbox"
            checked={projectDatabaseBinding.requireWhereForUpdateDelete}
            onChange={(e) => onProjectDatabaseBindingChange('requireWhereForUpdateDelete', e.target.checked)}
          />
          <span>{t(`${dbBindingBaseKey}.safeWhere`)}</span>
        </label>

        <div className={styles.databaseActions}>
          <button
            className={styles.saveBtn}
            onClick={onSaveProjectDatabaseBinding}
            disabled={savingProjectDatabaseBinding}
          >
            {savingProjectDatabaseBinding && (
              <span className="codicon codicon-loading codicon-modifier-spin" />
            )}
            {t('common.save')}
          </button>
        </div>

        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>
            {t(`${dbBindingBaseKey}.hint`, { path: '~/.codemoss/project-db-mcp' })}
          </span>
        </small>

        <div className={styles.databaseMeta}>
          {projectDatabaseBinding.resolvedDbMcpInstallDir && (
            <small className={styles.formHint}>
              <span className={`codicon ${projectDatabaseBinding.usingBundledDbMcpServer ? 'codicon-package' : 'codicon-folder-library'}`} />
              <span>
                {t(projectDatabaseBinding.usingBundledDbMcpServer
                  ? `${dbBindingBaseKey}.bundledServer`
                  : `${dbBindingBaseKey}.resolvedInstallDir`)}: <code>{projectDatabaseBinding.resolvedDbMcpInstallDir}</code>
              </span>
            </small>
          )}

          {projectDatabaseBinding.generatedConfigPath && (
            <small className={styles.formHint}>
              <span className="codicon codicon-file-code" />
              <span>
                {t(`${dbBindingBaseKey}.generatedConfig`)}: <code>{projectDatabaseBinding.generatedConfigPath}</code>
              </span>
            </small>
          )}
        </div>
      </div>
    </div>
  );
};

export default EnvironmentTab;
