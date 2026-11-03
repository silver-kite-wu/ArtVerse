import { EventType, HttpAgent, type AGUIEvent, type RunAgentInput } from '@ag-ui/client';
import { readApiData, BASE, apiHeaders, authFetch, parseApiError } from './auth';
import { getProviderRequestPayload } from './providerSettings';
import { randomUUID } from '../utils/browserSecurity';

export type MangaWorkflowRoute = 'CONVERSATION' | 'CREATIVE' | 'STORYBOARD' | 'REVIEW' | 'DIRECTOR';

export type MangaAgentRunEvent =
  | { type: 'status'; data: { message?: string; requestId?: string; request_id?: string } }
  | { type: 'run_event'; data: AgentRunTimelineEvent }
  | { type: 'tool'; data: { tool?: string; succeeded?: boolean; saved?: boolean; scenes_count?: number; error?: string } }
  | { type: 'user_input_requested'; data: AgentUserInputRequest }
  | { type: 'done'; data: { reply?: string; requestId?: string; request_id?: string } }
  | { type: 'error'; data: { detail?: string; error?: string; requestId?: string; request_id?: string } }
  | { type: 'ag_ui_event'; data: ArtVerseAgUiEvent };

export type ArtVerseAgUiEvent = AGUIEvent & {
  protocol?: 'ag-ui';
  runId?: string;
  route?: MangaWorkflowRoute;
  rawEvent?: AgentRunTimelineEvent | Record<string, unknown>;
  snapshot?: {
    requestId?: string;
    runId?: string;
    status?: string;
    phase?: string;
    message?: string;
    route?: MangaWorkflowRoute;
  };
  result?: {
    reply?: string;
  };
  outcome?: {
    type?: 'success' | 'interrupt';
    interrupts?: Array<{
      id: string;
      reason: string;
      message?: string;
      metadata?: {
        question?: string;
        options?: AgentUserInputOption[];
        allowFreeText?: boolean;
        purpose?: AgentUserInputRequest['purpose'];
        reason?: string;
      };
    }>;
  };
};

export interface AgentRunTimelineEvent {
  type: string;
  phase?: string;
  label?: string;
  toolName?: string;
  status?: string;
  text?: string;
  data?: Record<string, unknown>;
  createdAt?: string;
}

export type MangaAgentRunStatus = 'RUNNING' | 'WAITING_USER' | 'SUCCEEDED' | 'DEGRADED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';

export interface AgentRunPersistedEvent {
  eventId?: number;
  eventName: MangaAgentRunEvent['type'] | 'phase';
  data: MangaAgentRunEvent['data'];
  createdAt?: string;
}

export interface MangaAgentRunSnapshot {
  requestId: string;
  request_id?: string;
  route?: MangaWorkflowRoute;
  status: MangaAgentRunStatus;
  inputMessage?: string;
  finalReply?: string;
  errorMessage?: string;
  userInputRequest?: AgentUserInputRequest | null;
  events: AgentRunPersistedEvent[];
  createdAt?: string;
  updatedAt?: string;
  completedAt?: string | null;
  lastProgressAt?: string;
  currentPhase?: 'MODEL' | 'TOOL' | string;
  routeSource?: 'AUTO' | 'RESUME_FIXED' | 'RESUME_RECLASSIFIED' | 'SHADOW' | 'FALLBACK' | string;
  routeConfidence?: number | null;
  routerVersion?: string | null;
  workflowVersion?: string;
  traceId?: string;
  skillVersions?: Record<string, string>;
  modelConfigId?: number | null;
  knowledgeSnapshotId?: number | null;
  budgetUsage?: Record<string, number>;
  runAttributes?: Record<string, unknown>;
  contextSnapshot?: {
    storyId?: number | null;
    chapterId?: number | null;
    storyTitle?: string | null;
    chapterDisplayName?: string | null;
    sceneCount?: number;
    imageCount?: number;
    contextHash?: string | null;
    knowledgeRecallHash?: string | null;
    requiredFields?: string[];
    warnings?: string[];
  } | null;
  steps?: MangaAgentRunStep[];
  artifacts?: MangaAgentArtifactSummary[];
}

