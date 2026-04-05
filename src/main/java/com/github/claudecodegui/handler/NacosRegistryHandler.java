package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.NacosRegistryManager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Nacos AI Registry 消息处理器。
 * 处理前端市场页面的所有请求。
 */
public class NacosRegistryHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(NacosRegistryHandler.class);
    private static final Gson GSON = new Gson();

    private final NacosRegistryManager registryManager;

    private static final String[] SUPPORTED_TYPES = {
        "get_nacos_registry_config",
        "set_nacos_registry_config",
        "test_nacos_connection",
        "fetch_registry_skills",
        "fetch_registry_mcps",
        "install_registry_skill",
        "install_registry_mcp"
    };

    public NacosRegistryHandler(HandlerContext context, NacosRegistryManager registryManager) {
        super(context);
        this.registryManager = registryManager;
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "get_nacos_registry_config":
                handleGetConfig();
                return true;
            case "set_nacos_registry_config":
                handleSetConfig(content);
                return true;
            case "test_nacos_connection":
                handleTestConnection(content);
                return true;
            case "fetch_registry_skills":
                handleFetchSkills();
                return true;
            case "fetch_registry_mcps":
                handleFetchMcps();
                return true;
            case "install_registry_skill":
                handleInstallSkill(content);
                return true;
            case "install_registry_mcp":
                handleInstallMcp(content);
                return true;
            default:
                return false;
        }
    }

    /**
     * 获取 Nacos Registry 配置。
     */
    private void handleGetConfig() {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject config = registryManager.readConfig();
                String json = GSON.toJson(config);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.updateNacosRegistryConfig", escapeJs(json));
                });
            } catch (Exception e) {
                LOG.warn("Failed to get nacos registry config", e);
            }
        });
    }

    /**
     * 保存 Nacos Registry 配置。
     */
    private void handleSetConfig(String content) {
        CompletableFuture.runAsync(() -> {
            JsonObject result = new JsonObject();
            try {
                JsonObject config = JsonParser.parseString(content).getAsJsonObject();
                registryManager.writeConfig(config);
                result.addProperty("success", true);
            } catch (Exception e) {
                LOG.warn("Failed to save nacos registry config", e);
                result.addProperty("success", false);
                result.addProperty("error", e.getMessage());
            }
            String json = GSON.toJson(result);
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.nacosRegistryConfigSaved", escapeJs(json));
            });
        });
    }

    /**
     * 测试 Nacos 连接。
     */
    private void handleTestConnection(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject config;
                if (content != null && !content.trim().isEmpty()) {
                    config = JsonParser.parseString(content).getAsJsonObject();
                } else {
                    config = registryManager.readConfig();
                }
                String testResult = registryManager.testConnection(config);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.nacosConnectionTestResult", escapeJs(testResult));
                });
            } catch (Exception e) {
                LOG.warn("Failed to test nacos connection", e);
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", e.getMessage());
                String json = GSON.toJson(error);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.nacosConnectionTestResult", escapeJs(json));
                });
            }
        });
    }

    /**
     * 获取远程仓库 Skill 列表。
     */
    private void handleFetchSkills() {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject config = registryManager.readConfig();
                String list = registryManager.fetchSkillList(config);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.updateRegistrySkills", escapeJs(list));
                });
            } catch (Exception e) {
                LOG.warn("Failed to fetch registry skills", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.updateRegistrySkills", escapeJs("[]"));
                });
            }
        });
    }

    /**
     * 获取远程仓库 MCP 列表。
     */
    private void handleFetchMcps() {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject config = registryManager.readConfig();
                String list = registryManager.fetchMcpList(config);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.updateRegistryMcps", escapeJs(list));
                });
            } catch (Exception e) {
                LOG.warn("Failed to fetch registry mcps", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.updateRegistryMcps", escapeJs("[]"));
                });
            }
        });
    }

    /**
     * 安装远程仓库 Skill。
     * 前端传递 { name, version?, scope? }
     * scope: "local"（项目级，默认）或 "global"（全局）
     */
    private void handleInstallSkill(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject payload = JsonParser.parseString(content).getAsJsonObject();
                String skillName = payload.get("name").getAsString();
                String version = payload.has("version") ? payload.get("version").getAsString() : "";
                String scope = payload.has("scope") ? payload.get("scope").getAsString() : "local";
                String provider = context.getCurrentProvider();
                String workspaceRoot = context.getProject() != null ? context.getProject().getBasePath() : null;
                JsonObject config = registryManager.readConfig();
                String installResult = registryManager.installSkill(config, skillName, version, provider, scope, workspaceRoot);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.registrySkillInstallResult", escapeJs(installResult));
                });
            } catch (Exception e) {
                LOG.warn("Failed to install registry skill", e);
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("error", e.getMessage());
                String json = GSON.toJson(error);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.registrySkillInstallResult", escapeJs(json));
                });
            }
        });
    }

    /**
     * 安装远程仓库 MCP。
     */
    private void handleInstallMcp(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject payload = JsonParser.parseString(content).getAsJsonObject();
                String mcpName = payload.get("name").getAsString();
                String mcpEndpoint = payload.get("endpoint").getAsString();
                String mcpDescription = payload.has("description")
                        ? payload.get("description").getAsString() : "";
                JsonObject config = registryManager.readConfig();
                String installResult = registryManager.installMcp(config, mcpName, mcpEndpoint, mcpDescription);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.registryMcpInstallResult", escapeJs(installResult));
                });
            } catch (Exception e) {
                LOG.warn("Failed to install registry mcp", e);
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("error", e.getMessage());
                String json = GSON.toJson(error);
                ApplicationManager.getApplication().invokeLater(() -> {
                    callJavaScript("window.registryMcpInstallResult", escapeJs(json));
                });
            }
        });
    }
}
