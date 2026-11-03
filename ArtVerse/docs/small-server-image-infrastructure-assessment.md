# 2 核 2G 服务器：生图队列与对象存储评估

日期：2026-11-03。依据：用户确认服务器为阿里云 2 核 2G；当前工作区源码与官方文档。实例类型（ECS 或轻量应用服务器）、地域、每日生图量、图片大小、实际内存占用和线上未完成任务数量尚未提供；下文以内网可连通 OSS 的同地域 ECS 为例，具体连接条件需按实际实例确认。本文件是评估和建议，不表示下面的改造已经实现或部署。

## 结论

建议目标部署为：**一个 Spring Boot 应用 + PostgreSQL + Redis + 同地域 OSS**。即时生图采用 PostgreSQL 持久任务与应用内有界执行器，省去本机 RabbitMQ 和 MinIO。前端构建成静态文件，由现有 Web 服务提供。

- **MinIO 换 OSS：建议优先做。** 可以省去本机存储服务的常驻资源、图片磁盘增长和部分存储运维；需要适配代码和迁移数据。
- **RabbitMQ：现阶段不建议在这台机器常驻。** 当前规模尚无明确需要独立 Broker 的证据；已有数据库可以承接低吞吐、长耗时任务的持久排队。代价是需要实现并验证数据库派发与恢复逻辑。
- **可靠异步生图仍然需要。** 去掉 RabbitMQ 不等于改成同步 HTTP、裸线程池或内存队列；必须保留已受理任务持久化、防重复调用、候选结果恢复和待核实状态。
- **先从所有生图入口合计并发 1 起步。** 容器数量不是容量指标，进程总内存、图片解码峰值、数据库和磁盘压力才是。没有实测就不能保证这个部署一定能在 2G 下稳定运行。

## 当前项目事实

| 位置 | 已有行为 | 对选型的影响 |
| --- | --- | --- |
| `docker-compose.yml`、`deploy/compose.production.yml` | PostgreSQL、Redis、MinIO、RabbitMQ 四个基础组件；生产模板不含应用 | 四个基础容器还不代表完整上线资源需求 |
| `application/imagequeue/ImageMessagingConfig.java` | 持久 quorum 队列、手动 ACK、预取 1、延迟/恢复/章节推进/隔离队列 | RabbitMQ 承担的是已实现的可靠传输，需要替代其职责 |
| `application/imagequeue/ImageRequestSubmissionService.java` | 入口依赖生产者开关；受理后发布命令 | 关闭三个角色开关不会切换到其他执行方案，会暂停即时生图受理 |
| `application/imagequeue/ImageQueueProperties.java` | 即时模型总额度默认 4，并行消费者默认 4 | 需要调整；单改这两个值不会约束预约和定时生图 |
| `application/automation/AutomationStore.java` | 已有 `FOR UPDATE SKIP LOCKED`、租约、fencing token 和延迟重试字段 | 可以复用设计经验，不表示即时生图已经可以直接使用这个执行器 |
| `application/automation/AutomationWorker.java` | 调度工作容量目前在代码中设为 4 | 全站生图限流不能只靠即时队列配置 |
| `storage/ObjectStorageService.java` | 普通图片已有存储接口 | 可添加 OSS 实现，但目前不覆盖所有持久快照操作 |
| `application/imagequeue/ImageGenerationObjects.java` | 直接创建 MinIO 客户端，保存加密快照、别名、首次开始时间及候选图片；使用条件 PUT 与元数据核验 | OSS 改造必须覆盖此类，不能只替换普通图片存储 |
| `domain/StorageProvider.java`、Flyway V2、`ImageGenerationEvidence.java`、`MangaImageStorageService.java` | 枚举/数据库约束仅接受 LOCAL/MINIO，写入点也硬编码 MINIO | 需新增迁移与存储位置适配，不能只换 endpoint |
| `api/StaticMediaController.java` | `/static/manga/**` 从本地或配置的默认 bucket 读取，然后经应用代理返回 | 只换 OSS 后，图片读取仍经过 ECS；已有 URL 可在首期保留 |
| `ai/WebClientImage2Client.java`、`ai/GeneratedImage.java` | 外部生图 API；响应聚合上限 64 MiB，最多 4000 万像素，完整解码校验，图片字节保留在内存 | 云存储不会消除应用的响应缓冲和图片解码开销 |

