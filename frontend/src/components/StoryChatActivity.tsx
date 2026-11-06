import { useEffect, useState } from 'react';
import { Check, ChevronDown, CircleAlert, Clock3, LoaderCircle, Square, Wrench } from 'lucide-react';
import type { StoryChatActivity as Activity } from './storyChatActivityState';

const statusLabels = { running: '正在执行', waiting: '等待人工审核', succeeded: '已完成', failed: '执行失败', cancelled: '已停止' };
export default function StoryChatActivity({ activity }: { activity: Activity }) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    if (activity.status !== 'running') return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [activity.status]);
  const seconds = Math.max(0, Math.round(((activity.status === 'running' ? now : activity.updatedAt) - activity.startedAt) / 1000));
  const label = activity.reviewed && activity.status === 'waiting' ? '审阅已提交，等待总结'
    : activity.reviewed && activity.status === 'failed' ? '总结失败，审阅结果已保留' : statusLabels[activity.status];
  return <details className="story-chat-activity" open={activity.status === 'running' || activity.status === 'waiting' || activity.status === 'failed'}>
    <summary><ChevronDown size={13} /><span>思考与执行过程</span><span className="ml-auto text-text-muted">{label} · {seconds} 秒</span></summary>
    <ol aria-label="AI 执行记录">{activity.steps.map(step => <li key={step.id} data-status={step.status}>
      <span className="mt-0.5 shrink-0" aria-hidden="true">{step.status === 'running' ? <LoaderCircle size={13} className="animate-spin motion-reduce:animate-none" /> : step.status === 'failed' ? <CircleAlert size={13} /> : step.status === 'waiting' ? <Clock3 size={13} /> : step.status === 'cancelled' ? <Square size={12} /> : <Check size={13} />}</span>
      <div className="min-w-0 flex-1"><div className="flex items-center gap-1.5">{step.toolName && <Wrench size={11} />}<span>{step.label}</span><span className="ml-auto shrink-0 text-[10px] text-text-muted">{statusLabels[step.status]}{step.durationMs != null ? ` · ${step.durationMs < 1000 ? `${step.durationMs}ms` : `${(step.durationMs / 1000).toFixed(1)}s`}` : ''}</span></div>
        {step.toolName && <code className="text-[10px] text-text-muted">{step.toolName}</code>}
        {step.detail && <p className="mt-0.5 text-[11px] text-text-muted">{step.detail}</p>}</div>
    </li>)}</ol>
    {activity.error && <p role="alert" className="px-3 pb-3 text-xs text-red-500">{activity.error}</p>}
    <p className="px-3 pb-2 text-[10px] text-text-muted">展示实际执行阶段与工具结果摘要。</p>
  </details>;
}
