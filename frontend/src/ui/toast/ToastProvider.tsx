import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AlertCircle, AlertTriangle, CheckCircle2, Info, X } from 'lucide-react';
import OverlayPortal from '../OverlayPortal';
import { ToastContext, type ToastOptions, type ToastTone } from './ToastContext';

interface ToastEntry {
  id: string;
  message: string;
  tone: ToastTone;
  duration: number | null;
  action?: ToastOptions['action'];
  revision: number;
  closing?: boolean;
}

const DEFAULT_DURATION: Record<ToastTone, number> = {
  success: 3500,
  info: 4500,
  warning: 6000,
  error: 8000,
};

const toneStyles: Record<ToastTone, { icon: ReactNode; className: string }> = {
  success: {
    icon: <CheckCircle2 size={19} />,
    className: 'border-success/35 text-success',
  },
  error: {
    icon: <AlertCircle size={19} />,
    className: 'border-danger/40 text-danger',
  },
  warning: {
    icon: <AlertTriangle size={19} />,
    className: 'border-warning/40 text-warning',
  },
  info: {
    icon: <Info size={19} />,
    className: 'border-accent/35 text-accent',
  },
};

function ToastCard({ toast, onDismiss }: { toast: ToastEntry; onDismiss: (id: string) => void }) {
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if (paused || toast.duration === null || toast.closing) return undefined;
    const timer = window.setTimeout(() => onDismiss(toast.id), toast.duration);
    return () => window.clearTimeout(timer);
  }, [onDismiss, paused, toast.closing, toast.duration, toast.id, toast.revision]);

  const style = toneStyles[toast.tone];
  const urgent = toast.tone === 'error' || toast.tone === 'warning';

  return (
    <div
      role={urgent ? 'alert' : 'status'}
      aria-live={urgent ? 'assertive' : 'polite'}
      aria-atomic="true"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocusCapture={() => setPaused(true)}
      onBlurCapture={() => setPaused(false)}
      className={`pointer-events-auto flex w-full items-start gap-3 rounded-2xl border bg-bg-raised/95 px-4 py-3.5 shadow-xl backdrop-blur-xl ${toast.closing ? 'animate-toast-out' : 'animate-toast-in'} ${style.className}`}
    >
      <span className="mt-0.5 shrink-0" aria-hidden="true">{style.icon}</span>
      <div className="min-w-0 flex-1">
        <p className="break-words text-sm leading-5 text-text-primary">{toast.message}</p>
        {toast.action && (
          <button
            type="button"
            onClick={() => {
              toast.action?.onClick();
              onDismiss(toast.id);
            }}
            className="mt-2 text-xs font-semibold text-current underline-offset-2 hover:underline"
          >
            {toast.action.label}
          </button>
        )}
      </div>
      <button
        type="button"
        onClick={() => onDismiss(toast.id)}
        className="-mr-1 -mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-bg-surface hover:text-text-primary"
        aria-label="关闭提示"
      >
        <X size={15} />
      </button>
    </div>
  );
}

export default function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastEntry[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: string) => {
    setToasts((current) => current.map((toast) => toast.id === id ? { ...toast, closing: true } : toast));
    window.setTimeout(() => {
      setToasts((current) => current.filter((toast) => toast.id !== id));
    }, 180);
  }, []);

  const show = useCallback((message: string, options: ToastOptions = {}) => {
    const normalizedMessage = message.trim();
    if (!normalizedMessage) return '';
    const tone = options.tone ?? 'info';
    const id = `toast-${++nextId.current}`;
    setToasts((current) => {
      const duplicate = current.find((toast) => toast.tone === tone && toast.message === normalizedMessage);
      if (duplicate) {
        const replacement: ToastEntry = {
          id,
          message: normalizedMessage,
          tone,
          duration: options.duration === undefined ? DEFAULT_DURATION[tone] : options.duration,
          action: options.action,
          revision: duplicate.revision + 1,
        };
        return [...current.filter((toast) => toast.id !== duplicate.id), replacement].slice(-3);
      }
      const next: ToastEntry = {
        id,
        message: normalizedMessage,
        tone,
        duration: options.duration === undefined ? DEFAULT_DURATION[tone] : options.duration,
        action: options.action,
        revision: 0,
      };
      return [...current, next].slice(-3);
    });
    return id;
  }, []);

  const value = useMemo(() => ({
    show,
    dismiss,
    success: (message: string, options?: Omit<ToastOptions, 'tone'>) => show(message, { ...options, tone: 'success' }),
    error: (message: string, options?: Omit<ToastOptions, 'tone'>) => show(message, { ...options, tone: 'error' }),
    warning: (message: string, options?: Omit<ToastOptions, 'tone'>) => show(message, { ...options, tone: 'warning' }),
    info: (message: string, options?: Omit<ToastOptions, 'tone'>) => show(message, { ...options, tone: 'info' }),
  }), [dismiss, show]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <OverlayPortal>
        <div className="pointer-events-none absolute right-3 top-[calc(0.75rem+env(safe-area-inset-top))] z-[70] flex w-[min(24rem,calc(100vw-1.5rem))] flex-col gap-2.5 sm:right-5 sm:top-5">
          {toasts.map((toast) => <ToastCard key={toast.id} toast={toast} onDismiss={dismiss} />)}
        </div>
      </OverlayPortal>
    </ToastContext.Provider>
  );
}
