import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Bot,
  BookOpenText,
  CheckCircle2,
  Clock3,
  Loader2,
  MessageCircleQuestion,
  Plus,
  Send,
  Sparkles,
  Square,
  TriangleAlert,
  Wrench,
} from 'lucide-react';
import {
  type AgentRunTimelineEvent,
  type AgentUserInputRequest,
  cancelMangaAgentConversationRun,
  createMangaAgentConversation,
  getMangaAgentRunState,
  getMangaAgentConversationRunState,
  getMangaAgentMessages,
  getMangaAgentConversationMessages,
  getOpenMangaAgentConversationRun,
  listMangaAgentConversations,
  listChapters,
  listStories,
  runMangaAgentAgUiStream,
  resumeMangaAgentAgUiStream,
  type Chapter,
  type MangaAgentMessage,
  type MangaAgentRunEvent,
  type MangaAgentRunSnapshot,
  type Story,
} from '../api';

interface Message {
  role: 'user' | 'assistant' | 'system';
  content: string;
  requestId?: string;
}

type AgentStreamResult = {
  reply: string;
  requestId?: string;
  request_id?: string;
  waiting?: boolean;
};

const STARTER_PROMPTS = [
  '甯垜妫€鏌ヨ繖涓€璇濈殑婕敾杩涘害锛屽苟鍛婅瘔鎴戜笅涓€姝ュ仛浠€涔?,
  '鏍规嵁褰撳墠鍐呭锛屽厛甯垜鐢熸垚杩欎竴璇濈殑鍒嗛暅',
  '甯垜鐪嬬湅鍒嗛暅鏄惁杩橀渶瑕佹鼎鑹诧紝鍐嶇粰鍑轰慨鏀瑰缓璁?,
];

function toMessages(items: MangaAgentMessage[]): Message[] {
  return items
    .flatMap((item) => {
      if (item.role !== 'user' && item.role !== 'assistant' && item.role !== 'system') {
        return [];
      }
      const content = item.role === 'system' ? formatSystemMessage(item.content) : item.content;
      if (!content) {
        return [];
      }
      return [{
        role: item.role,
        content,
        requestId: item.requestId ?? item.request_id,
      }];
    });
}

function formatSystemMessage(content: string): string | null {
  const type = content.match(/type=([^,}]+)/)?.[1]?.trim();
  const message = content.match(/message=(.*?)(, [a-zA-Z_]+=|}$)/)?.[1]?.trim();
  if (type === 'agent_run_degraded_after_tool_success') {
    return null;
  }
  if (type === 'agent_run_failed') {
    return `绯荤粺鎻愮ず锛氭櫤鑳戒綋鏈鍝嶅簲澶辫触銆?{message ? `鍘熷洜锛?{message}` : '璇风◢鍚庨噸璇曘€?}`;
  }
  return `绯荤粺鎻愮ず锛?{content}`;
}

function appendRunEvent(events: AgentRunTimelineEvent[], event: AgentRunTimelineEvent): AgentRunTimelineEvent[] {
  if (event.type === 'text_delta') {
    return events;
  }
  const last = events[events.length - 1];
  if (last && last.type === event.type && last.label === event.label && last.status === event.status) {
    return events;
  }
  return [...events, event].slice(-24);
}

function createRequestId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function requestIdOf(value: { requestId?: string; request_id?: string } | null | undefined) {
  return value?.requestId ?? value?.request_id;
}

type ExecutionTone = 'neutral' | 'thinking' | 'tool' | 'waiting' | 'success' | 'warning' | 'error';

