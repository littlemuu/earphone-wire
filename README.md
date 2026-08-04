# 耳机线

`Android QQ 音乐 → Cloudflare Worker → 私有 MCP → ChatGPT`

本仓库当前实现“第三关 A”：Android 只向受保护的上传接口发送 QQ 音乐最新播放快照；MCP 仅在 GitHub OAuth 认证、数字 GitHub 用户 ID 白名单和 `playback:read` scope 同时通过后读取该快照。

`android/` 不属于本工单改动范围。服务端不包含真实歌曲、令牌或 OAuth 凭证。

## 服务端设计

- `POST /api/v1/now-playing` 只接受 Bearer 上传凭证和不超过 4 KiB 的严格 JSON。它不开放 CORS，也没有读取快照的普通 HTTP GET 端点。
- `NowPlayingDurableObject` 使用固定名称 `earphone-wire-single-user-now-playing-v1`，SQLite 中只有一行最新快照。较旧的 `observedAt` 不能覆盖新数据，相同 `eventId` 重试幂等。
- 快照在 `receivedAt` 后 120 秒变为“最后一次上报”；24 小时后 Durable Object alarm 删除它。MCP 对已过期快照与从未收到快照采用相同的空结果。
- `/mcp` 由 `@cloudflare/workers-oauth-provider` 保护，提供 OAuth 发现、授权码 + PKCE 和动态客户端注册。GitHub 只用于验证身份，访问令牌不会保存到快照、日志或工具结果。
- `/health` 保持匿名，只返回服务状态与 `secure-relay` 阶段。

## 本地验证

在 `server/` 中运行：

```bash
npm install
npm run typecheck
npm test
npx wrangler deploy --dry-run
```

`npm test` 使用运行时生成的测试凭证，覆盖上传校验、幂等、旧数据拒绝、陈旧/过期语义、OAuth challenge、用户 ID/scope 拒绝、已授权 MCP 输出和 `/health` 隐私边界。

## 用户完成外部配置后再部署

本工单没有创建 GitHub OAuth App、设置 Worker secret 或部署。部署前，用户应先创建 GitHub OAuth App：

- Homepage URL：`https://<worker-name>.<account-subdomain>.workers.dev`
- Authorization callback URL：`https://<worker-name>.<account-subdomain>.workers.dev/callback`

在 Worker 中以 secret 方式配置下列变量（仅名称，不要把值提交到仓库）：

```bash
npx wrangler secret put ANDROID_UPLOAD_TOKEN
npx wrangler secret put ALLOWED_GITHUB_USER_ID
npx wrangler secret put GITHUB_CLIENT_ID
npx wrangler secret put GITHUB_CLIENT_SECRET
npx wrangler secret put COOKIE_ENCRYPTION_KEY
```

复制 `.dev.vars.example` 到本地未跟踪的 `.dev.vars` 仅用于本机测试。`wrangler.jsonc` 已声明 OAuth 用的 `OAUTH_KV` 绑定和 `NowPlayingDurableObject` 的 SQLite migration；首次真实部署前请由用户确认账户权限和资源创建提示。部署后，在 ChatGPT 开发者模式中重新连接或刷新 MCP 应用，使用 `https://<worker-name>.<account-subdomain>.workers.dev/mcp`，完成 GitHub 登录并重新授权 `playback:read`。

尚未部署，尚未上传真实歌曲，`android/` 未改动。
