import { authFetch, readApiData, parseApiError } from './auth';
import { randomUUID } from '../utils/browserSecurity';
import type { AgentUserInputRequest } from './agentRuns';

export type AutomationExecutorKey = 'MANGA_AGENT' | 'STORY_CHAT' | 'MANGA_IMAGE' | 'IMAGE_GEN';
export interface AutomationSchedule {
  kind: 'ONCE' | 'DAILY' | 'WEEKLY';
  localDateTime?: string | null;
  localTime?: string | null;
  timezone: string;
  weekdays?: number[] | null;
}
export interface AutomationPayload {
  version?: number;
  storyId?: number;
  chapterId?: number;
  chapterIds?: number[];
  modelConfigId?: number;
  model?: string;
  inputPolicy?: 'FIXED_SNAPSHOT' | 'RESOLVE_AT_RUN';
  size?: string;
  referenceImageKeys?: string[];
  conversationId?: string;
}
export interface AutomationDefinition {
  name: string;
  taskType: 'AGENT' | 'IMAGE';
  executorKey: AutomationExecutorKey;
  instruction: string;
  schedule: AutomationSchedule;
  payload: AutomationPayload;
  limits: { maxImagesPerRun: number; maxModelCallsPerRun: number; maxDurationSeconds: number };
  misfirePolicy: 'RUN_LATEST' | 'SKIP';
  overlapPolicy: 'SKIP';
  maxLatenessSeconds?: number | null;
  expectedVersion?: number;
  origin?: string;
  sourceConversationId?: string;
}
export interface AutomationPlan {
  id: string; status: 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'ARCHIVED'; version: number;
  nextRunAt: string | null; definition: AutomationDefinition; createdAt: string; updatedAt: string;
}
export interface AutomationCommandProposal {
  artifactId: string; requestId: string; chapterId: number; conversationId: string;
  status: 'AWAITING_REVIEW' | 'COMMITTED' | 'REJECTED'; commandHash: string;
  action: 'create' | 'edit' | 'pause' | 'resume' | 'archive'; automationId: string | null;
  expectedVersion: number | null; definition: AutomationDefinition; result: AutomationPlan | null;
  applied: boolean; expiresAt: string; reviewUrl: string;
}
export interface AutomationCommandPage { items: AutomationCommandProposal[]; nextCursor: string | null; hasMore: boolean }
export interface AutomationRun {
  id: string; automationId: string; automationVersion: number; triggerKind: 'SCHEDULED' | 'MANUAL';
  scheduledFor: string; definition: AutomationDefinition;
  state: 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'WAITING_USER' | 'CANCEL_REQUESTED' | 'SUCCEEDED' | 'PARTIAL_FAILED' | 'FAILED' | 'CANCELLED' | 'SKIPPED';
  attemptCount: number; summary: string | null; errorCode: string | null; errorMessage: string | null;
  waitingReason: string | null; createdAt: string; startedAt: string | null; completedAt: string | null;
}
export interface AutomationExecution {
  id: number; targetKey: string; executionKind: AutomationExecutorKey;
  agentRunId: number | null; scheduledMangaId: string | null; imageRecordId: number | null;
  conversationId: string | null; requestId: string | null;
}
export interface AutomationEvent { id: number; type: string; payload: { summary?: string; code?: string }; createdAt: string }
export interface AutomationRunDetail { run: AutomationRun; executions: AutomationExecution[]; events: AutomationEvent[]; artifacts?: AutomationArtifact[]; reviewReceipt?: { artifact_id: string; status: 'COMMITTED' | 'REJECTED' }; userInputRequest?: AgentUserInputRequest & { artifactId?: string } }
export interface AutomationArtifact { kind: string; id: string; title: string; url?: string; content?: string; state?: string; imageNumber?: number; error?: string; review?: { chapterId: number; conversationId: string; requestId: string; baseVersion: number; currentVersion: number; contentHash: string; originalContent: string } }
export interface AutomationNotification { id: number; runId: string; name: string; kind: string; summary: string; read: boolean; createdAt: string }
export interface AutomationSeed { executorKey?: AutomationExecutorKey; storyId?: number; chapterId?: number; instruction?: string; modelConfigId?: number; model?: string; size?: string; conversationId?: string; referenceFiles?: File[]; origin?: string }

