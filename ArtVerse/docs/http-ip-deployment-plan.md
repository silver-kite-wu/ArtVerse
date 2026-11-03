# 无域名 / 纯 HTTP 个人服务器部署改造方案

> 方案 A 已在代码中实施（前端兼容层、调用点替换、Accept 修正、`cors-origins` 配置化）。服务器 `.env` 更新、重新打包部署和 LLM 配置仍需按本文档第 8 节执行；方案 B（Tailscale / Cloudflare Tunnel）尚未实施。

## 1. 背景与约束

- 服务器为个人服务器，只有公网 IP，不准备申请域名，也不配置公网 HTTPS。
- 前端由 Nginx 同源托管，`/api` 反向代理到后端；浏览器 Origin 为 `http://<公网IP>`（带端口时需一并包含）。
- 已出现的问题：
  - 登录报 `CSRF verification failed`（HTTP 403，code 40303）。
  - AI 助手发送消息报 `HTTP 400:`。
  - 进入定时任务页并操作时报 `应用出错 crypto.randomUUID is not a function`。

## 2. 根因汇总

| 现象 | 直接原因 | 代码位置 |
| --- | --- | --- |
| CSRF 403 | `artverse.cors-origins` 写死为 localhost，服务器 IP Origin 不在白名单 | `ArtVerse/src/main/resources/application.yml` 的 `cors-origins`；`ArtVerse/src/main/java/com/artverse/security/CsrfProtectionFilter.java` |
| 定时任务报错 | `crypto.randomUUID` 只在安全上下文（HTTPS / localhost）可用，HTTP 下不存在 | `frontend/src/components/automations/AutomationComposer.tsx`、`AutomationCenterPage.tsx`、`api/automations.ts` |
| AI 助手 HTTP 400 | 后端同步返回 400（多为 BYOK LLM 配置缺失）；同时客户端 `Accept: text/event-stream` 导致 JSON 错误体无法写出，界面只剩 `HTTP 400:` | `StoryChatController.requireLlmConfig`、`ApiKeyService.requireActiveUserProviderConfig`、`frontend/src/api/agentRuns.ts` |
| AI 对话隐性 400 风险 | HTTP 下 `crypto.randomUUID` 不可用，前端回退的 `时间戳-随机数` 不是合法 UUID，而后端 `RunRequest.requestId` 是 `UUID` 类型 | `frontend/src/components/ChatPanel.tsx`、`frontend/src/api/dto/MangaAgentDtos.java` |
| 生图提交报错（尚未暴露） | `crypto.subtle.digest` 同样只在安全上下文可用，HTTP 下点击生图会抛错 | `frontend/src/api/imageRequests.ts` |

结论：应用层需去掉对安全上下文 API 的强依赖；后端需让 Origin 白名单可配置；LLM 配置需在服务器域名（IP）下重新保存。

## 3. 设计目标

1. 在 `http://<公网IP>` 下，登录、AI 助手、定时任务、生图提交、读者角色聊天等功能均可正常使用。
2. 不引入域名、证书和额外付费服务；保留可选的免费 HTTPS 通道用于安全性升级。
3. 不削弱现有安全机制（CSRF、登录防爆破、BYOK 等）。

## 4. 方案 A：应用层适配纯 HTTP

### 4.1 新增安全上下文兼容层

新增文件 `frontend/src/utils/browserSecurity.ts`：

```ts
// crypto.randomUUID / crypto.subtle 仅在安全上下文（HTTPS、localhost）可用。
// 本文件提供在纯 HTTP 下的等价实现；随机数仍来自 crypto.getRandomValues（HTTP 可用）。

export function randomUUID(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();

  const bytes = new Uint8Array(16);
  if (c && typeof c.getRandomValues === 'function') c.getRandomValues(bytes);
  else for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);

  // RFC 4122 version 4
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function fnv1a(input: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, '0');
}

// 仅用于 localStorage 幂等键，不用于安全用途。
export async function stableDigest(input: string): Promise<string> {
  if (globalThis.crypto?.subtle) {
    const buffer = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
    return Array.from(new Uint8Array(buffer), v => v.toString(16).padStart(2, '0')).join('');
  }
  return `${fnv1a(input)}${fnv1a(`|${input}`)}`;
}
```

