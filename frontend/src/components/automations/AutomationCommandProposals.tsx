import { useEffect, useRef, useState } from 'react';
import { automationRule, decideAutomationCommand, listAutomationCommands, type AutomationCommandProposal } from '../../api/automations';

const actionLabels = { create: '新建计划', edit: '修改计划', pause: '暂停计划', resume: '恢复计划', archive: '归档计划' };

function CommandReview({ proposal, onUpdated }: { proposal: AutomationCommandProposal; onUpdated: () => void }) {
  const [saved, setSaved] = useState<AutomationCommandProposal | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [now, setNow] = useState(() => Date.now());
  const actionRef = useRef(false);
  const current = saved ?? proposal;
  const definition = current.definition;
  const expired = Date.parse(current.expiresAt) <= now;
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  async function decide(decision: 'confirm' | 'discard') {
    if (actionRef.current) return;
    actionRef.current = true; setBusy(true); setError('');
    try {
      const result = await decideAutomationCommand(current, decision);
      setSaved(result); onUpdated();
      if (result.applied) window.dispatchEvent(new Event('artverse:automations-changed'));
    } catch (e) { setError(e instanceof Error ? e.message : '计划决定未保存，请刷新后重试。'); }
    finally { actionRef.current = false; setBusy(false); }
  }
  return <section className="rounded-xl border border-border bg-bg-surface p-4 text-sm" aria-label="计划命令审阅">
    <h3 className="font-semibold">{actionLabels[current.action]} · {definition.name}</h3>
    <p className="mt-2 text-xs leading-6 text-text-secondary">{automationRule(definition.schedule)}</p>
    <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
      <dt>执行对象</dt><dd>章节 {(definition.payload.chapterIds ?? [definition.payload.chapterId]).filter(Boolean).join('、')}</dd>
      <dt>执行方式</dt><dd>{definition.executorKey}</dd>
      <dt>模型</dt><dd className="break-all">{definition.payload.model ?? '已保存配置'} · 配置 {definition.payload.modelConfigId}</dd>
      <dt>每次执行上限</dt><dd>{definition.limits.maxImagesPerRun} 张图片 / {definition.limits.maxModelCallsPerRun} 次模型 / {definition.limits.maxDurationSeconds} 秒</dd>
      {current.expectedVersion !== null && <><dt>计划基准版本</dt><dd>{current.expectedVersion}</dd></>}
    </dl>
    {definition.instruction && <p className="mt-3 whitespace-pre-wrap break-words text-xs leading-6">{definition.instruction}</p>}
    <details className="mt-2 text-xs"><summary className="cursor-pointer text-accent">核对完整计划定义</summary><pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-all">{JSON.stringify(definition, null, 2)}</pre></details>
    {current.status === 'AWAITING_REVIEW' ? <>
      <p className="mt-3 text-xs text-text-muted">此提案尚未写入正式计划。确认后按上述范围和额度安排任务，后续执行可能产生费用。</p>
      {expired && <p className="mt-2 text-xs text-warning">提案已过期，请让助手重新准备。</p>}
      <div className="mt-3 flex flex-wrap gap-2">
        <button type="button" className="workspace-primary-button" disabled={busy || expired} onClick={() => { void decide('confirm'); }}>{busy ? '正在保存…' : `确认${actionLabels[current.action]}`}</button>
        <button type="button" className="workspace-secondary-button" disabled={busy} onClick={() => { void decide('discard'); }}>放弃提案</button>
      </div>
    </> : <p className="mt-3 text-xs" role="status">{current.applied ? '计划操作已保存。定时执行结果请到任务中心查看。' : '提案已放弃。'}{current.result && <a className="ml-2 text-accent" href={`#/automations/${current.result.id}`}>查看计划</a>}</p>}
    {error && <p className="mt-2 text-xs text-warning" role="alert">{error}</p>}
  </section>;
}

export default function AutomationCommandProposals({ chapterId, conversationId, refreshToken = '' }: {
  chapterId?: number; conversationId?: string; refreshToken?: string;
}) {
  const [items, setItems] = useState<AutomationCommandProposal[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let live = true;
    void listAutomationCommands(chapterId, conversationId).then(page => {
      if (live) { setItems(page.items); setNext(page.hasMore ? page.nextCursor : null); setError(''); }
    }).catch(e => { if (live) setError(e instanceof Error ? e.message : '计划提案暂时无法读取。'); });
    return () => { live = false; };
  }, [chapterId, conversationId, refreshToken, revision]);
  async function more() {
    if (!next || loading) return;
    setLoading(true);
    try {
      const page = await listAutomationCommands(chapterId, conversationId, next);
      setItems(previous => [...previous, ...page.items.filter(item => !previous.some(old => old.artifactId === item.artifactId))]);
      setNext(page.hasMore ? page.nextCursor : null); setError('');
    } catch (e) { setError(e instanceof Error ? e.message : '读取后续提案失败。'); }
    finally { setLoading(false); }
  }
  if (!items.length && !error) return null;
  return <div className="space-y-3" aria-label="助手准备的计划提案">
    {items.map(item => <CommandReview key={item.artifactId} proposal={item} onUpdated={() => setRevision(value => value + 1)} />)}
    {error && <p role="alert" className="text-xs text-warning">{error} <button type="button" onClick={() => setRevision(value => value + 1)}>重新读取</button></p>}
    {next && <button type="button" className="workspace-secondary-button" disabled={loading} onClick={() => { void more(); }}>{loading ? '正在读取…' : '读取更多计划提案'}</button>}
  </div>;
}
