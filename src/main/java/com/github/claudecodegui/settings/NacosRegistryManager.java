package com.github.claudecodegui.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Nacos AI Registry 管理器。
 * 负责与 Nacos 3.x 的 Skill Registry / MCP Registry 交互。
 *
 * <p>认证方式：先通过 POST /v3/auth/user/login 获取 accessToken，
 * 后续请求在 Header 中携带 accessToken。</p>
 *
 * <p>API 端点（基于实际 Nacos 3.x 实例验证）：
 * <ul>
 *   <li>POST /v3/auth/user/login — 登录获取 token</li>
 *   <li>GET /v3/console/ai/mcp/list — MCP 列表</li>
 *   <li>GET /v3/console/ai/skills/list — Skill 列表</li>
 *   <li>GET /v3/console/ai/skills?skillName=xxx — Skill 详情</li>
 * </ul></p>
 */
public class NacosRegistryManager {

    private static final Logger LOG = Logger.getInstance(NacosRegistryManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 15000;

    private final ConfigPathManager pathManager;

    /** 缓存的 accessToken */
    private volatile String cachedAccessToken;
    /** Token 过期时间（毫秒时间戳） */
    private volatile long tokenExpireTime;

    public NacosRegistryManager(ConfigPathManager pathManager) {
        this.pathManager = pathManager;
    }

    // ==================== Configuration Persistence ====================

    public String getConfigPath() {
        return pathManager.getConfigDir().resolve("nacos-registry.json").toString();
    }

    public JsonObject readConfig() {
        Path configPath = Paths.get(getConfigPath());
        if (!Files.exists(configPath)) {
            return createDefaultConfig();
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(configPath), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("Failed to read nacos registry config", e);
            return createDefaultConfig();
        }
    }

    public void writeConfig(JsonObject config) throws IOException {
        Path configPath = Paths.get(getConfigPath());
        Files.createDirectories(configPath.getParent());
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(configPath), StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
    }

    private JsonObject createDefaultConfig() {
        JsonObject config = new JsonObject();
        config.addProperty("enabled", false);
        config.addProperty("serverAddr", "");
        config.addProperty("namespace", "public");
        config.addProperty("username", "");
        config.addProperty("password", "");
        return config;
    }

    // ==================== Token Authentication ====================

    /**
     * 通过 Nacos 登录接口获取 accessToken。
     * 带简单的缓存，在 token 过期前复用。
     */
    private String getAccessToken(String baseUrl, String username, String password) throws IOException {
        // 检查缓存
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return cachedAccessToken;
        }

        String loginUrl = baseUrl + "/v3/auth/user/login";
        URL url = toUrl(loginUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        String body = "username=" + encodeParam(username) + "&password=" + encodeParam(password);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code == 200) {
            String responseBody = readResponse(conn);
            conn.disconnect();
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            String token = response.get("accessToken").getAsString();
            long ttl = response.has("tokenTtl") ? response.get("tokenTtl").getAsLong() : 18000;

            // 缓存 token，提前 60 秒过期以留余量
            cachedAccessToken = token;
            tokenExpireTime = System.currentTimeMillis() + (ttl - 60) * 1000;

            return token;
        } else {
            String errorBody = readErrorResponse(conn);
            conn.disconnect();
            throw new IOException("Login failed (HTTP " + code + "): " + errorBody);
        }
    }

    /** 清除 token 缓存，用于认证失败时强制重新登录 */
    private void invalidateToken() {
        cachedAccessToken = null;
        tokenExpireTime = 0;
    }

    // ==================== Nacos API Interaction ====================

    /**
     * 测试 Nacos 连接。
     * 返回 JSON: { "success": true/false, "message": "...", "skillCount": N, "mcpCount": N }
     */
    public String testConnection(JsonObject config) {
        JsonObject result = new JsonObject();
        String serverAddr = config.has("serverAddr") ? config.get("serverAddr").getAsString().trim() : "";
        if (serverAddr.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("message", "Server address is empty");
            return GSON.toJson(result);
        }

        try {
            String baseUrl = normalizeServerAddr(serverAddr);
            String username = config.has("username") ? config.get("username").getAsString() : "";
            String password = config.has("password") ? config.get("password").getAsString() : "";

            // 清除旧 token，强制重新登录以验证凭据
            invalidateToken();

            // 尝试登录获取 token（同时验证连通性和凭据）
            String token = getAccessToken(baseUrl, username, password);

            // 登录成功，获取 Skill 和 MCP 数量
            int skillCount = fetchRegistryCount(baseUrl, "skills", token, config);
            int mcpCount = fetchRegistryCount(baseUrl, "mcp", token, config);

            result.addProperty("success", true);
            result.addProperty("message", "Connected");
            result.addProperty("skillCount", skillCount);
            result.addProperty("mcpCount", mcpCount);
        } catch (Exception e) {
            LOG.warn("Nacos connection test failed", e);
            result.addProperty("success", false);
            result.addProperty("message", e.getMessage());
        }

        return GSON.toJson(result);
    }

