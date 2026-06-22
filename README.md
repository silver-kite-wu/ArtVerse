<div align="center">

# ArtVerse

**AI-native workspace for story writing, storyboard generation, manga image production, and chapter-scoped agent collaboration.**

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-19-blue?logo=react)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-6-blue?logo=typescript)](https://www.typescriptlang.org/)
[![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite)](https://vite.dev/)

[中文文档](./README.zh.md)

</div>

## Overview

ArtVerse is a full-stack creation platform for manga-oriented storytelling. It combines a Spring Boot backend, a Vite React frontend, and an AgentScope-powered manga agent to help creators move from story setup to storyboard refinement and image generation in a single workflow.

The repository already covers the main creation loop:

- Manage stories, chapters, and character assets
- Generate storyboard content and manga images
- Run a chapter-scoped AI agent with streaming feedback
- Resume interrupted runs with human-in-the-loop decisions
- Persist conversations and execution history per story context

## Features

### Story Workspace

- Story and chapter management
- Character profiles and reference-image management
- Asset groups for reusable visual context
- Story package import and export

### Manga Agent

- Chapter-scoped conversations and run history
- AG-UI / SSE streaming execution events
- Human-in-the-loop resume flow
- Tool-call visibility and persisted run states

### Image Generation

- Prompt-based image generation API
- Reference-image-assisted generation
- Generation history management
- Guard layer for idempotency and concurrency control

### Platform Foundations

- Spring Data JPA + Flyway migrations
- Redis-backed guard and runtime coordination
- MinIO-compatible object storage
- Sa-Token-based authentication

## Tech Stack

| Layer | Stack |
| --- | --- |
| Backend | Java 21, Spring Boot 3.3, Spring Web, Spring Data JPA, Flyway |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS |
| Agent Runtime | AgentScope Harness |
| Data & Storage | PostgreSQL, Redis, MinIO |
| Auth & Security | Sa-Token, BCrypt |

## Repository Layout

```text
ArtVerse/
|- ArtVerse/              # Spring Boot backend
|- frontend/              # Vite + React frontend
|- docs/knowledge/        # Business knowledge and module notes
|- sql/                   # SQL assets
|- AGENTS.md              # Repository working conventions
|- README.md              # English README (GitHub homepage)
`- README.zh.md           # Chinese README
```

## Quick Start

### 1. Start local dependencies

```bash
cd ArtVerse
docker compose up -d
```

This starts PostgreSQL, Redis, and MinIO from `ArtVerse/docker-compose.yml`.

### 2. Configure the backend

Before running the backend, prepare the required environment or local configuration:

- `DEEPSEEK_API_KEY`
- `COZE_API_KEY` if Coze tools are enabled
- Database, Redis, and object-storage settings in `ArtVerse/src/main/resources/application.yml`

### 3. Run the backend

```bash
cd ArtVerse
mvn spring-boot:run
```

### 4. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

## Development Commands

### Backend

```bash
cd ArtVerse
mvn -q -DskipTests compile
mvn test
```

### Frontend

```bash
cd frontend
npm run build
npm run lint
npm run preview
```

## Architecture Notes

- Backend controllers live in `ArtVerse/src/main/java/com/artverse/api`
- Core services live in `ArtVerse/src/main/java/com/artverse/application`
- Guard and idempotency logic live in `ArtVerse/src/main/java/com/artverse/guard`
- Frontend components live in `frontend/src/components`
- Frontend API helpers live in `frontend/src/api.ts`

## Documentation

- [Chinese README](./README.zh.md)
- [Business knowledge index](./docs/knowledge/INDEX.md)
- [Manga agent skill](./docs/knowledge/modules/manga-agent/SKILL.md)
- [Manga agent flow](./docs/knowledge/modules/manga-agent/flow.md)

## Status

ArtVerse is under active development. The current repository already includes story and chapter workflows, character and asset-group management, manga-agent conversations with run persistence, image-generation endpoints, and guard observability for generation safety.

## License

No license file is currently included in this repository.
