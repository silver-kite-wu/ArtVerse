import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowUpDown,
  BookOpenText,
  CalendarDays,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Eye,
  FileText,
  Globe,
  Image as ImageIcon,
  Layers,
  Loader2,
  Search,
  Send,
  SquarePen,
} from 'lucide-react';
import {
  listMyWorks, publishStory, updateChapterOrder, getChapter,
  mangaImageUrl, refImageUrl,
  type MyWork, type MyWorkChapter, type Chapter, type MangaImage,
} from '../api';

type View = 'list' | 'detail' | 'reader';
type StatusFilter = 'all' | 'published' | 'draft';
type SortOrder = 'newest' | 'oldest';

interface WorkStats {
  totalChapters: number;
  publishedChapters: number;
  draftChapters: number;
  progress: number;
}

const text = {
  allWorks: '\u5168\u90e8\u4f5c\u54c1',
  published: '\u5df2\u53d1\u5e03',
  draftBox: '\u8349\u7a3f\u7bb1',
  unrecorded: '\u672a\u8bb0\u5f55',
  title: '\u4f5c\u54c1\u7ba1\u7406',
  subtitle: '\u7ba1\u7406\u53d1\u5e03\u72b6\u6001\u3001\u7ae0\u8282\u987a\u5e8f\u4e0e\u8bfb\u8005\u53ef\u89c1\u5185\u5bb9',
  search: '\u641c\u7d22\u4f5c\u54c1\u540d\u79f0',
  newest: '\u6700\u8fd1\u521b\u5efa',
  oldest: '\u6700\u65e9\u521b\u5efa',
  publishedWorks: '\u5df2\u53d1\u5e03\u4f5c\u54c1',
  totalChapters: '\u7ae0\u8282\u603b\u6570',
  onlineChapters: '\u4e0a\u7ebf\u7ae0\u8282',
  pendingWorks: '\u5f85\u53d1\u5e03\u4f5c\u54c1',
  worksUnit: '\u90e8',
  noMatch: '\u6ca1\u6709\u5339\u914d\u7684\u4f5c\u54c1',
  noWorks: '\u8fd8\u6ca1\u6709\u4f5c\u54c1\uff0c\u53bb\u5de5\u4f5c\u533a\u521b\u5efa\u5427',
  noDescription: '\u6682\u65e0\u7b80\u4ecb',
  draft: '\u8349\u7a3f',
  totalPrefix: '\u5171',
  chapterUnit: '\u8bdd',
  online: '\u4e0a\u7ebf',
  createdAt: '\u521b\u5efa\u4e8e',
  backToList: '\u8fd4\u56de\u4f5c\u54c1\u5217\u8868',
  savePublish: '\u4fdd\u5b58\u53d1\u5e03\u8bbe\u7f6e',
  saveFailed: '\u4fdd\u5b58\u5931\u8d25\uff1a',
  unknownError: '\u672a\u77e5\u9519\u8bef',
  publicWork: '\u4f5c\u54c1\u5df2\u516c\u5f00',
  privateWork: '\u4f5c\u54c1\u672a\u516c\u5f00',
  publishProgress: '\u53d1\u5e03\u8fdb\u5ea6',
  publishedChapterCount: '\u5df2\u4e0a\u7ebf',
  pendingChapterCount: '\u5f85\u6574\u7406',
  chapterQueue: '\u7ae0\u8282\u53d1\u5e03\u961f\u5217',
  queueSubtitle: '\u8c03\u6574\u5c55\u793a\u6807\u9898\u3001\u6392\u5e8f\u548c\u8bfb\u8005\u53ef\u89c1\u72b6\u6001',
  allOnline: '\u5168\u90e8\u4e0a\u7ebf',
  allDraft: '\u5168\u90e8\u8f6c\u8349\u7a3f',
  noChapters: '\u6682\u65e0\u7ae0\u8282\uff0c\u53bb\u5de5\u4f5c\u533a\u521b\u5efa\u5427',
  chapterPrefix: '\u7b2c',
  visible: '\u53ef\u89c1',
  preview: '\u9884\u89c8',
  back: '\u8fd4\u56de',
  noManga: '\u8be5\u8bdd\u6682\u672a\u751f\u6210\u6f2b\u753b',
  backToManage: '\u8fd4\u56de\u7ba1\u7406',
  pageUnit: '\u9875',
  prevChapter: '\u4e0a\u4e00\u8bdd',
  nextChapter: '\u4e0b\u4e00\u8bdd',
};

