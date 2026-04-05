/**
 * Nacos AI Registry 类型定义
 *
 * 支持从公司 Nacos 3.2 实例的 Skill Registry / MCP Registry
 * 浏览、搜索、安装 Skill 和 MCP。
 */

/**
 * Nacos Registry 连接配置
 */
export interface NacosRegistryConfig {
  /** 是否启用 */
  enabled: boolean;
  /** Nacos 服务地址 (如 http://nacos.company.com:8848) */
  serverAddr: string;
  /** 命名空间 */
  namespace: string;
  /** 用户名 */
  username: string;
  /** 密码 */
  password: string;
}

/**
 * 连接测试结果
 */
export interface NacosConnectionTestResult {
  success: boolean;
  message: string;
  skillCount?: number;
  mcpCount?: number;
}

/**
 * 远程仓库 Skill 条目
 */
export interface RegistrySkillItem {
  /** Skill 名称 */
  name: string;
  /** 版本号 */
  version?: string;
  /** 描述 */
  description?: string;
  /** 作者 */
  author?: string;
  /** 标签 */
  tags?: string[];
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

/**
 * 远程仓库 MCP 条目
 */
export interface RegistryMcpItem {
  /** MCP 名称 */
  name: string;
  /** 版本号 */
  version?: string;
  /** 描述 */
  description?: string;
  /** 远程 endpoint URL */
  endpoint: string;
  /** 作者 */
  author?: string;
  /** 标签 */
  tags?: string[];
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

/**
 * 安装结果
 */
export interface RegistryInstallResult {
  success: boolean;
  name?: string;
  path?: string;
  endpoint?: string;
  error?: string;
}

/**
 * 远程仓库 Tab 类型
 */
export type RegistryTab = 'installed' | 'remote';