说明：
- `crypto.getRandomValues` 不属于安全上下文限制，因此 HTTP 下仍可生成 UUID v4。
- `stableDigest` 的降级实现只改变本地幂等键的形态，不影响后端校验。

### 4.2 替换所有直接调用点

| 文件 | 位置 | 改造 |
| --- | --- | --- |
| `frontend/src/api/agentRuns.ts` | `createClientRequestId`（约 393-398 行） | 删除本地实现，改用 `randomUUID` |
| `frontend/src/components/ChatPanel.tsx` | `createLocalRequestId`（约 672-675 行） | 删除本地回退，改用 `randomUUID` |
| `frontend/src/api/automations.ts` | `saveAutomation` 默认参数（约 70 行） | 改用 `randomUUID` |
| `frontend/src/components/automations/AutomationCenterPage.tsx` | `keyFor`（约 35 行） | 改用 `randomUUID` |
| `frontend/src/components/automations/AutomationComposer.tsx` | `useRef` 初始化（约 20 行） | 改用 `randomUUID` |
| `frontend/src/api/imageRequests.ts` | 44 行摘要、46/108/116 行 id | 摘要改 `stableDigest`，id 改 `randomUUID` |
| `frontend/src/components/ImageGenerationReviews.tsx` | 约 43 行 `action_id` | 改用 `randomUUID` |
| `frontend/src/components/imageGenModel.ts` | 约 54 行 | 改用 `randomUUID` |
| `frontend/src/components/ReaderCharacterChatPanel.tsx` | 约 240 行 `clientMessageId` | 改用 `randomUUID` |
| `frontend/src/components/knowledge/FactEditor.tsx` | 约 11、32 行 `transaction_id` | 改用 `randomUUID` |

改造完成后，HTTP 下前端不再产生 `crypto.randomUUID is not a function`，并且发给后端的 `requestId` 始终是合法 UUID。

### 4.3 剪贴板降级

`navigator.clipboard` 在 HTTP 下为 `undefined`。涉及位置：

- `frontend/src/components/ImageGenPage.tsx` 约 600-601、1040、1115-1116 行。

处理方式：
- 调用前判断 `navigator.clipboard` 是否存在；不存在时提示“当前 HTTP 环境不支持复制，请改用 HTTPS 或手动保存”。
- 文本复制可选降级到 `document.execCommand('copy')`；图片复制不做降级。

### 4.4 让后端错误信息可见

`frontend/src/api/agentRuns.ts` 的 `requestInit` 中：

```ts
// 改造前
Accept: 'text/event-stream',

// 改造后
Accept: 'text/event-stream, application/json',
```

原因：成功响应仍是 SSE；失败时 Spring 可以协商出 JSON，前端即可展示后端 `Result.message`，而不是只剩 `HTTP 400:`。

### 4.5 后端 Origin 白名单配置化

`ArtVerse/src/main/resources/application.yml`：

```yaml
artverse:
  cors-origins: ${ARTVERSE_CORS_ORIGINS:http://localhost:5173,http://127.0.0.1:5173}
```

服务器 `.env`：

```properties
ARTVERSE_CORS_ORIGINS=http://<公网IP>
ARTVERSE_AUTH_COOKIE_SECURE=false
```

注意事项：
- 若站点带端口（如 `http://<IP>:8080`），必须写完整 Origin，且与浏览器地址栏完全一致。
- 需要同时保留 `http://localhost:5173` 时，用逗号分隔。
- 不要设置 `SPRING_PROFILES_ACTIVE=production`：该 Profile 强制要求 `ARTVERSE_AUTH_COOKIE_SECURE=true` 且校验强密钥，纯 HTTP 下会导致启动失败（`ProductionSecurityConfigurationValidator`）。个人服务器使用默认 Profile 即可。
- Nginx 不要覆盖或删除 `Origin` 请求头；同源托管 + `/api` 反代时无需额外 CORS 配置。

