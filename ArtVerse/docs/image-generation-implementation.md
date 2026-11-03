# 即时生图 MQ 状态机实施记录

更新：2026-10-26。用户已明确授权本轮开发。代码已接入即时生图入口；尚未在生产部署。原审核文件保留问题背景，本文记录实际交付和验证边界，不把设计验收矩阵等同于已通过的测试。

## 实际交付

- `application/imagequeue/` 实现不可变私有输入、准入合并、RabbitMQ 可靠发布、虚拟线程同步消费、Redis 单一执行 status、持久调用/结果证据、GET/SSE、逐张章节控制和恢复服务。没有新建数据库任务表、Outbox、数据库派发扫描器、Redisson 或上传队列。
- 原 `/api/image-gen/generate`、`/api/chapters/{id}/generate-manga-stream`、`/api/chapters/{id}/regenerate-image/{number}` 保留 URL，统一受理后返回 202 和 GET/SSE 地址；已知终态重放可返回 200。旧 POST SSE 客户端必须与本次前端一起升级。新独立漫画首次生成入口为 `/api/chapters/{id}/manga-images/{number}/generation-requests`。
- 登录态决定 userId。只接受有效 config_id 或完整临时 BYOK；缺供应商返回 HTTP 400、code=40062、data.error_code=PROVIDER_REQUIRED 和“无供应商，请选择生图供应商”。不按 model 猜供应商。漫画单图无 ID 时按用户/章节/图序/操作/图片基准稳定派生；普通独立生图和整章需要 Idempotency-Key 或 request_id，前端持久保留未确认提交的身份。
- 同 ID 异体冲突；同目标同意图的不同 UUID 合并到原 canonical，私有 alias 固定关联。短图片锁按章节+图序；快照写入和 Publisher Confirm 不持有该短锁。Redis target-active 不设置超时，Redis 丢失后准入核对持久未决调用并重建占用。
- 私有 `${imageBucket}-generation-private` bucket 保存 AES-GCM 加密 manifest（复用版本化密钥服务）、固定首次认领时间/截止时间和 alias；不通过图片代理公开。条件写 `If-None-Match:*` 禁止覆盖原输入。MQ 仅含身份和快照摘要，不带密钥或参考图。
- durable quorum 队列、持久消息、mandatory/Return、correlated Publisher Confirm；手工 ACK 位于监听执行线程。30/120/600 秒等待、5 秒章节控制和隔离转交均先确认后继投递，再 ACK 当前消息。Quorum 容量允许少量在途超限，测试要求最终 reject-publish 且保留旧消息，不错误要求严格只能容纳一条。
- 共享总额度/用户/配置额度默认 4/4/4；整章和所有重生成增加跨实例 SERIAL_MODEL=1。Redis TIME/ZSET 各作用域分别原子，固定顺序获取、逆序回滚，独立平台线程续租。持久 UNRESOLVED 调用在短数据库事务内再次核对，Redis TTL/回滚不能放行未知调用。
- `QUEUED → LOAD_CONTEXT → GENERATING → UPLOADING → COMMITTING → SUCCEEDED`；异常使用 RETRY_WAIT/FAILED/STALE/NEEDS_REVIEW。没有 phase、CLAIMED、ACKED。提交确认只更新传输元数据，迟到生产者不回写执行阶段。执行 owner、版本和数据库 execution_token 共同阻止旧执行者提交。
- 复用图片直接上传链路：完整有效图片响应后，原始字节内存流直接上传 MinIO，不产生输出临时 PNG。先登记候选 key/hash/token，再 PUT 并核对对象元数据和内容摘要；重投有候选则恢复提交。数据库业务成功后 Redis 重建成功；ACK 后通知失败不回退成功，GET 返回完整结果。
- 整章固定受理时的缺图清单，每次只发布当前一张，不覆盖已有图片。独立 MANGA_ADVANCE 控制消息先确认延迟后继再 ACK；图片消费者不承担发下一张。控制代次从根域 Redis 分配，恢复命令使用新代次；Redis 重建后以当前投递代次为下界。用户断线不影响控制链。
- `V51__image_generation_evidence_and_versions.sql` 增加 MangaImage 乐观版本和现有 image_gen_records 的有限许可/候选/结果/恢复证据。保留 ddl-auto: validate；旧行 request_id 仍为 NULL。图片 ID+十进制字符串 image_version 贯穿章节 DTO、结果和前端，重生成必须提交两者。删除重建时新 ID 防 ABA。预约共享存储先固定基准、上传后短事务核对版本；导入是新章节/新图片实体，自动初始化版本。
- 旧 12 分钟 RUNNING 推断排除 request_id 非空及 automation 记录。普通历史过滤漫画 MQ 证据，软删除不删除调用去重依据。
- SSE 使用请求级 Redis 频道、初始 snapshot、心跳和有界异步通知；慢浏览器不阻塞订阅线程。前端串行 GET 补偿丢事件，拒绝同 epoch 的旧版本；epoch 变化只以 GET 建立基线。恢复后同步图片和版本，NEEDS_REVIEW 禁止普通重生成入口。

