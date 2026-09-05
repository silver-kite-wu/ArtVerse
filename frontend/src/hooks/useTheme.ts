import { useCallback, useEffect, useState } from 'react';

type Mode = 'dark' | 'light';

const KEY_MODE = 'artverse.mode';

function getSystemMode(): Mode {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'dark';
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function getStoredMode(): Mode | null {
  try {
    const v = localStorage.getItem(KEY_MODE);
    if (v === 'dark' || v === 'light') return v;
  } catch { /* noop */ }
  return null;
}

function apply(mode: Mode) {
  document.documentElement.setAttribute('data-mode', mode);
  document.documentElement.removeAttribute('data-theme');
}

export function useTheme() {
  const [mode, setModeState] = useState<Mode>(() => getStoredMode() ?? getSystemMode());

  useEffect(() => { apply(mode); }, [mode]);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia('(prefers-color-scheme: light)');
    const handler = (e: MediaQueryListEvent) => {
      if (!getStoredMode()) setModeState(e.matches ? 'light' : 'dark');
    };
    if (typeof mq.addEventListener !== 'function') return;
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, []);

  const setMode = useCallback((m: Mode) => {
    setModeState(m);
    try { localStorage.setItem(KEY_MODE, m); } catch { /* noop */ }
  }, []);

  const toggleMode = useCallback(() => {
    setModeState(prev => {
      const next: Mode = prev === 'dark' ? 'light' : 'dark';
      try { localStorage.setItem(KEY_MODE, next); } catch { /* noop */ }
      return next;
    });
  }, []);

  return { mode, setMode, toggleMode };
}
