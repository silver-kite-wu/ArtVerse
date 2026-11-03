# 生图方案审核修订与实施规格

修订日期：2026-10-26。对应 [审核原文](image-generation-design-review.md) 的 H1–H4、M1–M7、L1–L10。**后续用户已明确授权即时单图、重生成和整章共同实施，代码已接入；尚未生产部署。** 实际实现、验证子集及限制以 [实施记录](image-generation-implementation.md) 为准。本文保留审核修订和完整验收规格，未将所有场景标记为已通过。

## 1. 逐项处理结论

| 审核项 | 本轮决策 | 规格位置 |
| --- | --- | --- |
| H1 资源与安全 | 按用户意见不讨论服务器规格/扩容，不作为本轮开发前置；保留配置文件范围与凭证/端口规则 | 第 2 节 |
| H2 hash tag | 采用 `{rootId}`；修正“复合 tag 必然按用户聚集”的推论，保留用户键空间隔离 | 第 3 节 |
| H3 无 TTL 占用与恢复入口 | 接受；补有界核实、无收费恢复、人工处理和显式风险重生成协议；时间阈值不代表外部调用结束 | 第 4 节 |
| H4 图片版本来源 | 接受；新增图片实体 version、列表/详情/结果 DTO、前端原样提交图片 ID 与版本 | 第 5 节 |
| M1 租约信号量 | 接受；ZSET + Redis TIME + Lua，固定顺序取得/续期/回滚；持久未决调用不能被 TTL 抹去 | 第 6.1 节 |
| M2 超时口径 | 接受；单投递 15m、单图首次认领起总预算 60m、最多 3 次且必须容纳于剩余预算 | 第 6.2 节 |
| M3 停机 | 接受；正常发布 drain 16m，容器退出宽限 17m；60s 仅紧急停机口径 | 第 6.3 节 |
| M4 错误码 | 接受；数值 code 保留 Result 契约，字符串在 data.error_code，业务查询成功仍 code=0 | 第 7 节 |
| M5 旧记录超时推断 | 接受过滤；新记录仅实际准备调用时为 RUNNING，不为排队创建 RUNNING，不新增数据库阶段状态机 | 第 6.4 节 |
| M6 续租与 pinning | 接受；独立续租线程，阶段 A 验证 JDK 21 carrier pinning 和停机行为 | 第 6.1、8 节 |
| M7 复杂度与回退 | 接受分期；先上线完整独立单图双通道，整章入口暂不切换，串行章节控制可二期；不会把重生成改成并行 | 第 8 节 |
| L1 单节点 quorum | 保留；需要验证可靠 TTL/DLX 转交，换 classic 不能悄悄降低该语义；不宣称单节点有 HA | 第 2.3 节 |
| L2/L3 延迟退避 | 接受；额度等待 30s→120s→600s，not_before 采用不早于剩余等待的最小可用档位 | 第 6.2 节 |
| L4 事件频道 | 接受；改请求级频道，不用全局 image:events 广播所有业务事件 | 第 3 节 |
| L5 单图重复 Hash | 接受；合并 submission 与 request；独立单图状态也保存在该 Hash，不另存 op 副本 | 第 3 节 |
| L6 长期待核实对象 | 接受；人工核对清单与终态后保留期，未决结果不按年龄自动删 | 第 4.6 节 |
| L7 status_source | 接受；定义 redis/rebuild/db；无法核实不伪造 status | 第 7 节 |
| L8 follower 等待 | 接受；8s，发布确认等待仍 5s；超时响应可重试原身份 | 第 6.2 节 |
| L9 交叉引用 | 接受；引用单图方案 7.1–7.5 | 主方案 4.2 节 |
| L10 automation_* 共存 | 接受；旧行 request_id 为 NULL，新唯一约束不回填随机 ID；保留 automation 过滤 | 第 6.4 节 |

## 2. H1 / L1：部署、容量与安全

### 2.1 本轮范围

按用户 2026-10-26 的意见，服务器规格、扩容/外置服务和同机内存预算不纳入本轮方案审核，也不作为开始开发的前置条件；删除原 4GiB/8GiB 建议及由此引入的并发覆盖。执行并发仍按已确认的双通道与共享额度设计。

保留功能所需的有界处理：输入/结果不放 Redis，单图解码后 32MiB、供应商 HTTP 响应 64MiB 为拟议上限；队列满拒绝发布，不能静默丢弃，Redis/MQ 写失败不降级为内存任务。具体服务器容量规划不在此展开。

### 2.2 文件范围与安全配置

后续实施拟修改仓库 `ArtVerse/docker-compose.yml` 作为开发配置，并新增可审核的生产模板 `ArtVerse/deploy/compose.production.yml` 和无真实值的 `.env.example`。生产模板计划放到 `/opt/artverse` 使用；本轮不连接服务器、不修改 `/opt/artverse` 实际文件、不读取或重写现有 `.env`。新增凭证由部署时生成，示例不包含默认口令。

