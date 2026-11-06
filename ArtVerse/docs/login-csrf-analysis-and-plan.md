# 本地登录 CSRF 失败：原因与解决方案

日期：2026-11-04。状态：已完成源码分析、开发启动入口、诊断改进及回归测试；本机后端已按本地配置重启，CSRF 修复已通过真实 HTTP 请求验证。真实账号与浏览器 Cookie 会话回归尚未执行。实施明细见第 8 节。

## 1. 已确认的结论

用户通过 `http://localhost:5173` 或 `http://127.0.0.1:5173` 使用本地前端。本机 `.env` 却配置为：

```properties
ARTVERSE_CORS_ORIGINS=https://artverse.duckdns.org
ARTVERSE_AUTH_COOKIE_SECURE=true
```

运行中的后端确实拒绝 localhost 来源，接受上述 HTTPS 域名来源。因此，本次错误的直接原因是本地开发加载了线上来源配置，浏览器发送的 Origin 不在后端白名单中。

Cookie 的 HTTPS 配置也混入本地环境，应一并拆分，但它不参与当前 CSRF 过滤器的拒绝判断，不能将其解释为本次 403 的原因。具体 Cookie 是否被本地浏览器接受，需单独查看浏览器行为；不能据此宣称已复现登录会话故障。

## 2. 代码证据与请求链

1. `frontend/src/api/auth.ts`：`BASE = ''`，登录请求为相对路径 `/api/auth/login`，通过 `requestHeaders('POST', true)` 附加 `X-ArtVerse-Client: web`，使用 `credentials: 'same-origin'`。
2. `frontend/vite.config.ts`：开发服务器代理 `/api` 至 `http://127.0.0.1:8080`，也支持 `ARTVERSE_API_TARGET` 覆盖。
3. `src/main/resources/application.yml`：导入启动工作目录下的 `.env` 和 `../.env`；`cors-origins` 通过 `ARTVERSE_CORS_ORIGINS` 读取，未设置时才使用 localhost 默认值。
4. `src/main/java/com/artverse/security/CsrfProtectionFilter.java`：对 `/api/` 下的 POST/PUT/PATCH/DELETE 请求，要求 Origin 存在且精确命中允许来源，同时要求自定义请求头值正确。登录接口同样受保护。
5. 任一条件不满足即返回 HTTP 403、业务码 `40303` 和 `CSRF verification failed`，不会进入登录控制器验证账号密码。
6. `src/main/java/com/artverse/config/CorsConfig.java`：CORS 与 CSRF 共用来源列表。前端与 Vite 同源，不代表代理后的请求可以跳过项目显式的 Origin 白名单判断。

简化链路：

```text
本地浏览器 POST /api/auth/login
  Origin: http://localhost:5173
  X-ArtVerse-Client: web
        ↓ Vite /api 代理
本地后端 CsrfProtectionFilter
  validHeader = true
  validOrigin = false（白名单只有线上 HTTPS 域名）
        ↓
HTTP 403 / code 40303
```

这里使用的是“来源白名单 + 自定义请求头”校验，不存在本次需要重新获取或刷新的 CSRF Token。

## 3. 本机验证结果

使用空 JSON `{}`，未提交账号密码，未尝试真实登录。测试在本机 5173/8080 端口进行，域名仅作为 Origin 请求头，没有向线上服务器发起请求。

| 请求入口 | Origin | 客户端请求头 | 实际结果 |
| --- | --- | --- | --- |
| Vite 5173 `/api/auth/login` | `http://localhost:5173` | `X-ArtVerse-Client: web` | 403，`40303`，CSRF 校验失败 |
| 后端 8080 `/api/auth/login` | `http://localhost:5173` | `X-ArtVerse-Client: web` | 403，`40303`，CSRF 校验失败 |
| 后端 8080 `/api/auth/login` | `https://artverse.duckdns.org` | `X-ArtVerse-Client: web` | 400，邮箱不能为空 |
| 后端 8080 `/api/auth/login` | `https://artverse.duckdns.org` | 缺失 | 403，`40303`，CSRF 校验失败 |

第三项的 400 是正向证据：CSRF 已放行，随后进入请求参数校验。第一、二项也说明该失败无需经过 Vite 即可复现，不必先修改代理。

上述表格记录修复前的结果；修复后的请求结果见第 8 节。尚未验证：真实账号登录、浏览器 Cookie 保存、已登录会话刷新、线上服务器的运行配置。

## 4. 第一阶段：恢复本地登录

优先在本地后端启动进程中覆盖配置，避免把共享 `.env` 改成同时承担开发和生产配置。

在启动后端的 PowerShell 中设置：

```powershell
$env:ARTVERSE_CORS_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173'
$env:ARTVERSE_AUTH_COOKIE_SECURE = 'false'
```

