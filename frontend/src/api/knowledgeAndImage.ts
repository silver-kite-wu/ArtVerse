import { readApiData, BASE, authFetch, parseApiError } from './auth';
import { getProviderRequestPayload } from './providerSettings';
import { submitImageRequest, getImageRequest, type ImageRequestSnapshot, type ImageRequestStatus } from './imageRequests';

function encodeStaticPath(imagePath: string): string {
  return imagePath.split('/').map((part) => encodeURIComponent(part)).join('/');
}

// ---- Image Gen ----
export interface ImageGenRecord {
  id: number;
  request_id?: string;
  conversation_id?: string;
  prompt: string;
  image_url: string | null;
  model: string;
  size: string;
  status: 'RUNNING' | ImageRequestStatus;
  failure_reason: string | null;
  created_at: string;
  completed_at: string | null;
}

// ---- Story knowledge base ----
export type KnowledgeUnitType = 'CHARACTER_CARD' | 'CHARACTER_RELATION' | 'WORLDVIEW' | 'TIMELINE' | 'FORESHADOWING';
export interface KnowledgeUnitInput {
  type: KnowledgeUnitType;
  title: string;
  body: string;
  summary: string;
  structuredData: Record<string, unknown>;
  importance: number;
  effectiveFromChapter?: number | null;
  effectiveToChapter?: number | null;
}
export interface EmbeddingConfigInfo {
  id: number;
  displayName: string;
  baseUrl: string;
  model: string;
  apiKeyMasked: string;
  customHeaders: string;
  status: 'UNVERIFIED' | 'VERIFIED' | 'RETIRED';
  actualDimension: number | null;
  configVersion: number;
  active: boolean;
  usedByStories: boolean;
  usedByStoryTitles?: string[];
}
interface EmbeddingConfigInput {
  configId?: number;
  displayName: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  customHeaders: string;
}
interface KnowledgeRecallPreview {
  items: Array<{ knowledgeUnitId: number; version: number; type: KnowledgeUnitType; title: string; content: string; score: number }>;
  context: string;
  contextHash: string;
  embeddingSpaceId: number;
  snapshotId?: number | null;
}

type NovelRevisionSource = 'manual' | 'ai' | 'restore' | 'legacy_import' | 'generated';

export interface NovelRevision {
  id: number;
  revision_number: number;
  content: string;
  content_hash: string;
  source: NovelRevisionSource;
  created_at: string;
}

interface NovelContentSaveResult {
  changed: boolean;
  chapter_version: number;
  revision_id?: number;
  revision_number?: number;
  content_hash?: string;
  auditId?: string;
}

