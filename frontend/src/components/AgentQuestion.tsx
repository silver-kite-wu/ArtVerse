import { useState } from 'react';
import type { AgentAnswer, AgentUserInputRequest } from '../api/agentRuns';

interface Props {
  request: AgentUserInputRequest;
  busy: boolean;
  onAnswer: (answer: AgentAnswer) => void;
  onCancel?: () => void;
}

export default function AgentQuestion({ request, busy, onAnswer, onCancel }: Props) {
  const [answer, setAnswer] = useState('');
  const valid = !!request.toolCallId && Number.isSafeInteger(request.pendingVersion) && request.pendingVersion! > 0;
  return <section className="rounded-xl border border-border bg-bg-surface p-4" aria-label="助手待答问题">
    <p className="text-sm font-medium leading-6">{request.question}</p>
    {valid ? <>
      <div className="mt-3 flex flex-wrap gap-2">{request.options.map(option => <button key={option.id}
        type="button" disabled={busy} className="workspace-secondary-button" onClick={() => onAnswer({ selectedOptionId: option.id })}>
        {option.label}
      </button>)}</div>
      {request.allowFreeText && <form className="mt-3 grid gap-2" onSubmit={event => {
        event.preventDefault();
        if (!busy && answer.trim()) onAnswer({ answer: answer.trim() });
      }}>
        <textarea aria-label="回复助手问题" className="rounded-xl border border-border bg-bg-base p-3 text-sm"
          rows={3} maxLength={4000} disabled={busy} value={answer} onChange={event => setAnswer(event.target.value)} />
        <button type="submit" className="workspace-secondary-button" disabled={busy || !answer.trim()}>提交补充说明</button>
      </form>}
    </> : <p role="status" className="mt-2 text-xs text-text-muted">此问题使用旧协议，无法继续答复。已有内容保留，可关闭本次运行后重新发起。</p>}
    {onCancel && <button type="button" disabled={busy} className="mt-3 text-xs text-text-muted" onClick={onCancel}>关闭本次运行</button>}
  </section>;
}
