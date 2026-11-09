import { BASE, authFetch, parseApiError, readApiData } from './auth';

export type AiPermissionMode = 'MANUAL' | 'AUTO' | 'FULL';

export const AI_PERMISSION_CHANGE_EVENT = 'artverse:ai-permission-change';

export interface AiPermissionOption {
  value: AiPermissionMode;
  label: string;
  description: string;
}

export const AI_PERMISSION_OPTIONS: AiPermissionOption[] = [
  { value: 'MANUAL', label: '手动审批', description: '只能读取资料并给出建议，不直接创建草稿或提案；所有修改由你确认后执行。' },
  { value: 'AUTO', label: '自动审批', description: '可自动创建草稿和提案；正式内容提交仍需你确认。' },
  { value: 'FULL', label: '完全访问', description: '可自动执行全部已授权工具，包括最终提交。' },
];

export function isAiPermissionMode(value: unknown): value is AiPermissionMode {
  return value === 'MANUAL' || value === 'AUTO' || value === 'FULL';
}

function modeFromPayload(payload: Record<string, unknown>): AiPermissionMode {
  const value = payload.permission_mode ?? payload.permissionMode;
  return isAiPermissionMode(value) ? value : 'AUTO';
}

export async function fetchAiPermission(): Promise<AiPermissionMode> {
  const res = await authFetch(`${BASE}/api/user/ai-assistant/permission`, { cache: 'no-store' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return modeFromPayload(await readApiData(res));
}

export async function saveAiPermission(mode: AiPermissionMode): Promise<AiPermissionMode> {
  const res = await authFetch(`${BASE}/api/user/ai-assistant/permission`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ permission_mode: mode }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const saved = modeFromPayload(await readApiData(res));
  window.dispatchEvent(new CustomEvent(AI_PERMISSION_CHANGE_EVENT, { detail: saved }));
  return saved;
}
