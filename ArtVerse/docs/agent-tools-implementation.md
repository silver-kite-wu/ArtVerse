# 模型工具改造实施记录

日期：2026-11-06。设计依据：`docs/agent-tools-and-registration-design.md`。

本文件保留当前工作区已有改动与历史证据。**用户最新授权为继续实施 C，所有工作串行。** A/B/C 的本地实现与适用自动化验证已完成，生产目录累计 **35 个业务工具**；真实供应商和浏览器产品验收未完成，因此不宣称全部 P01–P38/T01–T30 已关闭。本工作区包含其他未提交工作，不提交、推送或覆盖无关修改。当前结论以本节和方案 12.12 为准，下面按日期保留的历史记录不代表当前缺口。

## 当前状态

- A：9 个业务工具保留。本地契约、SQL 预算、读取证据、可信 capability、原 parent/child owner+fence、四专家/源流取消、领域效果/作者回执关联、普通/后台恢复已落地；实际环境产品验收未完成。
- B：18 个新增工具已进入生产 Registry/Strategy/Toolkit，A+B 累计 27 个；按任务和意图装配子集。分页、权限、历史恢复审核及回执相关本地测试已通过，产品验收未完成。
- C：8 个新增工具已进入同一生产注册链，累计 35 个。设定/人物差异、独立知识候选、图片只读预览与提案、作者决定、真实队列受理、只读状态及安全恢复动作均已完成本地闭环；直接 `IMAGE_GENERATION` 仍未开放。
- 实际 provider 模型调用尚未验证；真实付费生图未发起。图片队列使用真实 PostgreSQL、Redis、RabbitMQ、MinIO 与 mock 供应商客户端验证。

## 当前 C 的串行交付与验证（2026-11-06）

1. **原生注册与意图路由**：新增 `list_generation_models`、`draft_story_settings`、`draft_character_profile`、`draft_knowledge_change`、`preview_manga_generation`、`propose_manga_generation`、`get_generation_request`、`propose_generation_recovery`。它们使用 AgentScope 原生 `@Tool/@ToolParam`、强类型 record/enum、Toolkit/ToolGroup、Guard、Middleware、权限和结果转换，没有手写 JSON Schema、通用执行器或 self-MCP。创作用途按最新用户意图装配；否定/咨询问题不触发写准备。SUMMARY 仍只开放 `get_novel_context`，四专家仍只开放 `get_chapter_context`，自动任务与总结不装配 C 写工具。
2. **设定与人物差异审核**：`draft_story_settings` 仅允许 title/description/manga_style；`draft_character_profile` 显式区分 CREATE/UPDATE，更新必须提供稳定 ID 和精确 base_version。确认在同一事务中比较完整旧值、应用修改并保存新版本；人物同名不自动合并，重复确认返回同一人物 ID。Story/CharacterProfile 增加实体版本，V59 以默认 0 回填既有行，并提供从 V58 升级不改内容的隔离数据库测试。
3. **知识变更候选**：`draft_knowledge_change` 追加独立 `AI_PROPOSAL` 候选，不替换章节提取批次，也不自动确认正式事实。动作 ID 由 request/step/输入/目标/base/source/evidence 稳定派生；候选保存真实当前章节来源快照、hash、类型、引用、目标版本与证据。作者批准前重新锁并核对源，批准/拒绝在 `saveAndFlush` 后发布真实版本回执；存在 native ledger 时关联原候选动作。
4. **图片预览、提案与查询**：模型目录只返回已保存配置的安全元数据、精确配置版本和签名分页，不返回密钥或 URL；预览使用现有服务只读构造真实章节目标、参考图、模式与图序，并以 5 分钟签名 `preview_ref` 绑定作者、范围、会话和原 run。提案再次重算完整快照后持久化，不提交供应商。费用未知保持 `UNKNOWN`，额度仅在提交时检查；`get_generation_request` 只投影真实受理/图片状态和可用恢复动作，不重建状态或泄漏内部命令。
5. **作者决定与真实队列**：首次/重生成提案都由作者卡片确认。服务在事务提交批准回执和不可变 dispatch body 后，状态为 `ACCEPTED` 也明确不是生图成功；实际结果继续从图片队列读取。恢复提案只允许现有用户 API 支持的 `RECHECK`、`REQUEST_REVIEW`、`RESUME_SAFE`、`RELEASE_CONFIRMED`，禁止 `ADMIN_DECISION`、外部结果认证和自动付费重生成。部分受理保留已确认请求，重复确认按同 body/request ID 重试且不重复 dispatch。
6. **数据一致性修复**：分离原 Agent run 身份 `source_request_id` 与图片业务 `request_id`，消除 ID 覆盖；所有提案 checksum 先规范化 JSON 再与 jsonb 语义比较；实体 version 故意保持未初始化 `null`，由 JPA 插入后从 0 递增，避免 Spring Data 将新实体误当 merge。图片测试连接串与生产一致使用 `stringtype=unspecified`，修复真实 jsonb 写入。
7. **产品接入**：聊天按当前章节/会话显示提案卡，任务中心提供本人提案审核入口；卡片展示字段差异、完整图片范围、模型/配置版本、目标 ID/版本、未知费用、安全恢复动作和真实请求状态。确认前必须展开完整范围；过期、冲突、已批准但部分受理和继续原提案都有明确状态。8 个工具加入中文活动标签，漫画/小说运行技能与提示词说明“准备受控提案，不直接执行付款/领域批准”。
8. **自动化证据**：最终串行验证包含 21 个不同测试类、**231 个不同用例**，均为 0 failures/errors/skipped：C 聚焦 `CreativeProposalIntegrationTest` 16、`AgentToolGroupSupportTest` 15；真实队列 `ImageQueueIntegrationTest` 1；共享回归 10 类 93；版本/路由/正文/分镜影响回归 8 类 106。各批用例集合不重叠，不能据此宣称执行了项目全套测试。关键证据日志为 `target/agent-stage-c-final-focused.log`、`target/agent-stage-c-queue.log`、`target/agent-stage-c-regression-shared.log`、`target/agent-stage-c-regression-impacted.log`。
9. **构建和前端**：规定后端 `mvn -q -DskipTests compile` 退出码 0，日志 `target/agent-stage-c-final-compile.log`。前端节点测试 8/8、`npm run lint`、`npm run build` 均退出码 0，日志 `target/agent-stage-c-ui-test.log`、`target/agent-stage-c-ui-lint.log`、`target/agent-stage-c-ui-build.log`；没有浏览器交互验收证据。

