import { useCallback, useEffect, useRef, useState, type CSSProperties, type RefObject } from 'react';
import { Image, MessageSquare, Minus } from 'lucide-react';
import type { Chapter } from '../api';
import ChatPanel from './ChatPanel';
import MangaPanel from './MangaPanel';
import NovelEditor, { type NovelEditorHandle } from './NovelEditor';
import './workspace.css';

type Tool = 'ai' | 'manga';
const TOOL_KEY = 'artverse.workspace.activeTool';
const WIDTH_KEY = 'artverse.workspace.panelWidth';
const MIN_WIDTH = 280;
const MAX_WIDTH = 560;

function readTool(): Tool | null {
  try { const value = localStorage.getItem(TOOL_KEY); return value === 'ai' || value === 'manga' ? value : null; }
  catch { return null; }
}

function readWidth() {
  try { const value = Number(localStorage.getItem(WIDTH_KEY)); return Number.isFinite(value) && value >= MIN_WIDTH ? Math.min(MAX_WIDTH, value) : MIN_WIDTH; }
  catch { return MIN_WIDTH; }
}

interface Props {
  chapter: Chapter | null;
  editorRef: RefObject<NovelEditorHandle | null>;
  onMessageSent: () => Promise<void>;
  onChapterRefresh: (chapterId: number) => Promise<void>;
}

