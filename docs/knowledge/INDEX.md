# ArtVerse Business Knowledge Index

Use this index to route AI-assisted development to the smallest useful business knowledge file.

## Active Skills

- `modules/manga-agent/SKILL.md`: use for Manga Agent chat, AgentScope execution, SSE run lifecycle, human-in-the-loop resume, tool calls, run event persistence, and the frontend Manga Agent panel.

## Planned Skills

- `modules/storyboard-generation/SKILL.md`: planned for novel/chat content to storyboard scenes, structured storyboard normalization, and image generation handoff.
- `modules/guard/SKILL.md`: planned for idempotency, rate limiting, Guard metrics, Guard events, and internal observability endpoints.

## Knowledge Hygiene

- Prefer module skills over asking the user to repeat background.
- Keep each `SKILL.md` short enough to load cheaply; move detailed flow notes into sibling reference files.
- When code changes a documented flow, update the skill or note why no knowledge update is needed.
- Treat stale knowledge as a defect. If a skill disagrees with code, trust code first and repair the skill.
