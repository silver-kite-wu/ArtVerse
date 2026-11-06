import { useEffect, useRef, useState } from 'react';
import { decideCreativeProposal, listCreativeProposals, type CreativeProposal } from '../api/creativeProposals';
import { getImageRequest } from '../api/imageRequests';

const labels = { SETTINGS_CHANGE: '作品设定修改', CHARACTER_CHANGE: '人物资料修改', IMAGE_PROPOSAL: '漫画生图提案', IMAGE_RECOVERY: '图片恢复提案' };
const fields: Record<string, string> = { title: '作品标题', description: '简介 / 描述', manga_style: '漫画风格', name: '人物名' };
const actions: Record<string, string> = { RECHECK: '重新核实原调用', REQUEST_REVIEW: '申请人工核实', RESUME_SAFE: '安全恢复已有结果', RELEASE_CONFIRMED: '释放已核实结束的占用' };

function Review({ proposal, onUpdated }: { proposal: CreativeProposal; onUpdated: () => void }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [requestStatus, setRequestStatus] = useState<string>('');
  const [now, setNow] = useState(() => Date.now());
  const action = useRef(false);
  const pending = proposal.status === 'DRAFT' || proposal.status === 'APPROVED_PENDING';
  const expired = proposal.status === 'DRAFT' && Date.parse(proposal.expiresAt) <= now;
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  async function decide(decision: 'confirm' | 'discard') {
    if (action.current) return;
    action.current = true; setBusy(true); setError('');
    try { await decideCreativeProposal(proposal, decision); onUpdated(); }
    catch (e) { setError(e instanceof Error ? e.message : '决定未完成，请刷新后重试。'); onUpdated(); }
    finally { action.current = false; setBusy(false); }
  }
  async function inspectRequests() {
    const ids = proposal.receipt.accepted_requests ?? (proposal.request_id ? [proposal.request_id] : []);
    setError('');
    try {
      const states: string[] = [];
      for (const id of ids) {
        const value = await getImageRequest(id);
        states.push(`${id}：${value.status ?? '状态暂不可用'}${value.images?.map(image => `；第 ${image.image_number} 图 ${image.status ?? '未知'}`).join('') ?? ''}`);
      }
      setRequestStatus(states.join('\n'));
    } catch (e) { setError(e instanceof Error ? e.message : '图片状态暂时无法读取。'); }
  }
  return <section className="rounded-xl border border-border bg-bg-surface p-4 text-sm" aria-label={labels[proposal.kind]}>
    <h3 className="font-semibold">{labels[proposal.kind]}{proposal.mode === 'CREATE' ? ' · 新建人物' : ''}</h3>
    <p className="mt-1 text-xs text-text-muted">章节 {proposal.chapterId}{proposal.base_version !== undefined ? ` · 基准版本 ${proposal.base_version}` : ''}{proposal.character_id ? ` · 人物 ${proposal.character_id}` : ''}</p>
    <details className="mt-3" open={open} onToggle={e => setOpen(e.currentTarget.open)}><summary className="cursor-pointer text-accent">核对完整修改和执行范围</summary>
      {proposal.after && <div className="mt-2 overflow-x-auto"><table className="w-full table-fixed border-collapse text-xs"><thead><tr><th className="w-20 p-2 text-left">字段</th><th className="p-2 text-left">修改前</th><th className="p-2 text-left">确认后</th></tr></thead><tbody>{Object.entries(proposal.after).map(([key, value]) => <tr key={key} className="border-t border-border"><th className="p-2 text-left align-top">{fields[key] ?? key}</th><td className="whitespace-pre-wrap break-words p-2 align-top">{proposal.before?.[key] || '（空）'}</td><td className="whitespace-pre-wrap break-words p-2 align-top">{value || '（空）'}</td></tr>)}</tbody></table></div>}
      {proposal.selection && <dl className="mt-3 grid grid-cols-[6rem_1fr] gap-2 text-xs"><dt>图片范围</dt><dd>{proposal.selection.image_numbers.join('、')}</dd><dt>操作</dt><dd>{proposal.selection.mode === 'FIRST' ? '首次生成空缺图片' : '重新生成现有图片'}</dd><dt>模型</dt><dd className="break-all">{proposal.selection.model} · 配置 {proposal.selection.config_id}</dd><dt>配置版本</dt><dd className="break-all">{proposal.selection.config_version}</dd><dt>尺寸</dt><dd>{proposal.selection.size ?? '默认尺寸'}</dd><dt>参考素材组</dt><dd>{proposal.selection.asset_group_id ?? '当前章节配置'}</dd><dt>调用次数</dt><dd>{proposal.model_calls}</dd><dt>费用</dt><dd>暂无法估算，可能产生费用；额度在提交时重新检查。</dd></dl>}
      {proposal.targets?.map(target => <p key={target.image_number} className="mt-2 break-all text-xs text-text-secondary">第 {target.image_number} 图：{target.expected_image_id ? `替换图片 ${target.expected_image_id} / 版本 ${target.expected_image_version}` : '目标必须为空'}；参考图 {target.reference_count} 张。</p>)}
      {proposal.recovery_action && <p className="mt-2 break-all text-xs leading-6">{actions[proposal.recovery_action] ?? proposal.recovery_action}<br />原请求 {proposal.request_id}<br />图片操作 {proposal.operation_id}<br />恢复基准版本 {proposal.recovery_version}</p>}
    </details>
    {pending ? <>
      <p className="mt-3 text-xs leading-6 text-text-secondary">{proposal.kind === 'IMAGE_PROPOSAL' ? '确认后提交上述付费生成范围，受理不代表图片已完成。' : proposal.kind === 'IMAGE_RECOVERY' ? '仅恢复原请求；未知供应商结果不会自动重新付费生成。' : '确认后保存上述资料，并产生独立待审知识候选；候选需在知识库另行审核。'}</p>
      {proposal.status === 'APPROVED_PENDING' && <p role="status" className="mt-2 text-xs text-warning">此提案已批准，提交尚未全部确认。继续原提案可核对并复用已受理请求。</p>}
      {expired && <p className="mt-2 text-xs text-warning">提案已过期，请重新准备。</p>}
      <div className="mt-3 flex flex-wrap gap-2"><button type="button" className="workspace-primary-button" disabled={busy || expired || !open} onClick={() => { void decide('confirm'); }}>{busy ? '正在处理…' : proposal.status === 'APPROVED_PENDING' ? '继续原提案' : proposal.kind === 'IMAGE_PROPOSAL' ? '确认并提交生图（可能付费）' : '确认上述修改'}</button><button type="button" className="workspace-secondary-button" disabled={busy || proposal.status === 'APPROVED_PENDING'} onClick={() => { void decide('discard'); }}>放弃提案</button></div>
    </> : <p role="status" className="mt-3 text-xs">{proposal.status === 'REJECTED' ? '提案已放弃。' : proposal.receipt.accepted ? '业务请求已受理，请查看实际执行状态。' : `资料已保存，版本 ${proposal.receipt.saved_version ?? ''}。知识候选 ${proposal.receipt.candidate_id ?? ''} 待审。`}</p>}
    {(proposal.receipt.accepted_requests?.length || proposal.request_id) && <button type="button" className="mt-2 text-xs text-accent" onClick={() => { void inspectRequests(); }}>刷新图片请求实际状态</button>}
    {requestStatus && <p role="status" className="mt-2 whitespace-pre-wrap break-all text-xs">{requestStatus}</p>}
    {error && <p role="alert" className="mt-2 text-xs text-warning">{error}</p>}
  </section>;
}

