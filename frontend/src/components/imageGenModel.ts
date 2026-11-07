import type { AiConversationSummary, ImageGenRecord } from '../api';
import { randomUUID } from '../utils/browserSecurity.ts';

export interface Message {
  id: string;
  type: 'user' | 'ai';
  prompt?: string;
  refThumbnails?: string[];
  refImageKeys?: string[];
  record?: ImageGenRecord;
}

export interface GenTheme {
  id: string;
  conversationId?: string;
  titleSource?: AiConversationSummary['titleSource'];
  titleState?: AiConversationSummary['titleState'];
  manualTitlePending?: boolean;
  name: string;
  createdAt: string;
  messages: Message[];
}

export interface GenConfig {
  resolution: string;
  aspectRatio: string;
}

export const LS_THEMES_KEY = 'artverse.genThemes';
const LS_ACTIVE_THEME_KEY = 'artverse.activeGenTheme';
const LS_GEN_CONFIG_KEY = 'artverse.genConfig';
export const LS_CANVAS_OPEN_KEY = 'artverse.genCanvasOpen';
export const LS_CANVAS_WIDTH_KEY = 'artverse.genCanvasWidth';
const DEFAULT_CONFIG: GenConfig = { resolution: '1024x1024', aspectRatio: '1:1' };

export const RESOLUTIONS = [
  { label: '1024×1024', value: '1024x1024', ratio: '1:1' },
  { label: '1152×864', value: '1152x864', ratio: '4:3' },
  { label: '864×1152', value: '864x1152', ratio: '3:4' },
  { label: '1280×720', value: '1280x720', ratio: '16:9' },
  { label: '720×1280', value: '720x1280', ratio: '9:16' },
  { label: '1344×768', value: '1344x768', ratio: '16:9' },
  { label: '768×1344', value: '768x1344', ratio: '9:16' },
];

export const ASPECT_RATIOS = [
  { label: '1:1', value: '1:1', sub: '正方形' },
  { label: '4:3', value: '4:3', sub: '横屏' },
  { label: '3:4', value: '3:4', sub: '竖屏' },
  { label: '16:9', value: '16:9', sub: '宽屏' },
  { label: '9:16', value: '9:16', sub: '长屏' },
];

export function generateId(): string {
  return randomUUID();
}

export function imageThemeManualTitle(theme: GenTheme): string | undefined {
  if (theme.titleSource) return theme.titleSource === 'USER' ? theme.name : undefined;
  // Old caches did not record title ownership. Preserve their custom names.
  return /^(默认主题|新会话|新主题(?:\s+\d+)?)$/.test(theme.name.trim()) ? undefined : theme.name;
}

export function imageThemePromptTitle(theme: GenTheme, prompt: string): GenTheme {
  if (imageThemeManualTitle(theme) || theme.titleSource === 'AI' || theme.titleSource === 'FALLBACK') return theme;
  const name = Array.from(prompt.replace(/[\p{Cc}\p{Z}\s]+/gu, ' ').trim()).slice(0, 24).join('');
  return name ? { ...theme, name, titleSource: 'FALLBACK', titleState: 'GENERATING' } : theme;
}

export function recoverImageThemeConversation(theme: GenTheme): GenTheme {
  if (theme.conversationId) return theme;
  const ids = new Set(theme.messages.flatMap(message => message.record?.conversation_id ? [message.record.conversation_id] : []));
  return ids.size === 1 ? { ...theme, conversationId: [...ids][0] } : theme;
}

export function mergeImageConversationTitles(themes: GenTheme[], summaries: AiConversationSummary[]): GenTheme[] {
  const byId = new Map(summaries.filter(item => item.type === 'IMAGE_GEN').map(item => [item.conversationId, item]));
  let changed = false;
  const updated = themes.map(theme => {
    const summary = theme.conversationId ? byId.get(theme.conversationId) : undefined;
    if (!summary || theme.manualTitlePending || imageThemeManualTitle(theme)) return theme;
    // Keep the immediate prompt fallback while the generation request is being accepted.
    if (theme.titleSource === 'FALLBACK' && summary.titleSource === 'DEFAULT') return theme;
    if (theme.name === summary.title && theme.titleSource === summary.titleSource && theme.titleState === summary.titleState) return theme;
    changed = true;
    return { ...theme, name: summary.title, titleSource: summary.titleSource, titleState: summary.titleState };
  });
  return changed ? updated : themes;
}

