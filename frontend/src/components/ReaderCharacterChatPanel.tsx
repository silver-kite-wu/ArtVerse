import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import {
  ArrowLeft,
  ChevronLeft,
  GripVertical,
  Loader2,
  MessageCircle,
  PanelRightClose,
  PanelRightOpen,
  Plus,
  Search,
  Send,
  Sparkles,
  Trash2,
  UserPlus,
  Users,
  X,
} from 'lucide-react';
import {
  addReaderCharacterFriend,
  createReaderCharacterConversation,
  createReaderCharacterGroup,
  deleteReaderCharacterMessage,
  listReaderCharacterConversations,
  listReaderCharacterFriends,
  listReaderCharacterMessages,
  listReaderCharacters,
  sendReaderCharacterMessage,
  type ReaderCharacterConversation,
  type ReaderCharacterConversationSummary,
  type ReaderCharacterMessage,
  type ReaderCharacterOption,
} from '../api';
import { useToast } from '../ui/toast/useToast';
import { randomUUID } from '../utils/browserSecurity';

export type ReaderCharacterChatMode = 'floating' | 'docked';

type FloatingPosition = { right: number; bottom: number };

const DEFAULT_POSITION: FloatingPosition = { right: 20, bottom: 20 };

function isDead(character: ReaderCharacterOption | null | undefined): boolean {
  return character?.lifecycle_status === 'DEAD';
}

