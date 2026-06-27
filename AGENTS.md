# Repository Guidelines

## Project Structure & Module Organization

This repository contains a Spring Boot backend and a Vite React frontend.

- `ArtVerse/`: Java 21 backend. Main code lives in `src/main/java/com/artverse`.
  - `api/`: REST and SSE controllers.
  - `application/`: business services, guard/idempotency logic, external orchestration.
  - `domain/`: JPA entities and enums.
  - `persistence/`: Spring Data repositories.
  - `config/`: application, Redis, Sa-Token, and integration configuration.
- `ArtVerse/src/main/resources/`: backend configuration, migrations, and static resources.
- `frontend/`: React + TypeScript client. Components are in `frontend/src/components`, API helpers in `frontend/src/api.ts`.
- `LoreVista-python/` and `.agentscope/`: auxiliary/agent-related assets; avoid changing them unless the task targets them.

## Build, Test, and Development Commands

Backend commands from `ArtVerse/`:

```bash
mvn spring-boot:run        # run the backend locally
mvn -q -DskipTests compile # fast compile check
mvn test                   # run backend tests
```

Frontend commands from `frontend/`:

```bash
npm run dev     # start Vite dev server
npm run build   # TypeScript check and production build
npm run lint    # ESLint checks
npm run preview # preview built frontend
```

## Coding Style & Naming Conventions

Use existing patterns before adding abstractions. Backend classes use PascalCase and package by role (`Controller`, `Service`, `Repository`). Prefer constructor injection with Lombok `@RequiredArgsConstructor`. Keep controller logic thin; put cross-cutting request protection in guard/application services.

Frontend uses React function components, TypeScript interfaces, and Tailwind utility classes. Component files use PascalCase, e.g. `GuardDashboardPage.tsx`. API functions live in `api.ts` and should return typed payloads.

## Business Knowledge Routing

Use repository business knowledge before changing complex AI workflow code. Start from `docs/knowledge/INDEX.md`, then read only the module skill that matches the task.

- Manga agent chat, SSE runs, HITL resume, AgentScope tools, run persistence: `docs/knowledge/modules/manga-agent/SKILL.md`.
- Guard/idempotency/rate-limit and storyboard generation knowledge are planned modules; inspect code directly until their skills are added.

When a change touches a documented module, compare the skill with the current code. If the code and knowledge disagree, mention the mismatch and update the relevant knowledge file in the same change when possible.

## Testing Guidelines

Run at least `mvn -q -DskipTests compile` after backend changes and `npm run build` after frontend changes. Add focused tests for shared service behavior, persistence queries, idempotency/rate-limit logic, and API contract changes. Name backend tests after the unit under test, e.g. `IdempotencyServiceTest`.

## Commit & Pull Request Guidelines

Recent commits use short Chinese summaries, usually verb-object style, such as `娣诲姞guard灞傝繘琛屽箓绛夊拰闄愭祦`. Keep commits concise and scoped.

Pull requests should include:

- What changed and why.
- Verification commands run.
- Screenshots for UI changes.
- Notes for configuration, Redis, database, or external API behavior changes.

## Security & Configuration Tips

Never commit API keys or local secrets. User API keys are stored through the application settings flow or backend `.env`. Be careful with internal observability endpoints such as `/internal/guard` and `/api/internal/guard/**`; they expose runtime metadata and should not be linked from user-facing navigation.

## Chinese Display Rules

- User-facing UI text must be valid Simplified Chinese by default.
- Do not commit encoded garbage, mojibake, or mixed-language placeholders into visible labels, prompts, tooltips, or status bars.
- If a screen must show English, keep it intentional and localize the rest of the surface consistently.
- Frontend source files must be kept in UTF-8, and any visible Chinese string that becomes乱码 is a correctness defect that must be fixed at the source.
- Treat any visible涔辩爜 as a correctness defect and fix the source string, not just the rendering layer.
