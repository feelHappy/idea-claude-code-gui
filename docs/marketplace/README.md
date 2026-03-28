# JetBrains Marketplace 发布说明

这组文件用于整理 `CC AI Toolkit` 的自发布资料，避免继续沿用旧插件条目、旧链接或旧命名。

## 当前默认发布信息

- Plugin name: `CC AI Toolkit`
- Default plugin ID: `io.github.feelhappy.cc-ai-toolkit`
- Vendor: `Jordan`
- Repository URL: `https://github.com/feelHappy/idea-claude-code-gui`
- Current package output: `build/distributions/cc-ai-toolkit-<version>.zip`

## 发布前要先确认的三件事

1. 你的 Marketplace 条目是否继续使用当前默认 ID
2. 你的 Vendor 邮箱和官网是否已经准备好
3. 你的隐私政策和 EULA 链接是否已经准备好

## 这些位置需要保持一致

- `build.gradle`
  - 默认发布 ID、Vendor 名称、版本号
- `src/main/resources/META-INF/plugin.xml`
  - 插件 ID、名称、Vendor 展示、插件主页 URL、Marketplace 描述
- `README.md`
  - 英文对外说明和安装方式
- `README.zh-CN.md`
  - 中文对外说明和安装方式
- `docs/marketplace/listing-copy.md`
  - Marketplace 页面标题、简介、完整描述、截图计划、外链
- `docs/marketplace/upload-checklist.md`
  - 上传前核对项

## 你还需要替换的内容

- 如果你不想使用当前默认 ID，在 `local.properties` 里覆盖 `marketplace.plugin.id`
- 将 Privacy Policy 和 EULA 链接替换为你对外公开的正式地址
- 将 Vendor 邮箱和官网补充到 `local.properties` 或环境变量

## 推荐顺序

1. 填写 `local.properties` 中的 Marketplace 元数据覆盖项
2. 完成 `PRIVACY.md`、`EULA.md`、截图、CHANGELOG
3. 确认 Source Code / Support / Documentation 外链可访问
4. 执行构建与校验命令
5. 上传 ZIP 到 JetBrains Marketplace

## 本地命令

```powershell
$env:JAVA_HOME='D:\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
./gradlew marketplaceCheck
./gradlew buildPlugin
```

如果需要直接发布：

```powershell
$env:PUBLISH_TOKEN='...'
$env:CERTIFICATE_CHAIN='...'
$env:PRIVATE_KEY='...'
$env:PRIVATE_KEY_PASSWORD='...'
./gradlew publishPlugin
```

## 参考文档

- 上传新插件
  - https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html
- Vendor Profile
  - https://plugins.jetbrains.com/docs/marketplace/organizations.html
- Listing 最佳实践
  - https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html
- Marketplace 审核要求
  - https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html
- Gradle 发布与签名
  - https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
