import { readApiData, BASE, parseApiError } from './auth';

export interface GuardActionStats {
  action: string;
  total: number;
  leader: number;
  follower: number;
  success_hit: number;
  failed_hit: number;
  follower_rejected: number;
  processing_rejected: number;
  failed: number;
  hit_rate: number;
  reuse_rate: number;
  single_flight_rate: number;
  reject_rate: number;
}

export interface GuardStatsPayload {
  updated_at: string;
  actions: GuardActionStats[];
}

export async function getGuardStats(): Promise<GuardStatsPayload> {
  const res = await fetch(BASE + '/api/internal/guard/stats');
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export interface GuardMetricBucket {
  bucket_type: string;
  bucket_start: string;
  action: string;
  total: number;
  leader: number;
  follower: number;
  success_hit: number;
  failed_hit: number;
  follower_rejected: number;
  processing_rejected: number;
  failed: number;
}

export async function getGuardMetrics(bucket = 'HOUR', range = 24): Promise<{ updated_at: string; bucket_type: string; items: GuardMetricBucket[] }> {
  const res = await fetch(BASE + '/api/internal/guard/metrics?bucket=' + bucket + '&range=' + range);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export interface GuardEvent {
  id: string;
  time: string;
  action: string;
  scope: string;
  decision: string;
  result: string;
  key_hash: string;
  duration_ms?: number | null;
  summary?: Record<string, unknown>;
  message?: string;
}

export async function getGuardEvents(limit = 100): Promise<{ events: GuardEvent[] }> {
  const res = await fetch(BASE + '/api/internal/guard/events?limit=' + limit);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