export interface MangaAgentRunStep {
  planId: string;
  sequence: number;
  route: MangaWorkflowRoute;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
  mutating: boolean;
  skillKey?: string | null;
  skillVersion?: string | null;
  inputSummary?: string | null;
  outputSummary?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
}

export interface MangaAgentArtifactSummary {
  artifactId: string;
  type: string;
  status: 'DRAFT' | 'VALIDATED' | 'REJECTED' | 'COMMITTED' | 'SUPERSEDED';
  schemaVersion: string;
  evaluation: Record<string, unknown>;
  checksum: string;
}

export interface MangaAgentArtifact extends MangaAgentArtifactSummary {
  requestId: string;
  payload: Record<string, unknown>;
}

export interface AgentSkillManifest {
  skillKey: string;
  semanticVersion: string;
  checksum: string;
  status: string;
  supportedRoutes: string[];
  capabilities: string[];
  promptVersion: string;
  allowedToolGroups: string[];
  budgetPolicy: Record<string, unknown>;
  evaluatorKey?: string | null;
  hitlPolicy: string;
  userConfigurable: boolean;
}

export interface UserAgentSkill { manifest: AgentSkillManifest; enabled: boolean; }

export interface AgentUserInputOption {
  id: string;
  label: string;
  description?: string;
  recommended?: boolean;
}

export interface AgentUserInputRequest {
  requestId?: string;
  request_id?: string;
  question: string;
  options: AgentUserInputOption[];
  allowFreeText?: boolean;
  reason?: string;
  purpose?: 'ROUTING' | 'MUTATION_CONFIRMATION' | 'BUSINESS_CONFIRMATION' | string;
}

class ArtVerseMangaAgentHttpAgent extends HttpAgent {
  private readonly message: string;
  private readonly requestId?: string;
  private readonly answer?: string | Record<string, unknown>;
  private readonly model?: string;

  constructor(
    url: string,
    message: string,
    requestId: string | undefined,
    abortController: AbortController,
    answer?: string | Record<string, unknown>,
    model?: string,
  ) {
    super({
      url,
      headers: apiHeaders(true, 'POST'),
    });
    this.message = message;
    this.requestId = requestId;
    this.answer = answer;
    this.model = model;
    this.abortController = abortController;
  }

  protected override requestInit(input: RunAgentInput): RequestInit {
    const providerPayload = getProviderRequestPayload('llm', this.model);
    return {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        ...this.headers,
        'Content-Type': 'application/json',
        Accept: 'text/event-stream, application/json',
      },
      body: JSON.stringify(this.answer === undefined
        ? {
          message: this.message,
          requestId: this.requestId || input.runId,
          request_id: this.requestId || input.runId,
          ...providerPayload,
        }
        : typeof this.answer === 'string'
          ? {
            answer: this.answer,
            ...providerPayload,
          }
          : {
            ...this.answer,
            ...providerPayload,
          }),
      signal: this.abortController.signal,
    };
  }
}

function streamErrorMessage(err: unknown, fallback: string): string {
  const payload = (err as { payload?: { detail?: unknown; error?: unknown } } | null)?.payload;
  const detail = payload?.detail ?? payload?.error;
  if (typeof detail === 'string' && detail.trim()) return detail;
  const message = (err as { message?: unknown } | null)?.message;
  return typeof message === 'string' && message ? message : fallback;
}

export function runStoryChatAgUiStream(
  chapterId: number,
  conversationId: string,
  message: string,
  requestId: string | undefined,
  onEvent: (event: MangaAgentRunEvent) => void,
  onErrorOrModel?: ((error: string) => void) | string,
  modelArg?: string,
): AbortController {
  const controller = new AbortController();
  const onError = typeof onErrorOrModel === 'function' ? onErrorOrModel : undefined;
  const model = typeof onErrorOrModel === 'string' ? onErrorOrModel : modelArg;
  const agent = new ArtVerseMangaAgentHttpAgent(
    `${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/ag-ui/run`,
    message,
    requestId,
    controller,
    undefined,
    model,
  );
  const runId = requestId || randomUUID();
  const subscription = agent.run({
    threadId: `story-chat-${chapterId}-${conversationId}`,
    runId,
    state: {},
    messages: [{ id: `user-${runId}`, role: 'user', content: message }],
    tools: [],
    context: [],
    forwardedProps: {},
  }).subscribe(storyChatStreamObserver(controller, runId, onEvent, onError));
  controller.signal.addEventListener('abort', () => subscription.unsubscribe());
  return controller;
}

