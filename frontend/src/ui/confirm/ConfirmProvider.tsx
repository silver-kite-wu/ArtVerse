import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { AlertTriangle } from 'lucide-react';
import OverlayPortal from '../OverlayPortal';
import { ConfirmContext, type ConfirmFn, type ConfirmOptions } from './confirmContext';

interface PendingConfirm {
  options: ConfirmOptions;
  resolve: (confirmed: boolean) => void;
}

export default function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<PendingConfirm | null>(null);
  const pendingRef = useRef<PendingConfirm | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const restoreFocusRef = useRef<Element | null>(null);

  const settle = useCallback((confirmed: boolean) => {
    const current = pendingRef.current;
    pendingRef.current = null;
    setPending(null);
    if (restoreFocusRef.current instanceof HTMLElement) restoreFocusRef.current.focus();
    current?.resolve(confirmed);
  }, []);

  const confirm = useCallback<ConfirmFn>((options) => new Promise<boolean>((resolve) => {
    pendingRef.current?.resolve(false);
    restoreFocusRef.current = document.activeElement;
    const next = { options, resolve };
    pendingRef.current = next;
    setPending(next);
  }), []);

  useEffect(() => {
    if (pending) cancelRef.current?.focus();
  }, [pending]);

  useEffect(() => {
    if (!pending) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        settle(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [pending, settle]);

  const danger = pending?.options.tone === 'danger';

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {pending && (
        <OverlayPortal>
          <div
            className="absolute inset-0 z-[160] flex items-center justify-center bg-black/45 p-4"
            onMouseDown={(event) => {
              if (event.target === event.currentTarget) settle(false);
            }}
          >
            <section
              role="dialog"
              aria-modal="true"
              aria-labelledby="artverse-confirm-title"
              aria-describedby="artverse-confirm-message"
              className="w-full max-w-sm rounded-2xl border border-border bg-bg-raised p-5 shadow-2xl"
            >
              <div className="flex items-start gap-3">
                {danger && (
                  <span className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-danger/15 text-danger" aria-hidden="true">
                    <AlertTriangle size={18} />
                  </span>
                )}
                <div className="min-w-0 flex-1">
                  <h2 id="artverse-confirm-title" className="text-base font-semibold text-text-primary">
                    {pending.options.title ?? '请确认'}
                  </h2>
                  <p id="artverse-confirm-message" className="mt-2 max-h-[50vh] overflow-y-auto whitespace-pre-wrap break-words text-sm leading-6 text-text-secondary">
                    {pending.options.message}
                  </p>
                </div>
              </div>
              <div className="mt-5 flex justify-end gap-2">
                <button
                  ref={cancelRef}
                  type="button"
                  onClick={() => settle(false)}
                  className="rounded-lg border border-border px-3.5 py-2 text-sm font-medium text-text-secondary transition-colors hover:bg-bg-surface hover:text-text-primary"
                >
                  {pending.options.cancelText ?? '取消'}
                </button>
                <button
                  type="button"
                  onClick={() => settle(true)}
                  className={'rounded-lg px-3.5 py-2 text-sm font-semibold text-white transition-colors '
                    + (danger ? 'bg-danger hover:opacity-90' : 'bg-accent hover:bg-accent-hover')}
                >
                  {pending.options.confirmText ?? '确定'}
                </button>
              </div>
            </section>
          </div>
        </OverlayPortal>
      )}
    </ConfirmContext.Provider>
  );
}
