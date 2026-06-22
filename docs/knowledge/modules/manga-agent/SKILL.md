---
name: manga-agent
description: Use when changing or reviewing ArtVerse Manga Agent behavior, including chat messages, AgentScope execution, run-stream/resume-stream SSE, human-in-the-loop questions, MangaAgentRun persistence, tool audit events, storyboard tool calls, and the frontend MangaAgentPage/API contract.
---

# Manga Agent Skill

Use this skill for changes under the Manga Agent workflow. Read `flow.md` when you need event ordering, run status transitions, API contract details, or tool behavior beyond the summary below.

## Domain Model

The Manga Agent is a chapter-scoped assistant with conversation-level isolation. A chapter can have multiple Manga Agent conversations; each conversation owns its messages, runs, AgentScope session, and conversation workspace.

The selected workspace chapter is authoritative. Do not silently switch chapters based on free-text user intent. The agent should ask the user to switch the workspace if another chapter is intended.

Image generation is not performed by the Manga Agent. The agent prepares or refines storyboard scenes, then tells the user to use the existing Generate Manga action.

## Code Map

- REST/SSE entrypoint: `ArtVerse/src/main/java/com/artverse/api/MangaAgentController.java`.
- Request/response DTOs: `ArtVerse/src/main/java/com/artverse/api/dto/MangaAgentDtos.java`.
- Main orchestration: `ArtVerse/src/main/java/com/artverse/application/MangaAgentService.java`.
- Conversation history and prompt construction: `ArtVerse/src/main/java/com/artverse/application/MangaAgentConversationService.java`.
- Run state and event snapshots: `ArtVerse/src/main/java/com/artverse/application/MangaAgentRunService.java`.
- SSE publishing and event persistence: `ArtVerse/src/main/java/com/artverse/application/MangaAgentRunEventPublisher.java`.
- AG-UI protocol event mapping: `ArtVerse/src/main/java/com/artverse/application/AgUiEventFactory.java`.
- AgentScope bridge and tool registration: `ArtVerse/src/main/java/com/artverse/agents/AgentScopeHarnessAgentGateway.java`.
- Story knowledge sync: `ArtVerse/src/main/java/com/artverse/agents/AgentWorkspaceSyncService.java`.
- Runtime workspace files: `ArtVerse/src/main/java/com/artverse/agents/AgentWorkspaceService.java`.
- Agent tools: `ArtVerse/src/main/java/com/artverse/application/MangaAgentToolFactory.java`.
- Frontend API and stream parser: `frontend/src/api.ts`.
- Frontend UI state machine: `frontend/src/components/MangaAgentPage.tsx`.
- Frontend navigation shell: `frontend/src/App.tsx`; main nav `home` renders `MangaAgentPage`, while `workspace` renders the story/workspace list (`HomePage`).

## Core Flow

The controller resolves the current user and delegates to `MangaAgentService`. Synchronous calls return a final reply. Stream calls return an `SseEmitter` and run on `mangaGenerationExecutor`.

For a new run, `MangaAgentService` resolves the active `MangaAgentConversation` unless a conversation id is supplied, validates the message, starts or reuses a `MangaAgentRun`, checks idempotency through `GenerationGuardService.executeMangaAgentRun`, saves the user message, builds agent messages from the selected conversation history, syncs chapter knowledge to the AgentScope workspace, builds an `AgentRunRequest`, and executes the AgentScope gateway.

For resume, the service requires an existing `WAITING_USER` run, reconstructs a continuation message from the stored user-input request and the user's answer, clears waiting state, and continues the same request id.

`AgentScopeHarnessAgentGateway` creates or reuses a per-user/story/chapter/conversation/task/model/workspace agent. For `AgentTaskType.MANGA_DIRECTOR`, it registers `MangaAgentToolFactory.Tools`.

The frontend consumes AG-UI as the default live protocol. `POST /conversations/{conversationId}/ag-ui/run` and `POST /conversations/{conversationId}/ag-ui/runs/{requestId}/resume` are the preferred endpoints. Legacy chapter-level endpoints auto-resolve the active conversation and remain compatibility paths. Keep the execution panel as the single place that explains what the agent is doing; do not add a second competing progress widget.

