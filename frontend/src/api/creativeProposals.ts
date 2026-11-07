import { authFetch, parseApiError, readApiData } from './auth';

export interface CreativeProposal {
  artifactId: string; kind: 'SETTINGS_CHANGE' | 'CHARACTER_CHANGE' | 'IMAGE_PROPOSAL' | 'IMAGE_RECOVERY';
  status: 'DRAFT' | 'APPROVED_PENDING' | 'COMMITTED' | 'REJECTED'; commandHash: string;
  chapterId: number; conversationId: string; expiresAt: string;
  before?: Record<string, string>; after?: Record<string, string>; base_version?: string;
  character_id?: string; mode?: 'CREATE' | 'UPDATE';
  selection?: { config_id: number; config_version: string; model: string; mode: string; image_numbers: number[]; asset_group_id?: number | null; size?: string | null };
  targets?: { image_number: number; expected_image_id: string | null; expected_image_version: string | null; context_hash: string; reference_count: number }[];
  request_id?: string; operation_id?: string; recovery_action?: string; recovery_version?: string;
  model_calls?: number; execution_lane?: string; estimated_cost?: string; quota_availability?: string;
  receipt: { approved?: boolean; accepted?: boolean; candidate_id?: string; character_id?: string; saved_version?: string; accepted_requests?: string[] };
}
interface CreativeProposalPage { items: CreativeProposal[]; hasMore: boolean; nextCursor: string }
async function request<T>(path: string, body?: unknown): Promise<T> {
  const res = await authFetch(`/api/creative-proposals${path}`, body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData<T>(res);
}
export function listCreativeProposals(chapterId?: number, conversationId?: string, before?: string) {
  const params = new URLSearchParams();
  if (chapterId !== undefined) params.set('chapterId', String(chapterId));
  if (conversationId) params.set('conversationId', conversationId);
  if (before) params.set('before', before);
  return request<CreativeProposalPage>(`?${params}`);
}
export const decideCreativeProposal = (proposal: CreativeProposal, decision: 'confirm' | 'discard') =>
  request<CreativeProposal>(`/${proposal.artifactId}/decision`, { decision, commandHash: proposal.commandHash });
