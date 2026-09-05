import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDown, ArrowUp, ArrowUpDown, BookOpenText, CalendarDays, CheckCircle2,
  ChevronLeft, ChevronRight, Eye, FileText, Globe, Image as ImageIcon, Layers,
  Loader2, RefreshCw, Search, Send, SquarePen, X,
} from 'lucide-react';
import {
  getChapter, listMyWorks, mangaImageUrl, publishStory, refImageUrl, updateChapterOrder,
  type Chapter, type MangaImage, type MyWork, type MyWorkChapter,
} from '../api';

type Format = 'novel' | 'manga';
type PageView = 'list' | 'detail' | 'reader';
type StatusFilter = 'all' | 'published' | 'draft';
type SortOrder = 'newest' | 'oldest';

const formatCopy = {
  novel: {
    label: '小说管理',
    subtitle: '管理小说正文、章节顺序与独立发布状态',
    chapterUnit: '章',
    contentMetric: '正文总字数',
    publishedMetric: '上线章节',
    emptyContent: '该章节还没有小说正文',
  },
  manga: {
    label: '漫画管理',
    subtitle: '管理漫画页面、章节顺序与独立发布状态',
    chapterUnit: '话',
    contentMetric: '漫画总页数',
    publishedMetric: '上线章节',
    emptyContent: '该章节还没有生成漫画',
  },
} as const;

const isWorkPublished = (work: MyWork, format: Format) =>
  format === 'novel' ? work.novel_is_published : work.manga_is_published;

const isChapterPublished = (chapter: MyWorkChapter, format: Format) =>
  format === 'novel' ? chapter.novel_is_published : chapter.manga_is_published;

const chapterContentCount = (chapter: MyWorkChapter, format: Format) =>
  format === 'novel' ? chapter.novel_char_count : chapter.manga_image_count;

const chapterTitle = (chapter: MyWorkChapter, format: Format) =>
  chapter.display_title || `第 ${chapter.chapter_number} ${formatCopy[format].chapterUnit}`;

const formatDate = (date: string | null) => date ? new Date(date).toLocaleDateString('zh-CN') : '未记录';

