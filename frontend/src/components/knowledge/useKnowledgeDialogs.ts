import { useEffect } from 'react';

/** Shared keyboard boundary for nested detail/edit/image dialogs. */
export function useKnowledgeDialogs() {
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    let focused: Element | null = null;
    let opened: HTMLElement[] = [];const returnFocus = new Map<HTMLElement, HTMLElement | null>();
    const dialogs = () => Array.from(document.querySelectorAll<HTMLElement>('.kb-modal[role="dialog"]'));
    const focusable = (dialog: HTMLElement) => Array.from(dialog.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]')).filter(e => e.getClientRects().length > 0);
    const observer = new MutationObserver(() => {const current = dialogs();for (const closed of opened.filter(d => !current.includes(d)).reverse()) {const trigger = returnFocus.get(closed);if (trigger?.isConnected) trigger.focus();returnFocus.delete(closed);}for (const added of current.filter(d => !opened.includes(d))) returnFocus.set(added, document.activeElement as HTMLElement | null);opened = current;const dialog = current.at(-1);if (dialog && dialog !== focused) {focused = dialog;focusable(dialog)[0]?.focus();}if (!dialog) focused = null;});
    observer.observe(document.body, {childList: true, subtree: true});
    const onKey = (e: KeyboardEvent) => {const dialog = dialogs().at(-1);if (!dialog) return;
      if (e.key === 'Escape') {e.preventDefault();dialog.querySelector<HTMLButtonElement>('header > button')?.click();}
      if (e.key === 'Tab') {const items = focusable(dialog);if (!items.length) return;const first = items[0], last = items[items.length - 1];if (e.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {e.preventDefault();last.focus();}else if (!e.shiftKey && (document.activeElement === last || !dialog.contains(document.activeElement))) {e.preventDefault();first.focus();}}
    };
    document.addEventListener('keydown', onKey);return () => {observer.disconnect();document.removeEventListener('keydown', onKey);previous?.focus();};
  }, []);
}