export async function decideNovelDraft(chapterId: number, conversationId: string, requestId: string, artifactId: string,
  body: { decision: 'confirm' | 'discard'; content: string; base_version: number; expected_content_hash: string },
): Promise<{ changed: boolean; chapter_version: number; status: string }> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/runs/${requestId}/artifacts/${artifactId}/decision`, {
    method: 'POST', headers: apiHeaders(true, 'POST'), body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export function resumeStoryChatAgUiStream(
  chapterId: number,
  conversationId: string,
  requestId: string,
  decision: 'confirm' | 'discard',
  artifactId: string,
  onEvent: (event: MangaAgentRunEvent) => void,
  model?: string,
): AbortController {
  const controller = new AbortController();
  const agent = new ArtVerseMangaAgentHttpAgent(
    `${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/ag-ui/runs/${requestId}/resume`,
    '',
    requestId,
    controller,
    { decision, artifact_id: artifactId, artifactId },
    model,
  );
  const subscription = agent.run({
    threadId: `story-chat-${chapterId}-${conversationId}`,
    runId: requestId,
    state: {},
    messages: [],
    tools: [],
    context: [],
    forwardedProps: {},
  }).subscribe(storyChatStreamObserver(controller, requestId, onEvent));
  controller.signal.addEventListener('abort', () => subscription.unsubscribe());
  return controller;
}

function storyChatStreamObserver(controller: AbortController, requestId: string,
  onEvent: (event: MangaAgentRunEvent) => void, onError?: (detail: string) => void) {
  let finished = false;
  const fail = (detail: string) => {
    if (finished || controller.signal.aborted) return;
    finished = true;
    onError?.(detail);
    onEvent({ type: 'error', data: { detail, requestId } });
  };
  return {
    next(event: { type: string }) {
      if (event.type === 'RUN_FINISHED' || event.type === 'RUN_ERROR') finished = true;
      onEvent({ type: 'ag_ui_event', data: event as ArtVerseAgUiEvent });
    },
    error(err: unknown) { fail(streamErrorMessage(err, '连接已断开，请刷新查看任务状态。')); },
    complete() { fail('回复连接提前结束，请刷新查看任务状态。'); },
  };
}

async function consumeMangaAgentEventResponse(
  response: Response,
  requestId: string | undefined,
  onEvent: (event: MangaAgentRunEvent) => void,
  onEventId?: (eventId: number) => void,
): Promise<void> {
  void requestId;
  const reader = response.body?.getReader();
  if (!reader) throw new Error('Agent stream is unavailable');
  const decoder = new TextDecoder();
  let buffer = '';
  let currentEvent = 'message';
  const handleLine = (line: string) => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith(':')) return;
    if (trimmed.startsWith('id:')) {
      const parsed = Number(trimmed.slice(3).trim());
      if (Number.isSafeInteger(parsed) && parsed >= 0) onEventId?.(parsed);
      return;
    }
    if (trimmed.startsWith('event:')) {
      currentEvent = trimmed.slice(6).trim();
      return;
    }
    if (!trimmed.startsWith('data:')) return;

    const dataStr = trimmed.slice(5).trim();
    try {
      const data = JSON.parse(dataStr);
      if (isAgUiEventPayload(data) && (currentEvent === 'message' || currentEvent === 'ag_ui_event')) {
        onEvent({ type: 'ag_ui_event', data });
      } else if (currentEvent === 'status'
        || currentEvent === 'run_event'
        || currentEvent === 'tool'
        || currentEvent === 'user_input_requested'
        || currentEvent === 'done'
        || currentEvent === 'error') {
        onEvent({ type: currentEvent, data } as MangaAgentRunEvent);
      }
    } catch {
      // Ignore malformed stream chunks; the durable cursor remains unchanged.
    } finally {
      currentEvent = 'message';
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';
    for (const line of lines) handleLine(line);
  }
  if (buffer.trim()) handleLine(buffer);
}

function isAgUiEventPayload(value: unknown): value is ArtVerseAgUiEvent {
  if (!value || typeof value !== 'object') return false;
  const type = (value as { type?: unknown }).type;
  return typeof type === 'string' && Object.values(EventType).includes(type as EventType);
}

export async function getStoryChatRunArtifacts(
  chapterId: number,
  conversationId: string,
  requestId: string,
): Promise<MangaAgentArtifact[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/runs/${requestId}/artifacts`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await readApiData(res);
  return Array.isArray(data) ? data.map((item: Record<string, unknown>) => ({
    artifactId: String(item.artifact_id ?? item.artifactId ?? ''),
    requestId: String(item.request_id ?? item.requestId ?? requestId),
    type: String(item.type ?? ''),
    status: String(item.status ?? 'DRAFT') as MangaAgentArtifact['status'],
    schemaVersion: String(item.schema_version ?? item.schemaVersion ?? '1'),
    payload: (item.payload ?? {}) as Record<string, unknown>,
    evaluation: (item.evaluation ?? {}) as Record<string, unknown>,
    checksum: String(item.checksum ?? ''),
  })) : [];
}

