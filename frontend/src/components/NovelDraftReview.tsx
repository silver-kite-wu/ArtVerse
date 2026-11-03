import { useMemo, useRef, useState } from 'react';
import { ArrowDown, ArrowUp, Check, FileDiff, Undo2 } from 'lucide-react';
import { buildNovelDiff, inlineNovelDiff, mergeNovelDiff, textLines, type NovelDiffBlock } from './novelDiff';

export interface NovelReviewDraft {
  requestId: string;
  artifactId: string;
  content: string;
  originalContent: string;
  contentHash: string;
  baseVersion: number;
  busy: boolean;
  error: string;
}

interface Props {
  draft: NovelReviewDraft;
  stale: boolean;
  onDecision: (decision: 'confirm' | 'discard', content: string) => void;
}

export default function NovelDraftReview({ draft, stale, onDecision }: Props) {
  const blocks = useMemo(() => buildNovelDiff(draft.originalContent, draft.content), [draft.originalContent, draft.content]);
  const changes = blocks.filter(block => block.changed);
  const [excluded, setExcluded] = useState<Set<number>>(new Set());
  const [mode, setMode] = useState<'diff' | 'edit'>('diff');
  const [manualText, setManualText] = useState<string | null>(null);
  const [activeChange, setActiveChange] = useState(0);
  const rootRef = useRef<HTMLElement>(null);
  const merged = mergeNovelDiff(blocks, excluded);
  const finalText = manualText ?? merged;
  const selected = changes.length - excluded.size;
  const valid = !!finalText.trim() && finalText.trim().length <= 50000;
  const navigate = (direction: number) => {
    const next = (activeChange + direction + changes.length) % changes.length;
    setActiveChange(next);
    rootRef.current?.querySelector<HTMLElement>(`[data-change-id="${changes[next]?.id}"]`)?.focus({ preventScroll: true });
    rootRef.current?.querySelector(`[data-change-id="${changes[next]?.id}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  };

  return <section ref={rootRef} className="novel-review" aria-label="AI 文字修改审阅" aria-busy={draft.busy}>
    <header className="novel-review-heading"><div><h2><FileDiff size={17} aria-hidden="true" />审阅本章修改</h2><p>基于版本 {draft.baseVersion} · {changes.length} 处修改 · 正文在确认后更新</p></div><div className="novel-review-tabs" role="group" aria-label="审阅方式"><button type="button" aria-pressed={mode === 'diff'} onClick={() => setMode('diff')}>修改对比</button><button type="button" aria-pressed={mode === 'edit'} onClick={() => setMode('edit')}>编辑最终稿</button></div></header>
    <div className="novel-review-controls"><span><i className="novel-review-delete-key" />删除 <i className="novel-review-insert-key" />新增</span><div><button type="button" disabled={draft.busy || manualText !== null} onClick={() => setExcluded(new Set())}>全部采纳</button><button type="button" disabled={draft.busy || manualText !== null} onClick={() => setExcluded(new Set(changes.map(block => block.id)))}>全部保留原文</button><button type="button" aria-label="上一处修改" disabled={!changes.length || mode !== 'diff'} onClick={() => navigate(-1)}><ArrowUp size={14} /></button><button type="button" aria-label="下一处修改" disabled={!changes.length || mode !== 'diff'} onClick={() => navigate(1)}><ArrowDown size={14} /></button></div></div>
    {stale && <p role="alert" className="novel-review-warning">正文已有新版本。这份建议仍可查看，请放弃后基于最新正文重新生成。</p>}
    {manualText !== null && <p className="novel-review-warning">最终稿已手工编辑，对比仍展示 AI 的原始建议。<button type="button" disabled={draft.busy} onClick={() => { if (window.confirm('重新选择修改会清除最终稿中的手工编辑，是否继续？')) setManualText(null); }}>回到逐处选择</button></p>}
    {mode === 'diff' ? <div className="novel-review-body" tabIndex={0} aria-label="正文差异">
      {!changes.length && <p className="novel-review-empty">AI 草稿与原文相同，无需写入。</p>}
      {blocks.map(block => block.changed ? <ChangeBlock key={block.id} block={block} included={!excluded.has(block.id)} disabled={draft.busy || manualText !== null} onToggle={() => setExcluded(current => { const next = new Set(current); if (next.has(block.id)) next.delete(block.id); else next.add(block.id); return next; })} /> : <ContextBlock key={block.id} text={block.original} line={block.oldLine} />)}
    </div> : <div className="novel-review-body novel-review-edit"><label htmlFor="novel-reviewed-text">确认时将保存下方完整正文，可直接修改。</label><textarea id="novel-reviewed-text" aria-label="最终稿正文" value={finalText} disabled={draft.busy} onChange={event => setManualText(event.target.value)} /></div>}
    <footer className="novel-review-footer"><p aria-live="polite">{manualText !== null ? '含手工编辑' : `采纳 ${selected} / ${changes.length} 处`} · 最终稿 {finalText.trim().length.toLocaleString()} 字</p>{!valid && <p role="alert">最终稿须为 1–50,000 字。</p>}{draft.error && <p role="alert">{draft.error}</p>}<div><button type="button" disabled={draft.busy} className="workspace-secondary-button" onClick={() => onDecision('discard', finalText)}>放弃本次修改</button><button type="button" disabled={draft.busy || stale || !valid || finalText === draft.originalContent} className="workspace-primary-button" onClick={() => onDecision('confirm', finalText)}><Check size={14} />{draft.busy ? '处理中…' : '确认并保存新版本'}</button></div></footer>
  </section>;
}

function ChangeBlock({ block, included, disabled, onToggle }: { block: NovelDiffBlock; included: boolean; disabled: boolean; onToggle: () => void }) {
  const inline = useMemo(() => inlineNovelDiff(block.original, block.proposed), [block.original, block.proposed]);
  return <section className={`novel-review-change ${included ? '' : 'is-excluded'}`} data-change-id={block.id} tabIndex={-1} aria-label={`原文第 ${block.oldLine} 行的修改`}>
    <header><span>原文 {block.oldLine} 行 → 草稿 {block.newLine} 行 · {included ? '将采纳' : '保留原文'}</span><button type="button" disabled={disabled} aria-pressed={!included} onClick={onToggle}><Undo2 size={12} />{included ? '保留原文' : '改为采纳'}</button></header>
    {block.original && <div className="novel-review-line is-delete"><span className="novel-review-gutter" aria-label="删除">−</span><div>{inline.filter(part => part.type !== 'insert').map((part, index) => part.type === 'delete' ? <mark key={index}>{part.text}</mark> : part.text)}</div></div>}
    {block.proposed && <div className="novel-review-line is-insert"><span className="novel-review-gutter" aria-label="新增">+</span><div>{inline.filter(part => part.type !== 'delete').map((part, index) => part.type === 'insert' ? <mark key={index}>{part.text}</mark> : part.text)}</div></div>}
  </section>;
}

function ContextBlock({ text, line }: { text: string; line: number }) {
  const lines = textLines(text);
  return <div className="novel-review-context">{lines.length > 4 ? <><p>{lines[0]}</p><details><summary>展开 {lines.length - 2} 行未修改内容</summary><p>{lines.slice(1, -1).join('')}</p></details><p>{lines.at(-1)}</p></> : <p><span className="novel-review-context-number" aria-hidden="true">{line}</span>{text}</p>}</div>;
}