真实队列用例在隔离 PostgreSQL、Redis、RabbitMQ、MinIO 中从真实 saved image config、StoryChat run、preview/proposal、作者确认一路到业务提交；断言作者确认前无供应商调用、队列受理后实际状态成功、只读查询不改写 Redis、重复确认不再生图，mock 供应商仅调用一次。C 聚焦用例还通过生产 Registry、原生 guarded Toolkit、Harness 与真实 SQL 连续调用 8 个工具，并完成真实作者设定/人物/知识决策。该证据不等于实际供应商兼容或付费生产验收。

本批没有并行任务、子 Agent、提交/推送、用户数据库访问、真实供应商请求或付费生图；V59 与升级测试只使用隔离 Testcontainers，未迁移用户数据库。直接 `IMAGE_GENERATION` 仍不可用，图片执行只经作者审核后的既有队列。

## 灵境第 2 章 AI 评审故障修复（2026-11-06）

用户指定账号在“灵境”第 2 章发送“评审一下本章节的创作质量如何，有什么优化的方向吗”后，原表现是 0 秒失败并显示“章节内容准备失败”。指定账号仅用于真实浏览器登录测试，凭据未写入源码、测试、文档或日志。

| 故障链 | 根因与修复 |
|---|---|
| StoryChat run 创建失败 | Story 新增 JPA 版本后，异步执行使用只含 ID 的合成 Story 引用但没有真实 `version`，Hibernate 将其误判为临时实体。`StoryChatPreparationService.Scope` 现在保存并恢复真实 story version |
| `recordModelConfig` 懒加载失败 | run 已脱离原事务后先访问 conversation 懒关联。`MangaAgentRunService.lockedClaimedRun` 现在先在事务中读取身份，先锁父执行、再锁子 run，不访问 detached run 的懒关联 |
| 模型能力缺失 | 本地账号使用 `deepseek/deepseek-flash`，但服务端能力表为空。`application.yml` 增加官方 endpoint/model 的精确 `tool-calling=true` 声明；结构化输出仍明确声明为 false |
| AgentScope 技能加载器误判 | 安全边界只接受旧 `SkillToolFactory` 类名，未识别 2.0.4 Harness 官方 `SkillLoadTool`。现同时接受两种官方框架来源，并保留业务工具替换拒绝 |
| 原生读取回执丢失 | 工具进度整实体更新可能覆盖 `novel_reads`，且读取事件登记缺失时模型输入无法补证。进度改为 SQL 定点更新；模型适配层直接对照数据库正文、receipt、范围与调用 ID，补登记缺失的 `RESULT_FORMED` 和 handoff。证据哈希排除可变审计字段，正文与 scope 篡改仍拒绝 |
| `ask_user` 预算授权缺失 | 外部工具暂停事件的 Gateway 上下文不保留 Boundary 的 capability 对象。`MangaHitlTools` 现在注入与 exact call/tool/scope 绑定的 `AgentToolAttempt`，审计服务同时接受原生 attempt 和 capability 证据 |
| 纯评审误路由为改稿 | 消息同时包含“评审”和“优化方向”时，旧规则先命中“优化”并生成 43 处正文修改。Router 现在识别“评审”，只有明确“润色/改写/修改/重写/优化一下”等编辑意图才进入 POLISH；纯质量分析和优化建议进入只读 REVIEW |

中途真实浏览器复测已经验证技能加载、正文读取、模型分析、正文草稿生成和 `ask_user` 可以进入“审阅本章修改”，显示基于版本 1、43 处修改；该测试草稿未被作者保存。修正纯评审路由后，最终复测使用用户指定账号进入“灵境”第 2 章并发送同一问题：运行 `SUCCEEDED`、无错误、进入只读 REVIEW，最终回复 3181 字，未生成待审修改或改变正文。数据库复核章节仍为版本 1、2105 字，revision 数未增加。页面证据保存在 `output/playwright/lingjing-ch2-review-fixed.png`。

故障排查期间创建的 7 条中间失败会话及其对应 Redis state 已按测试数据清理；最终成功会话保留，原有会话和正文未删除。清理直接作用于本次明确识别的临时会话 UUID，没有按标题或时间范围批量删除。

聚焦回归日志：`target/agent-chat-story-version-regression.log`、`target/agent-chat-skill-loader-regression.log`、`target/agent-chat-read-handoff-regression.log`、`target/agent-chat-read-input-regression.log`、`target/agent-chat-hitl-audit-regression.log`、`target/agent-chat-final-focused.log`；对应最新批次均退出码 0。最终编译日志 `target/agent-chat-final-compile.log` 退出码 0。真实 provider 调用形成最终成功回答；未产生图片费用。

## 当前 B 的串行交付与验证（2026-11-05）

