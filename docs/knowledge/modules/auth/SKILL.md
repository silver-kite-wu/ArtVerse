# Auth Module Skill

Sa-Token 认证模块：登录、注册、令牌管理、限流、角色鉴权。

## Architecture

```
frontend (httpOnly cookie)
  └─ POST /api/auth/login|register  → AuthController → AuthService → UserRepository (PostgreSQL)
  └─ POST /api/auth/logout|refresh  → AuthController (StpUtil)
  └─ POST /api/auth/kickout          → AuthController (admin role check)
  └─ GET  /api/auth/me               → AuthController

SaTokenConfig (Java)   → interceptor allowlist: /api/auth/**, /api/square/**, /static/**, /actuator/health
                        → remaining /api/** requires login

StpInterfaceImpl        → provides role list from User.role (DB-backed)
RateLimitAspect         → Redis Lua sliding window, per-IP or per-userId
```

## Token Lifecycle

| Phase | Detail |
|-------|--------|
| Login | `POST /api/auth/login` → `StpUtil.login(userId, "PC")` → cookie `satoken` set (`httpOnly`, `SameSite=Lax`) |
| Auto-renew | `active-timeout: 1800` (30 min) — any request within window auto-extends |
| Hard expiry | `timeout: 43200` (12 h) — absolute max token lifetime |
| Manual refresh | `POST /api/auth/refresh` → `StpUtil.renewTimeout(43200)` — extends by 12h |
| Logout | `POST /api/auth/logout` → `StpUtil.logout()` — destroys current token only |
| Kickout | `POST /api/auth/kickout?userId=X` → `StpUtil.kickout(userId)` — admin only, destroys all tokens for user |
| Multi-device | `is-concurrent: true`, `is-share: false` — each device gets independent token |

### Important semantics

- `timeout` is the hard ceiling for the current access token session. It is 12 hours.
- `active-timeout` is the sliding idle window. Any authenticated request within 30 minutes extends the session automatically.
- `/api/auth/refresh` is a compatibility and explicit renewal endpoint. It does not change the hard ceiling; it refreshes the current access token state up to the configured 12-hour limit.
- Refresh tokens are separate from access tokens. They are rotated on use and revoked on logout, kickout, or reuse detection.

## Cookie Strategy

Token delivery uses **httpOnly cookie** as primary channel (XSS-safe), with header fallback for transition:

```yaml
sa-token:
  is-read-cookie: true    # read token from cookie
  is-write-cookie: true   # write token to Set-Cookie on login
  is-read-header: true    # also read from satoken header (deprecated, transitional)
  is-write-header: false  # do NOT write token to response header
  cookie:
    http-only: true       # JS cannot read cookie
    same-site: lax        # CSRF protection
    secure: false         # set true for HTTPS-only deployments
```

Frontend:
- `credentials: 'same-origin'` on all fetch calls (sends cookie automatically)
- Token never stored in `localStorage`
- `isAuthenticated()` checks `getUser()` (from `LS_USER`) not token existence
- `authFetch()` auto-calls `/api/auth/refresh` on 401

## Rate Limiting

`@RateLimit` annotation on controller methods, backed by Redis Lua sliding window.

| Endpoint | Window | Max | Key |
|----------|--------|-----|-----|
| `/api/auth/register` | 60s | 5 | `register` |
| `/api/auth/login` | 60s | 10 | `login` |
| `/api/auth/logout` | 60s | 30 | `logout` |
| `/api/auth/refresh` | 60s | 20 | `refresh` |
| `/api/auth/kickout` | 60s | 10 | `kickout` |
| `/api/auth/me` | 60s | 60 | `me` |

Key resolution: `userId` for authenticated users, `IP` (from `X-Forwarded-For` or `RemoteAddr`) for unauthenticated.

## Role Model

| Role | Permissions |
|------|-------------|
| `USER` | Default for all new registrations |
| `ADMIN` | Can kick out any user via `/api/auth/kickout` |

Roles stored in `users.role` column (VARCHAR(20), default `USER`).
`StpInterfaceImpl.getRoleList()` reads from DB on each permission check.

## Password Policy

Enforced in `AuthService.validatePassword()`:
- Minimum 8 characters, maximum 128
- At least 2 of: letters, digits, special characters
- Rejects blank/null passwords

Hashing: BCrypt strength 10 (`BCryptPasswordEncoder`).

## Key Files

| File | Role |
|------|------|
| `api/AuthController.java` | REST endpoints (login, register, logout, refresh, kickout, me) |
| `api/dto/AuthDtos.java` | Request/response DTOs with validation annotations |
| `application/AuthService.java` | Registration + login business logic, password validation |
| `config/SaTokenConfig.java` | Sa-Token interceptor, password encoder, RedisTemplate |
| `config/StpInterfaceImpl.java` | Role permission provider for Sa-Token |
| `config/BCryptPasswordEncoder.java` | BCrypt hashing (jBCrypt, no Spring Security dep) |
| `common/aspect/RateLimitAspect.java` | Redis sliding window rate limiter |
| `common/GlobalExceptionHandler.java` | 401/403/429/400 error mapping |
| `domain/User.java` | JPA entity with `role` field |
| `domain/Role.java` | USER/ADMIN enum |
| `persistence/UserRepository.java` | User lookup by username/email |

## Tests

| Test | Coverage |
|------|----------|
| `AuthServiceTest` | Registration validation, login success/failure, password policy, duplicate detection |
| `AuthControllerTest` | Kickout auth (401/403/admin), refresh lifecycle, /me auth |
| `BCryptPasswordEncoderTest` | Encode/match correctness, null safety, salt uniqueness |
| `RateLimitAspectTest` | Allow/block thresholds, disabled mode, IP/userId key resolution |

Run: `mvn test -pl .`

## Configuration Reference

`application.yml` under `sa-token:`:
- `timeout`: hard token expiry in seconds (43200 = 12h)
- `active-timeout`: auto-renew threshold (1800 = 30min)
- `is-concurrent`: allow multi-device login (true)
- `is-share`: share token across devices (false)
- `token-style`: uuid format
- `cookie.http-only`: block JS access (true)
