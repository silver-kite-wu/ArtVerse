-- MinIO 已由阿里云 OSS 取代：把历史对象存储记录归属改写为 OSS，并收紧枚举约束。
-- 执行前置条件：MinIO 中的对象已经复制到 OSS bucket，否则这些记录将指向不存在的对象。
UPDATE manga_images SET storage_provider = 'OSS' WHERE storage_provider IN ('MINIO', 'minio');

ALTER TABLE manga_images DROP CONSTRAINT IF EXISTS ck_manga_images_storage_provider;
ALTER TABLE manga_images ADD CONSTRAINT ck_manga_images_storage_provider
    CHECK (storage_provider IN ('LOCAL', 'local', 'OSS', 'oss'));
