# 智信 AIChat

安卓端 AI 伴侣聊天应用：界面风格接近安卓版微信，聊天对象是你自己创建的 AI 联系人。没有账号和开发者服务器，数据保存在本机；聊天请求只发给你自己配置的模型服务商。

> 本项目与腾讯、微信没有任何关系。界面只参考布局与配色，不使用微信的图标、素材和名称。

## 当前状态

| 项目 | 内容 |
| --- | --- |
| 最新正式版 | **0.4.1**（versionCode 401），2026-11-04 发布，[下载](https://github.com/KokoroLyase/AIChat/releases/latest) |
| 系统要求 | Android 14（API 34）及以上 |
| 包名 | `isomeria.aichat` |
| 数据版本 | Room v3，备份内层 v3，加密容器 v1 |
| 技术栈 | 原生 Kotlin + Jetpack Compose，单 Activity，MVVM + StateFlow，Hilt |
| 模型接入 | 7 家连接预置和自定义 OpenAI Chat Completions 兼容 API；只使用你自己的 API Key |
| 下一版本 | 计划 0.4.2（界面逻辑优化），尚未开工；0.5.0（全局记忆、回复偏好、可靠投递）排在其后，下文的功能都不包含它们 |

## 文档

| 文档 | 内容 |
| --- | --- |
| [更新说明](docs/release-notes/) | 每个已发布版本改了什么，也是 App 内更新弹窗显示的内容 |
| [设计方案](智信%20AIChat%20设计方案.md) | 当前的界面、数据和接口规范，以及保留的早期计划 |
| [路线图](docs/roadmap.md) | 0.4.2～1.0.0 的计划，已定的产品取舍和未验证项 |
| [隐私说明（草稿）](docs/privacy-draft.md) | 数据存在哪里、什么时候离开手机、申请哪些权限 |

## 安装与升级

从 [Releases](https://github.com/KokoroLyase/AIChat/releases) 下载 `AIChat-<版本号>.apk`，同目录的 `.sha256` 是校验文件。

- **覆盖安装**保留数据。所有正式版使用同一个签名；不能降级覆盖。
- **卸载会清空全部本地数据**，卸载前请先导出。
- **应用内更新：** 0.3.0 起可在 App 内下载，校验 SHA-256、包名、版本和签名后拉起系统安装；失败时可在浏览器打开发布页。0.2.0 及更早只能走浏览器。
- **自动检查：** 启动时静默检查 GitHub 最新 Release，24 小时最多一次，可在设置里关闭。

## 备份与恢复

路径：设置 → 数据。系统自动备份已关闭，换机只能用导出和恢复。

| | 不设密码 | 设密码 |
| --- | --- | --- |
| 文件 | 普通 `.zip`，拿到文件的人都能打开 | `.aichat`，整个文件加密（分块 AES-256-GCM，PBKDF2-SHA256 31 万次） |
| API Key | 不导出，恢复后要重新填写 | 可以选择导出；包含 Key 时必须设密码 |
| 忘记密码 | 不涉及 | **无法恢复**，没有找回方式 |

- 导出内容：联系人、聊天记录、记忆、用量统计、服务商、头像、聊天图片和部分设置。
- 恢复方式：「合并」保留本机已有数据，「覆盖」先清空再导入。恢复前自动做快照，失败时回滚，成功后立即删除快照和解密临时文件。
- 旧备份：明文 zip 和只加密 Key 的旧备份都能恢复，v1、v2 会自动迁移。
- 版本差异：比当前 App 新的备份会被拒绝；0.3.1 读不了 `.aichat` 文件。

App 自己加密的只有 API Key 和设了密码的导出文件。手机上的数据库、图片和设置依靠系统的应用隔离和全盘加密，App 没有另外加密。

## 功能

### 聊天

| 功能 | 说明 |
| --- | --- |
| 三个 Tab | 智信（聊天列表）、通讯录、我；可左右滑动切换 |
| 接收回复 | 回复完成后整条出现，不逐字显示；请求交给 WorkManager，离开聊天页或切到后台也会继续，到达时发通知 |
| 分条显示 | 回复里有空行时拆成多条，约 1 秒一条依次出现；每个联系人可关闭 |
| 思考过程 | 可折叠显示，历史思考不回传给模型 |
| 图片 | 系统照片选择器（一次最多 9 张）或系统相机；压缩到长边 2048 像素并去掉 EXIF；只有最近 3 张带原图，模型不支持图片时入口不可用 |
| 消息操作 | 长按复制、删除、重新生成（最后一条回复）、取消回复；失败消息点红色感叹号重试 |
| 长聊天 | Paging 3 + Room 分页，每页 40 条；有未读时定位到第一条未读，只标红点时定位到最新 |
| 看历史 | 收到回复不跳走，显示“↓ N 条新消息”，点击回到底部；自己发消息总是回到底部 |
| 搜索 | 全局搜联系人名称和聊天内容，也可在聊天信息里查找；点结果跳到消息并高亮 |
| 其他 | 表情面板（系统 emoji、最近使用）、草稿、链接识别、置顶、标为未读、清空聊天记录 |

输入栏的语音按钮和设置里的语音合成、语音识别目前只是占位；语音消息计划在 0.7.0，语音通话在 0.8.0。

### 联系人与模型

| 功能 | 说明 |
| --- | --- |
| 联系人 | 新建、编辑、删除；独立的服务商、模型、思考档位、温度、系统提示词、上下文上限、分条显示和自动记忆 |
| 服务商 | 固定顺序的预置：DeepSeek、阿里云百炼（千问）、智谱、Kimi、Xiaomi MiMo、硅基流动、OpenRouter；另有自定义入口。预置只填写新连接，不覆盖已保存地址。Base URL 只支持 https，请求不跟随重定向 |
| 模型配置 | 不内置型号目录。主动获取列表或手动添加 ID；缺失的用途、图片、思考、温度信息保持待确认，准备使用的模型才需补齐 |
| 参数规则 | 思考分不支持、开关、始终开启，档位按实际原生值配置；温度可调、固定或省略；模型可覆盖兼容字段并恢复继承 |
| 刷新与冲突 | 保留手动、已配置、已引用和默认模型，未返回只标记；被引用模型须先解除引用才能删除。只有这次保存新造成的冲突才要确认，请求前阻止无效参数 |
| 上下文预算 | 每个联系人 1000～200000 token（默认 4000），可直接输入或加减；系统提示词、记忆和聊天记录合计不超过它，超出时丢弃最早的消息。估算不用分词库：DeepSeek 按官方比例（汉字 0.6、其他 0.3），其他服务商汉字按 1 |
| 长期记忆 | 未总结的消息超过可用预算 80% 时，把最早的一半总结成一条，单批最多 6000 token；记忆最多占预算 1/4，超出时合并。可查看、编辑、删除，老聊天不自动回溯，可手动分批总结，每个联系人可关闭 |
| 记忆模型 | 联系人 → 记忆：独立选择同服务商的模型和思考档位；未指定时跟随服务商默认，默认为空时回退聊天模型。同联系人串行，全局最多并行 2 个，只保存总结正文 |
| 用量统计 | 按天、按联系人查看 token 用量；记忆总结单独标出，清空聊天不影响统计 |

不提供：模型订阅、OAuth 登录、Anthropic/Google 原生协议、OpenAI Responses、内置或联网模型目录、费用估算、任意请求 JSON 编辑器。

### 设置与排查

| 功能 | 说明 |
| --- | --- |
| 外观与通知 | 深色模式（跟随系统/浅色/深色）、四档 App 字体大小、新消息通知开关和内容预览、电池优化入口 |
| 存储空间 | 设置 → 数据：聊天图片、头像、崩溃日志、缓存和数据库的占用；可清理可再生缓存；清理孤立文件先预览，确认时重新核对引用 |
| 崩溃日志 | 设置 → 关于，或长按 App 图标（独立进程，不依赖数据库）：本机保留 20 份，含 ANR 和原生崩溃的系统退出记录；不含异常说明、聊天内容和 Key，只有主动分享才离开手机 |

## 验证状态

以 0.4.1 为准，详细范围见[路线图第二节](docs/roadmap.md#二现状基线v041-已发布)。

- **已通过：** 647 项 JVM 测试（含截图）和 lint；GitHub CI 和 Release 流程；真实 v0.2.0、v0.3.0 导出文件的恢复；本机假 Release 上的更新下载和 SHA 校验。
- **只用模拟接口测过：** 七家预置。模拟测试通过不代表真实服务商兼容，遇到不兼容可以在模型配置里手动调整。
- **尚未验证：** 正式签名的应用内升级最后安装步骤；TalkBack、系统字体 2.0 叠加 App 特大、整体手势返回和横屏（留到 1.0.0）。

## 版本历史

| 版本 | 主要内容 |
| --- | --- |
| [0.4.1](docs/release-notes/v0.4.1.md) | 修复底座问题：看历史时的小幅位移、日志快捷入口、分享副本清理；上下文上限可直接输入，整理空记忆页面 |
| [0.4.0](docs/release-notes/v0.4.0.md) | 稳定性与性能底座：分页、新消息浮标、整文件加密导出、恢复快照清理、存储空间、崩溃日志 |
| [0.3.1](docs/release-notes/v0.3.1.md) | 七家连接预置、按需配置模型、完整参数规则、独立记忆设置；Room 和备份升到 v3 |
| [0.3.0](docs/release-notes/v0.3.0.md) | 上下文预算、长期记忆、多服务商、用量统计、应用内更新；备份 v2 |
| [0.2.0](docs/release-notes/v0.2.0.md) | 导出恢复、检查更新、聊天信息、搜索、表情面板、草稿 |
| [0.1.0](docs/release-notes/v0.1.0.md) | 三个 Tab、联系人、文字和图片聊天、后台接收、DeepSeek 接入 |

## 构建

需要 JDK（CI 用 21，维护者本机用 25）和 Android SDK（平台 37、构建工具 37）。`local.properties` 里写 `sdk.dir=Android SDK 路径`。如果下载依赖要走代理，给 `GRADLE_OPTS` 和 Gradle 命令都设置 http/https 的 proxyHost、proxyPort。

```bash
./gradlew testDebugUnitTest   # 单元测试（含 Robolectric 截图测试）
./gradlew lintDebug           # 代码检查
./gradlew assembleDebug       # debug 包：包名 isomeria.aichat.debug，可与正式包同时安装
./gradlew assembleRelease     # release 包：需要正式签名（见下）
./gradlew assemblePerf        # perf 包：本地验证用，R8 开启、debug 签名，包名 isomeria.aichat.perf，不发布
```

`perf` 和 debug 包带测试工具（`app/src/testTools/`），release 不含。用 adb 生成虚构消息或触发一次崩溃：

```bash
adb shell am start -n isomeria.aichat.perf/isomeria.aichat.testtools.TestToolsActivity --es action seed --ei count 10000
adb shell am start -n isomeria.aichat.perf/isomeria.aichat.testtools.TestToolsActivity --es action crash
```

新页面的浅色/深色截图：`./gradlew recordRoborazziDebug`。如果 Hilt 生成文件与 lint 并发分析冲突，先 `./gradlew --stop`，再为同一套验收加 `--no-parallel --max-workers=1`；不能把分析器崩溃当作 lint 通过。

## 正式签名

每个版本必须用同一个密钥签名，否则无法覆盖安装；卸载重装会清空全部本地数据。

本地签名读取根目录的 `keystore.properties`（已在 `.gitignore` 里，绝不提交）：

```properties
storeFile=keystore/aichat-release.jks
storePassword=...
keyAlias=aichat
keyPassword=...
```

**`keystore/aichat-release.jks` 和 `keystore.properties` 必须一起备份到至少两个独立位置。**

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
3. 公开分支不带 `docs/audits`、`docs/validation` 等测试证据；分支 CI 通过后再合并到 main
4. 推送 tag `v<版本号>`：`release.yml` 会核对 tag 与 versionName、跑单元测试、构建签名包、生成 `AIChat-<版本号>.apk` 和 `.sha256`，并创建正式 Release（不是 Pre-release）；R8 的 `mapping.txt` 存为 CI 产物，用来还原崩溃日志，不放进 Release，有保留期限

不要把 debug 包发到 Release：debug 包与 release 包签名不同，互相不能覆盖。

## 开发约定

- 新功能在 `feature/功能名` 分支开发，CI 通过后合并回 main；提交信息前缀 `feat` / `fix` / `refactor` / `docs` / `test` / `chore`
- 依赖版本统一放在 `gradle/libs.versions.toml`
- 界面文字全部放 `strings.xml`（`StringResourceGuardTest` 检查）；颜色、尺寸、字号只从 `ui/theme`（`WeTheme.colors / dimens / typography`）取
- 详细日志只在 debug 包输出；任何包都不记录 API Key 和消息正文
- 每次改表：数据库版本号 +1、写迁移脚本、用 `app/schemas` 里的 schema 文件做迁移测试

## 工程结构

```
isomeria.aichat
├─ ui/theme        微信风格主题：颜色、尺寸、字号
├─ ui/components   通用组件：顶栏、Tab 栏、头像、气泡、输入栏、列表行、对话框、菜单、表情面板
├─ feature/        main · chatlist · chat · chatinfo · contacts · contactedit · memory · me · settings · usage · backup
├─ navigation      路由与页面切换
├─ core            纯逻辑：时间显示、链接识别、分条拆分、上下文拼装与预算、记忆策略、用量汇总、拼音分组
├─ di              Hilt 依赖注入
├─ data/db         Room 表与 DAO
├─ data/repo       仓库层（页面只依赖这里）
├─ data/llm        模型服务接口：OpenAI Chat Completions、连接预置、能力/参数规则、兼容编码及 legacy 请求体
├─ data/memory     长期记忆：自动总结、合并，后台任务
├─ data/reply      后台等待回复（WorkManager）与通知
├─ data/settings   MMKV 设置
├─ data/crypto     Android Keystore 加密
├─ data/backup     导出与恢复（格式、整文件加密容器、密码加密、迁移）
├─ data/logs       本地崩溃日志（不依赖 Room 和 MMKV）
└─ update          检查更新与应用内更新（GitHub Release、下载、校验、安装）
```

仓库其他目录：`app/schemas`（Room schema）、`app/screenshots`（截图基线）、`tools/`（`generate_emoji.py` 生成表情列表，`mock_llm.py` 是本机模拟接口）。

## 许可

本项目以 [MIT 许可](LICENSE) 开源。所用第三方库的许可见 App 内「设置 → 关于 → 开源许可」。
