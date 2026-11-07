import type { AgentRunTimelineEvent, MangaAgentRunEvent, MangaAgentRunSnapshot } from '../api/agentRuns';

type ActivityStatus = 'running' | 'waiting' | 'succeeded' | 'failed' | 'cancelled';
interface StoryChatActivityStep {
  id: string; label: string; status: ActivityStatus; toolName?: string;
  detail?: string; durationMs?: number; startedAt: number; finishedAt?: number;
}
export interface StoryChatActivity {
  requestId: string; status: ActivityStatus; startedAt: number; updatedAt: number;
  steps: StoryChatActivityStep[]; seen: string[]; error?: string;
  reviewed?: boolean;
}
export type StoryChatActivities = Record<string, StoryChatActivity>;

const toolLabels: Record<string, string> = {
  get_novel_context: '读取当前章节', draft_novel_content: '生成修改草稿',
  commit_novel_content: '保存章节新版本', ask_user: '人工审核修改',
  get_story_context: '读取作品设定', list_chapters: '查看章节目录',
  search_story_knowledge: '检索已确认知识', get_knowledge_detail: '读取知识详情',
  get_character_context: '读取角色信息', list_reference_assets: '查看参考素材',
  get_artifact: '读取草稿产物', list_artifacts: '查看产物列表', discard_artifact: '丢弃未审核草稿',
  list_novel_revisions: '查看正文历史版本', get_novel_revision: '读取正文历史版本', draft_novel_restore: '生成历史恢复草稿',
  list_knowledge_candidates: '查看待审知识候选', get_knowledge_candidate: '读取候选详情',
  preview_automation: '预览任务计划', get_automation: '读取任务计划', list_automations: '查看任务计划',
  list_automation_runs: '查看任务运行记录', get_automation_run: '读取任务运行详情',
  list_generation_models: '查看可用生图模型', draft_story_settings: '准备作品设定修改',
  draft_character_profile: '准备人物资料修改', draft_knowledge_change: '追加待审知识候选',
  preview_manga_generation: '预览漫画生图范围', propose_manga_generation: '准备生图审核提案',
  get_generation_request: '查看图片请求实际状态', propose_generation_recovery: '准备图片恢复审核提案',
};
type Data = Record<string, unknown>;
const object = (v: unknown): Data => v && typeof v === 'object' ? v as Data : {};

