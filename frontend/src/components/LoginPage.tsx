import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import {
  Eye,
  EyeOff,
  KeyRound,
  LoaderCircle,
  LogIn,
  Mail,
  ShieldCheck,
  Sparkles,
  User,
  UserPlus,
} from 'lucide-react';
import {
  ApiError,
  getChallengeConfig,
  loginUser,
  registerUser,
  type ChallengeConfig,
} from '../api';
import CaptchaWidget from './CaptchaWidget';

interface Props {
  onAuthSuccess: () => void;
  variant?: 'page' | 'modal';
  message?: string;
  onCancel?: () => void;
}

type Mode = 'login' | 'register';
type ChallengeStatus = 'idle' | 'loading' | 'ready' | 'verified' | 'expired' | 'error';

const DEFAULT_CHALLENGE_CONFIG: ChallengeConfig = {
  enabled: false,
  provider: '',
  siteKey: '',
  registrationRequired: false,
  loginMode: 'disabled',
};

const MIN_PASSWORD_LENGTH = 15;
const PASSWORD_HINT = '注册密码建议使用短语式长密码，长度至少 15 个字符。';

function codePointLength(value: string): number {
  return [...value].length;
}

function challengeStatusText(status: ChallengeStatus, mode: Mode): string {
  switch (status) {
    case 'loading':
      return '验证码加载中...';
    case 'ready':
      return mode === 'register' ? '请完成图形验证码后再注册' : '请输入图形验证码后登录';
    case 'verified':
      return '验证码已输入';
    case 'expired':
      return '验证码已过期，请刷新后重试';
    case 'error':
      return '验证码加载失败，请刷新后重试';
    default:
      return mode === 'register' ? '注册前需要完成图形验证码' : '请完成图形验证码后登录';
  }
}

