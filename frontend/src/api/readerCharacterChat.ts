import { authFetch, BASE, parseApiError, readApiData } from './auth';

export interface ReaderCharacterOption {
  id: number;
  name: string;
  description: string;
  anchor_chapter_number: number;
  chat_ready: boolean;
  friend?: boolean;
  lifecycle_status?: 'ACTIVE' | 'TERMINAL_FUTURE' | 'DEAD' | string;
  terminal_chapter_number?: number | null;
  can_chat?: boolean;
  conversation_id?: string | null;
  last_message?: string | null;
}

export interface ReaderCharacterConversation {
  id: string;
  story_id: number;
  character_id: number;
  character_name: string;
  anchor_chapter_id: number;
  anchor_chapter_number: number;
  snapshot_status: string;
  conversation_type?: 'SINGLE' | 'GROUP' | string;
  member_ids?: number[];
}

export interface ReaderCharacterConversationSummary {
  id: string;
  conversation_type: 'SINGLE' | 'GROUP' | string;
  title: string;
  character_id: number | null;
  character_name: string;
  anchor_chapter_number: number | null;
  last_message: string | null;
  last_message_at: string;
  unread_count: number;
  member_count: number;
}

export interface ReaderCharacterMessage {
  id: number;
  role: 'user' | 'assistant' | 'system';
  content: string;
  request_id: string;
  created_at: string;
  sender_character_id?: number | null;
  sender_character_name?: string | null;
  deleted?: boolean;
}

export async function listReaderCharacters(storyId: number, chapterId: number): Promise<ReaderCharacterOption[]> {
  const response = await authFetch(`${BASE}/api/reader/stories/${storyId}/chapters/${chapterId}/characters`);
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  const data = await readApiData<{ characters: ReaderCharacterOption[] }>(response);
  return data.characters ?? [];
}

export async function listReaderCharacterFriends(storyId: number, chapterId: number, query = ''): Promise<ReaderCharacterOption[]> {
  const params = query.trim() ? `?q=${encodeURIComponent(query.trim())}` : '';
  const response = await authFetch(`${BASE}/api/reader/stories/${storyId}/chapters/${chapterId}/friends${params}`);
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  const data = await readApiData<{ characters: ReaderCharacterOption[] }>(response);
  return data.characters ?? [];
}

export async function listReaderCharacterConversations(storyId: number, chapterId: number): Promise<ReaderCharacterConversationSummary[]> {
  const response = await authFetch(`${BASE}/api/reader/stories/${storyId}/chapters/${chapterId}/conversations`);
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  const data = await readApiData<{ conversations: ReaderCharacterConversationSummary[] }>(response);
  return data.conversations ?? [];
}

export async function addReaderCharacterFriend(storyId: number, characterId: number, chapterId: number): Promise<ReaderCharacterConversation> {
  const response = await authFetch(`${BASE}/api/reader/character-friends`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ story_id: storyId, character_id: characterId, chapter_id: chapterId }),
  });
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  return readApiData(response);
}

export async function createReaderCharacterGroup(storyId: number, chapterId: number, characterIds: number[], title?: string): Promise<ReaderCharacterConversation> {
  const response = await authFetch(`${BASE}/api/reader/character-groups`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ story_id: storyId, chapter_id: chapterId, character_ids: characterIds, title: title?.trim() || null }),
  });
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  return readApiData(response);
}

export async function createReaderCharacterConversation(storyId: number, characterId: number, chapterId: number): Promise<ReaderCharacterConversation> {
  const response = await authFetch(`${BASE}/api/reader/character-conversations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ story_id: storyId, character_id: characterId, chapter_id: chapterId }),
  });
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  return readApiData(response);
}

export async function listReaderCharacterMessages(conversationId: string): Promise<ReaderCharacterMessage[]> {
  const response = await authFetch(`${BASE}/api/reader/character-conversations/${conversationId}/messages`);
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  return readApiData(response);
}

export async function sendReaderCharacterMessage(conversationId: string, message: string, clientMessageId: string): Promise<{ userMessage: ReaderCharacterMessage; assistantMessage: ReaderCharacterMessage }> {
  const response = await authFetch(`${BASE}/api/reader/character-conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, client_message_id: clientMessageId }),
  });
  if (!response.ok) throw new Error(parseApiError(await response.text()));
  const data = await readApiData<{
    userMessage?: ReaderCharacterMessage;
    user_message?: ReaderCharacterMessage;
    assistantMessage?: ReaderCharacterMessage;
    assistant_message?: ReaderCharacterMessage;
  }>(response);
  const userMessage = data.userMessage ?? data.user_message;
  const assistantMessage = data.assistantMessage ?? data.assistant_message;
  if (!userMessage || !assistantMessage) throw new Error('角色回复格式无效');
  return { userMessage, assistantMessage };
}

export async function deleteReaderCharacterMessage(messageId: number): Promise<void> {
  const response = await authFetch(`${BASE}/api/reader/character-messages/${messageId}`, { method: 'DELETE' });
  if (!response.ok) throw new Error(parseApiError(await response.text()));
}