In the main app navigation, `首页` is the Manga Agent conversation surface. `工作区` is the story/project management surface where users create, import, select, and edit stories. Do not point `workspace` back to `home`; that recreates a navigation loop and hides the agent from the first screen.

## Tools

- `get_chapter_context`: read-only; returns story, chapter, source excerpt, storyboard scenes, and generated image status.
- `generate_storyboard`: mutating; generates scenes through `SceneService.generateScenes` and saves them through the existing scene flow.
- `save_storyboard`: mutating; saves a complete list of storyboard scenes.
- `save_structured_storyboard`: mutating; normalizes page/panel objects through `StructuredStoryboardService` before saving scenes. Prefer this for creating or rewriting storyboard pages.
- `ask_user`: read-only at the tool level but pauses the run by storing `AgentUserInputRequest` and throwing `ToolSuspendException`.

After a mutating tool succeeds, failures in the final agent response may degrade rather than fail the whole user action. Preserve this behavior unless deliberately changing recovery semantics.

## Invariants

- `requestId` is the idempotency and resume key. Preserve it across stream retries and resume calls.
- `conversationId` isolates messages, runs, AgentScope session id, and conversation workspace. Starting a new conversation must not reuse the old AgentScope session.
- Only `RUNNING` and `WAITING_USER` are open statuses. Terminal statuses are `SUCCEEDED`, `DEGRADED`, `FAILED`, `CANCELLED`, and `INTERRUPTED`. `CANCELLED` is user initiated through `/runs/{requestId}/cancel`; `INTERRUPTED` is system repair for stale `RUNNING` runs.
- Persist non-`text_delta` run events so the frontend can restore an interrupted stream.
- Frontend run progress should be derived from persisted/streamed events, not from hard-coded timers or generic "running" text alone.
- AG-UI events are the live observability protocol. Legacy events remain compatibility payloads and persisted restore input.
- Chapter source text lives in `chapters.novel_content`; chat-derived fallback comes from `Chapter.novelContentOrJoinedMessages()`. `AgentWorkspaceSyncService` writes this into the story workspace `KNOWLEDGE.md` before a Manga Director run.
- Manga Director must not use AgentScope shell/filesystem tools to find business content. `AgentScopeHarnessAgentGateway` disables Harness shell/filesystem tools for this business agent; chapter/story facts must come from `get_chapter_context`, synced `KNOWLEDGE.md`, and registered ArtVerse tools.
- When backend emits AG-UI frames, `MangaAgentPage.tsx` must translate `ag_ui_event` frames into execution panel state and synchronize final persisted messages after `RUN_FINISHED`; otherwise the frontend can appear stuck or require a manual refresh.
- Use `ask_user` for blocking decisions instead of plain-text questions.
- Keep controllers thin. Put workflow behavior in application services.
- Do not expose internal Guard endpoints from user-facing navigation.

## Change Checklist

- If API payloads, AG-UI mappings, or SSE event names change, update `MangaAgentDtos`, `AgUiEventFactory`, `frontend/src/api.ts`, and the execution panel in `MangaAgentPage.tsx` together.
- If tool return shapes change, update frontend timeline handling and tests around `AgentRunToolStatus`.
- If run status transitions change, update `MangaAgentRunService` tests and open-run restore behavior.
- If cancellation or stale-run repair changes, update backend status tests, frontend terminal-state rendering, and the flow reference.
- If prompt or workspace knowledge changes, check both `MangaAgentConversationService.buildSystemPrompt` and `AgentWorkspaceSyncService.buildKnowledge`.
- If AgentScope session/cache key inputs change, update `AgentScopeHarnessAgentGatewayTest` and `AgentSessionIdFactoryTest`.
- If conversation isolation changes, update `MangaAgentConversationRegistry`, message/run repositories, frontend conversation API helpers, and this skill.
- If this skill disagrees with code, trust code first and update this skill or `flow.md`.

## Validation

For backend-only changes, run from `ArtVerse/`:

```bash
mvn -q -DskipTests compile
```

For frontend contract or UI changes, run from `frontend/`:

```bash
npm run build
```