// Both the live stream and durable replay pass through this reducer. No provider thinking
// text or raw tool payload is rendered; the UI exposes actual phases and safe outcome summaries.
export function applyStoryChatActivity(previous: StoryChatActivities, requestId: string,
  input: MangaAgentRunEvent, receivedAt = Date.now()): StoryChatActivities {
  let eventName: string = input.type;
  let payload = object(input.data);
  let eventKey = '';
  let at = receivedAt;
  if (eventName === 'ag_ui_event') {
    eventName = String(payload.type);
    if (typeof payload.timestamp === 'number') at = payload.timestamp;
    if (eventName === 'CUSTOM') {
      const name = String(payload.name);
      payload = object(payload.value);
      if (name === 'artverse.replayed_run_event') {
        eventName = String(payload.eventName);
        eventKey = `event:${String(payload.eventId)}`;
        if (typeof payload.createdAt === 'string') at = Date.parse(payload.createdAt) || at;
        payload = object(payload.data);
      } else if (name === 'artverse.tool_audit') {
        eventName = 'tool'; payload = object(payload.payload);
      } else eventName = 'run_event';
    } else if (eventName === 'STATE_SNAPSHOT') {
      eventName = 'phase'; payload = object(payload.snapshot);
    }
  }
  if (eventName === 'phase' && payload.snapshot) payload = object(payload.snapshot);
  if (eventName === 'run_event' && payload.type === 'text_delta') return previous;
  if (['TEXT_MESSAGE_START', 'TEXT_MESSAGE_CONTENT', 'TEXT_MESSAGE_END'].includes(eventName)) return previous;
  if (typeof payload.createdAt === 'string') at = Date.parse(payload.createdAt) || at;
  const raw = payload as unknown as AgentRunTimelineEvent;
  const data = object(payload.data);
  const identity = eventName === 'tool' ? payload.auditId : data.toolCallId;
  // Semantic identity also deduplicates the same event received live and then via replay.
  eventKey = eventName === 'run_event' || eventName === 'tool'
    ? `${eventName}:${String(payload.type || payload.status)}:${String(identity || '')}:${String(payload.createdAt || payload.auditId || '')}`
    : eventKey || `${eventName}:${at}:${String(payload.phase || '')}:${String(payload.status || '')}`;
  const old = previous[requestId];
  if (old?.seen.includes(eventKey)) return previous;
  const activity: StoryChatActivity = old
    ? { ...old, steps: old.steps.map(step => ({ ...step })), seen: [...old.seen, eventKey], updatedAt: Math.max(old.updatedAt, at) }
    : { requestId, status: 'running', startedAt: at, updatedAt: at, steps: [], seen: [eventKey] };
  activity.startedAt = Math.min(activity.startedAt, at);
  const step = (id: string, label: string, status: ActivityStatus, toolName?: string) => {
    let current = activity.steps.find(item => item.id === id);
    if (!current) { current = { id, label, status, toolName, startedAt: at }; activity.steps.push(current); }
    // Audits may arrive before framework start events; do not regress a completed result.
    if (!(current.finishedAt && status === 'running')) current.status = status;
    if (status === 'succeeded' || status === 'failed' || status === 'cancelled') current.finishedAt = at;
    return current;
  };
  const finish = (status: ActivityStatus) => {
    activity.status = status;
    for (const item of activity.steps) if (item.status === 'running' || (status !== 'waiting' && item.status === 'waiting')) {
      item.status = status; if (status !== 'waiting') item.finishedAt = at;
    }
  };
  if (eventName === 'status' || eventName === 'RUN_STARTED') { activity.status = 'running'; activity.error = undefined; }
  if (eventName === 'phase') {
    const status = String(payload.status || 'RUNNING');
    if (status === 'RUNNING') {
      activity.status = 'running';
      if (payload.phase === 'PREPARING_CONTEXT' || payload.phase === 'BUILDING_REQUEST') {
        const id = String(payload.phase);
        for (const item of activity.steps) if (item.status === 'running' && item.id !== id) {
          item.status = 'succeeded'; item.finishedAt = at;
        }
        step(id, id === 'PREPARING_CONTEXT' ? '读取章节与版本' : '整理创作上下文', 'running');
      }
    } else if (status === 'WAITING_USER') finish('waiting');
    else if (status === 'SUCCEEDED') finish('succeeded');
    else if (status === 'CANCELLED') finish('cancelled');
    else finish('failed');
  }
  if (eventName === 'run_event') {
    if (raw.type === 'model_started') {
      activity.status = 'running';
      activity.error = undefined;
      for (const item of activity.steps) if (['PREPARING_CONTEXT', 'BUILDING_REQUEST'].includes(item.id) && item.status === 'running') {
        item.status = 'succeeded'; item.finishedAt = at;
      }
      step(`model:${raw.createdAt || at}`, '思考并分析本章', 'running');
    } else if (raw.type === 'model_finished') {
      const model = [...activity.steps].reverse().find(item => item.id.startsWith('model:') && item.status === 'running');
      if (model) { model.status = 'succeeded'; model.finishedAt = at; }
    } else if (raw.toolName && data.toolCallId) {
      const status = raw.type === 'tool_finished'
        ? raw.status === 'success' || raw.status === 'succeeded' ? 'succeeded'
          : raw.status === 'error' || raw.status === 'failed' ? 'failed' : raw.status === 'running' ? 'waiting' : 'running'
        : 'running';
      step(String(data.toolCallId), toolLabels[raw.toolName] || raw.toolName, status, raw.toolName);
    } else if (raw.type === 'review_saved' || raw.type === 'review_resumed') {
      activity.reviewed = true;
      for (const item of activity.steps) if (item.toolName === 'ask_user') { item.status = 'succeeded'; item.finishedAt = at; }
      step(raw.type, raw.label || '人工审核已完成，继续总结', 'succeeded');
      activity.status = 'running';
      activity.error = undefined;
    } else if (raw.type === 'execution_error') {
      activity.error = raw.label; finish('failed');
    }
  }
  if (eventName === 'tool') {
    const name = String(payload.tool || '');
    const candidates = activity.steps.filter(item => item.toolName === name && !item.detail);
    const target = candidates.length === 1 ? candidates[0] : undefined;
    if (target) {
      if (typeof payload.durationMs === 'number') target.durationMs = payload.durationMs;
      const result = object(payload.result);
      target.detail = typeof payload.error === 'string' ? payload.error
        : name === 'get_novel_context' && result.chapter_number != null ? `第 ${result.chapter_number} 章 · 基于版本 ${result.chapter_version}`
          : name === 'draft_novel_content' || name === 'draft_novel_restore' ? '完整修改稿已生成，等待作者审阅。'
            : name === 'ask_user' ? '作者确认前不会修改正文。' : '执行结果已记录。';
      if (payload.succeeded === false) { target.status = 'failed'; target.finishedAt = at; }
    }
  }
  if (eventName === 'user_input_requested') finish('waiting');
  if (eventName === 'RUN_FINISHED') finish(object(payload.outcome).type === 'interrupt' ? 'waiting' : 'succeeded');
  if (eventName === 'done') finish('succeeded');
  if (eventName === 'error' || eventName === 'RUN_ERROR') {
    activity.error = String(payload.message || payload.detail || payload.error || '本轮执行失败。'); finish('failed');
  }
  return { ...previous, [requestId]: activity };
}

