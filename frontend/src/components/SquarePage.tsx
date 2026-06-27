import { useEffect, useState } from 'react';
import { Search, X, ChevronLeft, BookOpenText } from 'lucide-react';
import { listSquareStories, getSquareStoryDetail, refImageUrl, mangaImageUrl, type SquareStory, type SquareStoryDetail } from '../api';

export default function SquarePage() {
  const [stories, setStories] = useState<SquareStory[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [selected, setSelected] = useState<SquareStoryDetail|null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const load = async (p:number, s:string) => { setLoading(true); try { const r = await listSquareStories(p,12,s||undefined); setStories(r.content); setTotalPages(r.total_pages); } catch(e){ console.error(e); } finally { setLoading(false); } };
  useEffect(()=>{load(0,search)},[search]);

  const handleSearch = () => { setPage(0); setSearch(searchInput.trim()); };
  const clearSearch = () => { setSearchInput(''); setSearch(''); setPage(0); };
  const openDetail = async (id:number) => { setDetailLoading(true); try { setSelected(await getSquareStoryDetail(id)); } catch(e){ console.error(e); } finally { setDetailLoading(false); } };

  if (detailLoading) return <div className="flex-1 flex items-center justify-center"><div className="animate-spin rounded-full h-8 w-8 border-2 border-paper-border border-t-vermilion"/></div>;
  if (selected) return (
    <div className="flex-1 flex flex-col min-h-0 overflow-y-auto">
      <div className="sticky top-0 glass backdrop-blur-sm border-b border-paper-border px-6 py-3 flex items-center gap-4 z-10">
        <button onClick={()=>setSelected(null)} className="text-sumi-dim hover:text-vermilion flex items-center gap-1"><ChevronLeft size={18}/>返回</button>
        <h2 className="text-lg font-semibold text-sumi">{selected.title}</h2>
      </div>
      <div className="p-6 max-w-5xl mx-auto w-full">
        {selected.cover_url && <img src={refImageUrl(selected.cover_url)} alt={selected.title} className="w-full max-h-64 object-cover rounded-lg mb-6"/>}
        <p className="text-sumi-dim mb-8">{selected.description}</p>
        <h3 className="text-lg font-medium text-sumi mb-4">章节列表</h3>
        <div className="space-y-6">
          {selected.chapters.map(ch=>(
            <div key={ch.id} className="border border-paper-border rounded-lg p-4">
              <h4 className="text-md font-medium text-sumi mb-3">{ch.display_title}</h4>
              {ch.images.length>0 ? <div className="grid grid-cols-2 md:grid-cols-4 gap-3">{ch.images.map(img=><img key={img.id} src={mangaImageUrl(img.image_url)} alt={'Panel '+img.image_number} className="rounded-md border border-paper-border w-full aspect-[2/3] object-cover"/>)}</div> : <p className="text-sm text-sumi-faint">暂无漫画</p>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );

  return (
    <div className="flex-1 flex flex-col min-h-0 overflow-y-auto">
      <div className="sticky top-0 glass backdrop-blur-sm border-b border-paper-border px-6 py-4 z-10 space-y-3">
        <h2 className="text-xl font-bold text-sumi flex items-center gap-2"><BookOpenText size={22} className="text-vermilion"/>发现作品</h2>
        <div className="flex gap-2">
          <div className="flex-1 relative">
            <input value={searchInput} onChange={e=>setSearchInput(e.target.value)} onKeyDown={e=>e.key==='Enter'&&handleSearch()} placeholder="搜索作品..." className="w-full bg-paper-surface border border-paper-border rounded-md pl-10 pr-4 py-2 text-sm text-sumi placeholder-sumi-faint focus:outline-none focus:border-vermilion"/>
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-sumi-faint"/>
            {searchInput && <button onClick={clearSearch} className="absolute right-3 top-1/2 -translate-y-1/2 text-sumi-faint hover:text-sumi-dim"><X size={14}/></button>}
          </div>
          <button onClick={handleSearch} className="px-4 py-2 bg-vermilion hover:bg-vermilion-hover text-white text-sm font-medium rounded-md">搜索</button>
        </div>
      </div>
      {loading ? <div className="flex-1 flex items-center justify-center"><div className="animate-spin rounded-full h-8 w-8 border-2 border-paper-border border-t-vermilion"/></div> :
       stories.length===0 ? <div className="flex-1 flex items-center justify-center text-sumi-dim">{search?'没有搜索结果':'暂无已发布作品'}</div> :
       <><div className="p-6 grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">{stories.map(s=><button key={s.id} onClick={()=>openDetail(s.id)} className="text-left panel-frame overflow-hidden transition-all group"><div className="aspect-[3/4] bg-paper-surface overflow-hidden">{s.cover_url?<img src={refImageUrl(s.cover_url)} alt={s.title} className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"/>:<div className="w-full h-full flex items-center justify-center text-sumi-faint"><BookOpenText size={28}/></div>}</div><div className="p-2.5"><h3 className="font-medium text-sm text-sumi truncate">{s.title}</h3><p className="text-xs text-sumi-dim mt-1 line-clamp-2">{s.description}</p></div></button>)}</div>
       {totalPages>1 && <div className="flex items-center justify-center gap-2 py-4">{Array.from({length:totalPages},(_,i)=><button key={i} onClick={()=>{setPage(i);load(i,search)}} className={'w-8 h-8 rounded-md text-sm font-medium transition-colors '+(i===page?'bg-vermilion text-white':'bg-paper-surface text-sumi-dim hover:bg-paper-border')}>{i+1}</button>)}</div>}</>
      }
    </div>
  );
}