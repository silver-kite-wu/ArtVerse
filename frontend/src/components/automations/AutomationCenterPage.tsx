import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeft, Bell, CalendarClock, ChevronRight, CircleAlert, Clock3, Image, Loader2, Pause, Pencil, Play, Plus, Search, Sparkles } from 'lucide-react';
import { authFetch } from '../../api/auth';
import { imageGenUrl } from '../../api/knowledgeAndImage';
import { listAutomations, getAutomation, changeAutomation, runAutomationNow, listAutomationRuns, getAutomationRun, actOnAutomationRun, listAutomationNotifications, readAutomationNotification, automationStateLabel, automationRule, automationTime, type AutomationPlan, type AutomationRun, type AutomationRunDetail, type AutomationNotification } from '../../api/automations';
import AutomationComposer from './AutomationComposer';
import AutomationNovelReview from './AutomationNovelReview';
import AutomationCommandProposals from './AutomationCommandProposals';
import CreativeProposals from '../CreativeProposals';
import AgentQuestion from '../AgentQuestion';
import MarkdownRenderer from '../MarkdownRenderer';
import { useToast } from '../../ui/toast/useToast';
import { randomUUID } from '../../utils/browserSecurity';

interface Props { automationId?: string; runId?: string }
const button = 'inline-flex items-center justify-center gap-2 rounded-xl border border-border px-3 py-2 text-sm font-medium transition-colors hover:bg-bg-base disabled:opacity-40';
function navigate(planId?: string, runId?: string) { window.location.hash = planId ? `#/automations/${planId}${runId ? `?run=${runId}` : ''}` : '#/automations'; }
function State({ value }: { value: string }) { return <span className={`inline-flex shrink-0 items-center whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-medium ${['WAITING_USER', 'FAILED', 'PARTIAL_FAILED'].includes(value) ? 'bg-warning/10 text-warning' : ['SUCCEEDED', 'ACTIVE'].includes(value) ? 'bg-accent/10 text-accent' : 'bg-bg-base text-text-secondary'}`}>{automationStateLabel[value] ?? value}</span>; }

