import { submitImageRequest, watchImageRequest, isImageRequestTerminal, IMAGE_STATUS_LABELS, type ImageRequestSnapshot } from './imageRequests';
import { readApiData, unwrapApiData, BASE, apiHeaders, authFetch, parseApiError, refreshAuthSession } from './auth';
import { getImageGenerationProviderPayload, getProviderRequestPayload } from './providerSettings';

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
  request_id?: string;
  chapter_id?: number;
  role: string;
  content: string;
  completion_status?: 'complete' | 'partial';
  created_at: string;
}

export async function listStoryChatMessages(chapterId: number, conversationId: string): Promise<ChatMessage[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/messages`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const items = await readApiData(res) as Array<Record<string, unknown>>;
  return items.map((item) => ({
    id: Number(item.id),
    request_id: typeof (item.request_id ?? item.requestId) === 'string' ? String(item.request_id ?? item.requestId) : undefined,
    chapter_id: chapterId,
    role: String(item.role || '').toLowerCase(),
    content: String(item.content || ''),
    completion_status: String(item.completion_status ?? item.completionStatus ?? 'complete').toLowerCase() as ChatMessage['completion_status'],
    created_at: String(item.created_at ?? item.createdAt ?? new Date().toISOString()),
  }));
}

export interface MangaImage {
  id: number;
  image_version: string;
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
  version?: number;
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
  return readApiData(res);
}

export async function listStories(): Promise<Story[]> {
  const res = await authFetch(`${BASE}/api/stories`);
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function updateStory(storyId: number, data: { title?: string; description?: string }): Promise<Story> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function deleteStory(storyId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}

export async function exportStory(story: Story): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${story.id}/export`);
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

export function importStoryPackage(
  file: File,
  onProgress?: (progress: ImportStoryProgress) => void,
): Promise<Story> {
  return new Promise((resolve, reject) => {
    const doSend = () => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}/api/stories/import`);
      xhr.withCredentials = true;
      const headers = apiHeaders(false, 'POST');
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
        onProgress?.({ phase: 'uploading', percent, message: `婵犳鍠楃换鎰緤閽樺鑰挎い蹇撴噽閳绘柨鈹戦悩杈厡闁绘劕锕ら湁闁挎繂妫涢惌濠囨煙娴ｅ啿娲ょ粈?${percent}%` });
      };

      xhr.onload = async () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress?.({ phase: 'processing', percent: 100, message: '...' });
          try {
            resolve(unwrapApiData(JSON.parse(xhr.responseText)));
          } catch {
            reject(new Error('Import failed'));
          }
          return;
        }
        if (xhr.status === 401) {
          const refreshed = await refreshAuthSession();
          if (refreshed) {
            doSend();
            return;
          }
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
  const data = await readApiData(res);
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
  return readApiData(res);
}

export async function listChapters(storyId: number): Promise<Chapter[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/chapters`);
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function createNextChapter(storyId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/chapters`, { method: 'POST' });
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function deleteChapter(chapterId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}


export function chatStream(
  chapterId: number,
  content: string,
  onToken: (token: string) => void,
  _onDone: (message: ChatMessage) => void,
  onError: (err: string) => void,
  model?: string,
): AbortController {
  const controller = new AbortController();

  authFetch(`${BASE}/api/chapters/${chapterId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: content, ...getProviderRequestPayload('llm', model) }),
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
              _onDone(data.message || {
                id: Date.now(),
                role: 'assistant',
                content: data.content,
                completion_status: 'complete',
                created_at: new Date().toISOString(),
              });
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
  return readApiData(res);
}

export async function importNovel(chapterId: number, content: string): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/import-novel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}


export async function generateScenes(chapterId: number, signal?: AbortSignal): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/generate-scenes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({}),
    signal,
  });
  if (!res.ok) throw new Error(await res.text());
  const data = await readApiData(res);
  return data.scenes;
}

export async function getScenes(chapterId: number): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/scenes`);
  if (!res.ok) throw new Error(await res.text());
  const data = await readApiData(res);
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
  const data = await readApiData(res);
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
  return await readApiData(res);
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
  return readApiData(res);
}

export async function createCharacterProfile(storyId: number, name: string, description: string): Promise<CharacterProfile> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function updateCharacterProfile(storyId: number, characterId: number, name: string, description: string): Promise<CharacterProfile> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
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
  return readApiData(res);
}

export async function addCharRefImage(storyId: number, characterId: number, base64: string): Promise<CharRefImage> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
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
  return readApiData(res);
}

export async function createStoryAssetGroup(storyId: number, name: string, description?: string, characterIds?: number[]): Promise<AssetGroup> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description: description || '', characterIds: characterIds || [] }),
  });
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function updateStoryAssetGroup(groupId: number, data: { name?: string; description?: string; characterIds?: number[] }): Promise<AssetGroup> {
  const res = await authFetch(`${BASE}/api/asset-groups/${groupId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
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
  return readApiData(res);
}

