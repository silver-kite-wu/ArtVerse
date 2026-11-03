import { authFetch, cacheUser, parseApiError, readApiData, type UserInfo } from './auth';

async function profileRequest(path: string, init: RequestInit): Promise<UserInfo> {
  const response = await authFetch(`/api/user/me${path}`, init);
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  const user = await readApiData<UserInfo>(response);
  cacheUser(user);
  return user;
}

export function updateProfile(username: string): Promise<UserInfo> {
  return profileRequest('', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username }) });
}
export function saveAvatar(image: Blob): Promise<UserInfo> {
  const body = new FormData();
  body.append('image', image, image.type === 'image/gif' ? 'avatar.gif' : 'avatar.jpg');
  return profileRequest('/avatar', { method: 'POST', body });
}
export function deleteAvatar(): Promise<UserInfo> { return profileRequest('/avatar', { method: 'DELETE' }); }
export async function changePassword(currentPassword: string, newPassword: string, confirmPassword: string): Promise<void> {
  const response = await authFetch('/api/user/me/password', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ current_password: currentPassword, new_password: newPassword, confirm_password: confirmPassword }),
  });
  if (!response.ok) throw new Error(parseApiError(await response.text()));
}
