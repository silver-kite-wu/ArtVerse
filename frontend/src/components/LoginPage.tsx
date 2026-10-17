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
  getMailConfig,
  loginByCode,
  loginUser,
  registerUser,
  resetPassword,
  sendEmailCode,
  verifyResetCode,
  type ChallengeConfig,
  type EmailCodeScenario,
} from '../api';
import CaptchaWidget from './CaptchaWidget';

interface Props {
  onAuthSuccess: () => void;
  variant?: 'page' | 'modal';
  message?: string;
  onCancel?: () => void;
}

type Mode = 'login' | 'register' | 'code-login' | 'forgot-password';
type ChallengeStatus = 'idle' | 'loading' | 'ready' | 'verified' | 'expired' | 'error';

const DEFAULT_CHALLENGE_CONFIG: ChallengeConfig = {
  enabled: false,
  provider: '',
  siteKey: '',
  registrationRequired: false,
};

const MIN_PASSWORD_LENGTH = 8;
const MAX_PASSWORD_LENGTH = 16;
const PASSWORD_HINT = '注册密码长度需在 8 到 16 个字符之间。';
const CODE_COOLDOWN_SECONDS = 60;
const CHALLENGE_REQUIRED_CODE = 40301;
const CHALLENGE_FAILED_CODE = 40302;
const CHALLENGE_UNAVAILABLE_CODE = 50301;
const PASSWORD_LOGIN_LOCKED_CODE = 40304;

function codePointLength(value: string): number {
  return [...value].length;
}

function scenarioForMode(mode: Mode): EmailCodeScenario | null {
  if (mode === 'register') return 'register';
  if (mode === 'code-login') return 'login';
  if (mode === 'forgot-password') return 'reset_password';
  return null;
}

function challengeStatusText(status: ChallengeStatus): string {
  switch (status) {
    case 'loading':
      return '验证码加载中...';
    case 'ready':
      return '请完成图形验证码后再注册';
    case 'verified':
      return '验证码已输入';
    case 'expired':
      return '验证码已过期，请刷新后重试';
    case 'error':
      return '验证码加载失败，请刷新后重试';
    default:
      return '注册前需要完成图形验证码';
  }
}