export async function deleteStoryAssetGroupRefImage(storyId: number, groupId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/stories/${storyId}/asset-groups/${groupId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function getChapterAssetGroup(chapterId: number): Promise<AssetGroupsPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/asset-group`);
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function setChapterAssetGroup(chapterId: number, groupId: number | null): Promise<AssetGroupsPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/asset-group`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ group_id: groupId }),
  });
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

// Story-level
export async function getStoryRefImages(storyId: number): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/ref-images`);
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function addStoryRefImage(storyId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function deleteStoryRefImage(storyId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/stories/${storyId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

// Chapter-level (with story fallback)
export async function getChapterRefImages(chapterId: number): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/ref-images`);
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function addChapterRefImage(chapterId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}

export async function deleteChapterRefImage(chapterId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/chapters/${chapterId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return readApiData(res);
}


export type MangaStyle = 'japanese_manga' | 'korean_webtoon' | 'american_comic' | 'ligne_claire' | 'chinese_ink' | 'semi_realistic' | 'realistic' | 'oil_painting' | 'flat_design' | 'pixel_art' | 'watercolor' | 'cyberpunk';

export const MANGA_STYLE_LABELS: Record<MangaStyle, string> = {
  japanese_manga: '日式漫画',
  korean_webtoon: '韩式网漫',
  american_comic: '美式漫画',
  ligne_claire: '欧式清线',
  chinese_ink: '中国水墨',
  semi_realistic: '半写实',
  realistic: '写实',
  oil_painting: '油画',
  flat_design: '扁平设计',
  pixel_art: '像素艺术',
  watercolor: '水彩',
  cyberpunk: '赛博朋克',
};

export async function getMangaStyle(storyId: number): Promise<MangaStyle> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/manga-style`);
  if (!res.ok) throw new Error(await res.text());
  const data = await readApiData(res);
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
  duotone: '双色调',
};


export async function getColorMode(chapterId: number): Promise<ColorMode> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/color-mode`);
  if (!res.ok) throw new Error(await res.text());
  const data = await readApiData(res);
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
  const data = await readApiData(res);
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
  model: string | undefined,
  expectedImage?: { id: number; image_version: string },
): Promise<{ id: number; image_version: string; image_number: number; image_path: string; prompt: string }> {
  if (expectedImage && (!expectedImage.id || !expectedImage.image_version)) throw new Error('缺少图片版本，请刷新后重新确认');
  const url = expectedImage ? `/api/chapters/${chapterId}/regenerate-image/${imageNumber}` : `/api/chapters/${chapterId}/manga-images/${imageNumber}/generation-requests`;
  const snapshot = await submitImageRequest(url, {
    prompt, ...(expectedImage ? { expected_image_id: expectedImage.id, expected_image_version: expectedImage.image_version } : { expected_absent: true }),
    ...getImageGenerationProviderPayload(model),
  }, `chapter.${chapterId}`);
  return new Promise((resolve, reject) => {
    const watcher = watchImageRequest(snapshot.request_id, current => {
      const item = current.images[0];
      if (item?.status === 'SUCCEEDED' && item.manga_image_id && item.image_version && item.image_path) {
        watcher.abort();resolve({ id: item.manga_image_id, image_version: item.image_version, image_number: imageNumber, image_path: item.image_path, prompt });
      } else if (current.status === 'NEEDS_REVIEW' || isImageRequestTerminal(current.status)) {
        watcher.abort();reject(new Error(item?.evidence_summary || item?.error_code || '生成未完成，请查看请求状态'));
      }
    });
  });
}


export interface MangaProgress {
  type: 'status' | 'scenes' | 'progress' | 'image' | 'image_error' | 'done' | 'error';
  // data 是后端 SSE 推送的多态负载，形状随 type 变化（message/image_number/total/image_path/error 等）。
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  data: any;
}

