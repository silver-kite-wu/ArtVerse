import { useSyncExternalStore } from 'react';

type Mode = 'dark' | 'light';
export type ThemePreference = Mode | 'system';
const KEY = 'artverse.mode';
const listeners = new Set<() => void>();
const media = window.matchMedia('(prefers-color-scheme: light)');
function stored(): ThemePreference {
  try { const value = localStorage.getItem(KEY); return value === 'dark' || value === 'light' ? value : 'system'; }
  catch { return 'system'; }
}
let preference = stored();
let mode: Mode = preference === 'system' ? (media.matches ? 'light' : 'dark') : preference;
function sync() {
  mode = preference === 'system' ? (media.matches ? 'light' : 'dark') : preference;
  document.documentElement.setAttribute('data-mode', mode);
  listeners.forEach(listener => listener());
}
function setPreference(value: ThemePreference) {
  preference = value;
  try { localStorage.setItem(KEY, value); } catch { /* Storage can be disabled. */ }
  sync();
}
function subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }
media.addEventListener('change', sync);
window.addEventListener('storage', event => { if (event.key === KEY || event.key === null) { preference = stored(); sync(); } });
sync();
export function useTheme() {
  useSyncExternalStore(subscribe, () => `${preference}:${mode}`);
  return { mode, preference, setPreference, setMode: setPreference, toggleMode: () => setPreference(mode === 'dark' ? 'light' : 'dark') };
}
