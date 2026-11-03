import { useState } from 'react';
import type { UserInfo } from '../api/auth';

export default function UserAvatar({ user, src, large = false }: { user: UserInfo; src?: string | null; large?: boolean }) {
  const url = src === undefined ? user.avatar_url : src;
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  return <span className={'account-avatar' + (large ? ' is-large' : '')}>
    {url && failedUrl !== url
      ? <img src={url} alt={`${user.username}的头像`} onError={() => setFailedUrl(url)} />
      : <span aria-hidden="true">{Array.from(user.username.trim())[0]?.toUpperCase() || 'A'}</span>}
  </span>;
}