RabbitMQ 5672/15672 如需映射，均仅 `127.0.0.1`；纯容器内调用可不映射 5672，管理 UI 默认不开公网。生产模板同样收紧 PostgreSQL/Redis/MinIO 的管理/数据端口；公开图片沿用现有授权代理，不把管理口暴露给浏览器。明确镜像版本/digest、持久卷、专用 vhost/最小权限账号，禁用 guest 用作应用账号。

compose 使用 `${VAR:?required}` 验证凭证存在；application.yml 仅环境占位（如 `${RABBITMQ_PASSWORD:}`），启动校验拒绝空值，不能用空值/默认口令悄悄启动。环境文件不入 Git，生产权限仅部署用户可读。现有 PostgreSQL/MinIO 默认凭证在实施中替换并给出轮换步骤；已有持久卷修改环境变量并不会自动轮换服务端口令，必须分别执行服务端凭证变更、应用更新和回滚演练，避免数据库无法登录。安全变更列入独立可审核部署差异，不把它当成本轮已经完成的修复。

### 2.3 单节点队列选择

保留 durable quorum 起步，理由是本方案依赖需要实证的 at-least-once dead-lettering，包含 TTL 延迟和隔离责任；持久化 classic + Publisher Confirm 能覆盖部分接收持久化，但不能直接等价替代本方案的可靠 DLX 转交。**单节点 quorum 不提供节点级 HA**，卷/宿主机丢失仍可能损失数据。三节点部署与副本迁移是后续独立验收项。

如果后续考虑换 classic，需要另审重试/章节控制的可靠转交协议及故障保证，不能只把队列 type 改掉。阶段 A 固定 Broker 版本后验证 queue type、delivery-limit、overflow、dead-letter-strategy，不能填写未经验证的 lazy 参数。

## 3. H2 / L4 / L5：Redis 键空间

请求域键统一为 `image:request:userId:{rootId}`、`image:op:userId:{rootId}:operationId`、`image:op-lock:userId:{rootId}:operationId`、`image:advance-lock:userId:{rootId}`、`image:terminal-set:userId:{rootId}`。userId 在 tag 外保留租户隔离；rootId 使用完整规范 UUID，不截断，不用用户常量代替；客户端重试身份与 canonical root 的映射继续核实归属。

Redis 对有效 tag 的完整内容计算 CRC16 再映射 slot。因此 `{userId:rootId}` 在 rootId 变化时也会变化，不意味着“同用户固定同 slot”；采用 `{rootId}` 是简化分片边界，不据此宣称已经解决所有热点。一个根请求的键有意同 slot，目标图片域仍用 `{chapterId:imageNumber}`，额度域独立；根请求最多 60 张以及有界控制唤醒用于限制单根热点。`CLUSTER KEYSLOT` 验证同根同 slot、不同根分散统计；不要求不同根绝不碰撞。

本轮仅做公式复核：固定 userId=123，用 10000 个确定性 UUID 分别计算 CRC16(tag) % 16384；复合 tag 与纯 root tag 均覆盖 7487 个 slot，四个 slot 区段分别为 2496/2464/2487/2553 与 2464/2496/2553/2487 个请求。此实验说明固定用户前缀不把这些不同根 ID 压为一个 slot，**不是 Redis Cluster 实测或线上流量容量结论**。

移除独立 `image:submission` 数据 Hash，将 submission_hash、submission_status、publish_attempt_id、首次受理时间合并到 request Hash；submission-lock 仍是独立租约锁。对于独立单图，该 request Hash 同时就是唯一单图状态 Hash，不另写相同 operation 的 image:op Hash。整章才为已发布的子图写 op Hash；根聚合与子图代表不同业务层级。StateRepository 根据已验证的 execution_mode 解析 stateKey，所有 Lua 使用同一解析器，禁止调用方自行拼两套键。

别名只保存 canonical 引用，不复制状态。事件频道改为 `image:events:userId:{rootId}`，API 仅订阅当前 SSE 需要的请求频道，最后一个本地订阅关闭后解除订阅。通知仍不可靠，重连先 snapshot；普通 Pub/Sub 与 Cluster sharded Pub/Sub 是否可用在阶段 A 验证，不把频道分组直接称为物理分片。不增加全局 image:events 广播回退。

## 4. H3 / L6：无 TTL 占用与恢复的完整链路

### 4.0 供应商参数缺失与调用结果未知

前端应在新生图请求中明确选定供应商：提交有效 config_id（引用当前用户已保存的供应商配置），或按该入口支持的临时 BYOK 协议提交 provider/base_url/model/api_key 等配置。config_id 已明确供应商时不要求重复发送明文凭据。不能仅凭 model 名猜供应商，不能在缺配置时自动切换其他供应商。

参数校验层在 single-flight、图片锁、MQ 和模型调用之前解析并验证供应商。完全没有供应商选择返回 HTTP 400、Result.code=40062、data.error_code=PROVIDER_REQUIRED，message="无供应商，请选择生图供应商"；配置不完整、已禁用、无权限或模型不匹配，分别按配置校验错误返回，不入队、不创建图片占用。前端可提前提示，后端仍必须校验。此规则是拟改造目标；当前 MangaGenerationController 仍有未传 model 时解析默认配置的路径，需要在实施时收口，不能把现状误写为已满足。

