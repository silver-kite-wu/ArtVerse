import { EventType, HttpAgent, type AGUIEvent, type RunAgentInput } from '@ag-ui/client';

const BASE = '';
export const DEEPSEEK_USAGE_URL = 'https://platform.deepseek.com/usage';
export const IMAGE2_CONSOLE_URL = 'https://api.duojie.games/console/token';


const LS_REFRESH_TOKEN = 'artverse.refreshToken';
const LS_USER = 'artverse.user';

export interface UserInfo {
  id: number;
  username: string;
  email: string;
}

export function getUser(): UserInfo | null {
  const raw = localStorage.getItem(LS_USER);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { return null; }
}

export function isAuthenticated(): boolean {
  return !!getUser();
}

export function clearAuth(): void {
  localStorage.removeItem(LS_REFRESH_TOKEN);
  localStorage.removeItem(LS_USER);
}

function notifyAuthExpired(): void {
  clearAuth();
  window.dispatchEvent(new CustomEvent('artverse:auth-expired'));
}

let refreshPromise: Promise<boolean> | null = null;

async function tryRefreshToken(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const refreshToken = localStorage.getItem(LS_REFRESH_TOKEN);
        const body = refreshToken ? JSON.stringify({ refresh_token: refreshToken }) : undefined;
        const res = await fetch(`${BASE}/api/auth/refresh`, {
          method: 'POST',
          credentials: 'same-origin',
          headers: body ? { 'Content-Type': 'application/json' } : undefined,
          body,
        });
        if (!res.ok) {
          if (res.status === 401) {
            localStorage.removeItem(LS_REFRESH_TOKEN);
          }
          return false;
        }
        const data = await res.json();
        const newRefresh = data.refreshToken ?? data.refresh_token;
        if (newRefresh) {
          localStorage.setItem(LS_REFRESH_TOKEN, newRefresh);
        }
        return true;
      } catch {
        return false;
      }
    })();
  }
  const ok = await refreshPromise;
  refreshPromise = null;
  return ok;
}

async function fetchAndSaveUser(): Promise<void> {
  const res = await fetch(`${BASE}/api/user/me`, {
    credentials: 'same-origin',
  });
  if (!res.ok) throw new Error('Error');
  const user: UserInfo = await res.json();
  localStorage.setItem(LS_USER, JSON.stringify(user));
}

export async function loginUser(username: string, password: string): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  const rt = data.refreshToken ?? data.refresh_token;
  if (rt) localStorage.setItem(LS_REFRESH_TOKEN, rt);
  await fetchAndSaveUser();
}

export async function registerUser(username: string, email: string, password: string): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({ username, email, password }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  const rt = data.refreshToken ?? data.refresh_token;
  if (rt) localStorage.setItem(LS_REFRESH_TOKEN, rt);
  await fetchAndSaveUser();
}

export async function logoutUser(): Promise<void> {
  try {
    await fetch(`${BASE}/api/auth/logout`, {
      method: 'POST',
      credentials: 'same-origin',
    });
  } catch { /* ignore */ }
  clearAuth();
}


const LS_DEEPSEEK_API_KEY = 'lorevista.deepseekApiKey';
const LS_IMAGE_API_KEY = 'lorevista.imageApiKey';
const LS_COZE_API_KEY = 'lorevista.cozeApiKey';
export const API_KEY_CHANGE_EVENT = 'lorevista:api-key-change';

export interface ApiKeySettings {
  deepseekApiKey: string;
  imageApiKey: string;
  cozeApiKey: string;
}

export function getApiKeySettings(): ApiKeySettings {
  return {
    deepseekApiKey: localStorage.getItem(LS_DEEPSEEK_API_KEY) || '',
    imageApiKey: localStorage.getItem(LS_IMAGE_API_KEY) || '',
    cozeApiKey: localStorage.getItem(LS_COZE_API_KEY) || '',
  };
}

export function saveApiKeySettings(settings: ApiKeySettings): void {
  const deepseek = settings.deepseekApiKey.trim();
  const image = settings.imageApiKey.trim();
  const coze = (settings.cozeApiKey || '').trim();
  if (deepseek) localStorage.setItem(LS_DEEPSEEK_API_KEY, deepseek);
  else localStorage.removeItem(LS_DEEPSEEK_API_KEY);
  if (image) localStorage.setItem(LS_IMAGE_API_KEY, image);
  else localStorage.removeItem(LS_IMAGE_API_KEY);
  if (coze) localStorage.setItem(LS_COZE_API_KEY, coze);
  else localStorage.removeItem(LS_COZE_API_KEY);
  try { window.dispatchEvent(new Event(API_KEY_CHANGE_EVENT)); } catch { /* ignore */ }
}