1. **注册与隔离**：生产 AgentToolGroupSupport 注入现有 B 薄层对象；Schema、反射调用、结果转换、权限及暂停继续复用 AgentScope。作品/章节索引加入常规创作上下文；知识、素材、候选、历史和计划按最新用户意图选择。正文/分镜写阶段可读取和丢弃草稿；正文恢复只在写阶段加恢复意图开放。SUMMARY 仍仅 `get_novel_context`，四专家仍仅 `get_chapter_context`。13 项注册测试核对跨视图集合精确为 27，C 的 8 个工具不注册；没有常驻加载全部 27 个。
2. **分页和范围**：修复 PostgreSQL 对产物查询可空参数无法推断类型；产物列表增加快照指纹，状态/内容/集合或筛选变化明确要求重读。历史游标绑定作者/章/会话/请求；历史和产物文本按 Unicode 边界分页，显式 start/end/offset_unit/has_more。正式知识详情与检索复用同一章节/角色投影，候选仅为当前作者当前章的未确认提案，素材仅返回已揭示阶段的持久元数据。历史读取不解锁当前正文写基准，跨章读取不改变写目标。
3. **两项新增写操作**：`draft_novel_restore` 和 `discard_artifact` 必须接收 Guard 注入的原生 AgentToolAttempt；原领域事务内校验 SQL 预占并写 `domain_effect`，缺证据则回滚。丢弃有精确 artifact_version、当前 run/step、未进入人工审核和草稿类型限制；重放不改变正文，迟到原生 ERROR 不覆盖领域效果。丢弃后相同内容可生成新的有效草稿，仍受原 3 次草稿上限控制。
4. **数据和输入修复**：新增 V58，仅为既有产物状态约束增加 DISCARDED，保留作者 REJECTED 的独立语义，不修改 V35 等既有迁移。修复 Boundary 把合法小整数 Long 与解析后的 Integer 当成不同输入的问题，统一按 JSON 结构比较，重复键、额外参数和内容篡改仍拒绝。
5. **恢复与产品接入**：明确的历史正文恢复请求进入现有 WRITE 路由，历史查询/功能问题保持只读；服务端取完整历史并生成差异草稿，复用现有作者编辑/确认/放弃页面。真实 PostgreSQL＋生产 Registry＋Harness 用例同时覆盖普通草稿及恢复草稿，验证作者最终文本、RESTORE revision、原 call 领域效果和作者回执、重建 session 后只读总结。更新小说/漫画运行技能、写作提示词、B 工具中文活动标签；活动审计不复制历史正文和草稿原文。
6. **自动化证据**：`target/agent-stage-b-regression.log` 首批 24 类 214 项、0 failures/2 errors/0 skipped；212 项通过，两个错误为新增 Harness 夹具漏 raw JSON 与旧分镜 Schema 名称断言仍按 A。仅修正夹具后，`target/agent-stage-b-native-recheck.log` 两类 25/0/0/0、退出码 0。因此当前 24 类最新报告为 **214 个不同用例，0 failures/errors/skipped**，这是联合批加定向复验，不能加成 239，也不能说首批全绿。此前 B 基线 28/2 failures/1 error，以及中间 65/1 failure/3 errors 均已由修复后报告覆盖。11 类聚焦 `target/agent-stage-b-focused-final.log` 为 80/0/0/0、退出码 0，与最终批有重叠，不累计。
7. **Harness 证据边界**：8 个作品/知识/素材/候选读工具使用真实读取服务＋隔离 PostgreSQL＋Harness；4 个计划新增工具使用真实 AutomationReadService/SQL＋Harness，其中配置解析服务为 fixture，断言 preview 不增加计划/运行/产物/账本。6 个产物/历史工具的原生 guarded Toolkit＋Harness 用例使用领域 mock，真实恢复/保存由上面的完整联合用例覆盖，真实丢弃/CAS/幂等/回滚/快照由 AgentArtifactHistoryIntegrationTest 覆盖；不把分离测试冒充每工具的完整生产闭环。直接 Toolkit DTO 转换返回默认 RUNNING，最终 SUCCESS 在 Agent 循环中核对。
8. **构建**：规定 `mvn -q -DskipTests compile` 退出码 0，日志 `target/agent-stage-b-compile.log`。前端 10 项 Node 测试、lint、build 均退出码 0，日志 `frontend/agent-stage-b-ui-tests.log`、`frontend/agent-stage-b-ui-lint.log`、`frontend/agent-stage-b-ui-build.log`；build 保留既有包体积提示。

本 B 批历史记录没有并行任务、子 Agent、提交/推送、用户数据库访问或实际 provider/付费生图。V58 仅在隔离 Testcontainers 通过 Flyway 验证，未迁移用户数据库。C 在当时尚未启动，后续授权实施与证据见上面的当前 C 段。

## 当前 A 的串行交付与验证（2026-11-05）

- 可信 capability 由实际 run、绑定和数据库租约重建，冻结完整契约/分组及身份、来源、步骤、模型、父执行、专家 task/session、审核 continuation。模型变量不能授权；每次模型/工具调用重新验证。
- 原生最终输入注入 AgentToolAttempt；NativeNovelDraftService、分镜草稿/提交和计划提案在同一领域事务内写入原工具账本 `domain_effect`，缺少预占或 hash/作用域不匹配时回滚。正文/计划作者决定关联实际 `author_review_receipt`，迟到原生错误不能抹去保存证据。
- 作者决定先锁父再锁 root。恢复已保存回执与新作者授权使用不同校验，修复 FAILED 后台恢复被错误拒绝。四专家取消直接取消尚未响应的模型源流，output/cancel 预先核对 task 归属。
- 正文新增真实 PostgreSQL＋生产注册＋Harness 联合用例：读取→草稿→原生 external 暂停→作者改写保存→重建原 session→总结。核对正文/revision/领域回执，模型收到作者最终稿，总结只开放 `get_novel_context`，旧调用被拒绝。该用例用内存 session；Redis/CAS/投递恢复由独立测试覆盖。
- 分镜真实 Harness 用例现已完成全部分页→草稿→`commit_storyboard`→正式正文及两条原调用效果回执。计划数据库用例验证精确作者决定、重放、迟到 ERROR 后保留已保存证据，以及缺少原生预占时整笔提案回滚。
- **后端 40 类最新报告共 317 个不同用例，0 failures/errors/skipped。** 联合批 `target/agent-stage-a-final-regression.log` 最初为 317/2/0/0；315 项通过，两项生命周期失败来自 mock 未模拟 Factory.closeAgent。夹具修正后 `target/agent-stage-a-lifecycle-tests.log` 3/0/0/0、退出码 0。这是联合批加定向复验，数量不能相加。规定编译 `target/agent-stage-a-final-compile.log` 退出码 0。
- **前端 10 项测试、lint、build 均退出码 0。** 日志 `frontend/agent-stage-a-ui-tests.log`、`frontend/agent-stage-a-ui-lint.log`、`frontend/agent-stage-a-ui-build.log`。build 保留现有包体积提示。当前没有浏览器交互或真实供应商验收证据。
- 该 A 批历史记录没有新增迁移，当时最新仍为 V57；没有提交、使用用户数据库、请求真实供应商或付费生图。生产当时只发布 A9，B/C 由后续授权批次扩展。

## 前一批授权实施记录（历史）

