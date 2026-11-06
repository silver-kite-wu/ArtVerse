# ArtVerse Collaboration Guidelines

## Before Making Changes

- Check the working tree before editing. Preserve unrelated user changes and avoid reverting files that are already modified.
- Follow the existing package and component structure. Prefer small, behavior-preserving changes over broad rewrites.
- Treat credentials, user content, and generated artifacts as sensitive. Never copy secrets into source, logs, tests, or documentation.

## AI Workflow and Business Knowledge

- Before changing complex agent behavior, inspect the relevant implementation, tests, API contracts, and database migrations.
- Runtime business skills are backend-owned resources under `src/main/resources/business-agent-skills/`. Read the relevant manga or novel skill when changing behavior that depends on it.
- Keep runtime business skills and source materials out of `.agents/skills`; that directory is reserved for Codex/development skills.
- There is currently no `docs/knowledge/` developer knowledge index. Do not reference that path as an existing source of guidance. If durable module documentation is added, update this file with its real path.
- Scheduled manga image generation design and implementation boundaries: `docs/scheduled-manga-generation.md`.
- Unified scheduled task center (existing Agent tasks and scheduled image requests): `docs/task-center-design.md`; implementation, validation, rollout configuration, and unverified scenarios: `docs/task-center-implementation.md`.
- Normal image-request RabbitMQ/virtual-thread implementation, validation boundaries, migration and rollout: `docs/image-generation-implementation.md`; code: `application/imagequeue/` (separate from Agent/scheduled task orchestration).
- Detailed RabbitMQ / Redis specification (single status, post-ACK results, reliable serial chapter coordination): `docs/image-generation-rabbitmq-development-plan.md`.
- Single manga-image lifecycle, admission and recovery requirements: `docs/single-manga-image-request-lifecycle.md`.
- Image-generation H/M/L review decisions and concrete recovery/version/quota specifications: `docs/image-generation-review-revisions.md`; original review background: `docs/image-generation-design-review.md`. The implementation record, not the complete design matrix, states verified behavior.
- Generated-image direct MinIO upload implementation and validation boundaries: `docs/generated-image-direct-upload.md`.
- Small-server RabbitMQ / PostgreSQL task and MinIO / OSS selection assessment (2 CPU / 2 GB; recommendations, not implemented migration): `docs/small-server-image-infrastructure-assessment.md`.
- Reader-to-character chat design, chapter knowledge boundaries, and MVP implementation notes: `docs/reader-character-chat-plan.md`.
- Knowledge base schemas, temporal facts, review and validation record: `docs/knowledge-base-implementation.md`.
- Novel text diff review, deterministic author confirmation, version checks and validation boundaries: `docs/novel-text-review.md`.
- Agent tool catalog and native AgentScope registration design: `docs/agent-tools-and-registration-design.md`; implementation progress, per-tool verification, and remaining work: `docs/agent-tools-implementation.md`.
- AI conversation automatic titles, bounded fallback, concurrency and validation: `docs/ai-conversation-titles.md`.
- Account menu, profile/avatar/password APIs, migration and verification: `docs/account-profile-implementation.md`.
- Local login CSRF diagnosis, development launcher, environment separation and verification: `docs/login-csrf-analysis-and-plan.md`.
- When code and a runtime skill disagree, call out the mismatch and update the skill in the same change when appropriate.

## Backend and Data Changes

- Run backend checks from this directory after backend changes:

  ```bash
  mvn -q -DskipTests compile
  ```

- Add focused tests for shared service behavior, persistence queries, idempotency/rate-limit logic, and API contract changes.
- Add database changes as a new Flyway migration. Do not edit migrations that may already have been applied in another environment.
- Treat authentication, CSRF, cookie, encryption, rate-limit, Actuator, and external-provider settings as security-sensitive. Avoid weakening defaults; document any required deployment configuration and verify its production impact.
- Keep database schema validation (`ddl-auto: validate`) and migration ownership through Flyway.

## Frontend Changes

- Run checks from `../frontend` after frontend changes:

  ```bash
  npm run lint
  npm run build
  ```

- Keep API request/response types aligned with backend DTOs. Add or update focused tests when frontend test coverage exists for the affected behavior.
- Preserve accessible keyboard behavior and responsive layouts when changing interactive components.

## 本地测试账号规则

- 读者角色聊天功能的默认回归账号：`test-reader@example.com`（占位符，真实账号仅通过本地运行环境配置）。
- 密码只通过本地运行时环境或用户当次提供，不写入源代码、测试代码、文档、日志或提交记录。
- 后续接口和页面回归优先使用该账号，并清理测试产生的临时作品、角色和聊天数据。