export function clearApiKeySettings(): void {
  saveApiKeySettings({ deepseekApiKey: '', imageApiKey: '', cozeApiKey: '' });
}

export async function getUserApiKeys(): Promise<{ provider: string; api_key_masked: string }[]> {
  const res = await authFetch(`${BASE}/api/user/api-keys`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function saveUserApiKey(provider: string, apiKey: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/user/api-keys`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, api_key: apiKey }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}


function apiHeaders(json = false): HeadersInit {
  return {
    ...(json ? { 'Content-Type': 'application/json' } : {}),
  };
}

async function authFetch(input: RequestInfo, init?: RequestInit): Promise<Response> {
  let res = await fetch(input, { ...init, credentials: 'same-origin', headers: { ...apiHeaders(), ...(init?.headers || {}) } });
  if (res.status === 401) {
    const refreshed = await tryRefreshToken();
    if (refreshed) {
      res = await fetch(input, { ...init, credentials: 'same-origin', headers: { ...apiHeaders(), ...(init?.headers || {}) } });
    } else {
      notifyAuthExpired();
    }
  }
  return res;
}

export interface Story {
  id: number;
  title: string;
  description?: string;
  cover_image?: string | null;
  has_character_profiles?: boolean;
  has_ref_image?: boolean;
  created_at: string;
}

export interface ChatMessage {
  id: number;
  chapter_id: number;
  role: string;
  content: string;
  created_at: string;
}

export interface MangaImage {
  id: number;
  chapter_id: number;
  image_number: number;
  image_path: string;
  prompt: string | null;
  created_at: string;
}

export interface Chapter {
  id: number;
  story_id: number;
  chapter_number: number;
  novel_content: string | null;
  content_source?: 'chat' | 'import' | null;
  created_at: string;
  messages: ChatMessage[];
  images: MangaImage[];
}


export async function createStory(title: string = 'Untitled Story', description: string = ''): Promise<Story> {
  const res = await authFetch(`${BASE}/api/stories`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, description }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function listStories(): Promise<Story[]> {
  const res = await authFetch(`${BASE}/api/stories`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function updateStory(storyId: number, data: { title?: string; description?: string }): Promise<Story> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStory(storyId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}

export async function exportStory(story: Story): Promise<void> {
  const res = await fetch(`${BASE}/api/stories/${story.id}/export`, { headers: apiHeaders() });
  if (!res.ok) throw new Error(await res.text());
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const safeTitle = (story.title || `story-${story.id}`).replace(/[\\/:*?"<>|]+/g, '_');
  const a = document.createElement('a');
  a.href = url;
  a.download = `${safeTitle}_lorevista.zip`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export interface ImportStoryProgress {
  phase: 'uploading' | 'processing';
  percent?: number;
  message: string;
}

function parseApiError(text: string): string {
  try {
    const data = JSON.parse(text);
    if (typeof data?.detail === 'string') return data.detail;
    if (Array.isArray(data?.detail)) return data.detail.map((item: any) => item?.msg || JSON.stringify(item)).join('; ');
    if (typeof data?.error === 'string') return data.error;
  } catch {
    // Plain text response.
  }
  return text || 'Request failed';
}

export function importStoryPackage(
  file: File,
  onProgress?: (progress: ImportStoryProgress) => void,
): Promise<Story> {
  return new Promise((resolve, reject) => {
    const doSend = () => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}/api/stories/import`);
      const headers = apiHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        if (typeof value === 'string') xhr.setRequestHeader(key, value);
      });
      xhr.responseType = 'text';

      const formData = new FormData();
      formData.append('file', file);

      xhr.upload.onprogress = (event) => {
        if (!event.lengthComputable) {
          onProgress?.({ phase: 'uploading', message: '...' });
          return;
        }
        const percent = Math.max(1, Math.min(99, Math.round((event.loaded / event.total) * 100)));
        onProgress?.({ phase: 'uploading', percent, message: `正在上传作品包 ${percent}%` });
      };

      xhr.onload = async () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress?.({ phase: 'processing', percent: 100, message: '...' });
          try {
            resolve(JSON.parse(xhr.responseText));
          } catch {
            reject(new Error('Import failed'));
          }
          return;
        }
        if (xhr.status === 401) {
          const refreshed = await tryRefreshToken();
          if (refreshed) {
            doSend();
            return;
          }
          notifyAuthExpired();
          reject(new Error('Login expired'));
          return;
        }
        reject(new Error(parseApiError(xhr.responseText)));
      };

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.onabort = () => reject(new Error('Cancelled'));
      xhr.ontimeout = () => reject(new Error('Timeout'));

      onProgress?.({ phase: 'uploading', percent: 0, message: '...' });
      xhr.send(formData);
      xhr.upload.onload = () => {
        onProgress?.({ phase: 'processing', percent: 100, message: '...' });
      };
    };
    doSend();
  });
}