export default function AutomationCenterPage({ automationId, runId }: Props) {
  const toast = useToast();
  const [plans, setPlans] = useState<AutomationPlan[]>([]);
  const [plan, setPlan] = useState<AutomationPlan | null>(null);
  const [runs, setRuns] = useState<AutomationRun[]>([]);
  const selectedRun = runId || '';
  const setSelectedRun = (id: string) => navigate(automationId, id);
  const [detail, setDetail] = useState<AutomationRunDetail | null>(null);
  const [filter, setFilter] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [more, setMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [composer, setComposer] = useState<'create' | 'edit' | null>(null);
  const [notifications, setNotifications] = useState<AutomationNotification[]>([]);
  const [showNotifications, setShowNotifications] = useState(false);
  const [answer, setAnswer] = useState('');
  const selectedRunRef = useRef(selectedRun);
  useEffect(() => { selectedRunRef.current = selectedRun; }, [selectedRun]);
  const keys = useRef(new Map<string, string>());
  const actionBusyRef = useRef(false);
  const keySlot = (action: string) => JSON.stringify([automationId, selectedRun, detail?.userInputRequest?.toolCallId,
    detail?.userInputRequest?.pendingVersion, detail?.reviewReceipt?.artifact_id, action]);
  const keyFor = (action: string) => { const slot = keySlot(action); let key = keys.current.get(slot); if (!key) { key = randomUUID(); keys.current.set(slot, key); } return key; };

  const refreshPlans = useCallback(async () => {
    const params = new URLSearchParams({ page: String(page), search, ...(filter === 'attention' ? { needsAttention: 'true' } : filter ? { status: filter } : {}) });
    const result = await listAutomations(params); setPlans(result.items); setMore(result.hasMore);
  }, [filter, search, page]);
  const refreshDetail = useCallback(async () => {
    if (!automationId) return;
    const [value, history] = await Promise.all([getAutomation(automationId), listAutomationRuns(automationId)]);
    setPlan(value); setRuns(previous => [...history.items, ...previous.filter(item => item.automationId === value.id && !history.items.some(newItem => newItem.id === item.id))]);
  }, [automationId]);
  useEffect(() => {
    let live = true;
    void Promise.resolve().then(() => Promise.all([refreshPlans(), refreshDetail(), listAutomationNotifications().then(value => { if (live) setNotifications(value.items); })]))
      .catch(e => { if (live) setError(e.message); }).finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [refreshPlans, refreshDetail]);
  useEffect(() => {
    const changed = () => { void refreshPlans().catch(e => setError(e.message)); };
    window.addEventListener('artverse:automations-changed', changed);
    const timer = window.setInterval(() => { changed(); void refreshDetail().catch(() => {}); void listAutomationNotifications().then(value => setNotifications(value.items)).catch(() => {}); }, 10000);
    return () => { window.removeEventListener('artverse:automations-changed', changed); clearInterval(timer); };
  }, [refreshPlans, refreshDetail]);
  useEffect(() => {
    if (!selectedRun) return;
    let live = true;
    const controller = new AbortController();
    let cursor = 0;
    const load = async () => { const value = await getAutomationRun(selectedRun); if (live) setDetail(value); };
    void load().catch(e => { if (live) setError(e.message); });
    const timer = window.setInterval(() => { void load().catch(() => {}); void refreshDetail().catch(() => {}); }, 5000);
    // The cursor is retained between reconnects. Periodic snapshots also refresh per-image progress.
    const subscribe = async () => {
      while (live) {
        try {
          const res = await authFetch(`/api/automation-runs/${selectedRun}/events?after=${cursor}`, { signal: controller.signal });
          if (!res.ok || !res.body) return;
          const reader = res.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
          while (live) {
            const chunk = await reader.read(); if (chunk.done) break;
            buffer += decoder.decode(chunk.value, { stream: true }).replace(/\r\n/g, '\n');
            let index: number;
            while ((index = buffer.indexOf('\n\n')) >= 0) {
              const frame = buffer.slice(0, index); buffer = buffer.slice(index + 2);
              const id = frame.match(/^id:\s*(\d+)/m); if (id) cursor = Number(id[1]);
              if (frame.includes('event:snapshot') || frame.includes('event: snapshot')) {
                const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
                if (data && live) setDetail(JSON.parse(data) as AutomationRunDetail);
              } else if (frame.includes('data:')) void load().catch(() => {});
            }
          }
        } catch { if (!live) return; }
        if (!live) return;
        await new Promise<void>(resolve => { const timeout = setTimeout(resolve, 3000); controller.signal.addEventListener('abort', () => { clearTimeout(timeout); resolve(); }, { once: true }); });
      }
    };
    void subscribe();
    return () => { live = false; controller.abort(); clearInterval(timer); };
  }, [selectedRun, refreshDetail]);

  async function act(name: string, operation: () => Promise<unknown>, refresh = true) {
    if (actionBusyRef.current) return;
    actionBusyRef.current = true;
    const targetRun = selectedRun;
    const slot = keySlot(name);
    setBusy(name); setError('');
    try { await operation(); if (!['respond', 'reviewed'].includes(name)) keys.current.delete(slot); if (refresh) await Promise.all([refreshPlans(), refreshDetail(), listAutomationNotifications().then(value => setNotifications(value.items))]); if (targetRun) { const latest = await getAutomationRun(targetRun); if (selectedRunRef.current === targetRun) setDetail(latest); } toast.success('已更新'); }
    catch (e) { toast.error(e instanceof Error ? e.message : '操作失败'); if (targetRun) { try { const latest = await getAutomationRun(targetRun); if (selectedRunRef.current === targetRun) setDetail(latest); } catch { /* Keep the submitted input available when refresh also fails. */ } } }
    finally { actionBusyRef.current = false; setBusy(''); }
  }
  const run = detail?.run.id === selectedRun && detail.run.automationId === automationId ? detail.run : undefined;
  const pendingDraft = detail?.artifacts?.filter(artifact => artifact.kind === 'NOVEL_CONTENT_DRAFT' && artifact.state === 'VALIDATED' && artifact.review).at(-1);
  const unread = notifications.filter(n => !n.read).length;

  return <main className="flex min-h-0 flex-1 flex-col bg-bg-base pb-16 lg:pb-0">
    <header className="flex flex-wrap items-center justify-between gap-4 border-b border-border px-5 py-5 sm:px-8"><div><div className="mb-1 text-[0.625rem] tracking-[0.18em] text-text-muted">YOUR CREATIVE ROUTINE</div><h1 className="flex items-center gap-2 text-xl font-semibold"><CalendarClock size={22} className="text-accent" />定时任务</h1><p className="mt-1 text-xs text-text-secondary">安排创作，让灵感按时发生。</p></div><div className="relative flex gap-2"><button className={button} aria-expanded={showNotifications} onClick={() => setShowNotifications(!showNotifications)}><Bell size={16} />待办{unread > 0 && <span className="rounded-full bg-accent px-1.5 text-xs text-white">{unread}</span>}</button><button className={`${button} border-accent bg-accent text-white hover:bg-accent/90`} onClick={() => setComposer('create')}><Plus size={17} />新建任务</button>
      {showNotifications && <div className="absolute right-0 top-12 z-20 max-h-96 w-[min(23rem,85vw)] overflow-y-auto rounded-2xl border border-border bg-bg-surface p-3 shadow-xl"><h2 className="px-2 py-2 text-sm font-semibold">任务通知</h2>{notifications.length === 0 && <p className="p-4 text-sm text-text-muted">暂无通知</p>}{notifications.map(item => <button key={item.id} onClick={() => { void act(`notification:${item.id}`, async () => { await readAutomationNotification(item.id); const info = await getAutomationRun(item.runId); navigate(info.run.automationId, item.runId); }); setShowNotifications(false); }} className="mb-1 block w-full rounded-xl p-3 text-left hover:bg-bg-base"><p className="text-sm font-medium">{!item.read && <span className="mr-2 inline-block h-1.5 w-1.5 rounded-full bg-accent" />}{item.name}</p><p className="mt-1 line-clamp-2 text-xs text-text-secondary">{item.summary}</p><time className="mt-1 block text-[0.625rem] text-text-muted">{automationTime(item.createdAt)}</time></button>)}</div>}
    </div></header>
    {error && <div role="alert" className="flex items-center gap-2 bg-warning/10 px-5 py-3 text-sm"><CircleAlert size={16} />{error}</div>}
    {!automationId && <div className="max-h-[40vh] overflow-y-auto px-5 py-3"><AutomationCommandProposals refreshToken={String(plans.length)} /></div>}
    {!automationId && <div className="max-h-[40vh] overflow-y-auto px-5 py-3"><CreativeProposals refreshToken={String(plans.length)} /></div>}
    <div className="flex min-h-0 flex-1">
      <aside className={`${automationId ? 'hidden md:flex' : 'flex'} w-full shrink-0 flex-col border-r border-border md:w-80 xl:w-96`}>
        <div className="space-y-3 px-5 py-4"><label className="flex items-center gap-2 rounded-xl border border-border bg-bg-surface px-3 py-2.5"><Search size={16} className="text-text-muted" /><input aria-label="搜索定时任务" placeholder="搜索任务……" value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} className="min-w-0 flex-1 bg-transparent text-sm outline-none" /></label><div className="flex flex-wrap gap-1">{[['', '全部'], ['ACTIVE', '启用中'], ['PAUSED', '已暂停'], ['attention', '待处理'], ['ARCHIVED', '已归档']].map(([value, text]) => <button key={value} onClick={() => { setFilter(value); setPage(0); }} aria-pressed={filter === value} className={`rounded-full px-3 py-1.5 text-xs ${filter === value ? 'bg-accent/10 font-semibold text-accent' : 'text-text-secondary hover:bg-bg-surface'}`}>{text}</button>)}</div></div>
        <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-3">{loading && <div className="flex justify-center py-10"><Loader2 className="animate-spin text-accent" /></div>}{!loading && plans.length === 0 && <div className="px-5 py-10 text-center"><Clock3 size={30} className="mx-auto text-text-muted" /><p className="mt-4 text-sm font-medium">{search || filter ? '没有符合条件的任务' : '还没有安排任务'}</p><p className="mt-2 text-xs leading-5 text-text-muted">预约一次生图，或让创作助手定期检查你的作品。</p><button className="mt-4 text-sm font-medium text-accent" onClick={() => setComposer('create')}>创建第一个任务</button></div>}{plans.map(item => <button key={item.id} onClick={() => navigate(item.id)} className={`mb-2 w-full rounded-2xl border p-4 text-left transition-colors ${item.id === automationId ? 'border-accent/30 bg-accent/5' : 'border-transparent hover:border-border hover:bg-bg-surface'}`}><div className="flex items-start gap-3"><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-bg-surface text-accent">{item.definition.taskType === 'IMAGE' ? <Image size={17} /> : <Sparkles size={17} />}</span><div className="min-w-0 flex-1"><h2 className="truncate text-sm font-semibold">{item.definition.name}</h2><p className="mt-1 truncate text-xs text-text-muted">{item.definition.taskType === 'IMAGE' ? '生图任务' : 'Agent 任务'} · {automationRule(item.definition.schedule)}</p></div></div><div className="mt-3 flex items-center justify-between gap-2"><State value={item.status} /><span className="text-[0.6875rem] text-text-secondary">{item.nextRunAt ? `下次 ${automationTime(item.nextRunAt, item.definition.schedule.timezone)}` : item.status === 'ACTIVE' ? '本次任务已登记' : '暂无后续执行'}</span></div></button>)}</div>
        <div className="flex items-center justify-between border-t border-border px-5 py-3 text-xs text-text-secondary"><button disabled={page === 0} className="disabled:opacity-30" onClick={() => setPage(page - 1)}>上一页</button><span>第 {page + 1} 页</span><button disabled={!more} className="disabled:opacity-30" onClick={() => setPage(page + 1)}>下一页</button></div>
      </aside>
      <section className={`${automationId ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col overflow-y-auto`}>
        {!plan ? <div className="m-auto max-w-sm p-8 text-center"><CalendarClock size={48} strokeWidth={1} className="mx-auto text-accent/60" /><h2 className="mt-6 text-xl font-medium">把重复的创作交给时间</h2><p className="mt-3 text-sm leading-7 text-text-muted">选择左侧任务查看设置和执行记录。你也可以定时检查分镜、准备小说草稿，或预约生成图片。</p></div> : <div className="mx-auto w-full max-w-4xl p-5 sm:p-8">
          <button className="mb-5 flex items-center gap-1 text-xs text-text-secondary md:hidden" onClick={() => navigate()}><ArrowLeft size={14} />返回任务列表</button>
          <div className="flex flex-wrap items-start justify-between gap-4"><div><div className="mb-3"><State value={plan.status} /></div><h2 className="text-2xl font-semibold">{plan.definition.name}</h2><p className="mt-2 text-xs text-text-secondary">{automationRule(plan.definition.schedule)}</p></div><div className="flex flex-wrap gap-2">{!['COMPLETED', 'ARCHIVED'].includes(plan.status) && <><button className={button} disabled={!!busy} onClick={() => setComposer('edit')} aria-label="编辑任务"><Pencil size={15} /></button><button className={button} disabled={!!busy} onClick={() => { void act('lifecycle', () => changeAutomation(plan, plan.status === 'PAUSED' ? 'resume' : 'pause')); }}>{plan.status === 'PAUSED' ? <Play size={15} /> : <Pause size={15} />}{plan.status === 'PAUSED' ? '恢复' : '暂停'}</button></>}{plan.status !== 'ARCHIVED' && <button className={button} disabled={!!busy} onClick={() => { void act('run-now', async () => { const result = await runAutomationNow(plan.id, keyFor('run-now')); setSelectedRun(result.id); }); }}><Play size={15} />立即执行</button>}</div></div>
          <div className="my-6 rounded-2xl border border-border bg-bg-surface p-5"><h3 className="mb-2 text-xs font-medium text-text-muted">任务说明</h3><p className="whitespace-pre-wrap text-sm leading-7">{plan.definition.instruction || '生成指定章节缺失的图片'}</p><div className="mt-4 flex flex-wrap gap-x-6 gap-y-2 border-t border-border pt-4 text-xs text-text-secondary"><span>下次执行：{plan.nextRunAt ? automationTime(plan.nextRunAt, plan.definition.schedule.timezone) : '暂无后续时间'}</span><span>{plan.definition.payload.model}</span><span>{plan.definition.taskType === 'IMAGE' ? `每次最多 ${plan.definition.limits.maxImagesPerRun} 张` : `每次最多 ${plan.definition.limits.maxModelCallsPerRun} 次模型调用`}</span>{plan.definition.payload.storyId && <a className="text-accent" href={`#/editor/${plan.definition.payload.storyId}`}>打开作品 ↗</a>}</div></div>
          <div className="mb-4 flex items-center justify-between"><h3 className="text-base font-semibold">执行记录</h3>{plan.status !== 'ARCHIVED' && <button className="text-xs text-text-muted hover:text-text-primary" disabled={!!busy} onClick={() => { void act('archive', () => changeAutomation(plan, 'archive')); }}>归档任务</button>}</div>
          {runs.length === 0 && <p className="rounded-2xl border border-dashed border-border py-8 text-center text-sm text-text-muted">还未执行，到点后会在这里留下记录。</p>}
          <div className="space-y-2">{runs.map(item => <button key={item.id} onClick={() => { setSelectedRun(item.id); setDetail(null); }} className={`flex w-full items-center justify-between gap-3 rounded-xl border px-4 py-3 text-left ${selectedRun === item.id ? 'border-accent/30 bg-accent/5' : 'border-border bg-bg-surface'}`}><div className="min-w-0 flex-1"><span className="text-sm font-medium">{automationTime(item.scheduledFor, plan.definition.schedule.timezone)}</span><span className="ml-2 text-xs text-text-muted">{item.triggerKind === 'MANUAL' ? '立即执行' : '计划执行'}</span><p className="mt-1 line-clamp-1 text-xs text-text-secondary">{item.summary || '等待执行'}</p></div><div className="flex shrink-0 items-center gap-2"><State value={item.state} /><ChevronRight size={14} /></div></button>)}</div>
          {runs.length >= 20 && <button className="mt-3 text-xs text-accent" onClick={() => { void act('more-runs', async () => { const older = await listAutomationRuns(plan.id, runs[runs.length - 1].id); setRuns([...runs, ...older.items]); }, false); }}>加载更早记录</button>}
          {run && detail && <article className="mt-6 rounded-2xl border border-border bg-bg-surface p-5"><div className="flex flex-wrap items-center justify-between gap-3"><h3 className="text-sm font-semibold">本次运行 · <State value={run.state} /></h3><div className="flex gap-2">{['FAILED', 'PARTIAL_FAILED'].includes(run.state) && <button className={button} disabled={!!busy} onClick={() => { void act('retry', () => actOnAutomationRun(run.id, 'retry', keyFor('retry'))); }}>重试失败项</button>}{['QUEUED', 'RUNNING', 'RETRY_WAIT', 'WAITING_USER'].includes(run.state) && <button className={button} disabled={!!busy} onClick={() => { void act('cancel', () => actOnAutomationRun(run.id, 'cancel', keyFor('cancel'))); }}>停止本次运行</button>}</div></div>
            <dl className="my-4 grid grid-cols-2 gap-3 text-xs text-text-secondary sm:grid-cols-3"><div><dt className="text-text-muted">原定执行</dt><dd className="mt-1">{automationTime(run.scheduledFor, plan.definition.schedule.timezone)}</dd></div><div><dt className="text-text-muted">实际开始</dt><dd className="mt-1">{automationTime(run.startedAt, plan.definition.schedule.timezone)}</dd></div><div><dt className="text-text-muted">结束时间</dt><dd className="mt-1">{automationTime(run.completedAt, plan.definition.schedule.timezone)}</dd></div></dl>
            {run.summary && <div className="text-sm leading-7"><MarkdownRenderer content={run.summary} /></div>}
            {detail.artifacts && detail.artifacts.length > 0 && <div className="mt-5 grid gap-3 sm:grid-cols-2">{detail.artifacts.map(artifact => <div key={artifact.id} className="overflow-hidden rounded-xl border border-border">{artifact.url && <a href={imageGenUrl(artifact.url)} target="_blank" rel="noreferrer"><img src={imageGenUrl(artifact.url)} alt={artifact.title} className="max-h-80 w-full object-contain" loading="lazy" /></a>}<div className="p-3"><p className="text-xs font-medium">{artifact.title} {artifact.state && <span className="text-text-muted">· {automationStateLabel[artifact.state] ?? artifact.state}</span>}</p>{artifact.error && <p className="mt-2 text-xs text-warning">{artifact.error}</p>}{artifact.content && <details className="mt-2 text-xs"><summary className="cursor-pointer text-accent">查看产物内容</summary><div className="mt-3 whitespace-pre-wrap leading-6">{artifact.content}</div></details>}</div></div>)}</div>}
            {run.state === 'WAITING_USER' && run.definition.executorKey === 'STORY_CHAT' && pendingDraft && !detail.reviewReceipt && <AutomationNovelReview key={pendingDraft.id} runId={run.id} artifact={pendingDraft} onUpdated={async () => setDetail(await getAutomationRun(run.id))} />}
            {run.state === 'WAITING_USER' && run.definition.executorKey === 'STORY_CHAT' && detail.reviewReceipt && <section className="mt-5 rounded-xl border border-border p-4"><p>审阅结果已保存，可以继续生成总结。</p><button type="button" className={button} disabled={!!busy} onClick={() => { void act('reviewed', () => actOnAutomationRun(run.id, 'respond', keyFor('reviewed'), { decision: 'reviewed', artifactId: detail.reviewReceipt!.artifact_id })); }}>继续生成总结</button></section>}
            {run.state === 'WAITING_USER' && <div className="mt-5 grid gap-3 rounded-xl border border-warning/30 bg-warning/5 p-4">
              {run.waitingReason === 'AGENT_INPUT' && !(run.definition.executorKey === 'STORY_CHAT' && (pendingDraft || detail.reviewReceipt)) && (
                run.definition.executorKey === 'STORY_CHAT' || detail.userInputRequest?.purpose === 'USER_QUESTION'
                  ? detail.userInputRequest ? <AgentQuestion
                    key={`${run.id}:${detail.userInputRequest.toolCallId}:${detail.userInputRequest.pendingVersion}`}
                    request={detail.userInputRequest} busy={!!busy} onAnswer={response => {
                      const pending = detail.userInputRequest!;
                      if (!pending.toolCallId || !pending.pendingVersion) return;
                      void act('respond', () => actOnAutomationRun(run.id, 'respond', keyFor('respond'), {
                        decision: 'answer', toolCallId: pending.toolCallId, pendingVersion: pending.pendingVersion, ...response,
                      }));
                    }} /> : <p role="status" className="text-sm">提问详情暂不可用，请刷新后处理。</p>
                  : <>
                    <p className="text-sm font-medium">{detail.userInputRequest?.question || '请检查本次运行的输入'}</p>
                    <textarea aria-label="回复 Agent" className="rounded-xl border border-border bg-bg-surface p-3 text-sm" rows={3}
                      maxLength={4000} value={answer} onChange={e => setAnswer(e.target.value)} placeholder="输入补充说明……" />
                    {detail.userInputRequest?.options.map(option => <button key={option.id} className={`${button} text-left`}
                      disabled={!!busy} onClick={() => setAnswer(option.label)}>{option.label}</button>)}
                    <button className={`${button} bg-accent text-white`} disabled={!!busy || !answer.trim()} onClick={() => {
                      void act('respond', () => actOnAutomationRun(run.id, 'respond', keyFor('respond'), { answer, decision: 'confirm' }));
                    }}>提交回复</button>
                  </>
              )}
              {run.waitingReason !== 'AGENT_INPUT' && <p className="text-sm font-medium">{detail.userInputRequest?.question || '请检查本次运行的输入或供应商结果'}</p>}
              {['INPUT_CHANGED', 'CONFIGURATION'].includes(run.waitingReason ?? '') && <button className={button} disabled={!!busy} onClick={() => { void act('refresh-input', () => actOnAutomationRun(run.id, 'respond', keyFor('refresh-input'), { action: 'refresh-input' })); }}>已检查，重新校验本次输入</button>}
              <button className="text-left text-xs text-text-muted" disabled={!!busy} onClick={() => { void act('close', () => actOnAutomationRun(run.id, 'respond', keyFor('close'), { action: 'close' })); }}>关闭本次待处理运行</button>
            </div>}
            <details className="mt-5 border-t border-border pt-4"><summary className="cursor-pointer text-xs text-text-secondary">运行过程</summary><ol className="mt-3 space-y-3">{detail.events.map(event => <li key={event.id} className="flex gap-3 text-xs"><time className="shrink-0 text-text-muted">{automationTime(event.createdAt, plan.definition.schedule.timezone)}</time><span className="text-text-secondary">{event.payload.summary || automationStateLabel[event.type] || event.type}</span></li>)}</ol><p className="mt-4 break-all text-[0.625rem] text-text-muted">运行 ID：{run.id} · 计划版本 {run.automationVersion}</p></details>
          </article>}
        </div>}
      </section>
    </div>
    {composer && <AutomationComposer plan={composer === 'edit' ? plan ?? undefined : undefined} onClose={() => setComposer(null)} onSaved={saved => { const editing = composer === 'edit'; setComposer(null); navigate(saved.id); void refreshDetail(); toast.success(editing ? '定时任务已更新' : '定时任务已创建'); }} />}
  </main>;
}
