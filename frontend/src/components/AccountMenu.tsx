import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, KeyRound, LogOut, Monitor, Moon, Sun, UserRound } from 'lucide-react';
import type { UserInfo } from '../api/auth';
import { useTheme } from '../hooks/useTheme';
import OverlayPortal from '../ui/OverlayPortal';
import UserAvatar from './UserAvatar';

interface Props { user: UserInfo; compact?: boolean; mobile?: boolean; onProfile: () => void; onSettings: () => void; onLogout: () => Promise<void> }
export default function AccountMenu({ user, compact, mobile, onProfile, onSettings, onLogout }: Props) {
  const [open, setOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [position, setPosition] = useState({ left: 12, top: 12 });
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const id = useId();
  const { preference, setPreference } = useTheme();
  const close = () => { setOpen(false); trigger.current?.focus(); };
  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const anchor = trigger.current?.getBoundingClientRect();
      const box = panel.current?.getBoundingClientRect();
      const host = document.getElementById('overlay-root')?.getBoundingClientRect();
      if (!anchor || !box) return;
      const left = Math.max(12, Math.min(mobile ? anchor.right - box.width : anchor.left, window.innerWidth - box.width - 12));
      const top = Math.max(12, Math.min(mobile ? anchor.bottom + 10 : anchor.top - box.height - 10, window.innerHeight - box.height - 12));
      setPosition({ left: left - (host?.left ?? 0), top: top - (host?.top ?? 0) });
    };
    place();
    panel.current?.querySelector<HTMLButtonElement>('button')?.focus();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); };
  }, [open, mobile]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!panel.current?.contains(event.target as Node) && !trigger.current?.contains(event.target as Node)) setOpen(false);
    };
    const keyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); close(); }
      const buttons = [...(panel.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])];
      const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
      if (event.key === 'Tab') {
        if (event.shiftKey && index === 0) { event.preventDefault(); buttons.at(-1)?.focus(); }
        else if (!event.shiftKey && index === buttons.length - 1) { event.preventDefault(); buttons[0]?.focus(); }
      }
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        event.preventDefault();
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
        buttons[next]?.focus();
      }
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', keyboard);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', keyboard); };
  }, [open]);
  return <>
    <button ref={trigger} type="button" className={'account-trigger' + (compact ? ' is-compact' : '')} aria-label={`账号菜单：${user.username}`} aria-expanded={open} aria-controls={open ? id : undefined} aria-haspopup="dialog" onClick={() => setOpen(value => !value)}>
      <UserAvatar user={user} />
      {!compact && <><span className="account-identity"><strong>{user.username}</strong><small>{user.email}</small></span>{open ? <ChevronDown size={15} /> : <ChevronUp size={15} />}</>}
    </button>
    {open && <OverlayPortal><div ref={panel} id={id} className="account-popover" role="dialog" aria-label="账号选项" style={position}>
      <div className="account-popover-heading"><UserAvatar user={user} /><span className="account-identity"><strong>{user.username}</strong><small>{user.email}</small></span></div>
      <div className="account-menu-links">
        <button type="button" onClick={() => { close(); onProfile(); }}><UserRound size={17} /><span>个人资料</span></button>
        <button type="button" onClick={() => { close(); onSettings(); }}><KeyRound size={17} /><span>API 设置</span></button>
      </div>
      <fieldset className="account-appearance"><legend>颜色模式</legend><div>
        {([{ value: 'light', label: '浅色', Icon: Sun }, { value: 'dark', label: '深色', Icon: Moon }, { value: 'system', label: '跟随系统', Icon: Monitor }] as const).map(({ value, label, Icon }) => <button key={value} type="button" aria-pressed={preference === value} onClick={() => setPreference(value)}><Icon size={16} /><span>{label}</span></button>)}
      </div></fieldset>
      <div className="account-menu-links account-logout"><button type="button" disabled={loggingOut} onClick={async () => {
        if (loggingOut || !window.confirm('确定退出当前账号吗？')) return;
        setLoggingOut(true);
        try { await onLogout(); setOpen(false); } finally { setLoggingOut(false); }
      }}><LogOut size={17} /><span>{loggingOut ? '正在退出…' : '退出登录'}</span></button></div>
    </div></OverlayPortal>}
  </>;
}