export async function uploadStoryCover(storyId: number, base64: string): Promise<string> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/upload-cover`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cover_image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.cover_image;
}

function mangaStaticPath(imagePath: string): string {
  return imagePath.replace(/^manga_outputs\//, '');
}

function encodeStaticPath(imagePath: string): string {
  return mangaStaticPath(imagePath).split('/').map(encodeURIComponent).join('/');
}

export function mangaThumbUrl(imagePath: string | null | undefined, width = 720, cacheBust?: string | number): string | null {
  if (!imagePath) return null;
  const url = `${BASE}/static/manga/_thumb/${encodeStaticPath(imagePath)}?w=${width}`;
  return cacheBust ? `${url}&v=${encodeURIComponent(String(cacheBust))}` : url;
}

export function coverImageUrl(coverPath: string | null | undefined): string | null {
  if (!coverPath) return null;
  return `${BASE}/static/manga/${mangaStaticPath(coverPath)}`;
}


export async function getChapter(chapterId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function listChapters(storyId: number): Promise<Chapter[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/chapters`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function createNextChapter(storyId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/chapters`, { method: 'POST' });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteChapter(chapterId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}


export function chatStream(
  chapterId: number,
  content: string,
  onToken: (token: string) => void,
  _onDone: (fullContent: string) => void,
  onError: (err: string) => void,
): AbortController {
  const controller = new AbortController();

  authFetch(`${BASE}/api/chapters/${chapterId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: content }),
    signal: controller.signal,
  })
    .then(async (res) => {
      if (!res.ok) {
        onError(await res.text());
        return;
      }
      const reader = res.body?.getReader();
      if (!reader) return;
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';
      const handleLine = (line: string) => {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith(':')) return;
        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trim();
        } else if (trimmed.startsWith('data:')) {
          const dataStr = trimmed.slice(5).trim();
          try {
            const data = JSON.parse(dataStr);
            if (currentEvent === 'token' && data.content !== undefined) {
              onToken(data.content);
            } else if (currentEvent === 'done' && data.content !== undefined) {
              _onDone(data.content);
            } else if (currentEvent === 'error' || data.error) {
              onError(data.error);
            }
          } catch {
            // ignore
          }
          currentEvent = 'message';
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          handleLine(line);
        }
      }
      if (buffer.trim()) handleLine(buffer);

      // If stream ended without a done event, call onDone with empty
      // This handles edge cases where connection closes unexpectedly
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onError(err.message);
      }
    });

  return controller;
}


export async function generateNovel(chapterId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/generate-novel`, { method: 'POST' });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function importNovel(chapterId: number, content: string): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/import-novel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}


export async function generateScenes(chapterId: number, signal?: AbortSignal): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/generate-scenes`, { method: 'POST', signal });
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.scenes;
}

export async function getScenes(chapterId: number): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/scenes`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.scenes;
}

export async function updateScenes(chapterId: number, scenes: string[]): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/scenes`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(scenes),
  });
  if (!res.ok) throw new Error(await res.text());
}


// Story-level (global)
export async function getStoryCharacters(storyId: number): Promise<string> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.characters;
}

export async function saveStoryCharacters(storyId: number, characters: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characters }),
  });
  if (!res.ok) throw new Error(await res.text());
}

// Chapter-level (with source info)
export type CharacterSource = 'chapter' | 'asset_group' | 'story' | 'none';

export async function getCharacters(chapterId: number): Promise<{ characters: string; source: CharacterSource; group_id?: number; group_name?: string }> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/characters`);
  if (!res.ok) throw new Error(await res.text());
  return await res.json();
}

export async function saveCharacters(chapterId: number, characters: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/characters`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characters }),
  });
  if (!res.ok) throw new Error(await res.text());
}