export default function CreativeProposals({ chapterId, conversationId, refreshToken = '' }: { chapterId?: number; conversationId?: string; refreshToken?: string }) {
  const [items, setItems] = useState<CreativeProposal[]>([]);
  const [next, setNext] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const scope = `${chapterId ?? ''}:${conversationId ?? ''}`;
  const currentScope = useRef(scope);
  useEffect(() => { currentScope.current = scope; }, [scope]);
  useEffect(() => {
    let live = true;
    void listCreativeProposals(chapterId, conversationId).then(page => { if (live) { setItems(page.items); setNext(page.hasMore ? page.nextCursor : ''); setError(''); } }).catch(e => { if (live) setError(e instanceof Error ? e.message : '提案读取失败。'); });
    return () => { live = false; };
  }, [chapterId, conversationId, refreshToken, revision]);
  async function more() {
    if (!next || busy) return;
    const original = scope; setBusy(true);
    try { const page = await listCreativeProposals(chapterId, conversationId, next); if (original === currentScope.current) { setItems(old => [...old, ...page.items.filter(item => !old.some(value => value.artifactId === item.artifactId))]); setNext(page.hasMore ? page.nextCursor : ''); setError(''); } }
    catch (e) { if (original === currentScope.current) setError(e instanceof Error ? e.message : '分页读取失败。'); }
    finally { setBusy(false); }
  }
  if (!items.length && !error) return null;
  return <div className="space-y-3" aria-label="助手创作提案审核">{items.map(item => <Review key={item.artifactId} proposal={item} onUpdated={() => setRevision(value => value + 1)} />)}{error && <p role="alert" className="text-xs text-warning">{error} <button type="button" onClick={() => setRevision(value => value + 1)}>重新读取</button></p>}{next && <button type="button" className="workspace-secondary-button" disabled={busy} onClick={() => { void more(); }}>读取更多创作提案</button>}</div>;
}