- 具体新增行为及边界见设计 12.9。均复用原生 Toolkit、ToolResultBlock、暂停恢复、SubagentsMiddleware 与 TaskRepository；项目约束集中于持久身份、作品范围、产物授权、版本/事务与有限预算，没有重建自调用 MCP。
- 本次中间回归：43/1 failure（绝对截止取消源流缺陷）；171/7 failures/14 errors（接口和执行身份联动）；81/0/14（JSONB 夹具及计划状态约束）；223/1/10（原生非法消息夹具与父执行校验 Mockito 重载）。这些失败均保留原报告；当前修复后联合批正在验证，结果须按该批 XML/退出码单独记录，不相加。
- 前端计划审核卡片：`npm run lint`、`npm run build` 通过；现有 auth/StoryChatActivity Node 测试 10 项通过。build 存在体积提示，不影响此次退出码。后续前端变更须重跑适用检查。
- 当前迁移仍为 V57；没有因计划审核新增状态迁移，正式表使用已有 DRAFT 并由 DTO 投影等待审核。没有实际 provider 或真实付费调用。

## 历史方案复核与失败（2026-11-05，仅文档时点）

- 已核对现有源码、AgentScope Java 2.0.4 sources JAR 及已有测试报告。本轮只修改两份文档并收取此前联合回归进程结果，未新增业务代码、迁移、前端改动、测试/编译进程或实际 provider 请求。已有工作区实现全部保留。
- `target/agent-reading-claim-regression-tests.log` 退出码 **1**，25 类共 **228 tests / 0 failures / 1 error / 0 skipped**。分镜类 14 项/1 error，其余 24 类共 214 项无失败或错误；不能宣称全批通过。唯一错误为 `nativeHarnessReadsAllPagesThenDraftsUsingObservedReadReference`，在 Boundary.verifyTool/onActing 被 `TOOL_NOT_ALLOWED: tool unavailable or changed` 拒绝，根因尚未定位。
- 本批含 Orchestrator5/ExecutionSupport7/Director8；新独立 `MangaWorkflowExecutionClaimTest` 5 项不在选择列表内，未由该报告验证。没有本批最终 compile 成功证据；94/76/66/49 等先前批次各自保留，不与此次相加，也不替此次背书。
- 方案新增 2.6 全问题现状/裁决表、6.13 原生注册最小适配与失败处理、7.14 计划写入/取消/版本的事务规则、10.5 最短合法链/剩余预算/活动时长及读取证据口径、12.8 最新失败记录。38 项问题及 30 组验收沿用原编号，目标仍为 A9→B27→C35。
- 后续修订待本轮审核。先定位合法分镜被拒原因，使用生产 GroupSupport 装配修复并保留篡改拒绝负例；再按 A0–A6/B/C 依赖执行，不删除校验、跳过失败或重写已有预算/提取基础来取得通过。后续实际变更再运行受影响测试、联合回归及规定 compile。

## 当前授权实施：读取与执行边界收口（2026-11-05，持续推进）

- 生成配置已改为不可变 ModelLimits，并在现有 SQL 预算 root/step 元数据冻结输入/输出 token、字节及单次模型超时上限；重建/新增 step 不能放大，配置只可缩小，调用中配置变大不能放宽既有输出流。旧 root 缺少该证据时拒绝沿用，不补造历史配置。运行剩余时长、可信 profile 与专家仍待实施。
- 知识提取使用现有 outbox.payload 固化 PREPARED 输入版本/hash 与 COMPLETED 候选 ID（包括空结果）；保存事务锁动作 fence/章节并核对同一来源。取消静默 48000 字符截断，超限、来源变更、旧 fence、缺失结构化结果均保留旧候选；同动作重放复用原批次。未新增迁移或分块调度器。
- `list_automations` 复用原生 ToolParam，提供可选 status/task_type/cursor/limit，返回 items/next_cursor/has_more/snapshot；在只读 REPEATABLE_READ 中先过滤当前作品，按 created_at/id 稳定分页，跨页结果集变化明确拒绝。实时 SQL 验证归属；Base64 游标不是授权凭证，其携带的过期时间不作为不可篡改授权证明。
- AgentToolInvocationGuard 在原生合并 param.input/preset 后，比较 raw、block.input 和最终 input，再脱离嵌套可变数据并委托原生工具。保留 Schema/strict/outputSchema/权限和 external 等属性；请求级替换工具关闭，正常注册的 external 问答保留。没有引入执行器或 Schema 引擎。
- `target/agent-generation-reading-focused-tests.log` 已收取退出码 0：SQL27、预算3、原生结构化5、普通往返1、知识提取5、知识数据库28、outbox1、任务分页6，共 **76/0/0/0**。`target/agent-native-final-input-tests.log` 已收取退出码 0：Guard8、Group11、Boundary12、往返1、Factory5、SQL27、任务工具2，共 **66/0/0/0**。包含隔离 PostgreSQL 和真实原生调用；两批有重叠，不相加。
- 该批后续加入分镜实际读取结果登记、显式 read_ref/完整覆盖、持久交互来源和漫画原 claim 透传；最新联合回归有 1 error，见本文顶部及方案 12.8，最终 compile 尚缺证据。第一轮生成快照编译曾因两处遗留 getter 名称未改失败，已修正并通过上述聚焦测试的编译阶段。

## 当前授权实施：模型请求与原生结果观察（2026-11-05）

- BudgetedAgentModel 使用框架 JsonCodec/公开 Builder 深度封存 messages、嵌套工具 Schema、responseFormat；检查、摘要与原生 delegate 使用同一请求。拒绝 apiKey/baseUrl/modelName/endpointPath 和扩展 maps 覆盖；两种输出 token 字段择一且为有限正值，推理预算不超过输出上限，保留更短超时，原生网络尝试固定为 1。
- 原生 ChatUsage 保留最后累计报告，重复不相加；缺失标记 UNAVAILABLE，非法或倒退标记 INCONSISTENT，原生默认零值不冒充精确账单。输入序列化字节、输出增量字节、code point 估计分别标记；Unicode 代理对跨块计量已覆盖。
- Boundary 按具体 call/name/输入摘要观察原生 ToolResultEnd；成功、失败、拒绝、中断、external RUNNING 分别记录。未知结果与观察 SQL 故障不退款、不重执行、不覆盖原错误；子任务结果不完成或停止父调用。整批原生 DENIED 通过原生 RequestStopEvent 停止，部分拒绝保留合法成员执行。原生成功不等于领域已提交，关联正式回执仍待补。
- 预算根新增持久 run_type 与任务 family 检查，拒绝 StoryChat 异常 DIRECTOR，新增 step 不恢复满额。完整可信 profile/父 owner/fence/四专家未完成，P28/P38 保持开放。
- 最终日志 target/agent-model-observation-final-tests.log，进程退出码 0：SQL 24、预算 3、Boundary 11、Factory 5、生命周期 3、状态恢复 12、结构化 5、原生往返 1、分镜 9、后台答复 10、正文审核 11，共 **94 tests / 0 failures / 0 errors / 0 skipped**。包含隔离 PostgreSQL/Redis、真实 Harness 和 OpenAIChatModel→本地 JDK HTTP 服务；未请求实际 provider 或付费生图。编译 target/agent-model-observation-final-compile.log 退出码 0。
- 中间 38 项回归曾有 1 项失败：原生 DENIED 后继续模型请求，最终 MODEL_STOP。已通过上述原生停止事件修正，并经单例及 92/94 项回归复验。各批次存在重叠，不累加。