export async function resetChapterCharacters(chapterId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/characters`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}


// ===== Individual Character Profiles =====
export interface CharacterProfile {
  id: number;
  name: string;
  description: string;
  created_at: string;
  updated_at: string;
}

export async function listCharacterProfiles(storyId: number): Promise<CharacterProfile[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function createCharacterProfile(storyId: number, name: string, description: string): Promise<CharacterProfile> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function updateCharacterProfile(storyId: number, characterId: number, name: string, description: string): Promise<CharacterProfile> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function deleteCharacterProfile(storyId: number, characterId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export interface CharRefImage {
  filename: string;
  object_key: string;
  size_kb: number;
}

export async function listCharRefImages(storyId: number, characterId: number): Promise<CharRefImage[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}/ref-images`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function addCharRefImage(storyId: number, characterId: number, base64: string): Promise<CharRefImage> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function deleteCharRefImage(storyId: number, characterId: number, filename: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}/ref-images/${encodeURIComponent(filename)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export type RefSource = 'chapter' | 'asset_group' | 'story' | 'none';

export interface RefImage {
  filename: string;
  image_path: string;
  size_kb: number;
}

export interface RefImagesPayload {
  images: RefImage[];
  max: number;
  source?: RefSource;
  group_id?: number;
  group_name?: string;
}

export function refImageUrl(imagePath: string): string {
  return `${BASE}/static/manga/${mangaStaticPath(imagePath)}`;
}

export interface AssetGroupCharacter {
  id: number;
  name: string;
  description: string;
}

export interface AssetGroup {
  id: number | null;
  name: string;
  description: string;
  is_default?: boolean;
  characters: AssetGroupCharacter[];
}

export interface AssetGroupsPayload {
  groups: AssetGroup[];
  max: number;
  selected_group_id?: number | null;
}

export interface AssetGroupSinglePayload {
  group: AssetGroup;
  groups: AssetGroup[];
}

export async function getStoryAssetGroups(storyId: number): Promise<AssetGroup[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function createStoryAssetGroup(storyId: number, name: string, description?: string, characterIds?: number[]): Promise<AssetGroup> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description: description || '', characterIds: characterIds || [] }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function updateStoryAssetGroup(groupId: number, data: { name?: string; description?: string; characterIds?: number[] }): Promise<AssetGroup> {
  const res = await authFetch(`${BASE}/api/asset-groups/${groupId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStoryAssetGroup(storyId: number, groupId: number): Promise<AssetGroup[]> {
  const res = await authFetch(`${BASE}/api/asset-groups/${groupId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
  return getStoryAssetGroups(storyId);
}

export async function addStoryAssetGroupRefImage(storyId: number, groupId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups/${groupId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStoryAssetGroupRefImage(storyId: number, groupId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/stories/${storyId}/asset-groups/${groupId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function getChapterAssetGroup(chapterId: number): Promise<AssetGroupsPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/asset-group`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function setChapterAssetGroup(chapterId: number, groupId: number | null): Promise<AssetGroupsPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/asset-group`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ group_id: groupId }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// Story-level
export async function getStoryRefImages(storyId: number): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/ref-images`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function addStoryRefImage(storyId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStoryRefImage(storyId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/stories/${storyId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// Chapter-level (with story fallback)
export async function getChapterRefImages(chapterId: number): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/ref-images`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function addChapterRefImage(chapterId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteChapterRefImage(chapterId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/chapters/${chapterId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}


export type MangaStyle = 'japanese_manga' | 'korean_webtoon' | 'american_comic' | 'ligne_claire' | 'chinese_ink' | 'semi_realistic' | 'realistic' | 'oil_painting' | 'flat_design' | 'pixel_art' | 'watercolor' | 'cyberpunk';

export const MANGA_STYLE_LABELS: Record<MangaStyle, string> = {
  japanese_manga: '日式漫画',
  korean_webtoon: '韩式条漫',
  american_comic: '美式漫画',
  ligne_claire: '欧式清线',
  chinese_ink: '水墨国风',
  semi_realistic: '半写实',
  realistic: '全写实',
  oil_painting: '厚涂油画',
  flat_design: '扁平极简',
  pixel_art: '像素风',
  watercolor: '水彩淡雅',
  cyberpunk: '赛博朋克',
};

export async function getMangaStyle(storyId: number): Promise<MangaStyle> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/manga-style`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.manga_style || 'japanese_manga';
}

export async function setMangaStyle(storyId: number, style: MangaStyle): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/manga-style`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ manga_style: style }),
  });
  if (!res.ok) throw new Error(await res.text());
}


export type ColorMode = 'bw' | 'grayscale' | 'color' | 'duotone';

export const COLOR_MODE_LABELS: Record<ColorMode, string> = {
  bw: '黑白',
  grayscale: '灰度',
  color: '彩色',
  duotone: 'Duotone',
};


export async function getColorMode(chapterId: number): Promise<ColorMode> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/color-mode`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.color_mode || 'bw';
}

export async function setColorMode(chapterId: number, mode: ColorMode): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/color-mode`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ color_mode: mode }),
  });
  if (!res.ok) throw new Error(await res.text());
}


export const ALLOWED_IMAGE_COUNTS = [4, 6, 8, 10, 12, 15, 20] as const;

export async function getImageCount(chapterId: number): Promise<number> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/image-count`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.image_count ?? 10;
}

export async function setImageCount(chapterId: number, count: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/image-count`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image_count: count }),
  });
  if (!res.ok) throw new Error(await res.text());
}

export async function regenerateImage(
  chapterId: number,
  imageNumber: number,
  prompt: string,
): Promise<{ id: number; image_number: number; image_path: string; prompt: string }> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/regenerate-image/${imageNumber}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}