“调用结果未知”则发生在供应商和请求参数已通过校验、后端已经发起调用之后。例如请求已发给供应商，等待期间本应用进程宕机，重启后无法确认供应商是否完成生图、是否已计费以及图片结果能否取回。这里供应商身份是已知的，未知的是这一笔调用的结果；因此不是返回“无供应商”的参数错误。下文 SAFE/NEEDS_REVIEW 只处理后一种情况。

### 4.1 时间阈值与入口预算

target-active 增加 `created_at/updated_at/last_heartbeat_at`、`active_version`、canonical root/operation、提交状态及 input_hash。消费者每 30s 续租成功后按 canonical 身份更新 heartbeat；更新失败按失去安全运行条件处理。时间不代替调用证据。

建议 `stale-active-after=2h` 只触发“需核实”提示及入口轻量核对；原操作已有明确终态可立即清理，不必等 2h。超过 2h 不能强删；QUEUED 无 Worker heartbeat 也不能判定消息丢失。可用存储都没有 UNRESOLVED 还需确认旧 Worker/调用许可无法继续执行、基准未被消费；查不到数据与查询失败不能混同。

入口清理总预算 3s：Redis 命令超时上限 500ms，数据库核实事务上限 1s，其余留条件更新及响应。图片短锁 lease 60s 是容错上限，不是允许请求阻塞 60s。**不在入口持锁期间下载候选或查询供应商**；需要外部核实时返回占用信息与 recovery 操作，由恢复消费者处理。跨存储状态先核对再条件清理，版本有变化则重读一次或返回冲突，不无界循环。

| 条件 | 入口处理 |
| --- | --- |
| Redis 不可用/超时 | 503 STATUS_UNAVAILABLE；不依据 DB 快照绕过目标准入 |
| Redis 可用，DB 不可用/超时 | 503 STATUS_UNAVAILABLE；不删除 target-active |
| 终态证据充分、没有未决调用、旧执行权结束 | 在目标锁内按 canonical + active_version CAS 删除残留，保留幂等凭证 |
| 有已知未决调用/无法读取候选/证据矛盾 | 返回真实占用或待核实；不在入口新增收费调用 |
| 发布前遗留、无发送证据 | 原身份可安全恢复发布；若选择放弃，先持久写未调用终结凭证，再释放占用 |

发布前遗留的放弃必须在同调用许可的短事务互斥内写 `provider_attempt=0 / attempt_decision=FINAL / outcome=ABANDONED_BEFORE_CALL` 的有限历史凭证；原消息或旧 API 发布迟到，消费看到它即停止，不复活操作。若调用已取得许可则不能走此分支。无数据库任务扫描或自动再派发。

### 4.2 状态响应与用户按钮

GET/snapshot/needs_review 事件在单图项增加 `last_activity_at`、`review_reason`、`recovery_version`、`recovery_actions`、`recovery_in_progress`、`next_check_at`、`review_requested_at`、`blocks_serial_lane`、安全的 evidence_summary。依据只含“调用可能受理/已找到候选/供应商不支持查询”等事实，不泄露密钥、原始响应或其他用户信息。

前端由服务端 recovery_actions 显示按钮；用户不能自行以 status 推导可执行动作。待核实展示图序、耗时、关联 ID、已有图片与处理依据，区分“本张阻断”和“共用串行通道被未决调用阻断”；禁用普通再次生成按钮。

| action | 界面与行为 | 是否调用模型 |
| --- | --- | --- |
| RECHECK | “重新核实”；只读持久结果、候选、已支持的供应商查询 | 否 |
| REQUEST_REVIEW | “申请人工核实”；保存申请与依据摘要，显示已申请和可复制关联 ID；管理页可查询处理 | 否 |
| RESUME_SAFE | “继续原请求”；只在已证明未调用或持久 RETRY_ALLOWED/候选恢复条件具备时显示，仍用原 operationId | 仅获准的原尝试/重试可调用 |
| RELEASE_CONFIRMED | “结束已核实请求”；仅在外部调用确定结束/未受理且旧调用者不能再发送时结束、清残留；不自动生成 | 否 |
| ABANDON_AND_REGENERATE | “放弃旧结果并重新生成”；风险确认后新 operationId，保留与原操作的关联 | 是，一次新的付费意图 |

不能把“关闭窗口/隐藏记录”当成放弃。RECHECK/REQUEST_REVIEW 在没有供应商查询能力时也可到达：前者检查本地持久证据并明确能力缺失，后者进入人工清单，不能假装在后台永远轮询。用户可继续阅读已有图和使用不受该目标/额度阻断的独立首次生成。

### 4.3 HTTP 与恢复命令

新增 `POST /api/image-generation/requests/{requestId}/operations/{operationId}/recovery-actions`，请求包含 `action_id` UUID、action、`expected_recovery_version`；需当前登录用户归属/章节权限和 CSRF。action_id 与规范 body 绑定；重复返回原处理结果，异体 409，旧 recovery_version 返回 409 RECOVERY_CONFLICT。