    /**
     * 获取 Skill 列表。返回 JSON 字符串。
     */
    public String fetchSkillList(JsonObject config) {
        return fetchRegistryList(config, "skills");
    }

    /**
     * 获取 MCP 列表。返回 JSON 字符串。
     */
    public String fetchMcpList(JsonObject config) {
        return fetchRegistryList(config, "mcp");
    }

    /**
     * 安装 Skill - 从 Nacos 获取 Skill 内容并保存。
     * <p>通过 GET /v3/console/ai/skills/version?skillName=xxx&version=yyy 获取完整内容。</p>
     * <p>根据 provider 决定安装路径：
     * <ul>
     *   <li>claude: ~/.claude/skills/{name}/SKILL.md</li>
     *   <li>codex: ~/.agents/skills/{name}/SKILL.md</li>
     * </ul></p>
     *
     * @param config        Nacos 连接配置
     * @param skillName     Skill 名称
     * @param version       Skill 版本（可选，为空时先查详情获取最新版本）
     * @param provider      当前 provider（"claude" 或 "codex"）
     * @param scope         安装范围（"local" 项目级 或 "global" 全局）
     * @param workspaceRoot 项目根路径（scope=local 时必需）
     */
    public String installSkill(JsonObject config, String skillName, String version,
                               String provider, String scope, String workspaceRoot) {
        JsonObject result = new JsonObject();
        try {
            String baseUrl = normalizeServerAddr(config.get("serverAddr").getAsString());
            String namespace = config.has("namespace") ? config.get("namespace").getAsString() : "public";
            String username = config.has("username") ? config.get("username").getAsString() : "";
            String password = config.has("password") ? config.get("password").getAsString() : "";
            String token = getAccessToken(baseUrl, username, password);

            // 如果没有指定版本，先查详情获取最新可用版本
            if (version == null || version.isEmpty()) {
                String detailUrl = baseUrl + "/v3/console/ai/skills"
                        + "?skillName=" + encodeParam(skillName)
                        + "&namespaceId=" + encodeParam(namespace);
                HttpURLConnection detailConn = createAuthenticatedConnection(detailUrl, "GET", token);
                if (detailConn.getResponseCode() == 200) {
                    String detailBody = readResponse(detailConn);
                    detailConn.disconnect();
                    JsonObject detailResp = JsonParser.parseString(detailBody).getAsJsonObject();
                    if (detailResp.has("data")) {
                        JsonObject detailData = detailResp.getAsJsonObject("data");
                        // 优先 editingVersion，其次 reviewingVersion，最后从 versions 数组取第一个
                        version = getJsonString(detailData, "editingVersion");
                        if (version == null || version.isEmpty()) {
                            version = getJsonString(detailData, "reviewingVersion");
                        }
                        if ((version == null || version.isEmpty())
                                && detailData.has("versions")
                                && detailData.getAsJsonArray("versions").size() > 0) {
                            JsonObject firstVersion = detailData.getAsJsonArray("versions")
                                    .get(0).getAsJsonObject();
                            version = getJsonString(firstVersion, "version");
                        }
                    }
                } else {
                    detailConn.disconnect();
                }
            }

            if (version == null || version.isEmpty()) {
                result.addProperty("success", false);
                result.addProperty("error", "Cannot determine skill version");
                return GSON.toJson(result);
            }

            // 通过版本接口获取完整 Skill 内容（包含 skillMd 和 resource）
            String versionUrl = baseUrl + "/v3/console/ai/skills/version"
                    + "?skillName=" + encodeParam(skillName)
                    + "&version=" + encodeParam(version)
                    + "&namespaceId=" + encodeParam(namespace);

            HttpURLConnection conn = createAuthenticatedConnection(versionUrl, "GET", token);
            int code = conn.getResponseCode();

            if (code == 200) {
                String body = readResponse(conn);
                conn.disconnect();
                JsonObject response = JsonParser.parseString(body).getAsJsonObject();
                JsonObject data = response.has("data") ? response.getAsJsonObject("data") : new JsonObject();

                // 确定安装目录
                Path targetDir = getSkillInstallDir(skillName, provider, scope, workspaceRoot);
                Files.createDirectories(targetDir);

                // 保存 SKILL.md（从 skillMd 字段提取）
                String skillMd = getJsonString(data, "skillMd");
                if (skillMd == null) skillMd = "";
                if (skillMd.isEmpty()) {
                    result.addProperty("success", false);
                    result.addProperty("error", "Skill content is empty");
                    return GSON.toJson(result);
                }
                Path skillFile = targetDir.resolve("SKILL.md");
                Files.writeString(skillFile, skillMd, StandardCharsets.UTF_8);

                // 保存附属资源文件（resource 字段）
                if (data.has("resource") && data.get("resource").isJsonObject()) {
                    JsonObject resources = data.getAsJsonObject("resource");
                    for (String key : resources.keySet()) {
                        JsonObject res = resources.getAsJsonObject(key);
                        String fileName = getJsonString(res, "name");
                        String fileContent = getJsonString(res, "content");
                        if (fileName != null && !fileName.isEmpty() && fileContent != null) {
                            // 安全检查：防止路径穿越
                            Path filePath = targetDir.resolve(fileName).normalize();
                            if (!filePath.startsWith(targetDir)) {
                                LOG.warn("Skipping resource with unsafe path: " + fileName);
                                continue;
                            }
                            Files.createDirectories(filePath.getParent());
                            Files.writeString(filePath, fileContent, StandardCharsets.UTF_8);
                        }
                    }
                }

                result.addProperty("success", true);
                result.addProperty("name", skillName);
                result.addProperty("version", version);
                result.addProperty("path", targetDir.toString());
                result.addProperty("provider", provider != null ? provider : "claude");
            } else {
                String errorBody = readErrorResponse(conn);
                conn.disconnect();
                result.addProperty("success", false);
                result.addProperty("error", "HTTP " + code + ": " + errorBody);
            }
        } catch (Exception e) {
            LOG.warn("Failed to install skill: " + skillName, e);
            result.addProperty("success", false);
            result.addProperty("error", e.getMessage());
        }
        return GSON.toJson(result);
    }

