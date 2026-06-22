# ArtVerse Development Data Export

This folder contains a development snapshot for local team setup.

## Contents

- `artverse-dev.dump`: PostgreSQL custom-format dump for database `manga_novel`.
- `artverse-manga/`: exported MinIO bucket contents for bucket `artverse-manga`.

## Restore Database

From repository root:

```powershell
cd "D:\develop\Vibe Coding\ArtVerse\ArtVerse"
docker compose up -d postgres minio redis
docker cp "..\sql\artverse-dev.dump" artverse-postgres:/tmp/artverse-dev.dump
docker exec artverse-postgres pg_restore -U postgres -d manga_novel --clean --if-exists --no-owner --no-privileges /tmp/artverse-dev.dump
```

## Restore MinIO Bucket

The MinIO container image used by this project includes `mc`.

```powershell
docker cp "..\sql\artverse-manga" artverse-minio:/tmp/artverse-manga-import
docker exec artverse-minio sh -c "mc alias set local http://127.0.0.1:9000 minioadmin minioadmin >/dev/null && mc mb -p local/artverse-manga >/dev/null 2>&1 || true && mc mirror --overwrite /tmp/artverse-manga-import local/artverse-manga"
```

Run database restore before MinIO restore if you want records and object files to match.
