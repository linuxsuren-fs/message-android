# message-android

[LinuxSuRen/message-hub](https://github.com/LinuxSuRen/message-hub) 的安卓客户端（Kotlin）。message-hub 是一个自托管的消息接收与转发中间件（服务端为 Go + Vue）；本客户端帮你随时随地接收自己服务实例上的消息：配置服务端地址，用邮箱密码登录，然后实时接收本人租户与公开消息。

## 功能特性

- **登录即用**：填入服务端地址（支持内网 `http://`）+ 邮箱 + 密码即可，不保存密码，只保留 12 小时会话 Cookie。
- **实时消息**：通过 [SSE](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) 长连接接收推送，断线后指数退避自动重连（1s、2s、4s … 上限 60s）。
- **消息列表**：卡片式 RecyclerView，展示标题、正文摘要、topic、级别色点（info 灰绿 / warning 橙 / error 红）与相对时间；支持「全部 / 本租户 / 公开」三种范围筛选和手动刷新。
- **后台通知**：应用不在前台时收到消息会发系统通知（Android 13+ 首次启动会申请通知权限），点击通知回到消息列表；前台则以轻提示音提醒。
- **零重型依赖**：HTTP 用 `HttpURLConnection` 封装，JSON 用平台 `org.json`，界面为经典 Views + XML（ViewBinding）；除 Kotlin 协程与 RecyclerView 外无第三方库。

## 下载与安装

- 从 [GitHub Releases](../../releases) 下载 APK。
- **注意**：Release 构建的 APK 未签名（`message-android-x.y.z-release-unsigned.apk`），安装前需要自行签名，例如：

  ```bash
  # 生成调试密钥（已有可跳过）
  keytool -genkey -v -keystore my.keystore -alias myalias -keyalg RSA -validity 10000
  # 对齐 + 签名（Android SDK build-tools 中的工具）
  zipalign -p -f 4 message-android-x.y.z-release-unsigned.apk aligned.apk
  apksigner sign --ks my.keystore --out message-android.apk aligned.apk
  ```

- 不想折腾签名的话，直接从 Actions 的构建产物里下载 `app-debug.apk` 安装即可（CI 页面 → 最新一次构建 → Artifacts）。

## 快速开始

1. 准备一个 message-hub 服务端实例（部署方式见[服务端仓库](https://github.com/LinuxSuRen/message-hub)），确认手机能访问其地址（内网需同一局域网）。
2. 安装并打开本客户端，在登录页填入服务端地址，例如 `https://hub.example.com` 或 `http://192.168.1.10:8080`（省略协议时默认按 `https://` 处理）。
3. 输入邮箱与密码登录。登录成功后进入消息列表，之后每次打开应用会自动跳过登录（直到会话过期）。
4. 保持应用在后台或前台即可实时收消息；顶部的实时通道状态行会显示连接/重连进度。

## 构建

- **Android Studio**：直接用 Android Studio 打开仓库根目录，等待 Gradle 同步完成后运行 `app` 配置。
- **命令行**（需要 JDK 17+）：

  ```bash
  ./gradlew assembleDebug      # Linux/macOS（Windows 用 gradlew.bat）
  ```

- **CI**：每次 push 与 pull request 会触发 [CI](.github/workflows/ci.yml)，在 ubuntu-latest 上执行 `./gradlew assembleDebug` 并上传 APK 产物；打 `v*` tag 会触发 [Release](.github/workflows/release.yml) 构建并上传未签名的 release APK。

## 工作原理

- **登录换会话**：`POST /api/v1/auth/login` 成功后服务端通过 `Set-Cookie` 下发 12 小时有效的 `mh_session` 会话；客户端用 `java.net.CookieManager`（设为进程默认 `CookieHandler`）统一保管，REST 请求与 SSE 流自动携带同一 Cookie。Cookie 值会存入 SharedPreferences 以便冷启动免登录，密码永不落盘；会话过期（SSE 或列表请求返回 401）时自动回到登录页。
- **SSE 长连接 + 退避重连**：客户端对 `GET /api/v1/stream` 发起 `Accept: text/event-stream` 请求，逐行解析 `event:` / `data:`（多行 data 按 SSE 规范合并），忽略注释行；服务端约每 20 秒发一次 `heartbeat` 保活。连接中断后按 1s、2s、4s…（上限 60s）指数退避重连，曾经成功建立过的连接断开后重置为 1s 快速重连；遇到 401/403 则停止重试并要求重新登录。离开消息页时取消协程并断开连接。
- **后台通知**：进程在前台时新消息插入列表顶部并播放一声轻提示；不在前台时（应用退到后台、进程仍存活）发系统通知，点击通知打开消息列表。注意：应用被系统杀死或被用户划掉后长连接随之断开，无法继续收消息——这是无前台服务设计的有意取舍。
- **网络与线程**：所有网络请求运行在 `Dispatchers.IO` 协程中，UI 更新回到主线程；错误提示全部本地化（服务端 `error` 字段的可读信息优先展示）。

## 涉及的服务端 API

| 接口 | 用途 |
| --- | --- |
| `POST /api/v1/auth/login` | 邮箱密码登录，换取 `mh_session` 会话 Cookie |
| `GET /api/v1/messages?limit=50&cursor=&scope=all\|own\|public` | 拉取消息列表（id 降序，`next_cursor` 分页游标） |
| `GET /api/v1/stream` | SSE 实时流（`message` / `heartbeat` 事件） |

## 参与贡献

欢迎通过 issue 与 pull request 参与开发；PR 请保持单一主题、附自测说明。

## 开源协议

[MIT](LICENSE)
