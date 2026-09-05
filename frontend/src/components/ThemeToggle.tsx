import { Moon, Sun } from 'lucide-react';
import { useTheme } from '../hooks/useTheme';

interface Props {
  compact?: boolean;
}

export default function ThemeToggle({ compact = false }: Props) {
  const { mode, toggleMode } = useTheme();
  const isDark = mode === 'dark';

  return (
    <button
      type="button"
      onClick={toggleMode}
      title={isDark ? '切换浅色模式' : '切换深色模式'}
      aria-label={isDark ? '切换浅色模式' : '切换深色模式'}
      className={
        'group flex items-center gap-3 rounded-lg text-sm font-medium transition-all duration-200 '
        + 'text-text-secondary hover:text-accent hover:bg-accent-soft '
        + (compact ? 'justify-center h-9 w-9 p-0' : 'min-h-10 w-full px-3')
      }
    >
      {isDark
        ? <Sun size={18} className="shrink-0 transition-transform group-hover:rotate-12" />
        : <Moon size={18} className="shrink-0 transition-transform group-hover:-rotate-12" />
      }
      {!compact && (
        <span className="flex-1 text-left">{isDark ? '浅色模式' : '深色模式'}</span>
      )}
    </button>
  );
}
