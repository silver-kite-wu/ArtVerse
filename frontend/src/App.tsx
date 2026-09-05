import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  BookOpenText,
  ChevronRight,
  FileText,
  Globe,
  KeyRound,
  LogIn,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  Paintbrush,
  Sparkles,
  UserRound,
} from 'lucide-react';
import ApiSettingsPage from './components/ApiSettingsPage';
import HomePage from './components/HomePage';
import ImageGenPage from './components/ImageGenPage';
import LoginPage from './components/LoginPage';

import MyWorksPage from './components/MyWorksPage';
import SquarePage from './components/SquarePage';
import ThemeToggle from './components/ThemeToggle';
import WorkspaceEditor from './components/WorkspaceEditor';
import { getUser, hydrateAuthSession, isAuthenticated, logoutUser, type Story } from './api';
import {
  isProtectedRoute,
  parseAppHash,
  pushAppRoute,
  replaceAppRoute,
  type AppRoute,
  type AppView,
} from './appRouting';

type View = AppView;
type TopLevelView = Exclude<AppView, 'editor'>;

const LS_STORY_ID = 'lorevista.currentStoryId';
const LS_CHAPTER_ID = 'lorevista.currentChapterId';
const LS_CHAPTER_IDX = 'lorevista.currentChapterIdx';

function useIsMobile() {
  const read = () =>
    navigator.maxTouchPoints > 0 ||
    window.matchMedia('(any-pointer:coarse)').matches ||
    window.matchMedia('(max-width:1024px)').matches ||
    window.matchMedia('(pointer:coarse)').matches;

  const [isMobile, setIsMobile] = useState(read);

  useEffect(() => {
    const width = window.matchMedia('(max-width:1024px)');
    const pointer = window.matchMedia('(pointer:coarse)');
    const anyPointer = window.matchMedia('(any-pointer:coarse)');
    let frame = 0;

    const sync = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => setIsMobile(read()));
    };

    width.addEventListener('change', sync);
    pointer.addEventListener('change', sync);
    anyPointer.addEventListener('change', sync);
    window.addEventListener('resize', sync);

    return () => {
      cancelAnimationFrame(frame);
      width.removeEventListener('change', sync);
      pointer.removeEventListener('change', sync);
      anyPointer.removeEventListener('change', sync);
      window.removeEventListener('resize', sync);
    };
  }, []);

  return isMobile;
}

function clearWorkspaceState() {
  localStorage.removeItem(LS_STORY_ID);
  localStorage.removeItem(LS_CHAPTER_ID);
  localStorage.removeItem(LS_CHAPTER_IDX);
}

