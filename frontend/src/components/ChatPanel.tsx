import { useEffect, useRef, useState } from 'react';
import { useReducer } from 'react';
import { Clock3, History, LoaderCircle, MessageSquarePlus, Send, Square, Trash2 } from 'lucide-react';
import { createPortal } from 'react-dom';
import {
  API_KEY_CHANGE_EVENT,
  cancelStoryChatConversationRun,
  createAiConversation,
  deleteAiConversation,
  errorMessage,
  getOpenStoryChatConversationRun,
  getPrimaryProviderModel,
  getProviderModelOptions,
  getStoryChatRunArtifacts,
  listAiConversations,
  listStoryChatMessages,
  renameAiConversation,
  replayStoryChatRunEvents,
  decideNovelDraft,
  runStoryChatAgUiStream,
  type AiConversationSummary,
  type ArtVerseAgUiEvent,
  type Chapter,
  type ChatMessage,
  type MangaAgentRunEvent,
} from '../api';
import MarkdownRenderer from './MarkdownRenderer';
import ModelSwitcher from './ModelSwitcher';
import { randomUUID } from '../utils/browserSecurity';
import InlineConversationTitle from './InlineConversationTitle';
import NovelDraftReview, { type NovelReviewDraft } from './NovelDraftReview';
import { initialStoryChatTask, isStoryChatBusy, parseStoryChatPhase, storyChatPhaseLabel, storyChatTaskReducer } from './storyChatState';

type DisplayMessage = ChatMessage & { local_id?: string };

interface Props {
  chapter: Chapter | null;
  onMessageSent?: () => void | Promise<void>;
  onChapterRefresh?: (chapterId: number) => void | Promise<void>;
  onBeforeSend?: () => Promise<boolean>;
  onBeforeApply?: (baseVersion: number) => string | undefined;
  onApplyingChange?: (busy: boolean) => void;
  active?: boolean;
  reviewTarget?: HTMLElement | null;
  onReviewOpenChange?: (open: boolean) => void;
  onOpenReview?: () => void;
}

type PendingDraftState = NovelReviewDraft;

export default function ChatPanel(props: Props) {
  return <ChapterChatPanel key={props.chapter?.id ?? 'empty'} {...props} />;
}