export interface MangaProgress {
  type: 'status' | 'scenes' | 'progress' | 'image' | 'image_error' | 'done' | 'error';
  data: any;
}

export function generateMangaStream(
  chapterId: number,
  assetGroupId: number | null | undefined,
  onEvent: (event: MangaProgress) => void,
): AbortController {
  const controller = new AbortController();
  let reconnectAttempts = 0;
  let reconnectTimer: number | undefined;
  const maxReconnectAttempts = 120;

  const scheduleReconnect = (reason: string) => {
    if (controller.signal.aborted) return;
    reconnectAttempts += 1;
    if (reconnectAttempts > maxReconnectAttempts) {
      onEvent({ type: 'error', data: { error: reason || '生成连接已断开，请稍后重试' } });
      return;
    }
    onEvent({
      type: 'status',
      data: { message: `Reconnecting... (${reconnectAttempts}/${maxReconnectAttempts})` },
    });
    reconnectTimer = window.setTimeout(connect, Math.min(5000, 1000 + reconnectAttempts * 500));
  };

  controller.signal.addEventListener('abort', () => {
    if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer);
  });

  const connect = () => {
    authFetch(`${BASE}/api/chapters/${chapterId}/generate-manga-stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ assetGroupId: assetGroupId ?? null }),
      signal: controller.signal,
    })
      .then(async (res) => {
      if (!res.ok) {
        onEvent({ type: 'error', data: { error: await res.text() } });
        return;
      }
      const reader = res.body?.getReader();
      if (!reader) return;
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';
      let receivedTerminalEvent = false;
      const handleLine = (line: string) => {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith(':')) return;
        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trim();
        } else if (trimmed.startsWith('data:')) {
          const dataStr = trimmed.slice(5).trim();
          try {
            const data = JSON.parse(dataStr);
            const eventType = currentEvent as MangaProgress['type'];
            if (eventType === 'done' || eventType === 'error') receivedTerminalEvent = true;
            onEvent({ type: eventType, data });
          } catch {
            // ignore unparseable data
          }
          currentEvent = 'message';
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          handleLine(line);
        }
      }
      if (buffer.trim()) handleLine(buffer);
      if (!receivedTerminalEvent && !controller.signal.aborted) {
        scheduleReconnect('生成连接已断开，请稍后重试');
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        scheduleReconnect(err.message || '生成连接已断开，请稍后重试');
      }
    });
  };

  connect();

  return controller;
}

export function mangaImageUrl(imagePath: string, cacheBust?: number): string {
  // imagePath is like "manga_outputs/chapter_1/panel_01_abc12345.png"
  // Served at /static/manga/chapter_1/panel_01_abc12345.png
  const url = `${BASE}/static/manga/${mangaStaticPath(imagePath)}`;
  return cacheBust ? `${url}?t=${cacheBust}` : url;
}

// ---- Publish ----
export async function publishStory(storyId: number, isPublished: boolean, chapterIds?: number[]): Promise<Story> {
  const res = await authFetch(BASE+'/api/stories/'+storyId+'/publish', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ is_published: isPublished, chapter_ids: chapterIds }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function updateChapterOrder(storyId: number, orders: { chapter_id: number; display_order: number; display_title?: string }[]): Promise<void> {
  const res = await authFetch(BASE+'/api/stories/'+storyId+'/chapter-order', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ orders }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

// ---- Square ----
export interface SquareStory { id: number; title: string; description: string; cover_url: string; manga_style: string; published_at: string; }
export interface SquareStoryDetail { id: number; title: string; description: string; cover_url: string; manga_style: string; published_at: string; chapters: { id: number; chapter_number: number; display_title: string; images: { id: number; image_number: number; image_url: string }[] }[]; }

export async function listSquareStories(page = 0, size = 12, search?: string): Promise<{ content: SquareStory[]; total_pages: number; total_elements: number }> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (search) params.set('search', search);
  const res = await fetch(BASE+'/api/square/stories?'+params);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function getSquareStoryDetail(id: number): Promise<SquareStoryDetail> {
  const res = await fetch(BASE+'/api/square/stories/'+id);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

// ---- My Works ----
export interface MyWorkChapter { id: number; chapter_number: number; is_published: boolean; display_order: number; display_title: string; status: string; }
export interface MyWork { id: number; title: string; description: string; cover_image: string; is_published: boolean; published_at: string | null; created_at: string | null; chapters: MyWorkChapter[]; }

export async function listMyWorks(): Promise<MyWork[]> {
  const res = await authFetch(BASE+'/api/works');
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

// ---- Image Gen ----
export interface ImageGenRecord { id: number; prompt: string; image_url: string; model: string; size: string; created_at: string; }

export async function generateImage(prompt: string, referenceImages?: string[], size?: string, signal?: AbortSignal): Promise<ImageGenRecord> {
  const res = await authFetch(BASE+'/api/image-gen/generate', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt, reference_images: referenceImages || [], ...(size ? { size } : {}) }),
    signal,
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function listImageGenHistory(page = 0, size = 12): Promise<{ content: ImageGenRecord[]; total_pages: number; total_elements: number }> {
  const res = await authFetch(BASE+'/api/image-gen/history?page='+page+'&size='+size);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function deleteImageGenRecord(id: number): Promise<void> {
  const res = await authFetch(BASE+'/api/image-gen/'+id, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export function imageGenUrl(objectKey: string): string {
  return `${BASE}/static/manga/${encodeStaticPath(objectKey)}`;
}

export interface GuardActionStats {
  action: string;
  total: number;
  leader: number;
  follower: number;
  success_hit: number;
  failed_hit: number;
  follower_rejected: number;
  processing_rejected: number;
  failed: number;
  hit_rate: number;
  reuse_rate: number;
  single_flight_rate: number;
  reject_rate: number;
}

export interface GuardStatsPayload {
  updated_at: string;
  actions: GuardActionStats[];
}

export async function getGuardStats(): Promise<GuardStatsPayload> {
  const res = await fetch(BASE + '/api/internal/guard/stats');
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export interface GuardMetricBucket {
  bucket_type: string;
  bucket_start: string;
  action: string;
  total: number;
  leader: number;
  follower: number;
  success_hit: number;
  failed_hit: number;
  follower_rejected: number;
  processing_rejected: number;
  failed: number;
}

export async function getGuardMetrics(bucket = 'HOUR', range = 24): Promise<{ updated_at: string; bucket_type: string; items: GuardMetricBucket[] }> {
  const res = await fetch(BASE + '/api/internal/guard/metrics?bucket=' + bucket + '&range=' + range);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export interface GuardEvent {
  id: string;
  time: string;
  action: string;
  scope: string;
  decision: string;
  result: string;
  key_hash: string;
  duration_ms?: number | null;
  summary?: Record<string, unknown>;
  message?: string;
}

export async function getGuardEvents(limit = 100): Promise<{ events: GuardEvent[] }> {
  const res = await fetch(BASE + '/api/internal/guard/events?limit=' + limit);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export interface MangaAgentMessage {
  id: number;
  role: 'user' | 'assistant' | 'system';
  content: string;
  request_id?: string;
  requestId?: string;
  created_at?: string;
  createdAt?: string;
}

export interface MangaAgentConversation {
  conversationId: string;
  title: string;
  status: 'ACTIVE' | 'ARCHIVED';
  createdAt?: string;
  updatedAt?: string;
  archivedAt?: string | null;
}

export type MangaWorkflowRoute = 'AUTO' | 'CHAT' | 'DIRECTOR' | 'HITL' | 'REVIEW';

export type MangaAgentRunEvent =
  | { type: 'status'; data: { message?: string; requestId?: string; request_id?: string } }
  | { type: 'run_event'; data: AgentRunTimelineEvent }
  | { type: 'tool'; data: { tool?: string; succeeded?: boolean; saved?: boolean; scenes_count?: number; error?: string } }
  | { type: 'user_input_requested'; data: AgentUserInputRequest }
  | { type: 'done'; data: { reply?: string; requestId?: string; request_id?: string } }
  | { type: 'error'; data: { detail?: string; error?: string; requestId?: string; request_id?: string } }
  | { type: 'ag_ui_event'; data: ArtVerseAgUiEvent };

export type ArtVerseAgUiEvent = AGUIEvent & {
  protocol?: 'ag-ui';
  runId?: string;
  route?: MangaWorkflowRoute;
  rawEvent?: AgentRunTimelineEvent | Record<string, unknown>;
  snapshot?: {
    requestId?: string;
    runId?: string;
    status?: string;
    message?: string;
    route?: MangaWorkflowRoute;
  };
  result?: {
    reply?: string;
  };
  outcome?: {
    type?: 'success' | 'interrupt';
    interrupts?: Array<{
      id: string;
      reason: string;
      message?: string;
      metadata?: {
        question?: string;
        options?: AgentUserInputOption[];
        allowFreeText?: boolean;
      };
    }>;
  };
};

export interface AgentRunTimelineEvent {
  type: string;
  phase?: string;
  label?: string;
  toolName?: string;
  status?: string;
  text?: string;
  data?: Record<string, unknown>;
  createdAt?: string;
}

export type MangaAgentRunStatus = 'RUNNING' | 'WAITING_USER' | 'SUCCEEDED' | 'DEGRADED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';

export interface AgentRunPersistedEvent {
  eventName: MangaAgentRunEvent['type'];
  data: MangaAgentRunEvent['data'];
  createdAt?: string;
}

export interface MangaAgentRunSnapshot {
  requestId: string;
  request_id?: string;
  route?: MangaWorkflowRoute;
  status: MangaAgentRunStatus;
  inputMessage?: string;
  finalReply?: string;
  errorMessage?: string;
  userInputRequest?: AgentUserInputRequest | null;
  events: AgentRunPersistedEvent[];
  createdAt?: string;
  updatedAt?: string;
  completedAt?: string | null;
}

export interface AgentUserInputOption {
  id: string;
  label: string;
  description?: string;
  recommended?: boolean;
}

export interface AgentUserInputRequest {
  requestId?: string;
  request_id?: string;
  question: string;
  options: AgentUserInputOption[];
  allowFreeText?: boolean;
  reason?: string;
}

export async function getMangaAgentMessages(chapterId: number): Promise<MangaAgentMessage[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/messages`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return data.messages || [];
}

export async function listMangaAgentConversations(chapterId: number): Promise<MangaAgentConversation[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/conversations`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return data.conversations || [];
}

export async function createMangaAgentConversation(chapterId: number): Promise<MangaAgentConversation> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/conversations`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function deleteMangaAgentConversation(chapterId: number, conversationId: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/conversations/${conversationId}`, {
    method: 'DELETE',
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export async function getMangaAgentConversationMessages(
  chapterId: number,
  conversationId: string,
): Promise<MangaAgentMessage[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/conversations/${conversationId}/messages`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return data.messages || [];
}

export async function runMangaAgent(
  chapterId: number,
  message: string,
  requestId?: string,
  route?: MangaWorkflowRoute,
): Promise<{ reply: string; request_id?: string; requestId?: string }> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, requestId, route }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export function runMangaAgentStream(
  chapterId: number,
  message: string,
  requestId: string | undefined,
  onEvent: (event: MangaAgentRunEvent) => void,
  route?: MangaWorkflowRoute,
): AbortController {
  return startMangaAgentEventStream(
    `${BASE}/api/chapters/${chapterId}/manga-agent/run-stream`,
    { message, requestId, route },
    requestId,
    onEvent,
  );
}

class ArtVerseMangaAgentHttpAgent extends HttpAgent {
  private readonly message: string;
  private readonly requestId?: string;
  private readonly answer?: string;
  private readonly route?: MangaWorkflowRoute;

  constructor(
    url: string,
    message: string,
    requestId: string | undefined,
    abortController: AbortController,
    answer?: string,
    route?: MangaWorkflowRoute,
  ) {
    super({
      url,
      headers: apiHeaders(true) as Record<string, string>,
    });
    this.message = message;
    this.requestId = requestId;
    this.answer = answer;
    this.route = route;
    this.abortController = abortController;
  }

  protected override requestInit(input: RunAgentInput): RequestInit {
    return {
      method: 'POST',
      headers: {
        ...this.headers,
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(this.answer === undefined
        ? {
          message: this.message,
          requestId: this.requestId || input.runId,
          route: this.route,
        }
        : {
          answer: this.answer,
        }),
      signal: this.abortController.signal,
    };
  }
}

export function runMangaAgentAgUiStream(
  chapterId: number,
  message: string,
  requestId: string | undefined,
  onEvent: (event: MangaAgentRunEvent) => void,
  conversationId?: string,
  route?: MangaWorkflowRoute,
): AbortController {
  const controller = new AbortController();
  const agent = new ArtVerseMangaAgentHttpAgent(
    conversationId
      ? `${BASE}/api/chapters/${chapterId}/manga-agent/conversations/${conversationId}/ag-ui/run`
      : `${BASE}/api/chapters/${chapterId}/manga-agent/ag-ui/run`,
    message,
    requestId,
    controller,
    undefined,
    route,
  );
  const subscription = agent.run({
    threadId: conversationId ? `chapter-${chapterId}-conversation-${conversationId}` : `chapter-${chapterId}`,
    runId: requestId || createClientRequestId(),
    state: {},
    messages: [{ id: `user-${requestId || Date.now()}`, role: 'user', content: message }],
    tools: [],
    context: [],
    forwardedProps: {},
  }).subscribe({
    next: (event) => onEvent({ type: 'ag_ui_event', data: event as ArtVerseAgUiEvent }),
    error: (err) => {
      if (!controller.signal.aborted) {
        onEvent({ type: 'error', data: { detail: err?.message || '智能体连接中断', requestId } });
      }
    },
  });
  controller.signal.addEventListener('abort', () => subscription.unsubscribe());
  return controller;
}

export function resumeMangaAgentAgUiStream(
  chapterId: number,
  requestId: string,
  answer: string,
  onEvent: (event: MangaAgentRunEvent) => void,
  conversationId?: string,
): AbortController {
  const controller = new AbortController();
  const agent = new ArtVerseMangaAgentHttpAgent(
    conversationId
      ? `${BASE}/api/chapters/${chapterId}/manga-agent/conversations/${conversationId}/ag-ui/runs/${requestId}/resume`
      : `${BASE}/api/chapters/${chapterId}/manga-agent/ag-ui/runs/${requestId}/resume`,
    '',
    requestId,
    controller,
    answer,
  );
  const subscription = agent.run({
    threadId: conversationId ? `chapter-${chapterId}-conversation-${conversationId}` : `chapter-${chapterId}`,
    runId: requestId,
    state: {},
    messages: [],
    tools: [],
    context: [],
    forwardedProps: {},
  }).subscribe({
    next: (event) => onEvent({ type: 'ag_ui_event', data: event as ArtVerseAgUiEvent }),
    error: (err) => {
      if (!controller.signal.aborted) {
        onEvent({ type: 'error', data: { detail: err?.message || '智能体连接中断', requestId } });
      }
    },
  });
  controller.signal.addEventListener('abort', () => subscription.unsubscribe());
  return controller;
}

function startMangaAgentEventStream(
  url: string,
  body: Record<string, unknown>,
  requestId: string | undefined,
  onEvent: (event: MangaAgentRunEvent) => void,
): AbortController {
  const controller = new AbortController();

  authFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: controller.signal,
  })
    .then(async (res) => {
      if (!res.ok) {
        onEvent({ type: 'error', data: { detail: parseApiError(await res.text()), requestId } });
        return;
      }
      const reader = res.body?.getReader();
      if (!reader) {
        onEvent({ type: 'error', data: { detail: '智能体连接不可用', requestId } });
        return;
      }
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';
      const handleLine = (line: string) => {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith(':')) return;
        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trim();
          return;
        }
        if (!trimmed.startsWith('data:')) return;

        const dataStr = trimmed.slice(5).trim();
        try {
          const data = JSON.parse(dataStr);
          if (isAgUiEventPayload(data) && (currentEvent === 'message' || currentEvent === 'ag_ui_event')) {
            onEvent({ type: 'ag_ui_event', data });
          } else if (currentEvent === 'status'
            || currentEvent === 'run_event'
            || currentEvent === 'tool'
            || currentEvent === 'user_input_requested'
            || currentEvent === 'done'
            || currentEvent === 'error') {
            onEvent({ type: currentEvent, data } as MangaAgentRunEvent);
          }
        } catch {
          // Ignore malformed stream chunks.
        } finally {
          currentEvent = 'message';
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          handleLine(line);
        }
      }
      if (buffer.trim()) handleLine(buffer);
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onEvent({ type: 'error', data: { detail: err.message || '智能体连接中断', requestId } });
      }
    });

  return controller;
}

