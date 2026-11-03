# 生图 MQ/Redis 状态机设计审核意见（H/M/L 全量清单）

> 2026-10-26 实施状态：本文件保留原审核问题背景。用户后续明确授权开发；最新决策见审核修订与主方案，实际代码和验证边界见 [实施记录](image-generation-implementation.md)。下面的“代码基线”均指审核时原稿，不代表实施后的现状。

审核对象（2026-10-26 版原稿）：

- [image-generation-rabbitmq-development-plan.md](image-generation-rabbitmq-development-plan.md)（总体方案，下称"文档一"）
- [single-manga-image-request-lifecycle.md](single-manga-image-request-lifecycle.md)（单图生命周期细化，下称"文档二"）

说明：位置引用以节号为准（行号对应审核时原稿，文档修订后可能漂移）。已核实两份文档的代码基线描述与实际代码一致：IdempotencyService 固定锁值无 owner、MangaGenerationService activeJobs 内存管理、WebClientImage2Client PrematureClose 重试、MangaImageStorageService 随机对象名、ImageGenRecordRepository 12 分钟 RUNNING 推断、docker-compose 无 RabbitMQ/未启用 AOF、MangaImage 无 @Version、Flyway 最大 V50、uq_manga_images_chapter_number 唯一约束存在。

总体结论：核心语义设计正确且自洽（Publisher Confirm / 业务认领 / Consumer ACK 三边界区分、target-active 业务 single-flight、调用许可短事务、SAFE 防重复收费），两份文档交叉引用无矛盾。以下为问题清单与修改意见。

## 一、高优先级（实施前必须解决）

### H1. 部署资源与安全基线缺失

- 资源：项目已知约束为 2G 云服务器（+2G swap），方案要求 Redis `appendonly yes` + `noeviction` + 状态保留 30 天 + RabbitMQ quorum 队列，叠加 Erlang VM（常驻约 200–400MB）、quorum raft 日志开销、Redis AOF 内存增长，无任何内存预算评估。文档一 5.2 节只写"监控"，不可操作。
- 安全：仓库 docker-compose.yml 现状违反项目硬约束（端口全部 0.0.0.0、postgres/minio 默认密码）；文档一第 14 节增加 RabbitMQ 完全未提部署安全基线。

修改意见：

1. 第 14 节增加"资源预算"小节：Redis 峰值内存估算（30 天 × 单图 Hash 约 30 字段 × 峰值图量）、RabbitMQ `vm_memory_high_watermark`、JVM 堆、2G 规格下的结论或明确升级规格前提。
2. 新增条目：RabbitMQ 的 5672/15672 端口绑定 `127.0.0.1`；凭证全部走 `.env`（`application.yml` 只留 `${VAR:}` 占位，与 SMTP/MinIO 同一规范）；借此机会修正现有 postgres/minio 默认密码问题。
3. 明确开发 compose 与生产 compose（`/opt/artverse`）分离，方案只改哪一份。

### H2. Redis Cluster hash tag 设计错误（slot 倾斜）

文档一 5.1 节：`{123:rootUuid}` 的 hash tag 取花括号内全部内容，即 `{userId:rootId}` 意味着同一用户的所有请求挤进同一个 slot——生图重度用户的全部状态、锁、提交凭证都落一个节点，数据倾斜且无法水平扩展，违背引入 Cluster 兼容的初衷。

修改意见：tag 改为 `{rootId}`（requestId 全局唯一，天然分散）；`image:target-*` 保持 `{chapterId:imageNumber}` 不变。单实例下无感知，纯文档修改零成本。

### H3. target-active 无 TTL 的卡死风险与降级路径缺失

- 残留清理（文档一 6.2 节）依赖入口短锁内"核实 Redis 终态 + DB 无 UNRESOLVED + 旧执行租约已结束"三重核实。三个存储任一不可用时入口只能 503，用户被残留占用卡死且无自愈路径；入口短锁 60s 内完成"Redis 查询 + DB 查询 + 可能的 MinIO 候选核对"的预算未评估。
- NEEDS_REVIEW 长期无人处理时，用户自己也被占用阻断。文档二 7.4 节定义了"放弃旧结果并重新生成"的显式恢复操作，但文档一 12.3 节前端契约没有对应的界面与接口——这个恢复操作在整套 HTTP/SSE 契约中不存在。

