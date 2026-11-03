export type AppView = 'square' | 'workspace' | 'editor' | 'imagegen' | 'myworks' | 'settings' | 'automations' | 'profile';

let leaveGuard: (() => boolean) | null = null;
export function setRouteLeaveGuard(guard: (() => boolean) | null): void { leaveGuard = guard; }
export function confirmRouteLeave(): boolean { return leaveGuard?.() ?? true; }

export type AppRoute =
  | { view: Exclude<AppView, 'editor' | 'automations'> }
  | { view: 'automations'; automationId?: string; runId?: string }
  | { view: 'editor'; storyId: number }
  | { view: 'square'; format: 'novel' | 'manga'; storyId: number; chapterId?: number };

const SIMPLE_VIEWS = new Set<Exclude<AppView, 'editor'>>([
  'square',
  'workspace',
  'imagegen',
  'myworks',
  'settings',
  'profile',
  'automations',
]);

export function parseAppHash(hash: string): AppRoute | null {
  const path = hash.replace(/^#/, '').replace(/^\/+|\/+$/g, '');
  const [pathname] = path.split('?');
  if (!path) return null;

  const automationMatch = /^automations(?:\/([0-9a-f-]{36}))?$/.exec(pathname);
  if (automationMatch) return { view: 'automations', automationId: automationMatch[1], runId: new URLSearchParams(path.split('?')[1] || '').get('run') || undefined };
  if (SIMPLE_VIEWS.has(pathname as Exclude<AppView, 'editor'>)) {
    return { view: pathname as Exclude<AppView, 'editor' | 'automations'> };
  }

  const editorMatch = /^editor\/(\d+)$/.exec(path);
  const squareMatch = /^square\/(novel|manga)\/(\d+)(?:\/chapter\/(\d+))?$/.exec(pathname);
  if (squareMatch) return { view: 'square', format: squareMatch[1] as 'novel' | 'manga', storyId: Number(squareMatch[2]), ...(squareMatch[3] ? { chapterId: Number(squareMatch[3]) } : {}) };
  if (!editorMatch) return null;

  const storyId = Number(editorMatch[1]);
  return Number.isSafeInteger(storyId) && storyId > 0 ? { view: 'editor', storyId } : null;
}

export function appRouteHash(route: AppRoute): string {
  if (route.view === 'automations') return `#/automations${route.automationId ? `/${route.automationId}` : ''}${route.runId ? `?run=${route.runId}` : ''}`;
  if (route.view === 'editor') return `#/editor/${route.storyId}`;
  if (route.view === 'square' && 'storyId' in route) return `#/square/${route.format}/${route.storyId}${route.chapterId ? `/chapter/${route.chapterId}` : ''}`;
  return `#/${route.view}`;
}

function updateHash(route: AppRoute, replace: boolean): void {
  const nextHash = appRouteHash(route);
  if (window.location.hash === nextHash) {
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    return;
  }

  if (replace) {
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}${nextHash}`);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    return;
  }

  window.location.hash = nextHash;
}

export function pushAppRoute(route: AppRoute): void {
  updateHash(route, false);
}

export function replaceAppRoute(route: AppRoute): void {
  updateHash(route, true);
}

export function isProtectedRoute(route: AppRoute): boolean {
  return route.view !== 'square';
}
