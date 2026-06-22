<div align="center">

# ArtVerse

**面向漫画创作流程的 AI 工作台，覆盖故事管理、分镜生成、漫画出图与章节级智能体协作。**

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-19-blue?logo=react)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-6-blue?logo=typescript)](https://www.typescriptlang.org/)
[![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite)](https://vite.dev/)

[English README](./README.md)

</div>

## 项目简介

ArtVerse 是一个全栈 AI 漫画创作平台，目标是把“故事设定、章节编排、分镜辅助、图片生成、智能体协作”整合进一套连续工作流。

项目由 Spring Boot 后端、Vite React 前端，以及基于 AgentScope Harness 的漫画智能体组成，适合用于：

- 管理故事、章节、角色与素材
- 生成或重写分镜内容
- 在章节上下文中与漫画智能体多轮协作
- 通过流式事件观察智能体执行过程
- 在关键节点接入人工决策，继续推进任务

## 核心能力

### 故事与章节工作区

- 故事、章节的创建与管理
- 角色卡与参考图维护
- 设定组复用视觉上下文
- 故事数据导入、导出

### 漫画智能体协作

- 按章节隔离的对话与运行记录
- 基于 AG-UI / SSE 的实时执行反馈
- 支持中断后的人机协同恢复
- 工具调用、运行状态、消息历史可追踪

### 漫画图片生成

- 基于提示词的图片生成接口
- 支持参考图参与生成
- 生成历史查询与删除
- 带有并发保护与幂等控制的 guard 层

### 平台基础设施

- Spring Data JPA + Flyway 管理数据模型与迁移
- Redis 支撑 guard、运行态协调与缓存能力
- MinIO 兼容对象存储
- Sa-Token 认证体系

## 技术栈

| 分层 | 技术方案 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.3、Spring Web、Spring Data JPA、Flyway |
| 前端 | React 19、TypeScript、Vite、Tailwind CSS |
| 智能体运行时 | AgentScope Harness |
| 数据与存储 | PostgreSQL、Redis、MinIO |
| 认证与安全 | Sa-Token、BCrypt |

## 仓库结构

```text
ArtVerse/
|- ArtVerse/              # Spring Boot 后端服务
|- frontend/              # Vite + React 前端
|- docs/knowledge/        # 业务知识与模块说明
|- sql/                   # SQL 相关资源
|- AGENTS.md              # 仓库协作规范
|- README.md              # 英文首页 README
`- README.zh.md           # 中文 README
```

## 快速启动

### 1. 启动本地依赖

```bash
cd ArtVerse
docker compose up -d
```

这一步会使用 `ArtVerse/docker-compose.yml` 启动 PostgreSQL、Redis 和 MinIO。

### 2. 配置后端环境

启动后端前，请准备以下配置：

- `DEEPSEEK_API_KEY`
- 如果启用 Coze 工具，需要配置 `COZE_API_KEY`
- 数据库、Redis、对象存储配置可参考 `ArtVerse/src/main/resources/application.yml`

### 3. 启动后端

```bash
cd ArtVerse
mvn spring-boot:run
```

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

## 常用开发命令

### 后端

```bash
cd ArtVerse
mvn -q -DskipTests compile
mvn test
```

### 前端

```bash
cd frontend
npm run build
npm run lint
npm run preview
```

## 架构说明

- 后端接口层位于 `ArtVerse/src/main/java/com/artverse/api`
- 业务服务层位于 `ArtVerse/src/main/java/com/artverse/application`
- 幂等、限流、并发保护位于 `ArtVerse/src/main/java/com/artverse/guard`
- 前端页面与组件位于 `frontend/src/components`
- 前端 API 封装位于 `frontend/src/api.ts`

## 文档入口

- [英文 README](./README.md)
- [业务知识索引](./docs/knowledge/INDEX.md)
- [漫画智能体模块说明](./docs/knowledge/modules/manga-agent/SKILL.md)
- [漫画智能体流程说明](./docs/knowledge/modules/manga-agent/flow.md)

## 当前进展

仓库当前已经包含这些核心能力：

- 故事与章节管理流程
- 角色卡与设定组管理
- 漫画智能体会话与运行持久化
- 图片生成接口
- Guard 观测、幂等与并发保护

## License

当前仓库中还没有附带正式的 License 文件。
