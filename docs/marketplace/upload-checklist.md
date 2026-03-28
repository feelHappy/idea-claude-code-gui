# 上传所需清单

## 一、先确认发布身份

- 当前默认插件 ID
  - `io.github.feelhappy.cc-ai-toolkit`
- 当前默认 Vendor
  - `Jordan`

如果你要继续使用这套默认值，可以直接打包上传。

如果你要换成你自己的条目，请先在 `local.properties` 中覆盖：

```properties
marketplace.plugin.id=your.final.plugin.id
marketplace.plugin.name=CC AI Toolkit
marketplace.vendor.name=Jordan
marketplace.vendor.email=you@example.com
marketplace.vendor.url=https://your-site.example
marketplace.channel=default
marketplace.hidden=false
```

## 二、仓库与对外链接必须检查

- `src/main/resources/META-INF/plugin.xml`
  - 顶部 `<idea-plugin url="...">` 是否为 `https://github.com/feelHappy/idea-claude-code-gui`
- `docs/marketplace/listing-copy.md`
  - Source Code / Support / Documentation URL 是否分别为仓库、Issue、README 地址
- `README.md`
  - 是否不再指向旧的 Marketplace 页面
- `README.zh-CN.md`
  - 是否不再指向旧的 Marketplace 页面

## 三、仓库内需要准备好的文件

- `src/main/resources/META-INF/plugin.xml`
  - 插件名、插件 ID、Vendor、描述、URL
- `src/main/resources/META-INF/pluginIcon.svg`
  - 40x40 SVG
- `src/main/resources/META-INF/pluginIcon_dark.svg`
  - 40x40 SVG
- `LICENSE`
  - 开源许可证
- `PRIVACY.md`
  - 隐私政策
- `EULA.md`
  - 最终用户许可条款
- `CHANGELOG.md`
  - 当前版本变更说明
- `build/distributions/cc-ai-toolkit-<version>.zip`
  - 最终上传包

## 四、Marketplace 账号侧准备

- JetBrains Marketplace 账号
- 已接受 Developer Agreement
- 已创建 Vendor Profile
- 已选择 `trader` 或 `non-trader`
- Vendor 公网邮箱
- Vendor 官网或个人主页

## 五、发布前命令

```powershell
$env:JAVA_HOME='D:\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
./gradlew verifyPluginConfiguration
./gradlew verifyPlugin
./gradlew runPluginVerifier
./gradlew buildPlugin
```

或者至少执行：

```powershell
$env:JAVA_HOME='D:\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
./gradlew marketplaceCheck
./gradlew buildPlugin
```

## 六、环境变量

这些不要提交到仓库：

```text
PUBLISH_TOKEN
CERTIFICATE_CHAIN
PRIVATE_KEY
PRIVATE_KEY_PASSWORD
```

说明：

- `PUBLISH_TOKEN`
  - Marketplace 上传 Token
- `CERTIFICATE_CHAIN`
  - 插件签名证书链
- `PRIVATE_KEY`
  - 插件签名私钥
- `PRIVATE_KEY_PASSWORD`
  - 私钥密码

## 七、上传页要填写的内容

- Plugin name
- Tags
- Category / 适用 IDE
- License or EULA link
- Source code URL
- Support URL
- Documentation URL
- Privacy policy URL
- 3 到 5 张截图
- 可选演示视频

## 八、最后人工复核

- `plugin.xml` 中的插件 ID、Vendor、URL 与 Marketplace 填写内容一致
- `CHANGELOG.md` 的版本号与本次上传版本一致
- `README.md` 与 `README.zh-CN.md` 不再指向旧插件页面
- `listing-copy.md` 中的 Design 模块命名与当前插件界面一致
- 最终上传包来自当前工作区重新打包后的 ZIP