RECHECK 和 RESUME_SAFE 只有可靠发布后返回 202；REQUEST_REVIEW / RELEASE_CONFIRMED 在持久审计及条件更新完成后返回 200。所有响应包含 canonical 请求、最新单图快照和 action 结果。恢复操作受每用户每分钟 3 次及每原操作同时一条核实链约束；不占模型额度的查询也不能无限提交。

RECHECK 使用独立 `IMAGE_RECOVER` 命令及 `artverse.image.recovery.x/q`（durable direct + quorum，routing=recover，消费者 1、prefetch=1、MANUAL ACK），不复用仅有一个消费者的章节推进队列。字段含原身份/快照引用、action_id、expected_recovery_version、probe_index、recovery_deadline、not_before、trace_id；默认大小仍 64KiB，不带凭据。必要等待按确认下一条恢复命令后 ACK 当前投递，允许有界重复，最终核实结论持久记录后 ACK。IMAGE_RECOVER 本身禁止调用生成接口；RESUME_SAFE 另按许可可靠发布原 IMAGE_EXECUTE。

单次查询超时 10s、单投递处理 30s、同一核实链最多 3 次 probe、总时限 180s，固定等待 30s/120s，无法容纳下一次 probe 则结束自动核实。无查询能力立即返回仍待人工，无无效重试。并发/重复 action 先复用持久结论或已有链，不能各开 3 次查询。审批中的人工证据或供应商请求 ID 有变化才允许另建核实链；纯重复点击按 60s 冷却及频控处理。

仅持有 recovery-lock 不能解锁目标。恢复先读证据，外部查询在事务外；决定恢复/释放时，按第 6.1 节固定顺序重新核对 owner、token、目标版本及 recovery_version，短事务保存 `recovery_action_id/recovery_version/recovery_decision/review_requested_at` 和脱敏 recovery_audit，再同步 Redis。recovery_audit 按 action_id 保留输入摘要、处理结果及替代身份，不能只存“最后一个 action”而使旧请求重放再次生效；单操作最多 100 条用户恢复动作，达到上限只读/人工处理，管理决定保留审计。失败重放原 action 幂等补齐，不重复付费。现有业务历史仅增加有限恢复凭证，不存整个阶段状态机。

如果恢复的是暂停整章的当前图，需以原 root/manifest/cursor 可靠重放 MANGA_ADVANCE，而不是由恢复消费者直接发布下一张图片；当前恢复投递在必要控制责任 Confirm 后才 ACK。根计划已结束/失效则不重开。控制重放失败保留当前恢复责任，旧隔离消息保留审计且以后重放仍校验代次/业务结果，避免恢复图片后整章永久停在隔离状态。

管理页新增只读待核实列表与详情，按 ImageGenRecord 的 unresolved/outcome 查询，分页默认 20、最大 100；这是人工操作清单，不是数据库派发扫描器。管理决定接口 `POST /api/admin/image-generation/requests/{requestId}/operations/{operationId}/review-decisions` 使用已有 ADMIN 角色检查，要求 expected_recovery_version、证据来源/脱敏引用、provider_attempt/token 和明确结论。供应商后台人工核实属于人工责任，系统不能把任意备注当作已验证的供应商事实；“无法确认”只能维持未决，不能点确定失败来绕过串行限制。

### 4.4 显式放弃并重新生成的边界

为给出审核所需的完整接口，新增 `POST /api/image-generation/requests/{requestId}/operations/{operationId}/abandon-and-regenerate`。请求必须包含新的 request_id、expected_recovery_version、当前 expected_image_id/expected_image_version（缺图则 expected_absent）、`accept_possible_duplicate_charge=true`；登录用户确认一次展示的旧操作 ID、未决依据和收费风险。不能因旧请求超时就自动调用该接口。

**建议本期仅开放“已核实旧调用结束且旧执行者已退出”的安全放弃**；`allow-unsafe-abandon=false`。未确认结束时返回 409 EXTERNAL_CALL_UNRESOLVED，前端展示“申请人工核实”。若审核要求无供应商证据也必须强制放行，需要另行明确放弃“供应商侧严格串行/绝不重复收费”的保证；不能以一个确认框同时承诺这些性质。

安全放弃的短事务先终结旧操作调用/提交权限，记录原 outcome、replacement_request_id 与不可复活凭证；不得把“无法核实”伪造成确定失败。随后以新身份经正常校验/准入/快照/Confirm 流程创建新操作，旧 UUID 继续返回旧结论。此复合动作不用分布式事务：若旧操作已终结而新消息发布失败，返回可原新身份重试的结果，不回滚复活旧操作；稳定关联保证相同 action 不创建多个替代操作。Confirm 未知仍复用新身份。所有新重生成继续走 SERIAL_MODEL。

这里明确一条无法自动消除的限制：供应商无查询/幂等/取消能力、也无法人工核实时，可以释放进程线程和短租约，但不能在保持原收费与串行保证的同时安全自动解锁目标及串行通道。该项是审核要选择的恢复保证，不把卡死隐藏成 TTL 清理成功。