async function request<T>(path: string, method = 'GET', body?: unknown, key?: string): Promise<T> {
  const res = await authFetch(`/api/${path}`, { method, headers: {
    ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    ...(key ? { 'Idempotency-Key': key } : {}),
  }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData<T>(res);
}
export const listAutomations = (params: URLSearchParams) => request<{ items: AutomationPlan[]; hasMore: boolean }>(`automations?${params}`);
export const listAutomationCommands = (chapterId?: number, conversationId?: string, before?: string) => {
  const params = new URLSearchParams();
  if (chapterId !== undefined) params.set('chapterId', String(chapterId));
  if (conversationId !== undefined) params.set('conversationId', conversationId);
  if (before) params.set('before', before);
  return request<AutomationCommandPage>(`automation-command-proposals?${params}`);
};
export const decideAutomationCommand = (proposal: AutomationCommandProposal, decision: 'confirm' | 'discard') =>
  request<AutomationCommandProposal>(`automation-command-proposals/${proposal.artifactId}/decision`, 'POST', {
    decision, commandHash: proposal.commandHash, expectedVersion: proposal.expectedVersion,
  });
export const getAutomation = (id: string) => request<AutomationPlan>(`automations/${id}`);
export const saveAutomation = (definition: AutomationDefinition, id?: string, key = randomUUID()) => request<AutomationPlan>(id ? `automations/${id}` : 'automations', id ? 'PATCH' : 'POST', definition, key);
export const previewAutomation = (schedule: AutomationSchedule) => request<{ occurrences: { instant: string; localTime: string }[] }>('automations/schedule-preview', 'POST', { schedule });
export const changeAutomation = (plan: AutomationPlan, action: 'pause' | 'resume' | 'archive') => request<AutomationPlan>(`automations/${plan.id}/${action}`, 'POST', { expectedVersion: plan.version });
export const runAutomationNow = (id: string, key: string) => request<AutomationRun>(`automations/${id}/run-now`, 'POST', undefined, key);
export const listAutomationRuns = (id: string, before?: string) => request<{ items: AutomationRun[] }>(`automations/${id}/runs${before ? `?before=${before}` : ''}`);
export const getAutomationRun = (id: string) => request<AutomationRunDetail>(`automation-runs/${id}`);
export const actOnAutomationRun = (id: string, action: 'cancel' | 'retry' | 'respond', key: string, response?: unknown) => request<AutomationRun>(`automation-runs/${id}/${action}`, 'POST', response, key);
export const listAutomationNotifications = () => request<{ items: AutomationNotification[] }>('automation-notifications');
export const readAutomationNotification = (id: number) => request(`automation-notifications/${id}/read`, 'POST');
export async function uploadAutomationReference(file: File): Promise<string> {
  const body = new FormData(); body.append('file', file);
  const res = await authFetch('/api/automations/references', { method: 'POST', body });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return (await readApiData<{ objectKey: string }>(res)).objectKey;
}
export function openAutomationComposer(seed: AutomationSeed = {}) { window.dispatchEvent(new CustomEvent('artverse:create-automation', { detail: seed })); }
export const automationStateLabel: Record<string, string> = { ACTIVE: '启用中', PAUSED: '已暂停', COMPLETED: '已结束', ARCHIVED: '已归档', QUEUED: '等待执行', RUNNING: '运行中', RETRY_WAIT: '等待重试', WAITING_USER: '需要处理', CANCEL_REQUESTED: '正在停止', SUCCEEDED: '已完成', PARTIAL_FAILED: '部分失败', FAILED: '执行失败', CANCELLED: '已取消', SKIPPED: '已跳过' };
export function automationTime(value: string | null, zone = 'Asia/Shanghai') {
  if (!value) return '—';
  try { return new Intl.DateTimeFormat('zh-CN', { timeZone: zone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)); } catch { return value; }
}
export function automationRule(schedule: AutomationSchedule) {
  const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
  return `${schedule.kind === 'ONCE' ? schedule.localDateTime?.replace('T', ' ') : schedule.kind === 'DAILY' ? `每天 ${schedule.localTime}` : `每周${schedule.weekdays?.map(d => weekdays[d - 1]).join('、')} ${schedule.localTime}`} · ${schedule.timezone}`;
}