export default function ReaderCharacterChatPanel({
  storyId,
  chapterId,
  mode,
  onModeChange,
  onClose,
}: {
  storyId: number;
  chapterId: number;
  mode: ReaderCharacterChatMode;
  onModeChange: (mode: ReaderCharacterChatMode) => void;
  onClose: () => void;
}) {
  const toast = useToast();
  const [view, setView] = useState<'list' | 'chat'>('list');
  const [friends, setFriends] = useState<ReaderCharacterOption[]>([]);
  const [characters, setCharacters] = useState<ReaderCharacterOption[]>([]);
  const [conversations, setConversations] = useState<ReaderCharacterConversationSummary[]>([]);
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null);
  const [activeConversation, setActiveConversation] = useState<ReaderCharacterConversationSummary | null>(null);
  const [activeCharacter, setActiveCharacter] = useState<ReaderCharacterOption | null>(null);
  const [messages, setMessages] = useState<ReaderCharacterMessage[]>([]);
  const [draft, setDraft] = useState('');
  const [search, setSearch] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [picker, setPicker] = useState<'friend' | 'group' | null>(null);
  const [groupSelection, setGroupSelection] = useState<number[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingChat, setLoadingChat] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [selectingMessages, setSelectingMessages] = useState(false);
  const [selectedMessageIds, setSelectedMessageIds] = useState<number[]>([]);
  const [deleting, setDeleting] = useState(false);
  const [position, setPosition] = useState<FloatingPosition>(DEFAULT_POSITION);
  const endRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ x: number; y: number; right: number; bottom: number } | null>(null);

  const loadList = async () => {
    setLoading(true);
    setError('');
    try {
      const [nextFriends, nextConversations] = await Promise.all([
        listReaderCharacterFriends(storyId, chapterId, search),
        listReaderCharacterConversations(storyId, chapterId),
      ]);
      setFriends(nextFriends);
      setConversations(nextConversations);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '角色列表加载失败');
    } finally {
      setLoading(false);
    }
  };

  // Loading the server-backed list is the synchronization this effect owns.
  // eslint-disable-next-line react-hooks/set-state-in-effect, react-hooks/exhaustive-deps
  useEffect(() => { void loadList(); }, [storyId, chapterId, search]);

  const loadCharacters = async () => {
    if (characters.length > 0) return;
    try {
      setCharacters(await listReaderCharacters(storyId, chapterId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '角色加载失败');
    }
  };

  const openConversation = async (
    conversation: ReaderCharacterConversation,
    character?: ReaderCharacterOption,
    summary?: ReaderCharacterConversationSummary,
  ) => {
    setLoadingChat(true);
    setError('');
    try {
      setActiveConversationId(conversation.id);
      setActiveConversation({
        id: conversation.id,
        conversation_type: conversation.conversation_type ?? 'SINGLE',
        title: conversation.character_name,
        character_id: conversation.character_id,
        character_name: conversation.character_name,
        anchor_chapter_number: conversation.anchor_chapter_number,
        last_message: summary?.last_message ?? null,
        last_message_at: summary?.last_message_at ?? '',
        unread_count: 0,
        member_count: conversation.member_ids?.length ?? 1,
      });
      setActiveCharacter(character ?? null);
      setDraft('');
      setSelectingMessages(false);
      setSelectedMessageIds([]);
      setView('chat');
      setMessages(await listReaderCharacterMessages(conversation.id));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '聊天记录加载失败');
    } finally {
      setLoadingChat(false);
    }
  };

  const openFriend = async (character: ReaderCharacterOption) => {
    setMenuOpen(false);
    setPicker(null);
    setLoadingChat(true);
    setError('');
    try {
      const conversation = await createReaderCharacterConversation(storyId, character.id, chapterId);
      await openConversation(conversation, character);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '角色会话打开失败');
      setLoadingChat(false);
    }
  };

  const openSummary = async (summary: ReaderCharacterConversationSummary) => {
    setLoadingChat(true);
    setError('');
    try {
      if (summary.conversation_type === 'SINGLE' && summary.character_id) {
        const character = friends.find((item) => item.id === summary.character_id)
          ?? (await listReaderCharacterFriends(storyId, chapterId)).find((item) => item.id === summary.character_id);
        if (!character) throw new Error('角色好友不存在');
        const conversation = await createReaderCharacterConversation(storyId, character.id, chapterId);
        await openConversation(conversation, character, summary);
      } else {
        setActiveConversationId(summary.id);
        setActiveConversation(summary);
        setActiveCharacter(null);
        setDraft('');
        setSelectingMessages(false);
        setSelectedMessageIds([]);
        setView('chat');
        setMessages(await listReaderCharacterMessages(summary.id));
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '聊天记录加载失败');
    } finally {
      setLoadingChat(false);
    }
  };

  const addFriend = async (character: ReaderCharacterOption) => {
    setLoadingChat(true);
    setError('');
    setPicker(null);
    setGroupSelection([]);
    try {
      const conversation = await addReaderCharacterFriend(storyId, character.id, chapterId);
      await openConversation(conversation, { ...character, friend: true, conversation_id: conversation.id });
      await loadList();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '添加角色好友失败');
      setLoadingChat(false);
    }
  };

  const createGroup = async () => {
    if (groupSelection.length < 2) {
      setError('群聊至少需要选择两个角色');
      return;
    }
    setLoadingChat(true);
    setError('');
    try {
      const conversation = await createReaderCharacterGroup(storyId, chapterId, groupSelection);
      const summary: ReaderCharacterConversationSummary = {
        id: conversation.id,
        conversation_type: 'GROUP',
        title: conversation.character_name,
        character_id: null,
        character_name: conversation.character_name,
        anchor_chapter_number: conversation.anchor_chapter_number,
        last_message: null,
        last_message_at: new Date().toISOString(),
        unread_count: 0,
        member_count: groupSelection.length,
      };
      setPicker(null);
      setGroupSelection([]);
      await openConversation(conversation, undefined, summary);
      await loadList();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '群聊创建失败');
      setLoadingChat(false);
    }
  };

  const send = async () => {
    const value = draft.trim();
    if (!value || !activeConversationId || sending) return;
    setSending(true); setError('');
    const clientMessageId = randomUUID();
    const optimistic: ReaderCharacterMessage = {
      id: Date.now(),
      role: 'user',
      content: value,
      request_id: clientMessageId,
      created_at: new Date().toISOString(),
    };
    setMessages((items) => [...items, optimistic]);
    setDraft('');
    try {
      const result = await sendReaderCharacterMessage(activeConversationId, value, clientMessageId);
      setMessages((items) => [
        ...items.map((item) => item.request_id === clientMessageId ? result.userMessage : item),
        result.assistantMessage,
      ]);
      await loadList();
    } catch (cause) {
      setMessages((items) => items.filter((item) => item.request_id !== clientMessageId));
      toast.error(cause instanceof Error ? cause.message : '角色暂时无法回复');
    } finally { setSending(false); }
  };

  const toggleMessage = (id: number) => {
    setSelectedMessageIds((items) => items.includes(id) ? items.filter((item) => item !== id) : [...items, id]);
  };

  const deleteSelectedMessages = async () => {
    if (selectedMessageIds.length === 0 || deleting || sending) return;
    setDeleting(true);
    try {
      await Promise.all(selectedMessageIds.map((id) => deleteReaderCharacterMessage(id)));
      setMessages((items) => items.map((item) => selectedMessageIds.includes(item.id)
        ? { ...item, content: '消息已删除', deleted: true }
        : item));
      setSelectedMessageIds([]);
      setSelectingMessages(false);
      toast.success('消息已删除');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '删除消息失败');
    } finally { setDeleting(false); }
  };

  const startDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (mode !== 'floating' || event.button !== 0 || (event.target instanceof Element && event.target.closest('button'))) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    const panel = event.currentTarget.parentElement?.getBoundingClientRect();
    dragRef.current = {
      x: event.clientX,
      y: event.clientY,
      right: panel ? window.innerWidth - panel.right : position.right,
      bottom: panel ? window.innerHeight - panel.bottom : position.bottom,
    };
  };

  const moveDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag) return;
    const panel = event.currentTarget.parentElement?.getBoundingClientRect();
    setPosition({
      right: Math.max(8, Math.min(window.innerWidth - (panel?.width ?? 420) - 8, drag.right - (event.clientX - drag.x))),
      bottom: Math.max(8, Math.min(window.innerHeight - (panel?.height ?? 720) - 8, drag.bottom - (event.clientY - drag.y))),
    });
  };

  const endDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (dragRef.current) event.currentTarget.releasePointerCapture(event.pointerId);
    dragRef.current = null;
  };

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages, sending]);

  const filteredPickerCharacters = useMemo(() => {
    const added = new Set(friends.map((friend) => friend.id));
    return characters.filter((character) => picker === 'group' || !added.has(character.id));
  }, [characters, friends, picker]);

  const activeTitle = activeCharacter?.name || activeConversation?.character_name || activeConversation?.title || '角色群聊';
  const activeIsDead = isDead(activeCharacter);
  const floatingStyle = mode === 'floating'
    ? {
      right: `clamp(8px, ${position.right}px, max(8px, calc(100vw - 428px)))`,
      bottom: `clamp(8px, ${position.bottom}px, max(8px, calc(100vh - 728px)))`,
      width: 'min(420px, calc(100vw - 24px))',
      height: 'min(720px, calc(100vh - 24px))',
    }
    : undefined;

  return (
    <section
      className={mode === 'docked'
        ? 'fixed inset-y-0 right-0 z-40 flex w-[min(430px,calc(100vw-16px))] flex-col border-l border-border bg-bg-base shadow-2xl'
        : 'fixed z-40 flex flex-col overflow-hidden rounded-2xl border border-border bg-bg-base shadow-2xl'}
      style={floatingStyle}
      role="dialog"
      aria-modal="false"
      aria-label="角色聊天"
    >
      <header
        className="flex shrink-0 touch-none select-none cursor-grab items-center gap-2 border-b border-border bg-bg-surface/95 px-3 py-3 backdrop-blur active:cursor-grabbing"
        onPointerDown={startDrag}
        onPointerMove={moveDrag}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
      >
        {mode === 'floating' && <GripVertical size={16} className="shrink-0 text-text-muted" aria-hidden="true" />}
        {view === 'chat' && <button type="button" className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-text-secondary hover:bg-accent-soft hover:text-accent" disabled={sending || deleting} onClick={() => { setView('list'); setSelectingMessages(false); setSelectedMessageIds([]); }} aria-label="返回角色列表"><ArrowLeft size={17} /></button>}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5 text-sm font-semibold text-text-primary"><MessageCircle size={15} className="text-accent" />{view === 'list' ? '和角色聊聊' : activeTitle}</div>
          <div className="truncate text-[11px] text-text-muted">{view === 'list' ? '选择角色或发起群聊' : activeIsDead ? `已于第 ${activeCharacter?.terminal_chapter_number} 章离场 · 单聊停留在离场前` : `认知截止第 ${activeConversation?.anchor_chapter_number ?? chapterId} 章`}</div>
        </div>
        {view === 'list' && <><button type="button" className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-text-secondary hover:bg-accent-soft hover:text-accent ${searchOpen ? 'bg-accent-soft text-accent' : ''}`} onClick={() => setSearchOpen((value) => !value)} aria-label="搜索已添加角色"><Search size={17} /></button><button type="button" className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-text-secondary hover:bg-accent-soft hover:text-accent ${menuOpen ? 'bg-accent-soft text-accent' : ''}`} onClick={() => setMenuOpen((value) => !value)} aria-label="添加角色或发起群聊"><Plus size={18} /></button></>}
        {view === 'chat' && <button type="button" className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-text-secondary hover:bg-accent-soft hover:text-accent ${selectingMessages ? 'bg-accent-soft text-accent' : ''}`} onClick={() => setSelectingMessages((value) => !value)} aria-label="选择消息"><Trash2 size={16} /></button>}
        <button type="button" className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-text-secondary hover:bg-accent-soft hover:text-accent" onClick={onModeChange.bind(null, mode === 'docked' ? 'floating' : 'docked')} aria-label={mode === 'docked' ? '窗口悬浮' : '吸附到右侧'} title={mode === 'docked' ? '窗口悬浮' : '吸附到右侧'}>{mode === 'docked' ? <PanelRightClose size={17} /> : <PanelRightOpen size={17} />}</button>
        <button type="button" className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-text-secondary hover:bg-accent-soft hover:text-accent" onClick={onClose} aria-label="关闭"><X size={17} /></button>
      </header>

      {error && !picker && <div role="alert" className="shrink-0 border-b border-border bg-red-500/10 px-3 py-2 text-xs text-red-500">{error}</div>}

      {view === 'list' ? (
        <div className="min-h-0 flex-1 overflow-y-auto">
          {searchOpen && <div className="border-b border-border bg-bg-surface px-3 py-2"><div className="flex items-center gap-2 rounded-xl border border-border bg-bg-base px-3 py-2"><Search size={14} className="text-text-muted" /><input autoFocus value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索已添加的角色" className="min-w-0 flex-1 bg-transparent text-sm outline-none" /><button type="button" onClick={() => { setSearch(''); setSearchOpen(false); }} aria-label="清除搜索"><X size={14} /></button></div></div>}
          {menuOpen && <div className="border-b border-border bg-bg-surface px-3 py-2"><button type="button" className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm hover:bg-accent-soft" onClick={() => { setMenuOpen(false); setPicker('friend'); void loadCharacters(); }}><UserPlus size={16} className="text-accent" /><span><strong className="font-medium">添加角色好友</strong><small className="ml-2 text-xs text-text-muted">从本书角色中选择</small></span></button><button type="button" className="mt-1 flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm hover:bg-accent-soft" onClick={() => { setMenuOpen(false); setPicker('group'); void loadCharacters(); }}><Users size={16} className="text-accent" /><span><strong className="font-medium">发起群聊</strong><small className="ml-2 text-xs text-text-muted">选择两个或更多角色</small></span></button></div>}
          {loading ? <div className="flex min-h-48 items-center justify-center text-sm text-text-muted"><Loader2 className="mr-2 animate-spin" size={18} />正在加载角色</div> : (
            <div className="p-3">
              {friends.length === 0 && conversations.filter((item) => item.conversation_type === 'GROUP').length === 0 && <div className="flex min-h-64 flex-col items-center justify-center px-6 text-center text-text-muted"><Sparkles size={32} className="mb-3 text-accent/60" /><p className="text-sm">还没有角色好友</p><p className="mt-1 text-xs">点击右上角 ＋ 添加角色，开始第一段对话。</p></div>}
              {friends.length > 0 && <div><div className="mb-2 px-1 text-xs font-medium text-text-muted">角色好友</div><div className="space-y-1">{friends.map((character) => <button key={character.id} type="button" onClick={() => void openFriend(character)} className="flex w-full items-center gap-3 rounded-xl px-2.5 py-3 text-left transition hover:bg-accent-soft"><div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-accent-soft text-sm font-semibold text-accent ${isDead(character) ? 'grayscale opacity-60' : ''}`}>{character.name.slice(0, 1)}</div><div className="min-w-0 flex-1"><div className="flex items-center gap-2 text-sm font-medium text-text-primary"><span className="truncate">{character.name}</span>{isDead(character) && <span className="text-[10px] text-text-muted">已离场</span>}</div><div className="truncate text-xs text-text-muted">{character.last_message || character.description || '开始和角色聊聊'}</div></div><ChevronLeft size={16} className="rotate-180 text-text-muted" /></button>)}</div></div>}
              {conversations.filter((item) => item.conversation_type === 'GROUP').length > 0 && <div className="mt-5"><div className="mb-2 px-1 text-xs font-medium text-text-muted">群聊</div><div className="space-y-1">{conversations.filter((item) => item.conversation_type === 'GROUP').map((conversation) => <button key={conversation.id} type="button" onClick={() => void openSummary(conversation)} className="flex w-full items-center gap-3 rounded-xl px-2.5 py-3 text-left transition hover:bg-accent-soft"><div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-accent-soft text-accent"><Users size={17} /></div><div className="min-w-0 flex-1"><div className="text-sm font-medium text-text-primary">{conversation.title || '角色群聊'}</div><div className="truncate text-xs text-text-muted">{conversation.last_message || `${conversation.member_count} 位角色`}</div></div><ChevronLeft size={16} className="rotate-180 text-text-muted" /></button>)}</div></div>}
            </div>
          )}
        </div>
      ) : (
        <div className="flex min-h-0 flex-1 flex-col">
          {loadingChat ? <div className="flex flex-1 items-center justify-center text-sm text-text-muted"><Loader2 className="mr-2 animate-spin" size={18} />正在打开聊天</div> : <>
            <div className="min-h-0 flex-1 overflow-y-auto px-3 py-4">
              {messages.length === 0 && <div className="mb-5 rounded-2xl border border-border bg-bg-surface p-4 text-sm text-text-secondary"><div className="mb-2 flex items-center gap-2 font-medium text-text-primary"><Sparkles size={15} className="text-accent" />{activeTitle}的阶段对话</div><p className="text-xs leading-6">{activeIsDead ? '我只记得离场前发生的事。你可以问问我的选择、经历，或者此刻的心情。' : '我只记得截至当前章节发生的事。你可以问问我的选择、经历，或者此刻的心情。'}</p></div>}
              <div className="space-y-3">{messages.map((message) => <div key={`${message.id}-${message.request_id}`} className={`group flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}><div className={`flex max-w-[88%] items-end gap-1.5 ${message.role === 'user' ? 'flex-row-reverse' : ''}`}><div className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-accent-soft text-[11px] font-semibold text-accent ${message.sender_character_name && activeIsDead ? 'grayscale opacity-60' : ''}`}>{message.role === 'user' ? '我' : (message.sender_character_name || activeTitle).slice(0, 1)}</div><div className={`min-w-0 rounded-2xl px-3.5 py-2.5 text-sm leading-6 ${message.role === 'user' ? 'rounded-br-sm bg-accent text-white' : 'rounded-bl-sm border border-border bg-bg-surface text-text-primary'} ${message.deleted ? 'opacity-60' : ''} ${selectingMessages && selectedMessageIds.includes(message.id) ? 'ring-2 ring-accent' : ''}`}><div className="mb-1 text-[10px] text-text-muted">{message.role === 'user' ? '我' : (message.sender_character_name || activeTitle)}</div>{message.content}</div>{selectingMessages && <button type="button" className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full ${selectedMessageIds.includes(message.id) ? 'bg-accent text-white' : 'border border-border text-text-muted'}`} disabled={sending || deleting || message.deleted} onClick={() => toggleMessage(message.id)} aria-label="选择消息" aria-pressed={selectedMessageIds.includes(message.id)}>{selectedMessageIds.includes(message.id) ? '✓' : ''}</button>}</div></div>)}</div>
              {sending && <div className="mt-3 flex items-center gap-2 text-xs text-text-muted"><MessageCircle size={14} className="animate-pulse" />角色正在输入…</div>}
              <div ref={endRef} />
            </div>
            {selectingMessages && <div className="flex shrink-0 items-center justify-between border-t border-border bg-bg-surface px-3 py-2 text-xs"><span>已选择 {selectedMessageIds.length} 条</span><button type="button" disabled={selectedMessageIds.length === 0 || deleting || sending} onClick={() => void deleteSelectedMessages()} className="rounded-lg bg-red-500 px-3 py-1.5 text-white disabled:opacity-40">{deleting ? '删除中…' : '删除'}</button></div>}
            <div className="shrink-0 border-t border-border bg-bg-surface px-3 py-3"><div className="mb-2 flex gap-2 overflow-x-auto">{['你现在最担心什么？', '这一章发生的事改变了你什么？', '你最想对谁说一句话？'].map((suggestion) => <button key={suggestion} type="button" className="shrink-0 rounded-full border border-border px-2.5 py-1 text-[11px] text-text-muted hover:border-accent hover:text-accent" onClick={() => setDraft(suggestion)}>{suggestion}</button>)}</div><div className="flex items-end gap-2 rounded-2xl border border-border bg-bg-base p-2 focus-within:border-accent"><textarea value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); } }} rows={1} placeholder={`发送消息给${activeTitle}…`} className="max-h-24 min-h-9 flex-1 resize-none bg-transparent px-2 py-1.5 text-sm outline-none" /><button type="button" disabled={!draft.trim() || sending || !activeConversationId || activeIsDead && activeConversation?.conversation_type === 'GROUP'} onClick={() => void send()} className="flex h-9 w-9 items-center justify-center rounded-xl bg-accent text-white disabled:opacity-40" aria-label="发送"><Send size={16} /></button></div></div>
          </>}
        </div>
      )}

      {picker && <div className="absolute inset-0 z-10 flex flex-col bg-bg-base"><div className="flex items-center gap-2 border-b border-border bg-bg-surface px-3 py-3"><button type="button" onClick={() => { setPicker(null); setGroupSelection([]); }} aria-label="返回"><ChevronLeft size={18} /></button><div className="flex-1 text-sm font-semibold">{picker === 'friend' ? '添加角色好友' : '发起群聊'}</div>{picker === 'group' && <button type="button" onClick={() => void createGroup()} className="rounded-lg bg-accent px-3 py-1.5 text-xs text-white">完成</button>}</div><div className="min-h-0 flex-1 overflow-y-auto p-3">{error && <div role="alert" className="mb-3 rounded-lg bg-red-500/10 px-3 py-2 text-xs text-red-500">{error}</div>}{filteredPickerCharacters.map((character) => <button key={character.id} type="button" onClick={() => picker === 'friend' ? void addFriend(character) : setGroupSelection((items) => items.includes(character.id) ? items.filter((item) => item !== character.id) : [...items, character.id])} className={`mb-1 flex w-full items-center gap-3 rounded-xl px-2.5 py-3 text-left hover:bg-accent-soft ${groupSelection.includes(character.id) ? 'bg-accent-soft ring-1 ring-accent' : ''}`}><div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-accent-soft text-sm font-semibold text-accent ${isDead(character) ? 'grayscale opacity-60' : ''}`}>{character.name.slice(0, 1)}</div><div className="min-w-0 flex-1"><div className="text-sm font-medium">{character.name}</div><div className="truncate text-xs text-text-muted">{character.description || '暂无角色简介'}</div></div>{picker === 'group' && <span className="text-xs text-accent">{groupSelection.includes(character.id) ? '已选' : '选择'}</span>}</button>)}{filteredPickerCharacters.length === 0 && <div className="py-12 text-center text-sm text-text-muted">暂无可添加角色</div>}</div></div>}
    </section>
  );
}

