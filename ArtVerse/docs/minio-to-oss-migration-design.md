# MinIO 替换为阿里云 OSS 设计与实施记录

日期：2026-11-06。状态：**已实施**（代码为 OSS-only，MinIO 实现、SDK、容器与测试已删除）。前置评估见 [small-server-image-infrastructure-assessment.md](small-server-image-infrastructure-assessment.md)。

## 结论

- 本机 MinIO 容器与 `io.minio:minio` SDK 已删除，全部对象读写改走阿里云 OSS 原生 Java SDK（`aliyun-sdk-oss` 3.17.4），使用 V4 签名与单例客户端连接池。
- `ObjectStorageService` 仍是唯一存储出口，并扩展出“条件写入（禁止覆盖）+ 自定义元数据 + 分页列举 + 可区分缺失的读取”能力；`ImageGenerationObjects` 不再直接持有 SDK 客户端。
- 前端契约不变：图片仍由 `/static/manga/**` 代理返回，头像仍由 `GET /api/users/me/avatar` 代理返回，只换后端数据源。
- 两个 bucket：`artverse-manga`（普通图片，私有 + 可选自定义域名直出）与 `artverse-generation-private`（生图私有快照与候选结果，必须私有）。
- `StorageProvider` 枚举现为 `LOCAL/OSS`；Flyway `V60` 把历史 `MINIO/minio` 行改写为 `OSS` 并收紧约束。

## 一、MinIO 在本项目的真实用途

“存图片和 AI 生成的图片”基本正确，但范围偏小。MinIO 同时承担“图片仓库”和“生图任务私有证据仓库”两个角色：

| 用途 | 是否 AI 生成 | 存放位置（object key） | 代码位置 |
| --- | --- | --- | --- |
| 漫画分镜成品图 | 是 | `stories/{storyId}/chapters/{chapterId}/panels/...` | [MangaImageStorageService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/MangaImageStorageService.java) |
| 生图结果候选对象（提交前） | 是 | `generation-results/{userId}/{operationId}/1/...` | [ImageGenerationEvidence.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/imagequeue/ImageGenerationEvidence.java)、[ImageGenerationObjects.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/imagequeue/ImageGenerationObjects.java) |
| **生图输入快照（加密 JSON）** | 否 | 私有 bucket `generation-inputs/{userId}/{root}/manifest.enc` | [ImageGenerationObjects.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/imagequeue/ImageGenerationObjects.java) |
| **幂等别名 / 首次开始时间（JSON）** | 否 | `generation-inputs/.../alias.json`、`.../operations/{op}/start.json` | [ImageGenerationObjects.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/imagequeue/ImageGenerationObjects.java) |
| 垫图（用户上传的参考图） | 否 | `stories/{id}/ref_images/`、`.../chapters/{id}/ref_images/`、`.../asset_groups/{id}/ref_images/` | [ReferenceImageController.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/api/ReferenceImageController.java) |
| 故事封面图 | 否 | `stories/{id}/cover/...` | [StoryController.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/api/StoryController.java) |
| 用户头像 | 否 | `avatars/{userId}/...` | [UserProfileService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/UserProfileService.java) |
| 自动任务参考图、知识投影/资源清单 | 是/否 | 读取上述 key | [ImageAutomationExecutor.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/automation/ImageAutomationExecutor.java)、[KnowledgeProjectionService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/knowledge/KnowledgeProjectionService.java) |

三点关键偏差：

1. **不只是图片**：生图输入快照、幂等别名、开始时间都是加密 JSON 对象，迁移时必须一起搬，否则在途任务会失去恢复能力。
2. **不只是“成品存放”**：候选图依赖“禁止覆盖的原子 PUT + 自定义元数据 + HEAD 核验”，不能只换 endpoint。
3. **本地存储仍并行存在**：`StorageProvider.LOCAL` + `./manga_outputs` 仍用于历史图片与导入，OSS 只是其中一种 provider，本地逻辑未改。

## 二、改造结果

### 代码