`docs/image-generation-implementation.md` 记录了原实现的验证范围和当时尚未生产部署的状态；本次没有核实当前线上状态，也没有复跑其历史测试。

## RabbitMQ 的取舍

| 方案 | 额外本机服务 | 收益 | 代价与判断 |
| --- | --- | --- | --- |
| 保留现有 RabbitMQ | 一个 Broker | 现有可靠投递和恢复链路改动较少 | 占用内存、磁盘与运维精力；单节点 quorum 不提供跨主机高可用。适合作为迁移过渡，前提是实测资源允许 |
| PostgreSQL 持久任务 | 无 | 任务登记、幂等约束和业务提交可在同一数据库协调；已有类似租约实现 | 需增加任务派发、到期扫描、恢复及清理；**本阶段建议目标** |
| Redis Streams | 无新增服务 | 复用现有 Redis，支持消费组与待确认消息 | 仍需处理待确认接管、延迟重试、持久化丢失窗口及与 DB 的一致性；不因“已有 Redis”就默认最省开发成本 |
| 应用内队列/线程池 | 无 | 改动表面上较小 | 若作为唯一任务来源，重启会丢未执行任务，不适合已受理的付费生图 |

RabbitMQ 的价值会在独立扩缩容的多个 Worker、明显消息积压、多系统事件消费或复杂路由需求出现后增加。触发点应是实际负载和隔离需求，不是某个未经测量的日请求数。当前官方生产指南建议每节点 4 核、4 GiB RAM，并避免与其他数据服务共置；也明确低负载环境可以使用更低规格。这是生产资源建议，不是启动硬门槛，也不是当前拓扑的占用实测。[R1]

### 数据库任务替代必须保留的行为

1. **持久受理。** 在数据库中持久登记稳定 request_id、operation_id、输入摘要及私有快照定位，事务提交后才返回 202；相同身份异体冲突，相同身份重试可查询原任务。输入快照先安全写入 OSS，再提交引用；不能先返回成功再异步补任务。未被引用的快照允许后续保守清理。
2. **有界认领。** 按 next_attempt_at 和稳定排序，在短事务里用 `FOR UPDATE SKIP LOCKED` 认领，设置 owner、lease_until、execution_token 后提交；调用供应商和读写 OSS 时不持有事务或连接。活跃状态及到期字段建立合适索引，按可用容量取少量任务。PostgreSQL 官方明确提及 SKIP LOCKED 可用于多个消费者访问类似队列的表。[R2]
3. **区分执行权与付费调用。** 供应商调用前持久登记许可/未决证据。租约过期只允许接管核实，不代表可以重新调用供应商；网络超时、进程退出、响应丢失后缺少确定证据时保持 NEEDS_REVIEW 和必要的目标占用。
4. **恢复已有结果。** OSS 候选 key、SHA-256、operation_id、execution_token 和内容类型保持可核验；上传成功但数据库未提交时恢复提交，禁止再次生图。旧 token 无权覆盖新结果，保留图片 ID/版本校验。
5. **章节可靠推进。** 当前图片提交成功和登记下一张待执行任务在同一数据库事务完成，使用唯一约束防重复；失败或待核实时停在当前图片。重启扫描的是数据库任务，不能依赖一次进程内回调。
6. **一个业务状态来源。** 目标方案以 DB 任务/操作状态为权威；Redis 用于缓存和事件通知，SSE 丢失通过 GET 补偿。迁移时需要显式替换当前 Redis 执行状态逻辑，不能再叠加一个互相竞争的状态机。
7. **总额度跨入口生效。** 即时单图、整章、重生成、预约、自动任务都使用共同的生图容量入口，并保留用户/供应商限制。未决付费调用的限制不随普通租约超时解除。应用内执行器只提供有界执行能力，数据库持久证据承担恢复职责。

