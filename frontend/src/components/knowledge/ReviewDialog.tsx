import { blankKnowledge, inputOf, errorText, TYPES } from './knowledgeModel';
import { useEffect, useState } from 'react';
import { browseKnowledge, candidateFromResponse, recordFromResponse, getCandidateDetail, knowledgeRequest, saveCandidate, saveWorkspaceKnowledge, type Data, type KnowledgeCandidateDetail, type KnowledgeOptions, type KnowledgeRecord, type KnowledgeSchema } from '../../api/knowledgeWorkspace';
import type { KnowledgeUnitInput, KnowledgeUnitType } from '../../api/knowledgeAndImage';
import TypedKnowledgeForm from './TypedKnowledgeForm';
import { normalizeFormData } from './knowledgeFormValues';

export default function ReviewDialog({ story, options, schemas, record, candidateId, initial, onClose, onSaved }: { story: number; options: KnowledgeOptions; schemas: KnowledgeSchema[]; record?: KnowledgeRecord; candidateId?: number; initial: KnowledgeUnitInput; onClose: () => void; onSaved: () => void }) {
  const [draft, setDraft] = useState(initial);const [candidate, setCandidate] = useState<KnowledgeCandidateDetail | null>(null);const [targets, setTargets] = useState<KnowledgeRecord[]>([]);const [targetId, setTargetId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);const [loading, setLoading] = useState(Boolean(candidateId));const [error, setError] = useState('');const [reason, setReason] = useState('');
  const [targetQuery, setTargetQuery] = useState('');const [targetPage, setTargetPage] = useState(0);const [targetTotal, setTargetTotal] = useState(0);
  useEffect(() => { if (!candidateId) return;let active = true;
    getCandidateDetail(story, candidateId).then(async detail => { const official = await browseKnowledge(story, new URLSearchParams({ type: detail.type, state: 'ACTIVE', pageSize: '100' }));if (detail.targetUnitId && !official.items.some(i => i.id === detail.targetUnitId)) official.items.push(recordFromResponse(await knowledgeRequest<Data>(story, `/units/${detail.targetUnitId}`)));if (!active) return;setCandidate(detail);setDraft(inputOf(detail));setTargets(official.items);setTargetId(detail.targetUnitId); }).catch(e => { if (active) setError(errorText(e)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [story, candidateId]);
  useEffect(() => {if (!candidate) return;let alive = true;
    browseKnowledge(story, new URLSearchParams({type: draft.type, state: 'ACTIVE', q: targetQuery, page: String(targetPage), pageSize: '100'})).then(data => {if (alive) {setTargets(old => targetPage ? [...old, ...data.items.filter(i => !old.some(o => o.id === i.id))] : [...data.items, ...old.filter(i => i.id === targetId && i.type === draft.type && !data.items.some(n => n.id === i.id))]);setTargetTotal(data.total);}}).catch(e => {if (alive) setError(errorText(e));});
    return () => {alive = false;};
  }, [story, candidate, draft.type, targetQuery, targetPage, targetId]);
  useEffect(() => { const warn = (e: BeforeUnloadEvent) => e.preventDefault();window.addEventListener('beforeunload', warn);return () => window.removeEventListener('beforeunload', warn); }, []);
  const close = () => { if (window.confirm('尚未保存的修改将被放弃，是否关闭？')) onClose(); };
  const save = async (approve = false) => { setSaving(true);setError('');try {
    const normalizedDraft = {...draft, structuredData: normalizeFormData(draft.structuredData, schemas.find(s => s.type === draft.type)?.fields ?? [])};setDraft(normalizedDraft);
    if (candidateId && !candidate) throw new Error('候选详情未加载，请关闭后重新打开');
    if (targetId && !targets.some(t => t.id === targetId && t.type === draft.type)) throw new Error('更新目标未加载，请重新选择');
    if (candidate) { const updated = await saveCandidate(story, candidate, normalizedDraft, targets.find(t => t.id === targetId) ?? null);setCandidate(updated);
      if (approve) { await knowledgeRequest(story, `/candidates/${candidate.id}/approve`, 'POST', { version: updated.version });onSaved(); }
    } else { await saveWorkspaceKnowledge(story, normalizedDraft, record);onSaved(); }
  } catch (e) { setError(errorText(e)); } finally { setSaving(false); } };
  const reject = async () => { if (!candidate) return;setSaving(true);try { await knowledgeRequest(story, `/candidates/${candidate.id}/reject`, 'POST', { reason });onSaved(); } catch (e) { setError(errorText(e)); } finally { setSaving(false); } };
  const acknowledge = async () => { if (!candidate) return;setSaving(true);try { setCandidate(candidateFromResponse(await knowledgeRequest<Data>(story, `/candidates/${candidate.id}/acknowledge-source`, 'POST', { version: candidate.version, source_hash: candidate.currentSourceHash }))); } catch (e) { setError(errorText(e)); } finally { setSaving(false); } };
  return <div className="kb-modal-backdrop"><section role="dialog" aria-modal="true" aria-label={candidateId ? '审核待确认知识' : record ? '编辑知识' : '新增知识'} className="kb-modal"><header><h2>{candidateId ? `审核 · ${draft.title}` : record ? '编辑知识' : '新增知识'}</h2><button type="button" aria-label="关闭编辑" onClick={close}>×</button></header>{loading ? <p className="kb-empty">正在读取候选快照…</p> : <form onSubmit={e => { e.preventDefault();void save(); }}>
    {candidate && <><p className="kb-muted">候选 #{candidate.id} · v{candidate.version} · {candidate.sourceType} · {candidate.status}</p>{candidate.issues.length > 0 && <ul className="kb-warning">{candidate.issues.map((issue, i) => <li key={i}>{issue.message}</li>)}</ul>}
      <label className="kb-field"><span>搜索正式知识更新目标</span><input value={targetQuery} onChange={e => {setTargetQuery(e.target.value);setTargetPage(0);}} /></label>{(targetPage + 1) * 100 < targetTotal && <button type="button" onClick={() => setTargetPage(p => p + 1)}>加载更多更新目标</button>}
      <details open={candidate.sourceStale}><summary>来源快照与当前内容{candidate.sourceStale ? '（需要核对）' : ''}</summary><SourceView title="抽取时快照" data={candidate.sourceSnapshot} /><SourceView title="当前来源" data={candidate.currentSource} />{candidate.sourceStale && candidate.status === 'PENDING' && <><p className="kb-warning">旧快照缺失或来源已变化。请逐项核对当前来源与候选内容，再确认。</p><button type="button" disabled={saving} onClick={() => void acknowledge()}>已核对当前来源，保留此候选继续审核</button></>}</details>
      <label className="kb-field"><span>审核方式 / 更新目标</span><select value={targetId ?? ''} onChange={e => setTargetId(e.target.value ? Number(e.target.value) : null)}><option value="">新建正式知识</option>{targets.map(t => <option key={t.id} value={t.id}>更新 · {t.title} #{t.id} · v{t.version}</option>)}</select></label>{targetId && <details open><summary>与当前正式版本比较</summary><FieldDiff before={targets.find(t => t.id === targetId)} after={draft} /></details>}</>}
    <fieldset disabled={Boolean(candidateId && !candidate) || Boolean(candidate && candidate.status !== 'PENDING')}><label className="kb-field"><span>知识分类</span><select value={draft.type} disabled={Boolean(record) || candidate?.sourceType === 'CHARACTER_PROFILE'} onChange={e => { if (window.confirm('切换分类将清空类型专用字段，标题和描述保留。是否继续？')) {setDraft({ ...draft, type: e.target.value as KnowledgeUnitType, structuredData: blankKnowledge(e.target.value as KnowledgeUnitType, null).structuredData });setTargetId(null);setTargetPage(0);} }}>{TYPES.map(t => <option key={t.id} value={t.id}>{t.label}</option>)}</select></label>
    <TypedKnowledgeForm draft={draft} onChange={setDraft} schema={schemas.find(s => s.type === draft.type)} options={options} /></fieldset>
    {error && <p role="alert" className="kb-error">{error}</p>}{candidate && <label className="kb-field"><span>拒绝理由（可选）</span><input value={reason} onChange={e => setReason(e.target.value)} maxLength={500} /></label>}
    <footer>{candidate ? candidate.status === 'PENDING' ? <><button type="button" disabled={saving} onClick={() => void reject()}>拒绝</button><button disabled={saving}>保存补充</button><button className="kb-primary" type="button" disabled={saving || candidate.sourceStale} onClick={() => void save(true)}>{saving ? '处理中…' : '确认入库'}</button></> : <span>此候选已结束审核，仅保留历史。</span> : <><button type="button" onClick={close}>取消</button><button className="kb-primary" disabled={saving}>{saving ? '保存中…' : '保存正式知识'}</button></>}</footer>
  </form>}{!loading && candidateId && !candidate && <p role="alert" className="kb-error">候选详情未能加载，请关闭后重试。{error}</p>}</section></div>;
}
function SourceView({ title, data }: { title: string; data: Data }) { return <section className="kb-source"><strong>{title}</strong>{Object.entries(data).map(([key, value]) => <div key={key}><small>{({ novel: '小说正文', storyboard: '分镜', name: '人物姓名', description: '人物描述', chapter_id: '章节 ID' } as Record<string, string>)[key] ?? key}</small><p>{String(value ?? '--')}</p></div>)}</section>; }
function FieldDiff({ before, after }: { before?: KnowledgeRecord; after: KnowledgeUnitInput }) {
  if (!before) return <p>请选择目标</p>;
  const baseline: Data = { title: before.title, body: before.body, summary: before.summary, ...before.structuredData };
  const fields: Data = { ...(after.type === 'CHARACTER_CARD' ? baseline : {}), title: after.title, body: after.body, summary: after.summary, ...after.structuredData };
  const changed = Array.from(new Set([...Object.keys(baseline), ...Object.keys(fields)])).map(key => [key, fields[key]] as const).filter(([key, value]) => JSON.stringify(value ?? null) !== JSON.stringify(baseline[key] ?? null));
  return <div className="kb-table-wrap"><table><thead><tr><th>字段</th><th>当前正式值</th><th>本次候选值</th></tr></thead><tbody>{changed.map(([key, value]) => <tr key={key}><td>{key}</td><td>{JSON.stringify(baseline[key] ?? null)}</td><td>{JSON.stringify(value ?? null)}</td></tr>)}</tbody></table>{!changed.length && <p>未发现字段差异</p>}</div>;
}
