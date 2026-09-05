export type AppView = 'square' | 'workspace' | 'editor' | 'imagegen' | 'myworks' | 'settings';

export type AppRoute =
  | { view: Exclude<AppView, 'editor'> }
  | { view: 'editor'; storyId: number }
  | { view: 'square'; format: 'novel' | 'manga'; storyId: number; chapterId?: number };

const SIMPLE_VIEWS = new Set<Exclude<AppView, 'editor'>>([
  'square',
  'workspace',
  'imagegen',
  'myworks',
  'settings',
]);

export function parseAppHash(hash: string): AppRoute | null {
  const path = hash.replace(/^#/, '').replace(/^\/+|\/+$/g, '');
  const [pathname] = path.split('?');
  if (!path) return null;

  if (SIMPLE_VIEWS.has(pathname as Exclude<AppView, 'editor'>)) {
    return { view: pathname as Exclude<AppView, 'editor'> };
  }

  const editorMatch = /^editor\/(\d+)$/.exec(path);
  const squareMatch = /^square\/(novel|manga)\/(\d+)(?:\/chapter\/(\d+))?$/.exec(pathname);
  if (squareMatch) return { view: 'square', format: squareMatch[1] as 'novel' | 'manga', storyId: Number(squareMatch[2]), ...(squareMatch[3] ? { chapterId: Number(squareMatch[3]) } : {}) };
  if (!editorMatch) return null;

  const storyId = Number(editorMatch[1]);
  return Number.isSafeInteger(storyId) && storyId > 0 ? { view: 'editor', storyId } : null;
}

export function appRouteHash(route: AppRoute): string {
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
