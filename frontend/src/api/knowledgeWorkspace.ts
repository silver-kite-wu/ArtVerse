import { authFetch, BASE, parseApiError, readApiData } from './auth';
import type { KnowledgeUnitInput, KnowledgeUnitType } from './knowledgeAndImage';

export type Data = Record<string, unknown>;
export interface KnowledgeField { key: string; label: string; type: string; required: boolean; options: string[] }
export interface KnowledgeSchema { type: KnowledgeUnitType; version: number; fields: KnowledgeField[] }
interface ValidationIssue { path: string; code: string; message: string; severity: string }
export interface KnowledgeFact {
  id: number; unitId: number; revisionId: number; revision: number; kind: string; payload: Data;
  revealChapterId: number | null; fromChapterId: number | null; toChapterId: number | null;
  certainty: string; scope: string;
}
export interface KnowledgeRecord {
  id: number; type: KnowledgeUnitType; title: string; body: string; summary: string; structuredData: Data;
  importance: number; version: number; status: string; scope?: string; certainty?: string; factId?: number;
  revealChapterId?: number | null; facts: KnowledgeFact[]; issues: ValidationIssue[];
  characterId?: number; cover?: Data | null; images?: Data[]; levels?: KnowledgeFact[]; identities?: KnowledgeFact[];
  holdings?: Data[]; assetChanges?: KnowledgeFact[]; experiences?: KnowledgeRecord[]; clues?: KnowledgeFact[];
  reminders?: string[];
  relations?: KnowledgeRecord[];
}
export interface KnowledgeCandidateDetail extends KnowledgeRecord {
  sourceSnapshot: Data; currentSource: Data; currentSourceHash: string; sourceStale: boolean;
  targetUnitId: number | null; baseVersion: number | null; sourceType: string; sourceId: string;
}
export interface KnowledgeBrowse { items: KnowledgeRecord[]; total: number; counts: Record<string, number>; page: number; pageSize: number }
export interface KnowledgeOptions {
  chapters: Array<{ id: number; chapter_number: number; display_title?: string }>;
  characters: Array<{ id: number; name: string }>;
  entities: Array<{ id: number; kind: string; name: string; character_profile_id?: number }>;
  events: KnowledgeFact[];
}
export interface KnowledgeGraph {
  nodes: Array<{ id: number; name: string; unitId?: number; cover?: Data | null }>;
  edges: Array<{ id: number | string; unitId?: number; candidateId?: number; source: number; target: number; data: Data; pending: boolean }>;
  truncated: boolean; totalEdges: number;
}
export interface FactCommand {
  factId?: number; baseRevision?: number; kind: string; payload: Data;
  revealChapterId: number | null; fromChapterId: number | null; toChapterId: number | null;
  certainty: string; businessKey?: string; previousFactId?: number;
}
const root = (story: number) => `${BASE}/api/stories/${story}/knowledge`;
export async function knowledgeRequest<T>(story: number, path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await authFetch(root(story) + path, { method, ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) });
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  if (response.status === 204) return undefined as T;
  return readApiData<T>(response);
}
const field = (data: Data, camel: string, snake: string) => data[camel] ?? data[snake];
function factFromResponse(data: Data): KnowledgeFact {
  return { id: Number(data.id), unitId: Number(field(data, 'unitId', 'unit_id')), revisionId: Number(field(data, 'revisionId', 'revision_id')), revision: Number(data.revision), kind: String(data.kind), payload: data.payload as Data,
    revealChapterId: (field(data, 'revealChapterId', 'reveal_chapter_id') ?? null) as number | null,
    fromChapterId: (field(data, 'fromChapterId', 'from_chapter_id') ?? null) as number | null,
    toChapterId: (field(data, 'toChapterId', 'to_chapter_id') ?? null) as number | null, certainty: String(data.certainty), scope: String(data.scope) };
}
export function recordFromResponse(data: Data): KnowledgeRecord {
  const facts = (key: string) => ((data[key] ?? []) as Data[]).map(factFromResponse);
  return { ...data, id: Number(data.id), type: String(data.type ?? data.knowledgeType ?? data.knowledge_type) as KnowledgeUnitType,
    title: String(data.title ?? ''), body: String(data.body ?? ''), summary: String(data.summary ?? ''), importance: Number(data.importance ?? 3), version: Number(data.version), status: String(data.status ?? 'ACTIVE'),
    structuredData: (field(data, 'structuredData', 'structured_data') ?? {}) as Data,
    facts: facts('facts'), issues: (data.issues ?? data.validation_issues ?? data.validationIssues ?? []) as ValidationIssue[],
    levels: facts('levels'), identities: facts('identities'), assetChanges: ((field(data, 'assetChanges', 'asset_changes') ?? []) as Data[]).map(factFromResponse),
    experiences: ((data.experiences ?? []) as Data[]).map(recordFromResponse), relations: ((data.relations ?? []) as Data[]).map(recordFromResponse), clues: facts('clues') } as KnowledgeRecord;
}
export function candidateFromResponse(data: Data): KnowledgeCandidateDetail {
  return { ...recordFromResponse(data), sourceSnapshot: (field(data, 'sourceSnapshot', 'source_snapshot') ?? {}) as Data,
    currentSource: (field(data, 'currentSource', 'current_source') ?? {}) as Data,
    currentSourceHash: String(field(data, 'currentSourceHash', 'current_source_hash') ?? ''), sourceStale: Boolean(field(data, 'sourceStale', 'source_stale')),
    targetUnitId: (field(data, 'targetUnitId', 'target_unit_id') ?? null) as number | null, baseVersion: (field(data, 'baseVersion', 'base_version') ?? null) as number | null,
    sourceType: String(field(data, 'sourceType', 'source_type')), sourceId: String(field(data, 'sourceId', 'source_id')) };
}
export async function browseKnowledge(story: number, params: URLSearchParams): Promise<KnowledgeBrowse> {
  const result = await knowledgeRequest<KnowledgeBrowse>(story, `/browse?${params}`);
  return { ...result, items: result.items.map(item => recordFromResponse(item as unknown as Data)) };
}
export async function knowledgeOptions(story: number, asOf?: number | null): Promise<KnowledgeOptions> {
  const result = await knowledgeRequest<KnowledgeOptions>(story, `/options${asOf ? `?asOfChapterId=${asOf}` : ''}`);
  return { ...result, events: result.events.map(f => factFromResponse(f as unknown as Data)) };
}
export const knowledgeSchemas = (story: number) => knowledgeRequest<KnowledgeSchema[]>(story, '/schema');
export const knowledgeOverview = (story: number, q: string, asOf: number | null, filters: Record<string, string> = {}) => knowledgeRequest<Record<string, { total: number; counts: Record<string, number> }>>(story, `/overview?${new URLSearchParams({ q, ...filters, ...(asOf ? { asOfChapterId: String(asOf) } : {}) })}`);
export async function knowledgeCharacters(story: number, asOf: number | null): Promise<KnowledgeRecord[]> {
  const data = await knowledgeRequest<Data[]>(story, `/characters${asOf ? `?asOfChapterId=${asOf}` : ''}`); return data.map(recordFromResponse);
}
export async function knowledgeEvents(story: number, asOf: number | null, order: string): Promise<KnowledgeRecord[]> {
  return (await knowledgeRequest<Data[]>(story, `/events?${new URLSearchParams({ order, ...(asOf ? { asOfChapterId: String(asOf) } : {}) })}`)).map(recordFromResponse);
}
export async function knowledgeForeshadowing(story: number, asOf: number | null): Promise<KnowledgeRecord[]> {
  return (await knowledgeRequest<Data[]>(story, `/foreshadowing${asOf ? `?asOfChapterId=${asOf}` : ''}`)).map(recordFromResponse);
}
export async function knowledgeGraph(story: number, asOf: number | null, pending: boolean, characterId?: number, q = '', hops = 1): Promise<KnowledgeGraph> {
  const value = await knowledgeRequest<Data>(story, `/relations/graph?${new URLSearchParams({ pending: String(pending), q, hops: String(hops), ...(asOf ? { asOfChapterId: String(asOf) } : {}), ...(characterId ? { characterId: String(characterId) } : {}) })}`);
  return { nodes: value.nodes as KnowledgeGraph['nodes'], edges: value.edges as KnowledgeGraph['edges'], truncated: Boolean(value.truncated), totalEdges: Number(value.totalEdges ?? value.total_edges) };
}
export async function getCandidateDetail(story: number, id: number): Promise<KnowledgeCandidateDetail> {
  return candidateFromResponse(await knowledgeRequest<Data>(story, `/candidates/${id}`));
}
function knowledgePayload(input: KnowledgeUnitInput) {
  return { type: input.type, title: input.title, body: input.body, summary: input.summary, structured_data: input.structuredData, importance: input.importance,
    effective_from_chapter: input.effectiveFromChapter ?? null, effective_to_chapter: input.effectiveToChapter ?? null };
}
export async function saveWorkspaceKnowledge(story: number, input: KnowledgeUnitInput, selected?: KnowledgeRecord): Promise<KnowledgeRecord> {
  return recordFromResponse(await knowledgeRequest<Data>(story, selected ? `/${selected.id}` : '', selected ? 'PUT' : 'POST', { ...knowledgePayload(input), ...(selected ? { base_version: selected.version } : {}) }));
}
export async function saveCandidate(story: number, candidate: KnowledgeCandidateDetail, input: KnowledgeUnitInput, target: KnowledgeRecord | null): Promise<KnowledgeCandidateDetail> {
  return candidateFromResponse(await knowledgeRequest<Data>(story, `/candidates/${candidate.id}`, 'PUT', { ...knowledgePayload(input), version: candidate.version, target_unit_id: target?.id ?? null, base_version: target?.version ?? null }));
}
export async function saveKnowledgeFact(story: number, unit: number, command: FactCommand): Promise<KnowledgeFact> {
  return factFromResponse(await knowledgeRequest<Data>(story, `/${unit}/facts`, 'POST', { fact_id: command.factId ?? null, base_revision: command.baseRevision ?? null, kind: command.kind, payload: command.payload,
    reveal_chapter_id: command.revealChapterId, from_chapter_id: command.fromChapterId, to_chapter_id: command.toChapterId, certainty: command.certainty, business_key: command.businessKey ?? null, previous_fact_id: command.previousFactId ?? null }));
}
