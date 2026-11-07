import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowLeft, Camera, Check, Eye, EyeOff, KeyRound, ShieldCheck, UserRound } from 'lucide-react';
import { errorMessage, type UserInfo } from '../api/auth';
import { changePassword, deleteAvatar, saveAvatar, updateProfile } from '../api/profile';
import { setRouteLeaveGuard } from '../appRouting';
import { useToast } from '../ui/toast/useToast';
import { useConfirm } from '../ui/confirm/useConfirm';
import { prepareAvatar } from '../utils/profileAvatar';
import UserAvatar from './UserAvatar';

export default function ProfilePage({ user, onPasswordChanged, onBack }: { user: UserInfo; onPasswordChanged: () => void; onBack: () => void }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [username, setUsername] = useState(user.username);
  const [avatar, setAvatar] = useState<{ blob: Blob; url: string } | null>(null);
  const [removeAvatar, setRemoveAvatar] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [processing, setProcessing] = useState(false);
  const [error, setError] = useState('');
  const lock = useRef(false);
  const sequence = useRef(0);
  const mounted = useRef(true);
  const input = useRef<HTMLInputElement>(null);
  const errorBanner = useRef<HTMLDivElement>(null);
  const nameDirty = username !== user.username;
  const avatarDirty = !!avatar || removeAvatar;
  const dirty = nameDirty || avatarDirty || !!currentPassword || !!newPassword || !!confirmation;
  const disabled = !!busy || processing;
  useEffect(() => {
    if (error) {
      errorBanner.current?.focus({ preventScroll: true });
      errorBanner.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
  }, [error]);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => () => { if (avatar) URL.revokeObjectURL(avatar.url); }, [avatar]);
  useEffect(() => {
    setRouteLeaveGuard(() => {
      if (lock.current) { toast.info('正在保存，请稍候'); return false; }
      return !dirty || window.confirm('有尚未保存的修改，确定离开个人资料页吗？');
    });
    const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty || lock.current) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', beforeUnload);
    return () => { setRouteLeaveGuard(null); window.removeEventListener('beforeunload', beforeUnload); };
  }, [dirty, toast]);
  const run = async (task: string, action: () => Promise<void>) => {
    if (lock.current || processing) return;
    lock.current = true;
    setBusy(task);
    setError('');
    try { await action(); }
    catch (cause) { if (mounted.current) setError(errorMessage(cause) || '保存失败，请稍后重试'); }
    finally { lock.current = false; if (mounted.current) setBusy(null); }
  };
  const resetAvatar = () => { sequence.current++; setAvatar(null); setRemoveAvatar(false); if (input.current) input.current.value = ''; };
  const submitName = (event: FormEvent) => {
    event.preventDefault();
    const value = username.trim();
    if (value.length < 2 || value.length > 50) { setError('用户名需要 2–50 个字符'); return; }
    void run('name', async () => {
      const updated = await updateProfile(value);
      if (mounted.current) { setUsername(updated.username); toast.success('用户名已更新'); }
    });
  };
  const submitPassword = async (event: FormEvent) => {
    event.preventDefault();
    const length = Array.from(newPassword).length;
    if (length < 8 || length > 16 || !newPassword.trim()) { setError('新密码需要 8–16 个字符'); return; }
    if (newPassword !== confirmation) { setError('两次输入的新密码不一致'); return; }
    if (newPassword === currentPassword) { setError('新密码不能与当前密码相同'); return; }
    if ((nameDirty || avatarDirty) && !(await confirm({ title: '修改密码', message: '修改密码后需要重新登录，其他未保存的资料将丢失，确定继续吗？', confirmText: '继续', tone: 'danger' }))) return;
    void run('password', async () => {
      await changePassword(currentPassword, newPassword, confirmation);
      setRouteLeaveGuard(null);
      onPasswordChanged();
    });
  };
  return <section className="profile-page">
    <header className="studio-topbar"><div><p className="profile-eyebrow">ACCOUNT / 账号中心</p><h1>个人资料</h1></div><button type="button" className="profile-button secondary" onClick={onBack}><ArrowLeft size={15} />返回创作空间</button></header>
    <div className="profile-scroll"><div className="profile-content">
      <div className="profile-intro"><div><p className="profile-eyebrow">YOUR CREATIVE IDENTITY</p><h2>每个故事背后，都有独特的你。</h2><p>管理个人资料，让创作空间更有你的风格。</p></div><span className="profile-intro-mark" aria-hidden="true">A.</span></div>
      <div className="profile-identity-card"><UserAvatar user={user} large /><div><span className="profile-badge"><Check size={12} /> 创作者账号</span><h2>{user.username}</h2><p>{user.email}</p></div><span className="profile-identity-note">让想象，自由生长</span></div>
      {error && <div ref={errorBanner} className="profile-error" role="alert" tabIndex={-1}>{error}</div>}
      <section className="profile-card" aria-labelledby="profile-info-title"><div className="profile-card-heading"><span className="profile-section-icon"><UserRound size={19} /></span><div><h2 id="profile-info-title">资料与头像</h2><p>一个熟悉的名字，一张喜欢的头像。</p></div></div>
        <div className="profile-details-grid"><div className="profile-soft-panel"><h3>个人头像</h3><div className="profile-avatar-editor"><UserAvatar user={user} src={removeAvatar ? null : avatar?.url} large /><div><button type="button" className="profile-button secondary" disabled={disabled} onClick={() => input.current?.click()}><Camera size={15} />{processing ? '正在处理…' : '选择图片'}</button><p>将自动居中裁切为正方形</p></div></div>
          <input ref={input} type="file" className="sr-only" aria-label="上传头像" accept="image/jpeg,image/png,image/webp,image/gif" disabled={disabled} onChange={async event => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (!file) return;
            const token = ++sequence.current;
            setProcessing(true); setError('');
            try {
              const blob = await prepareAvatar(file);
              if (mounted.current && token === sequence.current) { setAvatar({ blob, url: URL.createObjectURL(blob) }); setRemoveAvatar(false); }
            } catch (cause) { if (mounted.current && token === sequence.current) setError(errorMessage(cause) || '图片无法读取，请更换图片'); }
            finally { if (mounted.current && token === sequence.current) setProcessing(false); }
          }} />
          <p className="profile-hint">JPG / PNG / WebP，最大 5MB，保存前自动压缩至 20KB。动态 GIF 需在 20KB、512 × 512 以内。</p>
          <div className="profile-actions"><button type="button" className="profile-text-button" disabled={disabled || (!user.avatar_url && !avatar)} onClick={() => { setAvatar(null); setRemoveAvatar(!!user.avatar_url); }}>移除头像</button>{avatarDirty && <button type="button" className="profile-text-button" disabled={disabled} onClick={resetAvatar}>取消</button>}<button type="button" className="profile-button primary" disabled={disabled || !avatarDirty} onClick={() => void run('avatar', async () => {
            if (removeAvatar) await deleteAvatar(); else if (avatar) await saveAvatar(avatar.blob);
            if (mounted.current) { resetAvatar(); toast.success('头像已更新'); }
          })}>{busy === 'avatar' ? '正在保存…' : '保存头像'}</button></div>
        </div>
        <form className="profile-soft-panel" onSubmit={submitName}><h3>基本信息</h3><label htmlFor="profile-username">用户名</label><input id="profile-username" autoComplete="username" minLength={2} maxLength={50} required value={username} disabled={disabled} onChange={event => setUsername(event.target.value)} /><p className="profile-hint">2–50 个字符，将展示在账号菜单和个人资料中。</p><label htmlFor="profile-email">邮箱 <span className="profile-readonly">只读</span></label><input id="profile-email" type="email" readOnly value={user.email} /><div className="profile-actions"><button type="button" className="profile-text-button" disabled={disabled || !nameDirty} onClick={() => setUsername(user.username)}>取消修改</button><button type="submit" className="profile-button primary" disabled={disabled || !nameDirty}>{busy === 'name' ? '正在更新…' : '更新资料'}</button></div></form></div>
      </section>
      <section className="profile-card" aria-labelledby="profile-password-title"><div className="profile-card-heading"><span className="profile-section-icon"><KeyRound size={19} /></span><div><h2 id="profile-password-title">修改密码</h2><p>定期更新密码，让你的创作更安心。</p></div></div>
        <form onSubmit={submitPassword}><div className="profile-password-grid"><div><label htmlFor="current-password">当前密码</label><input id="current-password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" required maxLength={200} value={currentPassword} disabled={disabled} onChange={event => setCurrentPassword(event.target.value)} placeholder="输入当前密码" /></div><div><label htmlFor="new-password">新密码</label><input id="new-password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" required maxLength={32} value={newPassword} disabled={disabled} onChange={event => setNewPassword(event.target.value)} placeholder="8–16 个字符" /></div><div><label htmlFor="confirm-password">确认新密码</label><input id="confirm-password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" required maxLength={32} value={confirmation} disabled={disabled} onChange={event => setConfirmation(event.target.value)} placeholder="再次输入新密码" /></div></div>
        <div className="profile-password-footer"><p><ShieldCheck size={15} />修改后所有设备需重新登录</p><button type="button" className="profile-text-button" aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>{showPassword ? <EyeOff size={15} /> : <Eye size={15} />}{showPassword ? '隐藏密码' : '显示密码'}</button><button type="submit" className="profile-button primary" disabled={disabled || !currentPassword || !newPassword || !confirmation}>{busy === 'password' ? '正在修改…' : '更新密码'}</button></div></form>
      </section><footer className="profile-footer"><span className="studio-wordmark">ArtVerse.</span><span>让创作回归灵感，让灵感成为故事。</span></footer>
    </div></div>
  </section>;
}
