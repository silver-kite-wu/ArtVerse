import { useRef, useState } from 'react';
import { ApiError, authFetch, readApiData } from '../api/auth';
import { imageRequestResponse } from '../api/imageRequests';
import { randomUUID } from '../utils/browserSecurity';

interface Review {
  request_id: string; operation_id: string; user_id: number; provider_attempt: number;
  execution_token: string; recovery_version: number; image_number: number | null; execution_lane: string;
  review_requested_at: string | null;
}
interface ReviewDetail { provider: string; provider_label: string; model: string; provider_started_at: string | null; evidence_summary: string; candidate_registered: boolean }
export default function ImageGenerationReviews() {
  const [items, setItems] = useState<Review[]>([]);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [selected, setSelected] = useState<Review | null>(null);
  const [detail, setDetail] = useState<ReviewDetail | null>(null);
  const detailLoad = useRef(0);
  const [source, setSource] = useState('');
  const [reference, setReference] = useState('');
  const [conclusion, setConclusion] = useState('UNKNOWN');
  const [confirmEnded, setConfirmEnded] = useState(false);
  const [busy, setBusy] = useState(false);
  async function select(item: Review) {
    const generation = ++detailLoad.current;
    setSelected(item);setDetail(null);setSource('');setReference('');setConclusion('UNKNOWN');setConfirmEnded(false);setError('');
    try { const next = await imageRequestResponse<ReviewDetail>(await authFetch(`/api/admin/image-generation/requests/${item.request_id}/operations/${item.operation_id}`));if (detailLoad.current === generation) setDetail(next); }
    catch (error) { if (detailLoad.current === generation) setError((error as Error).message); }
  }
  async function load(nextPage = page) {
    setError('');
    try {
      const response = await authFetch(`/api/admin/image-generation/reviews?page=${nextPage}&size=20`);
      if (!response.ok) { await imageRequestResponse(response);return; }
      const data = await readApiData<{ items: Review[]; total_pages: number }>(response);
      setItems(data.items);setPage(nextPage);setTotalPages(data.total_pages);
    } catch (error) { setError((error as Error).message); }
  }
  async function decide() {
    if (!selected) return;
    setBusy(true);setError('');
    // Keep the exact action identity/body on a transport retry.
    const body = { action_id: randomUUID(), expected_recovery_version: selected.recovery_version,
      provider_attempt: selected.provider_attempt, execution_token: selected.execution_token,
      evidence_source: source, evidence_reference: reference, conclusion, confirm_external_ended: confirmEnded };
    const key = `artverse.image-review.${selected.request_id}.${selected.operation_id}.${selected.recovery_version}`;
    const prior = sessionStorage.getItem(key);
    const serialized = prior || JSON.stringify(body);
    sessionStorage.setItem(key, serialized);
    try {
      await imageRequestResponse(await authFetch(`/api/admin/image-generation/requests/${selected.request_id}/operations/${selected.operation_id}/review-decisions`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: serialized,
      }));
      sessionStorage.removeItem(key);setSelected(null);await load();
    } catch (error) {
      // A definite validation rejection can be corrected; unknown transport outcomes keep the body.
      if (error instanceof ApiError && error.status === 400) sessionStorage.removeItem(key);
      setError((error as Error).message);
    }
    finally { setBusy(false); }
  }
  return <details className="my-4 rounded-lg border border-border p-4">
    <summary>生图人工核实（管理员）</summary>
    <p className="my-2 text-sm">凭供应商后台或支持工单核对调用。无法确认时保持未决，不能用超时推断结束。</p>
    <button className="underline" onClick={() => void load()}>读取待核实清单</button>
    {error && <p role="alert">{error}</p>}
    {items.map(item => <div key={item.operation_id} className="my-3 border-t border-border pt-2 text-sm">
      <p className="break-all">用户 {item.user_id} · {item.request_id} / {item.operation_id}</p>
      <p>第 {item.image_number || '独立'} 张 · {item.execution_lane} · 调用 {item.provider_attempt} · 代次 {item.execution_token}</p>
      <p>{item.review_requested_at ? '用户已申请人工核实' : '持久调用未决，可能仍在正常执行'}</p>
      <button className="underline" disabled={busy} onClick={() => void select(item)}>读取详情并填写核实结论</button>
    </div>)}
    {totalPages > 1 && <div><button disabled={page === 0} onClick={() => void load(page - 1)}>上一页</button> {page + 1}/{totalPages} <button disabled={page + 1 >= totalPages} onClick={() => void load(page + 1)}>下一页</button></div>}
    {selected && <form className="mt-3 space-y-3" onSubmit={event => { event.preventDefault();void decide(); }}>
      {detail ? <div><p>供应商：{detail.provider_label || detail.provider} · {detail.model}</p><p>开始时间：{detail.provider_started_at ? new Date(detail.provider_started_at).toLocaleString() : '尚未登记'}</p><p>{detail.evidence_summary}</p></div> : <p>正在读取核实详情；读取成功后才能提交。</p>}
      <label className="block">证据来源<input className="ml-2 border p-1" required maxLength={200} value={source} onChange={event => setSource(event.target.value)} placeholder="供应商后台 / 支持工单" /></label>
      <label className="block">脱敏证据引用<input className="ml-2 border p-1" required maxLength={500} value={reference} onChange={event => setReference(event.target.value)} placeholder="工单或审计记录编号，不含密钥" /></label>
      <label className="block">结论<select className="ml-2 border p-1" value={conclusion} onChange={event => setConclusion(event.target.value)}><option value="UNKNOWN">无法确认，维持未决</option><option value="NOT_ACCEPTED">已核实未受理</option><option value="ENDED_WITHOUT_RESULT">已核实结束且无可恢复结果</option></select></label>
      <label className="block"><input type="checkbox" checked={confirmEnded} onChange={event => setConfirmEnded(event.target.checked)} /> 已通过上述外部证据核实调用结束或未受理，承担人工核实责任</label>
      <button disabled={busy || !detail || (conclusion !== 'UNKNOWN' && !confirmEnded)} className="underline">保存结论</button>
    </form>}
  </details>;
}