export default function LoginPage({ onAuthSuccess, variant = 'page', message, onCancel }: Props) {
  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [challengeConfig, setChallengeConfig] = useState<ChallengeConfig>(DEFAULT_CHALLENGE_CONFIG);
  const [registerChallengeToken, setRegisterChallengeToken] = useState<string | null>(null);
  const [registerChallengeStatus, setRegisterChallengeStatus] = useState<ChallengeStatus>('idle');
  const [registerChallengeResetSignal, setRegisterChallengeResetSignal] = useState(0);
  const [loginChallengeVisible, setLoginChallengeVisible] = useState(false);
  const [loginChallengeToken, setLoginChallengeToken] = useState<string | null>(null);
  const [loginChallengeStatus, setLoginChallengeStatus] = useState<ChallengeStatus>('idle');
  const [loginChallengeResetSignal, setLoginChallengeResetSignal] = useState(0);
  const usernameInputRef = useRef<HTMLInputElement | null>(null);
  const idPrefix = useId();

  const isModal = variant === 'modal';
  const registerMode = mode === 'register';
  const supportsCaptcha = challengeConfig.enabled
    && challengeConfig.provider === 'graphic-captcha';
  const loginAlwaysChallenge = supportsCaptcha && challengeConfig.loginMode === 'always';
  const registerChallengeVisible = registerMode
    && supportsCaptcha
    && challengeConfig.registrationRequired;
  const loginChallengeActive = !registerMode && supportsCaptcha && (loginAlwaysChallenge || loginChallengeVisible);
  const dialogTitleId = `${idPrefix}-title`;
  const dialogDescriptionId = `${idPrefix}-description`;
  const usernameId = `${idPrefix}-username`;
  const emailId = `${idPrefix}-email`;
  const passwordId = `${idPrefix}-password`;
  const confirmPasswordId = `${idPrefix}-confirm-password`;

  useEffect(() => {
    let active = true;
    void getChallengeConfig()
      .then((config) => {
        if (!active) return;
        setChallengeConfig(config);
      })
      .catch(() => {
        if (!active) return;
        setChallengeConfig(DEFAULT_CHALLENGE_CONFIG);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    usernameInputRef.current?.focus();
  }, [mode]);

  const switchMode = (nextMode: Mode) => {
    setMode(nextMode);
    setError('');
    setPassword('');
    setConfirmPassword('');
    setShowPassword(false);
    setShowConfirmPassword(false);
    setLoginChallengeVisible(false);
    setLoginChallengeToken(null);
    setLoginChallengeStatus('idle');
    setLoginChallengeResetSignal((value) => value + 1);
    if (nextMode === 'login') {
      setRegisterChallengeToken(null);
      setRegisterChallengeStatus('idle');
      setRegisterChallengeResetSignal((value) => value + 1);
    }
  };

  const inputClass =
    'w-full rounded-[18px] border border-border/80 bg-bg-surface/85 px-4 py-3.5 text-sm text-text-primary outline-none! transition-[border-color,background-color] placeholder:text-text-muted focus:border-accent/50 focus:bg-bg-surface';

  const handleAuthError = (fallbackMessage: string, err: unknown, currentMode: Mode) => {
    if (err instanceof ApiError) {
      if (currentMode === 'login' && err.code === 'CHALLENGE_REQUIRED') {
        setLoginChallengeVisible(true);
        setLoginChallengeToken(null);
        setLoginChallengeStatus('idle');
        setLoginChallengeResetSignal((value) => value + 1);
        setError(err.message || '请先完成图形验证码');
        return;
      }
      if (err.code === 'CHALLENGE_FAILED' || err.code === 'CHALLENGE_UNAVAILABLE') {
        if (currentMode === 'login') {
          setLoginChallengeVisible(true);
          setLoginChallengeToken(null);
          setLoginChallengeStatus('idle');
          setLoginChallengeResetSignal((value) => value + 1);
        } else {
          setRegisterChallengeToken(null);
          setRegisterChallengeStatus('idle');
          setRegisterChallengeResetSignal((value) => value + 1);
        }
      }
      setError(err.message || fallbackMessage);
      return;
    }
    if (err instanceof Error) {
      setError(err.message || fallbackMessage);
      return;
    }
    setError(fallbackMessage);
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (loading) return;

    const trimmedUsername = username.trim();
    const trimmedEmail = email.trim();

    setError('');
    if (!trimmedUsername || !password.trim()) {
      setError('请填写用户名和密码');
      return;
    }
    if (registerMode && !trimmedEmail) {
      setError('请填写邮箱');
      return;
    }
    if (registerMode && codePointLength(password) < MIN_PASSWORD_LENGTH) {
      setError(PASSWORD_HINT);
      return;
    }
    if (registerMode && password !== confirmPassword) {
      setError('两次输入的密码不一致');
      return;
    }
    if (registerChallengeVisible && !registerChallengeToken) {
      setError('请先完成图形验证码后再注册');
      return;
    }
    if (loginChallengeActive && !loginChallengeToken) {
      setError('请先完成图形验证码');
      return;
    }

    setLoading(true);
    try {
      if (registerMode) {
        await registerUser(trimmedUsername, trimmedEmail, password, registerChallengeToken || undefined);
      } else {
        await loginUser(trimmedUsername, password, loginChallengeToken || undefined);
      }
      onAuthSuccess();
    } catch (err) {
      handleAuthError(registerMode ? '注册失败，请稍后重试' : '登录失败，请稍后重试', err, mode);
    } finally {
      setLoading(false);
    }
  };

  const renderPasswordField = (
    label: string,
    id: string,
    value: string,
    onChange: (nextValue: string) => void,
    visible: boolean,
    onVisibleChange: (nextValue: boolean) => void,
    placeholder: string,
  ) => (
    <div className="space-y-2">
      <label htmlFor={id} className="flex items-center gap-1.5 text-sm font-medium text-text-secondary">
        <KeyRound size={14} className="text-accent/70" />
        {label}
      </label>
      <div className="relative">
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          autoComplete={id === passwordId ? (registerMode ? 'new-password' : 'current-password') : 'new-password'}
          className={inputClass + ' pr-12'}
        />
        <button
          type="button"
          onClick={() => onVisibleChange(!visible)}
          className="absolute inset-y-0 right-3 flex items-center text-text-muted transition-colors hover:text-text-secondary"
          aria-label={visible ? `隐藏${label}` : `显示${label}`}
        >
          {visible ? <EyeOff size={18} /> : <Eye size={18} />}
        </button>
      </div>
    </div>
  );

  const challengeBlockVisible = registerChallengeVisible || loginChallengeActive;
  const currentChallengeMode: Mode = registerChallengeVisible ? 'register' : 'login';
  const currentChallengeStatus = registerChallengeVisible ? registerChallengeStatus : loginChallengeStatus;

  return (
    <div className={(isModal ? '' : 'min-h-dvh bg-bg-base ') + 'flex items-center justify-center p-4 sm:p-6'}>
      <div className="flex w-full max-w-[38rem] min-h-0 flex-col">
        <div className="mb-5 flex items-center justify-center gap-3 sm:mb-6">
          <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-accent text-white shadow-glow">
            <Sparkles size={20} />
          </div>
          <div className="text-left">
            <h1 className="font-display text-[2rem] font-semibold leading-none text-text-primary">ArtVerse</h1>
            <p className="mt-1 text-sm text-text-muted">AI 漫画创作工坊</p>
          </div>
        </div>

        <form
          onSubmit={handleSubmit}
          role={isModal ? 'dialog' : undefined}
          aria-modal={isModal ? 'true' : undefined}
          aria-labelledby={isModal ? dialogTitleId : undefined}
          aria-describedby={isModal ? dialogDescriptionId : undefined}
          className="relative flex h-[min(46rem,calc(100dvh-2rem))] flex-col overflow-hidden rounded-[32px] border border-border/80 bg-bg-raised/95 p-5 sm:p-7"
        >
          <div className="pointer-events-none absolute inset-x-12 top-0 h-28 rounded-full bg-[radial-gradient(circle,rgba(241,72,30,0.16)_0%,rgba(241,72,30,0.06)_32%,rgba(241,72,30,0)_76%)] blur-2xl" />

          <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto pr-1 sm:gap-6">
            <div className="sr-only">
              <h2 id={dialogTitleId}>{registerMode ? '注册 ArtVerse 账号' : '登录 ArtVerse 账号'}</h2>
              <p id={dialogDescriptionId}>登录和注册窗口采用固定尺寸布局，切换表单不会改变弹框大小。</p>
            </div>

            {message && !registerMode && (
              <p className="rounded-[20px] border border-accent/25 bg-[linear-gradient(135deg,rgba(241,72,30,0.10),rgba(241,72,30,0.03))] px-4 py-3 text-sm text-accent">
                {message}
              </p>
            )}

            <div className="rounded-[22px] border border-border/80 bg-bg-surface/60 p-1.5">
              <div className="grid grid-cols-2 gap-1">
                <button
                  type="button"
                  onClick={() => switchMode('login')}
                  className={
                    'rounded-[18px] px-4 py-3 text-sm font-semibold transition-all duration-200 '
                    + (mode === 'login'
                      ? 'bg-bg-raised text-text-primary'
                      : 'text-text-muted hover:text-text-primary')
                  }
                >
                  登录
                </button>
                <button
                  type="button"
                  onClick={() => switchMode('register')}
                  className={
                    'rounded-[18px] px-4 py-3 text-sm font-semibold transition-all duration-200 '
                    + (mode === 'register'
                      ? 'bg-bg-raised text-text-primary'
                      : 'text-text-muted hover:text-text-primary')
                  }
                >
                  注册
                </button>
              </div>
            </div>

            <div className="grid gap-4 sm:gap-5">
              <div className="space-y-2">
                <label htmlFor={usernameId} className="flex items-center gap-1.5 text-sm font-medium text-text-secondary">
                  <User size={14} className="text-accent/70" />
                  用户名
                </label>
                <input
                  ref={usernameInputRef}
                  id={usernameId}
                  type="text"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  placeholder="输入用户名"
                  autoComplete={registerMode ? 'username' : 'username'}
                  className={inputClass}
                />
              </div>

              {registerMode && (
                <div className="space-y-2">
                  <label htmlFor={emailId} className="flex items-center gap-1.5 text-sm font-medium text-text-secondary">
                    <Mail size={14} className="text-accent/70" />
                    邮箱
                  </label>
                  <input
                    id={emailId}
                    type="email"
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                    placeholder="输入邮箱地址"
                    autoComplete="email"
                    className={inputClass}
                  />
                </div>
              )}

              {renderPasswordField(
                '密码',
                passwordId,
                password,
                setPassword,
                showPassword,
                setShowPassword,
                registerMode ? `输入至少 ${MIN_PASSWORD_LENGTH} 个字符的密码` : '输入密码',
              )}

              {registerMode && renderPasswordField(
                '确认密码',
                confirmPasswordId,
                confirmPassword,
                setConfirmPassword,
                showConfirmPassword,
                setShowConfirmPassword,
                '再次输入密码',
              )}
            </div>

            {challengeBlockVisible && (
              <div className="space-y-2">
                <p className="flex items-center gap-1.5 text-sm font-medium text-text-secondary">
                  <ShieldCheck size={14} className="text-accent/70" />
                  图形验证码
                </p>
                {supportsCaptcha ? (
                  <CaptchaWidget
                    visible={challengeBlockVisible}
                    resetSignal={registerMode ? registerChallengeResetSignal : loginChallengeResetSignal}
                    onTokenChange={registerMode ? setRegisterChallengeToken : setLoginChallengeToken}
                    onStatusChange={registerMode ? setRegisterChallengeStatus : setLoginChallengeStatus}
                  />
                ) : (
                  <p className="text-sm text-text-muted">当前环境未配置可用的验证码控件。</p>
                )}
                <p className="text-xs text-text-muted" aria-live="polite">
                  {challengeStatusText(currentChallengeStatus, currentChallengeMode)}
                </p>
              </div>
            )}

            {registerMode && (
              <p className="text-sm text-text-muted">
                {PASSWORD_HINT}
              </p>
            )}

            {error && (
              <p
                className="rounded-[18px] border border-accent/20 bg-accent-soft px-4 py-3 text-sm text-accent"
                role="alert"
              >
                {error}
              </p>
            )}
          </div>

          <div className="space-y-3 pt-6">
            <button
              type="submit"
              disabled={loading}
              className="flex w-full items-center justify-center gap-2 rounded-[18px] bg-accent px-4 py-4 text-base font-semibold text-white transition-all duration-200 hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-60"
            >
              {loading ? (
                <LoaderCircle size={18} className="animate-spin" />
              ) : registerMode ? (
                <UserPlus size={18} />
              ) : (
                <LogIn size={18} />
              )}
              {registerMode ? '注册' : '登录'}
            </button>

            {isModal && onCancel && (
              <button
                type="button"
                onClick={onCancel}
                className="w-full text-center text-sm text-text-muted transition-colors hover:text-text-secondary"
              >
                稍后再说
              </button>
            )}
          </div>
        </form>
      </div>
    </div>
  );
}
