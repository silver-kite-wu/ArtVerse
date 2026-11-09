import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import {
  BookOpenText,
  CalendarClock,
  ChevronRight,
  FileText,
  Globe,
  LogIn,
  PanelLeftClose,
  PanelLeftOpen,
  Paintbrush,
  Sparkles,
} from 'lucide-react';
import AccountMenu from './components/AccountMenu';
import ProfilePage from './components/ProfilePage';
import ApiSettingsPage from './components/ApiSettingsPage';
import HomePage from './components/HomePage';
import ImageGenPage from './components/ImageGenPage';
import LoginPage from './components/LoginPage';

import MyWorksPage from './components/MyWorksPage';
import SquarePage from './components/SquarePage';
import ThemeToggle from './components/ThemeToggle';
import WorkspaceEditor from './components/WorkspaceEditor';
import AutomationCenterPage from './components/automations/AutomationCenterPage';
import AutomationComposerHost from './components/automations/AutomationComposerHost';
import { clearAuth, getUser, subscribeUser, hydrateAuthSession, isAuthenticated, logoutUser, type Story } from './api';
import {
  confirmRouteLeave,
  isProtectedRoute,
  parseAppHash,
  pushAppRoute,
  replaceAppRoute,
  type AppRoute,
  type AppView,
} from './appRouting';
import { useToast } from './ui/toast/useToast';

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
  const toast = useToast();
  const [authenticated, setAuthenticated] = useState(false);
  const [authCheck, setAuthCheck] = useState(false);
  const [route, setRoute] = useState<AppRoute | null>(() => parseAppHash(window.location.hash));
  const [sidebarOpen, setSidebarOpen] = useState(!isMobile);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginMessage, setLoginMessage] = useState('请先登录后再使用该功能');
  const [pendingRoute, setPendingRoute] = useState<AppRoute | null>(null);

  const lastFocusedElementRef = useRef<HTMLElement | null>(null);

  const visibleRoute = route && (!authenticated && isProtectedRoute(route)) ? { view: 'square' as const } : route;
  const view: View = visibleRoute?.view ?? 'square';
  const user = useSyncExternalStore(subscribeUser, getUser);
  const acceptedHash = useRef(window.location.hash);
  // A public work is a destination in its own right. Keep it outside the
  // creator-console chrome so a shared URL opens as an uninterrupted reading
  // page, rather than as a panel inside the application shell.
  const isSquareStoryRoute = visibleRoute?.view === 'square' && 'storyId' in visibleRoute;
  const isLoginOpen = loginOpen || (authCheck && !authenticated && !!route && isProtectedRoute(route));
  const routeToResume = pendingRoute ?? (route && isProtectedRoute(route) ? route : null);

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
      if (acceptedHash.current !== window.location.hash && !confirmRouteLeave()) {
        window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}${acceptedHash.current}`);
        return;
      }
      acceptedHash.current = window.location.hash;
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

  const cancelLogin = () => {
    setLoginOpen(false);
    setPendingRoute(null);
    clearWorkspaceState();
    replaceAppRoute({ view: 'square' });
  };

  useEffect(() => {
    if (!isLoginOpen) {
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
  }, [isLoginOpen]);

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
    pushAppRoute({ view: 'editor', storyId: story.id });
  };

  const leaveEditor = () => {
    clearWorkspaceState();
    pushAppRoute({ view: 'workspace' });
  };

  const handleAuthSuccess = () => {
    setAuthenticated(true);
    setLoginOpen(false);
    if (routeToResume) {
      replaceAppRoute(routeToResume);
      setPendingRoute(null);
    }
  };

  const handleLogout = async () => {
    if (!confirmRouteLeave()) return;
    await logoutUser();
    setAuthenticated(false);
    setPendingRoute(null);
    setLoginOpen(false);
    clearWorkspaceState();
    replaceAppRoute({ view: 'square' });
    toast.success('已退出登录');
  };

  const handlePasswordChanged = () => {
    clearAuth();
    clearWorkspaceState();
    setAuthenticated(false);
    setPendingRoute({ view: 'profile' });
    setLoginMessage('密码已修改，请使用新密码重新登录');
    setLoginOpen(true);
    toast.success('密码已更新，请重新登录');
  };

  const goView = (target: TopLevelView) => {
    const targetRoute: AppRoute = target === 'automations' ? { view: 'automations' } : { view: target };
    if (target !== 'square' && !authenticated) {
      requireLogin(targetRoute);
      return;
    }
    if (view === 'editor') {
      clearWorkspaceState();
    }
    pushAppRoute(targetRoute);
  };

  const handleEditorLoadError = useCallback((message: string) => {
    if (!isAuthenticated()) return;
    clearWorkspaceState();
    toast.error(message || '故事不存在或当前账号无权访问，已返回故事工作区。');
    replaceAppRoute({ view: 'workspace' });
  }, [toast]);

  const navItem = (icon: ReactNode, label: string, target: TopLevelView) => {
    const active = view === target;
    return (
      <button
        type="button"
        onClick={() => goView(target)}
        title={!sidebarOpen ? label : undefined}
        aria-label={label}
        aria-current={active ? 'page' : undefined}
        className={
          'studio-nav-item group relative flex min-h-10 w-full items-center rounded-xl px-3 text-left text-sm font-medium transition-all duration-200 '
          + (active
            ? 'is-active bg-accent-muted text-accent font-semibold'
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
        aria-label={label}
        aria-current={active ? 'page' : undefined}
        className={
          'relative flex min-w-0 flex-1 flex-col items-center justify-center gap-1 px-1 py-2 text-[0.625rem] font-medium transition-all duration-200 '
          + (active ? 'text-accent' : 'text-text-muted hover:text-text-secondary')
        }
      >
        <span className={active ? 'scale-110 transition-transform duration-300' : 'transition-transform duration-300'}>
          {icon}
        </span>
        <span className="max-w-full truncate">{label}</span>
      </button>
    );
  };

  if (!authCheck) {
    return (
      <div className="flex h-dvh w-full items-center justify-center bg-bg-base">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-border border-t-accent" />
      </div>
    );
  }

  return (
    <div className="studio-shell relative flex h-dvh w-full overflow-hidden bg-bg-base text-text-primary">
      {!isMobile && !isSquareStoryRoute && (
        <aside
          data-collapsed={!sidebarOpen}
          className={
            'studio-sidebar flex shrink-0 flex-col border-r border-border transition-[width] duration-300 '
            + (sidebarOpen ? 'w-[15.5rem]' : 'w-[5rem]')
          }
        >
          <div className={'studio-brand flex h-24 items-center gap-3 ' + (sidebarOpen ? 'px-5' : 'flex-col justify-center gap-1 px-2')}>
            <div className="studio-logo flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-lg">
              <img src="/favicon.svg" alt="ArtVerse" className="h-full w-full scale-[1.9] object-contain" />
            </div>
            {sidebarOpen && (
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="studio-wordmark text-xl font-bold text-text-primary">ArtVerse<span className="text-accent">.</span></span>
                </div>
                <div className="mt-1 text-[0.625rem] tracking-[0.14em] text-text-muted">让想象，自由生长</div>
              </div>
            )}
            <button
              type="button"
              onClick={() => setSidebarOpen((prev) => !prev)}
              className={(sidebarOpen ? 'ml-auto ' : '') + 'flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-accent-soft hover:text-text-primary'}
              aria-label={sidebarOpen ? '收起侧栏' : '展开侧栏'}
              title={sidebarOpen ? '收起侧栏' : '展开侧栏'}
            >
              {sidebarOpen ? <PanelLeftClose size={16} /> : <PanelLeftOpen size={16} />}
            </button>
          </div>

          <nav className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto px-4 py-5" aria-label="主导航">
            {sidebarOpen && <p className="mb-2 px-3 text-[0.625rem] font-medium tracking-[0.18em] text-text-muted">创作空间 / CREATE</p>}
            {navItem(<BookOpenText size={18} />, '故事工作区', 'workspace')}
            {navItem(<Paintbrush size={18} />, 'AI 生图', 'imagegen')}
            {navItem(<CalendarClock size={18} />, '定时任务', 'automations')}
            {sidebarOpen && <p className="mb-2 mt-7 px-3 text-[0.625rem] font-medium tracking-[0.18em] text-text-muted">探索更多 / EXPLORE</p>}
            {navItem(<Globe size={18} />, '作品广场', 'square')}
            {navItem(<FileText size={18} />, '作品管理', 'myworks')}
          </nav>

          {sidebarOpen && <div className="studio-sidebar-note mx-5 mb-5 rounded-xl p-4"><Sparkles size={19} strokeWidth={1.5} /><p className="mt-3 text-sm font-semibold">故事，从一个想法开始</p><p className="mt-2 text-xs leading-5 text-text-secondary">让 AI 成为灵感搭档，<br />把脑海中的世界带到眼前。</p><button type="button" className="mt-3 inline-flex items-center gap-1 text-xs font-semibold text-accent" onClick={() => goView('workspace')}>开启创作 <ChevronRight size={14} /></button></div>}
          <div className="studio-account border-t border-border px-4 py-3">
            {authenticated && user
              ? <AccountMenu user={user} compact={!sidebarOpen} onProfile={() => goView('profile')} onSettings={() => goView('settings')} onLogout={handleLogout} />
              : <><button type="button" onClick={() => requireLogin()} className="account-trigger" aria-label="登录账号"><LogIn size={20} />{sidebarOpen && <span className="account-identity"><strong>登录账号</strong><small>登录，留住每一次灵感</small></span>}</button><ThemeToggle compact={!sidebarOpen} /></>}
          </div>
        </aside>
      )}

      <div
        key={view}
        className={'flex min-h-0 min-w-0 flex-1 animate-view-enter flex-col ' + (isMobile && view !== 'editor' && !isSquareStoryRoute ? 'pb-[calc(64px+env(safe-area-inset-bottom))]' : '')}
      >
        {isMobile && view !== 'editor' && !isSquareStoryRoute && (
          <header className="studio-mobile-header flex h-14 shrink-0 items-center justify-between border-b border-border glass-panel px-4">
            <button type="button" onClick={() => goView('workspace')} className="flex items-center gap-2" aria-label="返回故事工作区">
              <span className="studio-logo flex h-8 w-8 items-center justify-center rounded-lg"><BookOpenText size={18} /></span>
              <span className="studio-wordmark text-lg font-bold text-text-primary">ArtVerse<span className="text-accent">.</span></span>
            </button>
            <div className="flex items-center gap-2">
              {authenticated && user
                ? <AccountMenu user={user} compact mobile onProfile={() => goView('profile')} onSettings={() => goView('settings')} onLogout={handleLogout} />
                : <><ThemeToggle compact /><button type="button" onClick={() => requireLogin()} className="profile-button secondary" aria-label="登录"><LogIn size={17} /></button></>}
            </div>
          </header>
        )}
        {view === 'square' && <SquarePage />}
        {view === 'workspace' && (
          <HomePage
            onSelectStory={loadEditor}
          />
        )}
        {visibleRoute?.view === 'editor' && (
          <WorkspaceEditor key={visibleRoute.storyId} storyId={visibleRoute.storyId} onBack={leaveEditor} onLoadError={handleEditorLoadError} />
        )}
        {view === 'imagegen' && <ImageGenPage />}
        {view === 'myworks' && <MyWorksPage />}
        {view === 'settings' && <ApiSettingsPage />}
        {view === 'profile' && user && <ProfilePage user={user} onPasswordChanged={handlePasswordChanged} onBack={() => goView('workspace')} />}
        {visibleRoute?.view === 'automations' && <AutomationCenterPage automationId={visibleRoute.automationId} runId={visibleRoute.runId} />}
      </div>

      {isMobile && view !== 'editor' && !isSquareStoryRoute && (
        <nav className="studio-mobile-nav absolute inset-x-0 bottom-0 z-40 flex h-[calc(4rem+env(safe-area-inset-bottom))] border-t border-border glass-panel pb-[env(safe-area-inset-bottom)]" aria-label="主导航">
          {mobileNavItem(<BookOpenText size={19} />, '故事', 'workspace')}
          {mobileNavItem(<Paintbrush size={19} />, '生图', 'imagegen')}
          {mobileNavItem(<CalendarClock size={19} />, '任务', 'automations')}
          {mobileNavItem(<Globe size={19} />, '广场', 'square')}
          {mobileNavItem(<FileText size={19} />, '作品', 'myworks')}
        </nav>
      )}

      {authenticated && <AutomationComposerHost />}
      {isLoginOpen && (
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