### 4.6 LLM 配置（HTTP 400 的另一半）

BYOK 配置按用户保存在服务端数据库，浏览器 localStorage 按源隔离，localhost 的配置不会带到 `http://<IP>`。

处理步骤：
1. 在服务器站点打开「设置 → AI 模型」。
2. 新建或确认 LLM 供应商：填写 Base URL、API Key、模型，保存并确保状态为启用。
3. 回到 AI 助手，确认模型选择器不为空后重新发送。

若改造 4.4 后仍返回 400，界面会直接显示后端文案（例如“请先配置可用的 AI 模型”），按文案处理即可。

## 5. 方案 B：无域名也能使用 HTTPS（可选，推荐叠加）

### 5.1 Tailscale

适合个人服务器，无需购买域名，且不向公网暴露端口。

1. 服务器与自己的电脑/手机安装 Tailscale，登录同一 tailnet。
2. 在 tailnet 管理后台开启 HTTPS 证书。
3. 服务器执行：

```bash
sudo tailscale serve --bg http://127.0.0.1:80
```

4. 通过 `https://<机器名>.<tailnet>.ts.net` 访问：
   - 是真正的 HTTPS，`window.isSecureContext === true`；
   - `crypto.randomUUID`、`crypto.subtle`、`navigator.clipboard` 均可用；
   - 仍建议保留方案 A，避免切换访问地址后功能差异。

### 5.2 Cloudflare 临时隧道（应急）

```bash
cloudflared tunnel --url http://127.0.0.1:80
```

得到一个 `https://xxx.trycloudflare.com` 地址，具备安全上下文；缺点是地址每次重启会变化，且服务对公网可见。

### 5.3 不推荐：IP 自签证书

浏览器会持续显示证书警告，iOS/Android 需手动安装信任，维护成本高于收益。

## 6. 验证清单

1. 登录：不再出现 `CSRF verification failed`；登录后刷新仍保持会话。
2. 定时任务：进入页面、新建、保存、立即执行均不再出现 `crypto.randomUUID is not a function`。
3. AI 助手：发送消息得到正常回复；若失败，任务提示中能看到后端具体 `message`。
4. 生图：提交请求不再出现 `crypto.subtle` 相关错误；等待回调与结果展示正常。
5. 读者角色聊天、知识库编辑等使用 UUID 的入口不报错。
6. 浏览器控制台执行 `window.isSecureContext` 可确认当前上下文（HTTP 下为 `false`，属预期）。

## 7. 风险与说明

- 纯 HTTP 下密码、BYOK API Key、正文内容均以明文经过公网，建议至少：
  - 云服务器安全组限制来源 IP；或
  - 日常访问改用 Tailscale 等加密通道。
- `ARTVERSE_AUTH_COOKIE_SECURE=false` 仅适用于 HTTP；一旦启用 HTTPS，应立即改为 `true`，并同步把 `ARTVERSE_CORS_ORIGINS` 改为 HTTPS Origin。
- 若未来申请域名并启用 HTTPS，可删除方案 A 中的兼容代码（非必须，保留也不影响 HTTPS 行为）。

## 8. 执行顺序

1. 后端：`cors-origins` 配置化 -> 部署新 JAR -> 更新 `.env` -> 重启后端。
2. 前端：新增 `browserSecurity.ts` -> 替换调用点 -> 修改 `Accept` -> 剪贴板降级 -> `npm run lint && npm run build` -> 替换 Nginx 静态文件。
3. 服务器站点重新保存并启用 LLM BYOK 配置。
4. 按第 6 节逐项验证。
5. 可选：按第 5 节接入 Tailscale，日常改用 HTTPS 地址。