function isAgUiEventPayload(value: unknown): value is ArtVerseAgUiEvent {
  if (!value || typeof value !== 'object') return false;
  const type = (value as { type?: unknown }).type;
  return typeof type === 'string' && Object.values(EventType).includes(type as EventType);
}

function createClientRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export async function getOpenMangaAgentRun(chapterId: number): Promise<MangaAgentRunSnapshot | null> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/runs/open`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return data.run || null;
}

export async function getOpenMangaAgentConversationRun(
  chapterId: number,
  conversationId: string,
): Promise<MangaAgentRunSnapshot | null> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/conversations/${conversationId}/runs/open`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return data.run || null;
}

export async function getMangaAgentRunState(chapterId: number, requestId: string): Promise<MangaAgentRunSnapshot> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/runs/${requestId}`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function getMangaAgentConversationRunState(
  chapterId: number,
  conversationId: string,
  requestId: string,
): Promise<MangaAgentRunSnapshot> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/conversations/${conversationId}/runs/${requestId}`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function cancelMangaAgentRun(chapterId: number, requestId: string): Promise<MangaAgentRunSnapshot> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/runs/${requestId}/cancel`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function cancelMangaAgentConversationRun(
  chapterId: number,
  conversationId: string,
  requestId: string,
): Promise<MangaAgentRunSnapshot> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/conversations/${conversationId}/runs/${requestId}/cancel`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function resumeMangaAgentRun(
  chapterId: number,
  requestId: string,
  answer: string,
): Promise<{ reply: string; request_id?: string; requestId?: string }> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/manga-agent/runs/${requestId}/resume`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ answer }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export function resumeMangaAgentRunStream(
  chapterId: number,
  requestId: string,
  answer: string,
  onEvent: (event: MangaAgentRunEvent) => void,
): AbortController {
  return startMangaAgentEventStream(
    `${BASE}/api/chapters/${chapterId}/manga-agent/runs/${requestId}/resume-stream`,
    { answer },
    requestId,
    onEvent,
  );
}
