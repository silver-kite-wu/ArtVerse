import { Fragment, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  Archive,
  Bot,
  BookOpenText,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Loader2,
  MessageCircleQuestion,
  MessageSquareText,
  Plus,
  Send,
  Sparkles,
  Square,
  TriangleAlert,
  Wrench,
} from 'lucide-react';
import {
  cancelMangaAgentConversationRun,
  createMangaAgentConversation,
  deleteMangaAgentConversation,
  getMangaAgentConversationMessages,
  getOpenMangaAgentConversationRun,
  listChapters,
  listMangaAgentConversations,
  listStories,
  resumeMangaAgentAgUiStream,
  runMangaAgentAgUiStream,
  type AgentUserInputRequest,
  type Chapter,
  type MangaAgentConversation,
  type MangaAgentMessage,
  type MangaAgentRunSnapshot,
  type MangaWorkflowRoute,
  type Story,
} from '../api';

interface Message {
  role: 'user' | 'assistant' | 'system';
  content: string;
  requestId?: string;
}

type ExecutionTone = 'neutral' | 'thinking' | 'tool' | 'waiting' | 'success' | 'warning' | 'error';
type AgUiEventTone = ExecutionTone | 'info';

interface ConversationView extends MangaAgentConversation {
  isActive?: boolean;
}

interface ConversationCacheEntry {
  messages: Message[];
  runSnapshot: MangaAgentRunSnapshot | null;
}

interface ExecutionEventItem {
  id: string;
  type: string;
  title: string;
  detail: string;
  createdAt?: string;
  tone: AgUiEventTone;
  icon: 'bot' | 'sparkles' | 'wrench' | 'question' | 'check' | 'warning' | 'clock' | 'message' | 'archive';
}

const WORKFLOW_ROUTES: Array<{ value: MangaWorkflowRoute; label: string; description: string }> = [
  { value: 'AUTO', label: '自动', description: '先识别用户意图，再进入合适的任务模式' },
  { value: 'DIRECTOR', label: '导演', description: '规划章节、生成或修订分镜' },
  { value: 'REVIEW', label: '质检', description: '检查现有分镜和下一步风险' },
  { value: 'HITL', label: '决策', description: '收束需要用户确认的选择' },
  { value: 'CHAT', label: '聊天', description: '只回答问题，不修改章节内容' },
];

function routeLabel(route: MangaWorkflowRoute | string | undefined): string {
  return WORKFLOW_ROUTES.find((item) => item.value === route)?.label || '自动';
}

function conversationStatusLabel(status?: string): string {
  return status === 'ACTIVE' ? '进行中' : status === 'ARCHIVED' ? '已归档' : '未知';
}

function createRequestId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function formatRequestId(requestId: string): string {
  return requestId.length <= 18 ? requestId : `${requestId.slice(0, 8)}...${requestId.slice(-6)}`;
}

function formatTimestamp(value?: string): string {
  if (!value) return '';
  try {
    return new Date(value).toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return value;
  }
}

function normalizeConversationId(value: unknown): string {
  return typeof value === 'string' && value !== 'undefined' && value.trim() ? value : '';
}

function normalizeConversation(item: any): ConversationView {
  const status = String(item?.status || 'ARCHIVED');
  const conversationId = normalizeConversationId(item?.conversationId ?? item?.conversation_id);
  return {
    ...item,
    conversationId,
    title: String(item?.title || ''),
    status: status === 'ACTIVE' ? 'ACTIVE' : 'ARCHIVED',
    createdAt: item?.createdAt ?? item?.created_at,
    updatedAt: item?.updatedAt ?? item?.updated_at,
    archivedAt: item?.archivedAt ?? item?.archived_at ?? null,
    isActive: status === 'ACTIVE',
  };
}

function normalizeConversationList(items: MangaAgentConversation[]): ConversationView[] {
  return items.map((item) => normalizeConversation(item));
}

function toMessages(items: MangaAgentMessage[]): Message[] {
  return items.flatMap((item) => {
    if (item.role !== 'user' && item.role !== 'assistant' && item.role !== 'system') return [];
    if (!item.content) return [];
    return [{
      role: item.role,
      content: item.content,
      requestId: item.requestId ?? item.request_id,
    }];
  });
}

function renderInlineMarkdown(text: string): ReactNode[] {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  return parts.map((part, index) => {
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={index} className="font-semibold text-sumi">{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith('`') && part.endsWith('`')) {
      return <code key={index} className="rounded bg-paper-surface px-1.5 py-0.5 text-[0.9em] text-vermilion font-medium">{part.slice(1, -1)}</code>;
    }
    return <Fragment key={index}>{part}</Fragment>;
  });
}

function MarkdownMessage({ content }: { content: string }) {
  return <div className="whitespace-pre-wrap text-sm leading-7 text-sumi">{renderInlineMarkdown(content)}</div>;
}

function appendExecutionEvent(events: ExecutionEventItem[], event: ExecutionEventItem): ExecutionEventItem[] {
  const last = events[events.length - 1];
  if (last && last.type === event.type && last.title === event.title && last.detail === event.detail) {
    return events;
  }
  return [...events, event].slice(-30);
}

function asRecord(value: unknown): Record<string, any> {
  return value && typeof value === 'object' ? value as Record<string, any> : {};
}