修改意见：

1. target-active 增加 `last_heartbeat_at`/`updated_at` 字段；保留无 TTL 设计但补兜底规则："最后活跃超过 N 小时且无 UNRESOLVED 证据 → 允许入口核实清理"。
2. 第 12 节补充 NEEDS_REVIEW 的完整用户链路：详情展示（含依据）、"放弃旧结果并重新生成"的接口（新 operationId + 重复收费提示）、候选/快照的后续清理。
3. 写明入口清理核实的具体降级顺序（Redis 不可用 → 503；DB 不可用 → 503；均可用 → CAS 清理）。

### H4. expected_image_version 的前端来源缺失

MangaImage 当前没有 version 列（已核实无 `@Version`，计划新增）。方案要求重生成必须提交前端看到的图片版本，但文档一 12.2 节响应里 images 项的 `state_epoch/version` 是状态机版本，不是图片实体版本——前端没有任何渠道获取 `expected_image_version`。

修改意见：第 12 节补充章节图片列表/详情接口返回 `image_version` 字段的契约变更；同时说明 `@Version` 列的迁移（NOT NULL DEFAULT 0）及现有 MangaImage 写路径（如 MangaImageStorageService 保存/更新）对 `OptimisticLockingFailureException` 的适配——现有代码没有处理乐观锁异常的准备，实施时会踩坑。

## 二、中优先级（建议修改）

### M1. 多额度"带租约信号量"实现未定义

文档一 5.1 节一行带过"分作用域的带租约额度"。`SET NX PX` 只覆盖额度=1 的串行通道；global/user/profile 各 4 的场景需要"崩溃后额度自动回收"的租约信号量（如 ZSET + score=过期时间 + Lua 清理判断之类），否则 Worker 崩溃即额度泄漏，最终全部 blocked。

修改意见：第 15 节阶段 A 增加该数据结构的语义验证（取得/续期/崩溃回收/固定顺序回滚）。

### M2. operation-timeout=15m 语义歧义

文档一 9.2 节：3 次供应商尝试 × 600s 超时 + 600s 重试延迟档位，跨投递总时长可达 40 分钟，远超 15m。若 15m 指单次投递预算则命名误导；若指操作总预算则与 3 次尝试矛盾。

修改意见：明确 15m = 单次投递处理预算，另设操作级总预算（如 45m）并在 RETRY_WAIT 批准时校验剩余预算。

### M3. 停机时在途 GENERATING 的预案分散

优雅停机 60s vs `provider-timeout=600s` 直接冲突：每次滚动发布/系统更新都会把在途调用打断成 NEEDS_REVIEW。

修改意见：第 17 节发布顺序增加 drain 流程：先停新消费 → 等待在途 GENERATING 完成（上限 provider-timeout）→ 再停机；紧急停机时接受 NEEDS_REVIEW 的操作手册条目。

### M4. 新错误码缺数值 code 映射

文档一 12.2 节列出 12 个字符串错误码，但项目硬约束是统一 `Result` 数值 code + message。

修改意见：明确字符串码挂 `data.error_code`，`Result.code` 分配数值段（对齐 40304 等既有风格），否则前端会出现两套判断逻辑。

### M5. markExpiredRunningAsFailed 会误伤新 MQ 记录

现有 12 分钟 RUNNING 推断（ImageGenRecordRepository）会把新路径排队超过 12 分钟的记录标 FAILED。文档一第 2 节只说"对新 MQ 请求停用该推断"，未写实现口径。

修改意见：按 `request_id IS NULL`（旧记录）过滤；且新记录入库时 status 语义不再复用 RUNNING。

### M6. 虚拟线程的两个实现风险未列入验证