export default function WorkspaceToolWindows({ chapter, editorRef, onMessageSent, onChapterRefresh }: Props) {
  const [activeTool, setActiveTool] = useState<Tool | null>(readTool);
  const [panelWidth, setPanelWidth] = useState(readWidth);
  const [availableWidth, setAvailableWidth] = useState(0);
  const [reviewTarget, setReviewTarget] = useState<HTMLDivElement | null>(null);
  const [reviewOpen, setReviewOpen] = useState(false);
  const workspaceRef = useRef<HTMLDivElement>(null);
  const dockRef = useRef<HTMLElement>(null);
  const aiButtonRef = useRef<HTMLButtonElement>(null);
  const mangaButtonRef = useRef<HTMLButtonElement>(null);
  const dragRef = useRef<{ x: number; width: number } | null>(null);
  const actualWidth = Math.min(panelWidth, Math.max(MIN_WIDTH, availableWidth - 48 - 6 - 360));
  const maxWidth = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, availableWidth - 48 - 6 - 360));

  useEffect(() => {
    try { localStorage.setItem(TOOL_KEY, activeTool ?? 'closed'); }
    catch { /* The workspace still works when storage is unavailable. */ }
  }, [activeTool]);

  useEffect(() => {
    try { localStorage.setItem(WIDTH_KEY, String(panelWidth)); }
    catch { /* Keep the preference in memory for this session. */ }
  }, [panelWidth]);

  useEffect(() => {
    const element = workspaceRef.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setAvailableWidth(entry.contentRect.width));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (activeTool) dockRef.current?.querySelector<HTMLButtonElement>('[data-workspace-close]')?.focus({ preventScroll: true });
  }, [activeTool]);

  const closeTool = useCallback(() => {
    setActiveTool(null);
    (activeTool === 'ai' ? aiButtonRef : mangaButtonRef).current?.focus({ preventScroll: true });
  }, [activeTool]);

  useEffect(() => {
    if (!activeTool) return;
    const onEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || document.querySelector('[aria-modal="true"], dialog[open]')) return;
      event.preventDefault();
      closeTool();
    };
    window.addEventListener('keydown', onEscape);
    return () => window.removeEventListener('keydown', onEscape);
  }, [activeTool, closeTool]);

  return <div ref={workspaceRef} className="workspace-tool-windows" data-tool-open={activeTool !== null} style={{ '--workspace-panel-width': `${actualWidth}px` } as CSSProperties}>
    <main className="workspace-document">
      <NovelEditor key={chapter?.id ?? 'empty'} ref={editorRef} chapter={chapter} onChapterRefresh={onChapterRefresh} reviewOpen={reviewOpen} />
      <div ref={setReviewTarget} tabIndex={-1} className="workspace-review-slot" hidden={!reviewOpen} aria-label="正文修改审阅区" />
    </main>
    <button
      type="button" role="separator" aria-label="调整辅助面板宽度" aria-orientation="vertical" aria-valuemin={MIN_WIDTH} aria-valuemax={maxWidth} aria-valuenow={actualWidth}
      hidden={activeTool === null} className="workspace-resize-handle"
      onKeyDown={event => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); setPanelWidth(Math.max(MIN_WIDTH, Math.min(maxWidth, actualWidth + (event.key === 'ArrowLeft' ? 16 : -16)))); } }}
      onPointerDown={event => { if (event.button !== 0) return; event.preventDefault(); dragRef.current = { x: event.clientX, width: actualWidth }; event.currentTarget.setPointerCapture(event.pointerId); }}
      onPointerMove={event => { if (dragRef.current) setPanelWidth(Math.max(MIN_WIDTH, Math.min(maxWidth, dragRef.current.width + dragRef.current.x - event.clientX))); }}
      onPointerUp={event => { dragRef.current = null; if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId); }}
      onPointerCancel={() => { dragRef.current = null; }} onLostPointerCapture={() => { dragRef.current = null; }}
    />
    <button type="button" className="workspace-drawer-backdrop" hidden={activeTool === null} onClick={closeTool} aria-label="收起辅助面板" tabIndex={-1} />
    <aside ref={dockRef} id="workspace-tool-window" className="workspace-tool-window" hidden={activeTool === null} aria-labelledby="workspace-tool-title">
      <header className="workspace-tool-header"><h2 id="workspace-tool-title">{activeTool === 'manga' ? '漫画创作' : 'AI 助手'}</h2><button type="button" data-workspace-close aria-label="收起辅助面板" title="收起面板（Esc）" onClick={closeTool}><Minus size={18} /></button></header>
      <div className="workspace-tool-content" hidden={activeTool !== 'ai'}>
        <ChatPanel key={chapter?.id ?? 'empty'} chapter={chapter} active={activeTool === 'ai'} reviewTarget={reviewTarget} onReviewOpenChange={setReviewOpen} onOpenReview={() => { if (availableWidth <= 720) closeTool(); }} onMessageSent={onMessageSent} onChapterRefresh={onChapterRefresh} onBeforeSend={() => editorRef.current?.prepareForAi() ?? Promise.resolve(false)} onBeforeApply={version => editorRef.current?.checkAiDraft(version) ?? (editorRef.current ? undefined : '原文编辑器尚未就绪。')} onApplyingChange={busy => editorRef.current?.setAiWriting(busy)} />
      </div>
      <div className="workspace-tool-content" hidden={activeTool !== 'manga'}>
        <MangaPanel key={chapter?.id ?? 'empty'} chapter={chapter} onChapterRefresh={onChapterRefresh} onBeforeGenerateScenes={() => editorRef.current?.prepareForAi() ?? Promise.resolve(false)} />
      </div>
    </aside>
    <nav className="workspace-tool-rail" aria-label="创作辅助工具">
      <button ref={aiButtonRef} type="button" aria-label="AI 助手" aria-expanded={activeTool === 'ai'} aria-controls="workspace-tool-window" title="AI 助手" onClick={() => setActiveTool(current => current === 'ai' ? null : 'ai')}><MessageSquare size={20} aria-hidden="true" /><span>AI<br />助手</span></button>
      <button ref={mangaButtonRef} type="button" aria-label="漫画创作" aria-expanded={activeTool === 'manga'} aria-controls="workspace-tool-window" title="漫画创作" onClick={() => setActiveTool(current => current === 'manga' ? null : 'manga')}><Image size={20} aria-hidden="true" /><span>漫画<br />创作</span></button>
    </nav>
  </div>;
}