function inferExecutionEvent(event: Record<string, any>): ExecutionEventItem {
  const type = String(event.type || 'event');
  const createdAt = typeof event.timestamp === 'number'
    ? new Date(event.timestamp).toISOString()
    : typeof event.createdAt === 'string'
      ? event.createdAt
      : undefined;

  if (type === 'RUN_STARTED') {
    const message = String(event.input?.state?.message || event.input?.message || '智能体已启动');
    const route = String(event.input?.state?.route || event.route || '');
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '智能体已启动',
      detail: route ? `正在执行 ${routeLabel(route as MangaWorkflowRoute)} 模式：${message}` : message,
      createdAt,
      tone: 'thinking',
      icon: 'bot',
    };
  }

  if (type === 'STATE_SNAPSHOT') {
    const snapshot = asRecord(event.snapshot);
    const status = String(snapshot.status || event.status || 'RUNNING');
    const message = String(snapshot.message || '智能体正在处理中');
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '状态快照',
      detail: `${status} · ${message}`,
      createdAt,
      tone: status.includes('WAITING') ? 'waiting' : 'neutral',
      icon: status.includes('WAITING') ? 'question' : 'clock',
    };
  }

  if (type === 'TEXT_MESSAGE_START' || type === 'TEXT_MESSAGE_CONTENT' || type === 'TEXT_MESSAGE_END') {
    const delta = String(event.delta || event.content || event.text || '');
    return {
      id: `${type}-${createdAt || Date.now()}-${String(event.messageId || '')}`,
      type,
      title: type === 'TEXT_MESSAGE_START' ? '文本输出开始' : type === 'TEXT_MESSAGE_END' ? '文本输出结束' : '文本流输出',
      detail: delta ? delta : '智能体正在生成回复',
      createdAt,
      tone: 'neutral',
      icon: 'message',
    };
  }

  if (type === 'CUSTOM') {
    const name = String(event.name || '自定义事件');
    const value = asRecord(event.value);
    const data = asRecord(value.data);
    if (name === 'intent_classified' || value.type === 'intent_classified') {
      const selectedRoute = String(data.selectedRoute || value.selectedRoute || value.route || '');
      const rawConfidence = typeof data.confidence === 'number' ? data.confidence : value.confidence;
      const confidence = typeof rawConfidence === 'number' ? Math.round(rawConfidence * 100) : null;
      const reason = String(data.reason || value.reason || '');
      const requiresConfirmation = Boolean(data.requiresConfirmation ?? value.requiresConfirmation);
      return {
        id: `${type}-${name}-${createdAt || Date.now()}`,
        type,
        title: '用户意图识别完成',
        detail: `识别为 ${routeLabel(selectedRoute)} 模式${confidence == null ? '' : ` · 置信度 ${confidence}%`}${reason ? ` · ${reason}` : ''}`,
        createdAt,
        tone: requiresConfirmation ? 'waiting' : 'thinking',
        icon: requiresConfirmation ? 'question' : 'sparkles',
      };
    }
    const tool = String(value.tool || value.toolName || '');
    const label = String(value.label || value.title || name);
    const detailParts: string[] = [];
    if (tool) detailParts.push(`工具：${tool}`);
    if (value.status) detailParts.push(`状态：${value.status}`);
    if (data.status && !value.status) detailParts.push(`状态：${data.status}`);
    if (data.node) detailParts.push(`节点：${data.node}`);
    if (data.route) detailParts.push(`模式：${routeLabel(String(data.route))}`);
    if (Array.isArray(data.warnings) && data.warnings.length) detailParts.push(`警告：${data.warnings.join('；')}`);
    return {
      id: `${type}-${name}-${createdAt || Date.now()}`,
      type,
      title: label,
      detail: detailParts.length > 0 ? detailParts.join(' · ') : label,
      createdAt,
      tone: tool || name.includes('tool') ? 'tool' : 'neutral',
      icon: tool || name.includes('tool') ? 'wrench' : 'sparkles',
    };
  }

  if (type === 'RUN_FINISHED') {
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '运行完成',
      detail: '回复已保存到对话',
      createdAt,
      tone: 'success',
      icon: 'check',
    };
  }

  if (type === 'RUN_ERROR') {
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '运行失败',
      detail: String(event.message || event.error || '智能体运行出现错误'),
      createdAt,
      tone: 'error',
      icon: 'warning',
    };
  }

  if (type === 'RUN_INTERRUPTED') {
    return {
      id: `${type}-${createdAt || Date.now()}`,
      type,
      title: '运行中断',
      detail: String(event.message || '本次运行已被中断'),
      createdAt,
      tone: 'warning',
      icon: 'warning',
    };
  }

  return {
    id: `${type}-${createdAt || Date.now()}`,
    type,
    title: type,
    detail: '已收到事件',
    createdAt,
    tone: 'info',
    icon: 'clock',
  };
}

function executionBadgeClass(tone: AgUiEventTone): string {
  return {
    info: 'border-paper-border bg-paper-surface text-sumi-dim',
    neutral: 'border-paper-border bg-paper-surface text-sumi-dim',
    thinking: 'border-kinpaku/30 bg-kinpaku-light/50 text-kinpaku',
    tool: 'border-aizuri/30 bg-aizuri-light/50 text-aizuri',
    waiting: 'border-vermilion/30 bg-vermilion-light/50 text-vermilion',
    success: 'border-success/30 bg-success/10 text-success',
    warning: 'border-warning/30 bg-warning/10 text-warning',
    error: 'border-vermilion/30 bg-vermilion-light/30 text-vermilion',
  }[tone];
}

function executionIcon(tone: AgUiEventTone, icon: ExecutionEventItem['icon']) {
  const className = {
    info: 'text-sumi-dim',
    neutral: 'text-sumi-dim',
    thinking: 'text-kinpaku',
    tool: 'text-aizuri',
    waiting: 'text-vermilion',
    success: 'text-success',
    warning: 'text-warning',
    error: 'text-vermilion',
  }[tone];
  const size = 15;
  switch (icon) {
    case 'bot': return <Bot size={size} className={className} />;
    case 'sparkles': return <Sparkles size={size} className={className} />;
    case 'wrench': return <Wrench size={size} className={className} />;
    case 'question': return <MessageCircleQuestion size={size} className={className} />;
    case 'check': return <CheckCircle2 size={size} className={className} />;
    case 'warning': return <TriangleAlert size={size} className={className} />;
    case 'message': return <MessageSquareText size={size} className={className} />;
    case 'archive': return <Archive size={size} className={className} />;
    case 'clock':
    default:
      return <Clock3 size={size} className={className} />;
  }
}