function ChapterChatPanel({ chapter, onMessageSent, onChapterRefresh, onBeforeSend, onBeforeApply, onApplyingChange, active = true, reviewTarget, onReviewOpenChange, onOpenReview }: Props) {
  const [messages, setMessages] = useState<DisplayMessage[]>([]);
  const [input, setInput] = useState('');
  const [task, dispatchTask] = useReducer(storyChatTaskReducer, initialStoryChatTask);
  const busy = isStoryChatBusy(task.phase);
  const preparing = task.phase === 'SAVING';
  const streaming = busy && !preparing;
  const [streamContent, setStreamContent] = useState('');
  const [taskNotice, setTaskNotice] = useState('');
  const [selectedLlmModel, setSelectedLlmModel] = useState(() => getPrimaryProviderModel('llm'));
  const [conversation, setConversation] = useState<AiConversationSummary | null>(null);
  const [conversations, setConversations] = useState<AiConversationSummary[]>([]);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [conversationLoading, setConversationLoading] = useState(() => !!chapter);
  const [conversationError, setConversationError] = useState('');
  const [pendingDraft, setPendingDraft] = useState<PendingDraftState | null>(null);
  const initialConversationsRef = useRef<Promise<AiConversationSummary[]> | null>(null);
  const conversationMutationRef = useRef(false);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const streamingChapterIdRef = useRef<number | null>(null);
  const userScrolledUp = useRef(false);
  const decisionRef = useRef<'confirm' | 'discard' | null>(null);
  const sendingRef = useRef(false);
  const mountedRef = useRef(false);
  const requestRef = useRef<string | null>(null);
  const conversationRef = useRef<string | null>(null);
  const lifecycleRef = useRef(0);
  const attemptRef = useRef(0);
  conversationRef.current = conversation?.conversationId ?? null;

  const chapterId = chapter?.id;
  const hasPendingDraft = pendingDraft !== null;
  const hasMessages = messages.length > 0;
  const titleConversationId = conversation?.conversationId;
  const titleSource = conversation?.titleSource;
  const titleState = conversation?.titleState;

  useEffect(() => {
    if (!chapterId || !titleConversationId || titleSource === 'USER'
      || titleState === 'FINALIZED' || (titleState !== 'GENERATING' && !hasMessages && !busy)) return;
    const selectedId = titleConversationId;
    let current = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempts = 0;
    const refreshTitle = async () => {
      let finished = false;
      try {
        const items = await listAiConversations('STORY_CHAT', chapterId);
        if (!current) return;
        const latest = items.find(item => item.conversationId === selectedId);
        if (!latest) return;
        finished = latest.titleState === 'FINALIZED';
        // A response started before a manual rename must not restore an older title.
        setConversation(previous => previous?.conversationId === selectedId && previous.titleSource !== 'USER'
          ? latest : previous);
        setConversations(previous => previous.map(item => item.conversationId === selectedId && item.titleSource !== 'USER'
          ? latest : item));
      } catch {
        // Naming refresh is optional; retain the current title and retry briefly.
      }
      if (current && !finished && ++attempts < 90) timer = setTimeout(() => void refreshTitle(), 2000);
    };
    void refreshTitle();
    return () => { current = false; clearTimeout(timer); };
  }, [chapterId, titleConversationId, titleSource, titleState, hasMessages, busy]);

  useEffect(() => {
    onReviewOpenChange?.(hasPendingDraft);
    return () => onReviewOpenChange?.(false);
  }, [hasPendingDraft, onReviewOpenChange]);

  const autoResize = (el: HTMLTextAreaElement) => { el.style.height = 'auto'; el.style.height = `${el.scrollHeight}px`; };

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      lifecycleRef.current += 1;
      requestRef.current = null;
      abortRef.current?.abort();
      abortRef.current = null;
      streamingChapterIdRef.current = null;
    };
  }, [chapterId]);

  useEffect(() => {
    let current = true;
    if (!chapterId) return () => { current = false; };
    // Reuse initialization when StrictMode replays effects, including first-conversation creation.
    initialConversationsRef.current ??= listAiConversations('STORY_CHAT', chapterId)
      .then(async (items) => items.length ? items : [await createAiConversation('STORY_CHAT', undefined, chapterId)]);
    void initialConversationsRef.current
      .then(async (items) => {
        if (!current) return;
        const selected = items[0];
        const history = await listStoryChatMessages(chapterId, selected.conversationId);
        if (!current) return;
        setConversations(items);
        setConversation(selected);
        setMessages(history);
      })
      .catch((error) => {
        if (!current) return;
        setConversation(null);
        setConversations([]);
        setMessages([]);
        setConversationError(errorMessage(error) || '会话加载失败，请稍后重试。');
        setConversationLoading(false);
      });
    return () => { current = false; };
  }, [chapterId]);

  useEffect(() => {
    if (!chapter || !conversation) return;
    let current = true;
    const generation = lifecycleRef.current;
    void getOpenStoryChatConversationRun(chapter.id, conversation.conversationId)
      .then(async (run) => {
        if (!current || generation !== lifecycleRef.current || requestRef.current || !run) return;
        const requestId = run.requestId ?? run.request_id;
        if (!requestId) return;
        if (run.status === 'WAITING_USER') {
          await loadDraftCard(requestId, conversation.conversationId);
          return;
        }
        if (run.status === 'RUNNING') {
          requestRef.current = requestId;
          streamingChapterIdRef.current = chapter.id;
          dispatchTask({ type: 'start', requestId, phase: parseStoryChatPhase(run.currentPhase) });
          dispatchTask({ type: 'accept', requestId });
          abortRef.current = replayStoryChatRunEvents(chapter.id, conversation.conversationId, requestId, 0,
            event => handleRunStreamEvent(event, requestId, conversation.conversationId, {
              append() {}, finalText() { return ''; },
            }));
        }
      })
      .catch(error => { if (current) setConversationError(errorMessage(error) || '任务状态加载失败，请重试。'); })
      .finally(() => { if (current) setConversationLoading(false); });
    return () => { current = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chapter?.id, conversation?.conversationId]);

  useEffect(() => {
    if (active && !userScrolledUp.current) messagesEndRef.current?.scrollIntoView({ behavior: streaming ? 'instant' : 'smooth', block: 'nearest' });
  }, [active, messages, streamContent, streaming, pendingDraft, preparing, taskNotice]);
  useEffect(() => { userScrolledUp.current = false; }, [messages.length]);
  useEffect(() => {
    const element = scrollContainerRef.current;
    if (!element) return;
    const onScroll = () => { userScrolledUp.current = element.scrollHeight - element.scrollTop - element.clientHeight >= 80; };
    element.addEventListener('scroll', onScroll, { passive: true });
    return () => element.removeEventListener('scroll', onScroll);
  }, []);
  useEffect(() => {
    const syncModels = () => {
      const options = getProviderModelOptions('llm');
      setSelectedLlmModel((previous) => previous && options.includes(previous) ? previous : (options[0] || ''));
    };
    syncModels();
    window.addEventListener(API_KEY_CHANGE_EVENT, syncModels);
    return () => window.removeEventListener(API_KEY_CHANGE_EVENT, syncModels);
  }, []);

  const showTaskNotice = (content: string) => {
    setTaskNotice(content);
  };

  async function loadDraftCard(requestId: string, conversationId: string) {
    if (!chapter) return;
    const artifacts = await getStoryChatRunArtifacts(chapter.id, conversationId, requestId);
    if (!mountedRef.current || conversationRef.current !== conversationId) return;
    const draft = [...artifacts].reverse().find((item) =>
      item.type === 'NOVEL_CONTENT_DRAFT' && (item.status === 'VALIDATED' || item.status === 'DRAFT'));
    if (!draft) return;
    const payload = draft.payload || {};
    const content = String(payload.content || '');
    setPendingDraft({
      requestId,
      artifactId: draft.artifactId,
      content,
      contentHash: String(payload.content_hash || draft.checksum || ''),
      baseVersion: Number(payload.base_version ?? chapter.version ?? 0),
      originalContent: typeof payload.base_content === 'string' ? payload.base_content : chapter.novel_content ?? '',
      busy: false,
      error: '',
    });
  }

  const refreshConversationList = async () => {
    if (!chapter) return;
    const items = await listAiConversations('STORY_CHAT', chapter.id);
    if (!mountedRef.current) return;
    setConversations(previous => items.map(item => {
      const manual = previous.find(existing => existing.conversationId === item.conversationId && existing.titleSource === 'USER');
      return manual ? { ...item, title: manual.title, titleSource: manual.titleSource, titleState: manual.titleState } : item;
    }));
    setConversation(current => {
      const latest = items.find(item => item.conversationId === current?.conversationId);
      if (!latest || !current) return current;
      return current.titleSource === 'USER'
        ? { ...latest, title: current.title, titleSource: current.titleSource, titleState: current.titleState } : latest;
    });
  };

  const handleNewConversation = async () => {
    if (!chapter || busy || conversationLoading || pendingDraft || conversationMutationRef.current) return;
    if (conversation && messages.length === 0) {
      setHistoryOpen(false);
      textareaRef.current?.focus();
      return;
    }
    conversationMutationRef.current = true;
    setConversationLoading(true);
    setConversationError('');
    try {
      const created = await createAiConversation('STORY_CHAT', undefined, chapter.id);
      if (!mountedRef.current) return;
      setConversation(created);
      if (created.conversationId === conversationRef.current) setConversationLoading(false);
      setMessages([]);
      setTaskNotice('');
      dispatchTask({ type: 'reset' });
      setConversations((current) => [created, ...current.filter((item) => item.conversationId !== created.conversationId)]);
      setHistoryOpen(false);
      setInput('');
      textareaRef.current?.focus();
    } catch (error) {
      setConversationError(errorMessage(error) || '新建会话失败，请重试。');
      if (mountedRef.current) setConversationLoading(false);
    } finally {
      conversationMutationRef.current = false;
    }
  };

  const handleDeleteConversation = async (selected: AiConversationSummary) => {
    if (!chapter || busy || conversationLoading || pendingDraft || conversationMutationRef.current) return;
    if (!window.confirm(`删除“${selected.title || '新会话'}”及其聊天记录？此操作无法撤销。`)) return;
    conversationMutationRef.current = true;
    let loadingNextConversation = false;
    setConversationLoading(true);
    setConversationError('');
    try {
      await deleteAiConversation(selected.conversationId);
      if (!mountedRef.current) return;
      const remaining = conversations.filter((item) => item.conversationId !== selected.conversationId);
      setConversations(remaining);
      if (selected.conversationId === conversationRef.current) {
        setConversation(null);
        setMessages([]);
        setInput('');
        setStreamContent('');
        setTaskNotice('');
        dispatchTask({ type: 'reset' });
        const next = remaining[0] ?? await createAiConversation('STORY_CHAT', undefined, chapter.id);
        if (!mountedRef.current) return;
        if (!remaining.length) setConversations([next]);
        const history = await listStoryChatMessages(chapter.id, next.conversationId);
        if (!mountedRef.current) return;
        setMessages(history);
        loadingNextConversation = true;
        setConversation(next);
      }
    } catch (error) {
      if (mountedRef.current) setConversationError(errorMessage(error) || '删除会话失败，请重试。');
    } finally {
      conversationMutationRef.current = false;
      if (mountedRef.current && !loadingNextConversation) setConversationLoading(false);
    }
  };

  const handleSelectConversation = async (selected: AiConversationSummary) => {
    if (!chapter || busy || conversationLoading || pendingDraft || selected.conversationId === conversation?.conversationId) {
      setHistoryOpen(false);
      return;
    }
    setConversationLoading(true);
    setConversationError('');
    try {
      const history = await listStoryChatMessages(chapter.id, selected.conversationId);
      if (!mountedRef.current) return;
      setConversation(selected);
      setMessages(history);
      setTaskNotice('');
      dispatchTask({ type: 'reset' });
      setPendingDraft(null);
      setHistoryOpen(false);
    } catch (error) {
      setConversationError(errorMessage(error) || '历史会话加载失败，请重试。');
      if (mountedRef.current) setConversationLoading(false);
    }
  };

  const finishStream = async (reply: string) => {
    const requestId = requestRef.current;
    if (!requestId) return;
    const finishedConversation = conversationRef.current;
    const generation = lifecycleRef.current;
    const attempt = attemptRef.current;
    dispatchTask({ type: 'phase', requestId, phase: 'COMPLETED' });
    requestRef.current = null;
    abortRef.current = null;
    streamingChapterIdRef.current = null;
    setStreamContent('');
    const decision = decisionRef.current;
    decisionRef.current = null;
    if (decision) setPendingDraft(null);
    if (reply.trim()) {
      setMessages((previous) => [...previous, {
        local_id: `assistant-${Date.now()}`,
        id: Date.now(),
        role: 'assistant',
        content: reply.trim(),
        completion_status: 'complete',
        created_at: new Date().toISOString(),
      }]);
    }
    try {
      if (decision === 'confirm' && chapter) await onChapterRefresh?.(chapter.id);
      else await onMessageSent?.();
      if (chapter && finishedConversation) {
        const history = await listStoryChatMessages(chapter.id, finishedConversation);
        if (mountedRef.current && generation === lifecycleRef.current
            && conversationRef.current === finishedConversation && attempt === attemptRef.current) setMessages(history);
      }
      await refreshConversationList().catch(() => undefined);
    } catch (error) {
      if (mountedRef.current && conversationRef.current === finishedConversation && attempt === attemptRef.current) {
        showTaskNotice(`任务已完成，但章节刷新失败：${errorMessage(error)}`);
      }
    } finally {
      if (decision === 'confirm') onApplyingChange?.(false);
    }
  };

  const failStream = (detail: string) => {
    const requestId = requestRef.current;
    if (!requestId) return;
    dispatchTask({ type: 'phase', requestId, phase: 'FAILED' });
    requestRef.current = null;
    if (decisionRef.current) setPendingDraft(current => current ? { ...current, busy: false, error: detail } : current);
    showTaskNotice(detail);
    decisionRef.current = null;
    onApplyingChange?.(false);
    setStreamContent('');
    abortRef.current = null;
    streamingChapterIdRef.current = null;
  };

  const handleStoryChatEvent = (
    event: ArtVerseAgUiEvent,
    requestId: string,
    conversationId: string,
    stream: { append: (delta: string) => void; finalText: () => string },
  ) => {
    if (!event || typeof event.type !== 'string') return;
    if (requestRef.current !== requestId || conversationRef.current !== conversationId) return;
    if (event.runId && event.runId !== requestId) return;
    if (event.type === 'RUN_STARTED') {
      dispatchTask({ type: 'accept', requestId });
      return;
    }
    if (event.type === 'STATE_SNAPSHOT') {
      if (event.snapshot?.status === 'RUNNING') {
        dispatchTask({ type: 'accept', requestId });
        dispatchTask({ type: 'phase', requestId, phase: parseStoryChatPhase(event.snapshot.phase) });
      } else if (event.snapshot?.status === 'WAITING_USER') {
        requestRef.current = null;
        abortRef.current = null;
        onApplyingChange?.(false);
        setConversationLoading(true);
        dispatchTask({ type: 'phase', requestId, phase: 'WAITING_USER' });
        void loadDraftCard(requestId, conversationId)
          .catch(error => showTaskNotice(errorMessage(error)))
          .finally(() => { if (mountedRef.current) setConversationLoading(false); });
      } else if (event.snapshot?.status && event.snapshot.status !== 'RUNNING') {
        if (event.snapshot.status === 'SUCCEEDED') void finishStream('');
        else failStream(event.snapshot.status === 'CANCELLED' ? '任务已停止。' : '任务已结束，请查看最新记录。');
      }
      return;
    }
    if (event.type === 'CUSTOM' && (event as unknown as { name: string }).name === 'artverse.replayed_run_event') {
      const value = (event as unknown as { value: { eventName: string; data: Record<string, unknown> } }).value;
      if (value.eventName === 'phase') {
        handleStoryChatEvent(value.data as ArtVerseAgUiEvent, requestId, conversationId, stream);
      } else if (value.eventName === 'done') {
        void finishStream(String(value.data.reply || ''));
      } else if (value.eventName === 'error') {
        failStream(String(value.data.detail || '任务执行失败，请重试。'));
      } else if (value.eventName === 'user_input_requested') {
        handleStoryChatEvent({ type: 'STATE_SNAPSHOT', snapshot: { status: 'WAITING_USER' } } as ArtVerseAgUiEvent,
          requestId, conversationId, stream);
      }
      return;
    }
    if (event.type === 'TEXT_MESSAGE_CONTENT') {
      dispatchTask({ type: 'phase', requestId, phase: 'GENERATING' });
      stream.append(String((event as unknown as { delta?: string }).delta || ''));
      return;
    }
    if (event.type === 'RUN_FINISHED') {
      const interrupts = event.outcome?.interrupts || [];
      if (event.outcome?.type === 'interrupt' || interrupts.length > 0) {
        abortRef.current = null;
        streamingChapterIdRef.current = null;
        setStreamContent('');
        dispatchTask({ type: 'phase', requestId, phase: 'WAITING_USER' });
        requestRef.current = null;
        setConversationLoading(true);
        decisionRef.current = null;
        onApplyingChange?.(false);
        void (async () => {
          if (chapter) await onChapterRefresh?.(chapter.id);
          await loadDraftCard(requestId, conversationId);
        })().catch(error => showTaskNotice(errorMessage(error) || '修改草稿加载失败，请重试。'))
          .finally(() => { if (mountedRef.current) setConversationLoading(false); });
        return;
      }
      void finishStream(event.result?.reply || stream.finalText());
      return;
    }
    if (event.type === 'RUN_ERROR') {
      failStream(String((event as unknown as { message?: string }).message || '小说对话执行失败。'));
    }
  };

  const handleRunStreamEvent = (
    event: MangaAgentRunEvent,
    requestId: string,
    conversationId: string,
    stream: { append: (delta: string) => void; finalText: () => string },
  ) => {
    if (!mountedRef.current || requestRef.current !== requestId || conversationRef.current !== conversationId) return;
    if (event.type === 'error') {
      const detail = event.data?.detail || event.data?.error || '小说对话执行失败。';
      failStream(detail);
      return;
    }
    handleStoryChatEvent(event.data as ArtVerseAgUiEvent, requestId, conversationId, stream);
  };

  const handleSend = async () => {
    if (!input.trim() || !chapter || !conversation || busy || conversationLoading || sendingRef.current || requestRef.current || pendingDraft) return;
    const text = input.trim();
    const requestId = randomUUID();
    const generation = lifecycleRef.current;
    requestRef.current = requestId;
    sendingRef.current = true;
    setTaskNotice('');
    setHistoryOpen(false);
    dispatchTask({ type: 'start', requestId, phase: 'SAVING' });
    try {
      if (onBeforeSend && !await onBeforeSend()) { failStream('正文保存未完成，请先处理原文编辑区中的提示。'); return; }
      if (!mountedRef.current || generation !== lifecycleRef.current || requestRef.current !== requestId) return;
      startSend(text, requestId);
    } catch (error) {
      if (generation === lifecycleRef.current) failStream(errorMessage(error) || '保存正文失败，请重试。');
    } finally { sendingRef.current = false; }
  };

  const startSend = (text: string, requestId: string) => {
    if (!chapter) return;
    const attempt = ++attemptRef.current;
    const userMessage: DisplayMessage = {
      local_id: `local-user-${Date.now()}`,
      id: Date.now(),
      role: 'user',
      content: text,
      completion_status: 'complete',
      created_at: new Date().toISOString(),
    };
    setMessages((previous) => [...previous, userMessage]);
    setInput('');
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
    dispatchTask({ type: 'phase', requestId, phase: 'PREPARING_CONTEXT' });
    setStreamContent('');
    setPendingDraft(null);
    let accumulated = '';
    streamingChapterIdRef.current = chapter.id;
    void (async () => {
      const activeConversation = conversation
        ?? (await listAiConversations('STORY_CHAT', chapter.id).then((items) => items[0] ?? null))
        ?? await createAiConversation('STORY_CHAT', undefined, chapter.id);
      setConversation(activeConversation);
      if (!mountedRef.current || requestRef.current !== requestId) return;
      abortRef.current = runStoryChatAgUiStream(
        chapter.id,
        activeConversation.conversationId,
        text,
        requestId,
        (event: MangaAgentRunEvent) => attempt === attemptRef.current && handleRunStreamEvent(event, requestId, activeConversation.conversationId, {
          append(delta) { accumulated += delta; setStreamContent(accumulated); },
          finalText() { return accumulated; },
        }),
        selectedLlmModel,
      );
    })().catch((error) => {
      if (mountedRef.current && requestRef.current === requestId) failStream(errorMessage(error) || '小说对话启动失败。');
    });
  };

  const handleAbort = () => {
    if (decisionRef.current) return;
    const activeRequestId = requestRef.current;
    if (!activeRequestId || !task.accepted) return;
    attemptRef.current += 1;
    requestRef.current = null;
    dispatchTask({ type: 'phase', requestId: activeRequestId, phase: 'CANCELLED' });
    setTaskNotice('已停止接收回复，正在停止任务…');
    const chapterId = streamingChapterIdRef.current;
    abortRef.current?.abort();
    if (chapter && conversation && activeRequestId) {
      const selected = conversation.conversationId;
      void cancelStoryChatConversationRun(chapter.id, selected, activeRequestId)
        .then(() => { if (mountedRef.current && conversationRef.current === selected && !requestRef.current) setTaskNotice('任务已停止。'); })
        .catch(() => { if (mountedRef.current && conversationRef.current === selected && !requestRef.current) setTaskNotice('已停止接收回复，服务端停止未确认，请刷新查看任务状态。'); });
    }
    abortRef.current = null;
    streamingChapterIdRef.current = null;
    if (streamContent) {
      setMessages((previous) => [...previous, {
        local_id: `local-abort-${Date.now()}`,
        id: Date.now(),
        role: 'assistant',
        content: `${streamContent}\n\n[已中止]`,
        completion_status: 'partial',
        created_at: new Date().toISOString(),
      }]);
    }
    setStreamContent('');
    if (decisionRef.current) setPendingDraft(current => current ? { ...current, busy: false, error: '操作已停止，请核对最新正文后再决定。' } : current);
    decisionRef.current = null;
    onApplyingChange?.(false);
    window.setTimeout(() => chapterId !== null ? void onChapterRefresh?.(chapterId) : void onMessageSent?.(), 500);
  };

  const handleDraftDecision = async (decision: 'confirm' | 'discard', content: string) => {
    if (!chapter || !conversation || !pendingDraft || pendingDraft.busy || busy || requestRef.current) return;
    if (decision === 'confirm') {
      const error = onBeforeApply?.(pendingDraft.baseVersion);
      if (error) { setPendingDraft(current => current ? { ...current, error } : current); return; }
      onApplyingChange?.(true);
    }
    setPendingDraft((current) => current ? { ...current, busy: true, error: '' } : current);
    setTaskNotice('');
    try {
      const result = await decideNovelDraft(chapter.id, conversation.conversationId, pendingDraft.requestId, pendingDraft.artifactId, {
        decision, content, base_version: pendingDraft.baseVersion, expected_content_hash: pendingDraft.contentHash,
      });
      if (!mountedRef.current) return;
      setPendingDraft(null);
      showTaskNotice(decision === 'discard' ? '已放弃本次修改，正文未改变。' : result.changed ? '已保存为新版本，可在版本历史中恢复。' : '正文已是确认的版本。');
      if (decision === 'confirm') await onChapterRefresh?.(chapter.id);
      await refreshConversationList();
    } catch (error) {
      if (mountedRef.current) setPendingDraft(current => current ? { ...current, busy: false, error: errorMessage(error) || '操作未完成，草稿已保留，可重试。' } : current);
    } finally { onApplyingChange?.(false); }
  };

  return <div className="story-chat-panel relative flex h-full min-h-0 min-w-0 flex-col bg-bg-base">
    <div className="story-chat-toolbar relative z-20 shrink-0 border-b border-border px-3 py-3">
      <div className="flex min-w-0 items-center gap-2">
        <div className="min-w-0 flex-1">
          {conversation ? <InlineConversationTitle title={conversation.title} onSave={async (title) => {
            const renamed = await renameAiConversation(conversation.conversationId, title);
            setConversation(renamed);
            setConversations((items) => items.map((item) => item.conversationId === renamed.conversationId ? renamed : item));
          }} /> : <span className="text-sm font-semibold text-text-primary">新会话</span>}
        </div>
        <button type="button" onClick={() => void handleNewConversation()} disabled={!chapter || streaming || preparing || !!pendingDraft || conversationLoading} className="story-chat-icon-button" title="新建对话" aria-label="新建对话"><MessageSquarePlus size={16} /></button>
        <button type="button" onClick={() => setHistoryOpen((value) => !value)} disabled={!chapter || busy || conversationLoading || !!pendingDraft} className="story-chat-icon-button relative" title="查看历史对话" aria-label="查看历史对话" aria-expanded={historyOpen}><History size={16} />{conversations.length > 1 && <span className="story-chat-history-count">{Math.min(conversations.length, 99)}</span>}</button>
      </div>
      <div className="mt-2 flex items-center gap-2 text-[11px] text-text-muted">
        <span className="rounded-full bg-bg-surface px-2 py-1">第 {chapter?.chapter_number ?? '–'} 章</span>
        <span className="truncate">AI 修改需确认后写入正文</span>
      </div>
      {historyOpen && <div className="story-chat-history-panel" role="dialog" aria-label="历史对话">
        <div className="flex items-center justify-between border-b border-border/70 px-3 py-2.5">
          <div><p className="text-xs font-semibold text-text-primary">历史对话</p><p className="mt-0.5 text-[10px] text-text-muted">当前章节 · {conversations.length} 个会话</p></div>
          <button type="button" onClick={() => void handleNewConversation()} disabled={busy || conversationLoading || !!pendingDraft} className="inline-flex items-center gap-1 rounded-lg bg-accent px-2.5 py-1.5 text-[11px] font-medium text-white hover:bg-accent-hover disabled:opacity-40"><MessageSquarePlus size={13} />新建</button>
        </div>
        <div className="max-h-72 overflow-y-auto p-2">
          {conversations.map((item) => <div key={item.conversationId} className={'story-chat-history-item ' + (item.conversationId === conversation?.conversationId ? 'is-active' : '')}>
            <button type="button" disabled={busy || conversationLoading || !!pendingDraft} onClick={() => void handleSelectConversation(item)} className="flex min-w-0 flex-1 items-center gap-2 text-left">
            <span className="min-w-0 flex-1"><span className="block truncate text-xs font-medium">{item.title || '新会话'}</span><span className="mt-1 flex items-center gap-1 text-[10px] text-text-muted"><Clock3 size={10} />{formatConversationTime(item.lastActivityAt)}</span></span>
            {item.conversationId === conversation?.conversationId && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent" />}
            </button>
            <button type="button" disabled={busy || conversationLoading || !!pendingDraft} onClick={() => void handleDeleteConversation(item)} aria-label={`删除会话：${item.title || '新会话'}`} title="删除会话" className="shrink-0 rounded-lg p-2 text-text-muted hover:bg-red-500/10 hover:text-red-500 disabled:opacity-40"><Trash2 size={14} /></button>
          </div>)}
        </div>
      </div>}
    </div>
    <div ref={scrollContainerRef} className="min-h-0 min-w-0 flex-1 space-y-4 overflow-y-auto break-words px-3 py-4">
      {conversationError && <div className="rounded-xl border border-red-500/20 bg-red-500/5 px-3 py-2 text-xs text-red-500" role="alert">{conversationError}</div>}
      {conversationLoading && messages.length === 0 && <div className="flex h-full items-center justify-center"><div className="h-5 w-5 animate-spin rounded-full border-2 border-border border-t-accent" /></div>}
      {!conversationLoading && messages.length === 0 && !busy && !pendingDraft && !taskNotice && <div className="story-chat-empty"><div className="story-chat-empty-icon"><MessageSquarePlus size={20} /></div><p className="mt-3 text-sm font-medium text-text-primary">开始一段新对话</p><p className="mt-1 max-w-52 text-center text-xs leading-5 text-text-muted">讨论剧情、润色文字或让 AI 准备修改草稿。</p></div>}
      {messages.map((message) => <div key={message.local_id ?? message.id} className={`flex ${message.role === 'user' ? 'justify-end' : 'w-full justify-start'}`}><div className={`rounded-xl px-4 py-3 text-sm leading-relaxed ${message.role === 'user' ? 'max-w-[80%] rounded-br-sm bg-accent-muted/40 text-text-primary' : 'w-full rounded-bl-sm border border-border bg-bg-raised text-text-primary shadow-sm'}`}><MarkdownRenderer content={message.content} /></div></div>)}
      {pendingDraft && <><section className="novel-review-summary"><h3>本章修改已准备好</h3><p>基于版本 {pendingDraft.baseVersion}，在正文区逐处审阅后保存。</p><button type="button" className="workspace-secondary-button" onClick={() => { onOpenReview?.(); reviewTarget?.focus(); }}>审阅修改</button></section>{reviewTarget ? createPortal(<NovelDraftReview key={pendingDraft.artifactId} draft={pendingDraft} stale={chapter?.version !== pendingDraft.baseVersion} onDecision={handleDraftDecision} />, reviewTarget) : <NovelDraftReview key={pendingDraft.artifactId} draft={pendingDraft} stale={chapter?.version !== pendingDraft.baseVersion} onDecision={handleDraftDecision} />}</>}
      {taskNotice && <div role={task.phase === 'FAILED' ? 'alert' : 'status'} className="rounded-xl border border-border bg-bg-surface px-3 py-3 text-xs leading-5 text-text-secondary">{taskNotice}</div>}
      {busy && <div className="flex justify-start"><div className="relative w-full rounded-xl rounded-bl-sm border border-border bg-bg-raised px-4 py-3 text-sm leading-relaxed text-text-primary shadow-sm"><div className="gen-beam" />{streamContent ? <><MarkdownRenderer content={streamContent} /><span className="ml-0.5 inline-block h-4 w-1.5 rounded-sm bg-accent" /></> : <div role="status" aria-live="polite"><div className="flex items-center gap-2"><LoaderCircle size={15} className="animate-spin text-accent motion-reduce:animate-none" aria-hidden="true" /><span>思考中…</span></div><p className="mt-1 pl-6 text-xs text-text-muted">{storyChatPhaseLabel(task.phase)}</p></div>}</div></div>}
      <div ref={messagesEndRef} className="!mt-0" />
    </div>
    <div className="border-t border-border/80 bg-bg-base/90 px-3 py-3 backdrop-blur-md">
      <div className="group relative overflow-visible rounded-2xl border border-border bg-bg-surface/95 transition-all duration-200 focus-within:border-accent/70 focus-within:bg-bg-raised focus-within:shadow-[0_0_0_3px_var(--color-accent-muted)]">
        <textarea ref={textareaRef} value={input} onChange={(event) => { setInput(event.target.value); autoResize(event.target); }} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); handleSend(); } }} aria-label="AI 修改要求" disabled={preparing} placeholder="向 AI 助手提问，或描述需要修改的内容…" rows={2} className="min-h-16 w-full resize-none bg-transparent px-3 pb-1 pt-3 text-sm leading-6 text-text-primary outline-none placeholder:text-text-muted/70" style={{ maxHeight: '10rem', overflow: 'auto' }} />
        <div className="flex items-center justify-between gap-2 px-2 pb-2">
          <ModelSwitcher capability="llm" selectedModel={selectedLlmModel} onSelect={setSelectedLlmModel} disabled={streaming || preparing || !!pendingDraft?.busy} />
          {busy ? <button type="button" onClick={handleAbort} disabled={!task.accepted || !!pendingDraft?.busy} className="story-chat-send-button" title={preparing ? '正在保存正文，请稍候' : !task.accepted ? '正在连接助手，请稍候' : pendingDraft?.busy ? '正在处理修改，请稍候' : '停止生成'} aria-label={preparing ? '正在保存正文' : !task.accepted ? '正在连接助手' : pendingDraft?.busy ? '正在处理修改' : '停止生成'}><Square size={15} /></button> : <button type="button" onClick={handleSend} disabled={!chapter || !conversation || !input.trim() || conversationLoading || !!pendingDraft} className="story-chat-send-button" title="发送消息" aria-label="发送消息"><Send size={16} /></button>}
        </div>
      </div>
      <p className="mt-2 text-center text-[10px] text-text-muted">{pendingDraft ? '请先审阅并处理本次修改' : 'Enter 发送 · Shift + Enter 换行'}</p>
    </div>
  </div>;
}

function formatConversationTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '刚刚';
  const now = new Date();
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
}
