import { forwardRef, useEffect, useImperativeHandle, useState } from 'react';
import { FileText, History, Save } from 'lucide-react';
import { errorMessage, listNovelRevisions, restoreNovelRevision, saveNovelContent, type Chapter, type NovelRevision } from '../api';
import { useToast } from '../ui/toast/useToast';

const MAX_CHARS = 50000;
const sourceLabels = { manual: '手工保存', ai: 'AI 修改', restore: '恢复版本', legacy_import: '导入', generated: '生成' };

export interface NovelEditorHandle {
  prepareForAi: () => Promise<boolean>;
  checkAiDraft: (baseVersion: number) => string | undefined;
  setAiWriting: (busy: boolean) => void;
  canLeave: () => boolean;
}

interface Props {
  chapter: Chapter | null;
  onChapterRefresh: (chapterId: number) => Promise<void>;
  reviewOpen?: boolean;
}

const NovelEditor = forwardRef<NovelEditorHandle, Props>(function NovelEditor({ chapter, onChapterRefresh, reviewOpen = false }, ref) {
  const toast = useToast();
  const content = chapter?.novel_content ?? '';
  const [document, setDocument] = useState(() => ({ text: content, baseText: content, baseVersion: chapter?.version, seenText: content, seenVersion: chapter?.version }));
  const [writeBusy, setWriteBusy] = useState(false);
  const [aiWriting, setAiWriting] = useState(false);
  const [revisions, setRevisions] = useState<NovelRevision[]>([]);
  const [historyError, setHistoryError] = useState('');
  const [showHistory, setShowHistory] = useState(false);
  const dirty = document.text !== document.baseText;
  const conflict = dirty && document.baseText !== content;
  const busy = writeBusy || aiWriting;

  // A chapter refresh may update images or AI messages while the author is typing.
  // Rebase only when the saved text is unchanged, or the local document is clean.
  if (document.seenVersion !== chapter?.version || document.seenText !== content) {
    const canRebase = !dirty || document.baseText === content;
    setDocument({
      text: dirty ? document.text : content,
      baseText: canRebase ? content : document.baseText,
      baseVersion: canRebase ? chapter?.version : document.baseVersion,
      seenText: content,
      seenVersion: chapter?.version,
    });
  }

  useEffect(() => {
    if (!chapter) return;
    let active = true;
    void listNovelRevisions(chapter.id).then(items => { if (active) { setRevisions(items); setHistoryError(''); } }).catch(err => { if (active) setHistoryError(errorMessage(err) || '历史版本加载失败。'); });
    return () => { active = false; };
  }, [chapter?.id, chapter?.version]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!dirty && !busy && !reviewOpen) return;
    const protectDraft = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', protectDraft);
    return () => window.removeEventListener('beforeunload', protectDraft);
  }, [dirty, busy, reviewOpen]);

  const save = async (): Promise<boolean> => {
    if (!chapter || busy) return false;
    const text = document.text.trim();
    if (!text) { toast.warning('请输入小说原文。'); return false; }
    if (text.length > MAX_CHARS) { toast.warning(`内容不能超过 ${MAX_CHARS.toLocaleString()} 字。`); return false; }
    if (conflict) { toast.warning('正文已有新版本，本地草稿已保留。请先核对并载入最新正文。'); return false; }
    if (document.baseVersion === undefined) { toast.error('章节版本缺失，请刷新后再保存。'); return false; }
    setWriteBusy(true);
    try {
      const result = await saveNovelContent(chapter.id, text, document.baseVersion);
      setDocument(current => ({ ...current, text: current.text === document.text ? text : current.text, baseText: text, baseVersion: result.chapter_version }));
      await onChapterRefresh(chapter.id);
      toast.success('小说原文已保存');
      return true;
    } catch (err) { toast.error(errorMessage(err) || '保存失败，本地草稿已保留。'); return false; }
    finally { setWriteBusy(false); }
  };

  useImperativeHandle(ref, () => ({
    async prepareForAi() {
      if (busy) return false;
      if (!dirty) return true;
      return save();
    },
    checkAiDraft(baseVersion) {
      if (busy) return '正文正在保存，请稍后再确认。';
      if (dirty) return '正文有未保存修改，请先放弃本次建议、保存原文，再重新生成，避免覆盖你的内容。';
      if (chapter?.version !== baseVersion) return '正文版本已变化，请放弃旧草稿并重新生成建议。';
      return undefined;
    },
    setAiWriting,
    canLeave() {
      if (busy) { toast.warning('正文正在写入，请完成后再切换。'); return false; }
      return !(dirty || reviewOpen) || window.confirm(reviewOpen ? '本章还有待审阅的修改，离开会清除本地选择和手工编辑。AI 原始草稿可在返回后继续审阅。是否离开？' : '正文有未保存修改，确定放弃这些修改并离开吗？');
    },
  }));

  const restore = async (revision: NovelRevision) => {
    if (!chapter || busy || chapter.version === undefined) return;
    if (!window.confirm(`确定恢复版本 ${revision.revision_number} 吗？${dirty ? '当前未保存修改将被替换。' : ''}`)) return;
    setWriteBusy(true);
    try {
      const result = await restoreNovelRevision(chapter.id, revision.id, chapter.version);
      setDocument(current => ({ ...current, text: revision.content, baseText: revision.content, baseVersion: result.chapter_version }));
      await onChapterRefresh(chapter.id);
      toast.success(`已恢复版本 ${revision.revision_number}，并创建新版本`);
    } catch (err) { toast.error(errorMessage(err) || '恢复失败。'); }
    finally { setWriteBusy(false); }
  };

  return <section className={`novel-editor ${reviewOpen ? 'is-reviewing' : ''}`} aria-label="小说原文编辑器">
    <header className="novel-editor-toolbar">
      <h2 className="flex items-center gap-2 text-sm font-semibold text-text-primary"><FileText size={16} aria-hidden="true" />小说原文</h2>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" onClick={() => setShowHistory(value => !value)} aria-expanded={showHistory} aria-controls="novel-revision-history" className="workspace-secondary-button"><History size={14} aria-hidden="true" />版本历史</button>
        <button type="button" onClick={() => void save()} disabled={!chapter || busy || reviewOpen || !dirty || !document.text.trim()} className="workspace-primary-button"><Save size={14} aria-hidden="true" />{writeBusy ? '保存中…' : '保存原文'}</button>
      </div>
    </header>
    {showHistory && <div id="novel-revision-history" className="novel-history" aria-label="正文历史版本">
      {historyError ? <p role="alert">{historyError}</p> : revisions.length === 0 ? <p className="text-text-muted">暂无历史版本。</p> : revisions.map(revision => <div key={revision.id} className="novel-history-row"><span>v{revision.revision_number} · {sourceLabels[revision.source]}</span><time className="text-text-muted">{new Date(revision.created_at).toLocaleString()}</time><button type="button" onClick={() => void restore(revision)} disabled={busy} className="text-accent disabled:opacity-40">恢复</button></div>)}
    </div>}
    {conflict && <div className="novel-editor-warning" role="alert">正文已有新版本，本地草稿已保留。<button type="button" className="ml-2 underline" disabled={busy} onClick={() => { if (window.confirm('载入最新正文会替换当前未保存草稿，确定继续吗？')) { setDocument({ text: content, baseText: content, baseVersion: chapter?.version, seenText: content, seenVersion: chapter?.version }); } }}>载入最新正文</button></div>}
    <div className="novel-editor-page" hidden={reviewOpen}>
      <div className="novel-editor-page-heading">第 {chapter?.chapter_number ?? '–'} 章</div>
      <textarea aria-label="小说原文" value={document.text} disabled={!chapter || busy} onChange={event => { setDocument(current => ({ ...current, text: event.target.value })); }} onKeyDown={event => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); if (dirty) void save(); } }} placeholder="在这里开始你的故事，或粘贴已有正文。需要协助时，打开右侧 AI 助手。" className="novel-editor-textarea" />
    </div>
    <footer className="novel-editor-status" hidden={reviewOpen}><span>{document.text.length.toLocaleString()} / {MAX_CHARS.toLocaleString()} 字</span><span aria-live="polite">{aiWriting ? 'AI 修改正在写入…' : dirty ? '未保存' : `已保存${document.baseVersion === undefined ? '' : ` · 版本 ${document.baseVersion}`}`}</span></footer>
  </section>;
});

export default NovelEditor;
