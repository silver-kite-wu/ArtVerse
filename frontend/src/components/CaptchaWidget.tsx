import { useEffect, useRef, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { getCaptchaImage } from '../api';

interface Props {
  visible: boolean;
  resetSignal: number;
  onTokenChange: (token: string | null) => void;
  onStatusChange: (status: 'idle' | 'loading' | 'ready' | 'verified' | 'expired' | 'error') => void;
}

export default function CaptchaWidget({
  visible,
  resetSignal,
  onTokenChange,
  onStatusChange,
}: Props) {
  const [captchaId, setCaptchaId] = useState<string | null>(null);
  const [captchaImage, setCaptchaImage] = useState<string>('');
  const [userInput, setUserInput] = useState('');
  const [loading, setLoading] = useState(false);
  const captchaIdRef = useRef<string | null>(null);

  const loadCaptcha = (notify: boolean) => {
    if (notify) onStatusChange('loading');
    setLoading(true);
    getCaptchaImage()
      .then((captcha) => {
        captchaIdRef.current = captcha.captchaId;
        setCaptchaId(captcha.captchaId);
        setCaptchaImage(captcha.image);
        setUserInput('');
        onTokenChange(null);
        onStatusChange('ready');
      })
      .catch(() => {
        onTokenChange(null);
        onStatusChange('error');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (!visible) {
      setCaptchaId(null);
      setCaptchaImage('');
      setUserInput('');
      onTokenChange(null);
      onStatusChange('idle');
      return;
    }

    let active = true;
    setLoading(true);
    onStatusChange('loading');
    getCaptchaImage()
      .then((captcha) => {
        if (!active) return;
        captchaIdRef.current = captcha.captchaId;
        setCaptchaId(captcha.captchaId);
        setCaptchaImage(captcha.image);
        setUserInput('');
        onTokenChange(null);
        onStatusChange('ready');
      })
      .catch(() => {
        if (!active) return;
        onTokenChange(null);
        onStatusChange('error');
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, resetSignal]);

  const handleInputChange = (value: string) => {
    const trimmed = value.trim();
    setUserInput(trimmed);
    if (trimmed.length >= 4 && captchaId) {
      onTokenChange(`${captchaId}:${trimmed}`);
      onStatusChange('verified');
    } else {
      onTokenChange(null);
      onStatusChange('ready');
    }
  };

  if (!visible) {
    return null;
  }

  return (
    <div className="flex items-stretch gap-3">
      <input
        type="text"
        value={userInput}
        onChange={(e) => handleInputChange(e.target.value)}
        placeholder="输入右侧验证码"
        maxLength={6}
        autoComplete="off"
        disabled={loading || !captchaImage}
        className="min-w-0 flex-1 rounded-[18px] border border-border/80 bg-bg-surface/85 px-4 py-3.5 text-base tracking-[0.2em] text-text-primary outline-none transition-[border-color,box-shadow] placeholder:tracking-normal placeholder:text-text-muted focus:border-accent/50 focus:shadow-[0_0_0_4px_var(--color-accent-soft)] disabled:opacity-60"
      />
      <button
        type="button"
        onClick={() => loadCaptcha(true)}
        className="group relative h-[54px] w-[150px] shrink-0 overflow-hidden rounded-[18px] border border-border/70 bg-white"
        aria-label="点击刷新验证码"
        disabled={loading}
      >
        {loading ? (
          <div className="flex h-full w-full items-center justify-center bg-white/60">
            <RefreshCw size={18} className="animate-spin text-text-muted" />
          </div>
        ) : captchaImage ? (
          <img
            src={captchaImage}
            alt="图形验证码，点击可刷新"
            className="h-full w-full cursor-pointer object-cover"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center bg-white/60 text-xs text-text-muted">
            加载失败
          </div>
        )}
        <span className="absolute inset-x-0 bottom-0 hidden bg-black/45 py-0.5 text-center text-[10px] text-white group-hover:block">
          点击刷新
        </span>
      </button>
    </div>
  );
}
