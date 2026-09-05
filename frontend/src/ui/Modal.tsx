import { useEffect, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import OverlayPortal from './OverlayPortal';

interface Props {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  title?: string;
  /** Max width class. Default 'max-w-lg'. */
  size?: 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
}

const sizeClass: Record<string, string> = {
  sm: 'max-w-sm',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
  xl: 'max-w-4xl',
};

export default function Modal({ open, onClose, children, title, size = 'md', className = '' }: Props) {
  const overlayRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <OverlayPortal>
      <div
        ref={overlayRef}
        className="absolute inset-0 z-50 flex items-center justify-center bg-bg-overlay p-4 backdrop-blur-sm"
        onClick={(e) => { if (e.target === overlayRef.current) onClose(); }}
      >
        <div
          className={`animate-scale-in w-full ${sizeClass[size]} glass-panel rounded-2xl ${className}`}
          onClick={(e) => e.stopPropagation()}
        >
        {title && (
          <div className="flex items-center justify-between border-b border-border px-6 py-4">
            <h2 className="font-display text-lg font-semibold text-text-primary">{title}</h2>
            <button
              type="button"
              onClick={onClose}
              className="flex h-8 w-8 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-accent-soft hover:text-text-primary"
            >
              <X size={16} />
            </button>
          </div>
        )}
        <div className={title ? 'p-6' : 'p-0'}>{children}</div>
      </div>
      </div>
    </OverlayPortal>
  );
}
