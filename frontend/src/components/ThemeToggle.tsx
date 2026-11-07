import { Moon } from 'lucide-react';
import { useTheme } from '../hooks/useTheme';
import Switch from '../ui/Switch';

interface Props {
  compact?: boolean;
}

export default function ThemeToggle({ compact = false }: Props) {
  const { mode, setMode } = useTheme();
  const isDark = mode === 'dark';
  const hint = isDark ? '关闭深色模式' : '开启深色模式';

  return (
    <div
      title={hint}
      className={
        'flex items-center rounded-lg '
        + (compact ? 'h-10 w-10 justify-center' : 'min-h-10 w-full gap-3 px-3')
      }
    >
      {!compact && (
        <>
          <Moon size={18} className={'shrink-0 transition-colors ' + (isDark ? 'text-accent' : 'text-text-muted')} />
          <span className={'flex-1 text-left text-sm font-medium transition-colors ' + (isDark ? 'text-text-primary' : 'text-text-secondary')}>
            深色模式
          </span>
        </>
      )}
      <Switch
        checked={isDark}
        onChange={(checked, origin) => setMode(checked ? 'dark' : 'light', origin)}
        ariaLabel={hint}
      />
    </div>
  );
}
