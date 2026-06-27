# AgentScope v2 Refactor Plan

## Background

The Manga Agent depends on AgentScope Java v2 (`io.agentscope:agentscope-harness:2.0.0-RC4` locally). AgentScope-related changes must use the official v2 documentation and built-in primitives before adding project-local wrappers.

The target architecture should align with AgentScope Java v2:

- `HarnessAgent` is the long-running agent entrypoint.
- `RuntimeContext` carries per-call metadata such as `userId`, `sessionId`, request id, and business context.
- Tool instances are close to stateless and receive business data from injected context.
- Agent state, workspace, and run persistence remain separate responsibilities.
- AG-UI/SSE delivery stays in the application layer, not inside the AgentScope adapter.

## Current Pain Points

- `AgentScopeHarnessAgentGateway` previously mixed model resolution, cache-key construction, workspace selection, prompt selection, tool registration, message conversion, and `RuntimeContext` construction. The current split keeps AgentScope execution in the gateway, agent construction/tool group setup in `AgentScopeAgentFactory`, prompt selection in `MangaAgentPromptProvider`, and runtime context creation in `AgentScopeRuntimeContextFactory`.
- `MangaAgentService` is now the public application facade. It still coordinates conversations, run scope, resume/cancel/open-run APIs, and SSE sink setup, but workflow routing and execution live in `MangaWorkflowOrchestrator` and workflow nodes.
- Route selection is explicit (`DIRECTOR`, `HITL`, `REVIEW`) and persisted on `MangaAgentRun`; it should not be inferred from prompt text by default.
- HITL currently uses `ask_user` plus `ToolSuspendException` and business-side state lookup. This is compatible with the existing frontend, but it is not yet modeled around v2 permission/external-execution events.
- Visible Chinese strings in the Manga Agent path are correctness-sensitive. Treat mojibake as a defect and fix source strings when touched.

## Target Boundaries

### AgentScope Adapter

Owns only AgentScope concerns:

- Build or reuse `HarnessAgent`.
- Resolve the effective model.
- Build `RuntimeContext`.
- Convert ArtVerse `AgentMessage` to AgentScope messages.
- Register task-specific toolsets with AgentScope v2 `Toolkit` and tool groups.

It should not own run persistence, SSE emission, final reply saving, or business retry/degraded semantics.

### Manga Agent Execution

Owns one logical agent execution:

- Build conversation history.
- Sync workspace knowledge.
- Invoke the AgentScope adapter.
- Convert AgentScope events into ArtVerse events.
- Return a final reply or structured execution outcome.

### Run Coordination

Owns durable run lifecycle:

- `RUNNING`, `WAITING_USER`, terminal transitions.
- Idempotency and rate-limit guard calls.
- Cancellation and stale-run repair.
- Restoring open runs.

### Tool Layer

Owns business operations available to the agent:

- Read chapter context.
- Generate/save storyboard scenes.
- Ask the user for a blocking decision.
- Audit tool calls and mark mutating tool success.

Tool implementations should receive per-call business identity from `RuntimeContext`, using a typed value such as `MangaAgentRuntimeContext`.

Manga Director tools are registered through AgentScope tool groups:

- `context-tools`: read-only chapter/story/storyboard/image context.
- `storyboard-tools`: storyboard generation and storyboard persistence.
- `hitl-tools`: blocking user question/confirmation flow.

## Migration Phases

### Phase 1: RuntimeContext-based tool context

Status: implemented first because it is low risk and covered by existing tests.

- Add `MangaAgentRuntimeContext`.
- Put it into `RuntimeContext` in `AgentScopeHarnessAgentGateway`.
- Register Manga Director tools without binding user/chapter/key in the factory.
- Keep compatibility overloads for direct unit tests and non-AgentScope callers.
- Fix mojibake in touched Manga Agent status strings and tests.

Expected behavior should not change.

### Phase 2: Split AgentScope construction

Status: implemented and slimmed.

- Extracted `AgentScopeRuntimeContextFactory`.
- Extracted `AgentScopeAgentFactory`.
- Extracted `MangaAgentPromptProvider`.
- Tool group registration is colocated with `AgentScopeAgentFactory` because there is one current AgentScope-backed Manga task and AgentScope already provides `Toolkit`/tool group primitives.
- Moved cache-key construction next to agent construction.
- Replaced `PROMPT_VERSION` with `MANGA_DIRECTOR_PROMPT_VERSION`.
- Added focused tests for context construction and tool group registration.

Remaining follow-up:

- Use workflow node identity to choose allowed tool groups per Agent node when more AgentScope-backed nodes are introduced.
- Revisit AgentScope tool group scope when upgrading from `2.0.0-RC4`; this local version uses the available `createToolGroup(name, description, active)` API.

### Phase 3: Split run execution from run coordination

Status: node foundation implemented and route contract made explicit.

- Extracted `MangaWorkflowOrchestrator` for sync and streaming workflow execution.
- Kept `MangaAgentService` as the public application facade for controllers, conversations, run scope, resume/cancel/open-run APIs, and SSE sink setup.
- Preserved existing API contracts while reducing `MangaAgentService` method size and AgentScope execution coupling.
- Added `MangaWorkflowNodeHandler`, `MangaWorkflowNodeRegistry`, and `MangaWorkflowStreamContext`.
- Extracted current Director AgentScope execution into `MangaDirectorAgentNode`.
- `MangaWorkflowOrchestrator` now owns validation, guard/run lifecycle, explicit route/context events, and node dispatch instead of direct AgentScope request construction.

Remaining follow-up:

- Add dedicated Storyboard and Generation node handlers when product behavior is defined. Current routes are `DIRECTOR`, `HITL`, and `REVIEW`.
- Add explicit workflow result types for reply, degraded flag, waiting state, and terminal/cancelled outcome.
- Move node-specific tool group selection into workflow node configuration.

### Phase 4: HITL v2 alignment

- Keep `ask_user` as the business-facing tool name unless the frontend contract changes.
- Evaluate replacing manual `ToolSuspendException` flow with AgentScope v2 external execution or permission events.
- Model user input request and resume payloads as explicit AgentScope events where possible.
- Update AG-UI mapping to reflect native pause/resume events.

### Phase 5: Production state and workspace hardening

- Review whether the default file state store is acceptable for deployment.
- Consider Redis or database-backed `AgentStateStore` for multi-instance deployments.
- Keep workspace knowledge and AgentState separate.
- Audit shell/filesystem tool policy for each business agent.

## Validation Strategy

For backend changes:

```bash
cd ArtVerse
mvn -q -DskipTests compile
mvn -q "-Dtest=AgUiEventFactoryTest,MangaAgentRunRouteTest,MangaAgentServiceTest,GenerationRequestKeyBuilderTest" test
```

For frontend or AG-UI contract changes:

```bash
cd frontend
npm run build
```

## Knowledge Hygiene

When a phase changes behavior, update:

- `docs/knowledge/modules/manga-agent/SKILL.md`
- `docs/knowledge/modules/manga-agent/flow.md`
- Related backend tests
- `frontend/src/api.ts` and `frontend/src/components/MangaAgentPage.tsx` for protocol changes