## 恢复与人工操作

GET `/api/image-generation/requests/{id}` 和 `/events` 均校验用户归属/章节权限。单图项返回完整图片结果、恢复版本、允许动作、最近活动、待核实原因和安全摘要。状态来源为 redis、租约内重建后的 rebuild、Redis 故障时仅确定终态的 db；证据不足返回空状态和 STATUS_UNAVAILABLE。

`POST /api/image-generation/requests/{id}/operations/{operationId}/recovery-actions` 接受 action_id/action/expected_recovery_version：

| 动作 | 实际行为 |
| --- | --- |
| RECHECK | 独立可靠 IMAGE_RECOVER 消息，核对本地结果/候选；不调用生成接口 |
| REQUEST_REVIEW | 持久登记人工申请时间 |
| RESUME_SAFE | 仅有成功或候选依据时，可靠发布原操作；不创建新付费意图 |
| RELEASE_CONFIRMED | 仅持久 FINAL 依据允许条件释放 |

action_id 与规范输入绑定并保留审计；同动作重放补齐原提交，异体/旧版本返回 409。用户每分钟最多三次动作，RECHECK 冷却 60 秒、总核实窗口 180 秒，单操作最多 100 条用户动作。当前适配器没有供应商查询/取消能力，核实只做一次本地证据检查；需要外部核实则保持 NEEDS_REVIEW。恢复队列延迟/重投有界，不能转为生成调用。

管理面板已加入 GuardDashboard。`GET /api/admin/image-generation/reviews` 分页列出未决调用；`GET /api/admin/image-generation/requests/{id}/operations/{operationId}` 提供脱敏详情（供应商名称、模型、开始时间、调用代次、是否登记候选和能力边界），不返回密钥或原始响应。ADMIN 决定接口要求恢复版本、调用代次/token、脱敏来源和引用；有效候选禁止人工判无结果，应先安全恢复。UNKNOWN 保持占用；NOT_ACCEPTED 或 ENDED_WITHOUT_RESULT 必须明确确认外部结束，才递增 token、持久 FINAL、同步 Redis 并释放。表单内容是管理员基于供应商后台的人工结论，系统不声称自动验证其真实性。

已核实外部结束后，用户可通过 abandon-and-regenerate 明确接受新付费意图，绑定唯一替代 request_id；前端展示确认操作，未知调用不提供强制放弃。替代仍走相同准入/版本/串行限制。数据库成功和候选恢复不需要重新生成。

确定终态 Redis Hash 保留 30 天；整章未结束时保留前置图片状态，未决占用不按年龄自动清理。章节删除后，管理员仍可依据持久操作记录核实未决调用，防止删除章节使串行许可永久不可操作。私有输入、候选、历史证据和被引用图片保守保留，没有新增自动对象删除器。隔离队列也须保留，人工确认后可将原命令按原身份重投，禁止改成新 UUID 绕过许可。

## 时间预算与已知限制