export default function MyWorksPage() {
  const [format, setFormat] = useState<Format>('novel');
  const [view, setView] = useState<PageView>('list');
  const [works, setWorks] = useState<MyWork[]>([]);
  const [selectedWork, setSelectedWork] = useState<MyWork | null>(null);
  const [editChapters, setEditChapters] = useState<MyWorkChapter[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [sortOrder, setSortOrder] = useState<SortOrder>('newest');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [readerChapterId, setReaderChapterId] = useState<number | null>(null);
  const [readerChapter, setReaderChapter] = useState<Chapter | null>(null);
  const [readerImages, setReaderImages] = useState<MangaImage[]>([]);
  const [readerLoading, setReaderLoading] = useState(false);
  const pageScrollRef = useRef<HTMLDivElement>(null);

  const loadWorks = useCallback(async () => {
    setLoadError('');
    try {
      setWorks(await listMyWorks());
    } catch (error) {
      console.error(error);
      setLoadError('作品加载失败，请检查网络后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadWorks(); }, [loadWorks]);

  const switchFormat = (next: Format) => {
    setFormat(next);
    setView('list');
    setSelectedWork(null);
    setStatusFilter('all');
    setReaderChapter(null);
    setReaderImages([]);
  };

  const stats = useMemo(() => {
    const publishedWorks = works.filter((work) => isWorkPublished(work, format)).length;
    const totalChapters = works.reduce((sum, work) => sum + work.chapters.length, 0);
    const publishedChapters = works.reduce(
      (sum, work) => sum + work.chapters.filter((chapter) => isChapterPublished(chapter, format)).length, 0,
    );
    const contentCount = works.reduce(
      (sum, work) => sum + work.chapters.reduce((chapterSum, chapter) => chapterSum + chapterContentCount(chapter, format), 0), 0,
    );
    return { publishedWorks, totalChapters, publishedChapters, contentCount };
  }, [format, works]);

  const filteredWorks = useMemo(() => works
    .filter((work) => !searchQuery || work.title.toLowerCase().includes(searchQuery.toLowerCase()))
    .filter((work) => statusFilter === 'all'
      || (statusFilter === 'published' && isWorkPublished(work, format))
      || (statusFilter === 'draft' && !isWorkPublished(work, format)))
    .sort((a, b) => sortOrder === 'newest'
      ? (b.created_at || '').localeCompare(a.created_at || '')
      : (a.created_at || '').localeCompare(b.created_at || '')),
  [format, searchQuery, sortOrder, statusFilter, works]);

  const openDetail = (work: MyWork) => {
    setSelectedWork(work);
    setEditChapters(work.chapters
      .slice()
      .sort((a, b) => a.display_order - b.display_order || a.chapter_number - b.chapter_number)
      .map((chapter) => ({ ...chapter, is_published: isChapterPublished(chapter, format) })));
    setView('detail');
    requestAnimationFrame(() => pageScrollRef.current?.scrollTo({ top: 0 }));
  };

  const backToList = async () => {
    setView('list');
    setSelectedWork(null);
    await loadWorks();
  };

  const moveChapter = (index: number, direction: -1 | 1) => {
    const target = index + direction;
    if (target < 0 || target >= editChapters.length) return;
    setEditChapters((current) => {
      const next = [...current];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  };

  const handleSave = async () => {
    if (!selectedWork) return;
    setSaving(true);
    try {
      const publishedIds = editChapters.filter((chapter) => chapter.is_published).map((chapter) => chapter.id);
      await publishStory(selectedWork.id, format, publishedIds.length > 0, publishedIds);
      await updateChapterOrder(selectedWork.id, editChapters.map((chapter, index) => ({
        chapter_id: chapter.id,
        display_order: index,
        display_title: chapter.display_title || undefined,
      })));
      const updated = await listMyWorks();
      setWorks(updated);
      const work = updated.find((item) => item.id === selectedWork.id);
      if (work) openDetail(work);
    } catch (error) {
      alert(`保存失败：${error instanceof Error ? error.message : '未知错误'}`);
    } finally {
      setSaving(false);
    }
  };

  const openReader = async (chapterId: number) => {
    setReaderChapterId(chapterId);
    setReaderChapter(null);
    setReaderImages([]);
    setReaderLoading(true);
    setView('reader');
    try {
      const chapter = await getChapter(chapterId);
      setReaderChapter(chapter);
      setReaderImages((chapter.images || []).slice().sort((a, b) => a.image_number - b.image_number));
    } catch (error) {
      console.error(error);
    } finally {
      setReaderLoading(false);
    }
  };

  const navigateReader = (direction: -1 | 1) => {
    if (!selectedWork) return;
    const index = selectedWork.chapters.findIndex((chapter) => chapter.id === readerChapterId);
    const next = selectedWork.chapters[index + direction];
    if (next) void openReader(next.id);
  };

  if (loading) return <div className="page-atmosphere flex flex-1 flex-col bg-bg-base">
    <div className="mx-auto w-full max-w-7xl flex-1 px-4 py-5 md:px-8 md:py-7">
      <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">
        {Array.from({ length: 4 }).map((_, index) => (
          <div key={index} className="grid min-w-0 grid-cols-[82px_1fr] gap-3 rounded-xl border border-border bg-bg-surface p-3 sm:grid-cols-[96px_1fr] sm:gap-4">
            <div className="aspect-[3/4] rounded-lg bg-bg-raised shimmer-bg" />
            <div className="flex min-w-0 flex-col gap-2.5 py-0.5">
              <div className="h-5 w-1/2 rounded-lg  bg-bg-raised shimmer-bg" />
              <div className="h-3 w-full rounded-lg  bg-bg-raised shimmer-bg" />
              <div className="h-3 w-2/3 rounded-lg  bg-bg-raised shimmer-bg" />
              <div className="mt-auto h-3 w-1/3 rounded-lg  bg-bg-raised shimmer-bg" />
            </div>
          </div>
        ))}
      </div>
    </div>
  </div>;

  if (loadError) {
    return <div className="page-atmosphere flex flex-1 items-center justify-center bg-bg-base px-4"><div className="text-center">
      <RefreshCw size={38} className="mx-auto animate-float text-text-muted" strokeWidth={1.35} />
      <p className="mt-3 text-sm font-medium text-text-primary">{loadError}</p>
      <button type="button" onClick={() => void loadWorks()} className="fanqie-cta-secondary mt-4 !gap-1.5"><RefreshCw size={14} />重新加载</button>
    </div></div>;
  }

  if (view === 'list') {
    const draftCount = works.length - stats.publishedWorks;
    return <div className="page-atmosphere flex min-h-0 flex-1 flex-col bg-bg-base">
      <div className="relative z-10 shrink-0 border-b border-border glass-panel">
        <div className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-7">
          <div className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <div className="mb-3 inline-flex rounded-lg border border-border bg-bg-surface/60 p-1 backdrop-blur-sm" aria-label="作品形态">
                {(['novel', 'manga'] as Format[]).map((item) => <button key={item} type="button" onClick={() => switchFormat(item)} className={`flex h-9 items-center gap-2 rounded-lg px-4 text-sm font-semibold transition-all ${format === item ? 'bg-accent-muted text-accent ring-1 ring-accent/20' : 'text-text-secondary hover:text-text-primary hover:bg-accent-soft'}`}>
                  {item === 'novel' ? <BookOpenText size={16} /> : <ImageIcon size={16} />}{formatCopy[item].label}
                </button>)}
              </div>
              <div className="flex items-center gap-2.5">
                <h1 className="font-display text-2xl font-bold text-text-primary md:text-3xl">{formatCopy[format].label}</h1>
                <span className="seal-stamp h-5! min-w-5! text-[0.5rem]!">{format === 'novel' ? '文' : '画'}</span>
              </div>
              <p className="mt-1.5 text-sm text-text-secondary">{formatCopy[format].subtitle}</p>
            </div>
            <div className="flex w-full items-center gap-2 lg:w-auto">
              <label className="relative min-w-0 flex-1 lg:w-64">
                <span className="sr-only">搜索作品名称</span>
                <Search size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-muted" />
                <input value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} placeholder="搜索作品名称" className="input-field h-10 w-full pl-10 pr-9 text-sm" />
                {searchQuery && (
                  <button type="button" onClick={() => setSearchQuery('')} className="absolute right-2 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-full text-text-muted transition-colors hover:bg-accent-soft hover:text-text-primary focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent" aria-label="清除搜索">
                    <X size={14} />
                  </button>
                )}
              </label>
              <label className="relative w-32 shrink-0 lg:w-36">
                <span className="sr-only">排序方式</span>
                <select value={sortOrder} onChange={(event) => setSortOrder(event.target.value as SortOrder)} className="h-10 w-full appearance-none rounded-lg border border-border bg-bg-surface py-2 pl-3 pr-9 text-sm text-text-secondary transition-colors focus:border-accent focus:outline-none"><option value="newest">最近创建</option><option value="oldest">最早创建</option></select>
                <ArrowUpDown size={13} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-text-muted" />
              </label>
            </div>
          </div>
          <div className="brush-divider mt-5"></div>
          <div className="mt-4 grid grid-cols-2 sm:grid-cols-4">
            {[
              { label: '全部作品', value: works.length, icon: <Layers size={15} className="text-accent" /> },
              { label: '已发布作品', value: stats.publishedWorks, icon: <Globe size={15} className="text-success" /> },
              { label: formatCopy[format].contentMetric, value: stats.contentCount.toLocaleString('zh-CN'), icon: format === 'novel' ? <FileText size={15} className="text-accent-secondary" /> : <ImageIcon size={15} className="text-accent-secondary" /> },
              { label: formatCopy[format].publishedMetric, value: stats.publishedChapters, icon: <CheckCircle2 size={15} className="text-accent-tertiary" /> },
            ].map((stat, index) => <div key={stat.label} className={`flex items-center gap-3 py-3 ${index % 2 ? 'border-l border-border' : ''} ${index > 1 ? 'border-t border-border sm:border-t-0' : ''} sm:border-l sm:px-5 sm:first:border-l-0 sm:first:pl-0`}><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-border bg-bg-surface/60 backdrop-blur-sm transition-colors hover:border-accent/30">{stat.icon}</span><div><div className="text-xl font-semibold text-text-primary">{stat.value}</div><div className="text-[0.6875rem] text-text-muted">{stat.label}</div></div></div>)}
          </div>
        </div>
      </div>
      <div ref={pageScrollRef} className="flex-1 overflow-y-auto">
        <main className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-7">
          <div className="mb-5 flex items-center justify-between gap-3"><div className="flex gap-1 rounded-lg border border-border bg-bg-surface/60 p-1 backdrop-blur-sm">
            {([['all', '全部作品', works.length], ['published', '已发布', stats.publishedWorks], ['draft', '草稿箱', draftCount]] as const).map(([key, label, count]) => <button key={key} type="button" onClick={() => setStatusFilter(key)} className={`h-8 rounded-lg px-3 text-sm font-medium transition-all ${statusFilter === key ? 'bg-accent-muted text-accent shadow-sm' : 'text-text-secondary hover:text-text-primary'}`}>{label} <span className="text-[0.6875rem] text-text-muted">{count}</span></button>)}
          </div><span className="hidden text-xs text-text-muted sm:inline">待发布作品 {draftCount} 部</span></div>
          {filteredWorks.length === 0 ? <div className="flex min-h-64 items-center justify-center border-y border-border text-center text-text-muted"><div><ImageIcon size={42} className="mx-auto animate-float" strokeWidth={1.25} /><p className="mt-3 text-sm">{searchQuery ? '没有匹配的作品' : '这里还没有作品'}</p></div></div>
            : <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">{filteredWorks.map((work, workIdx) => {
              const published = isWorkPublished(work, format);
              const contentCount = work.chapters.reduce((sum, chapter) => sum + chapterContentCount(chapter, format), 0);
              const publishedCount = work.chapters.filter((chapter) => isChapterPublished(chapter, format)).length;
              const cover = work.cover_image ? refImageUrl(work.cover_image) : null;
              return <button key={work.id} type="button" onClick={() => openDetail(work)} style={{ animationDelay: `${workIdx * 60}ms` }} className="bento-card group grid min-w-0 animate-fade-in-up grid-cols-[82px_1fr] gap-3 p-3 text-left sm:grid-cols-[96px_1fr] sm:gap-4">
                <div className="aspect-[3/4] overflow-hidden rounded-lg border border-border bg-bg-surface">{cover ? <img src={cover} alt={work.title} className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105" loading="lazy" /> : <div className="flex h-full w-full items-center justify-center"><ImageIcon size={30} className="text-text-muted" /></div>}</div>
                <div className="flex min-w-0 flex-col py-0.5"><div className="flex items-start justify-between gap-2"><div className="min-w-0"><h2 className="truncate text-base font-semibold text-text-primary transition-colors group-hover:text-accent">{work.title}</h2><p className="mt-1 line-clamp-2 text-xs leading-[18px] text-text-secondary">{work.description || '暂无简介'}</p></div><span className={`shrink-0 rounded-lg px-2 py-1 text-[0.6875rem] font-medium ${published ? 'bg-success/10 text-success' : 'bg-bg-surface text-text-secondary'}`}>{published ? '已发布' : '草稿'}</span></div>
                  <div className="mt-auto flex flex-wrap items-center gap-x-4 gap-y-1 pt-3 text-xs text-text-muted"><span>{work.chapters.length} {formatCopy[format].chapterUnit}</span><span>{format === 'novel' ? `${contentCount.toLocaleString('zh-CN')} 字` : `${contentCount} 页`}</span><span>上线 {publishedCount}</span><span className="ml-auto">{formatDate(work.created_at)}</span></div>
                </div>
              </button>;
            })}</div>}
        </main>
      </div>
    </div>;
  }

  if (view === 'detail' && selectedWork) {
    const published = isWorkPublished(selectedWork, format);
    const cover = selectedWork.cover_image ? refImageUrl(selectedWork.cover_image) : null;
    const publishedCount = editChapters.filter((chapter) => chapter.is_published).length;
    return <div className="page-atmosphere flex min-h-0 flex-1 flex-col bg-bg-base">
      <header className="relative z-10 flex h-16 shrink-0 items-center justify-between border-b border-border glass-panel px-4 md:px-8"><button type="button" onClick={() => void backToList()} className="flex items-center gap-2 text-sm text-text-secondary transition-colors hover:text-accent"><ChevronLeft size={18} />返回{formatCopy[format].label}</button><button type="button" onClick={() => void handleSave()} disabled={saving} className="fanqie-cta-primary disabled:opacity-40">{saving ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}保存发布设置</button></header>
      <div ref={pageScrollRef} className="flex-1 overflow-y-auto"><main className="mx-auto w-full max-w-6xl px-4 py-6 md:px-8">
        <section className="animate-fade-in-up grid gap-5 border-b border-border pb-6 sm:grid-cols-[112px_1fr]">
          <div className="ambient-glow aspect-[3/4] overflow-hidden rounded-xl border border-border bg-bg-surface">{cover ? <img src={cover} alt={selectedWork.title} className="h-full w-full object-cover" /> : <div className="flex h-full items-center justify-center"><ImageIcon size={34} className="text-text-muted" /></div>}</div>
          <div className="min-w-0"><div className="flex flex-wrap items-center gap-3 text-xs"><span className={`inline-flex items-center gap-1.5 rounded-lg px-2 py-1 font-medium ${published ? 'bg-success/10 text-success' : 'bg-bg-surface text-text-secondary'}`}><span className={`h-1.5 w-1.5 rounded-full ${published ? 'bg-success' : 'bg-text-muted'}`} />{published ? `${formatCopy[format].label.replace('管理', '')}已公开` : `${formatCopy[format].label.replace('管理', '')}未公开`}</span><span className="flex items-center gap-1.5 text-text-muted"><CalendarDays size={13} />创建于 {formatDate(selectedWork.created_at)}</span></div><div className="mt-3 flex items-center gap-2.5"><h1 className="font-display break-words text-2xl font-bold text-text-primary md:text-3xl">{selectedWork.title}</h1><span className="seal-stamp h-6! min-w-6! text-[0.625rem]!">卷</span></div><p className="mt-2 max-w-2xl text-sm leading-6 text-text-secondary">{selectedWork.description || '暂无简介'}</p></div>
        </section>
        <section className="animate-fade-in-up py-6" style={{ animationDelay: '100ms' }}><div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between"><div><h2 className="font-display text-base font-semibold text-text-primary">章节发布队列</h2><p className="mt-1 text-xs text-text-muted">调整展示标题、顺序和当前形态的读者可见状态</p></div><div className="flex items-center gap-2"><span className="text-xs text-text-muted">已上线 {publishedCount} / {editChapters.length}</span><button type="button" onClick={() => setEditChapters((items) => items.map((item) => ({ ...item, is_published: true })))} className="fanqie-cta-secondary !min-h-8 !px-2.5 !py-1.5 !text-xs">全部上线</button><button type="button" onClick={() => setEditChapters((items) => items.map((item) => ({ ...item, is_published: false })))} className="fanqie-cta-secondary !min-h-8 !px-2.5 !py-1.5 !text-xs">全部转草稿</button></div></div>
          <div className="divide-y divide-border border-y border-border">{editChapters.map((chapter, index) => <div key={chapter.id} className={`group/chapter grid grid-cols-[auto_1fr_auto] items-center gap-3 py-3 transition-colors hover:bg-accent-soft/50 md:grid-cols-[70px_minmax(180px,1fr)_140px_100px_44px] ${chapter.is_published ? 'border-l-2 border-l-accent' : 'border-l-2 border-l-transparent'}`}>
            <div className="flex items-center gap-1"><button type="button" onClick={() => moveChapter(index, -1)} disabled={index === 0} className="flex h-7 w-7 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-accent-soft hover:text-accent disabled:opacity-20 disabled:hover:bg-transparent" title="上移"><ArrowUp size={14} /></button><button type="button" onClick={() => moveChapter(index, 1)} disabled={index === editChapters.length - 1} className="flex h-7 w-7 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-accent-soft hover:text-accent disabled:opacity-20 disabled:hover:bg-transparent" title="下移"><ArrowDown size={14} /></button></div>
            <label className="relative min-w-0"><SquarePen size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted" /><input value={chapter.display_title || ''} onChange={(event) => setEditChapters((items) => items.map((item) => item.id === chapter.id ? { ...item, display_title: event.target.value } : item))} placeholder={`第 ${chapter.chapter_number} ${formatCopy[format].chapterUnit}`} className="input-field h-9 pl-9" /></label>
            <span className="hidden text-xs text-text-muted md:block">{format === 'novel' ? `${chapter.novel_char_count.toLocaleString('zh-CN')} 字` : `${chapter.manga_image_count} 页漫画`}</span>
            <div className="flex items-center justify-end gap-2 text-xs"><span className={chapter.is_published ? 'font-medium text-accent' : 'text-text-secondary'}>{chapter.is_published ? '可见' : '草稿'}</span><button type="button" role="switch" aria-checked={chapter.is_published} onClick={() => setEditChapters((items) => items.map((item) => item.id === chapter.id ? { ...item, is_published: !item.is_published } : item))} className="flex cursor-pointer items-center" title={chapter.is_published ? '点击转为草稿' : '点击上线该章节'}><span className={`relative inline-flex h-5 w-9 items-center rounded-full border transition-colors duration-200 ${chapter.is_published ? 'border-accent bg-accent' : 'border-border-strong bg-bg-surface'}`}><span className={`inline-block h-3.5 w-3.5 rounded-full transition-all duration-200 ${chapter.is_published ? 'translate-x-[18px] bg-white' : 'translate-x-0.5 bg-text-secondary'}`} /></span></button></div>
            <button type="button" onClick={() => void openReader(chapter.id)} className="flex h-9 w-9 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-accent-muted/40 hover:text-accent" title={`预览${chapterTitle(chapter, format)}`}><Eye size={16} /></button>
          </div>)}</div>
        </section>
      </main></div>
    </div>;
  }

  if (view === 'reader' && selectedWork) {
    const chapters = selectedWork.chapters.slice().sort((a, b) => a.display_order - b.display_order || a.chapter_number - b.chapter_number);
    const currentIndex = chapters.findIndex((chapter) => chapter.id === readerChapterId);
    const currentMeta = chapters[currentIndex];
    return <div className="page-atmosphere flex min-h-0 flex-1 flex-col bg-bg-base">
      <header className="relative z-10 flex h-14 shrink-0 items-center justify-between gap-3 border-b border-border glass-panel px-3 md:px-6"><button type="button" onClick={() => setView('detail')} className="flex min-w-0 items-center gap-2 text-sm text-text-secondary transition-colors hover:text-accent"><ChevronLeft size={18} /><span className="hidden sm:inline">返回管理</span><span className="hidden text-text-muted sm:inline">/</span><span className="truncate font-semibold text-text-primary">{selectedWork.title}</span></button><select value={readerChapterId ?? ''} onChange={(event) => void openReader(Number(event.target.value))} aria-label="切换章节" className="h-9 max-w-48 rounded-lg border border-border bg-bg-surface px-2 text-xs text-text-secondary transition-colors focus:border-accent focus:outline-none">{chapters.map((chapter) => <option key={chapter.id} value={chapter.id}>{chapterTitle(chapter, format)}</option>)}</select></header>
      <div className="flex-1 overflow-y-auto bg-bg-surface/60">{readerLoading ? <div className="flex h-full items-center justify-center"><Loader2 size={28} className="animate-spin text-accent" /></div>
        : format === 'novel' ? <article className="fanqie-reader-page mx-auto min-h-full max-w-3xl px-6 py-10 sm:px-12 md:py-14"><div className="fanqie-reader-header mb-8"><div className="text-xs font-semibold text-accent">小说预览</div><h1 className="font-display mt-2 text-2xl font-bold text-text-primary">{currentMeta ? chapterTitle(currentMeta, format) : ''}</h1></div>{readerChapter?.novel_content?.trim() ? <div className="fanqie-reader-copy whitespace-pre-wrap break-words text-base leading-8">{readerChapter.novel_content}</div> : <EmptyReader message={formatCopy.novel.emptyContent} />}</article>
          : readerImages.length ? <div className="fanqie-reader-page mx-auto max-w-3xl">{readerImages.map((image) => <img key={image.id} src={mangaImageUrl(image.image_path) || ''} alt={`漫画第 ${image.image_number} 页`} className="block w-full animate-scale-in" loading="lazy" />)}</div> : <EmptyReader message={formatCopy.manga.emptyContent} />}</div>
      <footer className="relative z-10 flex shrink-0 items-center justify-center gap-5 border-t border-border glass-panel px-3 py-3"><button type="button" onClick={() => navigateReader(-1)} disabled={currentIndex <= 0} className="fanqie-reader-nav disabled:opacity-30"><ChevronLeft size={16} />上一{formatCopy[format].chapterUnit}</button><span className="min-w-14 text-center text-xs text-text-muted">{currentIndex + 1} / {chapters.length}</span><button type="button" onClick={() => navigateReader(1)} disabled={currentIndex < 0 || currentIndex >= chapters.length - 1} className="fanqie-reader-nav disabled:opacity-30">下一{formatCopy[format].chapterUnit}<ChevronRight size={16} /></button></footer>
    </div>;
  }

  return null;
}

function EmptyReader({ message }: { message: string }) {
  return <div className="flex min-h-80 items-center justify-center px-4 text-center text-text-muted"><div><FileText size={48} className="mx-auto mb-4 animate-float" strokeWidth={1.1} /><p className="text-sm">{message}</p></div></div>;
}
