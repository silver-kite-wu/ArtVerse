import { Archive, BookOpenCheck, CheckCircle2, ChevronLeft, Eye, Plus, Save, Search, Sparkles, XCircle } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { approveKnowledgeCandidate, archiveKnowledge, createKnowledge, getEmbeddingConfigs, listKnowledge, listKnowledgeCandidates, previewKnowledge, rebuildKnowledge, rejectKnowledgeCandidate, updateKnowledge, type EmbeddingConfigInfo, type KnowledgeCandidate, type KnowledgeUnit, type KnowledgeUnitInput, type KnowledgeUnitType } from '../api';

const TYPES: Array<{ id: KnowledgeUnitType; label: string; fields: Record<string, unknown> }> = [
  { id: 'CHARACTER_CARD', label: '角色卡', fields: { aliases: [], identity: '', personality: '', abilities: '', motivation: '', taboos: '', status: '' } },
  { id: 'CHARACTER_RELATION', label: '人物关系', fields: { left_character: '', right_character: '', relation_type: '', stage: '', public_information: '', hidden_information: '' } },
  { id: 'WORLDVIEW', label: '世界观', fields: { locations: [], organizations: [], rules: '', items: [], ability_system: '', hard_constraints: '' } },
  { id: 'TIMELINE', label: '时间线', fields: { story_time: '', participants: [], location: '', event: '', occurs_chapter: '' } },
  { id: 'FORESHADOWING', label: '伏笔', fields: { setup: '', status: 'PENDING', setup_chapter: '', expected_resolution_chapter: '', resolution_condition: '' } },
];

const blankInput = (type: KnowledgeUnitType = 'CHARACTER_CARD'): KnowledgeUnitInput => ({
  type, title: '', body: '', summary: '', structuredData: TYPES.find((item) => item.id === type)?.fields || {}, importance: 3,
});

interface Props { storyId: number; chapterNumber?: number; onBack: () => void; }