单次图片投递 watchdog 为 15 分钟，控制/恢复投递 30 秒；到期中断执行并关闭未确认 channel，由 Broker 保留责任，不将中断当作供应商已结束。图片操作从首次认领固定 60 分钟，跨重投不刷新；调用前要求剩余预算容纳 600 秒供应商时间和 180 秒后处理储备。MinIO 单次操作有 45 秒网络上限，短数据库事务 lock_timeout=1s/statement_timeout=3s；基础设施完全不可用时仍须依赖客户端连接超时，入口 3 秒图片锁核实不是整个 HTTP 请求的硬上限。

当前实现不自动重试供应商生成。包括网络中断、响应不可用等，均保守进入待核实；没有得到“不受理/已结束”的供应商契约前不会使用最多三次的上限来再次收费。图片字节在上传前仍仅在内存，进程在该窗口退出且供应商不可查询时只能人工核实。

单节点 quorum 不等于高可用。预约仍属于独立调度/许可体系，不纳入即时 MQ 的共享模型额度；本次只改其共享图片写入版本兼容。生产环境未启用角色，也没有读取或修改现有数据库、服务卷、真实 .env 或线上凭据。

## 验证记录

验证日期：2026-10-26。所有 AI 客户端均为模拟替身，没有真实 AI 请求。

真实集成使用隔离 Testcontainers：PostgreSQL pgvector/pg16、Redis 7 AOF/noeviction、MinIO RELEASE.2025-04-08、RabbitMQ 4.1.8。Flyway 在空数据库执行至 V51，并通过 JPA schema validate。故障钩子在监听线程内中断并 abort 真实 AMQP channel；这证明指定窗口重投行为，不等于独立 JVM 的 OS kill 测试。

| 验证范围 | 对应设计场景及边界 |
| --- | --- |
| 稳定身份、异体冲突、不同 UUID 真并发合并、缺供应商/缺版本 | T04/T05/T07/T31 的已覆盖子集 |
| mandatory Return、Confirm 超时后原身份重试、容量拒绝、Broker 应用重启 | T01/T02/T03/T46；Confirm 超时由缩短等待注入，不是网络真实丢包 |
| TTL 死信目标尚无绑定，后续绑定后可靠转交 | T03/T24/T46 子集；真实 quorum/DLX，测试单独缩短内部发布重试间隔，生产默认不改 |
| 调用前、许可后、上传后、提交后中断；Redis flush；ACK 重投 | T09/T10/T13/T15/T16/T32 的窗口级验证 |
| 同目标占用重建、候选无收费恢复、旧 token 拒绝、恢复动作重放/版本 | T29/T38/T39 的已覆盖子集 |
| owner 条件释放、两组独立租约实例共享串行额度、阻塞期间独立续租 | T06/T28/T41；两实例在同 JVM，未声称多 JVM 负载验证 |
| 独立首次与串行未决可重叠、章节 ACK 后控制推进、整章只补缺图、重复控制 | T18–T29 的已覆盖子集 |
| 数据库版本、删除重建 ABA、历史超时过滤、共享预约回归 | T34/T40/T45 |
| 真实数据库中的重生成版本递增、预约写入抢先提交后即时候选变 STALE | T29/T34/T40，不重新请求模型 |
| 实际虚拟线程监听、缩短投递 deadline 后未知调用不重调、ACK 后通知异常 | T17/T42/T43 的短时/故障钩子子集 |

本次最终验证：11 个测试类共 **58 项**，其中 **32 项真实四组件集成测试 + 26 项相关回归**，失败/错误/跳过均为 0。最后一轮集成约 55 秒。包括普通独立生图旧 URL 的 MockMvc 202/GET/错误 envelope、登录用户身份补全及供应商前置校验；MockMvc 没有启动真实浏览器或完整认证过滤器，不宣称浏览器端到端验收。最新逐项报告在 `target/surefire-reports/`，详细运行输出在 `target/image-queue-validation.log`。