### 4.5 恢复状态迁移

NEEDS_REVIEW 在已审核的恢复凭证下可进入 COMMITTING（已有候选）、SUCCEEDED（已有成功证据）、FAILED（确认结束无可恢复结果）或 RETRY_WAIT（供应商明确允许重试）。调用许可从未登记的中断可在新 claim 下恢复 LOAD_CONTEXT；不是把所有未知情况退回 QUEUED。SUCCEEDED/FAILED/STALE 不复活；安全替代始终新 operationId。通道未决标记只有确定结果/安全终结后才 FINAL。

### 4.6 候选、快照与人工清理

管理详情列出 operation/token 所有候选、manifest、是否被当前图片引用、调用未决情况、最后核实时间和预计可清理日。未决操作包括被用户隐藏的记录不自动清理。确定终态后保留至少 30d 且不短于命令可重放窗口；仍被当前图片引用的对象永久排除于候选清理。

初期提供 dry-run 清单和按操作执行的人工清理命令：再次核实终态/token/无未决及无引用，删除未使用候选、快照及已结束控制对象，记录审计；保留轻量业务幂等凭证和必要结果元数据以处理迟到消息。不能先删去重依据再删队列，不能直接按 bucket age lifecycle 删除所有 generation-results。自动回收器不在本期必需范围。

## 5. H4：图片版本从哪里来

### 5.1 Schema 与所有写入口

实施使用当时下一个可用 Flyway 编号，新增 `manga_images.version BIGINT NOT NULL DEFAULT 0`，存量回填 0；实体新增 `@Version Long version`。这表示图片行的乐观锁版本，与 Chapter.version、Redis 的 state_epoch/version、execution_token 均不同。

仅比较数字 version 会出现删除后重建又为 0 的 ABA 问题，因此重生成同时要求 `expected_image_id` 和 `expected_image_version`。持久 target 基准/默认 requestId 派生均包含二者。首次生成仍是 expected_absent。图片版本以十进制字符串在 JSON 传递（例如 "3"），避免前端处理 BIGINT 的精度问题；不允许客户端自增。

需审计并改造 MangaImageStorageService 的保存/替换、即时生成、预约调用共用保存方法、后台修改和原生/批量更新路径：旧写对象指针、prompt 等会改变结果的更新均推进 version。最终事务必须显式比较提交时固定的 ID/version，再执行 @Version 保护写入；不能重新查询最新实体后直接 save 覆盖旧基准。删除后重建新 ID 使旧请求 STALE；缺图插入仍依靠现有章节+图序唯一约束。

OptimisticLockingFailureException / 乐观锁冲突统一映射 TARGET_CONFLICT：调用前发现则不调用，结果提交时发现则 STALE 并保留新图，已上传候选列入清理清单；不重试整个生图方法。数据库/存储故障不误判成版本冲突。缺图并发唯一约束冲突重读：只有能证明相同 operation 已成功才复用，否则冲突。

### 5.2 API、DTO 与前端

现有 `GET /api/chapters/{chapterId}` 和 `GET /api/stories/{storyId}/chapters` 的 ChapterDto.MangaImageDto 增加 `image_version`；新单图结果/GET snapshot/image_result 同样返回 `manga_image_id` 和 `image_version`。Redis `version` 始终是状态版本，不能当图片版本提交。无需新增单独“获取版本”接口。

```json
{
  "id": 1234,
  "image_number": 7,
  "image_path": "stories/example/result.png",
  "image_version": "3"
}
```

```json
{
  "request_id": "0f5ecdcf-bf19-4e8c-83a6-069660573ee0",
  "expected_image_id": 1234,
  "expected_image_version": "3",
  "prompt": "用户确认的提示词"
}
```

前端 authoring.ts 的 MangaImage、MangaPanel 的展示对象/图片映射、统一请求 hook 均携带 image_version。初次加载从章节响应获取，生成成功从结果更新 ID/version；点击重生成时固定当时图片 ID/version 与请求 ID，网络重试原样复用。缺字段禁用重生成并重新 GET，仍缺则显示接口版本不兼容，不能默认 0。版本冲突刷新展示并要求用户再次点击确认新的业务意图，不自动刷新基准后继续收费。

迁移先部署列和可回滚读字段，再升级全部共享图片写入口；旧二进制可能不递增 version，不能在有旧写实例时开放新 MQ 重生成。前端字段上线后才切换要求基准的新接口；不能先要求前端发送尚未返回的字段。测试现有历史展示、预约写入和删除重建三条路径。

## 6. M1–M6 / L2 / L3 / L8 / L10：执行规格

### 6.1 额度、续期与许可

op/target 短租约采用 SET NX PX + owner Lua；多额度采用每作用域独立 ZSET（如 `image:permits:{global}`、`image:permits:{user:userId}`、`image:permits:{profile:profileId:modelHash}`、`image:permits:{serial-model}`），member=`operationId:claimId`，score=lease_expires_at_ms。serial 也是 capacity=1 的同一算法，避免另一套回收实现。

