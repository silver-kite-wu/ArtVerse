# Manga Agent Flow Reference

Read this file when the task depends on exact run lifecycle, event ordering, or API/frontend contracts.

## HTTP Contract

All endpoints are scoped to `/api/chapters/{chapterId}/manga-agent`.

- `GET /messages`: returns persisted user, assistant, and system messages for the current user and chapter.
- `GET /conversations`: returns Manga Agent conversations for the current user and chapter.
- `POST /conversations`: archives the active conversation, creates a fresh active conversation, and returns it.
- `POST /conversations/{conversationId}/archive`: archives a specific conversation.
- `GET /conversations/{conversationId}/messages`: returns messages for one conversation.
- `POST /conversations/{conversationId}/ag-ui/run`: preferred AG-UI streaming run for a specific conversation. Body: `{ message, requestId?, route? }`.
- `GET /conversations/{conversationId}/runs/open`: returns the latest open run for one conversation.
- `GET /conversations/{conversationId}/runs/{requestId}`: returns a run snapshot scoped to one conversation.
- `POST /conversations/{conversationId}/runs/{requestId}/cancel`: cancels a run scoped to one conversation.
- `POST /conversations/{conversationId}/ag-ui/runs/{requestId}/resume`: preferred AG-UI streaming resume for one conversation.
- `POST /run`: synchronous run. Body: `{ message, requestId?, route? }`. Response: `{ reply, requestId }`.
- `POST /run-stream`: compatibility streaming run. Body: `{ message, requestId? }`. Emits legacy business events (`status`, `run_event`, `tool`, `user_input_requested`, `done`, and `error`) plus AG-UI protocol events as default SSE `message` frames.
- `POST /ag-ui/run`: default streaming run for AG-UI clients. Body: `{ message, requestId?, route? }`. Emits only AG-UI protocol events as default SSE `message` frames.
- `GET /runs/open`: returns the latest `RUNNING` or `WAITING_USER` run snapshot, if any.
- `GET /runs/{requestId}`: returns a persisted run snapshot with events.
- `POST /runs/{requestId}/cancel`: marks an open run as `CANCELLED`. The frontend should also abort its active AG-UI subscription, but the persisted run state is the source of truth.
- `POST /runs/{requestId}/resume`: synchronous resume. Body: `{ answer }`.
- `POST /runs/{requestId}/resume-stream`: compatibility streaming resume. Body: `{ answer }`.
- `POST /ag-ui/runs/{requestId}/resume`: default AG-UI streaming resume. Body: `{ answer }`. Emits only AG-UI protocol events.

Frontend types and stream parsing live in `frontend/src/api.ts`. The frontend depends on `@ag-ui/core` and `@ag-ui/client` for formal AG-UI event types. `ArtVerseMangaAgentHttpAgent` extends the official `HttpAgent` and adapts AG-UI `RunAgentInput` to the current ArtVerse `{ message, requestId, route }` body. The Manga Agent page includes a route selector (自动 聊天 导演 质检 决策) in the sidebar. The selected route is sent with every run and restored from snapshots; `AUTO` asks the backend to classify user intent before dispatch. The left sidebar now lists chapter conversations explicitly; clicking one reloads that conversation's messages and open run. The page resolves or creates an active conversation for the selected chapter, restores open runs from persisted business events, and consumes live AG-UI events in `frontend/src/components/MangaAgentPage.tsx`.

`MangaAgentPage.tsx` renders the execution panel from the same stream. Live progress should prefer AG-UI events: `RUN_STARTED`, `STATE_SNAPSHOT`, `CUSTOM` run/tool audit events, `TEXT_MESSAGE_START`, `TEXT_MESSAGE_CONTENT`, `TEXT_MESSAGE_END`, `RUN_FINISHED`, and `RUN_ERROR`. The panel shows the active request id, latest run status, recent event timeline, tool activity, cancel action, and human-in-the-loop waiting state. The panel is restored from persisted run events after refresh or reconnect, and final messages are synchronized from `/messages` after `RUN_FINISHED`. Business run status remains visible as secondary metadata, but the event timeline is the primary live view.

## New Stream Run

1. `MangaAgentController.runStream` resolves the current user.
2. `MangaAgentService.runStream` resolves the active conversation or the supplied conversation id, creates an effective `requestId`, creates an `SseEmitter`, and submits work to `mangaGenerationExecutor`.
3. `AgentRunToolStatus.start` opens per-run tool tracking and forwards tool events to `MangaAgentRunEventPublisher`.
4. `MangaWorkflowOrchestrator.runStreamLeader` validates message text and prepares workflow execution.
5. `MangaAgentRunService.startOrReuse` creates or resumes a conversation-scoped `MangaAgentRun` with status `RUNNING`.
6. A `status` SSE event announces context loading.
7. `GenerationGuardService.executeMangaAgentRun` protects the run with idempotency/rate-limit logic.
8. `MangaWorkflowOrchestrator` builds workflow context metadata with the explicit request route. If the route is `AUTO`, `MangaIntentClassifierService` classifies the prompt, emits an `intent_classified` run event, and either dispatches to `CHAT`, `DIRECTOR`, `HITL`, or `REVIEW`, or pauses through the existing HITL mechanism when confidence is low. `MangaWorkflowNodeRegistry` selects the matching workflow node.
9. `MangaDirectorAgentNode` saves the user message to the selected conversation and builds history-limited agent messages from that conversation only.
10. `AgentWorkspaceSyncService.syncMangaDirectorKnowledge` writes `KNOWLEDGE.md` for the user/story workspace.
11. `MangaDirectorAgentNode` creates an `AgentRunRequest` with user, story, chapter, conversation id, task type, model, user API key, and request id.
12. `AgentScopeHarnessAgentGateway.streamEvents` obtains the cached `HarnessAgent` from `AgentScopeAgentFactory`, builds per-call context through `AgentScopeRuntimeContextFactory`, and sends messages to AgentScope.
13. `AgentScopeEventMapper` maps AgentScope events into `AgentRunEvent`; text deltas append to the final reply.
14. `MangaAgentRunEventPublisher` sends SSE events and persists non-text run events. It also maps the run lifecycle into formal AG-UI events through `AgUiEventFactory` and emits them as default SSE `message` frames.
15. On success, `MangaAgentRunService.markSucceeded` stores the final reply and `done` is emitted.
16. The frontend execution panel summarizes the latest events and keeps the user informed while the assistant response is still streaming.