export default function KnowledgeBasePage({ storyId, chapterNumber = 1, onBack }: Props) {
  const [items, setItems] = useState<KnowledgeUnit[]>([]);
  const [candidates, setCandidates] = useState<KnowledgeCandidate[]>([]);
  const [configs, setConfigs] = useState<EmbeddingConfigInfo[]>([]);
  const [selected, setSelected] = useState<KnowledgeUnit | null>(null);
  const [draft, setDraft] = useState<KnowledgeUnitInput>(blankInput());
  const [filter, setFilter] = useState<KnowledgeUnitType | 'ALL'>('ALL');
  const [query, setQuery] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [preview, setPreview] = useState<Array<{ title: string; type: string; score: number }>>([]);

  const load = async () => {
    try { const [units, spaces, pending] = await Promise.all([listKnowledge(storyId), getEmbeddingConfigs(), listKnowledgeCandidates(storyId)]); setItems(units); setConfigs(spaces.filter((item) => item.status === 'VERIFIED')); setCandidates(pending); }
    catch (error) { setMessage(error instanceof Error ? error.message : '知识库加载失败'); }
  };
  useEffect(() => { void load(); }, [storyId]);
  const visible = useMemo(() => items.filter((item) => (filter === 'ALL' || item.type === filter) && `${item.title}\n${item.body}\n${item.summary}`.toLowerCase().includes(query.toLowerCase())), [filter, items, query]);
  const select = (item: KnowledgeUnit) => { setSelected(item); setDraft({ type: item.type, title: item.title, body: item.body, summary: item.summary, structuredData: item.structuredData, importance: item.importance, effectiveFromChapter: item.effectiveFromChapter, effectiveToChapter: item.effectiveToChapter }); setMessage(''); };
  const newUnit = (type: KnowledgeUnitType = filter === 'ALL' ? 'CHARACTER_CARD' : filter) => { setSelected(null); setDraft(blankInput(type)); setMessage(''); };
  const changeType = (type: KnowledgeUnitType) => setDraft((current) => ({ ...current, type, structuredData: TYPES.find((item) => item.id === type)?.fields || {} }));
  const save = async () => {
    setSaving(true); setMessage('');
    try {
      const result = selected ? await updateKnowledge(storyId, selected.id, draft) : await createKnowledge(storyId, draft);
      setSelected(result); select(result); await load(); setMessage('已保存，索引会在后台更新。');
    } catch (error) { setMessage(error instanceof Error ? error.message : '保存失败'); } finally { setSaving(false); }
  };
  const archive = async () => { if (!selected || !confirm('归档这条知识？')) return; await archiveKnowledge(storyId, selected.id); newUnit(); await load(); };
  const rebuild = async (configId: number) => { try { await rebuildKnowledge(storyId, configId); setMessage('全书重建已提交；旧空间会继续服务，直到新空间完成。'); } catch (error) { setMessage(error instanceof Error ? error.message : '重建提交失败'); } };
  const recall = async () => { try { const result = await previewKnowledge(storyId, chapterNumber, `${draft.title}\n${draft.summary}\n${draft.body}`); setPreview(result.items.map((item) => ({ title: item.title, type: item.type, score: item.score }))); } catch (error) { setMessage(error instanceof Error ? error.message : '召回预览失败'); } };
  const approveCandidate = async (candidateId: number) => { try { await approveKnowledgeCandidate(storyId, candidateId); await load(); setMessage('候选知识已确认并进入正式索引。'); } catch (error) { setMessage(error instanceof Error ? error.message : '确认候选失败'); } };
  const rejectCandidate = async (candidateId: number) => { try { await rejectKnowledgeCandidate(storyId, candidateId); await load(); setMessage('候选知识已拒绝，不会影响后续创作。'); } catch (error) { setMessage(error instanceof Error ? error.message : '拒绝候选失败'); } };

  return <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
    <header className="flex shrink-0 items-center justify-between gap-3 border-b border-border bg-bg-surface px-3 py-2">
      <button onClick={onBack} className="inline-flex items-center gap-1 text-sm text-text-secondary hover:text-accent"><ChevronLeft size={16} />返回创作</button>
      <div className="flex min-w-0 items-center gap-2 text-sm font-medium text-text-primary"><BookOpenCheck size={16} className="text-accent" />知识库</div>
      <select onChange={(event) => void rebuild(Number(event.target.value))} defaultValue="" className="max-w-48 rounded-lg border border-border bg-bg-base px-2 py-1 text-xs text-text-primary" title="使用已验证的 Embedding 配置重建全书">
        <option value="" disabled>全书重建</option>
        {configs.map((config) => <option key={config.id} value={config.id}>{config.active ? '默认 · ' : ''}{config.displayName || config.model} · {config.model} ({config.actualDimension}D)</option>)}
      </select>
    </header>
    <div className="grid min-h-0 flex-1 md:grid-cols-[280px_minmax(0,1fr)]">
      <aside className="flex min-h-0 flex-col border-b border-border bg-bg-surface md:border-b-0 md:border-r">
        <div className="flex items-center gap-2 p-3"><div className="relative flex-1"><Search size={14} className="absolute left-2 top-2 text-text-muted" /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索知识" className="w-full rounded-lg border border-border bg-bg-base py-1.5 pl-7 pr-2 text-xs text-text-primary outline-none focus:border-accent" /></div><button onClick={() => newUnit()} className="rounded-md border border-border bg-bg-base p-1.5 text-text-secondary hover:text-accent" aria-label="新增知识"><Plus size={16} /></button></div>
        <div className="flex gap-1 overflow-x-auto px-3 pb-2">{[{ id: 'ALL' as const, label: '全部' }, ...TYPES].map((type) => <button key={type.id} onClick={() => setFilter(type.id)} className={'shrink-0 rounded-lg px-2 py-1 text-xs ' + (filter === type.id ? 'bg-accent text-white' : 'text-text-secondary hover:bg-bg-base')}>{type.label}</button>)}</div>
        <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
          {candidates.length > 0 && <div className="mb-3 rounded-lg border border-amber-400/30 bg-amber-400/5 p-2"><div className="mb-2 text-xs font-medium text-amber-700">待确认知识（{candidates.length}）</div>{candidates.map((candidate) => <div key={candidate.id} className="mb-2 rounded-lg  border border-border bg-bg-base p-2 last:mb-0"><div className="truncate text-xs font-medium text-text-primary">{candidate.title}</div><div className="mt-1 line-clamp-2 text-[0.6875rem] text-text-muted">{candidate.summary || candidate.body}</div><div className="mt-2 flex justify-end gap-1"><button type="button" onClick={() => void rejectCandidate(candidate.id)} className="inline-flex items-center gap-1 rounded-lg  px-2 py-1 text-[0.6875rem] text-text-secondary hover:bg-red-500/10 hover:text-red-600"><XCircle size={12} />拒绝</button><button type="button" onClick={() => void approveCandidate(candidate.id)} className="inline-flex items-center gap-1 rounded-lg  bg-emerald-600 px-2 py-1 text-[0.6875rem] text-white hover:bg-emerald-700"><CheckCircle2 size={12} />确认</button></div></div>)}</div>}
          {visible.map((item) => <button key={item.id} onClick={() => select(item)} className={'mb-1 w-full rounded-lg border p-2 text-left ' + (selected?.id === item.id ? 'border-accent bg-accent-soft' : 'border-transparent hover:border-border hover:bg-bg-base')}><div className="flex items-center justify-between gap-2"><span className="truncate text-sm text-text-primary">{item.title}</span><span className={'text-[0.625rem] ' + (item.indexStatus === 'SUCCEEDED' ? 'text-emerald-600' : 'text-accent-secondary')}>{item.indexStatus}</span></div><div className="mt-1 text-[0.6875rem] text-text-muted">{TYPES.find((type) => type.id === item.type)?.label} · v{item.version}</div></button>)}
        </div>
      </aside>
      <section className="min-h-0 overflow-y-auto p-3 md:p-5">
        <div className="mx-auto max-w-3xl">
          <div className="mb-4 flex items-center justify-between gap-2"><select value={draft.type} onChange={(event) => changeType(event.target.value as KnowledgeUnitType)} className="rounded-md border border-border bg-bg-surface px-2 py-1.5 text-sm text-text-primary">{TYPES.map((type) => <option key={type.id} value={type.id}>{type.label}</option>)}</select><div className="flex gap-1"><button onClick={() => void recall()} className="rounded-md border border-border bg-bg-surface p-2 text-text-secondary hover:text-accent" title="本章召回预览"><Eye size={15} /></button>{selected && <button onClick={() => void archive()} className="rounded-md border border-border bg-bg-surface p-2 text-text-secondary hover:text-accent" title="归档"><Archive size={15} /></button>}<button disabled={saving} onClick={() => void save()} className="inline-flex items-center gap-1 rounded-lg bg-accent px-3 py-2 text-xs text-white disabled:opacity-50"><Save size={14} />保存</button></div></div>
          <label className="mb-1 block text-xs text-text-secondary">标题</label><input value={draft.title} onChange={(event) => setDraft((current) => ({ ...current, title: event.target.value }))} className="mb-3 w-full rounded-lg border border-border bg-bg-surface px-3 py-2 text-sm text-text-primary outline-none focus:border-accent" />
          <label className="mb-1 block text-xs text-text-secondary">摘要</label><textarea value={draft.summary} onChange={(event) => setDraft((current) => ({ ...current, summary: event.target.value }))} rows={3} className="mb-3 w-full resize-y rounded-lg border border-border bg-bg-surface px-3 py-2 text-sm text-text-primary outline-none focus:border-accent" />
          <label className="mb-1 block text-xs text-text-secondary">正文</label><textarea value={draft.body} onChange={(event) => setDraft((current) => ({ ...current, body: event.target.value }))} rows={10} className="mb-3 w-full resize-y rounded-lg border border-border bg-bg-surface px-3 py-2 text-sm text-text-primary outline-none focus:border-accent" />
          <div className="grid gap-3 md:grid-cols-3"><label className="text-xs text-text-secondary">重要度<input type="number" min="1" max="5" value={draft.importance} onChange={(event) => setDraft((current) => ({ ...current, importance: Number(event.target.value) }))} className="mt-1 block w-full rounded-lg border border-border bg-bg-surface px-2 py-1.5 text-sm text-text-primary" /></label><label className="text-xs text-text-secondary">生效章节<input type="number" value={draft.effectiveFromChapter ?? ''} onChange={(event) => setDraft((current) => ({ ...current, effectiveFromChapter: event.target.value ? Number(event.target.value) : null }))} className="mt-1 block w-full rounded-lg border border-border bg-bg-surface px-2 py-1.5 text-sm text-text-primary" /></label><label className="text-xs text-text-secondary">截止章节<input type="number" value={draft.effectiveToChapter ?? ''} onChange={(event) => setDraft((current) => ({ ...current, effectiveToChapter: event.target.value ? Number(event.target.value) : null }))} className="mt-1 block w-full rounded-lg border border-border bg-bg-surface px-2 py-1.5 text-sm text-text-primary" /></label></div>
          <label className="mt-3 block text-xs text-text-secondary">结构化字段（JSON）<textarea value={JSON.stringify(draft.structuredData, null, 2)} onChange={(event) => { try { setDraft((current) => ({ ...current, structuredData: JSON.parse(event.target.value) })); } catch { /* retain last valid value */ } }} rows={8} className="mt-1 w-full resize-y rounded-lg border border-border bg-bg-surface px-3 py-2 font-mono text-xs text-text-primary outline-none focus:border-accent" /></label>
          {message && <div className="mt-3 flex items-center gap-2 text-xs text-text-secondary"><Sparkles size={14} className="text-accent-secondary" />{message}</div>}
          {preview.length > 0 && <div className="mt-4 border-t border-border pt-3"><div className="mb-2 text-xs text-text-secondary">本章召回预览</div>{preview.map((item) => <div key={`${item.type}-${item.title}`} className="flex justify-between py-1 text-xs text-text-primary"><span>{item.title}</span><span className="text-text-muted">{item.type} · {item.score.toFixed(2)}</span></div>)}</div>}
        </div>
      </section>
    </div>
  </div>;
}
