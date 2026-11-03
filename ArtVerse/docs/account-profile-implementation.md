# 账号菜单与个人资料

沿用项目暖白、森林绿、鼠尾草绿与陶土色主题。侧栏账号入口默认显示头像、用户名和邮箱，收起侧栏时保留头像。桌面向上弹出账号面板，移动端从顶部头像向下展开，包含个人资料、API 设置、浅色/深色/跟随系统与退出登录。面板通过 Portal 避免裁切，支持外部点击、Esc、方向键及焦点返回。

个人资料路由为 `#/profile`，使用登录账号的真实数据，提供头像草稿/保存/移除、用户名修改、只读邮箱和修改密码。资料保存后立即同步账号入口。离开、后退和关闭页面会保护未保存草稿；请求失败保留输入；错误提示自动进入可见区域。

## 接口与数据

| 接口 | 行为 |
| --- | --- |
| `GET /api/user/me` | 返回 `id/username/email/avatar_url` |
| `PUT /api/user/me` | 修改用户名，2–50 字符，保持唯一 |
| `POST /api/user/me/avatar` | multipart `image` 上传头像 |
| `DELETE /api/user/me/avatar` | 移除当前用户头像 |
| `GET /api/user/me/avatar` | 当前用户头像二进制，禁止缓存 |
| `POST /api/user/me/password` | `current_password/new_password/confirm_password`；成功 204 |

新增 Flyway `V54__user_profile_avatar.sql`，只给用户表增加可空的头像存储元数据。启动后端时自动迁移，保留 `ddl-auto: validate`。头像通过已有对象存储写入随机版本路径；事务提交后清理旧对象，回滚时清理新对象。旧文件删除采用现有 best-effort 机制，存储故障时可能留下待清理文件。

静态 JPG/PNG/WebP 原图最大 5MB，浏览器居中裁切、压缩为不超过 20KiB 的 JPEG；GIF 不超过 20KiB、512×512，保留动画。服务端独立校验实际 JPEG/PNG/GIF 内容、尺寸及帧数。头像只可通过登录用户的专用接口读取。

密码按 8–16 个 Unicode 码点验证，复用已有哈希服务；校验当前密码，拒绝相同新密码和不一致的确认密码。当前密码错误返回 400，避免误触发登录过期逻辑。成功后撤销该账号全部刷新令牌与登录会话，清理刷新 Cookie，并引导重新登录。新接口继续使用现有鉴权、CSRF 和按用户限流。

## 验证记录（2026-11-01）

- 后端 `mvn -q -DskipTests compile` 通过。
- `UserProfileServiceTest`、`UserProfileContractTest`：17 项通过，覆盖名称冲突、密码验证、头像内容/尺寸校验、提交与回滚清理、JSON 与二进制接口契约。
- `UserProfilePersistenceIntegrationTest`：1 项通过，使用本地 PostgreSQL 临时账号验证迁移、资料持久化、密码失败回滚与成功哈希；测试后清理账号。
- `UserProfileHttpIntegrationTest`：1 项通过，对运行中的 8080 服务、Redis、MinIO 验证真实登录、CSRF、用户名保存、头像上传读取删除、错误密码保持登录、新密码生效、两台设备登录失效及旧刷新令牌失效。使用临时账号并清理。
- HTTP 测试默认不执行；本地后端及依赖启动后使用 `mvn -q -Dtest=UserProfileHttpIntegrationTest -Dartverse.profile.http-smoke=true test` 执行。
- 前端 `npm run lint`、`npm run build` 通过；构建仍有现有大包体积提示。现有 22 项 Node 测试通过。
- 隔离浏览器会话使用测试接口响应验证 15 项交互：菜单默认关闭、主题跟随系统、Esc/焦点、草稿保护、保存失败保留输入、用户名同步、头像本地草稿与压缩、保存/删除、390px 移动端布局、密码确认及重新登录。另在实际账号页面核对展示和新后端 `/me` 返回值。未修改实际账号资料或密码。

本地 8080 后端已重启加载上述代码。其他环境部署时需同时更新前后端并执行 V54 迁移，无新增环境变量。