## Human-In-The-Loop Resume

`AgentScopeRuntimeContextFactory` adds `MangaAgentRuntimeContext` to AgentScope v2 `RuntimeContext` for Manga Director runs. Tools read user id, chapter id, conversation id, request id, and Coze API key from that typed context instead of relying on factory-captured fields.

`AgentScopeAgentFactory` registers Manga Director tools into AgentScope v2 `Toolkit` groups: `context-tools`, `storyboard-tools`, and `hitl-tools`. `MangaHitlTools.ask_user` stores the current `AgentUserInputRequest` in `AgentRunToolStatus` using `MangaAgentRuntimeContext.requestId`, then throws `ToolSuspendException`.

`MangaDirectorAgentNode` detects the tool-suspended waiting state and raises `AgentUserInputRequiredException`. `MangaAgentService` catches it, marks the run `WAITING_USER`, emits `user_input_requested`, and completes the stream. The frontend displays the options and can call resume.

Resume requires the same `conversationId` and `requestId`. `MangaAgentRunService.requireWaitingRun` verifies status inside the selected conversation. `MangaAgentConversationService.resumeMessage` combines original input, the stored question, and the user's answer into a continuation prompt. The run is marked `RUNNING` and the normal stream flow continues.

When `user_input_requested` is received or restored, the frontend execution panel switches to a waiting state and shows the selectable options. Free-text answers are allowed only when the backend request has `allowFreeText=true`.

## Cancellation And Interruption

`CANCELLED` means the user explicitly stopped the run through `POST /runs/{requestId}/cancel`. The frontend aborts the active AG-UI subscription after the backend confirms cancellation. Terminal writes from the background worker must not overwrite `CANCELLED`.

`INTERRUPTED` means the system repaired a stale `RUNNING` run. `MangaAgentService` calls stale-run repair before returning open-run and run-state snapshots. A run whose `updated_at` is older than `max(agent.stale-running-seconds, agent.run-timeout-seconds * 2)` becomes `INTERRUPTED` with an error message and `completed_at`.

`CANCELLED` and `INTERRUPTED` are terminal statuses. They are not returned by `/runs/open`, cannot be resumed, and should be shown as stopped/interrupted states in the frontend rather than continuing to poll.

## Persistence Rules

`manga_agent_conversations` stores chapter-level conversation sessions. Creating a new conversation archives the previous active conversation and gives the user a clean message/run/session scope without deleting old records.

`manga_agent_messages` belongs to one conversation. Legacy chapter-level message endpoints resolve the current active conversation for compatibility.

`manga_agent_runs` stores the current run status, requested workflow route, input message, final reply, error, user input request JSON, and timestamps. Runs belong to one conversation. `AUTO` is stored as the requested route; the resolved execution route is recorded in `intent_classified` run events. Valid statuses are `RUNNING`, `WAITING_USER`, `SUCCEEDED`, `DEGRADED`, `FAILED`, `CANCELLED`, and `INTERRUPTED`. The unique constraint remains user plus request id.

`manga_agent_run_events` stores event name, type, phase, label, status, full JSON payload, and creation time. Persisted events allow the frontend to restore progress after refresh or reconnect.

Do not persist `text_delta` events by default; they can be numerous and are only needed while the stream is active.

## Recovery Behavior

If the agent returns an empty final response or fails after a successful mutating tool call, `MangaAgentConversationService.fallbackAfterToolSuccess` creates a degraded assistant reply and records a system failure message. The run becomes `DEGRADED` instead of losing the completed write.

If no mutating tool succeeded, failures are saved as system failure messages and surfaced as `FAILED`.

## Token Context Surfaces

There are three separate context channels:

- Conversation prompt from `MangaAgentConversationService.buildSystemPrompt` plus visible history from the selected conversation.
- Story workspace knowledge from `AgentWorkspaceSyncService.buildKnowledge`, written to AgentScope `KNOWLEDGE.md`.
- AgentScope agent system prompt from `MangaAgentPromptProvider`.

AgentScope `RuntimeContext.sessionId` includes user, story, chapter, conversation id, and task suffix. A new conversation must produce a new session id. `RuntimeContext.userId` remains the ArtVerse user id for multi-tenant isolation. `MangaAgentRuntimeContext` carries the per-call business context that tools need.

When reducing token usage, prefer shrinking history, story knowledge excerpts, or duplicated prompt instructions before changing business behavior.