| 文件 | 改造后 |
| --- | --- |
| [ObjectStorageService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/storage/ObjectStorageService.java) | 新增 `defaultBucket/privateBucket`、`putIfAbsent`、`openIfPresent`、`statWithMetadata`、`ObjectStat` |
| [OssStorageService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/storage/OssStorageService.java) | 新增；OSS SDK 实现，V4 签名、单例客户端、分页列举、`x-oss-forbid-overwrite`、`x-oss-meta-*`、`@PreDestroy` 关闭 |
| `MinioStorageService.java` | 已删除 |
| [ImageGenerationObjects.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/imagequeue/ImageGenerationObjects.java) | 改为注入 `ObjectStorageService`，删除 `io.minio/okhttp` 依赖；freeze/alias/start/candidate 语义不变 |
| [ImageGenerationEvidence.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/imagequeue/ImageGenerationEvidence.java) | bucket 取自存储抽象，provider 写 `OSS` |
| [MangaImageStorageService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/MangaImageStorageService.java) | provider 写 `OSS`，`addMinioRefs/materializeMinioRefs` 改名为 `addObjectRefs/materializeRefs` |
| [StaticMediaController.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/api/StaticMediaController.java) | 默认 bucket 取自存储抽象，日志改为 “object storage” |
| [ReferenceImageController.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/api/ReferenceImageController.java)、[CharacterProfileService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/CharacterProfileService.java)、[KnowledgeProjectionService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/knowledge/KnowledgeProjectionService.java)、[ImageRequestInputs.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/imagequeue/ImageRequestInputs.java)、[ImageAutomationExecutor.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/automation/ImageAutomationExecutor.java) | 全部 `getMinio()` 改为 `storage.defaultBucket()` |
| [ExportImportService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/application/ExportImportService.java) | 识别 `StorageProvider.OSS` |
| [StorageProvider.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/domain/StorageProvider.java) | 枚举改为 `LOCAL/OSS` |
| [ArtVerseProperties.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/java/com/artverse/config/ArtVerseProperties.java) | `Minio` 配置类替换为 `Oss` |
| [application.yml](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/resources/application.yml) | `artverse.minio` 替换为 `artverse.oss` |
| [pom.xml](file:///e:/develop/projects/ArtVerse/ArtVerse/pom.xml) | `io.minio:minio` 替换为 `com.aliyun.oss:aliyun-sdk-oss:3.17.4` |

### 数据库

- 新增 [V60__migrate_storage_provider_to_oss.sql](file:///e:/develop/projects/ArtVerse/ArtVerse/src/main/resources/db/migration/V60__migrate_storage_provider_to_oss.sql)：
  ```sql
  UPDATE manga_images SET storage_provider = 'OSS' WHERE storage_provider IN ('MINIO','minio');
  ALTER TABLE manga_images DROP CONSTRAINT IF EXISTS ck_manga_images_storage_provider;
  ALTER TABLE manga_images ADD CONSTRAINT ck_manga_images_storage_provider
      CHECK (storage_provider IN ('LOCAL','local','OSS','oss'));
  ```
- **顺序要求**：V60 会在新版本启动时立即执行，因此必须先把 MinIO 对象复制到 OSS，再部署新版本；否则历史记录会指向不存在的对象。
- `users.avatar_bucket` 与 `manga_images.bucket` 直接存 bucket 名；若 OSS bucket 改名，迁移对象后还需执行 `UPDATE users SET avatar_bucket='<新 bucket>'` 与 `UPDATE manga_images SET bucket='<新 bucket>' WHERE storage_provider='OSS'`。沿用默认 `artverse-manga` 则无需改 bucket 值。

### 部署与测试

- [docker-compose.yml](file:///e:/develop/projects/ArtVerse/ArtVerse/docker-compose.yml) / [deploy/compose.production.yml](file:///e:/develop/projects/ArtVerse/ArtVerse/deploy/compose.production.yml)：`minio` 服务、9000/9001 端口、`minio_data` 卷已删除。
- [deploy/.env.example](file:///e:/develop/projects/ArtVerse/ArtVerse/deploy/.env.example)：MINIO 变量替换为 `ARTVERSE_OSS_*`。
- 测试：删除 `MinioStorageServiceTest` 与 `ImageQueueIntegrationTest` 的 MinIO Testcontainer；新增 [OssStorageServiceTest.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/test/java/com/artverse/storage/OssStorageServiceTest.java) 与 [InMemoryObjectStorageService.java](file:///e:/develop/projects/ArtVerse/ArtVerse/src/test/java/com/artverse/storage/InMemoryObjectStorageService.java)；`application-test.yml` 通过 `artverse.oss.enabled=false` 禁用真实 OSS。
- 前端无 MinIO 引用，只依赖 `/static/manga/**`，未改动。

## 三、目标架构

```
业务层（Controller / Service / imagequeue）
        │
        ▼
ObjectStorageService（唯一出口：条件写 / 元数据 / 分页 / 缺失语义）
        │
        └── OssStorageService（阿里云 OSS SDK，V4 签名，单例连接池）
```

接口新增能力：

```java
String defaultBucket();
String privateBucket();

/** 不存在才写；已存在返回 Optional.empty()，其他失败抛异常。 */
Optional<StoredObject> putIfAbsent(String bucket, String objectKey, InputStream content,
                                   long sizeBytes, String contentType, Map<String, String> metadata);

/** 缺失返回 empty；其他存储故障抛异常，避免把“不可用”误判成“不存在”。 */
Optional<InputStream> openIfPresent(String bucket, String objectKey);
Optional<ObjectStat> statWithMetadata(String bucket, String objectKey);
```

`ObjectStat` 记录 `bucket/objectKey/contentType/sizeBytes/metadata`。原 `stat` 保留默认实现，转调 `statWithMetadata` 并在故障时降级为空，供静态代理使用。

## 四、OSS 实现要点

1. **单例客户端**：`@PostConstruct` 构建一次 `OSS`，`@PreDestroy` 关闭；连接超时、读超时、`maxConnections` 可配置。
2. **V4 签名**：`ClientBuilderConfiguration#setSignatureVersion(SignVersion.V4)`。地域由标准 endpoint（如 `oss-cn-hangzhou.aliyuncs.com`）推导，因此不再单独配置 `region`。
3. **内网 endpoint**：服务器与 bucket 同地域时使用 `oss-cn-xxx-internal.aliyuncs.com`，内网流量不计费；不要把内网 endpoint 暴露给浏览器。
4. **bucket 预检与自动创建**：首次写入时 `doesBucketExist`，缺失且 `auto-create-bucket=true` 则创建；结果按 bucket 缓存，避免每次 PUT 都检查。生产建议在控制台预建两个私有 bucket。
5. **禁止覆盖**：`PutObjectRequest.addHeader("x-oss-forbid-overwrite", "true")`；同名已存在返回 `409 FileAlreadyExists`（兼容 `ObjectAlreadyExists/PreconditionFailed`）。**bucket 若开启或暂停版本控制，该头失效**，两个 bucket 都必须保持版本控制关闭。
6. **自定义元数据**：`sha256`、`operation-id`、`execution-token`、`input-hash` 写入 `x-oss-meta-*`；读回时 key 统一小写后逐项比对。
7. **分页列举**：单次 `max-keys` 上限 1000，按 `NextMarker` 循环，支持知识投影 20,000 条列举。
8. **流式上传**：`PutObjectRequest(bucket, key, inputStream, metadata)` 并显式设置 `contentLength`，不做 `byte[]` 全量中转。
9. **异常映射**：`NoSuchKey/NoSuchBucket` → `Optional.empty()`；`FileAlreadyExists` → 条件写冲突；其余 `OSSException/ClientException` → 运行时异常，日志只带 error code，不带 AK/SK。
10. **图片直出**：OSS 默认域名对图片可能强制下载；首期用 `/static/manga/**` 代理规避，需要直出时再配置自定义域名/CDN。

## 五、配置

```yaml
artverse:
  oss:
    enabled: ${ARTVERSE_OSS_ENABLED:true}
    endpoint: ${ARTVERSE_OSS_ENDPOINT:}
    # V4 签名必需；留空时从标准 endpoint 推导，自定义域名必须显式配置
    region: ${ARTVERSE_OSS_REGION:}
    bucket: ${ARTVERSE_OSS_BUCKET:artverse-manga}
    private-bucket: ${ARTVERSE_OSS_PRIVATE_BUCKET:artverse-generation-private}
    access-key: ${ARTVERSE_OSS_ACCESS_KEY:}
    secret-key: ${ARTVERSE_OSS_SECRET_KEY:}
    public-base-url: ${ARTVERSE_OSS_PUBLIC_BASE_URL:}
    presigned-url-expire-seconds: 3600
    connect-timeout-seconds: 10
    read-timeout-seconds: 30
    max-connections: 128
    auto-create-bucket: ${ARTVERSE_OSS_AUTO_CREATE_BUCKET:true}
```

- AK/SK 只放 `.env` 或 RAM 角色，不写入仓库。
- `enabled=true` 时 endpoint/bucket/private-bucket/AK/SK 任一为空都会启动失败，避免带病上线。
- 建议为 ECS 绑定 RAM 角色，仅授予两个 bucket 的 `PutObject/GetObject/DeleteObject/ListObjects/GetObjectMeta`。

## 六、数据迁移与切换

1. **准备**：控制台创建两个私有 bucket（版本控制关闭），创建 RAM 用户/角色，确认内存基线（`docker stats` 记录 MinIO 实际占用）。
2. **搬迁对象（必须先于部署）**：
   ```bash
   # MinIO -> 本地暂存 -> OSS（或 mc 直接 alias 到 OSS）
   mc mirror local/artverse-manga ./migrate/manga
   ossutil cp -r ./migrate/manga oss://artverse-manga/ --update
   mc mirror local/artverse-manga-generation-private ./migrate/private
   ossutil cp -r ./migrate/private oss://artverse-generation-private/ --update
   ```
3. **停写**：关闭生图入口（`artverse.image-queue.producer-enabled=false`，如有需要同时关闭 worker/coordinator），等待在途任务排空或确认无 UNRESOLVED 记录；备份 PostgreSQL。
4. **切换**：配置 `ARTVERSE_OSS_*` 后部署新版本，Flyway 自动执行 V60 把历史记录改写为 `OSS`；再做一次增量 `ossutil sync` 补齐停写窗口内的对象。
5. **校验**：对象数量/总大小对比；抽查 `sha256`；`image_gen_records` 中 `candidate_object_key` 与 OSS 实际 key 一一对应；打开历史漫画、垫图、封面、头像、导出 zip；跑一次真实生图（含候选恢复）。
6. **回退（部署级）**：回滚到旧镜像并恢复 V60 之前的数据库备份，同时重新启用 MinIO 容器与数据卷。OSS-only 代码不再提供 `provider=minio` 配置开关。
7. **收尾**：确认无回退需求后删除 MinIO 数据卷（`docker volume rm artverse_minio_data`）。

## 七、风险与取舍

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 条件写语义差异 | `If-None-Match:*` 在 OSS 不生效 | 用 `x-oss-forbid-overwrite`，固定 bucket 版本控制关闭；两种实现都返回“已存在”布尔 |
| 列举上限 | 20,000 条列举会漏数据 | 分页循环，单页 ≤1000 |
| 私有快照被公开 | 若把私有 bucket 配成公共读 | 两个 bucket 独立配置；私有 bucket 不接入静态代理，`publicOrPresignedUrl` 只对默认 bucket使用 publicBaseUrl |
| 公网流量费 | 浏览器直读 OSS 产生外网流出 | 首期继续走 ECS 代理（内网读、ECS 出网）；量大时再上 CDN |
| 迁移遗漏 | 只搬成品图，漏掉 manifest/alias/start 与候选图 | 迁移清单以 `mc find`/OSS 列举全量比对为准 |
| 2G 内存 | OSS SDK 新增连接池与缓冲区 | 复用单例 client、限制 `max-connections`、保持流式上传 |
| 启动依赖云服务 | OSS 不可达时上传/生图失败 | bucket 预检在首次写入时才发生；存储故障按原有 STATUS_UNAVAILABLE/重试语义处理 |

## 八、已完成事项

1. `ObjectStorageService` 扩展 + `ObjectStat`。
2. `OssStorageService`（V4、条件写、元数据、分页、预签名、关闭）。
3. `ImageGenerationObjects` 与全部 `getMinio()` 调用点改造。
4. Flyway V60、`StorageProvider.OSS`、导出识别。
5. 测试替换：内存对象存储 + OSS 单测；移除 MinIO 容器/SDK 测试。
6. compose、`.env.example`、README/AGENTS 与本文档更新。