export function generateMangaStream(
  chapterId: number,
  assetGroupId: number | null | undefined,
  onEvent: (event: MangaProgress) => void,
  model?: string,
): AbortController {
  const controller = new AbortController();
  let watcher: AbortController | undefined;
  const seen = new Map<string, string>();
  const deliver = (snapshot: ImageRequestSnapshot) => {
    onEvent({ type: 'status', data: { message: snapshot.status ? IMAGE_STATUS_LABELS[snapshot.status] : '状态暂不可核实' } });
    for (const item of snapshot.images) {
      if (item.status === 'SUCCEEDED' && item.image_path) {
        const receipt = `${item.manga_image_id}:${item.image_version}`;
        if (seen.get(item.operation_id) === receipt) continue;
        seen.set(item.operation_id, receipt);
        onEvent({ type: 'image', data: { ...item, id: item.manga_image_id, total: snapshot.total_images } });
      }
    }
    if (isImageRequestTerminal(snapshot.status)) {
      onEvent({ type: 'done', data: snapshot });watcher?.abort();
    } else if (snapshot.status === 'NEEDS_REVIEW') {
      onEvent({ type: 'error', data: { error: '当前图片待核实，请使用下方恢复操作；后端已暂停后续图片。' } });watcher?.abort();
    }
  };
  void submitImageRequest(`/api/chapters/${chapterId}/generate-manga-stream`, { asset_group_id: assetGroupId ?? null, ...getImageGenerationProviderPayload(model) }, `chapter.${chapterId}`, controller.signal)
    .then(snapshot => {
      if (controller.signal.aborted) return;
      deliver(snapshot);
      if (!isImageRequestTerminal(snapshot.status) && snapshot.status !== 'NEEDS_REVIEW') watcher = watchImageRequest(snapshot.request_id, deliver, error => onEvent({ type: 'status', data: { message: `查询暂不可用：${error.message}` } }));
    })
    .catch(error => { if (!controller.signal.aborted) onEvent({ type: 'error', data: { error: error.message } }); });
  controller.signal.addEventListener('abort', () => watcher?.abort());
  return controller;
}

export function mangaImageUrl(imagePath: string, cacheBust?: number): string {
  // imagePath is like "manga_outputs/chapter_1/panel_01_abc12345.png"
  // Served at /static/manga/chapter_1/panel_01_abc12345.png
  const url = `${BASE}/static/manga/${mangaStaticPath(imagePath)}`;
  return cacheBust ? `${url}?t=${cacheBust}` : url;
}

// ---- Publish ----
export async function publishStory(storyId: number, format: 'novel' | 'manga', isPublished: boolean, chapterIds?: number[]): Promise<Story> {
  const res = await authFetch(BASE+'/api/stories/'+storyId+'/publish', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ format, is_published: isPublished, chapter_ids: chapterIds }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function updateChapterOrder(storyId: number, orders: { chapter_id: number; display_order: number; display_title?: string }[]): Promise<void> {
  const res = await authFetch(BASE+'/api/stories/'+storyId+'/chapter-order', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ orders }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

// ---- Square ----
export type SquareFormat = 'all' | 'novel' | 'manga';
export interface SquareStory { id: number; format: Exclude<SquareFormat, 'all'>; title: string; description: string; cover_url: string; manga_style: string; published_at: string | null; chapter_count: number; content_count: number; }
export interface SquareStoryDetail { id: number; format: Exclude<SquareFormat, 'all'>; title: string; description: string; cover_url: string; manga_style: string; published_at: string | null; available_formats: Exclude<SquareFormat, 'all'>[]; chapters: { id: number; chapter_number: number; display_title: string; content: string | null; content_count: number; images: { id: number; image_number: number; image_url: string }[] }[]; }

export async function listSquareStories(page = 0, size = 12, search?: string, format: SquareFormat = 'manga'): Promise<{ content: SquareStory[]; total_pages: number; total_elements: number; facets: Record<SquareFormat, number> }> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (search) params.set('search', search);
  params.set('format', format);
  const res = await fetch(BASE+'/api/square/stories?'+params);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

export async function getSquareStoryDetail(id: number, format: Exclude<SquareFormat, 'all'> = 'manga'): Promise<SquareStoryDetail> {
  const res = await fetch(BASE+'/api/square/stories/'+id+'?format='+format);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

// ---- My Works ----
export interface MyWorkChapter {
  id: number;
  chapter_number: number;
  is_published: boolean;
  manga_is_published: boolean;
  novel_is_published: boolean;
  has_novel_content: boolean;
  novel_char_count: number;
  manga_image_count: number;
  display_order: number;
  display_title: string;
  status: string;
}
export interface MyWork {
  id: number;
  title: string;
  description: string;
  cover_image: string;
  is_published: boolean;
  manga_is_published: boolean;
  novel_is_published: boolean;
  published_at: string | null;
  manga_published_at: string | null;
  novel_published_at: string | null;
  created_at: string | null;
  chapters: MyWorkChapter[];
}

export async function listMyWorks(): Promise<MyWork[]> {
  const res = await authFetch(BASE+'/api/works');
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return readApiData(res);
}