export async function saveNovelContent(
  chapterId: number,
  content: string,
  baseVersion: number,
): Promise<NovelContentSaveResult> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/novel-content`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content, base_version: baseVersion }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function listNovelRevisions(chapterId: number): Promise<NovelRevision[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/novel-content/revisions`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function restoreNovelRevision(
  chapterId: number,
  revisionId: number,
  baseVersion: number,
): Promise<NovelContentSaveResult> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/novel-content/revisions/${revisionId}/restore`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ base_version: baseVersion }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export interface AiConversationSummary {
  conversationId: string;
  type: 'MANGA_AGENT' | 'STORY_CHAT' | 'IMAGE_GEN';
  title: string;
  titleSource: 'DEFAULT' | 'AI' | 'FALLBACK' | 'USER';
  titleState: 'WAITING' | 'GENERATING' | 'FINALIZED';
  status: 'ACTIVE' | 'ARCHIVED';
  chapterId: number | null;
  lastActivityAt: string;
}

export async function listAiConversations(type: AiConversationSummary['type'], chapterId?: number): Promise<AiConversationSummary[]> {
  const params = new URLSearchParams({ type });
  if (chapterId != null) params.set('chapterId', String(chapterId));
  const res = await authFetch(`${BASE}/api/ai/conversations?${params}`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function renameAiConversation(conversationId: string, title: string): Promise<AiConversationSummary> {
  const res = await authFetch(`${BASE}/api/ai/conversations/${conversationId}/title`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function createAiConversation(type: AiConversationSummary['type'], title?: string, chapterId?: number): Promise<AiConversationSummary> {
  const res = await authFetch(`${BASE}/api/ai/conversations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, title, ...(chapterId != null ? { chapterId } : {}) }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function archiveAiConversation(conversationId: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/ai/conversations/${conversationId}/archive`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export async function deleteAiConversation(conversationId: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/ai/conversations/${conversationId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export async function archiveKnowledge(storyId: number, id: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}
export async function getEmbeddingConfigs(): Promise<EmbeddingConfigInfo[]> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await readApiData(res);
  return Array.isArray(data) ? data.map(embeddingConfigFromResponse) : [];
}
function embeddingConfigPayload(input: EmbeddingConfigInput): Record<string, unknown> {
  return { config_id: input.configId, display_name: input.displayName, base_url: input.baseUrl, api_key: input.apiKey, model: input.model, custom_headers: input.customHeaders };
}
function embeddingConfigFromResponse(item: Record<string, unknown>): EmbeddingConfigInfo {
  return {
    id: Number(item.id),
    displayName: String(item.display_name ?? item.displayName ?? ''),
    baseUrl: String(item.base_url ?? item.baseUrl ?? ''),
    model: String(item.model ?? ''),
    apiKeyMasked: String(item.api_key_masked ?? item.apiKeyMasked ?? ''),
    customHeaders: String(item.custom_headers ?? item.customHeaders ?? '{}'),
    status: String(item.status ?? 'UNVERIFIED') as EmbeddingConfigInfo['status'],
    actualDimension: item.actual_dimension == null && item.actualDimension == null ? null : Number(item.actual_dimension ?? item.actualDimension),
    configVersion: Number(item.config_version ?? item.configVersion ?? 1),
    active: Boolean(item.active),
    usedByStories: Boolean(item.used_by_stories ?? item.usedByStories),
    usedByStoryTitles: ((item.used_by_story_titles ?? item.usedByStoryTitles ?? []) as unknown[]).map((title) => String(title)),
  };
}
export async function saveEmbeddingConfig(input: EmbeddingConfigInput): Promise<EmbeddingConfigInfo> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(embeddingConfigPayload(input)) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return embeddingConfigFromResponse(await readApiData(res));
}
export async function testEmbeddingConfig(configId: number): Promise<{ config: EmbeddingConfigInfo; embeddingSpaceId: number; dimension: number }> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs/test`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ config_id: configId }) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await readApiData(res);
  return { config: embeddingConfigFromResponse(data.config), embeddingSpaceId: Number(data.embedding_space_id ?? data.embeddingSpaceId), dimension: Number(data.dimension) };
}
export async function activateEmbeddingConfig(configId: number): Promise<EmbeddingConfigInfo> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs/${configId}/activate`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return embeddingConfigFromResponse(await readApiData(res));
}
export async function deactivateEmbeddingConfig(configId: number): Promise<EmbeddingConfigInfo> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs/${configId}/deactivate`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return embeddingConfigFromResponse(await readApiData(res));
}
export async function discoverEmbeddingModels(input: EmbeddingConfigInput): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs/models/discover`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(embeddingConfigPayload(input)) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await readApiData(res);
  return Array.isArray(data.models) ? data.models.map((model: unknown) => String(model)).filter(Boolean) : [];
}
export async function rebuildKnowledge(storyId: number, configId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/rebuild`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ config_id: configId }) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}
export async function previewKnowledge(storyId: number, chapterNumber: number, query: string): Promise<KnowledgeRecallPreview> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/preview?chapterNumber=${chapterNumber}&query=${encodeURIComponent(query)}`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function generateImage(prompt: string, referenceImages?: string[], size?: string, model?: string, signal?: AbortSignal, conversationId?: string): Promise<ImageGenRecord> {
  const snapshot = await submitImageRequest('/api/image-gen/generate', { prompt, reference_images: referenceImages || [], ...(size ? { size } : {}), ...(conversationId ? { conversation_id: conversationId } : {}), ...getProviderRequestPayload('image', model) }, `standalone.${conversationId || 'default'}`, signal);
  return imageRequestRecord(snapshot, prompt, size || '1024x1024');
}

function imageRequestRecord(snapshot: ImageRequestSnapshot, prompt: string, size: string, prior?: ImageGenRecord): ImageGenRecord {
  const item = snapshot.images[0];
  const fallbackId = -parseInt(snapshot.request_id.replaceAll('-', '').slice(0, 12), 16);
  return { id: item?.record_id ?? prior?.id ?? fallbackId, request_id: snapshot.request_id, conversation_id: snapshot.conversation_id,
    prompt, image_url: item?.image_path ?? null, model: item?.model ?? prior?.model ?? '', size: item?.size ?? size,
    status: item?.status ?? snapshot.status ?? 'QUEUED', failure_reason: item?.evidence_summary ?? item?.error_code ?? null,
    created_at: prior?.created_at ?? new Date().toISOString(), completed_at: item?.completed_at ?? null };
}
export async function refreshImageRequestRecord(record: ImageGenRecord): Promise<ImageGenRecord> {
  if (!record.request_id) return record;
  return imageRequestRecord(await getImageRequest(record.request_id), record.prompt, record.size, record);
}

export async function listImageGenHistory(page = 0, size = 12, conversationId?: string): Promise<{ content: ImageGenRecord[]; total_pages: number; total_elements: number }> {
  const res = await authFetch(BASE+'/api/image-gen/history?page='+page+'&size='+size+(conversationId ? '&conversation_id='+encodeURIComponent(conversationId) : ''));
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function deleteImageGenRecord(id: number): Promise<void> {
  const res = await authFetch(BASE+'/api/image-gen/'+id, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export function imageGenUrl(objectKey: string): string {
  return `${BASE}/static/manga/${encodeStaticPath(objectKey)}`;
}

