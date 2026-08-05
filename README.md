# 耳机线

`Android QQ 音乐 → Cloudflare Worker → 私有 MCP → ChatGPT`

耳机线把 Android 手机上的 QQ 音乐当前播放状态，经由受保护的 Cloudflare Worker 转成只读 MCP 工具，供已授权的 ChatGPT 会话读取。

当前端到端链路已经完成部署、配对与真机验收：

- Android 客户端版本：`0.3.0-secure-relay`
- Worker：`https://earphone-wire-mcp.andxiaoqie.workers.dev`
- MCP：`https://earphone-wire-mcp.andxiaoqie.workers.dev/mcp`
- MCP 工具：`get_now_playing`
- QQ 音乐滚动歌词场景下，歌名与歌手已通过真机字段证据完成稳定映射
- Android 回归基线：37 项 JVM 测试、`lintDebug` 与 `assembleDebug` 全部通过

## 工作方式

1. 用户在 Android 系统中为耳机线开启通知使用权。
2. App 只读取包名为 `com.tencent.qqmusic` 的活动 `MediaSession`，不读取通知正文。
3. App 规范化歌名、歌手与播放状态，并通过 HTTPS Bearer 请求上传最新快照。
4. Worker 在 Durable Object 中只保留一份最新快照。
5. ChatGPT 通过 GitHub OAuth、数字 GitHub 用户 ID 白名单和 `playback:read` scope 调用只读 MCP 工具。

上传字段仅包含：

- `eventId`
- `title`
- `artist`
- `playbackState`
- `playerPackage`
- `observedAt`

快照在 Worker 收到后 120 秒内视为新鲜；超过 120 秒会明确标记为最后一次上报，不能被描述成仍在播放。24 小时后数据自动删除。

## Android 构建与安装

要求：

- JDK 17 或更高版本
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- Android SDK Platform-Tools（含 `adb`）

在仓库根目录运行：

```powershell
.\android\gradlew.bat -p .\android testDebugUnitTest lintDebug assembleDebug --no-daemon
adb install -r ".\android\app\build\outputs\apk\debug\app-debug.apk"
```

覆盖安装不会清除现有配对数据。Debug APK 位于：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

### 真机配对

1. 打开耳机线 App，并在系统设置中开启“通知使用权”。
2. 在 **Secure relay pairing** 中填写 Worker origin：
   `https://earphone-wire-mcp.andxiaoqie.workers.dev`
3. 填写与 Worker 中 `ANDROID_UPLOAD_TOKEN` 相同的上传令牌并保存。
4. 点击 **Test connection**；它只读取匿名 `/health`，不会上传媒体数据。
5. 播放 QQ 音乐。App 会在会话变化后自动上报，并每 60 秒重新观察一次活动会话。

上传令牌使用 Android Keystore AES-GCM 加密后保存在本机，保存后不会再次显示。若 Worker 返回 `401`，App 会要求重新配对；临时网络错误采用有限退避重试。

## QQ 音乐元数据映射

QQ 音乐滚动歌词时，`TITLE` 可能变成当前歌词，而 `ARTIST` 会呈现 `歌名-歌手`，`ALBUM_ARTIST` 则继续保存独立歌手。

因此映射器只在 `ARTIST` 精确以 ASCII `"-" + ALBUM_ARTIST` 结尾时，移除这个完整后缀并恢复歌名；它不依赖歌词化的 `TITLE`，也不会按任意连字符盲拆。若证据不足，则保留 Android 元数据的普通回退顺序，不进行猜测。

## 连接 ChatGPT

在 ChatGPT 开发者模式中添加或刷新 MCP 连接：

```text
https://earphone-wire-mcp.andxiaoqie.workers.dev/mcp
```

随后完成 GitHub 登录并授权 `playback:read`。只有 `ALLOWED_GITHUB_USER_ID` 指定的数字 GitHub 用户 ID 可以读取快照。

连接成功后，可调用 `get_now_playing` 获取：

- 是否存在快照
- 歌名与歌手
- 播放状态与播放器包名
- 手机观察时间与 Worker 接收时间
- `stale` 新鲜度标记

## 服务端开发与验证

要求 Node.js 22 或更高版本。在 `server/` 中运行：

```bash
npm install
npm run typecheck
npm test
npx wrangler deploy --dry-run
```

本地开发可复制 `.dev.vars.example` 为未跟踪的 `.dev.vars`。不得提交真实凭证。

真实部署所需的 Worker secrets：

```bash
npx wrangler secret put ANDROID_UPLOAD_TOKEN
npx wrangler secret put ALLOWED_GITHUB_USER_ID
npx wrangler secret put GITHUB_CLIENT_ID
npx wrangler secret put GITHUB_CLIENT_SECRET
npx wrangler secret put COOKIE_ENCRYPTION_KEY
```

GitHub OAuth App 使用：

- Homepage URL：`https://<worker-name>.<account-subdomain>.workers.dev`
- Authorization callback URL：`https://<worker-name>.<account-subdomain>.workers.dev/callback`

`wrangler.jsonc` 已声明 `OAUTH_KV`、`NowPlayingDurableObject` 及其 SQLite migration。

## 安全与隐私边界

- 上传接口不开放 CORS，也没有读取快照的普通 HTTP GET 端点。
- `/health` 匿名可用，但只返回服务状态，不包含播放数据。
- Android 禁止明文流量，只增加 `INTERNET` 权限及用户主动开启的通知监听服务。
- App 不请求存储、麦克风、相机、无障碍或通知正文权限。
- 上传令牌、OAuth 凭证、歌曲数据和请求正文不写入日志。
- GitHub OAuth 访问令牌不保存到播放快照、日志或 MCP 工具结果。
- 相同 `eventId` 的上传可幂等重试；较旧的 `observedAt` 不能覆盖新快照。

## 仓库结构

- `android/`：QQ 音乐 MediaSession 读取、安全配对与快照上报
- `server/`：Cloudflare Worker、Durable Object、GitHub OAuth 与私有 MCP