## 先前方案复核（2026-11-05，仅文档，历史记录）

- 方案 2.3/12.5 校正后台受理/消费、V56/V57、SQL 预算、原生整批预检、Model 委托及旧活动提示词的当前状态；不再将已有代码写成待首次实现。
- 方案 2.4 将 38 项问题落到 A0–A6、B/C 的具体实施单元，记录依赖、框架复用、改动落点和验收门槛；35 工具及 30 组验收范围不变。
- 方案 6.1/6.12 明确框架/业务职责和现有能力 record 的扩展；工具定义、Schema、执行、权限、external、专家和状态 API 继续使用 AgentScope，项目补归属、授权、版本、幂等、预算及跨存储证据。不增加通用目录、执行器或确认引擎。
- 方案 2.5/10.4 对六项新增细化给出修改规则和验收：原生生成选项覆盖、工具预占与执行脱节、真实 usage、父子执行权/profile、导演预算适用范围和输入深度封存。当前 SQL 预占/原生预检保留，不能用局部报告宣布这些剩余项已完成。
- 研发切换继续移除旧工具别名/直写分支/self-MCP/无用开关和旧模型状态；保留作者作品、编辑器命令、历史产物/回执和计划，不维护双注册/旧 Schema 长期兼容。
- 本轮只读取既有源码/报告并收取先前预算进程完成结果（退出码 0），未新启动编译、测试、迁移、容器、浏览器或 provider 请求。19:28 的九类报告共 68 项零失败/错误/跳过，与历史 111/34 分批记录，不累计；详见方案 12.5。

## 19:28 预算基线（2026-11-05，历史记录）

- V57 已扩展现有 ledger，根按 user/root 唯一，attempt 按 user/root/kind/id 唯一；历史 ledger 不补造 root/attempt。AgentBudgetService 使用 REQUIRES_NEW 短事务，撤下 Redis 权威计数，全部 12 个 taskType 有限映射；同批工具一次事务预占，旧预占不成为再次执行许可，UNKNOWN 不退额。
- Boundary 已复用公开 ToolValidator 做整批预检，非法第二项不预占整批；Factory 安装原生 Model 委托，覆盖主请求、两次实际 Harness compaction 和 native→synthetic 两尝试；单个冷流二次订阅拒绝。Model 名称/上下文窗口/两项 native 能力完整透传。
- 既有预算回归日志 target/agent-budget-regression-tests.log，已收取进程退出码 0。2026-11-05 19:28 的 SQL14、预算3、Boundary10、Factory5、结构化5、普通往返1、分镜9、后台答复10、正文审核11，合计 68/0/0/0。隔离 PostgreSQL/Redis＋脚本 Model；实际 provider/付费调用未验证。各类范围及中间失败修复见方案 12.5。
- 当前不足：工具预占仍为 RESERVED，未闭合原生结果/领域效果关联；原生 provider usage 未保存；maxCompletionTokens/additionalBodyParams 等未完整约束；完整来源/profile/父 owner/fence/专家 child 及输入封存待补。rootLimits 的 DIRECTOR 判定也需加入持久 family/run_type/profile 约束；正常 StoryChat 新建当前传 CONVERSATION，不宣称正常聊天必然已放大额度。
- 本节只登记已存在的代码和证据，不表示本轮实施了新业务修改。A1/A2 及 P28/P38 不关闭；下一步仅在审核后接续现有实现，不重复建立第二套预算基础或修改 V57。

## 既有授权实施：后台答复与动作取证（2026-11-05，局部完成）

本节为 18:46–18:51 的历史实施记录；涉及“预算尚待实施”等结论由上面的当前基线覆盖。

- 基线及本批修改后均执行规定 `mvn -q -DskipTests compile`，退出码 0；日志为 target/agent-automation-baseline-compile.log、target/agent-automation-implementation-compile.log。
- AutomationService.respond 已在计划→automation run→Agent run 锁序下调用 acceptBackgroundAnswer；原问题 ACCEPTED、动作和队列 response 同一事务提交，模型/gate 仍在事务外。非空 response 槽禁止覆盖，旧 key 先核对原 payload，再判断当前阶段。
- 新增 V56，保留 V55；action 固化 action_kind、payload_hash、target_binding，绑定原 child/step/profile/session/pending/accepted action 或不可变审核 receipt。retry 新动作冻结阶段/输入摘要，相同 key 重放不随阶段推进改摘要；失败总结保留原 response 动作。
- AutomationStore.consumeResponse 已改为短事务校验精确动作/父租约及 child 的持久证据，清槽和 consumption_evidence 原子提交；responseConsumed 核对同目标凭证。新增约束拒绝新动作只有消费时间而没有证据的半状态；历史动作不补造新证明。
- executor 的 child lease 裁决已改用数据库；已有完成证据可在不加载 provider 的情况下清理响应；模型失败但原生已记录答案时仍尝试消费，保留原失败。漫画不再以 resume 返回作为消费依据，其完整原节点恢复仍未完成。
- 已新增 executor 的证据缺失、CAS false、旧时钟、已完成离线清理、模型失败取证等用例，以及隔离 PostgreSQL 的事务回滚/具体目标/清槽原子性/Q1-Q2/过期/约束测试。首轮 24 项有 8 项数据库夹具错误（用户名超长），修正后数据库 8 项通过；随后一次扩大回归因泛型推断及缺少 import 未通过 test-compile，修正后 15 类 111 项通过，进程退出码 0，日志 target/agent-automation-regression-tests.log。
- 此后补并发两响应 key 和 V54→V56 升级，数据库组增至 10 项。最终聚焦 executor 7、后台答复数据库 10、正文审核数据库 11、漫画对话 6，共 34 项零失败/错误/跳过，已记录退出码 0；日志 target/agent-automation-final-focused-tests.log，XML 时间 18:50:53–18:51:12。最终 compile 已记录退出码 0（target/agent-automation-final-compile.log）；本轮仅读这些证据，不重跑。111 与 34 有重叠，不相加。
- V56 升级测试保留旧 action 且不补造证明；后台消费测试的原生 proof 由真实 recordNativeAnswer 建立，该类不执行 Harness/Redis。另有真实 PostgreSQL＋Redis＋Harness 普通答案恢复用例，仍不能据此宣称正文审核/浏览器/全部入口端到端通过。
- 该历史批之后，SQL 权威有限预算、全入口/终态取证和四专家已继续实施，B/C 也已由后续批次接入；本历史记录不能代替当前 35 工具的产品验收。未使用用户数据库或发起实际 provider/付费生图。

