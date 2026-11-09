import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Check, ChevronDown, Lock, ShieldCheck, Unlock } from 'lucide-react';
import { createPortal } from 'react-dom';
import {
  AI_PERMISSION_CHANGE_EVENT,
  AI_PERMISSION_OPTIONS,
  fetchAiPermission,
  isAiPermissionMode,
  saveAiPermission,
  type AiPermissionMode,
} from '../api/aiPermission';

const MODE_ICONS = { MANUAL: Lock, AUTO: ShieldCheck, FULL: Unlock } as const;

interface PermissionModeSwitcherProps {
  disabled?: boolean;
  onModeChange?: (mode: AiPermissionMode) => void;
}

/** AI 助手的运行时权限，与定时任务共用同一份用户设置。 */
export default function PermissionModeSwitcher({ disabled, onModeChange }: PermissionModeSwitcherProps) {
  const [mode, setMode] = useState<AiPermissionMode>('AUTO');
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const modeChangeRef = useRef(onModeChange);
  modeChangeRef.current = onModeChange;

  useEffect(() => {
    let alive = true;
    fetchAiPermission().then((value) => {
      if (!alive) return;
      setMode(value);
      modeChangeRef.current?.(value);
    }).catch(() => undefined);
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    const sync = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      if (isAiPermissionMode(detail)) {
        setMode(detail);
        modeChangeRef.current?.(detail);
      }
    };
    window.addEventListener(AI_PERMISSION_CHANGE_EVENT, sync);
    return () => window.removeEventListener(AI_PERMISSION_CHANGE_EVENT, sync);
  }, []);

  const close = useCallback(() => setOpen(false), []);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (!menuRef.current?.contains(event.target as Node) && !triggerRef.current?.contains(event.target as Node)) close();
    };
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [open, close]);

  useLayoutEffect(() => {
    if (!open) return;
    const position = () => {
      const menu = menuRef.current;
      const trigger = triggerRef.current;
      if (!menu || !trigger) return;
      const rect = trigger.getBoundingClientRect();
      const width = Math.min(280, window.innerWidth - 24);
      menu.style.width = `${width}px`;
      menu.style.left = `${Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12))}px`;
      const above = rect.top - menu.offsetHeight - 8;
      menu.style.top = `${above >= 12 ? above : rect.bottom + 8}px`;
    };
    position();
    window.addEventListener('resize', position);
    window.addEventListener('scroll', position, true);
    return () => {
      window.removeEventListener('resize', position);
      window.removeEventListener('scroll', position, true);
    };
  }, [open]);

  const choose = async (next: AiPermissionMode) => {
    close();
    if (next === mode || saving) return;
    const previous = mode;
    setMode(next);
    modeChangeRef.current?.(next);
    setSaving(true);
    try {
      await saveAiPermission(next);
    } catch {
      setMode(previous);
      modeChangeRef.current?.(previous);
    } finally {
      setSaving(false);
    }
  };

  const current = AI_PERMISSION_OPTIONS.find((option) => option.value === mode) ?? AI_PERMISSION_OPTIONS[1];
  const Icon = MODE_ICONS[mode];

  return (
    <div className="model-switcher permission-switcher">
      <button
        ref={triggerRef}
        type="button"
        className="model-switcher__trigger"
        disabled={disabled || saving}
        aria-haspopup="listbox"
        aria-expanded={open}
        title={`AI 运行权限：${current.label}`}
        onClick={() => setOpen((value) => !value)}
      >
        <Icon size={12} />
        <span className="model-switcher__label">{current.label}</span>
        <ChevronDown size={14} className="model-switcher__chevron" />
      </button>
      {open && createPortal(
        <div ref={menuRef} className="model-switcher__menu permission-switcher__menu" role="listbox" aria-label="AI 运行权限">
          <div className="model-switcher__heading"><span>AI 运行权限</span><span>作用于后续对话</span></div>
          {AI_PERMISSION_OPTIONS.map((option) => {
            const OptionIcon = MODE_ICONS[option.value];
            const selected = option.value === mode;
            return (
              <button
                key={option.value}
                type="button"
                role="option"
                aria-selected={selected}
                className="model-switcher__option"
                onClick={() => void choose(option.value)}
              >
                <OptionIcon size={14} className="permission-switcher__icon" />
                <span className="model-switcher__option-label" title={option.description}>
                  {option.label}
                  <small>{option.description}</small>
                </span>
                {selected && <Check size={14} />}
              </button>
            );
          })}
        </div>,
        document.body,
      )}
    </div>
  );
}