- GENERATING 阻塞 600s 期间，执行租约（120s/30s 续期）需要独立续期机制——监听线程正阻塞在模型调用上，谁来续期未说明。
- JDK 21 虚拟线程在 `synchronized` 块内做 IO 会 pin carrier（JEP 444 已知限制，JDK 24 才解决）；MinIO SDK/现有代码的同步块需阶段 A 验证。文档引了 JEP 444 但未提 pin。

修改意见：两点均补入第 15 节阶段 A 验证清单。

### M7. 复杂度对账

35 个故障场景、15+ 新类、双通道 + 控制消息代际链 + 三层额度 + state_epoch，对单人和 2G 服务器是重架构。

修改意见：第 15 节各阶段显式声明"可独立回退点"；考虑把阶段 D（串行章节）允许延后——先并行单图通道上线，串行编排二期，两者依赖面已隔离。

## 三、低优先级（细节）

| # | 问题（位置） | 建议 |
| --- | --- | --- |
| L1 | 单节点 quorum queue 零冗余收益、内存/磁盘开销更高（文档一 7.2 拓扑表） | 改 durable classic（lazy）起步，quorum 留给三节点 HA；或写明单节点选 quorum 的理由（落盘语义更强） |
| L2 | 额度不足 blocked 重投无退避（文档一 4.1 表 QUEUED 处置） | 30s→120s→600s 递增退避，避免额度持续不足时空转 |
| L3 | RETRY_WAIT 的 not_before 与固定 TTL 档位（30s/120s/600s）不匹配时行为未写 | 写明"选不小于剩余等待的最小档位，提前唤醒后允许再次转延迟" |
| L4 | `image:events` 全局单频道，用户量增长后消息风暴 | 按用户或 requestId 分频道 |
| L5 | 单图场景 `image:submission` 与 `image:request` 职责重叠 | 明确单图只写一份，避免两套 key 一致性维护 |
| L6 | NEEDS_REVIEW 长期未决的候选对象无清理策略（文档一 6.3 只覆盖快照清单） | 并入人工核对清单；终态+保留期后清理 |
| L7 | `status_source` 取值未定义（文档一 12.2） | 补枚举（如 redis/rebuild/db） |
| L8 | follower-wait=5s 与 publish-confirm-timeout=5s 同值，边缘抖动 | follower-wait 提至 6–8s |
| L9 | 文档一 4.2 节末引用"单图细化方案 7.1–7.4 节"，漏了 7.5 | 补引用 |
| L10 | ImageGenRecord 已有 V50 automation_* 字段（automationTargetKey 等），与新增 request_id 唯一约束共存时旧记录为 NULL 不冲突 | 文档一 10.1 补一句说明，避免实施者疑虑 |

## 四、处理状态

- 2026-10-26：已形成 H1–H4、M1–M7、L1–L10 的逐项修订建议，见 [审核修订与实施规格](image-generation-review-revisions.md) 第 1 节对照表；主方案、单图生命周期及概览已同步。**状态为待用户复审，不代表已批准实施或运行验收通过。**
- H2 接受 `{rootId}` 规范，但原“复合 tag 使同用户固定一个 slot”的推论需纠正；根 ID 不同也会影响复合 tag 的哈希。H3 的未知结果不能仅凭时间阈值释放；补有界恢复与显式替代，默认不开放无证据强制放弃。
- L1 保留单节点 quorum 以验证本方案的可靠 DLX，不宣称 HA；M5 保留实际调用的粗粒度 RUNNING，排队不建该记录且旧推断过滤新请求；M7 允许整章二期，但不允许旧入口绕过串行许可。具体取舍仍需复审。
- 后续用户意见：服务器规格/扩容不再作为本轮方案内容或开发前置；新请求缺供应商选择在参数校验返回提示，调用结果未知与供应商参数缺失分开定义；“旧写入口”已补现有 HTTP 接口清单及可保留 URL 的 MQ 适配含义。本节以上问题原文保留为审核历史，不据其中原容量建议继续要求扩容。
