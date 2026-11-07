import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDown, ArrowUp, ArrowUpDown, BookOpenText, CalendarDays, CheckCircle2,
  ChevronLeft, ChevronRight, CircleDot, Eye, EyeOff, FileText, Globe, Image as ImageIcon,
  Layers, Loader2, RefreshCw, Save, Search, Send, SquarePen, X,
} from 'lucide-react';
import {
  getChapter, listMyWorks, mangaImageUrl, publishStory, refImageUrl, updateChapterOrder,
  type Chapter, type MangaImage, type MyWork, type MyWorkChapter,
} from '../api';
import { useConfirm } from '../ui/confirm/useConfirm';
import { useToast } from '../ui/toast/useToast';

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

/** 将作品章节转换为当前形态可编辑的草稿（可见状态统一到 is_published 字段）。 */
const buildChapterDraft = (work: MyWork, format: Format) => work.chapters
  .slice()
  .sort((a, b) => a.display_order - b.display_order || a.chapter_number - b.chapter_number)
  .map((chapter) => ({ ...chapter, is_published: isChapterPublished(chapter, format) }));

export default function MyWorksPage() {
  const toast = useToast();
  const confirm = useConfirm();
  const [format, setFormat] = useState<Format>('novel');
  const [view, setView] = useState<PageView>('list');
  const [works, setWorks] = useState<MyWork[]>([]);
  const [selectedWork, setSelectedWork] = useState<MyWork | null>(null);
  const [editChapters, setEditChapters] = useState<MyWorkChapter[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [unpublishing, setUnpublishing] = useState(false);
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
    try {
      setWorks(await listMyWorks());
      setLoadError('');
    } catch (error) {
      console.error(error);
      setLoadError('作品加载失败，请检查网络后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;
    listMyWorks()
      .then((items) => { if (active) setWorks(items); })
      .catch((error: unknown) => {
        if (!active) return;
        console.error(error);
        setLoadError('作品加载失败，请检查网络后重试');
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

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

  // 已落库的章节状态：用于判断排序、标题与发布范围是否存在未保存的改动。
  const baseline = useMemo(
    () => selectedWork ? buildChapterDraft(selectedWork, format) : [],
    [format, selectedWork],
  );
  const baselineTitles = useMemo(
    () => new Map(baseline.map((chapter) => [chapter.id, (chapter.display_title || '').trim()])),
    [baseline],
  );
  const baselinePublished = useMemo(
    () => new Map(baseline.map((chapter) => [chapter.id, chapter.is_published])),
    [baseline],
  );

  const orderDirty = editChapters.some((chapter, index) => baseline[index]?.id !== chapter.id);
  const titleDirty = editChapters.some(
    (chapter) => (chapter.display_title || '').trim() !== (baselineTitles.get(chapter.id) ?? ''),
  );
  const metaDirty = orderDirty || titleDirty;
  const publishDirty = editChapters.some(
    (chapter) => chapter.is_published !== (baselinePublished.get(chapter.id) ?? false),
  );

  const openDetail = (work: MyWork) => {
    setSelectedWork(work);
    setEditChapters(buildChapterDraft(work, format));
    setView('detail');
    requestAnimationFrame(() => pageScrollRef.current?.scrollTo({ top: 0 }));
  };

  const refreshDetail = useCallback(async (workId: number) => {
    const updated = await listMyWorks();
    setWorks(updated);
    const work = updated.find((item) => item.id === workId);
    if (work) {
      setSelectedWork(work);
      setEditChapters(buildChapterDraft(work, format));
    }
  }, [format]);

  const backToList = async () => {
    setView('list');
    setSelectedWork(null);
    await loadWorks();
  };

  const handleBack = async () => {
    if (metaDirty) {
      const confirmed = await confirm({
        title: '放弃未保存的修改？',
        message: '章节排序与标题的修改尚未保存，返回列表后将丢失。',
        confirmText: '放弃并返回',
        tone: 'danger',
      });
      if (!confirmed) return;
    }
    await backToList();
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

  const persistChapterEdits = async (workId: number) => {
    await updateChapterOrder(workId, editChapters.map((chapter, index) => ({
      chapter_id: chapter.id,
      display_order: index,
      display_title: (chapter.display_title || '').trim(),
    })));
  };

  // 保存：只落库章节排序与展示标题，不改变任何发布状态。
  const handleSave = async () => {
    if (!selectedWork || !metaDirty) return;
    setSaving(true);
    try {
      await persistChapterEdits(selectedWork.id);
      await refreshDetail(selectedWork.id);
      toast.success('章节排序与标题已保存');
    } catch (error) {
      toast.error(`保存失败：${error instanceof Error ? error.message : '未知错误'}`);
    } finally {
      setSaving(false);
    }
  };

  // 发布：将当前选中的章节提交为公开状态（作品级与章节级同时生效）。
  const handlePublish = async () => {
    if (!selectedWork) return;
    const selected = editChapters.filter((chapter) => chapter.is_published);
    if (selected.length === 0) {
      toast.error('请至少选择一个章节再发布');
      return;
    }
    const missingContent = selected.find((chapter) => chapterContentCount(chapter, format) <= 0);
    if (missingContent) {
      toast.error(`《${chapterTitle(missingContent, format)}》还没有${format === 'novel' ? '正文' : '漫画'}，请先完成内容后再发布`);
      return;
    }
    const published = isWorkPublished(selectedWork, format);
    const formatName = formatCopy[format].label.replace('管理', '');
    const confirmed = await confirm({
      title: published ? '更新发布内容' : '发布作品',
      message: [
        published
          ? `将公开 ${selected.length} 个章节，其余 ${editChapters.length - selected.length} 个章节保持草稿状态。`
          : `${formatName}发布后会在作品广场展示，读者可看到 ${selected.length} 个章节。`,
        metaDirty ? '当前未保存的章节排序与标题会一并保存。' : '',
      ].filter(Boolean).join('\n'),
      confirmText: published ? '更新发布' : '确认发布',
    });
    if (!confirmed) return;
    setPublishing(true);
    try {
      if (metaDirty) await persistChapterEdits(selectedWork.id);
      await publishStory(selectedWork.id, format, true, selected.map((chapter) => chapter.id));
      await refreshDetail(selectedWork.id);
      toast.success(published ? '发布内容已更新' : '作品已发布');
    } catch (error) {
      toast.error(`发布失败：${error instanceof Error ? error.message : '未知错误'}`);
    } finally {
      setPublishing(false);
    }
  };

  const handleUnpublish = async () => {
    if (!selectedWork) return;
    const confirmed = await confirm({
      title: '取消发布',
      message: '取消发布后，读者将无法在作品广场看到该作品，已上线的章节会全部转为草稿。',
      confirmText: '取消发布',
      tone: 'danger',
    });
    if (!confirmed) return;
    setUnpublishing(true);
    try {
      await publishStory(selectedWork.id, format, false, []);
      await refreshDetail(selectedWork.id);
      toast.success('已取消发布');
    } catch (error) {
      toast.error(`取消发布失败：${error instanceof Error ? error.message : '未知错误'}`);
    } finally {
      setUnpublishing(false);
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
    const chapters = buildChapterDraft(selectedWork, format);
    const index = chapters.findIndex((chapter) => chapter.id === readerChapterId);
    const next = chapters[index + direction];
    if (next) void openReader(next.id);
  };

  if (loading) return <div className="flex flex-1 flex-col bg-bg-base">
    <div className="mx-auto w-full max-w-7xl flex-1 px-4 py-5 md:px-8 md:py-7">
      <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">
        {Array.from({ length: 4 }).map((_, index) => (
          <div key={index} className="grid min-w-0 grid-cols-[88px_1fr] gap-3 rounded-xl border border-border bg-bg-surface p-3 sm:grid-cols-[104px_1fr] sm:gap-4">
            <div className="aspect-[3/4] rounded-lg bg-bg-raised shimmer-bg" />
            <div className="flex min-w-0 flex-col gap-2.5 py-0.5">
              <div className="h-5 w-1/2 rounded-lg bg-bg-raised shimmer-bg" />
              <div className="h-3 w-full rounded-lg bg-bg-raised shimmer-bg" />
              <div className="h-3 w-2/3 rounded-lg bg-bg-raised shimmer-bg" />
              <div className="mt-auto h-3 w-1/3 rounded-lg bg-bg-raised shimmer-bg" />
            </div>
          </div>
        ))}
      </div>
    </div>
  </div>;

  if (loadError) {
    return <div className="flex flex-1 items-center justify-center bg-bg-base px-4"><div className="text-center">
      <RefreshCw size={38} className="mx-auto animate-float text-text-muted" strokeWidth={1.35} />
      <p className="mt-3 text-sm font-medium text-text-primary">{loadError}</p>
      <button type="button" onClick={() => { setLoadError(''); void loadWorks(); }} className="fanqie-cta-secondary mt-4 !gap-1.5"><RefreshCw size={14} />重新加载</button>
    </div></div>;
  }

  if (view === 'list') {
    const draftCount = works.length - stats.publishedWorks;
    return <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
      <div className="relative z-10 shrink-0 border-b border-border glass-panel">
        <div className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-6">
          <div className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <div className="mb-3 inline-flex rounded-xl border border-border bg-bg-surface/60 p-1 shadow-sm backdrop-blur-sm" aria-label="作品形态">
                {(['novel', 'manga'] as Format[]).map((item) => <button key={item} type="button" onClick={() => switchFormat(item)} className={`flex h-9 items-center gap-2 rounded-lg px-4 text-sm font-semibold transition-all duration-200 ${format === item ? 'bg-gradient-to-b from-accent-soft to-accent-muted text-accent shadow-sm' : 'text-text-secondary hover:bg-accent-soft hover:text-text-primary'}`}>
                  {item === 'novel' ? <BookOpenText size={16} /> : <ImageIcon size={16} />}{formatCopy[item].label}
                </button>)}
              </div>
              <div className="flex flex-wrap items-center gap-2.5">
                <h1 className="font-display text-2xl font-bold text-text-primary md:text-3xl">{formatCopy[format].label}</h1>
                <span className="seal-stamp h-5! min-w-5! text-[0.5rem]!">{format === 'novel' ? '文' : '画'}</span>
                <span className="rounded-full border border-border px-2 py-0.5 font-mono text-[0.625rem] text-text-muted">{works.length} 部</span>
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
        </div>
      </div>
      <div ref={pageScrollRef} className="flex-1 overflow-y-auto">
        <main className="mx-auto w-full max-w-7xl px-4 py-5 md:px-8 md:py-7">
          <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
            {[
              { label: '全部作品', value: works.length, icon: <Layers size={15} className="text-accent" /> },
              { label: '已发布作品', value: stats.publishedWorks, icon: <Globe size={15} className="text-success" /> },
              { label: formatCopy[format].contentMetric, value: stats.contentCount.toLocaleString('zh-CN'), icon: format === 'novel' ? <FileText size={15} className="text-accent-secondary" /> : <ImageIcon size={15} className="text-accent-secondary" /> },
              { label: formatCopy[format].publishedMetric, value: stats.publishedChapters, icon: <CheckCircle2 size={15} className="text-accent-tertiary" /> },
            ].map((stat) => <div key={stat.label} className="flex items-center gap-3 rounded-xl border border-border bg-bg-surface/70 p-3.5 shadow-sm transition-colors hover:border-accent/40">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-border bg-bg-raised shadow-sm">{stat.icon}</span>
              <div className="min-w-0"><div className="text-xl font-semibold leading-tight text-text-primary">{stat.value}</div><div className="truncate text-[0.6875rem] text-text-muted">{stat.label}</div></div>
            </div>)}
          </div>
          <div className="mb-4 mt-5 flex flex-wrap items-center justify-between gap-3">
            <div className="flex gap-1 rounded-xl border border-border bg-bg-surface/60 p-1 shadow-sm backdrop-blur-sm">
              {([['all', '全部作品', works.length], ['published', '已发布', stats.publishedWorks], ['draft', '草稿箱', draftCount]] as const).map(([key, label, count]) => <button key={key} type="button" onClick={() => setStatusFilter(key)} className={`h-8 rounded-lg px-3 text-sm font-medium transition-all duration-200 ${statusFilter === key ? 'bg-gradient-to-b from-accent-soft to-accent-muted text-accent shadow-sm' : 'text-text-secondary hover:bg-accent-soft hover:text-text-primary'}`}>{label} <span className="font-mono text-[0.6875rem] text-text-muted">{count}</span></button>)}
            </div>
            <span className="text-xs text-text-muted">待发布作品 {draftCount} 部 · 共 {stats.totalChapters} {formatCopy[format].chapterUnit}</span>
          </div>
          {filteredWorks.length === 0
            ? <div className="flex min-h-64 items-center justify-center rounded-2xl border border-dashed border-border bg-bg-surface/40 text-center text-text-muted"><div className="px-6 py-10"><ImageIcon size={42} className="mx-auto animate-float" strokeWidth={1.25} /><p className="mt-3 text-sm">{searchQuery ? '没有匹配的作品' : statusFilter === 'draft' ? '没有待发布的草稿作品' : '这里还没有作品'}</p><p className="mt-1 text-[0.6875rem] text-text-muted">{searchQuery ? '换个关键词试试' : '在创作工作台完成内容后，作品会出现在这里'}</p></div></div>
            : <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">{filteredWorks.map((work, workIdx) => {
              const published = isWorkPublished(work, format);
              const contentCount = work.chapters.reduce((sum, chapter) => sum + chapterContentCount(chapter, format), 0);
              const publishedCount = work.chapters.filter((chapter) => isChapterPublished(chapter, format)).length;
              const progress = work.chapters.length ? Math.round((publishedCount / work.chapters.length) * 100) : 0;
              const cover = work.cover_image ? refImageUrl(work.cover_image) : null;
              return <button key={work.id} type="button" onClick={() => openDetail(work)} aria-label={`管理作品：${work.title}`} style={{ animationDelay: `${workIdx * 60}ms` }} className="bento-card group grid min-w-0 animate-fade-in-up grid-cols-[88px_1fr] gap-3.5 p-3.5 text-left sm:grid-cols-[104px_1fr]">
                <div className="relative aspect-[3/4] overflow-hidden rounded-lg border border-border bg-bg-raised">
                  {cover
                    ? <img src={cover} alt={work.title} className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105" loading="lazy" />
                    : <div className="flex h-full w-full items-center justify-center"><ImageIcon size={30} className="text-text-muted" /></div>}
                  <div className="absolute inset-0 flex items-end justify-center bg-gradient-to-t from-black/55 via-transparent to-transparent pb-2 opacity-0 transition-opacity duration-200 group-hover:opacity-100">
                    <span className="inline-flex items-center gap-1 rounded-full bg-black/40 px-2 py-0.5 text-[0.625rem] font-medium text-white/90 backdrop-blur-sm"><SquarePen size={11} />管理</span>
                  </div>
                </div>
                <div className="flex min-w-0 flex-col py-0.5">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <h2 className="truncate text-base font-semibold text-text-primary transition-colors group-hover:text-accent">{work.title}</h2>
                      <p className="mt-1 line-clamp-2 text-xs leading-[18px] text-text-secondary">{work.description || '暂无简介'}</p>
                    </div>
                    <span className={`inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2 py-0.5 text-[0.6875rem] font-medium ${published ? 'border-success/30 bg-success/10 text-success' : 'border-border bg-bg-raised text-text-muted'}`}>
                      <span className={`h-1.5 w-1.5 rounded-full ${published ? 'bg-success' : 'bg-text-muted'}`} />{published ? '已发布' : '草稿'}
                    </span>
                  </div>
                  <div className="mt-3">
                    <div className="flex items-center justify-between font-mono text-[0.625rem] text-text-muted"><span>上线 {publishedCount} / {work.chapters.length}</span><span>{progress}%</span></div>
                    <div className="mt-1 h-1 overflow-hidden rounded-full bg-bg-raised"><div className="h-full rounded-full bg-gradient-to-r from-accent to-amber-accent transition-all duration-300" style={{ width: `${progress}%` }} /></div>
                  </div>
                  <div className="mt-auto flex flex-wrap items-center gap-x-3 gap-y-1 pt-3 text-[0.6875rem] text-text-muted">
                    <span className="inline-flex items-center gap-1"><Layers size={11} />{work.chapters.length} {formatCopy[format].chapterUnit}</span>
                    <span className="inline-flex items-center gap-1">{format === 'novel' ? <FileText size={11} /> : <ImageIcon size={11} />}{contentCount.toLocaleString('zh-CN')} {format === 'novel' ? '字' : '页'}</span>
                    <span className="ml-auto inline-flex items-center gap-1"><CalendarDays size={11} />{formatDate(work.created_at)}</span>
                  </div>
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
    const selectedCount = editChapters.filter((chapter) => chapter.is_published).length;
    const totalContent = editChapters.reduce((sum, chapter) => sum + chapterContentCount(chapter, format), 0);
    const progress = editChapters.length ? Math.round((selectedCount / editChapters.length) * 100) : 0;
    const unit = formatCopy[format].chapterUnit;
    return <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
      <header className="relative z-10 flex h-16 shrink-0 items-center justify-between gap-3 border-b border-border glass-panel px-4 md:px-8">
        <button type="button" onClick={() => void handleBack()} className="flex min-w-0 items-center gap-2 text-sm text-text-secondary transition-colors hover:text-accent">
          <ChevronLeft size={18} />
          <span className="hidden sm:inline">返回{formatCopy[format].label}</span>
          <span className="hidden text-text-muted md:inline">/</span>
          <span className="hidden max-w-52 truncate font-semibold text-text-primary md:inline">{selectedWork.title}</span>
        </button>
        <div className="flex shrink-0 items-center gap-2">
          {metaDirty && <span className="hidden items-center gap-1.5 rounded-full border border-warning/40 bg-warning/10 px-2.5 py-1 text-[0.6875rem] font-medium text-warning md:inline-flex"><CircleDot size={11} />未保存</span>}
          <button type="button" onClick={() => void handleSave()} disabled={!metaDirty || saving || publishing} title={metaDirty ? '保存章节排序与标题' : '没有需要保存的修改'} className="fanqie-cta-secondary !min-h-9 !gap-1.5 !px-3 !py-0 !text-sm disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-border disabled:hover:bg-transparent disabled:hover:text-text-muted">
            {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}<span className="hidden sm:inline">保存更改</span>
          </button>
          <button type="button" onClick={() => void handlePublish()} disabled={publishing || unpublishing || editChapters.length === 0 || selectedCount === 0} title={selectedCount === 0 ? '请先在章节队列中选择要发布的章节' : undefined} className="fanqie-cta-primary !min-h-9 !gap-1.5 !px-4 !py-0 !text-sm disabled:opacity-40">
            {publishing ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}<span className="hidden sm:inline">{published ? '更新发布' : '发布作品'}</span>
            {publishDirty && <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />}
          </button>
        </div>
      </header>
      <div ref={pageScrollRef} className="flex-1 overflow-y-auto">
        <main className="mx-auto w-full max-w-6xl px-4 py-6 md:px-8 lg:py-8">
          <div className="grid gap-5 lg:grid-cols-[300px_minmax(0,1fr)] lg:items-start">
            <aside className="flex flex-col gap-4 lg:sticky lg:top-0">
              <section className="animate-fade-in-up overflow-hidden rounded-2xl border border-border bg-bg-surface shadow-md">
                <div className="ambient-glow relative aspect-[3/4] bg-bg-raised">
                  {cover
                    ? <img src={cover} alt={selectedWork.title} className="h-full w-full object-cover" />
                    : <div className="flex h-full items-center justify-center"><ImageIcon size={34} className="text-text-muted" /></div>}
                  <div className="pointer-events-none absolute inset-x-0 bottom-0 h-24 bg-gradient-to-t from-black/55 via-black/15 to-transparent" />
                  <span className={`absolute left-3 top-3 inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[0.6875rem] font-medium backdrop-blur-sm ${published ? 'border-success/40 bg-black/45 text-success' : 'border-white/20 bg-black/45 text-white/85'}`}>
                    <span className={`h-1.5 w-1.5 rounded-full ${published ? 'bg-success' : 'bg-white/60'}`} />{published ? '已发布' : '草稿'}
                  </span>
                  <span className="absolute bottom-3 right-3 rounded-full bg-black/45 px-2 py-1 font-mono text-[0.625rem] tracking-[0.12em] text-white/75 backdrop-blur-sm">{format === 'novel' ? 'NOVEL' : 'MANGA'}</span>
                </div>
                <div className="space-y-3 p-4">
                  <div className="flex items-center gap-2">
                    <h1 className="min-w-0 font-display break-words text-xl font-bold leading-snug text-text-primary">{selectedWork.title}</h1>
                    <span className="seal-stamp h-6! min-w-6! text-[0.625rem]!">卷</span>
                  </div>
                  <p className="line-clamp-3 text-xs leading-5 text-text-secondary">{selectedWork.description || '暂无简介'}</p>
                  <div className="rounded-xl border border-border bg-bg-base/50 p-3">
                    <div className="flex items-center justify-between text-[0.6875rem]"><span className="text-text-muted">上线进度</span><span className="font-mono text-text-secondary">{selectedCount} / {editChapters.length}</span></div>
                    <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-bg-raised"><div className="h-full rounded-full bg-gradient-to-r from-accent to-amber-accent transition-all duration-300" style={{ width: `${progress}%` }} /></div>
                    <p className="mt-2 text-[0.625rem] leading-4 text-text-muted">「保存」仅保存章节排序与标题；发布范围的调整需点击「发布」后生效。</p>
                    {selectedCount === 0 && editChapters.length > 0 && <p className="mt-1 text-[0.625rem] leading-4 text-warning">还没有选择发布章节，请在右侧队列中开启至少一个章节。</p>}
                  </div>
                  <p className="inline-flex items-start gap-1.5 text-[0.625rem] leading-4 text-text-muted"><CalendarDays size={12} className="mt-0.5 shrink-0" />创建于 {formatDate(selectedWork.created_at)}</p>
                </div>
              </section>
              <section className="animate-fade-in-up rounded-2xl border border-border bg-bg-surface p-4 shadow-sm" style={{ animationDelay: '100ms' }}>
                <h2 className="text-[0.625rem] font-semibold tracking-[0.18em] text-text-muted">作品信息 / INFO</h2>
                <dl className="mt-3 space-y-2.5 text-xs">
                  <div className="flex items-center justify-between gap-3"><dt className="inline-flex items-center gap-2 text-text-muted"><Layers size={13} />章节数量</dt><dd className="font-mono text-text-secondary">{editChapters.length} {unit}</dd></div>
                  <div className="flex items-center justify-between gap-3"><dt className="inline-flex items-center gap-2 text-text-muted">{format === 'novel' ? <FileText size={13} /> : <ImageIcon size={13} />}内容总量</dt><dd className="font-mono text-text-secondary">{totalContent.toLocaleString('zh-CN')} {format === 'novel' ? '字' : '页'}</dd></div>
                  <div className="flex items-center justify-between gap-3"><dt className="inline-flex items-center gap-2 text-text-muted"><Globe size={13} />发布状态</dt><dd className={published ? 'text-success' : 'text-text-muted'}>{published ? `已公开 · ${selectedCount} ${unit}` : '仅自己可见'}</dd></div>
                </dl>
                {published
                  ? <button type="button" onClick={() => void handleUnpublish()} disabled={publishing || unpublishing} className="mt-4 inline-flex w-full items-center justify-center gap-1.5 rounded-lg border border-danger/30 px-3 py-2 text-xs font-medium text-danger transition-colors hover:bg-danger/10 disabled:opacity-40">{unpublishing ? <Loader2 size={13} className="animate-spin" /> : <EyeOff size={13} />}取消发布</button>
                  : <p className="mt-4 rounded-lg border border-dashed border-border px-3 py-2 text-[0.625rem] leading-4 text-text-muted">作品尚未发布。选择要公开的章节后，点击右上角「发布作品」。</p>}
              </section>
            </aside>

            <section className="animate-fade-in-up" style={{ animationDelay: '60ms' }}>
              <div className="overflow-hidden rounded-2xl border border-border bg-bg-surface shadow-sm">
                <div className="flex flex-col gap-3 border-b border-border px-4 py-4 md:flex-row md:items-center md:justify-between">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="font-display text-base font-semibold text-text-primary">章节发布队列</h2>
                      <span className="rounded-full border border-border px-2 py-0.5 font-mono text-[0.625rem] text-text-muted">{editChapters.length}</span>
                      {publishDirty && <span className="inline-flex items-center gap-1 rounded-full border border-warning/40 bg-warning/10 px-2 py-0.5 text-[0.625rem] text-warning"><CircleDot size={10} />发布范围待应用</span>}
                    </div>
                    <p className="mt-1 text-xs text-text-muted">改标题或调顺序后点「保存」；切换可见状态后点「发布」生效。</p>
                  </div>
                  <div className="flex shrink-0 flex-wrap items-center gap-2">
                    <span className="font-mono text-[0.6875rem] text-text-muted">已上线 {selectedCount} / {editChapters.length}</span>
                    <button type="button" onClick={() => setEditChapters((items) => items.map((item) => ({ ...item, is_published: true })))} className="fanqie-cta-secondary !min-h-8 !gap-1 !px-2.5 !py-1 !text-xs">全部可见</button>
                    <button type="button" onClick={() => setEditChapters((items) => items.map((item) => ({ ...item, is_published: false })))} className="fanqie-cta-secondary !min-h-8 !gap-1 !px-2.5 !py-1 !text-xs">全部草稿</button>
                  </div>
                </div>
                {editChapters.length === 0
                  ? <div className="px-4 py-14 text-center text-text-muted"><Layers size={38} className="mx-auto animate-float" strokeWidth={1.25} /><p className="mt-3 text-sm">还没有章节</p><p className="mt-1 text-[0.6875rem]">在创作工作台生成内容后，章节会出现在这里</p></div>
                  : <div className="divide-y divide-border">
                    {editChapters.map((chapter, index) => {
                      const visible = chapter.is_published;
                      const count = chapterContentCount(chapter, format);
                      const hasContent = count > 0;
                      const pendingPublish = visible !== (baselinePublished.get(chapter.id) ?? false);
                      return <div key={chapter.id} className={`grid grid-cols-[1.75rem_minmax(0,1fr)_auto] items-center gap-3 border-l-2 px-3 py-3 transition-colors hover:bg-accent-soft/40 md:px-4 ${visible ? 'border-l-accent' : 'border-l-transparent'}`}>
                        <span className={`font-mono text-[0.6875rem] ${visible ? 'text-accent' : 'text-text-muted'}`}>{String(index + 1).padStart(2, '0')}</span>
                        <div className="min-w-0">
                          <label className="relative block">
                            <span className="sr-only">{`章节 ${index + 1} 标题`}</span>
                            <SquarePen size={13} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-text-muted" />
                            <input value={chapter.display_title || ''} onChange={(event) => setEditChapters((items) => items.map((item) => item.id === chapter.id ? { ...item, display_title: event.target.value } : item))} placeholder={`第 ${chapter.chapter_number} ${unit}`} className="input-field h-9 !pl-8 text-sm" />
                          </label>
                          <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 pl-0.5 text-[0.6875rem] text-text-muted">
                            <span>第 {chapter.chapter_number} {unit}</span>
                            <span className={hasContent ? '' : 'text-warning'}>{format === 'novel' ? `${count.toLocaleString('zh-CN')} 字` : `${count} 页`}{hasContent ? '' : ' · 待创作'}</span>
                            {pendingPublish && <span className="inline-flex items-center gap-1 text-warning"><CircleDot size={10} />{visible ? '待发布上线' : '待发布下线'}</span>}
                          </div>
                        </div>
                        <div className="flex items-center gap-1">
                          <button type="button" role="switch" aria-checked={visible} onClick={() => setEditChapters((items) => items.map((item) => item.id === chapter.id ? { ...item, is_published: !item.is_published } : item))} title={visible ? '点击转为草稿' : '点击加入发布范围'} className={`inline-flex h-8 items-center gap-1.5 rounded-lg border px-2 text-[0.6875rem] font-medium transition-colors ${visible ? 'border-accent/50 bg-accent-muted text-accent' : 'border-border bg-bg-surface text-text-muted hover:border-accent/40 hover:text-text-primary'}`}>
                            <span className={`h-1.5 w-1.5 rounded-full ${visible ? 'bg-accent' : 'bg-text-muted'}`} /><span className="hidden sm:inline">{visible ? '可见' : '草稿'}</span>
                          </button>
                          <button type="button" onClick={() => void openReader(chapter.id)} className="flex h-8 w-8 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-accent-muted/40 hover:text-accent" title={`预览${chapterTitle(chapter, format)}`} aria-label={`预览${chapterTitle(chapter, format)}`}><Eye size={15} /></button>
                          <button type="button" onClick={() => moveChapter(index, -1)} disabled={index === 0} className="flex h-8 w-8 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-accent-soft hover:text-accent disabled:opacity-20 disabled:hover:bg-transparent" title="上移" aria-label="上移章节"><ArrowUp size={14} /></button>
                          <button type="button" onClick={() => moveChapter(index, 1)} disabled={index === editChapters.length - 1} className="flex h-8 w-8 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-accent-soft hover:text-accent disabled:opacity-20 disabled:hover:bg-transparent" title="下移" aria-label="下移章节"><ArrowDown size={14} /></button>
                        </div>
                      </div>;
                    })}
                  </div>}
              </div>
            </section>
          </div>
        </main>
      </div>
    </div>;
  }

  if (view === 'reader' && selectedWork) {
    const chapters = buildChapterDraft(selectedWork, format);
    const currentIndex = chapters.findIndex((chapter) => chapter.id === readerChapterId);
    const currentMeta = chapters[currentIndex];
    return <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
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