export default function LoginPage({ onAuthSuccess, variant = 'page', message, onCancel }: Props) {
  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [emailCode, setEmailCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [resetStage, setResetStage] = useState<'code' | 'password'>('code');
  const [resetTicket, setResetTicket] = useState('');
  const [error, setError] = useState('');
  const [successMessage, setSuccessMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [codeCooldown, setCodeCooldown] = useState(0);
  const [mailEnabled, setMailEnabled] = useState(false);
  const [challengeConfig, setChallengeConfig] = useState<ChallengeConfig>(DEFAULT_CHALLENGE_CONFIG);
  const [registerChallengeToken, setRegisterChallengeToken] = useState<string | null>(null);
  const [registerChallengeStatus, setRegisterChallengeStatus] = useState<ChallengeStatus>('idle');
  const [registerChallengeResetSignal, setRegisterChallengeResetSignal] = useState(0);
  const [pwLockHint, setPwLockHint] = useState(false);
  const [loginChallengeRequired, setLoginChallengeRequired] = useState(false);
  const [loginChallengeToken, setLoginChallengeToken] = useState<string | null>(null);
  const [loginChallengeStatus, setLoginChallengeStatus] = useState<ChallengeStatus>('idle');
  const [loginChallengeResetSignal, setLoginChallengeResetSignal] = useState(0);
  const firstInputRef = useRef<HTMLInputElement | null>(null);
  const idPrefix = useId();

  const isModal = variant === 'modal';
  const registerMode = mode === 'register';
  const passwordLoginMode = mode === 'login';
  const codeLoginMode = mode === 'code-login';
  const forgotMode = mode === 'forgot-password';
  const supportsCaptcha = challengeConfig.enabled
    && challengeConfig.provider === 'graphic-captcha';
  const registerChallengeVisible = registerMode
    && supportsCaptcha
    && challengeConfig.registrationRequired;
  const dialogTitleId = `${idPrefix}-title`;
  const dialogDescriptionId = `${idPrefix}-description`;
  const usernameId = `${idPrefix}-username`;
  const emailId = `${idPrefix}-email`;
  const emailCodeId = `${idPrefix}-email-code`;
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
    let active = true;
    void getMailConfig()
      .then((config) => {
        if (!active) return;
        setMailEnabled(config.enabled);
      })
      .catch(() => {
        if (!active) return;
        setMailEnabled(false);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    firstInputRef.current?.focus();
  }, [mode]);

  useEffect(() => {
    if (codeCooldown <= 0) return;
    const timer = window.setTimeout(() => setCodeCooldown((value) => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [codeCooldown]);

  const switchMode = (nextMode: Mode) => {
    setMode(nextMode);
    setError('');
    setSuccessMessage('');
    setPassword('');
    setConfirmPassword('');
    setEmailCode('');
    setResetStage('code');
    setResetTicket('');
    setCodeCooldown(0);
    setShowPassword(false);
    setShowConfirmPassword(false);
    setPwLockHint(false);
    setLoginChallengeRequired(false);
    setLoginChallengeToken(null);
    setLoginChallengeResetSignal((value) => value + 1);
    if (nextMode !== 'register') {
      setRegisterChallengeToken(null);
      setRegisterChallengeStatus('idle');
      setRegisterChallengeResetSignal((value) => value + 1);
    }
  };

  const inputClass =
    'w-full rounded-[18px] border border-border/80 bg-bg-surface/85 px-4 py-3.5 text-sm text-text-primary outline-none! transition-[border-color,background-color] placeholder:text-text-muted focus:border-accent/50 focus:bg-bg-surface';

  const handleAuthError = (fallbackMessage: string, err: unknown, currentMode: Mode) => {
    if (err instanceof ApiError) {
      if (currentMode === 'login' && err.code === PASSWORD_LOGIN_LOCKED_CODE) {
        setPwLockHint(true);
      }
      if (currentMode === 'login' && err.code === CHALLENGE_REQUIRED_CODE) {
        // 密码连续输错达到阈值：展示图形验证码后再登录
        setLoginChallengeRequired(true);
      }
      if (err.code === CHALLENGE_FAILED_CODE || err.code === CHALLENGE_UNAVAILABLE_CODE) {
        setRegisterChallengeToken(null);
        setRegisterChallengeStatus('idle');
        setRegisterChallengeResetSignal((value) => value + 1);
        setLoginChallengeToken(null);
        setLoginChallengeResetSignal((value) => value + 1);
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

  const handleSendCode = async () => {
    const trimmedEmail = email.trim();
    if (!trimmedEmail) {
      setError('请先填写邮箱');
      return;
    }
    if (sendingCode || codeCooldown > 0) return;
    const scenario = scenarioForMode(mode);
    if (!scenario) return;
    setSendingCode(true);
    setError('');
    try {
      await sendEmailCode(trimmedEmail, scenario);
      setCodeCooldown(CODE_COOLDOWN_SECONDS);
    } catch (err) {
      handleAuthError('验证码发送失败，请稍后重试', err, mode);
    } finally {
      setSendingCode(false);
    }
  };

  /** 第二步返回上一步：作废已签发的票据，重新回到验证码校验 */
  const backToCodeStep = () => {
    setResetStage('code');
    setResetTicket('');
    setEmailCode('');
    setError('');
    setSuccessMessage('');
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (loading || sendingCode) return;

    const trimmedUsername = username.trim();
    const trimmedEmail = email.trim();
    const trimmedCode = emailCode.trim();

    setError('');

    if (registerMode) {
      if (!trimmedUsername || !password.trim()) {
        setError('请填写用户名和密码');
        return;
      }
      if (!trimmedEmail) {
        setError('请填写邮箱');
        return;
      }
      if (mailEnabled && !trimmedCode) {
        setError('请填写邮箱验证码');
        return;
      }
      const passwordLength = codePointLength(password);
      if (passwordLength < MIN_PASSWORD_LENGTH || passwordLength > MAX_PASSWORD_LENGTH) {
        setError(PASSWORD_HINT);
        return;
      }
      if (password !== confirmPassword) {
        setError('两次输入的密码不一致');
        return;
      }
      if (registerChallengeVisible && !registerChallengeToken) {
        setError('请先完成图形验证码后再注册');
        return;
      }
    } else if (passwordLoginMode) {
      if (!trimmedEmail || !password.trim()) {
        setError('请填写邮箱和密码');
        return;
      }
      if (loginChallengeRequired && !loginChallengeToken) {
        setError('请先完成图形验证码后再登录');
        return;
      }
    } else if (codeLoginMode) {
      if (!trimmedEmail || !trimmedCode) {
        setError('请填写邮箱和验证码');
        return;
      }
    } else if (forgotMode) {
      if (resetStage === 'code') {
        if (!trimmedEmail || !trimmedCode) {
          setError('请填写邮箱和验证码');
          return;
        }
      } else {
        const passwordLength = codePointLength(password);
        if (passwordLength < MIN_PASSWORD_LENGTH || passwordLength > MAX_PASSWORD_LENGTH) {
          setError(PASSWORD_HINT);
          return;
        }
      }
    }

    setLoading(true);
    try {
      if (registerMode) {
        await registerUser(
          trimmedUsername,
          trimmedEmail,
          password,
          registerChallengeToken || undefined,
          trimmedCode,
        );
      } else if (passwordLoginMode) {
        await loginUser(trimmedEmail, password, loginChallengeToken || undefined);
        setLoginChallengeRequired(false);
        setLoginChallengeToken(null);
      } else if (codeLoginMode) {
        await loginByCode(trimmedEmail, trimmedCode);
      } else if (resetStage === 'code') {
        // 第一步：校验验证码，通过后进入设置新密码
        const ticket = await verifyResetCode(trimmedEmail, trimmedCode);
        setResetTicket(ticket);
        setResetStage('password');
        setPassword('');
        setShowPassword(false);
        setError('');
        return;
      } else {
        // 第二步：凭一次性票据重置密码
        await resetPassword(resetTicket, password);
        switchMode('login');
        setSuccessMessage('密码已重置，请使用新密码登录');
        return;
      }
      onAuthSuccess();
    } catch (err) {
      handleAuthError(
        registerMode
          ? '注册失败，请稍后重试'
          : forgotMode
            ? '密码重置失败，请稍后重试'
            : '登录失败，请稍后重试',
        err,
        mode,
      );
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
          maxLength={registerMode || forgotMode ? MAX_PASSWORD_LENGTH : undefined}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          autoComplete={id === passwordId ? (passwordLoginMode ? 'current-password' : 'new-password') : 'new-password'}
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

  const renderEmailCodeField = () => {
    const buttonLabel = sendingCode
      ? '发送中...'
      : codeCooldown > 0
        ? `${codeCooldown}s 后重发`
        : '获取验证码';
    return (
      <div className="space-y-2">
        <label htmlFor={emailCodeId} className="flex items-center gap-1.5 text-sm font-medium text-text-secondary">
          <ShieldCheck size={14} className="text-accent/70" />
          邮箱验证码
        </label>
        <div className="relative">
          <input
            id={emailCodeId}
            type="text"
            value={emailCode}
            onChange={(event) => setEmailCode(event.target.value)}
            placeholder="输入 6 位验证码"
            autoComplete="one-time-code"
            inputMode="numeric"
            maxLength={8}
            className={inputClass + ' pr-28'}
          />
          <button
            type="button"
            onClick={handleSendCode}
            disabled={sendingCode || codeCooldown > 0}
            className="absolute right-2 top-1/2 h-9 -translate-y-1/2 rounded-xl bg-accent/10 px-3 text-xs font-semibold text-accent transition-colors hover:bg-accent/20 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {buttonLabel}
          </button>
        </div>
        <p className="text-xs text-text-muted">验证码 5 分钟内有效。</p>
        {(mode === 'code-login' || mode === 'forgot-password') && (
          <p className="text-xs text-text-muted">仅已注册的邮箱会收到验证码；若未收到，请确认该邮箱是否已注册。</p>
        )}
      </div>
    );
  };

  const challengeBlockVisible = registerChallengeVisible
    || (passwordLoginMode && loginChallengeRequired);
  const submitLabel = registerMode
    ? '注册'
    : forgotMode
      ? (resetStage === 'code' ? '验证验证码' : '重置密码')
      : '登录';
  const submitIcon = registerMode
    ? <UserPlus size={18} />
    : forgotMode
      ? (resetStage === 'code' ? <ShieldCheck size={18} /> : <KeyRound size={18} />)
      : <LogIn size={18} />;

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
              <h2 id={dialogTitleId}>
                {registerMode
                  ? '注册 ArtVerse 账号'
                  : codeLoginMode
                    ? '邮箱验证码登录'
                    : forgotMode
                      ? '找回密码'
                      : '登录 ArtVerse 账号'}
              </h2>
              <p id={dialogDescriptionId}>登录和注册窗口采用固定尺寸布局，切换表单不会改变弹框大小。</p>
            </div>

            {passwordLoginMode && (message || successMessage) && (
              <p className="rounded-[20px] border border-accent/25 bg-[linear-gradient(135deg,rgba(241,72,30,0.10),rgba(241,72,30,0.03))] px-4 py-3 text-sm text-accent">
                {message || successMessage}
              </p>
            )}

            {(passwordLoginMode || registerMode) ? (
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
            ) : (
              <div className="flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => switchMode('login')}
                  className="text-sm text-text-muted transition-colors hover:text-text-secondary"
                >
                  ← 返回登录
                </button>
                <span className="text-sm font-semibold text-text-primary">
                  {codeLoginMode
                    ? '邮箱验证码登录'
                    : forgotMode
                      ? (resetStage === 'code' ? '找回密码 · 验证身份' : '找回密码 · 设置新密码')
                      : ''}
                </span>
              </div>
            )}

            <div className="grid gap-4 sm:gap-5">
              {passwordLoginMode && (
                <div className="space-y-2">
                  <label htmlFor={emailId} className="flex items-center gap-1.5 text-sm font-medium text-text-secondary">
                    <Mail size={14} className="text-accent/70" />
                    邮箱
                  </label>
                  <input
                    ref={firstInputRef}
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

              {registerMode && (
                <div className="space-y-2">
                  <label htmlFor={usernameId} className="flex items-center gap-1.5 text-sm font-medium text-text-secondary">
                    <User size={14} className="text-accent/70" />
                    用户名
                  </label>
                  <input
                    ref={firstInputRef}
                    id={usernameId}
                    type="text"
                    value={username}
                    onChange={(event) => setUsername(event.target.value)}
                    placeholder="输入用户名"
                    autoComplete="username"
                    className={inputClass}
                  />
                </div>
              )}

              {(registerMode || codeLoginMode || (forgotMode && resetStage === 'code')) && (
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

              {(registerMode || codeLoginMode || (forgotMode && resetStage === 'code')) && mailEnabled && renderEmailCodeField()}

              {(passwordLoginMode || registerMode) && renderPasswordField(
                '密码',
                passwordId,
                password,
                setPassword,
                showPassword,
                setShowPassword,
                registerMode ? `输入 ${MIN_PASSWORD_LENGTH} 到 ${MAX_PASSWORD_LENGTH} 个字符的密码` : '输入密码',
              )}

              {forgotMode && resetStage === 'password' && (
                <div className="space-y-3">
                  <div className="flex flex-wrap items-center justify-between gap-2 rounded-[18px] border border-border/80 bg-bg-surface/60 px-4 py-3">
                    <span className="text-sm text-text-muted">验证通过，正在为以下账号设置新密码</span>
                    <span className="text-sm font-medium break-all text-text-primary">{email}</span>
                  </div>
                  <button
                    type="button"
                    onClick={backToCodeStep}
                    className="text-sm text-text-muted transition-colors hover:text-text-secondary"
                  >
                    ← 返回上一步
                  </button>
                </div>
              )}

              {forgotMode && resetStage === 'password' && renderPasswordField(
                '新密码',
                passwordId,
                password,
                setPassword,
                showPassword,
                setShowPassword,
                `输入 ${MIN_PASSWORD_LENGTH} 到 ${MAX_PASSWORD_LENGTH} 个字符的新密码`,
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

            {passwordLoginMode && (
              <div className="flex items-center justify-between text-sm">
                <button
                  type="button"
                  onClick={() => switchMode('code-login')}
                  className="text-accent transition-colors hover:underline"
                >
                  邮箱验证码登录
                </button>
                <button
                  type="button"
                  onClick={() => switchMode('forgot-password')}
                  className="text-text-muted transition-colors hover:text-text-secondary"
                >
                  忘记密码？
                </button>
              </div>
            )}

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
                {registerMode && (
                  <p className="text-xs text-text-muted" aria-live="polite">
                    {challengeStatusText(registerChallengeStatus)}
                  </p>
                )}
                {passwordLoginMode && loginChallengeRequired && (
                  <p className="text-xs text-text-muted">
                    {loginChallengeStatus === 'expired'
                      ? '图形验证码已过期，请刷新后重试'
                      : loginChallengeStatus === 'error'
                        ? '图形验证码加载失败，请刷新后重试'
                        : '密码连续输入错误，请先完成图形验证码后再登录。'}
                  </p>
                )}
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

            {pwLockHint && passwordLoginMode && (
              <div className="flex flex-wrap items-center gap-2 rounded-[18px] border border-border/80 bg-bg-surface/60 px-4 py-3 text-sm text-text-muted">
                <span>密码登录已临时锁定，可改用：</span>
                <button
                  type="button"
                  onClick={() => switchMode('code-login')}
                  className="text-accent transition-colors hover:underline"
                >
                  邮箱验证码登录
                </button>
                <span>/</span>
                <button
                  type="button"
                  onClick={() => switchMode('forgot-password')}
                  className="text-accent transition-colors hover:underline"
                >
                  找回密码
                </button>
              </div>
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
              ) : (
                submitIcon
              )}
              {submitLabel}
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