function formatRequestId(requestId: string | null | undefined): string {
  if (!requestId) {
    return '';
  }
  return requestId.length <= 18 ? requestId : `${requestId.slice(0, 8)}鈥?{requestId.slice(-6)}`;
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

function timelineEventSummary(event: AgentRunTimelineEvent): {
  tone: ExecutionTone;
  title: string;
  detail: string;
  icon: 'bot' | 'sparkles' | 'wrench' | 'question' | 'check' | 'warning' | 'clock';
} {
  const data = event.data || {};
  const type = String(data.type || event.type);
  const label = String(data.label || data.phase || data.toolName || event.label || event.type);

  if (event.type === 'status' || type === 'ag_ui_state_snapshot') {
    return {
      tone: 'neutral',
      title: event.label || event.phase || '杩愯鐘舵€?,
      detail: String(event.text || data.message || '鏅鸿兘浣撴鍦ㄦ帹杩涗换鍔?),
      icon: 'clock',
    };
  }
  if (event.type === 'tool'
    || type === 'ag_ui_tool_call_start'
    || type === 'ag_ui_tool_call_end'
    || type === 'ag_ui_tool_call_result') {
    const tool = String(data.tool || event.toolName || 'tool');
    const saved = data.saved === true ? '宸蹭繚瀛? : '';
    const scenesCount = typeof data.scenes_count === 'number' ? ` 路 ${data.scenes_count} 椤礰 : '';
    const suffix = data.error ? ` 路 ${String(data.error)}` : `${saved}${scenesCount}`;
    return {
      tone: data.succeeded === false ? 'error' : 'tool',
      title: tool,
      detail: data.succeeded === false
        ? `宸ュ叿璋冪敤澶辫触${suffix ? `锛?{suffix}` : ''}`
        : `宸ュ叿璋冪敤瀹屾垚${suffix}`,
      icon: data.succeeded === false ? 'warning' : 'wrench',
    };
  }

  if (type === 'text_delta') {
    return {
      tone: 'neutral',
      title: '鍥炲鐢熸垚涓?,
      detail: String(event.text || data.text || '鏅鸿兘浣撴鍦ㄦ嫾鎺ユ渶缁堝洖澶?),
      icon: 'bot',
    };
  }
  if (type === 'run_started' || type === 'ag_ui_run_started') {
    return {
      tone: 'thinking',
      title: label || '鏅鸿兘浣撳凡鍚姩',
      detail: '寮€濮嬪垎鏋愬綋鍓嶇珷鑺備笂涓嬫枃',
      icon: 'bot',
    };
  }
  if (type === 'context_loading') {
    return {
      tone: 'thinking',
      title: label || '鍚屾绔犺妭鐭ヨ瘑',
      detail: '姝ｅ湪鎶婃晠浜嬬煡璇嗗啓鍏ュ伐浣滃尯',
      icon: 'sparkles',
    };
  }
  if (type === 'model_started' || type === 'thinking_started' || type === 'ag_ui_step_started') {
    return {
      tone: 'thinking',
      title: label || '妯″瀷鎺ㄧ悊涓?,
      detail: '姝ｅ湪鎺ㄧ悊涓嬩竴姝ュ姩浣?,
      icon: 'sparkles',
    };
  }
  if (type === 'tool_call_started' || type === 'tool_started' || type === 'tool_call_ready') {
    return {
      tone: 'tool',
      title: label || '宸ュ叿澶勭悊涓?,
      detail: '鏅鸿兘浣撴鍦ㄨ皟鐢ㄥ伐鍏?,
      icon: 'wrench',
    };
  }
  if (type === 'tool_finished') {
    return {
      tone: 'tool',
      title: label || '宸ュ叿鎵ц瀹屾瘯',
      detail: '宸ュ叿璋冪敤宸茬粨鏉燂紝姝ｅ湪鏁寸悊缁撴灉',
      icon: 'wrench',
    };
  }
  if (type === 'user_answered') {
    return {
      tone: 'waiting',
      title: '宸叉敹鍒扮敤鎴烽€夋嫨',
      detail: String(data.answer || '缁х画榛樿鏂规'),
      icon: 'question',
    };
  }
  if (type === 'reply_ready') {
    return {
      tone: 'success',
      title: label || '鏈€缁堝洖澶嶅凡鐢熸垚',
      detail: '鏅鸿兘浣撳凡缁忓紑濮嬭緭鍑虹粨鏋?,
      icon: 'check',
    };
  }
  if (type === 'run_finished' || type === 'ag_ui_run_finished') {
    return {
      tone: 'success',
      title: label || '浠诲姟瀹屾垚',
      detail: '鏈杩愯宸茬粡缁撴潫',
      icon: 'check',
    };
  }
  if (type === 'user_input_requested' || type === 'ag_ui_run_interrupted') {
    const options = Array.isArray(data.options) ? data.options : [];
    return {
      tone: 'waiting',
      title: String(data.question || '闇€瑕佷綘鍋氫釜鍐冲畾'),
      detail: String(data.reason || `鍙€夐」 ${options.length} 涓猔),
      icon: 'question',
    };
  }
  if (event.type === 'error' || type === 'ag_ui_run_error') {
    return {
      tone: 'error',
      title: '杩愯澶辫触',
      detail: String(data.detail || data.error || data.message || '鏅鸿兘浣撹姹傚け璐?),
      icon: 'warning',
    };
  }
  return {
    tone: 'neutral',
    title: label || event.type,
    detail: '鎵ц浜嬩欢',
    icon: 'clock',
  };
}

function executionIcon(tone: ExecutionTone, icon: 'bot' | 'sparkles' | 'wrench' | 'question' | 'check' | 'warning' | 'clock') {
  const className = {
    neutral: 'text-gray-300',
    thinking: 'text-amber-200',
    tool: 'text-cyan-200',
    waiting: 'text-violet-200',
    success: 'text-emerald-200',
    warning: 'text-orange-200',
    error: 'text-red-200',
  }[tone];
  const size = 15;
  switch (icon) {
    case 'bot':
      return <Bot size={size} className={className} />;
    case 'sparkles':
      return <Sparkles size={size} className={className} />;
    case 'wrench':
      return <Wrench size={size} className={className} />;
    case 'question':
      return <MessageCircleQuestion size={size} className={className} />;
    case 'check':
      return <CheckCircle2 size={size} className={className} />;
    case 'warning':
      return <TriangleAlert size={size} className={className} />;
    case 'clock':
    default:
      return <Clock3 size={size} className={className} />;
  }
}

function executionBadgeClass(tone: ExecutionTone): string {
  return {
    neutral: 'border-white/10 bg-white/[0.04] text-gray-300',
    thinking: 'border-amber-300/20 bg-amber-300/[0.08] text-amber-100',
    tool: 'border-cyan-300/20 bg-cyan-300/[0.08] text-cyan-100',
    waiting: 'border-violet-300/20 bg-violet-300/[0.08] text-violet-100',
    success: 'border-emerald-300/20 bg-emerald-300/[0.08] text-emerald-100',
    warning: 'border-orange-300/20 bg-orange-300/[0.08] text-orange-100',
    error: 'border-red-300/20 bg-red-300/[0.08] text-red-100',
  }[tone];
}

function renderInlineMarkdown(text: string): ReactNode[] {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  return parts.map((part, index) => {
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={index} className="font-semibold text-white">{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith('`') && part.endsWith('`')) {
      return <code key={index} className="rounded bg-white/10 px-1 py-0.5 text-[0.9em] text-amber-100">{part.slice(1, -1)}</code>;
    }
    return <Fragment key={index}>{part}</Fragment>;
  });
}

function splitTableRow(line: string) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cell.trim());
}

function isTableSeparator(line: string) {
  return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);
}

function isBlockStart(line: string, nextLine?: string) {
  return /^#{1,4}\s+/.test(line)
    || /^\s*[-*]\s+/.test(line)
    || /^\s*\d+\.\s+/.test(line)
    || /^\s*---+\s*$/.test(line)
    || (line.includes('|') && !!nextLine && isTableSeparator(nextLine));
}

