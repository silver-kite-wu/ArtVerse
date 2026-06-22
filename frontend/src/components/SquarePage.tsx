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

  if (detailLoading) return <div className="flex-1 flex items-center justify-center"><div className="animate-spin rounded-full h-8 w-8 border-2 border-ink-border border-t-coral"/></div>;
  if (selected) return (
    <div className="flex-1 flex flex-col min-h-0 overflow-y-auto">
      <div className="sticky top-0 glass backdrop-blur-sm border-b border-ink-border px-6 py-3 flex items-center gap-4 z-10">
        <button onClick={()=>setSelected(null)} className="text-cream-dim hover:text-cream flex items-center gap-1"><ChevronLeft size={18}/>Back</button>
        <h2 className="text-lg font-semibold text-cream">{selected.title}</h2>
      </div>
      <div className="p-6 max-w-5xl mx-auto w-full">
        {selected.cover_url && <img src={refImageUrl(selected.cover_url)} alt={selected.title} className="w-full max-h-64 object-cover rounded-xl mb-6"/>}
        <p className="text-cream-dim mb-8">{selected.description}</p>
        <h3 className="text-lg font-medium text-cream mb-4">Chapters</h3>
        <div className="space-y-6">
          {selected.chapters.map(ch=>(
            <div key={ch.id} className="border border-ink-border rounded-xl p-4">
              <h4 className="text-md font-medium text-cream mb-3">{ch.display_title}</h4>
              {ch.images.length>0 ? <div className="grid grid-cols-2 md:grid-cols-4 gap-3">{ch.images.map(img=><img key={img.id} src={mangaImageUrl(img.image_url)} alt={'Panel '+img.image_number} className="rounded-lg border border-ink-border w-full aspect-[2/3] object-cover"/>)}</div> : <p className="text-sm text-warm-gray">No manga yet</p>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );

  return (
    <div className="flex-1 flex flex-col min-h-0 overflow-y-auto">
      <div className="sticky top-0 glass backdrop-blur-sm border-b border-ink-border px-6 py-4 z-10 space-y-3">
        <h2 className="text-xl font-bold text-cream flex items-center gap-2"><BookOpenText size={22} className="text-coral"/>Square</h2>
        <div className="flex gap-2">
          <div className="flex-1 relative">
            <input value={searchInput} onChange={e=>setSearchInput(e.target.value)} onKeyDown={e=>e.key==='Enter'&&handleSearch()} placeholder="Search..." className="w-full bg-ink-lighter border border-ink-border rounded-lg pl-10 pr-4 py-2 text-sm text-cream placeholder-ink-muted focus:outline-none focus:border-coral"/>
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-cream-dim"/>
            {searchInput && <button onClick={clearSearch} className="absolute right-3 top-1/2 -translate-y-1/2 text-cream-dim hover:text-cream-dim"><X size={14}/></button>}
          </div>
          <button onClick={handleSearch} className="px-4 py-2 bg-coral hover:bg-coral-light text-cream text-sm font-medium rounded-lg">Search</button>
        </div>
      </div>
      {loading ? <div className="flex-1 flex items-center justify-center"><div className="animate-spin rounded-full h-8 w-8 border-2 border-ink-border border-t-coral"/></div> :
       stories.length===0 ? <div className="flex-1 flex items-center justify-center text-cream-dim">{search?'No results':'No published works'}</div> :
       <><div className="p-6 grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">{stories.map(s=><button key={s.id} onClick={()=>openDetail(s.id)} className="text-left border border-ink-border rounded-xl overflow-hidden hover:border-coral/30 hover:bg-ink-light/50 transition-all group"><div className="aspect-[3/2] bg-ink-light overflow-hidden">{s.cover_url?<img src={refImageUrl(s.cover_url)} alt={s.title} className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"/>:<div className="w-full h-full flex items-center justify-center text-ink-muted"><BookOpenText size={40}/></div>}</div><div className="p-3"><h3 className="font-medium text-sm text-cream truncate">{s.title}</h3><p className="text-xs text-cream-dim mt-1 line-clamp-2">{s.description}</p></div></button>)}</div>
       {totalPages>1 && <div className="flex items-center justify-center gap-2 py-4">{Array.from({length:totalPages},(_,i)=><button key={i} onClick={()=>{setPage(i);load(i,search)}} className={'w-8 h-8 rounded-lg text-sm font-medium '+(i===page?'bg-coral text-cream':'bg-ink-lighter text-cream-dim hover:bg-ink-surface')}>{i+1}</button>)}</div>}</>
      }
    </div>
  );
}