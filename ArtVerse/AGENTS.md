# ArtVerse Backend Guidelines

## Business Knowledge Routing

Use repository business knowledge before changing complex AI workflow code. Start from `docs/knowledge/INDEX.md`, then read only the module skill that matches the task.

- Manga agent chat, SSE runs, HITL resume, AgentScope tools, run persistence: `docs/knowledge/modules/manga-agent/SKILL.md`.
- Guard/idempotency/rate-limit and storyboard generation knowledge are planned modules; inspect code directly until their skills are added.

When a change touches a documented module, compare the skill with the current code. If the code and knowledge disagree, mention the mismatch and update the relevant knowledge file in the same change when possible.

## Backend Checks

Run from this directory after backend changes:

```bash
mvn -q -DskipTests compile
```

Add focused tests for shared service behavior, persistence queries, idempotency/rate-limit logic, and API contract changes.
