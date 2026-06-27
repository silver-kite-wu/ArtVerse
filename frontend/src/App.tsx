import { useEffect, useState } from 'react';
import {
  BookOpenText,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  FileText,
  Globe,
  Image,
  KeyRound,
  LogIn,
  LogOut,
  MessageSquare,
  Paintbrush,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Sparkles,
  Trash2,
  X,
} from 'lucide-react';
import ChatPanel from './components/ChatPanel';
import MangaPanel from './components/MangaPanel';
import MangaAgentPage from './components/MangaAgentPage';
import HomePage from './components/HomePage';
import LoginPage from './components/LoginPage';
import SquarePage from './components/SquarePage';
import ImageGenPage from './components/ImageGenPage';
import MyWorksPage from './components/MyWorksPage';
import {
  listChapters,
  createNextChapter,
  deleteChapter,
  getChapter,
  type Chapter,
  getApiKeySettings,
  saveApiKeySettings,
  clearApiKeySettings,
  saveUserApiKey,
  DEEPSEEK_USAGE_URL,
  IMAGE2_CONSOLE_URL,
  isAuthenticated,
  logoutUser,
} from './api';

type View = 'home' | 'square' | 'workspace' | 'editor' | 'imagegen' | 'myworks';
type MobileTab = 'chat' | 'manga';

const LS_STORY_ID = 'lorevista.currentStoryId';
const LS_CHAPTER_ID = 'lorevista.currentChapterId';
const LS_CHAPTER_IDX = 'lorevista.currentChapterIdx';
const MOBILE_BREAKPOINT = 1024;

function useIsMobile() {
  const read = () =>
    navigator.maxTouchPoints > 0 ||
    window.matchMedia('(any-pointer:coarse)').matches ||
    window.matchMedia('(max-width:' + MOBILE_BREAKPOINT + 'px)').matches ||
    window.matchMedia('(pointer:coarse)').matches;
  const [m, setM] = useState(read);
  useEffect(() => {
    const w = window.matchMedia('(max-width:' + MOBILE_BREAKPOINT + 'px)');
    const t = window.matchMedia('(pointer:coarse)');
    const a = window.matchMedia('(any-pointer:coarse)');
    let f = 0;
    const s = () => {
      cancelAnimationFrame(f);
      f = requestAnimationFrame(() => setM(read()));
    };
    w.addEventListener('change', s);
    t.addEventListener('change', s);
    a.addEventListener('change', s);
    window.addEventListener('resize', s);
    return () => {
      cancelAnimationFrame(f);
      w.removeEventListener('change', s);
      t.removeEventListener('change', s);
      a.removeEventListener('change', s);
      window.removeEventListener('resize', s);
    };
  }, []);
  return m;
}

function clearWorkspaceState() {
  localStorage.removeItem(LS_STORY_ID);
  localStorage.removeItem(LS_CHAPTER_ID);
  localStorage.removeItem(LS_CHAPTER_IDX);
}

function ApiKeySettingsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [dk, setDk] = useState('');
  const [ik, setIk] = useState('');
  const [ck, setCk] = useState('');

  useEffect(() => {
    if (!open) return;
    const s = getApiKeySettings();
    setDk(s.deepseekApiKey);
    setIk(s.imageApiKey);
    setCk(s.cozeApiKey);
  }, [open]);

  if (!open) return null;

  const handleSave = async () => {
    saveApiKeySettings({ deepseekApiKey: dk, imageApiKey: ik, cozeApiKey: ck });
    const sync = async (p: string, k: string) => {
      if (!k) return;
      try {
        await saveUserApiKey(p, k);
      } catch {
        return;
      }
    };
    await Promise.all([sync('deepseek', dk), sync('image2', ik), sync('coze', ck)]);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-sumi/40 backdrop-blur-sm" onClick={onClose}>
      <div className="m-4 w-full max-w-md space-y-5 rounded-2xl border border-paper-border bg-paper-raised p-6 shadow-modal animate-fade-in" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h2 className="flex items-center gap-2 text-lg font-semibold text-sumi">
            <KeyRound size={18} className="text-kinpaku" />
            API 密钥
          </h2>
          <button onClick={onClose} className="text-sumi-faint hover:text-sumi-dim transition-colors" aria-label="关闭">
            <X size={18} />
          </button>
        </div>
        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm text-sumi-dim">DeepSeek 密钥</label>
            <input type="password" value={dk} onChange={(e) => setDk(e.target.value)} placeholder="sk-..." className="w-full rounded-md border border-paper-border bg-paper-base px-3 py-2 text-sm text-sumi placeholder-sumi-faint focus:border-vermilion focus:outline-none transition-colors" />
            <a href={DEEPSEEK_USAGE_URL} target="_blank" rel="noopener" className="mt-1 inline-flex items-center gap-1 text-xs text-aizuri hover:text-aizuri/80 transition-colors">
              <ExternalLink size={10} />
              获取密钥
            </a>
          </div>
          <div>
            <label className="mb-1 block text-sm text-sumi-dim">绘图密钥</label>
            <input type="password" value={ik} onChange={(e) => setIk(e.target.value)} placeholder="sk-..." className="w-full rounded-md border border-paper-border bg-paper-base px-3 py-2 text-sm text-sumi placeholder-sumi-faint focus:border-vermilion focus:outline-none transition-colors" />
            <a href={IMAGE2_CONSOLE_URL} target="_blank" rel="noopener" className="mt-1 inline-flex items-center gap-1 text-xs text-aizuri hover:text-aizuri/80 transition-colors">
              <ExternalLink size={10} />
              获取密钥
            </a>
          </div>
          <div>
            <label className="mb-1 block text-sm text-sumi-dim">Coze 密钥</label>
            <input type="password" value={ck} onChange={(e) => setCk(e.target.value)} placeholder="pat-..." className="w-full rounded-md border border-paper-border bg-paper-base px-3 py-2 text-sm text-sumi placeholder-sumi-faint focus:border-vermilion focus:outline-none transition-colors" />
          </div>
        </div>
        <div className="flex items-center justify-between pt-2">
          <button onClick={() => { if (!confirm('确定清空所有密钥吗？')) return; clearApiKeySettings(); setDk(''); setIk(''); setCk(''); }} disabled={!dk && !ik && !ck} className="text-xs text-vermilion hover:text-vermilion-hover disabled:opacity-30 transition-colors">
            清空全部
          </button>
          <div className="flex gap-2">
            <button onClick={onClose} className="px-4 py-2 text-sm text-sumi-dim hover:text-sumi transition-colors">取消</button>
            <button onClick={handleSave} className="rounded-md bg-vermilion px-4 py-2 text-sm font-medium text-white hover:bg-vermilion-hover transition-colors">保存</button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default function App() {
  const isMobile = useIsMobile();
  const [authenticated, setAuthenticated] = useState(false);
  const [authCheck, setAuthCheck] = useState(false);
  const [view, setView] = useState<View>('home');
  const [sidebarOpen, setSidebarOpen] = useState(!isMobile);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginMessage, setLoginMessage] = useState('请先登录后使用该功能');
  const [pendingView, setPendingView] = useState<View | null>(null);
  const [activeStoryId, setActiveStoryId] = useState<number | null>(null);
  const [currentIdx, setCurrentIdx] = useState(0);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [currentChapter, setCurrentChapter] = useState<Chapter | null>(null);
  const [creatingChapter, setCreatingChapter] = useState(false);
  const [mobileTab, setMobileTab] = useState<MobileTab>('chat');

  useEffect(() => {
    setAuthenticated(isAuthenticated());
    setAuthCheck(true);
  }, []);

  useEffect(() => {
    const handleExpired = () => {
      setAuthenticated(false);
      setLoginMessage('登录状态已过期，请重新登录');
      setPendingView(view === 'square' ? null : view);
      setLoginOpen(true);
      clearWorkspaceState();
      if (view !== 'square') setView('square');
    };
    window.addEventListener('artverse:auth-expired', handleExpired);
    return () => window.removeEventListener('artverse:auth-expired', handleExpired);
  }, [view]);

  if (!authCheck) {
    return <div className="flex h-dvh w-screen items-center justify-center bg-paper-base"><div className="h-8 w-8 animate-spin rounded-full border-2 border-paper-border border-t-vermilion" /></div>;
  }

  const loadChapters = async (storyId: number) => {
    try {
      const chs = await listChapters(storyId);
      setChapters(chs);
      const savedIdx = Number(localStorage.getItem(LS_CHAPTER_IDX) || '0');
      const idx = chs.length > 0 ? Math.min(savedIdx, chs.length - 1) : 0;
      setCurrentIdx(idx);
      if (chs.length > 0) {
        const ch = await getChapter(chs[idx].id);
        setCurrentChapter(ch);
        localStorage.setItem(LS_CHAPTER_ID, String(chs[idx].id));
      }
    } catch {
      return;
    }
  };

  const loadEditor = async (storyId: number) => {
    setActiveStoryId(storyId);
    localStorage.setItem(LS_STORY_ID, String(storyId));
    setView('editor');
    await loadChapters(storyId);
  };

  const unloadEditor = () => {
    setActiveStoryId(null);
    setChapters([]);
    setCurrentChapter(null);
    clearWorkspaceState();
  };

  const refreshCurrentChapter = async () => {
    if (currentChapter) setCurrentChapter(await getChapter(currentChapter.id));
  };

  const handleChapterRefresh = async (chapterId: number) => {
    try {
      const ch = await getChapter(chapterId);
      setCurrentChapter(ch);
      if (activeStoryId) {
        const chs = await listChapters(activeStoryId);
        setChapters(chs);
      }
    } catch {
      return;
    }
  };

  const setChapterByIndex = async (idx: number) => {
    if (idx < 0 || idx >= chapters.length || !activeStoryId) return;
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
    if (activeStoryId) {
      setCreatingChapter(true);
      try {
        await createNextChapter(activeStoryId);
        const chs = await listChapters(activeStoryId);
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
    }
  };

  const handleDelete = async () => {
    if (!currentChapter || chapters.length <= 1 || !activeStoryId) return;
    if (!confirm('确定删除本话吗？')) return;
    try {
      await deleteChapter(currentChapter.id);
      const chs = await listChapters(activeStoryId);
      setChapters(chs);
      const idx = Math.min(currentIdx, chs.length - 1);
      setCurrentIdx(idx);
      if (chs.length > 0) setCurrentChapter(await getChapter(chs[idx].id));
    } catch (e: any) {
      alert('操作失败：' + e.message);
    }
  };

  const requireLogin = (target?: View) => {
    setLoginMessage('请先登录后使用该功能');
    setPendingView(target || null);
    setLoginOpen(true);
  };

  const goView = (target: View) => {
    if (target !== 'square' && !authenticated) {
      requireLogin(target);
      return;
    }
    if (view === 'editor') unloadEditor();
    setView(target);
  };

  const handleAuthSuccess = () => {
    setAuthenticated(true);
    setLoginOpen(false);
    clearWorkspaceState();
    if (pendingView) {
      setView(pendingView);
      setPendingView(null);
    }
  };

  const openSettings = () => {
    if (!authenticated) {
      requireLogin();
      return;
    }
    setSettingsOpen(true);
  };

  const navItem = (icon: React.ReactNode, label: string, target: View) => (
    <button
      onClick={() => goView(target)}
      className={
        'relative w-full rounded-md px-3 py-2.5 text-left text-sm font-medium transition-all duration-200 ' +
        (view === target
          ? 'text-vermilion bg-vermilion-light/30 border-l-[3px] border-l-vermilion pl-[9px]'
          : 'text-sumi-dim hover:bg-paper-base hover:text-sumi border-l-[3px] border-l-transparent pl-[9px]')
      }
    >
      <span className="flex items-center gap-3">{icon}{sidebarOpen && <span>{label}</span>}</span>
    </button>
  );

  return (
    <div className="flex h-dvh w-screen overflow-hidden bg-paper-base text-sumi">
      <aside className={'flex shrink-0 flex-col border-r border-paper-border bg-paper-surface transition-all duration-300 ' + (sidebarOpen ? 'w-[220px]' : 'w-14') + ' ' + (isMobile && view === 'editor' ? 'hidden' : '')}>
        <div className="flex h-14 items-center justify-between border-b border-paper-border px-3">
          {sidebarOpen && (
            <span className="font-display text-base font-bold tracking-wide text-vermilion flex items-center gap-1.5">
              <Sparkles size={15} />
              ArtVerse
            </span>
          )}
          <button onClick={() => setSidebarOpen(!sidebarOpen)} className="ml-auto text-sumi-faint hover:text-sumi-dim transition-colors" aria-label="切换侧边栏">
            {sidebarOpen ? <PanelLeftClose size={16} /> : <PanelLeftOpen size={16} />}
          </button>
        </div>
        {sidebarOpen && <div className="brush-divider mx-3 mt-0" />}
        <nav className="flex flex-1 flex-col gap-0.5 px-2 py-3">
          {navItem(<Sparkles size={18} />, '创作工坊', 'home')}
          {navItem(<Globe size={18} />, '发现作品', 'square')}
          {navItem(<BookOpenText size={18} />, '我的故事', 'workspace')}
          {navItem(<FileText size={18} />, '章节管理', 'myworks')}
          {navItem(<Paintbrush size={18} />, '生图工坊', 'imagegen')}
        </nav>
        <div className="flex flex-col gap-0.5 border-t border-paper-border px-2 py-3">
          {authenticated ? (
            <>
              <button onClick={openSettings} className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm font-medium text-sumi-dim hover:bg-paper-base hover:text-sumi transition-colors">
                <KeyRound size={18} />
                {sidebarOpen && <span>API 密钥</span>}
              </button>
              <button onClick={() => { logoutUser(); setAuthenticated(false); unloadEditor(); clearWorkspaceState(); setView('home'); }} className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm font-medium text-sumi-dim hover:bg-vermilion-light/30 hover:text-vermilion transition-colors">
                <LogOut size={18} />
                {sidebarOpen && <span>退出登录</span>}
              </button>
            </>
          ) : (
            <button onClick={() => requireLogin()} className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-sm font-medium text-sumi-dim hover:bg-paper-base hover:text-sumi transition-colors">
              <LogIn size={18} />
              {sidebarOpen && <span>登录</span>}
            </button>
          )}
        </div>
      </aside>

      <div className="flex min-h-0 flex-1 flex-col">
        {view === 'home' && <MangaAgentPage />}
        {view === 'square' && <SquarePage />}
        {view === 'workspace' && <HomePage onSelectStory={(story) => loadEditor(story.id)} />}
        {view === 'imagegen' && <ImageGenPage />}
        {view === 'myworks' && <MyWorksPage />}

        {view === 'editor' && activeStoryId && (
          <div className="flex min-h-0 flex-1 flex-col">
            <div className="flex h-12 shrink-0 items-center justify-between border-b border-paper-border bg-paper-surface/90 px-3 backdrop-blur-md">
              <button onClick={() => { unloadEditor(); setView('workspace'); }} className="flex items-center gap-1.5 text-sm text-sumi-dim hover:text-vermilion transition-colors">
                <ChevronLeft size={16} />
                返回故事
              </button>
              <div className="flex items-center gap-2">
                {chapters.length > 0 && (
                  <select
                    value={currentIdx}
                    onChange={(e) => setChapterByIndex(Number(e.target.value))}
                    className="rounded-md border border-paper-border bg-paper-base px-2 py-1 text-xs text-sumi focus:border-vermilion focus:outline-none transition-colors"
                  >
                    {chapters.map((ch, i) => (
                      <option key={ch.id} value={i}>第 {ch.chapter_number} 话</option>
                    ))}
                  </select>
                )}
              </div>
            </div>

            {isMobile && chapters.length > 0 && (
              <div className="shrink-0 overflow-x-auto border-b border-paper-border bg-paper-surface px-2 py-2">
                <div className="flex gap-1">
                  {chapters.map((ch: Chapter, idx: number) => (
                    <button key={ch.id} onClick={() => setChapterByIndex(idx)} className={'shrink-0 rounded-full border px-3 py-1.5 text-xs transition-all duration-200 ' + (ch.id === currentChapter?.id ? 'border-vermilion bg-vermilion-light/50 text-vermilion font-medium' : 'border-paper-border bg-paper-base text-sumi-dim hover:text-sumi')}>
                      第 {ch.chapter_number} 话
                    </button>
                  ))}
                </div>
              </div>
            )}

            {isMobile && (
              <div className="flex border-b border-paper-border bg-paper-surface">
                <button onClick={() => setMobileTab('chat')} className={'flex flex-1 items-center justify-center gap-1.5 py-2.5 text-xs font-medium transition-colors ' + (mobileTab === 'chat' ? 'border-b-2 border-vermilion text-vermilion' : 'text-sumi-dim hover:text-sumi')}>
                  <MessageSquare size={14} />
                  对话
                </button>
                <button onClick={() => setMobileTab('manga')} className={'flex flex-1 items-center justify-center gap-1.5 py-2.5 text-xs font-medium transition-colors ' + (mobileTab === 'manga' ? 'border-b-2 border-vermilion text-vermilion' : 'text-sumi-dim hover:text-sumi')}>
                  <Image size={14} />
                  漫画
                </button>
              </div>
            )}

            {isMobile ? (
              <main className="min-h-0 flex-1">
                <div className={'h-full ' + (mobileTab === 'chat' ? '' : 'hidden')}>
                  <ChatPanel chapter={currentChapter} onMessageSent={refreshCurrentChapter} onChapterRefresh={handleChapterRefresh} />
                </div>
                <div className={'h-full ' + (mobileTab === 'manga' ? '' : 'hidden')}>
                  <MangaPanel chapter={currentChapter} onChapterRefresh={handleChapterRefresh} />
                </div>
              </main>
            ) : (
              <main className="flex min-h-0 flex-1">
                <div className="w-1/2 border-r border-paper-border">
                  <ChatPanel chapter={currentChapter} onMessageSent={refreshCurrentChapter} onChapterRefresh={handleChapterRefresh} />
                </div>
                <div className="w-1/2">
                  <MangaPanel chapter={currentChapter} onChapterRefresh={handleChapterRefresh} />
                </div>
              </main>
            )}

            <footer className="flex h-14 shrink-0 items-center justify-center gap-2 border-t border-paper-border bg-paper-surface/90 px-2 backdrop-blur-md md:gap-4">
              <button onClick={handlePrevChapter} disabled={currentIdx === 0} className="flex items-center gap-1 rounded-md border border-paper-border bg-paper-base px-3 py-2 text-sm font-medium text-sumi-dim disabled:cursor-not-allowed disabled:opacity-30 hover:border-sumi-faint hover:text-sumi transition-colors">
                <ChevronLeft size={16} />
                {!isMobile && '上一话'}
              </button>
              <button onClick={handleDelete} disabled={!currentChapter || chapters.length <= 1} className="flex items-center gap-1.5 rounded-md border border-vermilion/20 bg-vermilion-light/20 px-3 py-2 text-sm font-medium text-vermilion disabled:cursor-not-allowed disabled:opacity-30 hover:bg-vermilion-light/40 transition-colors" aria-label="删除本话">
                <Trash2 size={14} />
              </button>
              <div className="flex items-center gap-1">
                {chapters.map((ch: Chapter, i: number) => (
                  <button key={ch.id} onClick={() => setChapterByIndex(i)} className={'h-2 w-2 rounded-full transition-all duration-200 ' + (i === currentIdx ? 'bg-vermilion scale-125' : 'bg-sumi-faint/30 hover:bg-sumi-faint/60')} aria-label={`切换到第 ${ch.chapter_number} 话`} />
                ))}
              </div>
              <button onClick={handleNextChapter} disabled={creatingChapter} className="flex items-center gap-1 rounded-md bg-vermilion px-3 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40 hover:bg-vermilion-hover transition-colors md:px-5">
                {currentIdx === chapters.length - 1 ? (<><Plus size={16} />{creatingChapter ? '创建中...' : isMobile ? '新建' : '下一话（新建）'}</>) : (<><span>{!isMobile && '下一话'}</span><ChevronRight size={16} /></>)}
              </button>
            </footer>
          </div>
        )}
      </div>

      <ApiKeySettingsModal open={settingsOpen} onClose={() => setSettingsOpen(false)} />

      {loginOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-sumi/30 backdrop-blur-sm" onClick={() => setLoginOpen(false)}>
          <div className="w-full max-w-sm animate-fade-in" onClick={(e) => e.stopPropagation()}>
            <LoginPage variant="modal" message={loginMessage} onCancel={() => setLoginOpen(false)} onAuthSuccess={handleAuthSuccess} />
          </div>
        </div>
      )}
    </div>
  );
}