## 设计复核基线（2026-11-05，实施前历史记录）

本节为 18:14 时点的只读复核快照，后续实施状态以本文“当前状态”“当前工作区预算基线”及方案 12.5 为准；下文“尚未调用/未受测”等表述只描述历史时点，不能当作最新缺口。

- P31 的 scoped state、稳定双读、原子 owner guard 和资源作用域已有代码；当前状态 XML 为 12 项通过，仍不代表 SQL 提交/取消、shutdown、四专家及全部入口通过。
- 本轮解析 18:13:52–18:14:07 的五份投递 XML：Hydrator 5、RunService 19、StoryboardArtifactIntegration 9、后台恢复 5、请求准备 10，合计 **48 tests / 0 failures / 0 errors / 0 skipped**。已有日志 target/agent-delivery-focused-tests.log；本轮没有重新获取该历史进程退出码。
- 新联合用例使用隔离 PostgreSQL＋Redis＋真实 Harness，验证受理后领取失败不丢答复、有效 lease 不重领、过期重领，以及原生已保存/SQL 补证失败后重建补证、无额外模型请求。该场景是普通答案投递，不能写成精确作者正文已完整保存或产品已验收。
- 另七份 18:08 报告为状态 12、生命周期 3、Factory 5、ModelSpecFactory 3、普通往返 1、结构化 5、Boundary 8，共 37 项零失败/错误/跳过。不同时间的报告分开记录，不与历史 41 项累加成一次全套回归。
- P38 的生产模型声明及 run 绑定已有报告；完整 endpoint SHA-256/taskType＋step slot 是后续细化，不自动由旧报告背书。全部 root/step/child 绑定、预算 Model 委托和实际 provider 仍缺证据；默认声明列表为空时不能宣称产品模型已可用。
- 7.13.1 已有 acceptAnswer/claimAnswerDelivery、AgentAnswerDelivery、精确结果核对及本轮 disableCompaction/disableToolResultEviction 实现，不能再记为完全未实施。补证后恢复压缩、Q2/迟到终态、漫画全部入口和完整产品仍待验收。锁定源码不支持通过 public saveAgentState 中途保存来安全更新活跃 call 的 loadedVersion，保留原生最终/失败保存。
- **最新未受测代码**：acceptBackgroundAnswer（respond 尚未调用）、isNativeAnswerRecorded 后台分支、AutomationStore 消费 CTE/responseConsumed、executor 消费与失败恢复、V55。executor 仍使用本机时钟判断 child lease、漫画 resume 返回即消费；retry 仍固定 retry:runId。旧报告不能为这些代码背书。
- 方案 2.3、7.13.2–7.13.3 补齐：后台受理/action/入队同事务及固定父子锁序，response 空槽保护、具体目标与消费凭证、清槽原子性、CAS false 分类、先查旧 key 再冻结新阶段摘要、数据库时钟、原生最终回答/业务成功分别取证，以及所有终态回调绑定原 continuation/step/fence。
- 这是较早时点记录：当时 10.2 的 SQL 权威有限预算/唯一尝试与订阅边界、四专家、B/C 尚待实施，后续 A/B/C 批次已覆盖本地实现；旧活动提示词清理和历史状态隔离是由后续批次完成的现有事实。
- 历史 16:57 的 45 项/1 失败与 17:34 的 41 项通过继续作为历史记录保留；该历史时点没有整项产品验收关闭。当前 A/B/C 本地证据与剩余产品边界见顶部及方案 12.12。

## 已有实施改动与历史验证

1. 先执行 `mvn -q -DskipTests compile`，退出码 0；此编译发生在本轮后续回执改动之前，不作为最新改动验收。
2. 删除无调用方的 NovelContentArtifactService.commit/reject；模型正文保存统一走作者决策。保留编辑器正文保存/恢复。
3. 作者决策先锁 run，再锁 artifact/chapter；固化最终稿、保存时版本、是否产生变更及真实 revision 关联。重放不借用后来正文版本，放弃同样固化决策时版本。缺证据的历史回执明确报错。
4. NovelDraftReviewService 从已保存领域结果投影回执，支持读取结果后修复缺失投影；不再次 review/save。后台 reviewed/失败重试复用该核对。
5. 总结 continuation 绑定具体产物和 fencing；使用专用完成入口，普通 SUCCEEDED 不顺带完成另一总结。后台完整领取/消费协议仍需继续完善。
6. 已有回执单测与数据库报告：NovelDraftReviewIntegrationTest 9、NovelDraftReviewServiceTest 5、NovelContentArtifactReviewTest 8、MangaAgentRunServiceTest 17、StoryChatRequestPreparationTest 10、StoryChatBackgroundResumeTest 5，合计 54 项零失败/零错误/零跳过。本次方案复核仅读取报告，未重跑；不能视为后续所有试验及当前源码通过。

## 实施前方案复核：仅文档（历史记录）

本节保留较早的方案复核；其中“有限预算待实施”等状态由本文当前基线覆盖，不是本轮结论。