后端 `mvn -q -DskipTests compile`、前端 `npm run lint` / `npm run build`、两份 compose 模板的 `config --no-interpolate --quiet` 和 `git diff --check` 均通过。前端构建保留大于 500KB 的 chunk 提醒，无编译错误；没有为本次顺带重构分包。

构建命令：

```powershell
# 后端目录；镜像已在本机，测试禁止拉取，容器测试正常退出会清理
$env:TESTCONTAINERS_RYUK_DISABLED='true'
mvn -q '-Dtest=ImageQueueIntegrationTest,WebClientImage2ClientTest,MinioStorageServiceTest,MangaImageStorageServiceTest,ImageGenServiceTest,MangaGenerationServiceTest,ReferenceImageControllerTest,StaticMediaControllerTest,ScheduledMangaGenerationServiceTest,ScheduledMangaRetryPolicyTest,ScheduledGenerationRouteCapabilityTest' '-Ddocker.client.strategy=org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy' '-Dapi.version=1.44' test
mvn -q -DskipTests compile
# frontend 目录
npm run lint
npm run build
```

未验证：完整 T01–T47、独立多 JVM/OS kill、真实浏览器断网/重连的端到端操作、Redis Cluster KEYSLOT/集群故障与 AOF 回滚、RabbitMQ 满盘/整节点故障、600 秒长调用 JFR carrier pinning、16 分钟完整排空、生产密钥轮换和分期回退演练。静态检查或短时测试不替代这些场景。

## 迁移、启动与回退

1. 备份数据库及对象存储。盘点仍运行旧内存生图的实例和调用，暂停即时写入并等待已知调用结束；未知外部调用先核实。不要让旧版本与 MQ 版本同时写相同图片。
2. 为 RabbitMQ 建专用用户/vhost；Redis 配 AOF、noeviction 和密码；图片 bucket 与私有 generation-private bucket 必须保持不同访问策略。配置稳定的 ARTVERSE_SECRET_ENCRYPTION_KEY，旧快照仍依赖旧版本解密能力，不可随意替换并丢弃旧密钥。
3. 先以三个角色开关均 false 启动新版，Flyway 自动执行 V51，保持 ddl-auto: validate。升级所有共享图片写入口与前端，排除仍在运行的旧即时生图实例。预约保持原有调度配置。
4. 配置 application.yml 对应的数据库、Redis、MinIO、Rabbit 连接。应用在容器网络时使用服务名，宿主机运行时用映射地址。应用退出宽限至少 17 分钟，Spring drain 16 分钟；不要把 Broker 自身的 stop_grace_period 当成应用的退出宽限。
5. 先启 ARTVERSE_IMAGE_WORKER_ENABLED=true，再启 ARTVERSE_IMAGE_COORDINATOR_ENABLED=true；观察持久队列/隔离队列/未决记录，最后启 ARTVERSE_IMAGE_PRODUCER_ENABLED=true。API 和 Worker 可分进程或同进程，但角色配置必须明确；本次没有自动部署应用。
6. 开发基础设施模板为 docker-compose.yml，生产内部网络模板为 deploy/compose.production.yml，凭据样例为 deploy/.env.example（均为空值）。后者仅含基础组件，无应用容器；需要应用接入同网络，不能直接把它当成完整生产发布包。不要同时启动会冲突的同名容器，也不要通过删卷来套用新配置。
7. 已存在卷中的 PostgreSQL/RabbitMQ 密码不会因 DEFAULT 环境变量变化自动轮换。须使用各服务自己的凭据变更机制、同步应用连接配置并验证；Redis/MinIO 同样按服务流程处理。本文不自动读取或修改真实凭据。
8. 正常发布先关 producer、等待已确认命令和控制链排空，保持 Worker/coordinator 和租约续期存活。紧急停止后由原消息/证据恢复，未知保持 SAFE。回退时保留兼容新版 Worker/coordinator 和 V51，继续处理已受理命令；暂停普通入口，不能直接回滚到会忽略版本/许可的旧写代码。没有自动删队列、降级为内存执行或回滚数据库迁移。