function MarkdownMessage({ content }: { content: string }) {
  const lines = content.split(/\r?\n/);
  const blocks: ReactNode[] = [];

  for (let i = 0; i < lines.length;) {
    const line = lines[i];
    const trimmed = line.trim();

    if (!trimmed) {
      i += 1;
      continue;
    }

    if (/^\s*---+\s*$/.test(line)) {
      blocks.push(<hr key={i} className="my-4 border-white/10" />);
      i += 1;
      continue;
    }

    const heading = line.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      const level = heading[1].length;
      const className = level <= 2
        ? 'mt-1 text-base font-semibold text-white'
        : 'mt-3 text-sm font-semibold text-amber-100';
      blocks.push(<div key={i} className={className}>{renderInlineMarkdown(heading[2])}</div>);
      i += 1;
      continue;
    }

    if (line.includes('|') && lines[i + 1] && isTableSeparator(lines[i + 1])) {
      const headers = splitTableRow(line);
      const rows: string[][] = [];
      i += 2;
      while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
        rows.push(splitTableRow(lines[i]));
        i += 1;
      }
      blocks.push(
        <div key={i} className="my-4 overflow-x-auto rounded-2xl border border-white/10">
          <table className="w-full min-w-[520px] border-collapse text-left text-xs">
            <thead className="bg-white/10 text-amber-100">
              <tr>
                {headers.map((header, idx) => (
                  <th key={idx} className="border-b border-white/10 px-3 py-2 font-semibold">
                    {renderInlineMarkdown(header)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, rowIdx) => (
                <tr key={rowIdx} className="odd:bg-white/[0.025]">
                  {headers.map((_, cellIdx) => (
                    <td key={cellIdx} className="border-t border-white/5 px-3 py-2 align-top text-gray-200">
                      {renderInlineMarkdown(row[cellIdx] || '')}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>,
      );
      continue;
    }

    if (/^\s*[-*]\s+/.test(line) || /^\s*\d+\.\s+/.test(line)) {
      const ordered = /^\s*\d+\.\s+/.test(line);
      const items: string[] = [];
      while (i < lines.length && (ordered ? /^\s*\d+\.\s+/.test(lines[i]) : /^\s*[-*]\s+/.test(lines[i]))) {
        items.push(lines[i].replace(ordered ? /^\s*\d+\.\s+/ : /^\s*[-*]\s+/, '').trim());
        i += 1;
      }
      const ListTag = ordered ? 'ol' : 'ul';
      blocks.push(
        <ListTag key={i} className={(ordered ? 'list-decimal' : 'list-disc') + ' my-3 space-y-1 pl-5 text-sm text-gray-200'}>
          {items.map((item, idx) => <li key={idx}>{renderInlineMarkdown(item)}</li>)}
        </ListTag>,
      );
      continue;
    }

    const paragraph: string[] = [];
    while (i < lines.length && lines[i].trim() && !isBlockStart(lines[i], lines[i + 1])) {
      paragraph.push(lines[i]);
      i += 1;
    }
    blocks.push(
      <p key={i} className="my-2 whitespace-pre-wrap text-sm leading-7 text-gray-200">
        {renderInlineMarkdown(paragraph.join('\n'))}
      </p>,
    );
  }

  return <div className="space-y-1">{blocks}</div>;
}

export default function MangaAgentPage() {
  const [stories, setStories] = useState<Story[]>([]);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [storyId, setStoryId] = useState('');
  const [chapterId, setChapterId] = useState('');
  const [conversationId, setConversationId] = useState('');
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [bootLoading, setBootLoading] = useState(true);
  const [chapterLoading, setChapterLoading] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [error, setError] = useState('');
  const [runStatus, setRunStatus] = useState('姝ｅ湪鎬濊€冨綋鍓嶇珷鑺?..');
  const [runEvents, setRunEvents] = useState<AgentRunTimelineEvent[]>([]);
  const [draftReply, setDraftReply] = useState('');
  const [userInputRequest, setUserInputRequest] = useState<AgentUserInputRequest | null>(null);
  const [customAnswer, setCustomAnswer] = useState('');
  const chapterIdRef = useRef('');
  const conversationIdRef = useRef('');
  const activeRequestIdRef = useRef<string | null>(null);
  const runPollTimerRef = useRef<number | undefined>(undefined);
  const activeStreamControllerRef = useRef<AbortController | null>(null);
  const draftReplyRef = useRef('');
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const latestRunEvent = runEvents.length > 0 ? runEvents[runEvents.length - 1] : null;
  const latestRunSummary = latestRunEvent ? timelineEventSummary(latestRunEvent) : null;
  const showExecutionPanel = loading || runEvents.length > 0 || !!userInputRequest || !!draftReply;
  const visibleRequestId = userInputRequest?.requestId ?? activeRequestIdRef.current;

  useEffect(() => {
    chapterIdRef.current = chapterId;
  }, [chapterId]);

  useEffect(() => {
    conversationIdRef.current = conversationId;
  }, [conversationId]);

  useEffect(() => {
    draftReplyRef.current = draftReply;
  }, [draftReply]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, runEvents, draftReply, loading, userInputRequest]);

  useEffect(() => () => {
    if (runPollTimerRef.current !== undefined) {
      window.clearTimeout(runPollTimerRef.current);
    }
    activeStreamControllerRef.current?.abort();
  }, []);

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
        if (active) setError(err.message || '鍔犺浇鏁呬簨澶辫触');
      } finally {
        if (active) setBootLoading(false);
      }
    };
    load();
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    const load = async () => {
      if (!storyId) {
        setChapters([]);
        setChapterId('');
        return;
      }
      setChapterLoading(true);
      setError('');
      try {
        const list = await listChapters(Number(storyId));
        if (!active) return;
        setChapters(list);
        setChapterId((prev) => {
          if (prev && list.some((chapter) => String(chapter.id) === prev)) return prev;
          return list[0] ? String(list[0].id) : '';
        });
      } catch (err: any) {
        if (active) setError(err.message || '鍔犺浇绔犺妭澶辫触');
      } finally {
        if (active) setChapterLoading(false);
      }
    };
    load();
    return () => {
      active = false;
    };
  }, [storyId]);

  useEffect(() => {
    let active = true;
    const load = async () => {
      setInput('');
      setError('');
      setLoading(false);
      setMessages([]);
      setRunEvents([]);
      setDraftReply('');
      setUserInputRequest(null);
      setCustomAnswer('');
      setConversationId('');
      activeRequestIdRef.current = null;
      if (runPollTimerRef.current !== undefined) {
        window.clearTimeout(runPollTimerRef.current);
        runPollTimerRef.current = undefined;
      }
      if (!chapterId) {
        return;
      }
      setHistoryLoading(true);
      try {
        const id = Number(chapterId);
        const conversations = await listMangaAgentConversations(id);
        const activeConversation = conversations.find((item) => item.status === 'ACTIVE')
          || await createMangaAgentConversation(id);
        if (!active) return;
        setConversationId(activeConversation.conversationId);
        const list = await getMangaAgentConversationMessages(id, activeConversation.conversationId);
        if (!active) return;
        setMessages(toMessages(list));
        const openRun = await getOpenMangaAgentConversationRun(id, activeConversation.conversationId);
        if (!active || !openRun) return;
        restoreRunSnapshot(openRun, chapterId, activeConversation.conversationId);
      } catch (err: any) {
        if (active) setError(err.message || '鍔犺浇瀵硅瘽璁板綍澶辫触');
      } finally {
        if (active) setHistoryLoading(false);
      }
    };
    load();
    return () => {
      active = false;
    };
  }, [chapterId]);

  const clearRunPoll = () => {
    if (runPollTimerRef.current !== undefined) {
      window.clearTimeout(runPollTimerRef.current);
      runPollTimerRef.current = undefined;
    }
  };

  const reloadMessages = async (id: number, requestChapterId: string, requestConversationId: string) => {
    const list = requestConversationId
      ? await getMangaAgentConversationMessages(id, requestConversationId)
      : await getMangaAgentMessages(id);
    if (chapterIdRef.current === requestChapterId && conversationIdRef.current === requestConversationId) {
      setMessages(toMessages(list));
    }
  };

  const applyMangaAgentEvent = (
    event: MangaAgentRunEvent,
    fallbackRequestId: string,
  ): AgentStreamResult | Error | null => {
    if (event.type === 'status') {
      setRunStatus(event.data.message || '鏅鸿兘浣撴鍦ㄥ鐞嗗綋鍓嶇珷鑺?..');
      return null;
    }
    if (event.type === 'run_event') {
      setRunEvents((prev) => appendRunEvent(prev, event.data));
      if (event.data.label) setRunStatus(event.data.label);
      if (event.data.type === 'text_delta' && event.data.text) {
        setDraftReply((prev) => prev + event.data.text);
      }
      return null;
    }
    if (event.type === 'tool') {
      const toolLabel = event.data.tool === 'save_structured_storyboard' || event.data.tool === 'save_storyboard'
        ? '鍒嗛暅淇濆瓨'
        : event.data.tool === 'generate_storyboard'
          ? '鍒嗛暅鐢熸垚'
          : '宸ュ叿璋冪敤';
      if (event.data.succeeded && event.data.saved) {
        setRunStatus(`${toolLabel}宸插畬鎴愶紝姝ｅ湪鏁寸悊鍥炲...`);
      } else if (!event.data.succeeded) {
        setRunStatus(`${toolLabel}灏濊瘯鏈€氳繃锛屾櫤鑳戒綋姝ｅ湪淇...`);
      }
      return null;
    }
    if (event.type === 'ag_ui_event') {
      const agEvent = event.data;
      const rawEvent = agEvent.rawEvent as AgentRunTimelineEvent | undefined;
      if (rawEvent?.type) {
        setRunEvents((prev) => appendRunEvent(prev, rawEvent));
        if (rawEvent.label) setRunStatus(rawEvent.label);
      }
      if (agEvent.type === 'STATE_SNAPSHOT') {
        const message = agEvent.snapshot?.message;
        if (message) setRunStatus(message);
        setRunEvents((prev) => appendRunEvent(prev, {
          type: 'ag_ui_state_snapshot',
          phase: 'status',
          label: message || '杩愯鐘舵€?,
          status: agEvent.snapshot?.status,
          data: { type: 'ag_ui_state_snapshot', message },
          createdAt: new Date().toISOString(),
        }));
        return null;
      }
      if (agEvent.type === 'RUN_STARTED') {
        setRunEvents((prev) => appendRunEvent(prev, {
          type: 'ag_ui_run_started',
          phase: 'started',
          label: '鏅鸿兘浣撳凡鍚姩',
          status: 'running',
          data: { type: 'ag_ui_run_started' },
          createdAt: new Date().toISOString(),
        }));
        return null;
      }
      if (agEvent.type === 'STEP_STARTED') {
        const stepName = String((agEvent as any).stepName || '模型推理中');
        setRunStatus(stepName);
        setRunEvents((prev) => appendRunEvent(prev, {
          type: 'ag_ui_step_started',
          phase: 'thinking',
          label: stepName,
          status: 'running',
          data: { type: 'ag_ui_step_started', label: stepName },
          createdAt: new Date().toISOString(),
        }));
        return null;
      }
      if (agEvent.type === 'CUSTOM' && (agEvent as any).name === 'workflow_step') {
        const value = (agEvent as any).value as { node?: string; status?: string; route?: string; storyTitle?: string; chapterDisplayName?: string; sceneCount?: number; imageCount?: number; warnings?: string[] } | undefined;
        const node = String(value?.node || 'workflow');
        const status = String(value?.status || 'running');
        const title = ({
          ROUTING: '正在路由任务',
          COLLECTING_CONTEXT: '正在收集上下文',
          GENERATING: '正在生成内容',
          EVALUATING: '正在评估结果',
          WAITING_USER: '正在等待用户决策',
          COMPLETED: '流程已完成',
        } as Record<string, string>)[node] || '工作流节点';
        const detailParts: string[] = [];
        if (value?.route) detailParts.push(`路由 ${value.route}`);
        if (value?.storyTitle) detailParts.push(`故事 ${value.storyTitle}`);
        if (value?.chapterDisplayName) detailParts.push(`章节 ${value.chapterDisplayName}`);
        if (typeof value?.sceneCount === 'number') detailParts.push(`分镜 ${value.sceneCount}`);
        if (typeof value?.imageCount === 'number') detailParts.push(`图片 ${value.imageCount}`);
        if (value?.warnings?.length) detailParts.push(`提示 ${value.warnings.join('、')}`);
        setRunStatus(title);
        setRunEvents((prev) => appendRunEvent(prev, {
          type: 'workflow_step',
          phase: 'workflow',
          label: title,
          status,
          data: {
            type: 'workflow_step',
            node,
            status,
            route: value?.route,
            storyTitle: value?.storyTitle,
            chapterDisplayName: value?.chapterDisplayName,
            sceneCount: value?.sceneCount,
            imageCount: value?.imageCount,
            warnings: value?.warnings,
            detail: detailParts.join(' · '),
          },
          createdAt: new Date().toISOString(),
        }));
        return null;
      }
      if (agEvent.type === 'TOOL_CALL_START' || agEvent.type === 'TOOL_CALL_END' || agEvent.type === 'TOOL_CALL_RESULT') {
        const toolName = String((agEvent as any).toolCallName || (agEvent as any).toolName || 'tool');
        setRunStatus(`宸ュ叿澶勭悊涓細${toolName}`);
        setRunEvents((prev) => appendRunEvent(prev, {
          type: agEvent.type.toLowerCase(),
          phase: 'tool',
          label: toolName,
          toolName,
          status: agEvent.type === 'TOOL_CALL_START' ? 'running' : 'finished',
          data: { type: agEvent.type.toLowerCase(), toolName },
          createdAt: new Date().toISOString(),
        }));
        return null;
      }
      if (agEvent.type === 'CUSTOM') {
        const value = (agEvent as any).value as AgentRunTimelineEvent | { payload?: Record<string, unknown> } | undefined;
        if ((agEvent as any).name === 'artverse.tool_audit') {
          const payload = (value as { payload?: Record<string, unknown> } | undefined)?.payload || {};
          const tool = String(payload.tool || 'tool');
          const succeeded = payload.succeeded !== false;
          setRunStatus(succeeded ? `宸ュ叿宸插畬鎴愶細${tool}` : `宸ュ叿璋冪敤澶辫触锛?{tool}`);
          setRunEvents((prev) => appendRunEvent(prev, {
            type: 'tool',
            phase: 'tool',
            label: tool,
            toolName: tool,
            status: succeeded ? 'success' : 'failed',
            data: payload,
            createdAt: new Date().toISOString(),
          }));
          return null;
        }
        if (value && 'type' in value && typeof value.type === 'string') {
          const raw = value as AgentRunTimelineEvent;
          setRunEvents((prev) => appendRunEvent(prev, raw));
          if (raw.label) setRunStatus(raw.label);
        }
        return null;
      }
      if (agEvent.type === 'TEXT_MESSAGE_CHUNK' || agEvent.type === 'TEXT_MESSAGE_CONTENT') {
        const delta = String((agEvent as any).delta || (agEvent as any).content || '');
        if (delta) setDraftReply((prev) => prev + delta);
        return null;
      }
      if (agEvent.type === 'RUN_FINISHED') {
        if (agEvent.outcome?.type === 'interrupt') {
          const interrupt = agEvent.outcome.interrupts?.[0];
          const metadata = interrupt?.metadata;
          const requestId = agEvent.runId || fallbackRequestId;
          if (metadata?.question && metadata?.options) {
            activeRequestIdRef.current = requestId;
            setUserInputRequest({
              requestId,
              question: metadata.question,
              options: metadata.options,
              allowFreeText: metadata.allowFreeText,
              reason: interrupt?.reason,
            });
            setRunStatus('绛夊緟浣犵殑閫夋嫨');
            return { reply: '', requestId, waiting: true };
          }
        }
        const requestId = agEvent.runId || fallbackRequestId;
        activeRequestIdRef.current = null;
        clearRunPoll();
        return { reply: agEvent.result?.reply || draftReplyRef.current || '', requestId };
      }
      if (agEvent.type === 'RUN_ERROR') {
        activeRequestIdRef.current = null;
        clearRunPoll();
        return new Error(String((agEvent as any).message || '鏅鸿兘浣撹姹傚け璐?));
      }
      return null;
    }
    if (event.type === 'user_input_requested') {
      const requestId = requestIdOf(event.data) ?? fallbackRequestId;
      activeRequestIdRef.current = requestId;
      setUserInputRequest({ ...event.data, requestId });
      setRunStatus('绛夊緟浣犵殑閫夋嫨');
      return { reply: '', requestId, waiting: true };
    }
    if (event.type === 'done') {
      const requestId = requestIdOf(event.data) ?? fallbackRequestId;
      activeRequestIdRef.current = null;
      clearRunPoll();
      return {
        reply: event.data.reply || '',
        requestId,
      };
    }
    if (event.type === 'error') {
      activeRequestIdRef.current = null;
      clearRunPoll();
      return new Error(event.data.detail || event.data.error || '鏅鸿兘浣撹姹傚け璐?);
    }
    return null;
  };

  const restoreRunSnapshot = (
    snapshot: MangaAgentRunSnapshot,
    requestChapterId: string,
    requestConversationId: string,
  ) => {
    const requestId = requestIdOf(snapshot) ?? snapshot.requestId;
    activeRequestIdRef.current = requestId;
    setRunEvents([]);
    setDraftReply('');
    setUserInputRequest(null);

    for (const persisted of snapshot.events || []) {
      applyMangaAgentEvent({
        type: persisted.eventName,
        data: persisted.data,
      } as MangaAgentRunEvent, requestId);
    }

    if (snapshot.status === 'WAITING_USER') {
      const request = snapshot.userInputRequest;
      if (request) {
        setUserInputRequest({ ...request, requestId });
      }
      setRunStatus('绛夊緟浣犵殑閫夋嫨');
      setLoading(false);
      clearRunPoll();
      return;
    }

    if (snapshot.status === 'RUNNING') {
      setLoading(true);
      if (!snapshot.events || snapshot.events.length === 0) {
        setRunStatus('鏅鸿兘浣撲粛鍦ㄥ鐞嗗綋鍓嶇珷鑺?..');
      }
      scheduleRunPoll(Number(requestChapterId), requestId, requestChapterId, requestConversationId);
      return;
    }

    activeRequestIdRef.current = null;
    clearRunPoll();
    setLoading(false);
    setUserInputRequest(null);
    setDraftReply('');
    if (snapshot.status === 'FAILED') {
      setError(snapshot.errorMessage || '鏅鸿兘浣撹姹傚け璐?);
    } else if (snapshot.status === 'CANCELLED') {
      setRunStatus('鏈杩愯宸插仠姝?);
    } else if (snapshot.status === 'INTERRUPTED') {
      setRunStatus(snapshot.errorMessage || '鏈杩愯宸蹭腑鏂紝鍙互閲嶆柊鍙戣捣浠诲姟');
    }
    void reloadMessages(Number(requestChapterId), requestChapterId, requestConversationId);
  };

  const scheduleRunPoll = (
    id: number,
    requestId: string,
    requestChapterId: string,
    requestConversationId: string,
  ) => {
    clearRunPoll();
    runPollTimerRef.current = window.setTimeout(async () => {
      if (chapterIdRef.current !== requestChapterId
        || conversationIdRef.current !== requestConversationId
        || activeRequestIdRef.current !== requestId) return;
      try {
        const snapshot = requestConversationId
          ? await getMangaAgentConversationRunState(id, requestConversationId, requestId)
          : await getMangaAgentRunState(id, requestId);
        if (chapterIdRef.current === requestChapterId && conversationIdRef.current === requestConversationId) {
          restoreRunSnapshot(snapshot, requestChapterId, requestConversationId);
        }
      } catch (err: any) {
        if (chapterIdRef.current === requestChapterId && conversationIdRef.current === requestConversationId) {
          setError(err.message || '鍚屾鏅鸿兘浣撶姸鎬佸け璐?);
          setLoading(false);
        }
      }
    }, 3000);
  };

  const send = async (override?: string) => {
    const requestChapterId = chapterId;
    const requestConversationId = conversationId;
    const id = Number(requestChapterId);
    const text = (override ?? input).trim();
    if (!id || !requestConversationId || !text || loading) return;

    const requestId = createRequestId();
    setLoading(true);
    setError('');
    setRunStatus('鏅鸿兘浣撳凡寮€濮嬪鐞嗗綋鍓嶇珷鑺?..');
    setMessages((prev) => [...prev, { role: 'user', content: text, requestId }]);
    setRunEvents([]);
    setDraftReply('');
    setUserInputRequest(null);
    setCustomAnswer('');
    setInput('');
    activeRequestIdRef.current = requestId;

    try {
      const result = await runMangaAgentWithStream(id, requestConversationId, text, requestId, requestChapterId);
      if (chapterIdRef.current === requestChapterId
        && conversationIdRef.current === requestConversationId
        && !result.waiting
        && result.reply.trim()) {
        setMessages((prev) => [
          ...prev,
          { role: 'assistant', content: result.reply, requestId: result.requestId ?? result.request_id ?? requestId },
        ]);
      }
      if (chapterIdRef.current === requestChapterId && conversationIdRef.current === requestConversationId && !result.waiting) {
        await reloadMessages(id, requestChapterId, requestConversationId);
      }
    } catch (err: any) {
      if (chapterIdRef.current !== requestChapterId || conversationIdRef.current !== requestConversationId) return;
      setError(err.message || '璇锋眰澶辫触');
      try {
        const list = await getMangaAgentConversationMessages(id, requestConversationId);
        if (chapterIdRef.current === requestChapterId && conversationIdRef.current === requestConversationId) {
          setMessages(toMessages(list));
        }
      } catch {
        return;
      }
    } finally {
      if (chapterIdRef.current === requestChapterId) {
        setLoading(false);
      }
    }
  };

  const runMangaAgentWithStream = (
    id: number,
    requestConversationId: string,
    text: string,
    requestId: string,
    requestChapterId: string,
  ): Promise<AgentStreamResult> => {
    return consumeMangaAgentStream(
      id,
      requestId,
      requestChapterId,
      requestConversationId,
      (onEvent) => runMangaAgentAgUiStream(id, text, requestId, onEvent, requestConversationId),
    );
  };

  const resumeMangaAgentWithStream = (
    id: number,
    requestConversationId: string,
    requestId: string,
    answer: string,
    requestChapterId: string,
  ): Promise<AgentStreamResult> => {
    return consumeMangaAgentStream(
      id,
      requestId,
      requestChapterId,
      requestConversationId,
      (onEvent) => resumeMangaAgentAgUiStream(id, requestId, answer, onEvent, requestConversationId),
    );
  };

  const consumeMangaAgentStream = (
    id: number,
    requestId: string,
    requestChapterId: string,
    requestConversationId: string,
    startStream: (onEvent: (event: MangaAgentRunEvent) => void) => AbortController,
  ): Promise<AgentStreamResult> => {
    return new Promise((resolve, reject) => {
      let settled = false;
      let controller: AbortController | null = null;
      controller = startStream((event: MangaAgentRunEvent) => {
        if (chapterIdRef.current !== requestChapterId || conversationIdRef.current !== requestConversationId || settled) return;
        const outcome = applyMangaAgentEvent(event, requestId);
        if (!outcome) return;
        settled = true;
        controller?.abort();
        if (activeStreamControllerRef.current === controller) {
          activeStreamControllerRef.current = null;
        }
        if (outcome instanceof Error) {
          reject(outcome);
        } else {
          resolve(outcome);
        }
      });
      activeStreamControllerRef.current = controller;

      window.setTimeout(async () => {
        if (settled) return;
        controller?.abort();
        setRunStatus('杩炴帴绛夊緟杈冧箙锛屾鍦ㄥ悓姝ュ悗鍙拌繍琛岀姸鎬?..');
        try {
          const snapshot = await getMangaAgentConversationRunState(id, requestConversationId, requestId);
          restoreRunSnapshot(snapshot, requestChapterId, requestConversationId);
          if (snapshot.status === 'WAITING_USER') {
            settled = true;
            resolve({ reply: '', requestId, waiting: true });
          } else if (snapshot.status === 'SUCCEEDED' || snapshot.status === 'DEGRADED') {
            settled = true;
            resolve({ reply: snapshot.finalReply || '', requestId });
          } else if (snapshot.status === 'FAILED') {
            settled = true;
            reject(new Error(snapshot.errorMessage || '鏅鸿兘浣撹姹傚け璐?));
          } else if (snapshot.status === 'CANCELLED' || snapshot.status === 'INTERRUPTED') {
            settled = true;
            resolve({ reply: '', requestId });
          }
        } catch (err) {
          settled = true;
          reject(err);
        } finally {
          if (activeStreamControllerRef.current === controller) {
            activeStreamControllerRef.current = null;
          }
        }
      }, 240000);
    });
  };

  const cancelActiveRun = async () => {
    const id = Number(chapterId);
    const requestConversationId = conversationId;
    const requestId = activeRequestIdRef.current;
    if (!id || !requestConversationId || !requestId) return;
    setError('');
    setRunStatus('姝ｅ湪鍋滄鏈杩愯...');
    try {
      const snapshot = await cancelMangaAgentConversationRun(id, requestConversationId, requestId);
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      restoreRunSnapshot(snapshot, chapterId, requestConversationId);
      setRunStatus('鏈杩愯宸插仠姝?);
    } catch (err: any) {
      setError(err.message || '鍋滄鏅鸿兘浣撹繍琛屽け璐?);
    }
  };

  const resumeWithAnswer = async (answer: string) => {
    const currentRequest = userInputRequest;
    const id = Number(chapterId);
    const requestConversationId = conversationId;
    const requestId = currentRequest?.requestId ?? currentRequest?.request_id;
    if (!id || !requestConversationId || !requestId || loading) return;
    setLoading(true);
    setError('');
    setRunStatus('宸叉敹鍒颁綘鐨勯€夋嫨锛屾櫤鑳戒綋姝ｅ湪缁х画...');
    setUserInputRequest(null);
    setCustomAnswer('');
    activeRequestIdRef.current = requestId;
    try {
      const result = await resumeMangaAgentWithStream(id, requestConversationId, requestId, answer, chapterId);
      if (!result.waiting && result.reply.trim()) {
        setMessages((prev) => [
          ...prev,
          { role: 'assistant', content: result.reply, requestId: result.requestId ?? result.request_id ?? requestId },
        ]);
      }
      if (!result.waiting) {
        await reloadMessages(id, chapterId, requestConversationId);
      }
      setDraftReply('');
      setRunEvents([]);
    } catch (err: any) {
      setError(err.message || '缁х画浠诲姟澶辫触');
    } finally {
      setLoading(false);
    }
  };

  const startNewConversation = async () => {
    const id = Number(chapterId);
    if (!id || loading) return;
    setError('');
    setHistoryLoading(true);
    try {
      activeStreamControllerRef.current?.abort();
      activeStreamControllerRef.current = null;
      clearRunPoll();
      const conversation = await createMangaAgentConversation(id);
      setConversationId(conversation.conversationId);
      setMessages([]);
      setRunEvents([]);
      setDraftReply('');
      setUserInputRequest(null);
      setCustomAnswer('');
      setRunStatus('宸插紑鍚柊瀵硅瘽');
      activeRequestIdRef.current = null;
    } catch (err: any) {
      setError(err.message || '寮€鍚柊瀵硅瘽澶辫触');
    } finally {
      setHistoryLoading(false);
    }
  };

  const activeStory = stories.find((story) => String(story.id) === storyId) ?? null;
  const activeChapter = chapters.find((chapter) => String(chapter.id) === chapterId) ?? null;
  const emptyState = messages.length === 0 && !historyLoading;

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-[radial-gradient(circle_at_top,_rgba(139,92,246,0.12),_transparent_28%),linear-gradient(180deg,_#09090b_0%,_#111827_45%,_#09090b_100%)] text-gray-100">
      <header className="border-b border-white/10 bg-black/15 px-5 py-4 backdrop-blur-sm">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-2xl border border-white/10 bg-white/5 shadow-[0_0_40px_rgba(251,191,36,0.08)]">
            <Sparkles size={18} className="text-amber-300" />
          </div>
          <div className="min-w-0">
            <h1 className="text-lg font-semibold text-white">澧ㄦ煋鍒涗綔</h1>
            <p className="text-sm text-gray-400">鐢ㄥ璇濅覆璧峰垎闀溿€佺珷鑺備笌婕敾鐢熸垚鐨勫垱浣滈椤?/p>
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 flex-col gap-4 p-4 lg:flex-row">
        <aside className="w-full shrink-0 rounded-3xl border border-white/10 bg-black/20 p-4 backdrop-blur-sm lg:w-[320px]">
          <div className="space-y-4">
            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">鏁呬簨</p>
              <select
                value={storyId}
                onChange={(e) => setStoryId(e.target.value)}
                className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-3 text-sm text-gray-100 outline-none transition focus:border-amber-400/50"
              >
                {stories.length === 0 ? <option value="">鏆傛棤鏁呬簨</option> : null}
                {stories.map((story) => (
                  <option key={story.id} value={story.id} className="bg-gray-900 text-gray-100">
                    {story.title}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">绔犺妭</p>
              <div className="relative">
                <select
                  value={chapterId}
                  onChange={(e) => setChapterId(e.target.value)}
                  disabled={chapterLoading || chapters.length === 0}
                  className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-3 text-sm text-gray-100 outline-none transition focus:border-amber-400/50 disabled:opacity-40"
                >
                  {chapters.length === 0 ? <option value="">鏆傛棤绔犺妭</option> : null}
                  {chapters.map((chapter) => (
                    <option key={chapter.id} value={chapter.id} className="bg-gray-900 text-gray-100">
                      绗?{chapter.chapter_number} 璇?                    </option>
                  ))}
                </select>
                {chapterLoading && <Loader2 size={16} className="absolute right-3 top-1/2 -translate-y-1/2 animate-spin text-gray-500" />}
              </div>
            </div>

            <div className="rounded-3xl border border-amber-300/10 bg-amber-300/[0.06] p-4">
              <p className="text-xs uppercase tracking-[0.22em] text-amber-200/70">褰撳墠宸ヤ綔鍙?/p>
              <div className="mt-3 space-y-2">
                <div>
                  <div className="text-xs text-gray-500">鏁呬簨鍚?/div>
                  <div className="text-sm text-gray-100">{activeStory?.title || '鏈€夋嫨鏁呬簨'}</div>
                </div>
                <div>
                  <div className="text-xs text-gray-500">绔犺妭</div>
                  <div className="text-sm text-gray-100">{activeChapter ? `绗?${activeChapter.chapter_number} 璇漙 : '鏈€夋嫨绔犺妭'}</div>
                </div>
              </div>
              <button
                onClick={() => void startNewConversation()}
                disabled={!chapterId || loading || historyLoading}
                className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-amber-200/20 bg-black/20 px-3 py-2 text-sm text-amber-100 transition hover:border-amber-200/40 hover:bg-amber-200/10 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <Plus size={15} />
                鏂板璇?              </button>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">蹇嵎鍙戣捣</p>
              <div className="space-y-2">
                {STARTER_PROMPTS.map((prompt) => (
                  <button
                    key={prompt}
                    onClick={() => void send(prompt)}
                    disabled={!chapterId || loading || historyLoading}
                    className="w-full rounded-2xl border border-white/10 bg-white/[0.04] px-3 py-3 text-left text-sm text-gray-300 transition hover:border-amber-300/30 hover:bg-white/[0.08] hover:text-white disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    {prompt}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </aside>

        <main className="flex min-h-0 min-w-0 flex-1 flex-col rounded-[32px] border border-white/10 bg-black/20 backdrop-blur-sm">
          <div className="flex items-center gap-3 border-b border-white/10 px-5 py-4">
            <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-white/5">
              <Bot size={18} className="text-gray-200" />
            </div>
            <div className="min-w-0">
              <div className="text-sm font-medium text-white">婕敾鏅鸿兘浣?/div>
              <div className="text-xs text-gray-500">瀵硅瘽璁板綍鎸夊綋鍓嶇珷鑺傚拰瀵硅瘽闅旂淇濆瓨</div>
            </div>
          </div>

          {error && <div className="mx-4 mt-4 rounded-2xl border border-red-500/30 bg-red-950/40 px-3 py-2 text-sm text-red-200">{error}</div>}

          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
            {bootLoading || historyLoading ? (
              <div className="flex h-full items-center justify-center">
                <Loader2 size={28} className="animate-spin text-amber-300" />
              </div>
            ) : emptyState ? (
              <div className="flex h-full flex-col items-center justify-center px-6 text-center">
                <div className="mb-6 flex h-24 w-24 items-center justify-center rounded-full border border-white/10 bg-white/[0.04] shadow-[0_0_60px_rgba(245,158,11,0.08)]">
                  <BookOpenText size={34} className="text-amber-200" />
                </div>
                <h2 className="text-3xl font-semibold text-white">澧ㄦ煋鍒涗綔</h2>
                <p className="mt-3 max-w-xl text-sm leading-7 text-gray-400">
                  浠庤繖涓€椤靛紑濮嬶紝鐢ㄥ璇濇帹杩涗綘鐨勬极鐢诲垱浣溿€傞€夊畾鏁呬簨涓庣珷鑺傚悗锛屾櫤鑳戒綋浼氬府浣犳鏌ヤ笂涓嬫枃銆佺敓鎴愬垎闀溿€佹暣鐞嗕笅涓€姝ャ€?                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {messages.map((msg, idx) => (
                  <div key={`${msg.requestId || 'msg'}-${idx}`} className={msg.role === 'user' ? 'flex justify-end' : 'flex justify-start'}>
                    <div
                      className={
                        'max-w-[85%] rounded-3xl px-4 py-3 shadow-sm ' +
                        (msg.role === 'user'
                          ? 'whitespace-pre-wrap bg-amber-300 text-sm leading-7 text-gray-950'
                          : msg.role === 'system'
                            ? 'border border-red-400/20 bg-red-950/30 text-red-100'
                            : 'border border-white/10 bg-white/[0.04] text-gray-200')
                      }
                    >
                      {msg.role === 'assistant' || msg.role === 'system' ? <MarkdownMessage content={msg.content} /> : msg.content}
                    </div>
                  </div>
                ))}
                {showExecutionPanel && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-4 text-sm text-gray-300">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs ${executionBadgeClass(
                          userInputRequest ? 'waiting' : latestRunSummary?.tone || (loading ? 'thinking' : 'neutral'),
                        )}`}>
                          {executionIcon(
                            userInputRequest ? 'waiting' : latestRunSummary?.tone || (loading ? 'thinking' : 'neutral'),
                            userInputRequest ? 'question' : latestRunSummary?.icon || (loading ? 'sparkles' : 'clock'),
                          )}
                          {userInputRequest ? '绛夊緟鐢ㄦ埛鍐崇瓥' : loading ? '杩愯涓? : '鏈€杩戞墽琛岃褰?}
                        </span>
                        {visibleRequestId && (
                          <span className="inline-flex items-center rounded-full border border-white/10 bg-black/20 px-3 py-1 text-xs text-gray-300">
                            requestId {formatRequestId(visibleRequestId)}
                          </span>
                        )}
                        {latestRunEvent?.createdAt && (
                          <span className="text-xs text-gray-500">{formatTimestamp(latestRunEvent.createdAt)}</span>
                        )}
                        {visibleRequestId && (loading || userInputRequest) && (
                          <button
                            onClick={() => void cancelActiveRun()}
                            className="inline-flex items-center gap-1 rounded-full border border-red-400/30 bg-red-950/30 px-3 py-1 text-xs text-red-100 transition hover:border-red-300/60 hover:bg-red-900/40"
                          >
                            <Square size={12} />
                            鍋滄
                          </button>
                        )}
                      </div>
                      <div className="mt-3 text-sm text-gray-200">{runStatus}</div>
                      {latestRunSummary && (
                        <div className="mt-3 rounded-2xl border border-white/10 bg-black/15 px-4 py-3">
                          <div className="flex items-start gap-3">
                            <div className="mt-0.5">{executionIcon(latestRunSummary.tone, latestRunSummary.icon)}</div>
                            <div className="min-w-0 flex-1">
                              <div className="text-sm font-medium text-white">{latestRunSummary.title}</div>
                              <div className="mt-1 text-xs leading-5 text-gray-400">{latestRunSummary.detail}</div>
                            </div>
                          </div>
                        </div>
                      )}
                      {runEvents.length > 0 && (
                        <div className="mt-3 grid gap-2">
                          {runEvents.slice(-6).map((event, index) => {
                            const summary = timelineEventSummary(event);
                            return (
                              <div key={`${event.type}-${event.createdAt || index}`} className="flex items-start gap-3 rounded-2xl border border-white/10 bg-black/15 px-3 py-3">
                                <div className="mt-0.5">{executionIcon(summary.tone, summary.icon)}</div>
                                <div className="min-w-0 flex-1">
                                  <div className="flex flex-wrap items-center gap-2">
                                    <span className="text-sm font-medium text-white">{summary.title}</span>
                                    <span className="text-[11px] uppercase tracking-[0.18em] text-gray-500">{event.type}</span>
                                  </div>
                                  <div className="mt-1 text-xs leading-5 text-gray-400">{summary.detail}</div>
                                </div>
                                {event.createdAt && <div className="shrink-0 text-[11px] text-gray-500">{formatTimestamp(event.createdAt)}</div>}
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  </div>
                )}
                {draftReply && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-3 text-sm leading-7 text-gray-200">
                      <MarkdownMessage content={draftReply} />
                    </div>
                  </div>
                )}
                {userInputRequest && (
                  <div className="flex justify-start">
                    <div className="max-w-[85%] rounded-3xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-4 text-sm text-gray-100">
                      <div className="text-xs uppercase tracking-[0.18em] text-amber-200/70">闇€瑕佷綘鐨勫喅瀹?/div>
                      <div className="mt-2 text-base font-medium text-white">{userInputRequest.question}</div>
                      {userInputRequest.reason && <div className="mt-1 text-xs text-gray-400">{userInputRequest.reason}</div>}
                      <div className="mt-4 space-y-2">
                        {userInputRequest.options.map((option) => (
                          <button
                            key={option.id}
                            onClick={() => void resumeWithAnswer(option.label)}
                            className="w-full rounded-2xl border border-white/10 bg-black/20 px-3 py-3 text-left transition hover:border-amber-300/40 hover:bg-white/[0.06]"
                          >
                            <div className="flex items-center gap-2 text-sm font-medium text-white">
                              <span>{option.label}</span>
                              {option.recommended && <span className="rounded-full bg-amber-300/15 px-2 py-0.5 text-[11px] text-amber-100">鎺ㄨ崘</span>}
                            </div>
                            {option.description && <div className="mt-1 text-xs leading-5 text-gray-400">{option.description}</div>}
                          </button>
                        ))}
                      </div>
                      {userInputRequest.allowFreeText && (
                        <div className="mt-3 flex gap-2">
                          <input
                            value={customAnswer}
                            onChange={(e) => setCustomAnswer(e.target.value)}
                            placeholder="杈撳叆鍏朵粬閫夋嫨"
                            className="min-w-0 flex-1 rounded-2xl border border-white/10 bg-black/20 px-3 py-2 text-sm text-gray-100 outline-none focus:border-amber-300/40"
                          />
                          <button
                            onClick={() => void resumeWithAnswer(customAnswer)}
                            disabled={!customAnswer.trim()}
                            className="rounded-2xl bg-amber-300 px-4 py-2 text-sm font-medium text-gray-950 disabled:cursor-not-allowed disabled:opacity-40"
                          >
                            缁х画
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

          <div className="border-t border-white/10 p-4">
            <div className="flex gap-3">
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    void send();
                  }
                }}
                rows={2}
                placeholder={chapterId ? '渚嬪锛氬府鎴戝厛妫€鏌ヨ繖涓€璇濇槸鍚﹀彲浠ョ洿鎺ョ敓鎴愭极鐢? : '鍏堝湪宸︿晶閫夋嫨鏁呬簨鍜岀珷鑺?}
                className="min-h-[58px] flex-1 resize-none rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-3 text-sm text-gray-100 outline-none transition placeholder:text-gray-500 focus:border-amber-300/40"
              />
              <button
                onClick={() => void send()}
                disabled={loading || historyLoading || !chapterId || !input.trim()}
                className="inline-flex h-auto min-w-[110px] items-center justify-center gap-2 rounded-3xl bg-amber-300 px-4 py-3 text-sm font-medium text-gray-950 transition hover:bg-amber-200 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {loading ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
                鍙戦€?              </button>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}