- 核对现有源码与锁定 AgentScope 2.0.4 源包。方案保留 P37/T29 的原生结构化输出设计，新增 P38/T30：生产 Model 将所有兼容接口的两项 native 能力固定为 true，需按具体配置声明并由预算委托透传。
- 校正已有改动：Gateway 外层重试残留和专属配置已撤下；Hydrator 已拒绝无答案待答续接；结构化入口已限 taskType/空 Toolkit，并接入单次 AgentStructuredOutput 辅助契约。以上是已有工作区内容，本轮未改代码。
- 任务中心普通问题已共享 AgentQuestion 并携 call/version；executor 已用 asText(null) 修复选项分支，同题 key/双击控制已有实现。仍未完成漫画旧裸协议、原节点续接、同步旧 fence、可靠投递、CAS false 处理、阶段重试摘要及浏览器联合验收。
- 方案进一步明确全部 12 个 taskType 的有限持久预算根、技能调用额度、SQL 唯一预占与主模型/压缩/专家的同一 Model 计数入口；补 param/preset 后续覆盖、知识提取实际来源快照/超限保留旧批次、Gateway 创建后异常释放与句柄唯一登记。
- P31 复核补充：原生 Redis state 已有版本 CAS，但 ReActAgent 默认 OVERWRITE，Harness Builder 未转发 FAIL；当前直接调用又不经过内置 Gateway 的 session gate。方案复用 SessionTurnGate/TurnLease 与版本存储，仅补跨 worker gate 和严格冲突拒绝适配，要求真实双 Harness 旧保存/close 不能覆盖新 state。
- 本轮静态解析到 20 份现有测试报告。16:21:49–16:21:52 九份聚焦报告共 53 项零失败/错误/跳过，含结构化四路径、选项 DTO/executor、原生恢复及相关服务；详细数量/限制见方案 12.2。预算为 mock，正文往返仍模拟领域；分离的数据库报告不证明原生持久消费。
- frontend/agent-tools-build.log 已有成功输出和包体警告；lint 日志有 eslint 执行记录，未见错误输出。本轮未重跑，不能据此标记浏览器/完整产品验收通过。
- 该历史轮次只修订两份文档，没有修改业务代码、运行构建/测试、迁移或调用实际 provider。四专家、有限持久预算及 B/C 已由后续授权批次实施，不能把该旧时点当作当前缺口。

## 证据边界

2026-11-05 14:51–14:52、16:21、16:57、17:34、18:14、18:46/18:51 的数字均保留历史语义，见方案 12.2–12.4；当前预算回归见 12.5 的 19:28 报告，各批次不累加。普通答案已有 PostgreSQL＋Redis＋Harness 联合恢复，精确作者正文领域保存与 Harness 仍未在同一用例串联。既有 Docker 测试使用隔离容器；本轮仅读源码/日志/报告和既有完成结果，未新启容器、使用用户数据库或重新执行这些测试。

## 问题和验收跟踪

下表保留问题分组；其中可信 capability、四专家、分镜、正文往返及 C 提案链的旧“待补”描述已经由顶部当前记录及方案 12.10–12.12 更新。全部产品 P/T 仍不能因自动化通过统一关闭；A/B/C 本地功能已接入，真实供应商和浏览器验收仍开放：

| 问题范围 | 当前工作及剩余要求 | 验收 |
|---|---|---|
| P01–P10、P20–P22、P27 | 接续本地注册、分组、Schema、默认能力与领域边界；仍需清理、组合、输入及端到端验收 | T01–T06、T13–T16、T19 |
| P11–P16、P30 | B 读取/版本/历史及 C 设定/角色/知识/图片提案已接入；本地分页、来源、版本、作者决定和队列证据通过，真实供应商/浏览器产品验收未完成 | T07–T10、T13、T15、T22 |
| P17–P19、P28–P29 | root/attempt SQL 有限预算、原生整批预检/预占及主/压缩/fallback 委托已有局部实现；已补生成选项/原生 usage/结果观察和持久生成上限，继续可信 profile/父子执行权、领域回执关联、四专家、取消/提交及生命周期 | T11–T12、T20–T21、T30 |
| P23–P26、P31–P34 | 状态保护/普通投递及后台受理入队、目标凭证/清槽原子性已有局部及联合证据，reviewed 阶段重放已有数据库测试；待补普通失败答案 retry、来源/profile、旧回调 fencing、最终/业务完成分证、后续压缩和全部入口恢复 | T17–T18、T23–T26 |
| P35–P36 | 普通问题共享协议及选项局部测试已有；待补漫画旧分支/原 call 恢复、可靠消费和全部产品回归；保留已关闭的自动补错与无答案拒绝 | T27–T28 |
| P37 | 原生辅助契约及 SQL native→synthetic 两尝试已有报告；固定调用 DTO、完整属性、选项冻结和异常释放未整体验收 | T29 |
| P38 | 精确能力声明、完整摘要/step slot、SQL 模型身份绑定及委托透传已有实现/局部报告；可信 profile/全 child、实际生成选项及 provider 待最终验收 | T29–T30 |

## 尚未完成的验收

1. A6：真实供应商配置下的工具与结构化兼容，以及浏览器聊天/任务中心的刷新、审核、取消和恢复交互；目前只有本地自动化、API/事务及构建证据。
2. 全部 P/T 的产品验收不能统一标完成；有新增业务变更时只重跑受影响检查。
3. B/C 均需真实供应商与浏览器产品验收；当前 C 已有本地生产 Registry、隔离数据库、真实四组件队列和前端构建证据，不能据此关闭实际 provider/T15/T27 等产品门槛。

## 逐工具状态

下表由规范目录初始化，记录每个工具允许的 taskType/profile/来源、实际 Schema、Harness 往返、领域结果及拒绝/幂等证据、适用验收编号与 provider 范围。框架 skill/专家工具另行记录，不计入 35 个业务工具。没有记录的证据表示未验证。