停止旧的本地后端后，从设置上述变量的同一终端，按项目原有方式启动后端；若使用 Maven，则在 `ArtVerse` 后端目录执行 `mvn spring-boot:run`。不能只刷新页面或重启前端，配置必须由新后端进程加载。

该本地 HTTP 方案使用开发/默认 Profile。若当前启用了 `prod` 或 `production`，应改用本地开发启动方式；生产校验器要求 Secure Cookie 和 CSRF enforce，不修改该要求。

继续保留：

```yaml
artverse:
  auth:
    csrf:
      mode: enforce
```

允许来源必须与实际页面 Origin 对应，不包含路径或末尾 `/`。若使用局域网 IP 或更换前端端口，应明确添加相应来源。无需将后端地址 `http://127.0.0.1:8080` 作为前端来源添加。

生产进程继续独立使用 HTTPS 域名白名单、`ARTVERSE_AUTH_COOKIE_SECURE=true` 和现有生产校验，不把本地 HTTP 设置同步到服务器。

## 5. 第二阶段：避免再次混用环境配置

### 5.1 独立的开发启动入口

新增仓库内的本地后端启动脚本，按脚本位置确定后端工作目录，并显式注入第一阶段的两项非敏感配置。仅使用进程环境变量，避免写入用户级或机器级全局环境变量。密钥继续由现有受控环境提供，不拷贝进脚本。

保留现有生产启动流程；在部署文档分别列出开发与生产的来源、Cookie 和 Profile。进一步拆分 `application-dev.yml` / `application-prod.yml` 时，应同时验证 `.env` 导入顺序与变量覆盖关系，避免仅增加 Profile 文件却仍读取到旧的生产配置。

为 Vite 显式设置 `port: 5173`、`strictPort: true`，让端口占用时启动失败并提示用户处理，避免自动切换端口后再次与固定来源白名单失配。

### 5.2 增加可诊断性

`CsrfProtectionFilter` 当前在 enforce 拒绝时没有记录具体失败维度。建议增加结构化日志，记录方法、路径和有限原因枚举：

- `ORIGIN_MISSING` / `ORIGIN_NOT_ALLOWED`；
- `CLIENT_HEADER_MISSING` / `CLIENT_HEADER_INVALID`。

仅记录是否命中及原因，不记录密码、Cookie、令牌或请求体；对外继续保留 HTTP 403 与 `40303`。

前端按 `40303` 显示可操作中文提示，例如“请求来源校验失败，请联系管理员检查站点来源配置”。不把它当成密码错误，也不触发刷新会话和自动重试。

### 5.3 保持明确的信任边界

- 不关闭 CSRF，不将 enforce 改成 report，不豁免登录接口。
- 不使用通配符来源，不自动信任任意 Host、Origin 或转发请求头。
- 不通过代理伪造线上 Origin 来绕过校验，不让前端伪造浏览器 Origin。
- 不改成“来源正确或自定义请求头正确即可”；保持两者同时满足。
- 当前客户端请求头已经存在，无需为本次问题增加 CSRF Token 获取接口或 Cookie Token 协议。

## 6. 验收方案

### 6.1 配置恢复后的请求验证

1. localhost 和 127.0.0.1 的 5173 来源，携带正确客户端请求头、提交 `{}`：均应返回参数校验 400，不能再返回 `40303`。
2. 已允许来源缺少或错误客户端请求头：仍返回 `40303`。
3. 未允许来源，即使携带正确客户端请求头：仍返回 `40303`。
4. 缺少 Origin 或 Origin 为 `null`：仍返回 `40303`。
5. GET 与预检 OPTIONS 的行为保持正常，同时验证实际 CORS 过滤器的响应。

### 6.2 浏览器回归

使用用户提供或本地环境配置的测试账号，分别在 localhost 与 127.0.0.1 独立登录，检查：登录成功、后续 `/api/user/me` 成功、刷新页面维持会话、刷新令牌及退出正常。两个地址应各自测试，不将其中一个地址的 Cookie 会话视为另一个地址的已有会话。

检查登录响应及浏览器 Cookie 接受情况；第一阶段 Cookie 配置恢复是为了匹配开发环境，不代替这一验证。

### 6.3 实现阶段的自动化检查

扩展 `CsrfProtectionFilterTest`：来源与客户端请求头的独立拒绝、两个开发来源、生产来源、未知来源、缺少/`null` 来源及安全方法跳过。增加实际过滤器链的集成验证，覆盖 CORS 与 CSRF 的共同作用；现有两个测试不足以覆盖上述配置差异。

后端代码修改后运行针对性测试与 `mvn -q -DskipTests compile`；前端修改后运行 `npm run lint`、`npm run build`，有错误解析测试基础时补充 `40303` 提示映射用例。