建议新增专用图片任务表及所需唯一约束/索引，复用现有证据和业务服务。不要未经语义核对就把所有即时请求塞进 `automation_run`；即时与定时的取消、恢复、身份和前端接口契约不同。这个改造属于中等范围的后端工作，不是替换一个配置项。

## MinIO 换 OSS 的实现范围

建议采用 OSS 原生 Java SDK，新增可切换的存储实现，并为私有快照、原子“不存在才写”、自定义元数据、HEAD/内容核验提供统一能力。OSS 官方列出了兼容的 S3 API 及请求风格、ACL、ETag 等差异；据此，本评估不建议把 MinIO SDK 直接换 endpoint 作为未经验证的正式方案。[R3]

尤其要验证原子禁止覆盖：当前代码发送 `If-None-Match: *` 并识别 `PreconditionFailed`；OSS 原生 PutObject 文档提供 `x-oss-forbid-overwrite: true`，同名对象存在返回 `409 ObjectAlreadyExists`。文档说明版本控制开启或暂停时该头不生效，所以用于不可变快照的 bucket 必须选择适用配置，或改用经验证的其他原子机制。不能用“先 HEAD、再普通 PUT”替代，否则并发下会覆盖。[R4]

实施范围：

- 为普通图片和 generation-private 私有快照分别配置 bucket，后者保持私有，不能暴露到图片代理或公开 URL。保留 AES-GCM 快照和现有版本化解密密钥。
- 用新增 Flyway 迁移支持 OSS 枚举及数据库约束；处理所有硬编码 MINIO 的结果写入点。保留对象 key，明确历史 bucket/provider 的映射，核对静态代理当前仅使用默认 bucket 的行为。
- 迁移不只包含最终图片，还包括参考图、封面、素材及未完成任务引用的 manifest、alias、start 和候选结果；核对数据库引用和存储对象清单。
- 核对对象元数据及内容摘要；不能假设不同服务的 ETag 都等于文件 MD5。禁止覆盖返回、对象不存在、权限失败与超时需要分别处理。
- 首期保留 `/static/manga/**` 代理路径以减少接口变更。之后需要降低 ECS 图片出口压力时，再适配浏览器可访问的短期签名 URL 或 CDN；后端内网 endpoint 不能直接给公网浏览器使用。OSS 当前文档提示默认访问域名下图片可能强制下载，直出图片时应配置适合在线预览的自定义域名，并验证浏览器展示；签名 URL 使用对应的公网/自定义域名正确生成。[R5]

### 地域、费用与访问路径

OSS bucket 应和 ECS 同地域，并使用对应内网 endpoint。阿里云文档说明同地域 ECS 与 OSS 的内网传输不计流量费；存储和请求次数等仍按规则计费。浏览器从 OSS 公网读图会产生相应公网流出费用；通过 ECS 代理则仍占 ECS 出口带宽，并受 ECS 自身计费方式影响。[R5]

预算按“平均存储容量 × 存储单价 + API 请求次数 × 请求单价 + 公网流出量 × 流量单价 + 可选 CDN/图片处理”计算。生成张数不等于访问量，还要计入参考图、私有快照、候选核验 GET 和保留期限。地域、用量和访问方式未知，本次不承诺月费或购买资源包的收益。

## 2G 内存下还要处理什么