Lua 取 Redis TIME，清理 score 已到期成员；同 member 已存在且未过期视为幂等取得；ZCARD < capacity 才 ZADD 新成员。续期只对未过期、仍为本 claim 的 member 更新；已过期不能以“续期”复活。释放只 ZREM 本 member。检查容量/清理/新增在一个作用域原子完成。取得顺序固定 global→serial（适用时）→user→profile→op-lock→target-lock；任一失败逆序释放本次已取得资源，跨 slot 不假装全局原子。同操作读到别人仍有有效 owner 时不得释放其 member。

DB 许可/恢复事务统一 global→serial→user→profile→target→history 的互斥顺序，建议使用 PostgreSQL transaction advisory lock：规范名称如 `image:permit:global`、`image:permit:serial`、`image:target:chapterId:imageNumber`，以数据库 `hashtextextended(name, 0)` 生成 64 位锁 ID，禁止 Java hashCode 或实例随机种子。哈希碰撞仅导致额外串行，不作为身份相等的判断；身份仍逐字段核对。锁等待/事务预算内失败后退避。所有调用许可事务先 global，因此全局数量核对不会各自先查后写。

持久历史补 server-derived profile_model_key，使用与 Redis 相同的用户/配置/模型作用域标识，不包含明文密钥。迁移加入 UNRESOLVED 部分索引：全局未决、execution_lane、user_id、profile_model_key、chapter_id+image_number+target_image_id+target_base_version；实际 SQL、计划与索引数量在阶段 A 以真实 PostgreSQL EXPLAIN 验证，可合并冗余索引。按这些证据核对全局/用户/配置容量，串行还检查是否已有任意未决调用。Redis member 过期只能回收进程额度，失联已发送调用仍占持久未决额度，不能把它忽略后声称模型在途严格小于 4。终态/明确未受理同步更新调用结论；旧 Worker 许可令牌不因锁重新出现而复活。

独立 ScheduledThreadPoolExecutor（平台线程初值 2）管理活跃 claim 的续期，不依赖阻塞在 WebClient.block 的监听虚拟线程；以有界注册表和批次更新所有 op/target/额度/target-active heartbeat，30s 周期带小抖动，单 Redis 调用预算 2s，检查距过期安全余量。续期任务不执行图片生成/ACK。任一必要租约续期失败或丢 owner，停止新收费发送与状态推进；已有请求尽力取消本地等待，但不视作供应商已取消，按未决/候选恢复。过期消费者不能再提交结果。

### 6.2 时间、重试与延迟

| 参数 | 初值与精确定义 |
| --- | --- |
| publish-confirm-timeout | 5s，单次发布确认等待 |
| follower-wait | 8s，等待同一次提交结果，不等模型 |
| delivery-processing-timeout | 15m，单次模型工作投递的处理预算，包含上传/提交/可靠转交 |
| operation-total-timeout | 60m，单图首次认领起的总时限，跨重投/重试不重置；首次排队不计入 |
| provider-timeout | 600s，单次实际请求上限，同时受剩余总预算限制 |
| postprocess-reserve | 180s，批准下一次模型调用前预留上传/提交/转交预算 |
| provider attempts | 最多 3，不保证一定用满；每次都需剩余预算及持久许可 |
| defer_count | 等锁/额度的独立计数，30s→120s→600s 封顶，成功认领后重置，不增加 provider_attempt |
| stale-active-after | 2h，只触发核实，不自动删除占用 |
| recovery budget | 单 probe 10s、单投递 30s、最多 3 probes/180s；人工申请不创建无界循环 |

初次认领将 started_at/deadline 固定到可恢复 manifest/调用凭证引用；不能仅存 Redis 并在重建时刷新总预算。对尚无调用历史的准备阶段，在调用许可前条件创建私有 `generation-inputs/userId/rootId/operations/operationId/start.json`，保存 operationId、input_hash、first_started_at、deadline；不修改原不可变 manifest。条件创建冲突读取已有凭证并校验归属/摘要，原身份重投复用，不把它用作待发送队列。写失败不得调用模型；已有持久调用凭证后也只读取同一 deadline。

批准重试时需要 `now + 等待 + provider预算 + postprocess_reserve <= operation_deadline`，否则未发生未知调用才确定结束；已有 UNRESOLVED 必须核实，不能因为到总时限就伪造 FAILED。每次投递使用 min(15m,剩余总时限)；额度等待也不能无限跨过已开始操作的总时限。

not_before 延迟选不小于剩余等待的最小 30s/120s/600s 档位；大于 600s 则先等 600s，醒来检查剩余时间再转交。任何提前唤醒只可靠转延迟、不调用模型，约 600s 的等待不等于必须再增加一次 provider_attempt。Retry-After 优先，但不突破总预算。所有延迟转交先 Confirm 再 ACK；转交失败保留原投递。用于恢复的等待路由回 recovery.q，不能误入 execute 队列。

### 6.3 正常 drain 与紧急停机