## 7. 实施顺序与回退

先按第一阶段覆盖本地启动配置并重启，再完成请求和浏览器回归；随后落地开发启动入口、固定 Vite 端口和诊断改进。

回退时恢复原本的本地启动方式或重启前移除两项进程覆盖变量。所有变更均不涉及数据库迁移；生产来源和 Secure Cookie 配置单独验证，不随本地回退修改。

## 8. 实施与验证记录

### 8.1 已实施

- 新增 `ArtVerse/start-backend-local.ps1`（相对于仓库根目录），固定后端工作目录、`local` Profile、两个 5173 来源、本地 HTTP Cookie，以及 CSRF enforce。支持 `-Background` 隐藏运行和日志输出；退出时恢复调用终端的环境变量，未设置的变量会删除，避免留下空环境变量。检测 8080 占用并给出明确错误。
- 支持可选的 `-RedisHost` 参数，仅对该启动进程覆盖 Redis 主机。本次机器的 DataGrip 占用 IPv4 `127.0.0.1:6379` 且连接会被提前关闭；Redis 容器在 IPv6 `::1:6379` 的 PING 正常。因此本机使用 `-RedisHost ::1` 成功启动，未修改 DataGrip 配置或生产配置。
- Vite 固定 `port: 5173`、`strictPort: true`。
- CSRF 日志新增来源与客户端请求头的独立拒绝原因，未改变校验条件或响应契约。
- 前端将 `40303` 映射为中文来源配置提示，保留业务码和 HTTP 状态。
- 扩展过滤器单元测试、新增 CORS/CSRF 过滤器链测试；补充生产安全约束回归，并补齐原有生产通过用例缺少的 MCP 测试签名密钥。
- 新增 `frontend/test/auth.test.ts`（现有 `frontend/tests/` 整目录被忽略，因此单独使用可跟踪的测试目录），覆盖中文提示、登录错误信息及 CSRF 拒绝不刷新、不重试。
- 更新中英文 README 的本地脚本及 IDE 环境变量说明。未改写现有 `.env`。

本次本机后台启动命令（仓库根目录）：

```powershell
.\ArtVerse\start-backend-local.ps1 -Background -RedisHost ::1
```

后端正在运行时无需再次启动。日志位于 `ArtVerse/target/backend-local.log`、`ArtVerse/target/backend-local.err.log`。一般环境未遇到 Redis IPv4 冲突时，可省略 `-RedisHost ::1`。

### 8.2 已通过的检查

```text
后端：mvn -q -Dtest=CsrfProtectionFilterTest,CsrfCorsFilterChainTest,ProductionSecurityConfigurationValidatorTest test
结果：38 个用例通过（25 + 8 + 5），0 失败、0 错误。

后端：mvn -q -DskipTests compile
结果：通过。

前端：npm run test:auth
结果：3 个用例通过。

前端：npm run lint
结果：通过。

前端：npm run build
结果：通过；构建仍提示部分产物超过 500 kB。

PowerShell：语法解析；使用 Maven 替身验证启动工作目录、开发环境覆盖、
CSRF enforce 参数、正常退出与异常退出的环境恢复，以及 Redis 主机覆盖。
结果：通过。
```

真实 HTTP 验证（均未使用账号密码）：

| 场景 | 结果 |
| --- | --- |
| 经 Vite，localhost:5173 来源 + 正确请求头 + `{}` | 400，进入登录参数校验 |
| 经 Vite，127.0.0.1:5173 来源 + 正确请求头 + `{}` | 400，进入登录参数校验 |
| 直连后端，本地来源 + 正确请求头 + `{}` | 400，进入登录参数校验 |
| 缺失或错误客户端请求头 | 403 / `40303` |
| 未允许、缺失或 `null` 来源 | 403 / `40303` |
| 本地来源 CORS OPTIONS 预检 | 200，允许 Origin 及所需请求头 |
| 本地来源刷新会话、没有任何登录 Cookie | 401，进入未登录处理，没有 CSRF 错误 |
| Vite 返回的开发版 auth 模块 | 200，已包含新的中文提示 |

日志中已观察到 `ORIGIN_MISSING`、`ORIGIN_NOT_ALLOWED`、`CLIENT_HEADER_MISSING`、`CLIENT_HEADER_INVALID` 对应的拒绝记录。无 Cookie 的 refresh 分支不会设置 Cookie，不能据此验证已登录用户的 Cookie 属性。

### 8.3 仍需真实账号验证

没有读取或使用用户账号密码，未执行真实登录、浏览器 Cookie 检查、刷新页面维持会话及退出流程。这些项目应按第 6.2 节使用已有测试账号验证；不能将空 JSON 获得 400 等同于实际账号登录成功。