    /**
     * 安装 MCP - 将远程 HTTP endpoint 注册到 Claude 配置。
     */
    public String installMcp(JsonObject config, String mcpName, String mcpEndpoint, String mcpDescription) {
        JsonObject result = new JsonObject();
        try {
            JsonObject mcpServer = new JsonObject();
            mcpServer.addProperty("type", "http");
            mcpServer.addProperty("url", mcpEndpoint);

            Path claudeConfigPath = Paths.get(com.github.claudecodegui.util.PlatformUtils.getHomeDirectory(), ".claude.json");
            JsonObject claudeConfig;
            if (Files.exists(claudeConfigPath)) {
                try (Reader reader = new InputStreamReader(Files.newInputStream(claudeConfigPath), StandardCharsets.UTF_8)) {
                    claudeConfig = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                claudeConfig = new JsonObject();
            }

            if (!claudeConfig.has("mcpServers")) {
                claudeConfig.add("mcpServers", new JsonObject());
            }
            claudeConfig.getAsJsonObject("mcpServers").add(mcpName, mcpServer);

            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(claudeConfigPath), StandardCharsets.UTF_8)) {
                GSON.toJson(claudeConfig, writer);
            }

            result.addProperty("success", true);
            result.addProperty("name", mcpName);
            result.addProperty("endpoint", mcpEndpoint);
        } catch (Exception e) {
            LOG.warn("Failed to install MCP: " + mcpName, e);
            result.addProperty("success", false);
            result.addProperty("error", e.getMessage());
        }
        return GSON.toJson(result);
    }

    // ==================== Internal Helpers ====================

    private String fetchRegistryList(JsonObject config, String type) {
        try {
            String baseUrl = normalizeServerAddr(config.get("serverAddr").getAsString());
            String namespace = config.has("namespace") ? config.get("namespace").getAsString() : "public";
            String username = config.has("username") ? config.get("username").getAsString() : "";
            String password = config.has("password") ? config.get("password").getAsString() : "";
            String token = getAccessToken(baseUrl, username, password);

            String listUrl = baseUrl + "/v3/console/ai/" + type + "/list"
                    + "?namespaceId=" + encodeParam(namespace)
                    + "&pageNo=1&pageSize=200";

            HttpURLConnection conn = createAuthenticatedConnection(listUrl, "GET", token);
            int code = conn.getResponseCode();

            if (code == 200) {
                String body = readResponse(conn);
                conn.disconnect();
                return body;
            } else if (code == 403) {
                // Token 可能过期，重新登录重试
                conn.disconnect();
                invalidateToken();
                token = getAccessToken(baseUrl, username, password);
                conn = createAuthenticatedConnection(listUrl, "GET", token);
                code = conn.getResponseCode();
                if (code == 200) {
                    String body = readResponse(conn);
                    conn.disconnect();
                    return body;
                }
                conn.disconnect();
                return "[]";
            } else {
                conn.disconnect();
                return "[]";
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch " + type + " list", e);
            return "[]";
        }
    }

    private int fetchRegistryCount(String baseUrl, String type, String token, JsonObject config) {
        try {
            String namespace = config.has("namespace") ? config.get("namespace").getAsString() : "public";
            String listUrl = baseUrl + "/v3/console/ai/" + type + "/list"
                    + "?namespaceId=" + encodeParam(namespace)
                    + "&pageNo=1&pageSize=1";

            HttpURLConnection conn = createAuthenticatedConnection(listUrl, "GET", token);
            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readResponse(conn);
                conn.disconnect();
                JsonObject response = JsonParser.parseString(body).getAsJsonObject();
                if (response.has("data") && response.getAsJsonObject("data").has("totalCount")) {
                    return response.getAsJsonObject("data").get("totalCount").getAsInt();
                }
                if (response.has("totalCount")) {
                    return response.get("totalCount").getAsInt();
                }
                return 0;
            }
            conn.disconnect();
        } catch (Exception e) {
            LOG.debug("Failed to fetch " + type + " count", e);
        }
        return 0;
    }

    /**
     * 创建带 accessToken 认证的 HTTP 连接。
     */
    private HttpURLConnection createAuthenticatedConnection(String urlStr, String method, String accessToken) throws IOException {
        URL url = toUrl(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        if (accessToken != null && !accessToken.isEmpty()) {
            conn.setRequestProperty("accessToken", accessToken);
        }
        return conn;
    }

    private URL toUrl(String urlStr) throws IOException {
        try {
            return URI.create(urlStr).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + urlStr, e);
        }
    }

    private String normalizeServerAddr(String addr) {
        addr = addr.trim();
        if (addr.endsWith("/")) {
            addr = addr.substring(0, addr.length() - 1);
        }
        if (!addr.startsWith("http://") && !addr.startsWith("https://")) {
            addr = "http://" + addr;
        }
        return addr;
    }

    /**
     * 根据 provider 和 scope 获取 Skill 安装目录。
     * <pre>
     * Claude:
     *   local  → {workspaceRoot}/.claude/skills/{skillName}/
     *   global → ~/.claude/skills/{skillName}/
     * Codex:
     *   local  → {workspaceRoot}/.agents/skills/{skillName}/
     *   global → ~/.agents/skills/{skillName}/
     * </pre>
     */
    private Path getSkillInstallDir(String skillName, String provider,
                                    String scope, String workspaceRoot) throws IOException {
        Path skillsDir;
        if ("local".equalsIgnoreCase(scope) && workspaceRoot != null && !workspaceRoot.isEmpty()) {
            // 项目级安装
            if ("codex".equalsIgnoreCase(provider)) {
                skillsDir = Paths.get(workspaceRoot, ".agents", "skills");
            } else {
                skillsDir = Paths.get(workspaceRoot, ".claude", "skills");
            }
        } else {
            // 全局安装
            String home = com.github.claudecodegui.util.PlatformUtils.getHomeDirectory();
            if ("codex".equalsIgnoreCase(provider)) {
                skillsDir = Paths.get(home, ".agents", "skills");
            } else {
                skillsDir = Paths.get(home, ".claude", "skills");
            }
        }
        Files.createDirectories(skillsDir);
        return skillsDir.resolve(skillName);
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        try (InputStream is = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private String readErrorResponse(HttpURLConnection conn) {
        try (InputStream is = conn.getErrorStream()) {
            if (is == null) return "";
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 安全获取 JsonObject 中的字符串值，处理 null 和 JsonNull。
     */
    private String getJsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private String encodeParam(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
