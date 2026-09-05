import { useEffect, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

interface Props {
  children: ReactNode;
}

/**
 * Renders floating UI into the app shell's overlay layer (#overlay-root) so
 * every modal, lightbox, and toast shares a single positioning context
 * instead of the viewport. Viewport-fixed positioning degrades to
 * ancestor-relative whenever an ancestor carries transform, filter, or
 * backdrop-filter (glass panels, hover lifts) — and that degradation differs
 * across browsers. Absolute positioning against the shell is uniform.
 *
 * Falls back to inline rendering when the layer is absent (standalone
 * /internal pages, unit tests).
 */
export default function OverlayPortal({ children }: Props) {
  const [host, setHost] = useState<HTMLElement | null>(() =>
    typeof document === 'undefined' ? null : document.getElementById('overlay-root'),
  );

  useEffect(() => {
    setHost((current) => current ?? document.getElementById('overlay-root'));
  }, []);

  if (host) return createPortal(children, host);
  return <>{children}</>;
}