export default function MangaAgentPage() {
  const [stories, setStories] = useState<Story[]>([]);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [storyId, setStoryId] = useState('');
  const [chapterId, setChapterId] = useState('');
  const [conversations, setConversations] = useState<ConversationView[]>([]);
  const [conversationId, setConversationId] = useState('');
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [bootLoading, setBootLoading] = useState(true);
  const [chapterLoading, setChapterLoading] = useState(false);
  const [conversationLoading, setConversationLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [workflowRoute, setWorkflowRoute] = useState<MangaWorkflowRoute>('AUTO');
  const [userInputRequest, setUserInputRequest] = useState<AgentUserInputRequest | null>(null);
  const [customAnswer, setCustomAnswer] = useState('');
  const [draftReply, setDraftReply] = useState('');
  const [runStatus, setRunStatus] = useState('尚未开始运行');
  const [businessStatus, setBusinessStatus] = useState('');
  const [executionEvents, setExecutionEvents] = useState<ExecutionEventItem[]>([]);
  const [activeRequestId, setActiveRequestId] = useState<string | null>(null);
  const [pendingConversationId, setPendingConversationId] = useState<string | null>(null);
  const chapterIdRef = useRef('');
  const conversationIdRef = useRef('');
  const conversationLoadSeqRef = useRef(0);
  const activeRunConversationIdRef = useRef('');
  const activeStreamControllerRef = useRef<AbortController | null>(null);
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const conversationCacheRef = useRef<Record<string, ConversationCacheEntry>>({});

  const activeStory = useMemo(() => stories.find((story) => String(story.id) === storyId) ?? null, [stories, storyId]);
  const activeChapter = useMemo(() => chapters.find((chapter) => String(chapter.id) === chapterId) ?? null, [chapters, chapterId]);
  const latestExecutionEvent = executionEvents.length > 0 ? executionEvents[executionEvents.length - 1] : null;
  const showExecutionPanel = executionEvents.length > 0 || !!userInputRequest || !!draftReply || !!activeRequestId || sending;
  const waitingForHuman = !!userInputRequest;

  useEffect(() => { chapterIdRef.current = chapterId; }, [chapterId]);
  useEffect(() => { conversationIdRef.current = conversationId; }, [conversationId]);
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [messages, executionEvents, draftReply, userInputRequest, sending]);
  useEffect(() => () => { activeStreamControllerRef.current?.abort(); }, []);

  useEffect(() => {
    let active = true;
    const load = async () => {
      setBootLoading(true);
      try {
        const list = await listStories();
        if (!active) return;
        setStories(list);
        if (list.length > 0) setStoryId(String(list[0].id));
      } catch (err: any) {
        if (active) setError(err.message || '加载会话失败');
      } finally {
        if (active) setBootLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    conversationCacheRef.current = {};
    setPendingConversationId(null);
  }, [storyId, chapterId]);

  useEffect(() => {
    let active = true;
    const load = async () => {
      setConversationLoading(false);
      setConversations([]);
      setConversationId('');
      setPendingConversationId(null);
      setMessages([]);
      setExecutionEvents([]);
      setDraftReply('');
      setUserInputRequest(null);
      setCustomAnswer('');
      setRunStatus('尚未开始运行');
      setBusinessStatus('');
      setActiveRequestId(null);
      conversationCacheRef.current = {};
      if (!storyId) return;

      setChapterLoading(true);
      setError('');
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      try {
        const chapterList = await listChapters(Number(storyId));
        if (!active) return;
        setChapters(chapterList);
        setChapterId((prev) => (prev && chapterList.some((item) => String(item.id) === prev) ? prev : (chapterList[0] ? String(chapterList[0].id) : '')));
      } catch (err: any) {
        if (active) setError(err.message || '加载会话失败');
      } finally {
        if (active) setChapterLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, [storyId]);

  useEffect(() => {
    let active = true;
    const load = async () => {
      if (!chapterId) return;
      setConversationLoading(true);
      setError('');
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      try {
        const id = Number(chapterId);
        const conversationList = normalizeConversationList(await listMangaAgentConversations(id));
        if (!active) return;
        setConversations(conversationList);
        let selected = conversationList.find((item) => item.status === 'ACTIVE') ?? conversationList[0] ?? null;
        if (!selected) {
          selected = await createMangaAgentConversation(id);
        }
        if (!active || !selected) return;
        const resolvedConversationId = normalizeConversationId((selected as any)?.conversationId ?? (selected as any)?.conversation_id);
        if (!resolvedConversationId) {
          throw new Error('会话标识缺失，请刷新页面后重试');
        }
        await loadConversation(id, resolvedConversationId);
        const refreshed = normalizeConversationList(await listMangaAgentConversations(id));
        if (!active) return;
        setConversations(refreshed);
      } catch (err: any) {
        if (active) setError(err.message || '加载会话失败');
      } finally {
        if (active) setConversationLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, [chapterId]);

  async function loadConversation(chapterNumericId: number, selectedConversationId: string) {
    const loadSeq = ++conversationLoadSeqRef.current;
    activeStreamControllerRef.current?.abort();
    activeStreamControllerRef.current = null;
    activeRunConversationIdRef.current = '';
    setPendingConversationId(selectedConversationId);
    setError('');
    setRunStatus(`正在切换到：${conversations.find((item) => item.conversationId === selectedConversationId)?.title || '会话'}`);
    const cached = conversationCacheRef.current[selectedConversationId];
    if (cached) {
      if (loadSeq !== conversationLoadSeqRef.current) return;
        setConversationId(selectedConversationId);
      setMessages(cached.messages);
      if (cached.runSnapshot) restoreRunSnapshot(cached.runSnapshot);
      else {
        setExecutionEvents([]);
        setDraftReply('');
        setUserInputRequest(null);
        setBusinessStatus('');
        setActiveRequestId(null);
        setRunStatus('会话已就绪');
      }
      setPendingConversationId(null);
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
      return;
    }

    setConversationId(selectedConversationId);
    setMessages([]);
    setExecutionEvents([]);
    setDraftReply('');
    setUserInputRequest(null);
    setCustomAnswer('');
    setBusinessStatus('');
    setActiveRequestId(null);
    try {
      const [list, openRun] = await Promise.all([
        getMangaAgentConversationMessages(chapterNumericId, selectedConversationId),
        getOpenMangaAgentConversationRun(chapterNumericId, selectedConversationId),
      ]);
      if (loadSeq !== conversationLoadSeqRef.current) return;
      const nextMessages = toMessages(list);
      setMessages(nextMessages);
      const snapshot = openRun || null;
      conversationCacheRef.current[selectedConversationId] = { messages: nextMessages, runSnapshot: snapshot };
      if (snapshot) {
        restoreRunSnapshot(snapshot);
      } else {
        setRunStatus('会话已就绪');
      }
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    } finally {
      if (loadSeq === conversationLoadSeqRef.current) {
        setPendingConversationId(null);
      }
    }
  }

  function restoreRunSnapshot(snapshot: MangaAgentRunSnapshot) {
    setActiveRequestId(snapshot.requestId ?? snapshot.request_id ?? null);
    setUserInputRequest(snapshot.userInputRequest ?? null);
    if (snapshot.route) setWorkflowRoute(snapshot.route);
    setRunStatus(snapshot.status === 'WAITING_USER' ? '等待用户确认' : `业务状态：${snapshot.status}`);
    setBusinessStatus(snapshot.status);
    setDraftReply(snapshot.finalReply || '');
    setExecutionEvents((snapshot.events || []).map((event) => {
      const payload = asRecord(event.data);
      return inferExecutionEvent({
        type: String((event as any).eventName || payload.type || 'event'),
        ...payload,
        createdAt: event.createdAt,
      });
    }));
  }

  function recordAgUiEvent(rawEvent: Record<string, any>) {
    const executionEvent = inferExecutionEvent(rawEvent);
    setExecutionEvents((prev) => appendExecutionEvent(prev, executionEvent));

    if (rawEvent.type === 'STATE_SNAPSHOT') {
      const snapshot = asRecord(rawEvent.snapshot);
      const status = String(snapshot.status || rawEvent.status || '');
      const message = String(snapshot.message || rawEvent.message || '');
      if (status) setBusinessStatus(status);
      if (message) setRunStatus(message);
      else if (status) setRunStatus(`业务状态：${status}`);
      if (snapshot.route) setWorkflowRoute(snapshot.route as MangaWorkflowRoute);
    }

    if (rawEvent.type === 'RUN_STARTED') {
      const message = String(rawEvent.input?.state?.message || rawEvent.input?.message || '智能体已启动');
      setRunStatus(message);
      setBusinessStatus('RUNNING');
      if (rawEvent.route) setWorkflowRoute(rawEvent.route as MangaWorkflowRoute);
    }

    if (rawEvent.type === 'CUSTOM') {
      const value = asRecord(rawEvent.value);
      const data = asRecord(value.data);
      const status = String(value.status || '');
      if (status) setBusinessStatus(status);
      if (value.message) setRunStatus(String(value.message));
      const selectedRoute = data.selectedRoute || value.selectedRoute;
      if (rawEvent.name === 'intent_classified' && selectedRoute) {
        setWorkflowRoute(selectedRoute as MangaWorkflowRoute);
        setRunStatus(`已识别为${routeLabel(String(selectedRoute))}模式`);
      }
    }

    if (rawEvent.type === 'TEXT_MESSAGE_CONTENT' || rawEvent.type === 'TEXT_MESSAGE_START') {
      const delta = String(rawEvent.delta || rawEvent.content || rawEvent.text || '');
      if (delta) setDraftReply((prev) => prev + delta);
    }

    if (rawEvent.type === 'TEXT_MESSAGE_END') {
      const message = String(rawEvent.message || '');
      if (message && !draftReply) setDraftReply(message);
    }

    if (rawEvent.type === 'RUN_FINISHED') {
      const reply = String(rawEvent.result?.reply || '');
      if (reply) setDraftReply(reply);
      setBusinessStatus('SUCCEEDED');
      setRunStatus('运行已完成');
      setUserInputRequest(null);
    }

    if (rawEvent.type === 'RUN_ERROR') {
      const message = String(rawEvent.message || rawEvent.error || '智能体运行失败');
      setRunStatus(message);
      setBusinessStatus('FAILED');
    }
  }

  function handleAgUiEvent(event: { type?: string; [key: string]: any }): void {
    recordAgUiEvent(event);

    if (event.type === 'RUN_FINISHED') {
      void refreshMessagesAfterRun();
    }

    if (event.type === 'RUN_ERROR') {
      setSending(false);
    }

    if (event.type === 'RUN_STARTED' || event.type === 'STATE_SNAPSHOT' || event.type === 'CUSTOM') {
      setSending(true);
    }

    if (event.type === 'STATE_SNAPSHOT' && event.snapshot?.status === 'WAITING_USER') {
      setSending(false);
      setUserInputRequest(event.outcome?.interrupts?.[0]?.metadata || null);
    }

    if (event.type === 'RUN_FINISHED') {
      setSending(false);
    }
  }

  async function refreshMessagesAfterRun() {
    const chapterNumericId = Number(chapterIdRef.current);
    const selectedConversationId = normalizeConversationId(activeRunConversationIdRef.current || conversationIdRef.current);
    if (!chapterNumericId || !selectedConversationId) return;
    try {
      const list = await getMangaAgentConversationMessages(chapterNumericId, selectedConversationId);
      setMessages(toMessages(list));
      setDraftReply('');
    } catch {
      // ignore refresh failure; live state still exists
    }
  }

  function handleStreamEvent(event: any): void {
    if (!event) return;

    if (event.type === 'ag_ui_event') {
      handleAgUiEvent(event.data || {});
      return;
    }

    if (event.type === 'status') {
      setRunStatus(event.data?.message || '智能体正在运行');
      setBusinessStatus('RUNNING');
      return;
    }

    if (event.type === 'run_event') {
      const payload = asRecord(event.data);
      recordAgUiEvent({
        type: String(payload.type || 'CUSTOM'),
        ...payload,
      });
      return;
    }

    if (event.type === 'tool') {
      recordAgUiEvent({
        type: 'CUSTOM',
        name: 'tool_audit',
        value: event.data || {},
      });
      return;
    }

    if (event.type === 'user_input_requested') {
      setUserInputRequest(event.data);
      setRunStatus(event.data.question || '需要你做出选择');
      setBusinessStatus('WAITING_USER');
      setSending(false);
      return;
    }

    if (event.type === 'done') {
      recordAgUiEvent({
        type: 'RUN_FINISHED',
        result: { reply: event.data?.reply || '' },
      });
      return;
    }

    if (event.type === 'error') {
      recordAgUiEvent({
        type: 'RUN_ERROR',
        message: event.data?.detail || event.data?.error || '智能体请求失败',
      });
    }
  }

  async function startRun(message?: string) {
    const chapterNumericId = Number(chapterId);
    const selectedConversationId = normalizeConversationId(conversationId);
    if (!chapterNumericId || !selectedConversationId || sending) return;
    const text = (message ?? input).trim();
    if (!text) return;
    setError('');
    setSending(true);
    setRunStatus('正在启动运行...');
    setBusinessStatus('RUNNING');
    setDraftReply('');
    setUserInputRequest(null);
    setExecutionEvents([]);
    const requestId = createRequestId();
    setActiveRequestId(requestId);
    activeRunConversationIdRef.current = selectedConversationId;
    setMessages((prev) => [...prev, { role: 'user', content: text, requestId }]);
    setInput('');
    activeStreamControllerRef.current?.abort();
    activeStreamControllerRef.current = runMangaAgentAgUiStream(
      chapterNumericId,
      text,
      requestId,
      (event) => handleStreamEvent(event),
      selectedConversationId,
      workflowRoute,
    );
  }

  async function resumeWithAnswer(answer: string) {
    const chapterNumericId = Number(chapterId);
    const selectedConversationId = normalizeConversationId(conversationId);
    const requestId = userInputRequest?.requestId ?? userInputRequest?.request_id ?? activeRequestId;
    if (!chapterNumericId || !selectedConversationId || !requestId || sending) return;
    const text = answer.trim();
    if (!text) return;
    setError('');
    setSending(true);
    setRunStatus('正在提交回答...');
    setBusinessStatus('RUNNING');
    setCustomAnswer('');
    setUserInputRequest(null);
    activeRunConversationIdRef.current = selectedConversationId;
    activeStreamControllerRef.current?.abort();
    activeStreamControllerRef.current = resumeMangaAgentAgUiStream(
      chapterNumericId,
      requestId,
      text,
      (event) => handleStreamEvent(event),
      selectedConversationId,
    );
    setMessages((prev) => [...prev, { role: 'system', content: `已提交回答：${text}`, requestId }]);
  }

  async function cancelActiveRun() {
    const chapterNumericId = Number(chapterId);
    const selectedConversationId = normalizeConversationId(conversationId);
    const requestId = activeRequestId;
    if (!chapterNumericId || !selectedConversationId || !requestId) return;
    setError('');
    setRunStatus('正在停止运行...');
    try {
      const snapshot = await cancelMangaAgentConversationRun(chapterNumericId, selectedConversationId, requestId);
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      activeRunConversationIdRef.current = '';
      restoreRunSnapshot(snapshot);
      setSending(false);
      setRunStatus('本次运行已停止');
    } catch (err: any) {
      setError(err.message || '停止运行失败');
    }
  }

  async function loadSelectedConversation(nextConversationId: string) {
    const chapterNumericId = Number(chapterId);
    if (!chapterNumericId || !nextConversationId) return;
    try {
      setConversationLoading(true);
      await loadConversation(chapterNumericId, nextConversationId);
    } catch (err: any) {
      setError(err.message || '切换会话失败');
    } finally {
      setConversationLoading(false);
    }
  }

  async function startNewConversation() {
    const chapterNumericId = Number(chapterId);
    if (!chapterNumericId) return;
    setError('');
    setConversationLoading(true);
    try {
      const conversation = normalizeConversation(await createMangaAgentConversation(chapterNumericId));
      const resolvedConversationId = normalizeConversationId(conversation.conversationId);
      if (!resolvedConversationId) throw new Error('会话标识缺失，请刷新页面后重试');
      const refreshed = normalizeConversationList(await listMangaAgentConversations(chapterNumericId));
      setConversations(refreshed);
      setPendingConversationId(resolvedConversationId);
      await loadConversation(chapterNumericId, resolvedConversationId);
      setRunStatus('会话已就绪');
    } catch (err: any) {
      setError(err.message || '开启新会话失败');
    } finally {
      setConversationLoading(false);
    }
  }

  async function deleteConversation(conversationToDelete: ConversationView) {
    const chapterNumericId = Number(chapterId);
    const targetConversationId = normalizeConversationId(conversationToDelete.conversationId);
    if (!chapterNumericId || !targetConversationId) return;
    if (!window.confirm(`确定删除会话「${conversationToDelete.title || '未命名会话'}」吗？删除后无法恢复。`)) return;
    setError('');
    setConversationLoading(true);
    try {
      await deleteMangaAgentConversation(chapterNumericId, targetConversationId);
      delete conversationCacheRef.current[targetConversationId];
      const nextConversations = normalizeConversationList(await listMangaAgentConversations(chapterNumericId));
      setConversations(nextConversations);

      if (conversationIdRef.current === targetConversationId) {
        const nextSelected = nextConversations.find((item) => item.status === 'ACTIVE') ?? nextConversations[0] ?? null;
        if (nextSelected) {
          await loadConversation(chapterNumericId, nextSelected.conversationId);
        } else {
          const created = normalizeConversation(await createMangaAgentConversation(chapterNumericId));
          const createdId = normalizeConversationId(created.conversationId);
          if (createdId) {
            const afterCreate = normalizeConversationList(await listMangaAgentConversations(chapterNumericId));
            setConversations(afterCreate);
            await loadConversation(chapterNumericId, createdId);
          }
        }
      }
    } catch (err: any) {
      setError(err.message || '删除会话失败');
    } finally {
      setConversationLoading(false);
    }
  }

  const showWelcome = !bootLoading && stories.length === 0;

  if (showWelcome) {
    return (
      <div className="flex min-h-0 flex-1 flex-col items-center justify-center bg-paper-base px-6 py-16 text-center">
        <div className="animate-stamp mb-8 inline-flex h-20 w-20 items-center justify-center rounded-2xl border-2 border-vermilion/20 bg-vermilion-light/20">
          <Sparkles size={36} className="text-vermilion" />
        </div>
        <h1 className="font-display text-4xl font-bold tracking-tight text-sumi">
          ArtVerse
        </h1>
        <p className="mt-2 text-lg text-sumi-dim">AI 漫画创作工坊</p>
        <div className="brush-divider my-6 w-48" />
        <p className="max-w-md text-sm leading-relaxed text-sumi-dim">
          将你的故事，变成漫画分镜。从创建故事开始，AI 将协助你完成角色设定、对话创作、分镜生成和漫画渲染。
        </p>
        <div className="mt-10 grid w-full max-w-xl grid-cols-3 gap-4">
          {[
            { icon: <BookOpenText size={22} />, label: '创建故事', desc: '设定世界观与角色' },
            { icon: <MessageSquareText size={22} />, label: 'AI 创作', desc: '对话式推进剧情' },
            { icon: <Sparkles size={22} />, label: '生成漫画', desc: '分镜转漫画图片' },
          ].map((step, i) => (
            <div key={i} className="panel-frame flex flex-col items-center gap-2 p-5">
              <div className="text-vermilion">{step.icon}</div>
              <div className="text-sm font-semibold text-sumi">{step.label}</div>
              <div className="text-xs text-sumi-dim">{step.desc}</div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-paper-base">
      {/* Header */}
      <header className="border-b border-paper-border bg-paper-surface/80 px-5 py-3 backdrop-blur-sm">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg border border-vermilion/20 bg-vermilion-light/30">
            <Sparkles size={16} className="text-vermilion" />
          </div>
          <div className="min-w-0">
            <h1 className="font-display text-base font-semibold text-sumi">创作工坊</h1>
            <p className="text-xs text-sumi-dim">{activeStory?.title || '选择故事开始'}{activeChapter ? ` · 第${activeChapter.chapter_number} 章` : ''}</p>
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 gap-4 p-4">
        {/* Left sidebar — story/chapter/conversation config */}
        <aside className="flex w-[300px] min-w-0 shrink-0 flex-col gap-3 rounded-xl border border-paper-border bg-paper-surface/70 p-4">
          <div>
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-sumi-faint">故事</p>
            <select
              value={storyId}
              onChange={(e) => setStoryId(e.target.value)}
              className="w-full rounded-md border border-paper-border bg-paper-base px-3 py-2.5 text-sm text-sumi outline-none transition focus:border-vermilion"
            >
              {stories.length === 0 ? <option value="">暂无故事</option> : null}
              {stories.map((story) => (
                <option key={story.id} value={story.id}>{story.title}</option>
              ))}
            </select>
          </div>

          <div>
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-sumi-faint">章节</p>
            <div className="relative">
              <select
                value={chapterId}
                onChange={(e) => setChapterId(e.target.value)}
                disabled={chapterLoading || chapters.length === 0}
                className="w-full rounded-md border border-paper-border bg-paper-base px-3 py-2.5 text-sm text-sumi outline-none transition focus:border-vermilion disabled:opacity-40"
              >
                {chapters.length === 0 ? <option value="">暂无章节</option> : null}
                {chapters.map((chapter) => (
                  <option key={chapter.id} value={chapter.id}>第{chapter.chapter_number} 章</option>
                ))}
              </select>
              {chapterLoading && <Loader2 size={14} className="absolute right-3 top-1/2 -translate-y-1/2 animate-spin text-sumi-faint" />}
            </div>
          </div>

          <div className="rounded-lg border border-kinpaku-light bg-kinpaku-light/40 p-3">
            <p className="text-[11px] font-semibold uppercase tracking-wider text-kinpaku/80">当前工作区</p>
            <div className="mt-2 space-y-1.5">
              <div className="text-xs text-sumi-dim">故事：<span className="text-sumi font-medium">{activeStory?.title || '未选择'}</span></div>
              <div className="text-xs text-sumi-dim">章节：<span className="text-sumi font-medium">{activeChapter ? `第${activeChapter.chapter_number} 章` : '未选择'}</span></div>
            </div>
            <button
              onClick={() => void startNewConversation()}
              disabled={!chapterId || conversationLoading}
              className="mt-3 inline-flex w-full items-center justify-center gap-1.5 rounded-md border border-kinpaku/20 bg-paper-base px-3 py-2 text-xs font-medium text-sumi transition hover:border-kinpaku/40 hover:bg-kinpaku-light/30 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Plus size={14} />
              新建会话
            </button>
          </div>

          <div className="flex-1 min-h-0">
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-sumi-faint">会话列表</p>
            <div className="max-h-full space-y-1.5 overflow-y-auto pr-1">
              {conversations.length === 0 ? (
                <div className="rounded-md border border-paper-border bg-paper-base/50 px-3 py-4 text-center text-xs text-sumi-faint">
                  暂无会话
                </div>
              ) : conversations.map((conversation) => {
                const selected = conversation.conversationId === conversationId;
                const pending = conversation.conversationId === pendingConversationId;
                return (
                  <div
                    key={conversation.conversationId}
                    className={`flex items-start gap-2 rounded-md border px-3 py-2.5 transition ${selected ? 'border-vermilion/40 bg-vermilion-light/20' : 'border-paper-border bg-paper-base hover:border-sumi-faint/40'} ${pending ? 'ring-1 ring-vermilion/30' : ''}`}
                  >
                    <button
                      type="button"
                      onClick={() => void loadSelectedConversation(conversation.conversationId)}
                      className="flex min-w-0 flex-1 items-start gap-2.5 text-left"
                    >
                      <div className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md ${selected ? 'bg-vermilion text-white' : 'bg-paper-surface text-sumi-dim'}`}>
                        {pending ? <Loader2 size={13} className="animate-spin" /> : <MessageSquareText size={13} />}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1.5">
                          <div className="truncate text-xs font-medium text-sumi">{conversation.title || '新会话'}</div>
                          {conversation.isActive && <span className="shrink-0 rounded-full border border-success/20 bg-success/10 px-1.5 py-0.5 text-[10px] text-success">进行中</span>}
                          {pending && <span className="shrink-0 rounded-full border border-kinpaku/20 bg-kinpaku-light/50 px-1.5 py-0.5 text-[10px] text-kinpaku">切换中</span>}
                        </div>
                        <div className="mt-0.5 text-[10px] text-sumi-faint">
                          {conversationStatusLabel(conversation.status)}
                          {conversation.updatedAt && <span> · {formatTimestamp(conversation.updatedAt)}</span>}
                        </div>
                      </div>
                      <ChevronRight size={12} className="mt-1 shrink-0 text-sumi-faint" />
                    </button>
                    <button
                      type="button"
                      onClick={() => void deleteConversation(conversation)}
                      className="mt-0.5 rounded p-0.5 text-sumi-faint transition hover:bg-vermilion-light/30 hover:text-vermilion"
                      title="删除会话"
                    >
                      <Archive size={12} />
                    </button>
                  </div>
                );
              })}
            </div>
          </div>

          <div>
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-sumi-faint">工作模式</p>
            <div className="grid grid-cols-2 gap-1.5">
              {WORKFLOW_ROUTES.map((route) => {
                const selected = workflowRoute === route.value;
                return (
                  <button
                    key={route.value}
                    type="button"
                    onClick={() => setWorkflowRoute(route.value)}
                    disabled={sending || waitingForHuman}
                    title={route.description}
                    className={`rounded-md border px-2.5 py-2 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${selected ? 'border-vermilion/50 bg-vermilion text-white' : 'border-paper-border bg-paper-base text-sumi-dim hover:border-sumi-faint/40 hover:text-sumi'}`}
                  >
                    {route.label}
                  </button>
                );
              })}
            </div>
          </div>
        </aside>

        {/* Main chat area */}
        <main className="flex min-h-0 min-w-0 flex-1 flex-col rounded-xl border border-paper-border bg-paper-raised shadow-sm">
          <div className="flex items-center gap-2.5 border-b border-paper-border px-4 py-3">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-paper-surface">
              <Bot size={16} className="text-sumi-dim" />
            </div>
            <div className="min-w-0">
              <div className="text-sm font-medium text-sumi">AI 对话</div>
              <div className="text-[11px] text-sumi-faint">模式：{routeLabel(workflowRoute)}</div>
            </div>
          </div>

          {error && <div className="mx-4 mt-3 rounded-md border border-vermilion/20 bg-vermilion-light/20 px-3 py-2 text-xs text-vermilion">{error}</div>}

          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
            {bootLoading || conversationLoading ? (
              <div className="flex h-full items-center justify-center">
                <Loader2 size={24} className="animate-spin text-vermilion" />
              </div>
            ) : messages.length === 0 && !showExecutionPanel ? (
              <div className="flex h-full flex-col items-center justify-center px-6 text-center">
                <div className="mb-5 flex h-20 w-20 items-center justify-center rounded-full border border-paper-border bg-paper-surface">
                  <BookOpenText size={30} className="text-vermilion/60" />
                </div>
                <h2 className="font-display text-2xl font-semibold text-sumi">开始创作</h2>
                <p className="mt-2 max-w-md text-sm leading-relaxed text-sumi-dim">
                  选择左侧的故事和章节，输入创作指令，AI 将协助你推进剧情、生成分镜和漫画。
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {messages.map((msg, idx) => (
                  <div key={`${msg.requestId || 'msg'}-${idx}`} className={msg.role === 'user' ? 'flex justify-end' : 'flex justify-start'}>
                    <div className={'max-w-[85%] rounded-xl px-4 py-2.5 text-sm leading-relaxed ' + (msg.role === 'user' ? 'bg-vermilion text-white' : msg.role === 'system' ? 'border border-paper-border bg-paper-surface text-sumi-dim' : 'border border-paper-border bg-paper-raised text-sumi shadow-sm')}>
                      {msg.role === 'assistant' || msg.role === 'system' ? <MarkdownMessage content={msg.content} /> : msg.content}
                    </div>
                  </div>
                ))}

                {showExecutionPanel && (
                  <div className="flex justify-start">
                    <div className="max-w-[90%] min-w-0 rounded-xl border border-paper-border bg-paper-surface px-4 py-3 text-sm">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-[11px] font-medium ${executionBadgeClass(latestExecutionEvent?.tone || (waitingForHuman ? 'waiting' : sending ? 'thinking' : 'neutral'))}`}>
                          {executionIcon(latestExecutionEvent?.tone || (waitingForHuman ? 'waiting' : sending ? 'thinking' : 'neutral'), latestExecutionEvent?.icon || 'clock')}
                          {waitingForHuman ? '等待确认' : runStatus}
                        </span>
                        <span className="rounded-full border border-paper-border bg-paper-base px-2.5 py-0.5 text-[11px] text-sumi-dim">模式 {routeLabel(workflowRoute)}</span>
                        {businessStatus && <span className="rounded-full border border-paper-border bg-paper-base px-2.5 py-0.5 text-[11px] text-sumi-dim">状态 {businessStatus}</span>}
                        {activeRequestId && <span className="text-[10px] text-sumi-faint font-mono">{formatRequestId(activeRequestId)}</span>}
                        {activeRequestId && (sending || waitingForHuman) && (
                          <button onClick={() => void cancelActiveRun()} className="inline-flex items-center gap-1 rounded-full border border-vermilion/30 bg-vermilion-light/20 px-2.5 py-0.5 text-[11px] text-vermilion transition hover:bg-vermilion-light/40">
                            <Square size={10} />
                            停止
                          </button>
                        )}
                      </div>

                      {executionEvents.length > 0 && (
                        <details className="mt-2">
                          <summary className="cursor-pointer text-xs text-sumi-faint hover:text-sumi-dim transition-colors">查看运行日志 ({executionEvents.length} 条事件)</summary>
                          <div className="mt-2 grid gap-1.5">
                            {executionEvents.slice(-10).map((event) => (
                              <div key={event.id} className="flex items-start gap-2 rounded-md border border-paper-border bg-paper-base/50 px-2.5 py-2 text-xs">
                                <div className="mt-0.5 shrink-0">{executionIcon(event.tone, event.icon)}</div>
                                <div className="min-w-0 flex-1">
                                  <div className="flex flex-wrap items-center gap-1.5">
                                    <span className="font-medium text-sumi">{event.title}</span>
                                    <span className="text-[10px] uppercase text-sumi-faint">{event.type}</span>
                                  </div>
                                  <div className="mt-0.5 text-sumi-dim">{event.detail}</div>
                                </div>
                                {event.createdAt && <div className="shrink-0 text-[10px] text-sumi-faint">{formatTimestamp(event.createdAt)}</div>}
                              </div>
                            ))}
                          </div>
                        </details>
                      )}
                    </div>
                  </div>
                )}

                {draftReply && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-xl border border-paper-border bg-paper-raised px-4 py-2.5 text-sm leading-relaxed shadow-sm">
                      <MarkdownMessage content={draftReply} />
                    </div>
                  </div>
                )}

                {userInputRequest && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-xl border border-kinpaku/30 bg-kinpaku-light/40 px-4 py-3 text-sm">
                      <div className="text-[11px] font-semibold uppercase tracking-wider text-kinpaku/80">需要确认</div>
                      <div className="mt-1.5 text-sm font-medium text-sumi">{userInputRequest.question}</div>
                      {userInputRequest.reason && <div className="mt-1 text-xs text-sumi-dim">{userInputRequest.reason}</div>}
                      <div className="mt-3 space-y-1.5">
                        {userInputRequest.options.map((option) => (
                          <button key={option.id} onClick={() => void resumeWithAnswer(option.label)} className="w-full rounded-md border border-paper-border bg-paper-base px-3 py-2.5 text-left text-xs transition hover:border-vermilion/30 hover:bg-vermilion-light/10">
                            <div className="flex items-center gap-2 font-medium text-sumi">
                              <span>{option.label}</span>
                              {option.recommended && <span className="rounded-full bg-kinpaku-light px-1.5 py-0.5 text-[10px] text-kinpaku">推荐</span>}
                            </div>
                            {option.description && <div className="mt-0.5 text-sumi-dim">{option.description}</div>}
                          </button>
                        ))}
                      </div>
                      {userInputRequest.allowFreeText && (
                        <div className="mt-2.5 flex gap-2">
                          <input
                            value={customAnswer}
                            onChange={(e) => setCustomAnswer(e.target.value)}
                            placeholder="输入你的回答"
                            className="min-w-0 flex-1 rounded-md border border-paper-border bg-paper-base px-3 py-2 text-xs text-sumi outline-none transition focus:border-vermilion"
                          />
                          <button onClick={() => void resumeWithAnswer(customAnswer)} disabled={!customAnswer.trim()} className="rounded-md bg-vermilion px-3 py-2 text-xs font-medium text-white disabled:cursor-not-allowed disabled:opacity-40 hover:bg-vermilion-hover transition-colors">
                            继续
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                )}

                <div ref={messagesEndRef} />
              </div>
            )}
          </div>

          <div className="border-t border-paper-border p-3">
            <div className="flex gap-2.5">
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    void startRun();
                  }
                }}
                rows={2}
                placeholder={chapterId ? '输入创作指令，例如：检查这一章能否直接转成分镜？' : '请先选择故事和章节'}
                className="min-h-[52px] flex-1 resize-none rounded-lg border border-paper-border bg-paper-surface px-3.5 py-2.5 text-sm text-sumi outline-none transition placeholder:text-sumi-faint focus:border-vermilion"
              />
              <button
                onClick={() => void startRun()}
                disabled={sending || conversationLoading || !chapterId || !input.trim()}
                className="inline-flex h-auto min-w-[100px] items-center justify-center gap-1.5 rounded-lg bg-vermilion px-4 py-2.5 text-sm font-medium text-white transition hover:bg-vermilion-hover disabled:cursor-not-allowed disabled:opacity-40"
              >
                {sending ? <Loader2 size={15} className="animate-spin" /> : <Send size={15} />}
                发送
              </button>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}
