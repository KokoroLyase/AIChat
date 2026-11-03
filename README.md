# 智信 AIChat

安卓端 AI 伴侣聊天应用：界面风格接近安卓版微信，聊天对象是你自己创建的 AI 联系人，所有数据只保存在本机。

- 包名 `isomeria.aichat`，需要 Android 14（API 34）及以上
- 原生 Kotlin + Jetpack Compose，单 Activity，MVVM + StateFlow，Hilt 依赖注入
- 大语言模型：7 家连接预置和自定义 OpenAI Chat Completions 兼容 API；只接入用户自己的 API Key
- 当前版本 **0.4.0**（versionCode 400）
- 后续路线见 [docs/roadmap.md](docs/roadmap.md)，隐私说明草稿见 [docs/privacy-draft.md](docs/privacy-draft.md)
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

## 0.3.0 功能

| 功能 | 说明 |
| --- | --- |
| 上下文预算 | 每个联系人可设 token 上限（默认 4000）；系统提示词、记忆和聊天记录合计不超过它，超出时丢弃最早的消息。估算不用分词库：DeepSeek 按官方比例（汉字 0.6、其他 0.3），其他服务商汉字按 1 |
| 长期记忆 | 未总结的消息超过可用预算的 80% 时，把最早的一半总结成一条记忆，每次请求都带上；最多占预算的 1/4，超出时合并。可查看、编辑、删除；每个联系人可关闭。老聊天不自动回溯，可手动分批总结 |
| 多服务商 | 添加任意兼容 OpenAI 格式的服务商（名称、Base URL、Key），拉取模型列表或手动添加模型；每个模型可标记是否支持图片和思考。Base URL 只支持 https |
| 联系人选服务商 | 编辑页先选服务商，再选模型；Key 各自加密保存 |
| 用量统计 | 设置里按天、按联系人查看 token 用量；记忆总结单独标出，清空聊天不影响统计 |
| 应用内更新 | 下载 APK，用 `.sha256` 校验，核对包名、版本和签名后拉起系统安装；失败时可在浏览器中打开发布页 |
| 备份格式 v2 | 服务商、记忆和用量也会导出和恢复；各服务商的 Key 默认不导出，勾选后用密码加密；v1 备份自动迁移 |

## 0.3.1 功能

| 功能 | 说明 |
| --- | --- |
| 服务商预置 | 固定顺序：DeepSeek、阿里云百炼（千问）、智谱、Kimi、Xiaomi MiMo、硅基流动、OpenRouter；另有自定义入口。品牌、协议和兼容方案独立，预置只填写新连接，不覆盖已保存地址 |
| 按需配置模型 | 不内置型号目录、不拉取第三方目录。主动获取列表或手动添加 ID；缺失的用途、图片、思考、温度信息保持待确认，准备使用的模型才需补齐。列表可访问不代表聊天已验证 |
| 完整参数规则 | 按模型配置实际原生思考档位，区分不支持、开关、始终开启；温度可调、固定或省略，允许思考状态下单独设置规则；模型可覆盖字段和流式用量参数，并恢复继承 |
| 刷新与冲突 | 保留手动、已配置、已引用和默认模型；未返回只标记。刷新不自动选默认，被引用模型须先解除引用才能删除。规则变更时只有这次新造成的冲突才要确认，原有冲突只作提示；联系人原值保留，请求前阻止无效参数。已有联系人没换模型时，可以先保存其他字段 |
| 独立记忆设置 | 联系人 → 记忆：独立选择同服务商的记忆模型及思考档位；未指定时跟随服务商默认，默认空时回退聊天模型。只保存总结正文 |
| 记忆任务反馈 | 同联系人串行，不同联系人最多并行 2 个；显示排队、生成和最近成功/失败。实际请求使用完整配置快照，编辑设置不会混用正在发送的参数 |
| 升级与安全 | Room v3、备份 v3，逐版迁移旧配置、Key 密文、模型身份和联系人设置；保留 legacy DeepSeek OFF/HIGH/MAX 及通用请求体。URL 路径区分大小写，Key 仅按请求格式与完整地址匹配复用；不跟随重定向 |