const statusFilters: { key: StatusFilter; label: string }[] = [
  { key: 'all', label: text.allWorks },
  { key: 'published', label: text.published },
  { key: 'draft', label: text.draftBox },
];

const chapterTitle = (chapterNumber: number) => `${text.chapterPrefix} ${chapterNumber} ${text.chapterUnit}`;

const getWorkStats = (work: MyWork): WorkStats => {
  const totalChapters = work.chapters.length;
  const publishedChapters = work.chapters.filter((chapter) => chapter.is_published).length;
  const draftChapters = Math.max(totalChapters - publishedChapters, 0);
  const progress = totalChapters > 0 ? Math.round((publishedChapters / totalChapters) * 100) : 0;
  return { totalChapters, publishedChapters, draftChapters, progress };
};

const formatDate = (date: string | null) => date ? new Date(date).toLocaleDateString('zh-CN') : text.unrecorded;

export default function MyWorksPage() {
  const [works, setWorks] = useState<MyWork[]>([]);
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<View>('list');
  const [selectedWork, setSelectedWork] = useState<MyWork | null>(null);
  const [editChapters, setEditChapters] = useState<MyWorkChapter[]>([]);
  const [saving, setSaving] = useState(false);
  const [readerChapterId, setReaderChapterId] = useState<number | null>(null);
  const [readerImages, setReaderImages] = useState<MangaImage[]>([]);
  const [readerLoading, setReaderLoading] = useState(false);
  const [readerChapterInfo, setReaderChapterInfo] = useState<{ chapter_number: number; display_title: string } | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [sortOrder, setSortOrder] = useState<SortOrder>('newest');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');

  const loadWorks = useCallback(async () => {
    try {
      const data = await listMyWorks();
      setWorks(data);
    } catch (error) {
      console.error(error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    void listMyWorks()
      .then((data) => {
        if (!cancelled) setWorks(data);
      })
      .catch((error) => console.error(error))
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, []);

  const dashboardStats = useMemo(() => {
    const publishedWorks = works.filter((work) => work.is_published).length;
    const totalChapters = works.reduce((sum, work) => sum + work.chapters.length, 0);
    const publishedChapters = works.reduce((sum, work) => sum + getWorkStats(work).publishedChapters, 0);
    const pendingWorks = works.length - publishedWorks;
    return { publishedWorks, totalChapters, publishedChapters, pendingWorks };
  }, [works]);

  const filteredWorks = useMemo(() => works
    .filter((work) => {
      const matchesSearch = !searchQuery || work.title.toLowerCase().includes(searchQuery.toLowerCase());
      const matchesStatus = statusFilter === 'all'
        || (statusFilter === 'published' && work.is_published)
        || (statusFilter === 'draft' && !work.is_published);
      return matchesSearch && matchesStatus;
    })
    .sort((a, b) => {
      const dateA = a.created_at || '';
      const dateB = b.created_at || '';
      return sortOrder === 'newest' ? dateB.localeCompare(dateA) : dateA.localeCompare(dateB);
    }), [searchQuery, sortOrder, statusFilter, works]);

  const coverUrl = (work: MyWork) => work.cover_image ? refImageUrl(work.cover_image) : null;

  const openDetail = (work: MyWork) => {
    setSelectedWork(work);
    setEditChapters(work.chapters.map((chapter) => ({ ...chapter })));
    setView('detail');
  };

  const backToList = async () => {
    setView('list');
    setSelectedWork(null);
    await loadWorks();
  };

  const toggleChapter = (chapterId: number) => {
    setEditChapters((prev) => prev.map((chapter) => chapter.id === chapterId ? { ...chapter, is_published: !chapter.is_published } : chapter));
  };

  const updateDisplayTitle = (chapterId: number, title: string) => {
    setEditChapters((prev) => prev.map((chapter) => chapter.id === chapterId ? { ...chapter, display_title: title } : chapter));
  };

  const moveChapter = (index: number, direction: -1 | 1) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= editChapters.length) return;
    const nextChapters = [...editChapters];
    [nextChapters[index], nextChapters[targetIndex]] = [nextChapters[targetIndex], nextChapters[index]];
    setEditChapters(nextChapters);
  };

  const selectAll = () => setEditChapters((prev) => prev.map((chapter) => ({ ...chapter, is_published: true })));
  const deselectAll = () => setEditChapters((prev) => prev.map((chapter) => ({ ...chapter, is_published: false })));

  const handleSave = async () => {
    if (!selectedWork) return;
    setSaving(true);
    try {
      const selectedChapterIds = editChapters.filter((chapter) => chapter.is_published).map((chapter) => chapter.id);
      await publishStory(selectedWork.id, selectedChapterIds.length > 0, selectedChapterIds);
      await updateChapterOrder(selectedWork.id, editChapters.map((chapter, index) => ({
        chapter_id: chapter.id,
        display_order: index,
        display_title: chapter.display_title || undefined,
      })));
      const updatedWorks = await listMyWorks();
      setWorks(updatedWorks);
      const updatedWork = updatedWorks.find((work) => work.id === selectedWork.id);
      if (updatedWork) {
        setSelectedWork(updatedWork);
        setEditChapters(updatedWork.chapters.map((chapter) => ({ ...chapter })));
      }
    } catch (error) {
      alert(text.saveFailed + (error instanceof Error ? error.message : text.unknownError));
    } finally {
      setSaving(false);
    }
  };

  const openReader = async (chapterId: number) => {
    setReaderChapterId(chapterId);
    setReaderImages([]);
    setReaderLoading(true);
    setView('reader');
    try {
      const chapter: Chapter = await getChapter(chapterId);
      const chapterMeta = selectedWork?.chapters.find((item) => item.id === chapterId);
      setReaderChapterInfo({
        chapter_number: chapter.chapter_number,
        display_title: chapterMeta?.display_title || chapterTitle(chapter.chapter_number),
      });
      setReaderImages((chapter.images || []).sort((a, b) => a.image_number - b.image_number));
    } catch (error) {
      console.error(error);
      setReaderImages([]);
    } finally {
      setReaderLoading(false);
    }
  };

  const closeReader = () => {
    setView('detail');
    setReaderChapterId(null);
    setReaderImages([]);
  };

  const navigateReaderChapter = (direction: -1 | 1) => {
    if (!selectedWork) return;
    const chapterIndex = selectedWork.chapters.findIndex((chapter) => chapter.id === readerChapterId);
    const nextIndex = chapterIndex + direction;
    if (nextIndex >= 0 && nextIndex < selectedWork.chapters.length) {
      openReader(selectedWork.chapters[nextIndex].id);
    }
  };

  const currentReaderIdx = selectedWork?.chapters.findIndex((chapter) => chapter.id === readerChapterId) ?? -1;

  if (loading) {
    return <div className="flex-1 flex items-center justify-center"><Loader2 size={28} className="animate-spin text-coral" /></div>;
  }

  if (view === 'list') {
    return (
      <div className="flex-1 flex flex-col min-h-0 bg-ink">
        <div className="border-b border-ink-border px-4 py-4 md:px-6 shrink-0">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <h2 className="flex items-center gap-2 text-xl font-semibold text-cream"><BookOpenText size={22} className="text-coral" />{text.title}</h2>
              <p className="mt-1 text-sm text-cream-dim">{text.subtitle}</p>
            </div>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
              <div className="relative">
                <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-cream-dim" />
                <input value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} placeholder={text.search} className="w-full rounded-lg border border-ink-border bg-ink-lighter py-2 pl-9 pr-3 text-sm text-cream placeholder-ink-muted focus:border-coral focus:outline-none sm:w-64" />
              </div>
              <div className="relative">
                <select value={sortOrder} onChange={(event) => setSortOrder(event.target.value as SortOrder)} className="w-full appearance-none rounded-lg border border-ink-border bg-ink-lighter py-2 pl-3 pr-9 text-sm text-cream-dim focus:border-coral focus:outline-none sm:w-32">
                  <option value="newest">{text.newest}</option>
                  <option value="oldest">{text.oldest}</option>
                </select>
                <ArrowUpDown size={13} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-cream-dim" />
              </div>
            </div>
          </div>

          <div className="mt-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
            <div className="rounded-lg border border-ink-border bg-ink-light p-3">
              <div className="flex items-center gap-2 text-xs text-cream-dim"><Layers size={14} className="text-coral" />{text.allWorks}</div>
              <div className="mt-2 text-2xl font-semibold text-cream">{works.length}</div>
            </div>
            <div className="rounded-lg border border-ink-border bg-ink-light p-3">
              <div className="flex items-center gap-2 text-xs text-cream-dim"><Globe size={14} className="text-success" />{text.publishedWorks}</div>
              <div className="mt-2 text-2xl font-semibold text-cream">{dashboardStats.publishedWorks}</div>
            </div>
            <div className="rounded-lg border border-ink-border bg-ink-light p-3">
              <div className="flex items-center gap-2 text-xs text-cream-dim"><FileText size={14} className="text-amber-accent" />{text.totalChapters}</div>
              <div className="mt-2 text-2xl font-semibold text-cream">{dashboardStats.totalChapters}</div>
            </div>
            <div className="rounded-lg border border-ink-border bg-ink-light p-3">
              <div className="flex items-center gap-2 text-xs text-cream-dim"><CheckCircle2 size={14} className="text-coral-light" />{text.onlineChapters}</div>
              <div className="mt-2 text-2xl font-semibold text-cream">{dashboardStats.publishedChapters}</div>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2 border-b border-ink-border px-4 py-3 md:px-6 shrink-0 overflow-x-auto">
          {statusFilters.map((filter) => (
            <button key={filter.key} onClick={() => setStatusFilter(filter.key)} className={`shrink-0 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${statusFilter === filter.key ? 'bg-coral text-cream' : 'bg-ink-lighter text-cream-dim hover:bg-ink-surface hover:text-cream'}`}>
              {filter.label}
            </button>
          ))}
          <span className="ml-auto shrink-0 text-xs text-warm-gray">{text.pendingWorks} {dashboardStats.pendingWorks} {text.worksUnit}</span>
        </div>

        <div className="flex-1 overflow-y-auto p-4 md:p-6">
          {filteredWorks.length === 0 ? (
            <div className="flex h-full items-center justify-center">
              <div className="text-center text-warm-gray">
                <ImageIcon size={48} className="mx-auto mb-3 opacity-30" />
                <p className="text-sm">{searchQuery ? text.noMatch : text.noWorks}</p>
              </div>
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
              {filteredWorks.map((work) => {
                const cover = coverUrl(work);
                const stats = getWorkStats(work);
                return (
                  <button key={work.id} onClick={() => openDetail(work)} className="group grid grid-cols-[88px_1fr] gap-4 rounded-lg border border-ink-border bg-ink-light p-3 text-left transition-colors hover:border-coral/40 hover:bg-ink-lighter sm:grid-cols-[112px_1fr]">
                    <div className="aspect-[3/4] overflow-hidden rounded-md border border-ink-border bg-ink-lighter">
                      {cover ? <img src={cover} alt={work.title} className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105" loading="lazy" /> : <div className="flex h-full w-full items-center justify-center"><ImageIcon size={34} className="text-warm-gray" /></div>}
                    </div>
                    <div className="min-w-0 py-1">
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <h3 className="truncate text-base font-semibold text-cream">{work.title}</h3>
                          <p className="mt-1 line-clamp-2 text-sm leading-relaxed text-cream-dim">{work.description || text.noDescription}</p>
                        </div>
                        <span className={`shrink-0 rounded-md border px-2 py-1 text-xs ${work.is_published ? 'border-success/30 bg-success/10 text-success' : 'border-ink-border bg-ink text-cream-dim'}`}>{work.is_published ? text.published : text.draft}</span>
                      </div>
                      <div className="mt-4 grid grid-cols-3 gap-2 text-xs text-cream-dim">
                        <span className="rounded-md bg-ink px-2 py-1.5">{text.totalPrefix} {stats.totalChapters} {text.chapterUnit}</span>
                        <span className="rounded-md bg-ink px-2 py-1.5">{text.online} {stats.publishedChapters} {text.chapterUnit}</span>
                        <span className="rounded-md bg-ink px-2 py-1.5">{text.draft} {stats.draftChapters} {text.chapterUnit}</span>
                      </div>
                      <div className="mt-4 flex items-center gap-3">
                        <div className="h-2 flex-1 overflow-hidden rounded-full bg-ink">
                          <div className="h-full rounded-full bg-coral" style={{ width: `${stats.progress}%` }} />
                        </div>
                        <span className="w-10 text-right text-xs text-cream-dim">{stats.progress}%</span>
                      </div>
                      <div className="mt-3 flex items-center gap-2 text-xs text-warm-gray"><CalendarDays size={13} />{text.createdAt} {formatDate(work.created_at)}</div>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </div>
    );
  }

  if (view === 'detail' && selectedWork) {
    const cover = coverUrl(selectedWork);
    const publishedCount = editChapters.filter((chapter) => chapter.is_published).length;
    const progress = editChapters.length > 0 ? Math.round((publishedCount / editChapters.length) * 100) : 0;

    return (
      <div className="flex-1 flex flex-col min-h-0 bg-ink">
        <div className="flex items-center justify-between border-b border-ink-border px-4 py-3 md:px-6 shrink-0">
          <button onClick={backToList} className="flex items-center gap-1.5 text-sm text-cream-dim transition-colors hover:text-cream">
            <ChevronLeft size={18} />{text.backToList}
          </button>
          <button onClick={handleSave} disabled={saving} className="flex items-center gap-2 rounded-lg bg-coral px-4 py-2 text-sm font-medium text-cream transition-colors hover:bg-coral-light disabled:opacity-40">
            {saving ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}{text.savePublish}
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4 md:p-6">
          <div className="grid gap-5 xl:grid-cols-[320px_1fr]">
            <aside className="space-y-4">
              <div className="rounded-lg border border-ink-border bg-ink-light p-4">
                <div className="mx-auto aspect-[3/4] w-36 overflow-hidden rounded-md border border-ink-border bg-ink-lighter">
                  {cover ? <img src={cover} alt={selectedWork.title} className="h-full w-full object-cover" /> : <div className="flex h-full w-full items-center justify-center"><ImageIcon size={34} className="text-warm-gray" /></div>}
                </div>
                <h1 className="mt-4 text-xl font-semibold text-cream">{selectedWork.title}</h1>
                <p className="mt-2 text-sm leading-relaxed text-cream-dim">{selectedWork.description || text.noDescription}</p>
                <div className="mt-4 flex flex-wrap gap-2 text-xs">
                  <span className={`rounded-md border px-2 py-1 ${selectedWork.is_published ? 'border-success/30 bg-success/10 text-success' : 'border-ink-border bg-ink text-cream-dim'}`}>{selectedWork.is_published ? text.publicWork : text.privateWork}</span>
                  <span className="rounded-md border border-ink-border bg-ink px-2 py-1 text-cream-dim">{text.createdAt} {formatDate(selectedWork.created_at)}</span>
                </div>
              </div>

              <div className="rounded-lg border border-ink-border bg-ink-light p-4">
                <h2 className="flex items-center gap-2 text-sm font-semibold text-cream"><Clock3 size={15} className="text-coral" />{text.publishProgress}</h2>
                <div className="mt-4 text-3xl font-semibold text-cream">{progress}%</div>
                <div className="mt-3 h-2 overflow-hidden rounded-full bg-ink">
                  <div className="h-full rounded-full bg-coral" style={{ width: `${progress}%` }} />
                </div>
                <div className="mt-4 grid grid-cols-2 gap-2 text-xs text-cream-dim">
                  <span className="rounded-md bg-ink px-2 py-2">{text.publishedChapterCount} {publishedCount} {text.chapterUnit}</span>
                  <span className="rounded-md bg-ink px-2 py-2">{text.pendingChapterCount} {editChapters.length - publishedCount} {text.chapterUnit}</span>
                </div>
              </div>
            </aside>

            <section className="rounded-lg border border-ink-border bg-ink-light">
              <div className="flex flex-col gap-3 border-b border-ink-border p-4 lg:flex-row lg:items-center lg:justify-between">
                <div>
                  <h2 className="flex items-center gap-2 text-base font-semibold text-cream"><SquarePen size={17} className="text-coral" />{text.chapterQueue}</h2>
                  <p className="mt-1 text-sm text-cream-dim">{text.queueSubtitle}</p>
                </div>
                <div className="flex items-center gap-2">
                  <button onClick={selectAll} className="rounded-lg border border-ink-border bg-ink px-3 py-2 text-xs text-cream-dim transition-colors hover:border-coral/40 hover:text-cream">{text.allOnline}</button>
                  <button onClick={deselectAll} className="rounded-lg border border-ink-border bg-ink px-3 py-2 text-xs text-cream-dim transition-colors hover:border-coral/40 hover:text-cream">{text.allDraft}</button>
                </div>
              </div>

              <div className="p-4">
                {editChapters.length === 0 ? (
                  <p className="py-12 text-center text-sm text-warm-gray">{text.noChapters}</p>
                ) : (
                  <div className="space-y-2">
                    {editChapters.map((chapter, index) => (
                      <div key={chapter.id} className={`grid gap-3 rounded-lg border p-3 transition-colors lg:grid-cols-[48px_96px_1fr_92px_48px_86px] lg:items-center ${chapter.is_published ? 'border-coral/30 bg-coral/5' : 'border-ink-border bg-ink'}`}>
                        <div className="flex items-center gap-1 lg:flex-col">
                          <button onClick={() => moveChapter(index, -1)} disabled={index === 0} className="text-warm-gray transition-colors hover:text-cream disabled:cursor-not-allowed disabled:opacity-20"><ChevronRight size={14} className="rotate-[-90deg]" /></button>
                          <button onClick={() => moveChapter(index, 1)} disabled={index === editChapters.length - 1} className="text-warm-gray transition-colors hover:text-cream disabled:cursor-not-allowed disabled:opacity-20"><ChevronRight size={14} className="rotate-90" /></button>
                        </div>
                        <span className="text-sm font-medium text-cream-dim">{chapterTitle(chapter.chapter_number)}</span>
                        <input value={chapter.display_title || ''} onChange={(event) => updateDisplayTitle(chapter.id, event.target.value)} placeholder={chapterTitle(chapter.chapter_number)} className="min-w-0 rounded-md border border-ink-border bg-ink-lighter px-3 py-2 text-sm text-cream placeholder-ink-muted focus:border-coral focus:outline-none" />
                        <span className={`w-fit rounded-md border px-2 py-1 text-xs ${chapter.is_published ? 'border-success/30 bg-success/10 text-success' : 'border-ink-border bg-ink-lighter text-cream-dim'}`}>{chapter.is_published ? text.publishedChapterCount : text.draft}</span>
                        <label className="flex items-center gap-2 text-xs text-cream-dim">
                          <input type="checkbox" checked={chapter.is_published} onChange={() => toggleChapter(chapter.id)} className="h-4 w-4 rounded border-ink-muted bg-ink-lighter text-coral focus:border-coral" />{text.visible}
                        </label>
                        <button onClick={() => openReader(chapter.id)} className="flex items-center justify-center gap-1 rounded-md px-2 py-2 text-xs text-cream-dim transition-colors hover:bg-coral-light/10 hover:text-coral" title={text.preview}>
                          <Eye size={14} />{text.preview}
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </section>
          </div>
        </div>
      </div>
    );
  }

  if (view === 'reader' && selectedWork) {
    const chapters = selectedWork.chapters;
    const nextDisabled = currentReaderIdx >= chapters.length - 1;
    const prevDisabled = currentReaderIdx <= 0;

    return (
      <div className="flex-1 flex flex-col min-h-0 bg-paper-base">
        <div className="flex items-center justify-between border-b border-paper-border bg-paper-surface px-3 py-2.5 md:px-5 shrink-0">
          <button onClick={closeReader} className="flex items-center gap-1.5 text-sm text-cream-dim transition-colors hover:text-cream"><ChevronLeft size={18} />{text.back}</button>
          <div className="flex min-w-0 items-center gap-2 px-3">
            <span className="truncate text-sm font-medium text-cream">{selectedWork.title}</span>
            <span className="text-warm-gray">/</span>
            <span className="truncate text-sm text-cream-dim">{readerChapterInfo?.display_title || (readerChapterInfo?.chapter_number ? chapterTitle(readerChapterInfo.chapter_number) : '')}</span>
          </div>
          <select value={readerChapterId ?? ''} onChange={(event) => { const id = Number(event.target.value); if (id) openReader(id); }} className="max-w-[150px] rounded-md border border-ink-border bg-ink-lighter px-2 py-1 text-xs text-cream-dim focus:border-coral focus:outline-none">
            {chapters.map((chapter) => <option key={chapter.id} value={chapter.id}>{chapterTitle(chapter.chapter_number)} {chapter.display_title || ''}</option>)}
          </select>
        </div>
        <div className="flex-1 overflow-y-auto">
          {readerLoading ? (
            <div className="flex h-full items-center justify-center"><Loader2 size={28} className="animate-spin text-coral" /></div>
          ) : readerImages.length === 0 ? (
            <div className="flex h-full items-center justify-center">
              <div className="text-center text-warm-gray">
                <ImageIcon size={64} className="mx-auto mb-4 opacity-20" />
                <p className="text-base">{text.noManga}</p>
                <button onClick={closeReader} className="mt-3 text-sm text-coral transition-colors hover:text-coral-light">{text.backToManage}</button>
              </div>
            </div>
          ) : (
            <div className="mx-auto max-w-3xl">
              {readerImages.map((image) => <img key={image.id} src={mangaImageUrl(image.image_path) || ''} alt={`${text.chapterPrefix} ${image.image_number} ${text.pageUnit}`} className="block w-full" loading="lazy" />)}
            </div>
          )}
        </div>
        <div className="flex items-center justify-center gap-4 border-t border-ink-border bg-ink px-4 py-3 md:gap-8 shrink-0">
          <button onClick={() => navigateReaderChapter(-1)} disabled={prevDisabled} className="flex items-center gap-1.5 rounded-lg bg-ink-lighter px-4 py-2 text-sm font-medium text-cream-dim transition-colors hover:bg-ink-surface disabled:cursor-not-allowed disabled:opacity-30"><ChevronLeft size={16} />{text.prevChapter}</button>
          <span className="text-xs text-warm-gray">{currentReaderIdx + 1} / {chapters.length}</span>
          <button onClick={() => navigateReaderChapter(1)} disabled={nextDisabled} className="flex items-center gap-1.5 rounded-lg bg-ink-lighter px-4 py-2 text-sm font-medium text-cream-dim transition-colors hover:bg-ink-surface disabled:cursor-not-allowed disabled:opacity-30">{text.nextChapter}<ChevronRight size={16} /></button>
        </div>
      </div>
    );
  }

  return null;
}
