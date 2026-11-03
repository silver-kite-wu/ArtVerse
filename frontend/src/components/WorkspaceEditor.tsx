import { BookOpenCheck, ChevronLeft, ChevronRight, Plus, Trash2 } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import WorkspaceToolWindows from './WorkspaceToolWindows';
import type { NovelEditorHandle } from './NovelEditor';
import KnowledgeBasePage from './KnowledgeBasePage';
import { createNextChapter, deleteChapter, errorMessage, getChapter, listChapters, type Chapter } from '../api';
import { useToast } from '../ui/toast/useToast';

interface Props {
  storyId: number;
  onBack: () => void;
  onLoadError?: (message: string) => void;
}

const LS_STORY_ID = 'lorevista.currentStoryId';
const LS_CHAPTER_ID = 'lorevista.currentChapterId';
const LS_CHAPTER_IDX = 'lorevista.currentChapterIdx';

export default function WorkspaceEditor({ storyId, onBack, onLoadError }: Props) {
  const toast = useToast();
  const [currentIdx, setCurrentIdx] = useState(0);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [currentChapter, setCurrentChapter] = useState<Chapter | null>(null);
  const [creatingChapter, setCreatingChapter] = useState(false);
  const novelEditorRef = useRef<NovelEditorHandle>(null);
  const [loading, setLoading] = useState(true);
  const [showKnowledge, setShowKnowledge] = useState(false);

  useEffect(() => {
    let active = true;
    listChapters(storyId)
      .then(async (chs) => {
        if (!active) return;
        setChapters(chs);
        const sameStory = Number(localStorage.getItem(LS_STORY_ID)) === storyId;
        const savedChapterId = sameStory ? Number(localStorage.getItem(LS_CHAPTER_ID)) : 0;
        const savedChapterIdx = chs.findIndex((chapter) => chapter.id === savedChapterId);
        const idx = savedChapterIdx >= 0 ? savedChapterIdx : 0;
        localStorage.setItem(LS_STORY_ID, String(storyId));
        setCurrentIdx(idx);
        if (chs.length > 0) {
          const chapter = await getChapter(chs[idx].id);
          if (!active) return;
          setCurrentChapter(chapter);
          localStorage.setItem(LS_CHAPTER_ID, String(chapter.id));
          localStorage.setItem(LS_CHAPTER_IDX, String(idx));
        } else {
          setCurrentChapter(null);
          localStorage.removeItem(LS_CHAPTER_ID);
          localStorage.removeItem(LS_CHAPTER_IDX);
        }
      })
      .catch((error: unknown) => {
        if (!active) return;
        setChapters([]);
        setCurrentChapter(null);
        const detail = error instanceof Error ? error.message : '';
        onLoadError?.(detail
          ? `无法打开该故事：${detail}`
          : '故事不存在或当前账号无权访问，已返回故事工作区。');
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [onLoadError, storyId]);

  const refreshCurrentChapter = async () => {
    if (!currentChapter) return;
    const chapterId = currentChapter.id;
    const chapter = await getChapter(chapterId);
    setCurrentChapter((current) => current?.id === chapterId ? chapter : current);
  };

  const handleChapterRefresh = async (chapterId: number) => {
    try {
      const ch = await getChapter(chapterId);
      setCurrentChapter((current) => current?.id === chapterId ? ch : current);
      const chs = await listChapters(storyId);
      setChapters(chs);
    } catch {
      return;
    }
  };

  const setChapterByIndex = async (idx: number) => {
    if (idx < 0 || idx >= chapters.length) return false;
    if (idx === currentIdx) return true;
    if (novelEditorRef.current && !novelEditorRef.current.canLeave()) return false;
    setCurrentIdx(idx);
    const ch = await getChapter(chapters[idx].id);
    setCurrentChapter(ch);
    localStorage.setItem(LS_CHAPTER_ID, String(chapters[idx].id));
    localStorage.setItem(LS_CHAPTER_IDX, String(idx));
    return true;
  };

  const handlePrevChapter = () => {
    if (currentIdx > 0) setChapterByIndex(currentIdx - 1);
  };

  const handleNextChapter = async () => {
    if (currentIdx < chapters.length - 1) {
      setChapterByIndex(currentIdx + 1);
      return;
    }
    if (novelEditorRef.current && !novelEditorRef.current.canLeave()) return;
    setCreatingChapter(true);
    try {
      await createNextChapter(storyId);
      const chs = await listChapters(storyId);
      setChapters(chs);
      const idx = chs.length - 1;
      setCurrentIdx(idx);
      setCurrentChapter(await getChapter(chs[idx].id));
      localStorage.setItem(LS_CHAPTER_ID, String(chs[idx].id));
      localStorage.setItem(LS_CHAPTER_IDX, String(idx));
      toast.success('新章节已创建');
    } catch (e) {
      toast.error('操作失败：' + (errorMessage(e) || '未知错误'));
    } finally {
      setCreatingChapter(false);
    }
  };

  const handleDelete = async () => {
    if (!currentChapter || chapters.length <= 1) return;
    if (novelEditorRef.current && !novelEditorRef.current.canLeave()) return;
    if (!confirm('确定删除这一章吗？')) return;
    try {
      await deleteChapter(currentChapter.id);
      const chs = await listChapters(storyId);
      setChapters(chs);
      const idx = Math.min(currentIdx, chs.length - 1);
      setCurrentIdx(idx);
      if (chs.length > 0) {
        setCurrentChapter(await getChapter(chs[idx].id));
        localStorage.setItem(LS_CHAPTER_ID, String(chs[idx].id));
        localStorage.setItem(LS_CHAPTER_IDX, String(idx));
      } else {
        setCurrentChapter(null);
        localStorage.removeItem(LS_CHAPTER_ID);
        localStorage.removeItem(LS_CHAPTER_IDX);
      }
      toast.success('章节已删除');
    } catch (e) {
      toast.error('操作失败：' + (errorMessage(e) || '未知错误'));
    }
  };

  if (loading) {
    return <div className="flex flex-1 items-center justify-center bg-bg-base"><div className="h-8 w-8 animate-spin rounded-full border-2 border-border border-t-accent" /></div>;
  }

  return <>
    {showKnowledge && <KnowledgeBasePage storyId={storyId} chapterNumber={currentChapter?.chapter_number} onBack={() => setShowKnowledge(false)} onSourceChapter={id => {const idx = chapters.findIndex(c => c.id === id);if (idx >= 0) void setChapterByIndex(idx).then(opened => {if (opened) setShowKnowledge(false);});}} />}
    <div hidden={showKnowledge} className="story-workspace flex min-h-0 min-w-0 flex-1 flex-col bg-bg-base">
      <div className="relative z-10 flex h-14 shrink-0 items-center justify-between border-b border-border glass-panel px-3 md:px-4">
        <button onClick={() => { if (!novelEditorRef.current || novelEditorRef.current.canLeave()) onBack(); }} className="flex items-center gap-1.5 text-sm text-text-secondary transition-colors hover:text-accent">
          <ChevronLeft size={16} />
          返回故事列表
        </button>
        <div className="flex items-center gap-2">
          <button onClick={() => setShowKnowledge(true)} className="flex h-8 w-8 items-center justify-center rounded-lg border border-border bg-bg-surface text-text-secondary transition-colors hover:border-accent/30 hover:text-accent" title="知识库" aria-label="打开知识库"><BookOpenCheck size={15} /></button>
          {chapters.length > 0 && (
            <select
              aria-label="选择章节"
              value={currentIdx}
              onChange={(e) => setChapterByIndex(Number(e.target.value))}
              className="rounded-lg border border-border bg-bg-surface px-2.5 py-1.5 text-xs text-text-primary transition-colors focus:border-accent focus:outline-none"
            >
              {chapters.map((ch, i) => (
                <option key={ch.id} value={i}>第 {ch.chapter_number} 章</option>
              ))}
            </select>
          )}
        </div>
      </div>
      <div className="brush-divider relative z-10 shrink-0"></div>

      <WorkspaceToolWindows chapter={currentChapter} editorRef={novelEditorRef} onMessageSent={refreshCurrentChapter} onChapterRefresh={handleChapterRefresh} />

      <footer className="relative z-10 flex h-14 shrink-0 items-center justify-center gap-2 border-t border-border glass-panel px-2 md:gap-4">
        <button onClick={handlePrevChapter} disabled={currentIdx === 0} className="fanqie-reader-nav disabled:cursor-not-allowed disabled:opacity-30">
          <ChevronLeft size={16} />
          <span className="hidden md:inline">上一章</span>
        </button>
        <button onClick={handleDelete} disabled={!currentChapter || chapters.length <= 1} className="flex items-center gap-1.5 rounded-lg border border-accent/20 bg-accent-soft px-3 py-2 text-sm font-medium text-accent transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-30 hover:bg-accent-muted/40" aria-label="删除当前章节">
          <Trash2 size={14} />
        </button>
        <div className="flex max-w-[30%] items-center gap-1.5 overflow-x-auto py-2">
          {chapters.map((ch: Chapter, i: number) => (
            <button key={ch.id} onClick={() => setChapterByIndex(i)} className={'shrink-0 rounded-full transition-all duration-200 ' + (i === currentIdx ? 'h-2.5 w-2.5 bg-accent scale-110 shadow-[0_0_8px_var(--color-accent-muted)]' : 'h-2 w-2 bg-text-muted/40 hover:bg-accent/50 hover:scale-110')} aria-label={'切换到第 ' + ch.chapter_number + ' 章'} />
          ))}
        </div>
        <button onClick={handleNextChapter} disabled={creatingChapter} className="fanqie-cta-primary !px-3 !py-2 !text-sm md:!px-5 disabled:cursor-not-allowed disabled:opacity-40">
          {currentIdx >= chapters.length - 1 ? (<><Plus size={16} />{creatingChapter ? '创建中…' : '新建'}</>) : (<><span className="hidden md:inline">下一章</span><ChevronRight size={16} /></>)}
        </button>
      </footer>
    </div>
  </>;
}