| 工具 | 阶段 | 实现状态 | Schema/Harness/领域证据 | 实际 provider |
|---|---|---|---|---|
| `get_story_context` | B | 已注册，本地验证通过 | 原生 Schema/Harness＋真实知识读取 SQL；分片/归属 | 未验证 |
| `list_chapters` | B | 已注册，本地验证通过 | 原生 Schema/Harness＋真实章节目录；稳定快照/写目标不变 | 未验证 |
| `get_novel_context` | A | 已注册，本地验证通过 | Schema/最终输入/读取 handoff；真实 PostgreSQL＋Harness 正文联合往返 | 未验证 |
| `get_chapter_context` | A | 已注册，本地验证通过 | 完整分页/read_ref/handoff；真实 Harness 分页→草稿→正式提交；缺页/旧源拒绝 | 未验证 |
| `search_story_knowledge` | B | 已注册，本地验证通过 | Harness＋真实投影；章节/知情角色/分页约束 | 未验证 |
| `get_knowledge_detail` | B | 已注册，本地验证通过 | Harness＋同一投影详情；直接 ID 不绕过未来/私有事实 | 未验证 |
| `get_character_context` | B | 已注册，本地验证通过 | Harness＋角色/有向关系投影；不返回未投影描述 | 未验证 |
| `list_reference_assets` | B | 已注册，本地验证通过 | Harness＋真实元数据；已揭示阶段/无路径凭证 | 未验证 |
| `list_generation_models` | C | 已注册，本地验证通过 | 生产 Registry＋原生 Harness；真实 SQL 安全目录/精确版本/签名分页，不含凭据 | 未验证 |
| `get_artifact` | B | 已注册，本地验证通过 | guarded Harness（领域 mock）＋独立 SQL 范围/分片/脱敏 | 未验证 |
| `list_artifacts` | B | 已注册，本地验证通过 | guarded Harness（领域 mock）＋SQL 快照/筛选/分页 | 未验证 |
| `discard_artifact` | B | 已注册，本地验证通过 | guarded Harness（领域 mock）＋SQL CAS/原调用回执/回滚/幂等/V58 | 未验证 |
| `draft_novel_content` | A | 已注册，本地验证通过 | 原生隐藏 attempt＋SQL 预占；草稿/效果同事务；作者编辑保存→原会话只读总结 | 未验证 |
| `list_novel_revisions` | B | 已注册，本地验证通过 | guarded Harness（领域 mock）＋真实历史 SQL；意图/作用域 | 未验证 |
| `get_novel_revision` | B | 已注册，本地验证通过 | guarded Harness（领域 mock）＋SQL 分片；历史不作当前基准 | 未验证 |
| `draft_novel_restore` | B | 已注册，本地验证通过 | 生产 Registry＋SQL＋Harness→作者编辑保存→RESTORE revision→只读总结；缺预占回滚 | 未验证 |
| `draft_structured_storyboard` | A | 已注册，本地验证通过 | 真实 Harness 草稿/领域效果；完整读取及旧源/跨运行/基准拒绝 | 未验证 |
| `commit_storyboard` | A | 已注册，本地验证通过 | 真实 Harness 提交＋正式分镜＋原调用回执；原父/子租约及取消/旧版本 SQL 用例 | 未验证 |
| `draft_story_settings` | C | 已注册，本地验证通过 | 生产 Registry＋原生 Harness＋真实 SQL；base_version/完整旧值/作者确认回执/回滚 | 未验证 |
| `draft_character_profile` | C | 已注册，本地验证通过 | CREATE/UPDATE、稳定 ID/版本、同名不合并、实际人物 ID 重放；真实作者决定 | 未验证 |
| `draft_knowledge_change` | C | 已注册，本地验证通过 | 独立 AI_PROPOSAL 批次、真实来源/hash/目标 CAS、批准/拒绝实际版本回执 | 未验证 |
| `list_knowledge_candidates` | B | 已注册，本地验证通过 | Harness＋SQL 当前作者/章/未确认标识/源变化 | 未验证 |
| `get_knowledge_candidate` | B | 已注册，本地验证通过 | Harness＋SQL 版本/源 hash/过期；不改审核状态 | 未验证 |
| `preview_manga_generation` | C | 已注册，本地验证通过 | 只读真实目标/配置/参考图；签名绑定原 run、篡改/过期/跨范围拒绝；零供应商 | 未验证 |
| `propose_manga_generation` | C | 已注册，本地验证通过 | 完整快照重验后持久化；作者决定→事务回执→真实队列受理→只读状态/幂等 | mock 仅受理一次 |
| `get_generation_request` | C | 已注册，本地验证通过 | 生产 Registry＋真实 SQL/队列状态；作者范围、未知状态和内部命令脱敏；只读不改 Redis | 未验证 |
| `propose_generation_recovery` | C | 已注册，本地验证通过 | 仅用户 API 当前可用动作；recovery_version/目标校验、作者回执、禁止 ADMIN/自动付费重生成 | 未验证 |
| `preview_automation` | B | 已注册，本地验证通过 | 原生 Schema/Harness＋真实服务/SQL（配置 fixture）；签名/过期/无副作用 | 未验证 |
| `create_automation` | A | 已注册，本地验证通过 | 原生 Schema/边界＋领域提案与精确作者决定 SQL；call/效果/作者回执；迟到错误/回滚用例。完整真实领域 Harness 产品链未验证 | 未验证 |
| `list_automations` | A | 已注册，本地验证通过 | 原生 Schema/调用及分页、归属/快照/持久交互来源数据库证据；完整 Harness 产品链未验证 | 未验证 |
| `get_automation` | B | 已注册，本地验证通过 | 原生 Schema/Harness＋真实 SQL；作品/作者/配置展示脱敏 | 未验证 |
| `update_automation` | A | 已注册，本地验证通过 | 原生 Schema/边界＋精确版本/目标/hash 的编辑/暂停/恢复/归档 SQL。完整真实领域 Harness 产品链未验证 | 未验证 |
| `list_automation_runs` | B | 已注册，本地验证通过 | 原生 Schema/Harness＋SQL；签名游标/快照/过期/稳定排序 | 未验证 |
| `get_automation_run` | B | 已注册，本地验证通过 | 原生 Schema/Harness＋SQL；真实产物引用/逐图状态/截断与错误脱敏 | 未验证 |
| `ask_user` | A | 已注册，本地验证通过 | 正文真实领域/原生暂停→作者结果恢复；普通 Redis 投递恢复；漫画原节点/后台受理消费及完成证据；浏览器产品交互未验证 | 未验证 |
