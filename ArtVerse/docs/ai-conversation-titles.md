# AI 助手会话自动命名

## 问题与方案

原实现只有本地消息截取，没有调用模型生成标题；`AiConversationService.autoTitle` 用 JPQL 更新标题后，持久化上下文仍保留旧的“新会话”，紧接着的 `touch/save` 可能把标题重新覆盖。原有“有意义的消息”判断也会跳过“你好”等短消息。

改为首条非空消息驱动的统一流程，沿用已有标题字段，不需要新增数据库迁移：

1. 新建空会话显示“新会话”，状态为 `DEFAULT / WAITING`。
2. 首次发送非空内容时，锁定数据库中仍使用默认标题的会话，将输入整理为单行、按 Unicode 码点截取前 **24 个字符**，立即保存为 `FALLBACK / GENERATING`。中文和 emoji 不拆开 UTF-16 代理对；不添加额外省略号，不删除可能有意义的代码符号。
3. 在同一事务中发布不可变事件。事务提交后才交给独立后台线程池；事务回滚不调用模型。消息发送和回复生成不等待命名网络请求。
4. 使用用户已保存且启用的 LLM 配置，发起一次无工具的 `/chat/completions` 请求，只传首条消息前 2000 个码点，输出上限 128 tokens，模型请求超时 8 秒。标题清理引号并限制在 24 个码点以内。
5. 成功时保存 `AI / FINALIZED`；配置缺失、请求失败、返回空内容、多行内容或默认标题时，保留已存的消息标题并改为 `FALLBACK / FINALIZED`。不重试，不使用运营方密钥。

| 场景 | 最终行为 |
| --- | --- |
| 首条消息为“你好”等短内容 | 同样触发命名，失败时标题为原消息 |
| 后续继续发送消息 | 不重复命名 |
| 用户预先指定或手动修改标题 | `USER / FINALIZED`，自动任务不能覆盖 |
| 用户在 AI 请求期间改名 | 模型结果通过条件更新丢弃 |
| 同时到达两个首条消息请求 | 数据库按会话串行，只发出一次命名事件 |
| 会话在后台命名前删除 | 更新影响零行，不恢复会话 |
| 后台线程池满 | 立即结束为本地兜底标题 |
| 进程重启或任务中断 | 已保存的兜底标题仍可用；每分钟将超过两分钟的生成状态结束为兜底 |

## 实现位置

- `application/AiConversationService`：首条消息命名、限长处理、手动命名；活跃时间采用字段更新，避免保存旧实体恢复过期标题。
- `application/ConversationTitleRequested`、`ConversationTitleGenerator`：提交后调度、失败收尾和超时状态恢复；跨线程只传 ID、限长输入及兜底标题。
- `ai/ConversationTitleClient`：轻量标题请求，沿用项目的服务商端点安全检查，不加载 Agent 工具、工作区或业务技能。
- `persistence/MangaAgentConversationRepository`：默认标题带锁查询，最终写入仅允许仍为 `FALLBACK / GENERATING` 的行。
- `domain/MangaAgentConversation`：动态更新变动列，归档等无关实体更新不会还原异步生成的新标题。
- `config/TaskExecutorConfig`：标题任务最多并行 2 个、排队 32 个；原执行器保留为默认注入目标。
- `MangaAgentConversationService`：委托统一命名服务，漫画助手与小说助手、图片会话共用规则。
- `frontend/src/components/ChatPanel.tsx`：发送后及加载生成中的会话时刷新标题，每两秒一次、每轮最多 90 次，更新当前标题和历史列表。切换会话/卸载时取消后续刷新；迟到响应保留本地已保存的手动名称。
- `frontend/src/components/InlineConversationTitle.tsx`：自动标题到达时保留用户正在输入的改名草稿。
- `frontend/src/components/ImageGenPage.tsx`、`imageGenModel.ts`：生图主题在首次发送前绑定独立后端会话，默认主题名称不作为用户自定义标题提交；先显示 24 字提示词兜底，再同步后端 AI/FALLBACK 标题。名称来源、状态及未同步的手动命名保存在本地缓存；后台主题同样刷新标题，迟到响应不能覆盖手动改名。旧缓存只有在图片记录指向唯一会话时才恢复绑定，混合历史不猜测归属。

## 验证与边界

针对性测试覆盖：模型响应解析与端点校验、空输入和短消息、中文与 emoji 截断、输入长度限制、模型失败与配置缺失、队列拒绝、默认执行器选择、事务提交/回滚、并发首条消息、旧持久化上下文、重复/迟到结果、手动改名优先、生成状态恢复及无关字段更新。

数据库测试使用隔离 PostgreSQL/pgvector 容器并执行当前完整 Flyway 迁移。当前机器运行命令如下；环境变量仅作用于本次 shell，JUnit 负责关闭测试容器：

```powershell
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
mvn -q '-Dapi.version=1.44' '-Dtest=AiConversationTitleTest,ConversationTitleClientTest,TaskExecutorConfigTest,AiConversationLifecycleIntegrationTest,StoryChatPreparationIntegrationTest,MangaAgentConversationServiceTest,MangaAgentServiceTest,StoryChatRequestPreparationTest' test
mvn -q -DskipTests compile
```

前端验证：`npm run lint`、`npm run build`、`node --test tests/storyChatState.test.ts`。

2026-11-01 验证结果：上述 8 个后端测试类共 **46 个测试全部通过，无跳过**；后端编译通过，前端 lint、构建及 4 个聊天状态测试通过。构建仍提示现有主包体积较大。测试容器已正常退出并清理。

本次不回填历史会话标题。历史默认标题会在下一次发送非空消息时进入该流程。纯临时、未保存的模型配置不会用于后台命名，此时保留消息兜底标题。测试未调用真实付费模型，未验证外部服务商的实际标题质量；不包含部署或重启当前运行服务。

## 生图区补充修复

原生图区创建会话时将“新主题 N”作为显式标题传入，后端因此记录为 `USER / FINALIZED`；此外页面显示本地主题名，没有读取自动生成后的服务端标题。现在空主题仅保留本地占位名，首次发送时创建无自定义标题的 `IMAGE_GEN` 会话；用户提前手动命名时才提交指定名称。会话创建失败时保留本地消息和兜底名，不再使用未指定会话 ID 的全局默认生图会话。

缓存与服务端同步只按会话 ID 更新对应主题；手动命名立即保护本地名称，服务端同步失败会提示，下一次发送前重试。元数据刷新每两秒一次、每轮最多 90 次，刷新页面后也会从服务端补齐标题。此前已被标记为 `USER` 的“新主题 N”无法可靠地区分程序默认名和用户主动设置的同名标题，本次保留其已有用户命名状态，不批量重置历史数据。

新增回归覆盖生图会话独立命名、24 字 Unicode 截断和手动名称保护，以及前端占位名称识别、迟到元数据、非当前主题更新、历史会话绑定恢复和本地缓存持久化。验证命令：

```powershell
node --test tests/imageGenModel.test.ts tests/storyChatState.test.ts
mvn -q '-Dapi.version=1.44' '-Dtest=AiConversationLifecycleIntegrationTest,AiConversationTitleTest,ConversationTitleClientTest' test
```

生图区补充验证结果（2026-11-01）：上述 3 个后端测试类共 29 个测试、前端 13 个测试全部通过；后端编译、前端 lint 和构建通过，隔离数据库测试容器已清理。未调用真实付费生图或命名服务。