export async function getOpenStoryChatConversationRun(
  chapterId: number,
  conversationId: string,
): Promise<MangaAgentRunSnapshot | null> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/runs/open`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await readApiData(res);
  const run = data.run as (MangaAgentRunSnapshot & { current_phase?: string }) | null;
  return run ? { ...run, requestId: run.requestId ?? run.request_id ?? '', currentPhase: run.currentPhase ?? run.current_phase } : null;
}

export async function cancelStoryChatConversationRun(
  chapterId: number,
  conversationId: string,
  requestId: string,
): Promise<MangaAgentRunSnapshot> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/runs/${requestId}/cancel`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export function replayStoryChatRunEvents(
  chapterId: number,
  conversationId: string,
  requestId: string,
  afterEventId: number,
  onEvent: (event: MangaAgentRunEvent) => void,
): AbortController {
  const controller = new AbortController();
  let cursor = Number.isSafeInteger(afterEventId) && afterEventId > 0 ? afterEventId : 0;
  let failures = 0;
  let finished = false;
  const deliver = (event: MangaAgentRunEvent) => {
    if (event.type === 'ag_ui_event') {
      const data = event.data;
      const replay = data as unknown as { name?: string; value?: { eventName?: string } };
      finished ||= data.type === 'RUN_FINISHED' || data.type === 'RUN_ERROR'
        || (data.type === 'STATE_SNAPSHOT' && !!data.snapshot?.status && data.snapshot.status !== 'RUNNING')
        || (replay.name === 'artverse.replayed_run_event' && ['done', 'error', 'user_input_requested'].includes(replay.value?.eventName || ''));
    }
    onEvent(event);
  };

  const tail = async () => {
    while (!controller.signal.aborted) {
      try {
        const response = await authFetch(
          `${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/runs/${requestId}/events`,
          {
            headers: {
              Accept: 'text/event-stream',
              'Last-Event-ID': String(cursor),
            },
            signal: controller.signal,
          },
        );
        if (!response.ok) throw new Error(parseApiError(await response.text()));
        await consumeMangaAgentEventResponse(response, requestId, deliver, (eventId) => { cursor = eventId; });
        if (!finished) throw new Error('Replay ended before a terminal event');
        return;
      } catch (error) {
        if (controller.signal.aborted || (error as Error | null)?.name === 'AbortError') return;
        if (finished) return;
        if (++failures >= 3) {
          onEvent({ type: 'error', data: { detail: '连接已断开，请刷新查看任务状态。', requestId } });
          return;
        }
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
      }
    }
  };
  void tail();
  return controller;
}