- 生图当前实际走外部 API；本机主要承受响应、Base64、引用快照、解码校验和上传。一个 4000 万像素的 4 字节/像素缓冲约 153 MiB，仅是示例缓冲大小，不代表每张图或完整请求的实际占用；其他缓冲可同时存在。
- 把所有入口总生图并发限制为 1，再根据实测增大；同时收紧与实际生成尺寸匹配的像素/响应限制，评估 Agent 并发、导入解压和缩略图处理是否叠加。
- 应用堆上限不是进程内存上限，还需容纳 JVM 原生内存、线程、直接缓冲、PostgreSQL、Redis、操作系统和文件缓存。虚拟线程不会压缩图片字节或解码缓冲。
- 检查 Redis 最大内存和持久化期间的峰值、数据库连接数与维护开销。当前 Redis 还用于并发控制、幂等及事件传递，不能把去掉 RabbitMQ 理解为可以一并删除 Redis。
- 以服务器实测为准：记录空闲、普通生图、最大允许图片、参考图、预约与即时同时触发时的 RSS/容器内存、系统可用内存、OOM、交换、CPU 与响应时间。当前没有这些数据，不能给出可靠的“能带多少用户”。

## 建议实施顺序与完成条件

1. **先确定资源基线与总并发方案。** 获取 ECS 地域、服务 RSS、存量对象和未决任务；将全入口共同容量 1 的方案实现并验证。只评估，不在未知线上状态下直接停组件。
2. **先迁存储。** 完成 OSS 普通图片和私有证据适配，使用模拟供应商与隔离 OSS 测试 bucket 验证：并发禁止覆盖、元数据、超时后核验、签名 URL、历史图片读取和已有候选恢复。按是否有存量数据选择迁移流程；暂停写入并处理在途任务后完成最终复制和切换，保留可回退副本。
3. **再换队列。** 实现数据库任务派发、跨入口总额度、重启核实与章节推进，复用已有付费证据和版本控制。保留 202/GET/SSE/恢复动作契约，或明确安排前后端同时升级。
4. **验证后停 RabbitMQ。** 旧入口暂停受理，等待已知调用排空，持久映射所有未完成的命令、延迟任务、隔离消息、章节进度和未决调用。新旧执行器不能同时处理同一操作；不能删除队列来完成迁移。
5. **验证后停 MinIO。** 仅在没有旧存储引用且新存储恢复路径验证后停服务；历史副本的清理另行安排。回退必须考虑切换后新增写入，不能只把配置改回旧存储。

必要验收包括：同 ID 重投不重复收费；认领后/调用后/上传后/提交后退出进程的恢复；旧执行者迟到提交被拒绝；未知付费调用不自动重试；章节推进事务失败恢复；即时与预约竞争时总额度不超过 1；对象存储不可用时已受理任务仍可追踪。

如果当前优先级是尽快上线，保留已验证 RabbitMQ 链路的开发风险可能更低：先完成 OSS 与容量收紧，再实测 RabbitMQ 是否能暂时保留。若完整部署仍超出 2G，选择完成数据库队列迁移或升级内存；不要用删除恢复逻辑来凑容量。就本次“小服务器、减少常驻组件”的目标，长期优先选择数据库任务与 OSS。

## 参考与验证边界

- [R1] RabbitMQ Production Checklist：`https://www.rabbitmq.com/docs/production-checklist`；Quorum Queues：`https://www.rabbitmq.com/docs/quorum-queues`。
- [R2] PostgreSQL 16 SELECT / SKIP LOCKED：`https://www.postgresql.org/docs/16/sql-select.html`。
- [R3] OSS 兼容 S3 说明：`https://help.aliyun.com/zh/oss/developer-reference/compatibility-with-amazon-s3`。
- [R4] OSS PutObject 条件与禁止覆盖：`https://help.aliyun.com/zh/oss/developer-reference/putobject`。
- [R5] OSS Endpoint 与访问方式：`https://help.aliyun.com/zh/oss/user-guide/regions-and-endpoints`、`https://help.aliyun.com/zh/oss/user-guide/access-oss-via-bucket-domain-name`；流量费用：`https://help.aliyun.com/zh/oss/traffic-fees`。

本次仅静态核对源码、现有实施记录和官方文档，产出选型评估；未修改业务实现或部署配置，未运行真实生图、云资源购买、线上迁移、容量压测或历史集成测试。
