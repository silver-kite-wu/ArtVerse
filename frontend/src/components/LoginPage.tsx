import { useState } from 'react';
import { KeyRound, LogIn, Mail, Sparkles, User, UserPlus } from 'lucide-react';
import { loginUser, registerUser } from '../api';

interface Props {
  onAuthSuccess: () => void;
  variant?: 'page' | 'modal';
  message?: string;
  onCancel?: () => void;
}

export default function LoginPage({ onAuthSuccess, variant = 'page', message, onCancel }: Props) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const isModal = variant === 'modal';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (!username.trim() || !password.trim()) {
      setError('请填写用户名和密码');
      return;
    }
    if (mode === 'register' && !email.trim()) {
      setError('请填写邮箱');
      return;
    }
    setLoading(true);
    try {
      if (mode === 'login') {
        await loginUser(username.trim(), password);
      } else {
        await registerUser(username.trim(), email.trim(), password);
      }
      onAuthSuccess();
    } catch (err: any) {
      setError(err.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className={(isModal ? '' : 'min-h-screen bg-paper-base ') + 'flex items-center justify-center p-4'}>
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="animate-stamp mb-4 inline-flex h-14 w-14 items-center justify-center rounded-xl border-2 border-vermilion/20 bg-vermilion-light/20">
            <Sparkles size={28} className="text-vermilion" />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-sumi">ArtVerse</h1>
          <p className="mt-1 text-sm text-sumi-dim">AI 漫画创作工坊</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4 rounded-xl border border-paper-border bg-paper-raised p-6 shadow-md">
          {message && (
            <p className="rounded-md border border-kinpaku/20 bg-kinpaku-light/50 px-3 py-2 text-sm text-kinpaku">
              {message}
            </p>
          )}

          <h2 className="text-sm font-semibold text-sumi">{mode === 'login' ? '登录' : '注册'}</h2>

          <div className="space-y-1.5">
            <label className="flex items-center gap-1.5 text-xs text-sumi-dim">
              <User size={12} />用户名
            </label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="输入用户名"
              autoFocus
              className="w-full rounded-md border border-paper-border bg-paper-surface px-3 py-2 text-sm text-sumi placeholder-sumi-faint outline-none transition-colors focus:border-vermilion"
            />
          </div>

          {mode === 'register' && (
            <div className="space-y-1.5">
              <label className="flex items-center gap-1.5 text-xs text-sumi-dim">
                <Mail size={12} />邮箱
              </label>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="输入邮箱地址"
                className="w-full rounded-md border border-paper-border bg-paper-surface px-3 py-2 text-sm text-sumi placeholder-sumi-faint outline-none transition-colors focus:border-vermilion"
              />
            </div>
          )}

          <div className="space-y-1.5">
            <label className="flex items-center gap-1.5 text-xs text-sumi-dim">
              <KeyRound size={12} />密码
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="输入密码"
              className="w-full rounded-md border border-paper-border bg-paper-surface px-3 py-2 text-sm text-sumi placeholder-sumi-faint outline-none transition-colors focus:border-vermilion"
            />
          </div>

          {error && (
            <p className="rounded-md border border-vermilion/20 bg-vermilion-light/20 px-3 py-2 text-xs text-vermilion">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={loading}
            className="flex w-full items-center justify-center gap-2 rounded-md bg-vermilion py-2.5 text-sm font-medium text-white transition-colors hover:bg-vermilion-hover disabled:opacity-50"
          >
            {loading ? (
              <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
            ) : mode === 'login' ? (
              <LogIn size={16} />
            ) : (
              <UserPlus size={16} />
            )}
            {mode === 'login' ? '登录' : '注册'}
          </button>

          <button
            type="button"
            onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(''); }}
            className="w-full text-center text-xs text-sumi-dim transition-colors hover:text-vermilion"
          >
            {mode === 'login' ? '还没有账号？立即注册' : '已有账号？去登录'}
          </button>

          {isModal && onCancel && (
            <button
              type="button"
              onClick={onCancel}
              className="w-full text-center text-xs text-sumi-faint transition-colors hover:text-sumi-dim"
            >
              稍后再说
            </button>
          )}
        </form>
      </div>
    </div>
  );
}
