# 智信 AIChat

安卓端 AI 伴侣聊天应用：界面风格接近安卓版微信，聊天对象是你自己创建的 AI 联系人，所有数据只保存在本机。

- 包名 `isomeria.aichat`，需要 Android 14（API 34）及以上
- 原生 Kotlin + Jetpack Compose，单 Activity，MVVM + StateFlow，Hilt 依赖注入
- 大语言模型：DeepSeek（兼容 OpenAI Chat Completions 格式）
- 设计方案见 [智信 AIChat 设计方案.md](智信%20AIChat%20设计方案.md)

> 本项目与腾讯、微信没有任何关系。界面只参考布局与配色，不使用微信的图标、素材和名称。

## 0.1.0 功能

| 功能 | 说明 |
| --- | --- |
| 三个 Tab | 智信（聊天列表）、通讯录、我；可左右滑动切换 |
| 联系人 | 新建、编辑、删除；独立的模型、思考强度（关/高/最高）、温度、系统提示词、上下文条数、分条显示 |
| 文字聊天 | 回复完成后整条出现；有空行时按空行拆成多条，约 1 秒一条依次出现；思考过程可折叠 |
| 后台接收 | 请求交给 WorkManager，离开聊天页或切到后台也会继续；回复到达时发通知，点通知进入聊天 |
| 图片 | 相册（系统照片选择器）或拍照；压缩到长边 2048 像素、去掉 EXIF；只有最近 3 张带原图 |
| 消息操作 | 长按复制、删除、重新生成（最后一条回复）、取消回复；失败消息点红色感叹号重试 |
| 设置 | DeepSeek API Key（Keystore 加密）、Base URL、默认模型、测试连接；深色模式、字体大小、通知 |

## 0.2.0 功能

| 功能 | 说明 |
| --- | --- |
| 导出数据 | zip 文件：`manifest.json`（App 版本、数据格式版本、导出时间）、`data.json`、`avatars/`、`images/`；默认不含 API Key，勾选时用密码加密（PBKDF2-SHA256 + AES-256-GCM） |
| 恢复数据 | 校验数据格式版本（比当前新则拒绝，旧则先迁移）；「合并」或「覆盖」；恢复前自动备份到私有目录，失败时回滚 |
| 检查更新 | GitHub「最新 Release」接口；启动时静默检查，24 小时最多一次；手动检查；「立即更新」「稍后」「忽略此版本」 |
| 聊天信息 | 联系人头像和名称、置顶聊天、查找聊天记录、清空聊天记录 |
| 搜索 | 同时搜联系人名称和聊天内容；点结果跳到对应消息并高亮 |
| 表情面板 | 系统 emoji（列表由 `tools/generate_emoji.py` 从 Unicode 官方数据生成）、分类标签、最近使用；面板和键盘同高 |
| 草稿 | 退出聊天时保存，聊天列表显示红色「[草稿]」 |

## 构建

需要 JDK 17 或更高版本（Android Studio 自带的即可）和 Android SDK（平台 37、构建工具 37）。

```bash
./gradlew testDebugUnitTest   # 单元测试
./gradlew lintDebug           # 代码检查
./gradlew assembleDebug       # debug 包：包名 isomeria.aichat.debug，可与正式包同时安装
./gradlew assembleRelease     # release 包：需要正式签名（见下）
```

命令行构建时，`local.properties` 里写 `sdk.dir=Android SDK 路径`，`JAVA_HOME` 指向 JDK 17+。

## 正式签名

每个版本必须用同一个密钥签名，否则无法覆盖安装；卸载重装会清空全部本地数据。

本地签名读取根目录的 `keystore.properties`（已在 `.gitignore` 里，绝不提交）：

```properties
storeFile=keystore/aichat-release.jks
storePassword=...
keyAlias=aichat
keyPassword=...
```

**`keystore/aichat-release.jks` 和 `keystore.properties` 必须一起备份到至少两个地方。**

GitHub Actions 从仓库 Secrets 读取签名，需要配置：

| Secret | 内容 |
| --- | --- |
| `AICHAT_KEYSTORE_BASE64` | `base64 -w0 keystore/aichat-release.jks` 的输出 |
| `AICHAT_KEYSTORE_PASSWORD` | keystore 密码 |
| `AICHAT_KEY_ALIAS` | `aichat` |
| `AICHAT_KEY_PASSWORD` | 密钥密码 |

## 发布

1. 在 `app/build.gradle.kts` 里改 `appVersionName`（versionCode 按「主版本×10000 + 次版本×100 + 修订号」自动计算），和代码在同一个提交里
2. 在 `docs/release-notes/` 下写 `v<版本号>.md`，App 内更新弹窗会直接显示它
3. 推送 tag `v<版本号>`：`release.yml` 会核对 tag 与 versionName、构建签名包、生成 `AIChat-<版本号>.apk` 和 `.sha256`，并创建正式 Release（不是 Pre-release）

不要把 debug 包发到 Release：debug 包与 release 包签名不同，互相不能覆盖。

## 开发约定

- 新功能在 `feature/功能名` 分支开发，CI 通过后合并回 main；提交信息前缀 `feat` / `fix` / `refactor` / `docs`
- 依赖版本统一放在 `gradle/libs.versions.toml`
- 界面文字全部放 `strings.xml`；颜色、尺寸、字号只从 `ui/theme`（`WeTheme.colors / dimens / typography`）取
- 详细日志只在 debug 包输出；任何包都不记录 API Key 和消息正文
- 每次改表：数据库版本号 +1、写迁移脚本、用 `app/schemas` 里的 schema 文件做迁移测试

## 工程结构

```
isomeria.aichat
├─ ui/theme        微信风格主题：颜色、尺寸、字号
├─ ui/components   通用组件：顶栏、Tab 栏、头像、气泡、输入栏、列表行、对话框、菜单
├─ feature/        main · chatlist · chat · chatinfo · contacts · contactedit · me · settings · backup
├─ navigation      路由与页面切换
├─ core            纯逻辑：时间显示、链接识别、分条拆分、上下文拼装、拼音分组
├─ data/db         Room 表与 DAO
├─ data/repo       仓库层（页面只依赖这里）
├─ data/llm        模型服务接口与 DeepSeek 实现
├─ data/reply      后台等待回复（WorkManager）与通知
├─ data/settings   MMKV 设置
├─ data/crypto     Android Keystore 加密
├─ data/backup     导出与恢复（格式、密码加密、迁移）
└─ update          检查更新（GitHub Release）
```

## 许可

本项目以 [MIT 许可](LICENSE) 开源。所用第三方库的许可见 App 内「设置 → 关于 → 开源许可」。
