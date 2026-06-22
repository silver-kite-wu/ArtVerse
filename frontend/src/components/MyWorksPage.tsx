import { useCallback, useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Eye, Globe, Loader2, Search, Send, ArrowUpDown, Image as ImageIcon } from 'lucide-react';
import {
  listMyWorks, publishStory, updateChapterOrder, getChapter,
  mangaImageUrl, refImageUrl,
  type MyWork, type MyWorkChapter, type Chapter, type MangaImage,
} from '../api';

type View = 'list' | 'detail' | 'reader';

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
  const [sortOrder, setSortOrder] = useState<'newest' | 'oldest'>('newest');

  const loadWorks = useCallback(async () => {
    try { const data = await listMyWorks(); setWorks(data); } catch (e: any) { console.error(e); } finally { setLoading(false); }
  }, []);

  useEffect(() => { loadWorks(); }, [loadWorks]);

  const openDetail = (work: MyWork) => { setSelectedWork(work); setEditChapters(work.chapters.map(ch => ({ ...ch }))); setView('detail'); };
  const backToList = async () => { setView('list'); setSelectedWork(null); await loadWorks(); };
  const toggleChapter = (chapterId: number) => { setEditChapters(prev => prev.map(ch => ch.id === chapterId ? { ...ch, is_published: !ch.is_published } : ch)); };
  const updateDisplayTitle = (chapterId: number, title: string) => { setEditChapters(prev => prev.map(ch => ch.id === chapterId ? { ...ch, display_title: title } : ch)); };
  const moveChapter = (idx: number, dir: -1 | 1) => { const next = [...editChapters]; const target = idx + dir; if (target < 0 || target >= next.length) return; [next[idx], next[target]] = [next[target], next[idx]]; setEditChapters(next); };
  const selectAll = () => setEditChapters(prev => prev.map(ch => ({ ...ch, is_published: true })));
  const deselectAll = () => setEditChapters(prev => prev.map(ch => ({ ...ch, is_published: false })));

  const handleSave = async () => {
    if (!selectedWork) return; setSaving(true);
    try {
      const sel = editChapters.filter(c => c.is_published).map(c => c.id);
      await publishStory(selectedWork.id, sel.length > 0, sel);
      await updateChapterOrder(selectedWork.id, editChapters.map((ch, i) => ({ chapter_id: ch.id, display_order: i, display_title: ch.display_title || undefined })));
      await loadWorks();
      const updated = (await listMyWorks()).find(w => w.id === selectedWork.id);
      if (updated) { setSelectedWork(updated); setEditChapters(updated.chapters.map(ch => ({ ...ch }))); }
    } catch (e: any) { alert('保存失败: ' + (e.message || '未知错误')); } finally { setSaving(false); }
  };

  const openReader = async (chapterId: number) => {
    setReaderChapterId(chapterId); setReaderImages([]); setReaderLoading(true); setView('reader');
    try {
      const ch: Chapter = await getChapter(chapterId);
      setReaderChapterInfo({ chapter_number: ch.chapter_number, display_title: '' });
      setReaderImages((ch.images || []).sort((a, b) => a.image_number - b.image_number));
      if (selectedWork) {
        const ec = selectedWork.chapters.find(c => c.id === chapterId);
        if (ec) setReaderChapterInfo({ chapter_number: ch.chapter_number, display_title: ec.display_title || ('第' + ch.chapter_number + '话') });
      }
    } catch { setReaderImages([]); } finally { setReaderLoading(false); }
  };

  const closeReader = () => { setView('detail'); setReaderChapterId(null); setReaderImages([]); };
  const navigateReaderChapter = (dir: -1 | 1) => { if (!selectedWork) return; const chapters = selectedWork.chapters; const idx = chapters.findIndex(c => c.id === readerChapterId); const next = idx + dir; if (next >= 0 && next < chapters.length) openReader(chapters[next].id); };
  const currentReaderIdx = selectedWork?.chapters.findIndex(c => c.id === readerChapterId) ?? -1;

  const filteredWorks = works
    .filter(w => !searchQuery || w.title.toLowerCase().includes(searchQuery.toLowerCase()))
    .sort((a, b) => { const da = a.created_at || ''; const db = b.created_at || ''; return sortOrder === 'newest' ? db.localeCompare(da) : da.localeCompare(db); });

  const coverUrl = (work: MyWork) => work.cover_image ? refImageUrl(work.cover_image) : null;

  if (loading) return <div className="flex-1 flex items-center justify-center"><Loader2 size={28} className="animate-spin text-coral" /></div>;

  if (view === 'list') {
    return (
      <div className="flex-1 flex flex-col min-h-0">
        <div className="flex items-center gap-3 px-4 md:px-6 py-4 border-b border-ink-border shrink-0">
          <h2 className="text-lg font-semibold text-cream flex items-center gap-2 shrink-0"><Globe size={20} className="text-coral" />作品管理</h2>
          <p className="text-xs text-warm-gray ml-1">{works.length} 部作品</p>
          <div className="flex-1" />
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-cream-dim" />
            <input value={searchQuery} onChange={e => setSearchQuery(e.target.value)} placeholder="搜索作品..." className="w-40 md:w-56 pl-8 pr-3 py-2 bg-ink-lighter border border-ink-border rounded-lg text-sm text-cream placeholder-ink-muted focus:outline-none focus:border-coral" />
          </div>
          <div className="relative">
            <select value={sortOrder} onChange={e => setSortOrder(e.target.value as 'newest' | 'oldest')} className="appearance-none pl-3 pr-8 py-2 bg-ink-lighter border border-ink-border rounded-lg text-sm text-cream-dim focus:outline-none focus:border-coral">
              <option value="newest">最新</option>
              <option value="oldest">最旧</option>
            </select>
            <ArrowUpDown size={12} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-cream-dim pointer-events-none" />
          </div>
        </div>
        <div className="flex-1 overflow-y-auto p-4 md:p-6">
          {filteredWorks.length === 0 ? (
            <div className="flex items-center justify-center h-full"><div className="text-center text-warm-gray"><ImageIcon size={48} className="mx-auto mb-3 opacity-30" /><p className="text-sm">{searchQuery ? '没有匹配的作品' : '还没有作品，去工作区创建吧'}</p></div></div>
          ) : (
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4">
              {filteredWorks.map(work => {
                const cover = coverUrl(work);
                const publishedCount = work.chapters.filter(c => c.is_published).length;
                return (
                  <div key={work.id} onClick={() => openDetail(work)} className="group cursor-pointer rounded-xl overflow-hidden border border-ink-border bg-ink-light/60 hover:border-coral/30 hover:bg-ink-light/80 transition-all duration-200 hover:scale-[1.02]">
                    <div className="aspect-[3/4] bg-ink-lighter relative overflow-hidden">
                      {cover ? <img src={cover} alt={work.title} className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" loading="lazy" /> : <div className="w-full h-full flex items-center justify-center"><ImageIcon size={40} className="text-warm-gray" /></div>}
                      {work.is_published && <div className="absolute top-2 left-2 px-2 py-0.5 rounded-full bg-green-600/80 text-cream text-[10px] font-medium flex items-center gap-1"><Globe size={10} /> 已发布</div>}
                    </div>
                    <div className="p-3">
                      <h3 className="font-semibold text-sm text-cream truncate">{work.title}</h3>
                      <p className="text-xs text-cream-dim mt-0.5 line-clamp-2 leading-relaxed">{work.description || '暂无简介'}</p>
                      <div className="flex items-center gap-2 mt-2 text-[11px] text-cream-dim"><span>{work.chapters.length} 话</span>{publishedCount > 0 && <span className="text-green-400">{publishedCount} 已发布</span>}</div>
                    </div>
                  </div>
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
    const publishedCount = editChapters.filter(c => c.is_published).length;
    return (
      <div className="flex-1 flex flex-col min-h-0">
        <div className="flex items-center justify-between px-4 md:px-6 py-3 border-b border-ink-border shrink-0">
          <button onClick={backToList} className="flex items-center gap-1.5 text-cream-dim hover:text-cream transition-colors">
            <ChevronLeft size={18} /><span className="text-sm">返回作品列表</span>
          </button>
          {selectedWork.is_published && <span className="flex items-center gap-1 px-2 py-0.5 rounded-full bg-green-900/50 text-green-400 border border-green-800 text-xs"><Globe size={12} /> 已发布</span>}
        </div>
        <div className="px-4 md:px-6 py-5 border-b border-ink-border shrink-0">
          <div className="flex gap-4 md:gap-6">
            <div className="w-24 md:w-36 aspect-[3/4] rounded-lg overflow-hidden border border-ink-border bg-ink-lighter shrink-0">
              {cover ? <img src={cover} alt={selectedWork.title} className="w-full h-full object-cover" /> : <div className="w-full h-full flex items-center justify-center"><ImageIcon size={32} className="text-warm-gray" /></div>}
            </div>
            <div className="min-w-0">
              <h1 className="text-xl md:text-2xl font-bold text-cream">{selectedWork.title}</h1>
              <p className="text-sm text-cream-dim mt-1.5 leading-relaxed line-clamp-3">{selectedWork.description || '暂无简介'}</p>
              <div className="flex flex-wrap items-center gap-3 mt-3 text-xs text-cream-dim">
                <span>📖 {selectedWork.chapters.length} 话</span>
                <span>✅ {publishedCount} 已发布</span>
                {selectedWork.created_at && <span>📅 {new Date(selectedWork.created_at).toLocaleDateString('zh-CN')}</span>}
              </div>
            </div>
          </div>
        </div>
        <div className="flex-1 overflow-y-auto">
          <div className="px-4 md:px-6 py-3">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-3">
                <button onClick={selectAll} className="text-xs text-cream-dim hover:text-coral transition-colors flex items-center gap-1"><span className="text-green-400">☑</span> 全选</button>
                <button onClick={deselectAll} className="text-xs text-cream-dim hover:text-cream-dim transition-colors flex items-center gap-1"><span className="text-warm-gray">☐</span> 取消全选</button>
              </div>
              <span className="text-xs text-warm-gray">{publishedCount} / {editChapters.length} 已选</span>
            </div>
            {editChapters.length === 0 ? (
              <p className="text-sm text-warm-gray text-center py-12">暂无章节，去工作区创建吧</p>
            ) : (
              <div className="space-y-1">
                {editChapters.map((ch, idx) => (
                  <div key={ch.id} className={`flex items-center gap-2 md:gap-3 px-3 py-2.5 rounded-lg border transition-colors ${ch.is_published ? 'border-coral/20 bg-violet-500/5' : 'border-ink-border bg-ink-light/30 hover:border-ink-border'}`}>
                    <div className="flex flex-col shrink-0">
                      <button onClick={() => moveChapter(idx, -1)} disabled={idx === 0} className="text-warm-gray hover:text-cream-dim disabled:opacity-20 disabled:cursor-not-allowed leading-none"><ChevronRight size={12} className="rotate-[-90deg]" /></button>
                      <button onClick={() => moveChapter(idx, 1)} disabled={idx === editChapters.length - 1} className="text-warm-gray hover:text-cream-dim disabled:opacity-20 disabled:cursor-not-allowed leading-none"><ChevronRight size={12} className="rotate-[90deg]" /></button>
                    </div>
                    <span className="text-xs text-cream-dim w-12 shrink-0">Ch.{ch.chapter_number}</span>
                    <input value={ch.display_title || ''} onChange={e => updateDisplayTitle(ch.id, e.target.value)} placeholder={'第' + ch.chapter_number + '话'} className="flex-1 bg-ink-lighter border border-ink-border rounded-md px-3 py-1.5 text-sm text-cream placeholder-ink-muted focus:outline-none focus:border-coral" />
                    <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium shrink-0 ${ch.is_published ? 'bg-green-900/40 text-green-400 border border-green-800/50' : 'bg-ink-lighter text-cream-dim border border-ink-border'}`}>{ch.is_published ? '已发布' : '草稿'}</span>
                    <label className="flex items-center cursor-pointer shrink-0"><input type="checkbox" checked={ch.is_published} onChange={() => toggleChapter(ch.id)} className="w-4 h-4 rounded border-ink-muted bg-ink-lighter text-coral focus:border-coral" /></label>
                    <button onClick={() => openReader(ch.id)} className="flex items-center gap-1 px-2 py-1.5 text-xs text-cream-dim hover:text-coral hover:bg-coral-light/10 rounded transition-colors shrink-0" title="预览漫画"><Eye size={13} /><span className="hidden md:inline">预览</span></button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
        <div className="flex items-center justify-end gap-3 px-4 md:px-6 py-4 border-t border-ink-border shrink-0">
          <button onClick={handleSave} disabled={saving} className="flex items-center gap-2 px-5 py-2.5 text-sm font-medium rounded-lg bg-coral hover:bg-coral-light text-cream disabled:opacity-40 transition-colors">{saving ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}保存并发布</button>
        </div>
      </div>
    );
  }
  if (view === 'reader' && selectedWork) {
    const chapters = selectedWork.chapters;
    const nextDisabled = currentReaderIdx >= chapters.length - 1;
    const prevDisabled = currentReaderIdx <= 0;
    return (
      <div className="flex-1 flex flex-col min-h-0 bg-black">
        <div className="flex items-center justify-between px-3 md:px-5 py-2.5 border-b border-ink-border bg-ink shrink-0">
          <button onClick={closeReader} className="flex items-center gap-1.5 text-cream-dim hover:text-cream transition-colors"><ChevronLeft size={18} /><span className="text-sm">返回</span></button>
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-cream truncate">{selectedWork.title}</span>
            <span className="text-warm-gray">·</span>
            <span className="text-sm text-cream-dim truncate">{readerChapterInfo?.display_title || (readerChapterInfo?.chapter_number ? '第' + readerChapterInfo.chapter_number + '话' : '')}</span>
          </div>
          <select value={readerChapterId ?? ''} onChange={e => { const id = Number(e.target.value); if (id) openReader(id); }} className="bg-ink-lighter border border-ink-border rounded-md px-2 py-1 text-xs text-cream-dim focus:outline-none focus:border-coral max-w-[140px]">
            {chapters.map(ch => <option key={ch.id} value={ch.id}>Ch.{ch.chapter_number} {ch.display_title || ''}</option>)}
          </select>
        </div>
        <div className="flex-1 overflow-y-auto">
          {readerLoading ? (
            <div className="flex items-center justify-center h-full"><Loader2 size={28} className="animate-spin text-coral" /></div>
          ) : readerImages.length === 0 ? (
            <div className="flex items-center justify-center h-full"><div className="text-center text-warm-gray"><ImageIcon size={64} className="mx-auto mb-4 opacity-20" /><p className="text-base">该话暂未生成漫画</p><button onClick={closeReader} className="mt-3 text-sm text-coral hover:text-coral-light transition-colors">返回管理</button></div></div>
          ) : (
            <div className="max-w-3xl mx-auto">
              {readerImages.map(img => <img key={img.id} src={mangaImageUrl(img.image_path) || ''} alt={'第 ' + img.image_number + ' 页'} className="w-full block" loading="lazy" />)}
            </div>
          )}
        </div>
        <div className="flex items-center justify-center gap-4 md:gap-8 px-4 py-3 border-t border-ink-border bg-ink shrink-0">
          <button onClick={() => navigateReaderChapter(-1)} disabled={prevDisabled} className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium bg-ink-lighter hover:bg-ink-surface text-cream-dim disabled:opacity-30 disabled:cursor-not-allowed transition-colors"><ChevronLeft size={16} />上一话</button>
          <span className="text-xs text-warm-gray">{currentReaderIdx + 1} / {chapters.length}</span>
          <button onClick={() => navigateReaderChapter(1)} disabled={nextDisabled} className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium bg-ink-lighter hover:bg-ink-surface text-cream-dim disabled:opacity-30 disabled:cursor-not-allowed transition-colors">下一话<ChevronRight size={16} /></button>
        </div>
      </div>
    );
  }

  return null;
}