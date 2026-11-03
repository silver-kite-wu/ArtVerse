export type StoryChatPhase = 'IDLE' | 'SAVING' | 'PREPARING_CONTEXT' | 'BUILDING_REQUEST'
  | 'WAITING_MODEL' | 'GENERATING' | 'WAITING_USER' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface StoryChatTaskState { phase: StoryChatPhase; requestId: string | null; accepted: boolean }
export const initialStoryChatTask: StoryChatTaskState = { phase: 'IDLE', requestId: null, accepted: false };
export const isStoryChatBusy = (phase: StoryChatPhase) =>
  ['SAVING', 'PREPARING_CONTEXT', 'BUILDING_REQUEST', 'WAITING_MODEL', 'GENERATING'].includes(phase);

const labels: Partial<Record<StoryChatPhase, string>> = {
  SAVING: '正在保存当前正文', PREPARING_CONTEXT: '正在读取章节内容',
  BUILDING_REQUEST: '正在整理创作上下文', WAITING_MODEL: '正在分析本章', GENERATING: '正在生成回复',
};
export const storyChatPhaseLabel = (phase: StoryChatPhase) => labels[phase] || '正在处理请求';

type Action = { type: 'reset' } | { type: 'start'; requestId: string; phase: StoryChatPhase }
  | { type: 'accept'; requestId: string }
  | { type: 'phase'; requestId: string; phase: StoryChatPhase };

export function storyChatTaskReducer(state: StoryChatTaskState, action: Action): StoryChatTaskState {
  if (action.type === 'reset') return initialStoryChatTask;
  if (action.type === 'start') return { requestId: action.requestId, phase: action.phase, accepted: false };
  if (state.requestId !== action.requestId || !isStoryChatBusy(state.phase)) return state;
  if (action.type === 'accept') return { ...state, accepted: true };
  return { ...state, phase: action.phase };
}

export function parseStoryChatPhase(value?: string): StoryChatPhase {
  return ['PREPARING_CONTEXT', 'BUILDING_REQUEST', 'WAITING_MODEL', 'GENERATING'].includes(value || '')
    ? value as StoryChatPhase : 'WAITING_MODEL';
}