发布先从负载入口停新提交、coordinator 停推进新图片、工作容器暂停取得新消息；不强制取消正在处理的 listener。保留续租/Redis/MQ/存储连接，等待在途单次处理完成及 ACK/可靠转交，drain-timeout=16m，容器 stop_grace_period=17m；Spring shutdown phase、listener 停止方式与容器宽限同阶段 A 验证，不能假定任一 shutdown 方法都自动等完整模型调用。

drain 结束前已完成则正常退出；16m 仍未结束记录未决与候选、保留 MQ 投递责任，按紧急停机处理。紧急停机预算 60s，接受 GENERATING 变为 NEEDS_REVIEW 的事实，重启不盲重调。发布手册区分“排空成功/强制结束”，不把 60s 当正常滚动发布保证。停机 hook 不抢监听 Channel 做跨线程 ACK。

### 6.4 旧历史推断与 automation 字段

markExpiredRunningAsFailed 的旧逻辑条件明确加 `request_id IS NULL`，并保留 `automation_target_key IS NULL` 及现有 user/status/cutoff 条件。新 MQ 路径的排队仅在 Redis/MQ，不预建 RUNNING 历史；实际登记调用许可时才写粗粒度 RUNNING。未决调用不能被列表查询的 12m 启发式改成已确定失败；历史展示通过 outcome 区分待核实。不添加数据库 QUEUED/LOAD_CONTEXT 等枚举。

旧 automation_* 字段保持原语义；新 `(user_id, request_id)` 唯一约束允许存量 request_id 为 NULL，不回填随机 requestId、不用 automation_target_key 替代请求 ID。所有新 MQ 调用历史从建立起必须有 request_id；测试新旧/automation 三类记录的超时过滤与唯一性共存。

## 7. M4 / L7：错误码与响应来源

成功受理、状态查询（包括返回 FAILED/NEEDS_REVIEW）使用 HTTP 200/202、Result.code=0。请求本身被拒绝/服务不可用时，使用下表数值 Result.code；字符串均在 data.error_code，HTTP 状态不代替业务码。SSE 没有 Result envelope，事件保留 error_code 和对应 error_numeric_code。前端统一 ApiError 保存 code/data，只用现有鉴权数值码触发退出登录。

| data.error_code | Result.code | HTTP（请求被拒绝时） |
| --- | --- | --- |
| INVALID_INPUT / IMAGE_VERSION_REQUIRED | 40060 / 40061 | 400 |
| PROVIDER_REQUIRED / PROVIDER_CONFIG_INVALID | 40062 / 40063 | 400，缺供应商或配置不完整/不可用；越权仍按既有鉴权与隐藏资源规则 |
| IDEMPOTENCY_CONFLICT | 40960 | 409 |
| IMAGE_GENERATION_IN_PROGRESS | 40961 | 409 |
| TARGET_CONFLICT / INPUT_STALE | 40962 / 40963 | 409 |
| RECOVERY_CONFLICT / EXTERNAL_CALL_UNRESOLVED | 40964 / 40965 | 409 |
| CONFIG_CHANGED | 40966 | 409 |
| SUBMISSION_IN_PROGRESS | 50360 | 503 |
| QUEUE_UNAVAILABLE / PUBLISH_OUTCOME_UNKNOWN / UNROUTABLE | 50361 / 50362 / 50363 | 503 |
| STATUS_UNAVAILABLE / RECOVERY_UNAVAILABLE | 50364 / 50365 | 503 |
| RATE_LIMITED | 42960 | 429 |
| PROVIDER_AUTH_FAILED | 50260 | 502，仅同步提交前检查失败；异步结果记单图错误 |
| RESULT_UNCERTAIN | 40967 | 409，仅拒绝对未知结果继续收费的动作；GET 仍成功 |

以上为本功能拟分配码段，实施前全仓检查无碰撞并落到统一 ImageRequestErrorCodes；不改现有 40304/50301 等鉴权码。BusinessException 当前只透传 provider 的错误 data，需要增加本功能类型化错误载荷或专用异常映射，保持 Result 的结构与原错误处理兼容。

status_source 枚举：`redis`=从运行状态读取；`rebuild`=本次由已核实消息/持久证据重建并成功写 Redis；`db`=只读降级返回已确定的业务终态，不能由粗粒度 RUNNING 猜阶段。证据不足时 status=null、error_code=STATUS_UNAVAILABLE，禁用收费动作；已有不可变成功结果仍可阅读。db 降级查询不代表允许绕过 Redis 新受理。

## 8. M6 / M7：分期、可回退点与新增验收

2026-10-26 实施更新：后续用户明确要求本轮包含整章，实际已将单图、全部重生成和整章三个旧入口一起适配到统一 MQ，保留 URL 并更新前端契约。以下阶段表是原审核时的发布分期选项，不再表示本轮仍等待整章开发授权；环境故障/pinning/drain 完整验收仍以实施记录的未验证清单为准。