export function loadThemes(): GenTheme[] {
  try {
    const raw = localStorage.getItem(LS_THEMES_KEY);
    return raw ? (JSON.parse(raw) as GenTheme[]).map(recoverImageThemeConversation) : [];
  } catch { return []; }
}

export function saveThemes(themes: GenTheme[]) {
  const persistedThemes = themes.map((theme) => ({
    ...theme,
    messages: theme.messages.map((message) => {
      const persistedMessage = { ...message };
      delete persistedMessage.refThumbnails;
      return persistedMessage;
    }),
  }));
  localStorage.setItem(LS_THEMES_KEY, JSON.stringify(persistedThemes));
}

export const loadActiveThemeId = () => localStorage.getItem(LS_ACTIVE_THEME_KEY);
export const saveActiveThemeId = (id: string) => localStorage.setItem(LS_ACTIVE_THEME_KEY, id);

export function loadGenConfig(): GenConfig {
  try {
    const raw = localStorage.getItem(LS_GEN_CONFIG_KEY);
    return raw ? JSON.parse(raw) as GenConfig : DEFAULT_CONFIG;
  } catch { return DEFAULT_CONFIG; }
}

export const saveGenConfig = (config: GenConfig) => localStorage.setItem(LS_GEN_CONFIG_KEY, JSON.stringify(config));

export function historyMessages(records: ImageGenRecord[]): Message[] {
  return [...records].reverse().flatMap((record) => [
    { id: 'u-' + record.id, type: 'user' as const, prompt: record.prompt },
    { id: 'a-' + record.id, type: 'ai' as const, record },
  ]);
}

export function mergeHistoryIntoThemes(themes: GenTheme[], records: ImageGenRecord[]): GenTheme[] {
  if (themes.length === 0) return themes;
  const recordsById = new Map(records.map((record) => [record.id, record]));
  const recordsByRequest = new Map(records.filter(record => record.request_id).map(record => [record.request_id, record]));
  const knownRecordIds = new Set<number>();
  const updatedThemes = themes.map((theme) => ({
    ...theme,
    messages: theme.messages.map((message) => {
      if (!message.record) return message;
      knownRecordIds.add(message.record.id);
      const current = (message.record.request_id ? recordsByRequest.get(message.record.request_id) : undefined) || recordsById.get(message.record.id);
      if (current) knownRecordIds.add(current.id);
      return current ? { ...message, record: current } : message;
    }),
  }));
  const missingRecords = records.filter((record) => !knownRecordIds.has(record.id));
  if (missingRecords.length === 0) return updatedThemes.map(recoverImageThemeConversation);
  const recoveredMessages = historyMessages(missingRecords);
  return updatedThemes.map((theme, index) => index === 0
    ? { ...theme, messages: [...recoveredMessages, ...theme.messages] }
    : theme).map(recoverImageThemeConversation);
}

export function removeMessageRound(messages: Message[], msgId: string): Message[] {
  const index = messages.findIndex((message) => message.id === msgId);
  if (index < 0) return messages;
  const idsToRemove = new Set<string>([msgId]);
  const current = messages[index];
  const next = messages[index + 1];
  const previous = messages[index - 1];
  if (current?.type === 'user' && next?.type === 'ai') idsToRemove.add(next.id);
  if (current?.type === 'ai' && previous?.type === 'user') idsToRemove.add(previous.id);
  return messages.filter((message) => !idsToRemove.has(message.id));
}

export const fmtRes = (value: string | null | undefined) => value ? value.split('x').join('×') : '';
