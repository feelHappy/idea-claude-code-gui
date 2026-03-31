export type ProjectDatabaseDialect = 'postgresql' | 'mysql' | 'oracle';
export type ProjectDatabaseMode = 'dev-write' | 'read-only';

export interface ProjectDatabaseBinding {
  enabled: boolean;
  dbMcpServerPath: string;
  sourceId: string;
  dialect: ProjectDatabaseDialect;
  mode: ProjectDatabaseMode;
  jdbcUrl: string;
  schema: string;
  username: string;
  password: string;
  usernameEnv: string;
  passwordEnv: string;
  maxRows: number;
  maxAffectedRows: number;
  requireWhereForUpdateDelete: boolean;
  projectPath?: string;
  serverId?: string;
  generatedConfigPath?: string;
  resolvedDbMcpInstallDir?: string;
  javaExecutable?: string;
  bundledDbMcpServerAvailable: boolean;
  usingBundledDbMcpServer: boolean;
}

export const createEmptyProjectDatabaseBinding = (): ProjectDatabaseBinding => ({
  enabled: false,
  dbMcpServerPath: '',
  sourceId: 'main',
  dialect: 'postgresql',
  mode: 'dev-write',
  jdbcUrl: '',
  schema: '',
  username: '',
  password: '',
  usernameEnv: '',
  passwordEnv: '',
  maxRows: 200,
  maxAffectedRows: 50,
  requireWhereForUpdateDelete: true,
  projectPath: '',
  serverId: '',
  generatedConfigPath: '',
  resolvedDbMcpInstallDir: '',
  javaExecutable: '',
  bundledDbMcpServerAvailable: false,
  usingBundledDbMcpServer: false,
});

const toPositiveInt = (value: unknown, fallback: number): number => {
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
};

export const normalizeProjectDatabaseBinding = (value: Partial<ProjectDatabaseBinding> | null | undefined): ProjectDatabaseBinding => {
  const defaults = createEmptyProjectDatabaseBinding();
  return {
    ...defaults,
    ...value,
    enabled: value?.enabled ?? defaults.enabled,
    dbMcpServerPath: value?.dbMcpServerPath ?? defaults.dbMcpServerPath,
    sourceId: value?.sourceId ?? defaults.sourceId,
    dialect: value?.dialect ?? defaults.dialect,
    mode: value?.mode ?? defaults.mode,
    jdbcUrl: value?.jdbcUrl ?? defaults.jdbcUrl,
    schema: value?.schema ?? defaults.schema,
    username: value?.username ?? defaults.username,
    password: value?.password ?? defaults.password,
    usernameEnv: value?.usernameEnv ?? defaults.usernameEnv,
    passwordEnv: value?.passwordEnv ?? defaults.passwordEnv,
    maxRows: toPositiveInt(value?.maxRows, defaults.maxRows),
    maxAffectedRows: toPositiveInt(value?.maxAffectedRows, defaults.maxAffectedRows),
    requireWhereForUpdateDelete: value?.requireWhereForUpdateDelete ?? defaults.requireWhereForUpdateDelete,
    projectPath: value?.projectPath ?? defaults.projectPath,
    serverId: value?.serverId ?? defaults.serverId,
    generatedConfigPath: value?.generatedConfigPath ?? defaults.generatedConfigPath,
    resolvedDbMcpInstallDir: value?.resolvedDbMcpInstallDir ?? defaults.resolvedDbMcpInstallDir,
    javaExecutable: value?.javaExecutable ?? defaults.javaExecutable,
    bundledDbMcpServerAvailable: value?.bundledDbMcpServerAvailable ?? defaults.bundledDbMcpServerAvailable,
    usingBundledDbMcpServer: value?.usingBundledDbMcpServer ?? defaults.usingBundledDbMcpServer,
  };
};