export default function App() {
  const isMobile = useIsMobile();
  const [authenticated, setAuthenticated] = useState(false);
  const [authCheck, setAuthCheck] = useState(false);
  const [route, setRoute] = useState<AppRoute | null>(() => parseAppHash(window.location.hash));
  const [sidebarOpen, setSidebarOpen] = useState(!isMobile);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginMessage, setLoginMessage] = useState('请先登录后再使用该功能');
  const [pendingRoute, setPendingRoute] = useState<AppRoute | null>(null);

  const [navigationMessage, setNavigationMessage] = useState('');
  const lastFocusedElementRef = useRef<HTMLElement | null>(null);

  const visibleRoute = route && (!authenticated && isProtectedRoute(route)) ? { view: 'square' as const } : route;
  const view: View = visibleRoute?.view ?? 'square';
  const user = getUser();
  const userName = user?.username?.trim() || 'Guest';
  const userEmail = user?.email?.trim() || '登录后同步你的创作配置';
  const userInitial = userName.slice(0, 1).toUpperCase();
  // A public work is a destination in its own right. Keep it outside the
  // creator-console chrome so a shared URL opens as an uninterrupted reading
  // page, rather than as a panel inside the application shell.
  const isSquareStoryRoute = visibleRoute?.view === 'square' && 'storyId' in visibleRoute;

  useEffect(() => {
    let active = true;
    void hydrateAuthSession().then((nextAuthenticated) => {
      if (!active) return;
      setAuthenticated(nextAuthenticated);
      setAuthCheck(true);
    });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!authCheck) return undefined;
    const syncRoute = () => {
      const parsed = parseAppHash(window.location.hash);
      if (parsed) {
        setRoute(parsed);
        return;
      }
      replaceAppRoute({ view: isAuthenticated() ? 'workspace' : 'square' });
    };

    window.addEventListener('hashchange', syncRoute);
    syncRoute();
    return () => window.removeEventListener('hashchange', syncRoute);
  }, [authCheck]);

  useEffect(() => {
    if (!authCheck || authenticated || !route || !isProtectedRoute(route)) return;
    setPendingRoute(route);
    if (!loginOpen) {
      setLoginMessage('请先登录后再使用该功能');
      if (document.activeElement instanceof HTMLElement) {
        lastFocusedElementRef.current = document.activeElement;
      }
      setLoginOpen(true);
    }
  }, [authCheck, authenticated, loginOpen, route]);

  useEffect(() => {
    const handleExpired = () => {
      setAuthenticated(false);
      setLoginMessage('登录已过期，请重新登录');
      setPendingRoute(route && isProtectedRoute(route) ? route : null);
      if (document.activeElement instanceof HTMLElement) {
        lastFocusedElementRef.current = document.activeElement;
      }
      setLoginOpen(true);
      clearWorkspaceState();
    };
    window.addEventListener('artverse:auth-expired', handleExpired);
    return () => window.removeEventListener('artverse:auth-expired', handleExpired);
  }, [route]);

  useEffect(() => {
    if (!loginOpen) {
      lastFocusedElementRef.current?.focus?.();
      return undefined;
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        cancelLogin();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [loginOpen]);

  const requireLogin = (target?: AppRoute) => {
    if (document.activeElement instanceof HTMLElement) {
      lastFocusedElementRef.current = document.activeElement;
    }
    setLoginMessage('请先登录后再使用该功能');
    setPendingRoute(target || null);
    setLoginOpen(true);
    if (target) pushAppRoute(target);
  };

  const loadEditor = (story: Story) => {
    const previousStoryId = Number(localStorage.getItem(LS_STORY_ID));
    if (previousStoryId !== story.id) {
      localStorage.removeItem(LS_CHAPTER_ID);
      localStorage.removeItem(LS_CHAPTER_IDX);
    }
    localStorage.setItem(LS_STORY_ID, String(story.id));
    setNavigationMessage('');
    pushAppRoute({ view: 'editor', storyId: story.id });
  };

  const leaveEditor = () => {
    clearWorkspaceState();
    setNavigationMessage('');
    pushAppRoute({ view: 'workspace' });
  };

  const handleAuthSuccess = () => {
    setAuthenticated(true);
    setLoginOpen(false);
    if (pendingRoute) {
      replaceAppRoute(pendingRoute);
      setPendingRoute(null);
    }
  };

  const cancelLogin = () => {
    setLoginOpen(false);
    setPendingRoute(null);
    clearWorkspaceState();
    replaceAppRoute({ view: 'square' });
  };

  const handleLogout = async () => {
    await logoutUser();
    setAuthenticated(false);
    setPendingRoute(null);
    setLoginOpen(false);
    clearWorkspaceState();
    replaceAppRoute({ view: 'square' });
  };

  const goView = (target: TopLevelView) => {
    const targetRoute: AppRoute = { view: target };
    if (target !== 'square' && !authenticated) {
      requireLogin(targetRoute);
      return;
    }
    if (view === 'editor') {
      clearWorkspaceState();
    }
    setNavigationMessage('');
    pushAppRoute(targetRoute);
  };

  const handleEditorLoadError = useCallback((message: string) => {
    if (!isAuthenticated()) return;
    clearWorkspaceState();
    setNavigationMessage(message || '故事不存在或当前账号无权访问，已返回故事工作区。');
    replaceAppRoute({ view: 'workspace' });
  }, []);

  const navItem = (icon: ReactNode, label: string, target: TopLevelView) => {
    const active = view === target;
    return (
      <button
        type="button"
        onClick={() => goView(target)}
        title={!sidebarOpen ? label : undefined}
        className={
          'group relative flex min-h-10 w-full items-center rounded-xl px-3 text-left text-sm font-medium transition-all duration-200 '
          + (active
            ? 'bg-accent-muted text-accent font-semibold ring-1 ring-accent/20'
            : 'text-text-secondary hover:bg-accent-soft hover:text-text-primary')
        }
      >
        <span className="flex items-center gap-3 whitespace-nowrap">
          <span className={active ? 'scale-110 transition-transform duration-300' : 'transition-transform duration-300'}>
            {icon}
          </span>
          {sidebarOpen && <span>{label}</span>}
        </span>
      </button>
    );
  };

  const mobileNavItem = (icon: ReactNode, label: string, target: TopLevelView) => {
    const active = view === target;
    return (
      <button
        type="button"
        onClick={() => goView(target)}
        className={
          'relative flex min-w-0 flex-1 flex-col items-center justify-center gap-1 px-1 py-2 text-[0.625rem] font-medium transition-all duration-200 '
          + (active ? 'text-accent' : 'text-text-muted hover:text-text-secondary')
        }
      >
        {active && <span className="absolute -top-0.5 left-1/2 -translate-x-1/2 h-1 w-1 rounded-full bg-accent" />}
        <span className={active ? 'scale-110 transition-transform duration-300' : 'transition-transform duration-300'}>
          {icon}
        </span>
        <span className="max-w-full truncate">{label}</span>
      </button>
    );
  };

  const sidebarUtilityItem = (
    icon: ReactNode,
    label: string,
    onClick: () => void,
    options?: {
      description?: string;
      accent?: boolean;
      title?: string;
    },
  ) => (
    <button
      type="button"
      onClick={onClick}
      title={!sidebarOpen ? (options?.title ?? label) : undefined}
      className={
        'group flex min-h-10 w-full items-center gap-3 rounded-xl px-3 text-left text-sm font-medium transition-all duration-200 '
        + (options?.accent
          ? 'text-text-secondary hover:bg-accent-muted/30 hover:text-accent'
          : 'text-text-secondary hover:bg-accent-soft hover:text-text-primary')
        + (!sidebarOpen ? ' justify-center px-0' : '')
      }
    >
      <span className="shrink-0">{icon}</span>
      {sidebarOpen && (
        <>
          <span className="min-w-0 flex-1">
            <span className="block truncate">{label}</span>
            {options?.description && (
              <span className="mt-0.5 block truncate text-[0.6875rem] font-normal text-text-muted">
                {options.description}
              </span>
            )}
          </span>
          <ChevronRight size={15} className="shrink-0 text-text-muted transition-transform duration-200 group-hover:translate-x-0.5" />
        </>
      )}
    </button>
  );

  if (!authCheck) {
    return (
      <div className="flex h-dvh w-full items-center justify-center bg-bg-base">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-border border-t-accent" />
      </div>
    );
  }

  return (
    <div className="relative flex h-dvh w-full overflow-hidden bg-bg-base text-text-primary">
      {!isMobile && !isSquareStoryRoute && (
        <aside
          className={
            'flex shrink-0 flex-col border-r border-border glass-panel transition-[width] duration-300 '
            + (sidebarOpen ? 'w-[14.5rem]' : 'w-[4.25rem]')
          }
        >
          <div className="flex h-16 items-center gap-3 border-b border-border px-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-accent text-white shadow-glow">
              <Sparkles size={18} />
            </div>
            {sidebarOpen && (
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="font-display text-base font-bold text-text-primary">ArtVerse</span>
                  <span className="seal-stamp h-4! min-w-4! text-[0.5rem]! font-normal">墨</span>
                </div>
                <div className="text-[0.625rem] text-text-muted">AI 漫画创作工坊</div>
              </div>
            )}
            <button
              type="button"
              onClick={() => setSidebarOpen((prev) => !prev)}
              className="ml-auto flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-accent-soft hover:text-text-primary"
              aria-label={sidebarOpen ? '收起侧栏' : '展开侧栏'}
              title={sidebarOpen ? '收起侧栏' : '展开侧栏'}
            >
              {sidebarOpen ? <PanelLeftClose size={16} /> : <PanelLeftOpen size={16} />}
            </button>
          </div>

          <nav className="flex flex-1 flex-col gap-1 px-2 py-4">
            {sidebarOpen && <p className="mb-1 px-3 text-[0.625rem] font-semibold uppercase tracking-wider text-text-muted">创作空间</p>}
            {navItem(<BookOpenText size={18} />, '故事工作区', 'workspace')}
            {navItem(<Paintbrush size={18} />, 'AI 生图', 'imagegen')}
            {sidebarOpen && <p className="mb-1 mt-4 px-3 text-[0.625rem] font-semibold uppercase tracking-wider text-text-muted">发现与管理</p>}
            {navItem(<Globe size={18} />, '作品广场', 'square')}
            {navItem(<FileText size={18} />, '作品管理', 'myworks')}
          </nav>

          <div className="border-t border-border px-3 py-3">
            <div
              className={
                'rounded-[22px] border border-border/80 bg-bg-surface/75 backdrop-blur-sm '
                + (sidebarOpen ? 'p-3' : 'p-2')
              }
              title={!sidebarOpen ? (authenticated ? userName : '游客模式') : undefined}
            >
              <div
                className={
                  'flex items-center gap-3 rounded-2xl border border-border/70 bg-bg-base/70 '
                  + (sidebarOpen ? 'px-3 py-3' : 'justify-center px-0 py-3')
                }
              >
                <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-accent text-white shadow-glow">
                  {authenticated ? <span className="text-sm font-semibold">{userInitial}</span> : <UserRound size={18} />}
                </div>
                {sidebarOpen && (
                  <>
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-sm font-semibold text-text-primary">{authenticated ? userName : '游客模式'}</div>
                      <div className="mt-0.5 truncate text-[0.6875rem] text-text-muted">
                        {authenticated ? userEmail : '登录后保存模型配置与创作记录'}
                      </div>
                    </div>
                    <span className={`shrink-0 rounded-full px-2 py-1 text-[0.625rem] font-semibold ${authenticated ? 'bg-accent-muted text-accent' : 'bg-bg-raised text-text-muted'}`}>
                      {authenticated ? '已登录' : '访客'}
                    </span>
                  </>
                )}
              </div>

              <div className="mt-2 rounded-2xl border border-border/70 bg-bg-base/55 p-1.5">
                {authenticated
                  ? sidebarUtilityItem(<KeyRound size={18} />, 'API 设置', () => goView('settings'), {
                      description: '管理供应商、模型与密钥',
                    })
                  : sidebarUtilityItem(<LogIn size={18} />, '登录账号', () => requireLogin(), {
                      description: '同步创作进度与个人配置',
                    })}
                <div className="mx-2 my-1 border-t border-border/70" />
                <ThemeToggle compact={!sidebarOpen} />
                {authenticated && (
                  <>
                    <div className="mx-2 my-1 border-t border-border/70" />
                    {sidebarUtilityItem(<LogOut size={18} />, '退出登录', () => { void handleLogout(); }, {
                      description: '清除当前会话并返回广场',
                      accent: true,
                    })}
                  </>
                )}
              </div>
            </div>
          </div>
        </aside>
      )}

      <div
        key={view}
        className={'flex min-h-0 min-w-0 flex-1 animate-view-enter flex-col ' + (isMobile && view !== 'editor' && !isSquareStoryRoute ? 'pb-[calc(64px+env(safe-area-inset-bottom))]' : '')}
      >
        {isMobile && view !== 'editor' && !isSquareStoryRoute && (
          <header className="flex h-14 shrink-0 items-center justify-between border-b border-border glass-panel px-4">
            <button type="button" onClick={() => goView('workspace')} className="flex items-center gap-2" aria-label="返回故事工作区">
              <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent text-white shadow-glow"><Sparkles size={16} /></span>
              <span className="font-display text-base font-bold text-text-primary">ArtVerse</span>
            </button>
            <div className="flex items-center gap-2">
              <ThemeToggle compact />
              <button
                type="button"
                onClick={() => authenticated ? goView('settings') : requireLogin()}
                className="flex h-9 w-9 items-center justify-center rounded-lg border border-border bg-bg-surface text-text-secondary"
                aria-label={authenticated ? 'API 设置' : '登录'}
              >
                {authenticated ? <KeyRound size={17} /> : <LogIn size={17} />}
              </button>
            </div>
          </header>
        )}
        {navigationMessage && (
          <div className="shrink-0 border-b border-warning/30 bg-warning/10 px-4 py-2 text-sm text-text-primary" role="status">
            {navigationMessage}
          </div>
        )}
        {view === 'square' && <SquarePage />}
        {view === 'workspace' && (
          <HomePage
            onSelectStory={loadEditor}
          />
        )}
        {visibleRoute?.view === 'editor' && (
          <WorkspaceEditor storyId={visibleRoute.storyId} onBack={leaveEditor} onLoadError={handleEditorLoadError} />
        )}
        {view === 'imagegen' && <ImageGenPage />}
        {view === 'myworks' && <MyWorksPage />}
        {view === 'settings' && <ApiSettingsPage />}
      </div>

      {isMobile && view !== 'editor' && !isSquareStoryRoute && (
        <nav className="absolute inset-x-0 bottom-0 z-40 flex h-[calc(4rem+env(safe-area-inset-bottom))] border-t border-border glass-panel pb-[env(safe-area-inset-bottom)]" aria-label="主导航">
          {mobileNavItem(<BookOpenText size={19} />, '故事', 'workspace')}
          {mobileNavItem(<Paintbrush size={19} />, '生图', 'imagegen')}
          {mobileNavItem(<Globe size={19} />, '广场', 'square')}
          {mobileNavItem(<FileText size={19} />, '作品', 'myworks')}
        </nav>
      )}

      <div id="overlay-root" className="app-overlay-layer" />

      {loginOpen && (
        <div
          className="absolute inset-0 z-50 flex items-center justify-center bg-bg-overlay/95 p-4 backdrop-blur-md sm:p-6"
          onClick={cancelLogin}
        >
          <div className="w-full max-w-[38rem] animate-fade-in" onClick={(event) => event.stopPropagation()}>
            <LoginPage
              variant="modal"
              message={loginMessage}
              onCancel={cancelLogin}
              onAuthSuccess={handleAuthSuccess}
            />
          </div>
        </div>
      )}
    </div>
  );
}
