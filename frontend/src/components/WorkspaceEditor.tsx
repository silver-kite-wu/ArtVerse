import { BookOpenCheck, ChevronLeft, ChevronRight, Image, MessageSquare, Plus, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import ChatPanel from './ChatPanel';
import MangaPanel from './MangaPanel';
import KnowledgeBasePage from './KnowledgeBasePage';
import { createNextChapter, deleteChapter, getChapter, listChapters, type Chapter } from '../api';

type MobileTab = 'chat' | 'manga';

interface Props {
  storyId: number;
  onBack: () => void;
  onLoadError?: (message: string) => void;
}

const LS_STORY_ID = 'lorevista.currentStoryId';
const LS_CHAPTER_ID = 'lorevista.currentChapterId';
const LS_CHAPTER_IDX = 'lorevista.currentChapterIdx';

export default function WorkspaceEditor({ storyId, onBack, onLoadError }: Props) {
  const [currentIdx, setCurrentIdx] = useState(0);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [currentChapter, setCurrentChapter] = useState<Chapter | null>(null);
  const [creatingChapter, setCreatingChapter] = useState(false);
  const [mobileTab, setMobileTab] = useState<MobileTab>('chat');
  const [loading, setLoading] = useState(true);
  const [showKnowledge, setShowKnowledge] = useState(false);

  const loadChapters = useCallback(async () => {
    setLoading(true);
    try {
      const chs = await listChapters(storyId);
      setChapters(chs);
      const sameStory = Number(localStorage.getItem(LS_STORY_ID)) === storyId;
      const savedChapterId = sameStory ? Number(localStorage.getItem(LS_CHAPTER_ID)) : 0;
      const savedChapterIdx = chs.findIndex((chapter) => chapter.id === savedChapterId);
      const idx = savedChapterIdx >= 0 ? savedChapterIdx : 0;
      localStorage.setItem(LS_STORY_ID, String(storyId));
      setCurrentIdx(idx);
      if (chs.length > 0) {
        const ch = await getChapter(chs[idx].id);
        setCurrentChapter(ch);
        localStorage.setItem(LS_CHAPTER_ID, String(chs[idx].id));
        localStorage.setItem(LS_CHAPTER_IDX, String(idx));
      } else {
        setCurrentChapter(null);
        localStorage.removeItem(LS_CHAPTER_ID);
        localStorage.removeItem(LS_CHAPTER_IDX);
      }
    } catch (error) {
      setChapters([]);
      setCurrentChapter(null);
      const detail = error instanceof Error ? error.message : '';
      onLoadError?.(detail
        ? `无法打开该故事：${detail}`
        : '故事不存在或当前账号无权访问，已返回故事工作区。');
    } finally {
      setLoading(false);
    }
  }, [onLoadError, storyId]);

  useEffect(() => {
    void loadChapters();
    setMobileTab('chat');
  }, [loadChapters]);

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
    if (idx < 0 || idx >= chapters.length) return;
    setCurrentIdx(idx);
    const ch = await getChapter(chapters[idx].id);
    setCurrentChapter(ch);
    localStorage.setItem(LS_CHAPTER_ID, String(chapters[idx].id));
    localStorage.setItem(LS_CHAPTER_IDX, String(idx));
  };

  const handlePrevChapter = () => {
    if (currentIdx > 0) setChapterByIndex(currentIdx - 1);
  };

  const handleNextChapter = async () => {
    if (currentIdx < chapters.length - 1) {
      setChapterByIndex(currentIdx + 1);
      return;
    }
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
    } catch (e: any) {
      alert('操作失败：' + e.message);
    } finally {
      setCreatingChapter(false);
    }
  };

  const handleDelete = async () => {
    if (!currentChapter || chapters.length <= 1) return;
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
    } catch (e: any) {
      alert('操作失败：' + e.message);
    }
  };

  if (loading) {
    return <div className="page-atmosphere flex flex-1 items-center justify-center bg-bg-base"><div className="h-8 w-8 animate-spin rounded-full border-2 border-border border-t-accent" /></div>;
  }

  if (showKnowledge) {
    return <KnowledgeBasePage storyId={storyId} chapterNumber={currentChapter?.chapter_number} onBack={() => setShowKnowledge(false)} />;
  }

  return (
    <div className="page-atmosphere flex min-h-0 flex-1 flex-col bg-bg-base">
      <div className="relative z-10 flex h-14 shrink-0 items-center justify-between border-b border-border glass-panel px-3 md:px-4">
        <button onClick={onBack} className="flex items-center gap-1.5 text-sm text-text-secondary transition-colors hover:text-accent">
          <ChevronLeft size={16} />
          返回故事列表
        </button>
        <div className="flex items-center gap-2">
          <button onClick={() => setShowKnowledge(true)} className="flex h-8 w-8 items-center justify-center rounded-lg border border-border bg-bg-surface text-text-secondary transition-colors hover:border-accent/30 hover:text-accent" title="知识库"><BookOpenCheck size={15} /></button>
          {chapters.length > 0 && (
            <select
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

      <div className="flex border-b border-border glass-panel md:hidden">
        <button onClick={() => setMobileTab('chat')} className={'flex flex-1 items-center justify-center gap-1.5 py-2.5 text-xs font-medium transition-all ' + (mobileTab === 'chat' ? 'border-b-2 border-accent text-accent' : 'text-text-secondary hover:text-text-primary')}>
          <MessageSquare size={14} />
          对话创作
        </button>
        <button onClick={() => setMobileTab('manga')} className={'flex flex-1 items-center justify-center gap-1.5 py-2.5 text-xs font-medium transition-all ' + (mobileTab === 'manga' ? 'border-b-2 border-accent text-accent' : 'text-text-secondary hover:text-text-primary')}>
          <Image size={14} />
          漫画分镜
        </button>
      </div>

      <div className="shrink-0 overflow-x-auto border-b border-border bg-bg-surface/60 px-2 py-2 backdrop-blur-sm md:hidden">
        <div className="flex gap-1.5">
          {chapters.map((ch: Chapter, idx: number) => (
            <button key={ch.id} onClick={() => setChapterByIndex(idx)} style={{ animationDelay: `${idx * 40}ms` }} className={'shrink-0 animate-fade-in rounded-full border px-3 py-1.5 text-xs transition-all duration-200 ' + (ch.id === currentChapter?.id ? 'border-accent bg-accent-muted/50 text-accent font-medium shadow-sm' : 'border-border bg-bg-base text-text-secondary hover:border-accent/30 hover:text-text-primary')}>
              第 {ch.chapter_number} 章
            </button>
          ))}
        </div>
      </div>

      <main className="min-h-0 flex-1 md:flex">
        <div className={'h-full md:w-1/2 md:border-r md:border-border ' + (mobileTab === 'chat' ? '' : 'hidden md:block')}>
          <ChatPanel
            chapter={currentChapter}
            onMessageSent={refreshCurrentChapter}
            onChapterRefresh={handleChapterRefresh}
          />
        </div>
        <div className={'h-full md:w-1/2 ' + (mobileTab === 'manga' ? '' : 'hidden md:block')}>
          <MangaPanel chapter={currentChapter} onChapterRefresh={handleChapterRefresh} />
        </div>
      </main>

      <footer className="relative z-10 flex h-14 shrink-0 items-center justify-center gap-2 border-t border-border glass-panel px-2 md:gap-4">
        <button onClick={handlePrevChapter} disabled={currentIdx === 0} className="fanqie-reader-nav disabled:cursor-not-allowed disabled:opacity-30">
          <ChevronLeft size={16} />
          <span className="hidden md:inline">上一章</span>
        </button>
        <button onClick={handleDelete} disabled={!currentChapter || chapters.length <= 1} className="flex items-center gap-1.5 rounded-lg border border-accent/20 bg-accent-soft px-3 py-2 text-sm font-medium text-accent transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-30 hover:bg-accent-muted/40" aria-label="Delete chapter">
          <Trash2 size={14} />
        </button>
        <div className="flex items-center gap-1.5">
          {chapters.map((ch: Chapter, i: number) => (
            <button key={ch.id} onClick={() => setChapterByIndex(i)} className={'rounded-full transition-all duration-200 ' + (i === currentIdx ? 'h-2.5 w-2.5 bg-accent scale-110 shadow-[0_0_8px_var(--color-accent-muted)]' : 'h-2 w-2 bg-text-muted/40 hover:bg-accent/50 hover:scale-110')} aria-label={'切换到第 ' + ch.chapter_number + ' 章'} />
          ))}
        </div>
        <button onClick={handleNextChapter} disabled={creatingChapter} className="fanqie-cta-primary !px-3 !py-2 !text-sm md:!px-5 disabled:cursor-not-allowed disabled:opacity-40">
          {currentIdx === chapters.length - 1 ? (<><Plus size={16} />{creatingChapter ? '创建中…' : '新建'}</>) : (<><span className="hidden md:inline">下一章</span><ChevronRight size={16} /></>)}
        </button>
      </footer>
    </div>
  );
}
