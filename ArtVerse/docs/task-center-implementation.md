# 定时任务中心实施记录

依据：[设计方案](task-center-design.md)。目标：完整实现统一 Agent／生图定时任务中心，并使用规定账号回归。

## 实现范围

2026-10-25，按现有 Agent 路线实现统一任务中心。工作区原有未提交改动全部保留；未提交或推送 Git。

- `V50__automation_task_center.sql`：计划、运行、事件、执行关联、通知、响应幂等表；旧章节预约回填及关联字段。已在本地 PostgreSQL 应用，JPA schema validate 通过，后续调整须使用新迁移。
- `application/automation/`：ONCE／DAILY／WEEKLY、IANA 时区及 DST、服务端预览、离线合并／跳过、重叠跳过、版本校验、幂等、暂停／恢复／归档和立即执行。
- 数据库扫描与虚拟线程 Worker：90 秒租约、30 秒续租、fencing、持久队列恢复、取消／时限控制、人工响应与失败重试；WAITING_USER 释放线程及租约。
- 小说和漫画 Agent 复用现有后台入口、运行记录与产物；每次定时运行隔离会话，草稿写入仍需原有产物确认。前台断开不会取消后台任务。
- 章节生图复用旧图片 Worker；独立生图直接调用已有图片与存储服务。持久化派发标记、参考图、容量许可、章节互斥、明确失败重试与 UNCERTAIN 人工核对均已接入。
- 旧预约 API 通过桥接登记统一计划，旧 Worker 受统一运行派发资格限制。
- 对话工具 `create_automation`／`list_automations`／`update_automation` 限当前作品；后台 Agent 在服务端禁止创建或管理其他计划。返回持久任务链接。
- 前端任务入口、列表／搜索／筛选、创建／编辑、时间预览、历史运行、草稿与逐图结果、待确认处理、站内通知及手机布局；章节与独立生图入口预填任务配置。

## 验证方式与证据

后端执行 `mvn -q -DskipTests compile`。共 62 项针对性测试通过，0 失败、0 错误（分批执行，均无真实生图调用）：

| 测试套件 | 项数 | 主要验证 |
| --- | ---: | --- |
| AutomationScheduleCalculatorTest | 8 | 上海规则、周规则、DST gap／overlap、预览 |
| AutomationPersistenceIntegrationTest | 9 | 实际 PostgreSQL 并发扫描、合并错过周期、幂等、租约接管／旧 fencing 拒绝、版本／归属、WAITING 重叠跳过、Worker 完成／关闭、通知已读 |
| AutomationAgentToolsTest | 2 | 后台管理拒绝、当前作品范围 |
| AgentScopeAgentFactoryTest | 1 | 构建前注册工具组、仅授权已注册业务工具且保持默认权限模式 |
| MangaHitlToolsTest | 1 | 外部 ask_user 事件转为业务等待、规范选项与审计 |
| AgentRedisSerializationTest | 1 | 含时间与 UUID 的状态在 Redis 序列化往返，保留类型元数据 |
| AgentToolAuditServiceTest | 2 | 空工具结果审计哈希、运行请求归属 |
| StoryChatBackgroundResumeTest | 4 | 普通回复不授权写入、空回复拒绝、从问题读取草稿标识、明确确认只授权指定产物 |
| 原预约／Agent／工具组相关测试 | 34 | 既有服务、重试分类、Agent 运行和预约路由兼容 |

数据库套件显式启用：`$env:ARTVERSE_AUTOMATION_DB_TEST='true'`，连接本地开发库 `manga_novel`；通过临时虚构用户隔离数据，测试后删除。运行前停止应用 Worker，避免后台扫描与测试同时认领数据。默认普通单测不要求本机 PostgreSQL。

前端执行 `npm run lint`、`npm run build`。构建有大于 500 kB 的打包体积提示，不影响成功产物。

## 账号与页面回归

使用仓库规定账号，密码仅用于运行时登录，未保存到源码、测试或文档。

- 通过实际页面创建工作日 09:00 章节检查任务，验证编辑、暂停和立即执行；后台小说 Agent 完成只读检查，结果与通知可刷新重开。
- 在临时作品内验证 ONCE 到点自动触发，区分原定时间与实际开始时间。
- 临时作品内的到点运行经历失败重试、WAITING_USER、人工明确确认后 SUCCEEDED；全过程复用同一运行与同一子 Agent 记录。确认前原文不变，确认后临时章 v1 → v2，仅生成一份修订，内容与指定 COMMITTED 草稿完全一致，其他草稿被标记 SUPERSEDED；ONCE 计划自动 COMPLETED。
- 已通过页面归档任务，校验通知已读持久化；用户原有章节版本和正文与回归前快照一致。确认结果截图：`output/playwright/task-center-confirmed.png`。
- 调试修复耗时超过测试计划原定 600 秒上限，因此仅把该临时运行快照的时限延长至 7200 秒以完成确认回归；未改变产品时限规则，此回归不作为 600 秒超时行为的证明。
- 回归结束后按已知 ID 与用户归属清理 4 个计划、6 次运行及对应隔离会话、临时作品／章节、用量与工作区数据；数据库查询确认上述记录全部为零，并清理对应 Redis 键。没有留下测试周期计划。
- 390 × 844 手机端检查任务详情，无水平溢出；截图位于工作区 `output/playwright/task-center-mobile.png`。
- 回归发现并修复 AgentScope 在构建时捕获初始工具组的问题，以及默认框架权限确认未被产品处理的问题。业务工具在构建前注册，并配置工具名称级授权；章节归属、预算和产物确认仍由业务服务校验。
- 修复 ask_user 作为外部工具时没有进入业务等待、Redis 时间类型序列化失败、空工具结果审计异常。未处理的外部工具或框架权限事件会明确失败，避免误报成功。
- 普通“提交回复”仅补充说明；“确认写入这份草稿”发送明确产物 ID 后才授权写入，放弃草稿单独处理。选项支持结构化返回；创建弹窗初始焦点与 Escape 返回触发按钮已实测，长运行摘要不会挤压状态标签。

## 部署与边界

- `ARTVERSE_AUTOMATIONS_ENABLED` 默认 `true`；`ARTVERSE_AUTOMATIONS_POLL_INTERVAL_MS` 默认 `5000`；`ARTVERSE_AUTOMATIONS_MAX_ACTIVE_PLANS_PER_USER` 默认 `10`。应用需持续运行，到点触发发生在服务端。
- 多实例共享 PostgreSQL；Agent 状态仍依赖项目现有 Redis。参考图与产物使用现有对象存储，模型凭据始终从用户已保存配置解析。
- 迁移旧预约时应暂停调度并备份数据库；回滚必须保留旧 Worker 的统一计划门禁，不能直接使用不认识新关联字段的旧二进制。
- 按用户要求，未执行真实章节生图或独立生图请求。图片供应商端到端行为、真实超时／迟到结果和费用情况未实测。
- 尚未完成生产多进程崩溃注入、旧预约完整状态矩阵的双次回填演练、生产负载与长时间离线演练。现有数据库测试证明对应事务／fencing／幂等行为，不等同于全部生产故障场景通过。
- 自定义 Cron、月规则、外部通知、金额预算和专用消息队列属于后续扩展。