export function restoreStoryChatActivity(previous: StoryChatActivities, run: MangaAgentRunSnapshot): StoryChatActivities {
  let state: StoryChatActivities = {};
  for (const event of run.events) {
    state = applyStoryChatActivity(state, run.requestId, { type: 'ag_ui_event', data: {
      type: 'CUSTOM', name: 'artverse.replayed_run_event', value: event,
    } } as unknown as MangaAgentRunEvent, Date.parse(event.createdAt || run.createdAt || '') || Date.now());
  }
  const restored = applyStoryChatActivity(state, run.requestId, { type: 'ag_ui_event', data: {
    type: 'STATE_SNAPSHOT', snapshot: { status: run.status }, timestamp: Date.parse(run.updatedAt || '') || Date.now(),
  } } as MangaAgentRunEvent);
  const live = previous[run.requestId];
  if (live?.updatedAt > restored[run.requestId].updatedAt) {
    // RUN_FINISHED can arrive milliseconds after the transaction timestamp. Keep its
    // newer status, but still merge durable-only events such as review_saved.
    const steps = restored[run.requestId].steps.map(saved => {
      const current = live.steps.find(item => item.id === saved.id);
      if (current) return { ...saved, ...current, detail: current.detail ?? saved.detail, durationMs: current.durationMs ?? saved.durationMs };
      return ['succeeded', 'failed', 'cancelled'].includes(live.status) && ['waiting', 'running'].includes(saved.status)
        ? { ...saved, status: live.status, finishedAt: live.updatedAt } : saved;
    });
    steps.push(...live.steps.filter(item => !steps.some(saved => saved.id === item.id)));
    return { ...previous, [run.requestId]: { ...live, reviewed: live.reviewed || restored[run.requestId].reviewed, steps } };
  }
  return { ...previous, [run.requestId]: restored[run.requestId] };
}