| 阶段 | 交付及独立回退点 |
| --- | --- |
| A 组件实验 | 无业务流量；验证 ZSET/owner 租约、独立续租、pinning、Confirm/DLX、drain、MinIO 条件写；停止实验不影响旧入口 |
| B 数据/只读契约 | 向前兼容列、DTO、版本、旧历史过滤与恢复清单；关闭新读接口可回退，列保留，已升级写入不能被旧实例并行覆盖 |
| C 独立单图 | 完整单图执行，首次并行 lane、全部重生成 serial lane；同一入口只一种执行路径；停新受理后保留兼容 Worker 排空即可回退前端 |
| D 整章编排（二期可延后） | 单图协议通过后接可靠 MANGA_ADVANCE；独立开关，停推进后保留控制责任，不能删除未完计划；此前整章新 MQ 入口保持关闭 |
| E 页面与人工恢复 | 先只读版本/恢复详情，再启提交/恢复按钮；回退按钮不丢 requestId，已受理请求仍可查询和处理 |
| F 部署验收 | 持久化、限流、故障恢复和运维；服务器规格/扩容不属于本轮审核前置 |

可二期上线章节不意味着允许现有整章内存生成与新 MQ 竞争同一目标/串行模型。如果不能一次改造现有整章接口以遵守同一锁/持久许可，须在审核中明确整章一起切换或迁移期暂停生成的安排。现有接口可以保留 URL 并适配 MQ，不能原执行路径与 MQ 双轨调用模型。原 Agent 预约场景不迁任务模型，共享图片版本写入仍须回归。

此处“旧写入口”指**当前会启动生图、最终保存图片的接口及其直接执行路径**，不是数据库写接口，也不表示必须更改前端 URL：

| 现有接口 | 当前执行方式 | 接入 MQ 时需统一的行为 |
| --- | --- | --- |
| POST /api/image-gen/generate | ImageGenController 调用 ImageGenService.submit，后台内存执行 | 普通单图也经过校验、幂等、MQ 和统一消费者 |
| POST /api/chapters/{chapterId}/generate-manga-stream | MangaGenerationController 启动整章虚拟线程与 SSE 生成 | 整章逐图接同一 MQ/目标占用/串行许可 |
| POST /api/chapters/{chapterId}/regenerate-image/{imageNumber} | 控制器等待 MangaGenerationService.regenerateImage 返回 | 所有重生成接统一串行通道，不从控制器直接调模型 |

“暂停或统一许可”的目的，是防止迁移期间一边走新 MQ、一边仍从上述路径直接调模型，从而绕过串行和图片防重。实际已保留现有 URL，把处理实现适配为统一 MQ 受理，没有批量关闭接口或返回 410。Agent 预约生图接口不属于本表。

JDK 21 阶段 A 使用慢供应商、慢 MinIO 与并发流量，通过 JFR jdk.VirtualThreadPinned、线程 dump 和池延迟验证 synchronized/native IO pinning；同时验证 600s 阻塞期间续租仍能按期执行。发现 carrier 被长时占用则定位 SDK/同步范围，必要时使用有界专用平台 IO 执行器并保持 ACK 所在线程协议，经验证后再接主流程；不未经审核升级 JDK 或无界增加 carrier。

在原 T01–T35 之外，主方案新增 T36–T47 覆盖键空间/事件/单份状态、入口有界核实、人工/风险恢复、版本全链路及 ABA、租约信号量与长阻塞续期、总预算/退避、drain/pinning、错误 envelope、旧历史过滤、存储写失败和分期回退；T31 明确缺供应商的前置校验。文档静态检查仅证明结构、引用和场景编号完整；这些运行时场景仍需实施阶段真实组件验证。

## 9. 本轮需要审核的取舍

1. 供应商选择：由前端请求明确传入配置引用或临时配置，缺失在参数校验层返回“无供应商”，不入队；config_id 不需要重复携带明文凭据。
2. 调用结果未知：供应商已知、请求已发出，但宕机/断连后无法确认本次调用结果，默认 SAFE；这与缺少供应商参数是两个不同问题。默认禁止未经核实结束就强制放弃。
3. 分期与接口迁移：整章可以后置，但现有直接启动生图的接口不能绕过统一 MQ/串行许可；现有 URL 可保留并适配，含义和接口清单见第 8 节。服务器规格/扩容已按用户意见移出本轮审核。
4. 此项为原审核阶段说明；本次后续授权已修改 AMQP 依赖、Java、前端、新 Flyway 迁移及 compose 模板，实际范围和未验证项见实施记录，尚未生产部署。

## 10. 协议与实现依据

- Redis Cluster Specification 的 hash tag/slot 规则：<https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/>。
- Redis ZSET/Lua/TIME：<https://redis.io/docs/latest/commands/zadd/>、<https://redis.io/docs/latest/commands/time/>、<https://redis.io/docs/latest/develop/programmability/eval-intro/>。
- RabbitMQ quorum/dead-lettering、内存 alarm：<https://www.rabbitmq.com/docs/quorum-queues>、<https://www.rabbitmq.com/docs/memory>。
- OpenJDK JEP 444（JDK 21 虚拟线程与 pinning）：<https://openjdk.org/jeps/444>。

本节列协议参考，实际依赖/部署行为仍以阶段 A 实证为准；不把本轮未完成的线上查询或未执行的集成测试写成通过。