上下文预算、估算与裁剪、记忆的 80% 阈值/最早一半/6000 token 批次/1/4 预算、合并规则、时间进度及用量归类沿用 0.3.0。语音、订阅、OAuth、其他原生协议和费用估算不在本版范围。

更新说明见 [v0.3.1](docs/release-notes/v0.3.1.md)。七家预置目前只用模拟接口测试过，模拟测试通过不能表示真实服务商全部兼容。

## 0.4.0 功能

| 功能 | 说明 |
| --- | --- |
| 分页加载 | 聊天记录用 Paging 3 加 Room 分页，每页 40 条，不再一次读整个聊天；重试、重新生成只查需要的范围 |
| 定位 | 搜索和聊天内查找定位到消息并居中高亮；有未读时定位到第一条未读，只标红点时定位到最新 |
| 新消息浮标 | 看历史时收到消息不跳走，显示“↓ N 条新消息”，点击回到底部；自己发消息总是回到底部 |
| 导出加密 | 可选密码，设了就加密整个文件（`.aichat`，分块 AES-256-GCM，PBKDF2-SHA256 31 万次，防截断、调换和拼接）；包含 API Key 必须设密码；内层格式仍是 v3，明文 zip 和只加密 Key 的旧备份继续能恢复。0.3.1 读不了加密文件 |
| 恢复快照 | 恢复成功后立即删除 `before-restore.zip` 和解密临时文件；失败仍从快照回滚 |
| 存储空间 | 设置 → 数据：聊天图片、头像、崩溃日志、缓存、数据库的占用；清理缓存只删可再生内容；清理孤立文件先显示数量，确认时重新核对引用，回复或恢复进行中不执行 |
| 崩溃日志 | 设置 → 关于，或长按 App 图标（独立进程，不依赖数据库）：本地保留 20 份，不含异常说明、聊天内容和 Key；ANR 和原生崩溃从系统退出记录补记；release 保留行号，CI 把 `mapping.txt` 存为产物 |

Room 和备份内层格式都没有变，从 0.3.1 升级没有数据迁移；发给模型的请求内容没有变。App 自己加密的只有 API Key 和设了密码的导出文件，其余本地数据依靠系统的应用隔离和全盘加密。

更新说明见 [v0.4.0](docs/release-notes/v0.4.0.md)。

## 构建

本机使用 JDK 25（不修改系统 JAVA_HOME）和 Android SDK（平台 37、构建工具 37）。

```bash
./gradlew testDebugUnitTest   # 单元测试
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

命令行构建时，`local.properties` 里写 `sdk.dir=Android SDK 路径`，`JAVA_HOME` 临时指向 JDK 25。本机下载依赖需要代理 127.0.0.1:10808，GRADLE_OPTS 和 Gradle 命令均设置 http/https proxyHost、proxyPort。

新页面的浅色/深色截图：`./gradlew recordRoborazziDebug`。本机若 Hilt 生成文件与 lint 并发分析冲突，先 `./gradlew --stop`，再为同一套验收加 `--no-parallel --max-workers=1`；不能把分析器崩溃当作 lint 通过。

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
3. 推送 tag `v<版本号>`：`release.yml` 会核对 tag 与 versionName、构建签名包、生成 `AIChat-<版本号>.apk` 和 `.sha256`，并创建正式 Release（不是 Pre-release）；R8 的 `mapping.txt` 存为 CI 产物，用来还原崩溃日志，不放进 Release

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
├─ feature/        main · chatlist · chat · chatinfo · contacts · contactedit · memory · me · settings · usage · backup
├─ navigation      路由与页面切换
├─ core            纯逻辑：时间显示、链接识别、分条拆分、上下文拼装与预算、记忆策略、用量汇总、拼音分组
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

## 许可

本项目以 [MIT 许可](LICENSE) 开源。所用第三方库的许可见 App 内「设置 → 关于 → 开源许可」。
