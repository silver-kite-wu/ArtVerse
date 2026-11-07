import { ApiError, authFetch, BASE, getUser, readApiData } from './auth';
import { randomUUID, stableDigest } from '../utils/browserSecurity';

export type ImageRequestStatus = 'QUEUED' | 'LOAD_CONTEXT' | 'GENERATING' | 'UPLOADING' | 'COMMITTING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED' | 'STALE' | 'NEEDS_REVIEW' | 'PARTIAL_FAILED';
interface ImageOperation {
  request_id: string; operation_id: string; image_number: number | null;
  status: ImageRequestStatus | null; state_epoch: string | null; version: number;
  record_id: number | null; manga_image_id: number | null; image_version: string | null;
  image_path: string | null; image_url: string | null; model: string; size: string; prompt: string;
  completed_at: string | null; error_code: string | null; evidence_summary: string | null;
  recovery_version: number; recovery_actions: string[]; blocks_serial_lane: boolean;
  review_requested_at: string | null; last_activity_at: string | null;
  recovery_in_progress?: boolean; next_check_at?: string | null;
  expected_image_id?: number | null; expected_image_version?: string | null;
}
export interface ImageRequestSnapshot {
  request_id: string; status: ImageRequestStatus | null; submission_status: string;
  execution_mode: string; total_images: number; completed_images: number; failed_images: number;
  current_image_number: number | null; images: ImageOperation[]; conversation_id?: string;
}
export const IMAGE_REQUEST_CHANGE = 'artverse:image-request-change';
export const IMAGE_STATUS_LABELS: Record<ImageRequestStatus, string> = {
  QUEUED: '排队中', LOAD_CONTEXT: '加载输入', GENERATING: '生成中', UPLOADING: '上传中', COMMITTING: '保存中',
  RETRY_WAIT: '等待重试', SUCCEEDED: '已完成', FAILED: '已失败', STALE: '目标已变化', NEEDS_REVIEW: '待核实', PARTIAL_FAILED: '部分失败',
};
export function isImageRequestTerminal(status: ImageRequestStatus | null): boolean {
  return status !== null && ['SUCCEEDED', 'FAILED', 'STALE', 'PARTIAL_FAILED'].includes(status);
}
function storageKey(scope: string) { return `artverse.image-request.${getUser()?.id ?? 'anonymous'}.${scope}`; }
export function trackedImageRequest(scope: string): string | null { return localStorage.getItem(storageKey(scope)); }
export function trackImageRequest(scope: string, id: string) {
  localStorage.setItem(storageKey(scope), id);
  window.dispatchEvent(new CustomEvent(IMAGE_REQUEST_CHANGE, { detail: { scope, id } }));
}
export async function imageRequestResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const payload = await response.json().catch(() => ({})) as { message?: string; code?: number; data?: Record<string, unknown> };
    throw new ApiError(payload.message || '生图服务暂不可用', { status: response.status, code: payload.code, data: payload.data });
  }
  return readApiData<T>(response);
}
export async function submitImageRequest(url: string, body: Record<string, unknown>, scope: string, signal?: AbortSignal): Promise<ImageRequestSnapshot> {
  if (!body.config_id && (!body.provider || !body.api_key || !body.base_url || !body.model)) throw new ApiError('无供应商，请选择生图供应商', { code: 40062 });
  // Only a digest and request identity are persisted; credentials and reference bytes stay in memory.
  const digest = await stableDigest(JSON.stringify(body));
  const pendingKey = storageKey(`pending.${scope}.${digest}`);
  const id = localStorage.getItem(pendingKey) || randomUUID();
  localStorage.setItem(pendingKey, id);
  try {
    const result = await imageRequestResponse<ImageRequestSnapshot>(await authFetch(BASE + url, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': id },
      body: JSON.stringify({ ...body, request_id: id }), signal,
    }));
    trackImageRequest(scope, result.request_id);
    localStorage.removeItem(pendingKey);
    return result;
  } catch (error) {
    if (error instanceof ApiError && error.data?.request_id) {
      const canonical = String(error.data.request_id);localStorage.setItem(pendingKey, canonical);trackImageRequest(scope, canonical);
    }
    // Unknown publish/HTTP outcomes must retain the original request identity for retries.
    throw error;
  }
}
export async function getImageRequest(id: string): Promise<ImageRequestSnapshot> {
  return imageRequestResponse(await authFetch(`${BASE}/api/image-generation/requests/${id}`));
}
export function watchImageRequest(id: string, onSnapshot: (snapshot: ImageRequestSnapshot) => void, onError?: (error: Error) => void): AbortController {
  const controller = new AbortController();
  let snapshot: ImageRequestSnapshot | undefined;
  const source = new EventSource(`${BASE}/api/image-generation/requests/${id}/events`, { withCredentials: true });
  let refreshing = false;
  const deliver = (next: ImageRequestSnapshot, authoritative = false) => {
    if (controller.signal.aborted) return;
    for (const item of next.images) {
      const old = snapshot?.images.find(i => i.operation_id === item.operation_id);
      if (!old) continue;
      if (old.state_epoch === item.state_epoch && old.version > item.version) return;
      // Only a serialized GET can establish a new epoch after Redis rebuilding.
      if (old.state_epoch !== item.state_epoch && !authoritative) { refresh(); return; }
    }
    snapshot = next; onSnapshot(next);
  };
  const refresh = () => {
    if (refreshing || controller.signal.aborted) return;
    refreshing = true;
    void getImageRequest(id).then(next => deliver(next, true)).catch(error => { if (!controller.signal.aborted) onError?.(error); }).finally(() => { refreshing = false; });
  };
  source.addEventListener('snapshot', event => deliver(JSON.parse((event as MessageEvent).data)));
  source.addEventListener('done', event => deliver(JSON.parse((event as MessageEvent).data)));
  for (const event of ['progress', 'image_result', 'image_error', 'needs_review']) {
    source.addEventListener(event, message => {
      const item = JSON.parse((message as MessageEvent).data) as ImageOperation;
      const old = snapshot?.images.find(i => i.operation_id === item.operation_id);
      if (old && (old.state_epoch !== item.state_epoch || old.version > item.version)) { refresh(); return; }
      if (snapshot) deliver({ ...snapshot, status: snapshot.execution_mode === 'SINGLE_IMAGE' ? item.status : snapshot.status, images: [...snapshot.images.filter(i => i.operation_id !== item.operation_id), item] });
      refresh();
    });
  }
  source.onopen = refresh;
  // Pub/Sub and SSE are hints. Periodic GET also covers lost terminal notifications.
  const timer = window.setInterval(refresh, 5000);
  controller.signal.addEventListener('abort', () => { source.close(); window.clearInterval(timer); });
  refresh();
  return controller;
}
export async function imageRecovery(id: string, item: ImageOperation, action: string): Promise<void> {
  const key = storageKey(`recovery.${id}.${item.operation_id}.${item.recovery_version}.${action}`);
  const actionId = localStorage.getItem(key) || randomUUID();localStorage.setItem(key, actionId);
  await imageRequestResponse(await authFetch(`${BASE}/api/image-generation/requests/${id}/operations/${item.operation_id}/recovery-actions`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action_id: actionId, action, expected_recovery_version: item.recovery_version }),
  }));
}
export async function replaceImageRequest(id: string, item: ImageOperation): Promise<string> {
  const key = storageKey(`replacement.${id}.${item.operation_id}.${item.recovery_version}`);
  const next = localStorage.getItem(key) || randomUUID();localStorage.setItem(key, next);
  const result = await imageRequestResponse<ImageRequestSnapshot>(await authFetch(`${BASE}/api/image-generation/requests/${id}/operations/${item.operation_id}/abandon-and-regenerate`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ request_id: next, expected_recovery_version: item.recovery_version,
      ...(item.expected_image_id ? { expected_image_id: item.expected_image_id, expected_image_version: item.expected_image_version } : { expected_absent: true }),
      accept_possible_duplicate_charge: true, use_original_input: true }),
  }));
  return result.request_id;
}
