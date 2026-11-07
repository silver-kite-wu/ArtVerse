# 生成图片直接上传 MinIO

> 历史实施记录：2026-06-06 的 MinIO 图床已被 2026-11-06 的阿里云 OSS 迁移取代，运行时对象存储实现见 [minio-to-oss-migration-design.md](minio-to-oss-migration-design.md)。本文中的 MinIO/SDK/测试描述仅代表当时实现。

日期：2026-10-25；2026-10-26 增补 MQ 复用状态。原存储链路为独立授权改造；即时 RabbitMQ 链路现已接入，见 [实施记录](image-generation-implementation.md)。下文原验证记录保留其当时范围。

## 已实现的链路

供应商 JSON → Base64 解码或 URL 下载 → 图片格式/大小/尺寸校验 → 原始字节输入流上传 MinIO → 保存现有图片记录。

- `GeneratedImage` 持有图片字节和 Content-Type，可重复打开内存输入流，不再持有本地输出 Path。
- `WebClientImage2Client` 使用显式内存 ImageIO 输入缓存，检测真实格式并解码验证；不重新编码 PNG，不创建生成结果临时文件。
- 当前 WebClient 供应商响应/URL 聚合上限调整为 64 MiB；原图校验原有字节上限仍为 128 MiB，实际还受响应聚合限制；即时 MQ 另限制单图输出 32 MiB。Base64 JSON 存在编码开销。图片像素上限为 4000 万，解码前核对尺寸。
- PNG/JPEG 使用当前 JDK 解码器校验，保留原始字节；WebP 只有运行时安装对应 ImageIO reader 时可通过校验。本次不增加 WebP 插件。GIF/BMP 等不在当前图片资源接口允许的格式中，明确拒绝。
- 对象扩展名与 Content-Type 由真实格式决定，不信任结果 URL 后缀或下载响应头；JPEG 不再伪装为 PNG。
- `ObjectStorageService.put` 使用输入流、明确字节长度和 Content-Type；调用方负责关闭输入流。MinIO 使用 `putObject`，现有 `putPng(Path, ...)` 保留给参考图等既有文件上传场景。
- 普通生图、整章生图、重生成、预约漫画和定时单图的共享输出路径均已适配。预约调度、重试和权限检查沿用现有逻辑；参考图仍可使用既有临时文件。
- 图片解析错误日志不再输出原始响应，避免将 Base64 或签名 URL 写入日志。

## 边界

本次是图片字节缓冲直接上传，仍会在内存中聚合单张结果，并解码一次校验；不是供应商直接写 MinIO，也不是端到端无缓冲流传输。像素/字节限制用于约束单图，整体并发仍按各入口已有规则控制。

原独立存储改造没有实现 RabbitMQ/ACK。后续即时 MQ 已加入持久候选登记、内容核对、上传有限重试及提交恢复，仍复用原始字节，不增加上传队列或输出临时文件。MinIO 上传成功之前宕机仍可能丢失内存结果；供应商无查询能力时保持 SAFE。预约调度保持独立；新增图片版本兼容见实施记录。

## 验证

使用模拟供应商响应与 MinIO SDK 客户端，验证 PNG 原字节保留、URL 返回 JPEG 的真实类型、空/非法图片与超限尺寸拒绝、预约解析错误的待核实语义、上传元数据、普通及漫画保存失败不误报成功，并回归现有图片资源与预约测试。

已通过 `mvn -q -DskipTests compile`。10 个测试类共 26 项测试通过、失败/错误/跳过均为 0：`WebClientImage2ClientTest`、`MinioStorageServiceTest`、`MangaImageStorageServiceTest`、`ImageGenServiceTest`、`MangaGenerationServiceTest`、`ReferenceImageControllerTest`、`StaticMediaControllerTest`、`ScheduledMangaGenerationServiceTest`、`ScheduledMangaRetryPolicyTest`、`ScheduledGenerationRouteCapabilityTest`。

供应商响应通过 WebClient ExchangeFunction 替身验证，MinIO 上传通过 SDK Mock 验证参数和原始字节；未进行真实供应商和真实 MinIO 联调。未运行全量后端测试；未改前端。
