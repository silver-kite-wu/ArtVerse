import { useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, FileText, History, MessageSquare, Save, Send, Sparkles, Square } from 'lucide-react';
import {
  API_KEY_CHANGE_EVENT,
  cancelStoryChatConversationRun,
  createAiConversation,
  getOpenStoryChatConversationRun,
  getPrimaryProviderModel,
  getProviderModelOptions,
  getStoryChatRunArtifacts,
  listAiConversations,
  listNovelRevisions,
  renameAiConversation,
  restoreNovelRevision,
  resumeStoryChatAgUiStream,
  runStoryChatAgUiStream,
  saveNovelContent,
  type AiConversationSummary,
  type ArtVerseAgUiEvent,
  type Chapter,
  type ChatMessage,
  type MangaAgentRunEvent,
  type NovelRevision,
} from '../api';
import MarkdownRenderer from './MarkdownRenderer';
import ModelSwitcher from './ModelSwitcher';
import InlineConversationTitle from './InlineConversationTitle';

type Mode = 'chat' | 'original';
type DisplayMessage = ChatMessage & { local_id?: string };

const MAX_ORIGINAL_CHARS = 50000;

interface Props {
  chapter: Chapter | null;
  onMessageSent?: () => void | Promise<void>;
  onChapterRefresh?: (chapterId: number) => void | Promise<void>;
}

interface PendingDraftState {
  requestId: string;
  artifactId: string;
  content: string;
  contentHash: string;
  baseVersion: number;
  currentWordCount: number;
  draftWordCount: number;
  expanded: boolean;
  busy: boolean;
  error: string;
}

export default function ChatPanel({ chapter, onMessageSent, onChapterRefresh }: Props) {
  const [messages, setMessages] = useState<DisplayMessage[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [streamContent, setStreamContent] = useState('');
  const [mode, setMode] = useState<Mode>('chat');
  const [selectedLlmModel, setSelectedLlmModel] = useState(() => getPrimaryProviderModel('llm'));
  const [originalText, setOriginalText] = useState('');
  const [serverOriginalText, setServerOriginalText] = useState('');
  const [writeBusy, setWriteBusy] = useState(false);
  const [originalError, setOriginalError] = useState('');
  const [originalNotice, setOriginalNotice] = useState('');
  const [revisions, setRevisions] = useState<NovelRevision[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [conversation, setConversation] = useState<AiConversationSummary | null>(null);
  const [pendingDraft, setPendingDraft] = useState<PendingDraftState | null>(null);
  const [activeRequestId, setActiveRequestId] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const streamingChapterIdRef = useRef<number | null>(null);
  const userScrolledUp = useRef(false);

  const originalDirty = originalText !== serverOriginalText;

  const refreshRevisions = async (chapterId: number) => setRevisions(await listNovelRevisions(chapterId));
  const autoResize = (el: HTMLTextAreaElement) => { el.style.height = 'auto'; el.style.height = `${el.scrollHeight}px`; };

  useEffect(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    streamingChapterIdRef.current = null;
    if (chapter) {
      setMessages(getDisplayMessages(chapter));
      const text = chapter.novel_content || '';
      setOriginalText(text);
      setServerOriginalText(text);
      void refreshRevisions(chapter.id).catch(() => setRevisions([]));
    } else {
      setMessages([]);
      setOriginalText('');
      setServerOriginalText('');
      setRevisions([]);
    }
    setPendingDraft(null);
    setActiveRequestId(null);
    setMode('chat');
    setOriginalError('');
    setOriginalNotice('');
    setStreamContent('');
    setStreaming(false);
  }, [chapter?.id, chapter?.version]);

  useEffect(() => {
    if (!chapter) { setConversation(null); return; }
    void listAiConversations('STORY_CHAT', chapter.id)
      .then((items) => setConversation(items[0] ?? null))
      .catch(() => setConversation(null));
  }, [chapter?.id, messages.length]);

  useEffect(() => {
    if (!chapter || !conversation) return;
    void getOpenStoryChatConversationRun(chapter.id, conversation.conversationId)
      .then(async (run) => {
        if (!run || run.status !== 'WAITING_USER') return;
        const requestId = run.requestId ?? run.request_id;
        if (requestId) await loadDraftCard(requestId, conversation.conversationId);
      })
      .catch(() => undefined);
  }, [chapter?.id, conversation?.conversationId]);

  useEffect(() => {
    if (!userScrolledUp.current) messagesEndRef.current?.scrollIntoView({ behavior: streaming ? 'instant' : 'smooth' });
  }, [messages, streamContent, streaming, pendingDraft]);
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

  const appendAssistantError = (content: string) => {
    setMessages((previous) => [...previous, {
      local_id: `local-error-${Date.now()}`,
      id: Date.now(),
      role: 'assistant',
      content,
      completion_status: 'partial',
      created_at: new Date().toISOString(),
    }]);
  };

  async function loadDraftCard(requestId: string, conversationId: string) {
    if (!chapter) return;
    const artifacts = await getStoryChatRunArtifacts(chapter.id, conversationId, requestId);
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
      currentWordCount: Number(payload.current_word_count ?? (serverOriginalText.trim().length || 0)),
      draftWordCount: Number(payload.word_count ?? content.length),
      expanded: false,
      busy: false,
      error: '',
    });
  }

  const finishStream = async (reply: string) => {
    abortRef.current = null;
    streamingChapterIdRef.current = null;
    setStreamContent('');
    setStreaming(false);
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
    await onMessageSent?.();
  };

  const handleStoryChatEvent = (
    event: ArtVerseAgUiEvent,
    requestId: string,
    conversationId: string,
    stream: { append: (delta: string) => void; finalText: () => string },
  ) => {
    if (!event || typeof event.type !== 'string') return;
    if (event.type === 'TEXT_MESSAGE_CONTENT') {
      stream.append(String((event as any).delta || ''));
      return;
    }
    if (event.type === 'RUN_FINISHED') {
      const interrupts = event.outcome?.interrupts || [];
      if (event.outcome?.type === 'interrupt' || interrupts.length > 0) {
        abortRef.current = null;
        streamingChapterIdRef.current = null;
        setStreamContent('');
        setStreaming(false);
        void loadDraftCard(requestId, conversationId);
        return;
      }
      void finishStream(event.result?.reply || stream.finalText());
      return;
    }
    if (event.type === 'RUN_ERROR') {
      appendAssistantError(String((event as any).message || '小说对话执行失败。'));
      setStreamContent('');
      setStreaming(false);
      abortRef.current = null;
      streamingChapterIdRef.current = null;
    }
  };

  const handleRunStreamEvent = (
    event: MangaAgentRunEvent,
    requestId: string,
    conversationId: string,
    stream: { append: (delta: string) => void; finalText: () => string },
  ) => {
    if (event.type === 'error') {
      const detail = event.data?.detail || event.data?.error || '小说对话执行失败。';
      appendAssistantError(detail);
      setStreamContent('');
      setStreaming(false);
      abortRef.current = null;
      streamingChapterIdRef.current = null;
      return;
    }
    handleStoryChatEvent(event.data as ArtVerseAgUiEvent, requestId, conversationId, stream);
  };

  const handleSend = () => {
    if (!input.trim() || !chapter || streaming) return;
    const text = input.trim();
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
    setStreaming(true);
    setStreamContent('');
    setPendingDraft(null);
    const requestId = createLocalRequestId();
    setActiveRequestId(requestId);
    let accumulated = '';
    streamingChapterIdRef.current = chapter.id;
    void (async () => {
      const activeConversation = conversation
        ?? (await listAiConversations('STORY_CHAT', chapter.id).then((items) => items[0] ?? null))
        ?? await createAiConversation('STORY_CHAT', undefined, chapter.id);
      setConversation(activeConversation);
      abortRef.current = runStoryChatAgUiStream(
        chapter.id,
        activeConversation.conversationId,
        text,
        requestId,
        (event: MangaAgentRunEvent) => handleRunStreamEvent(event, requestId, activeConversation.conversationId, {
          append(delta) { accumulated += delta; setStreamContent(accumulated); },
          finalText() { return accumulated; },
        }),
        selectedLlmModel,
      );
    })().catch((error: any) => {
      appendAssistantError(error.message || '小说对话启动失败。');
      setStreamContent('');
      setStreaming(false);
      abortRef.current = null;
      streamingChapterIdRef.current = null;
    });
  };

  const handleAbort = () => {
    const chapterId = streamingChapterIdRef.current;
    abortRef.current?.abort();
    if (chapter && conversation && activeRequestId) {
      void cancelStoryChatConversationRun(chapter.id, conversation.conversationId, activeRequestId).catch(() => undefined);
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
    setStreaming(false);
    window.setTimeout(() => chapterId !== null ? void onChapterRefresh?.(chapterId) : void onMessageSent?.(), 500);
  };

  const handleDraftDecision = async (decision: 'confirm' | 'discard') => {
    if (!chapter || !conversation || !pendingDraft || pendingDraft.busy) return;
    setPendingDraft((current) => current ? { ...current, busy: true, error: '' } : current);
    setStreaming(decision === 'confirm');
    let accumulated = '';
    abortRef.current = resumeStoryChatAgUiStream(
      chapter.id,
      conversation.conversationId,
      pendingDraft.requestId,
      decision,
      pendingDraft.artifactId,
      (event: MangaAgentRunEvent) => handleRunStreamEvent(event, pendingDraft.requestId, conversation.conversationId, {
        append(delta) { accumulated += delta; setStreamContent(accumulated); },
        finalText() { return accumulated; },
      }),
      selectedLlmModel,
    );
    window.setTimeout(async () => {
      setPendingDraft((current) => current?.artifactId === pendingDraft.artifactId ? null : current);
      if (decision === 'confirm') {
        await refreshRevisions(chapter.id).catch(() => undefined);
        await onChapterRefresh?.(chapter.id);
      }
    }, 1200);
  };

  const handleOriginalSave = async () => {
    if (!chapter || writeBusy) return;
    const content = originalText.trim();
    if (!content) { setOriginalError('请输入小说原文。'); return; }
    if (content.length > MAX_ORIGINAL_CHARS) { setOriginalError(`内容不能超过 ${MAX_ORIGINAL_CHARS} 字。`); return; }
    if (chapter.version === undefined) { setOriginalError('章节版本缺失，请刷新后再保存。'); return; }
    setWriteBusy(true); setOriginalError(''); setOriginalNotice('');
    try {
      const result = await saveNovelContent(chapter.id, content, chapter.version);
      setOriginalText(content);
      setServerOriginalText(content);
      setOriginalNotice(result.chapter_version !== undefined
        ? `小说原文已保存，当前版本 ${result.chapter_version}。`
        : '小说原文已保存。');
      await refreshRevisions(chapter.id);
      await onChapterRefresh?.(chapter.id);
    } catch (error: any) {
      setOriginalError(error.message || '保存失败。');
    } finally {
      setWriteBusy(false);
    }
  };

  const handleRestore = async (revision: NovelRevision) => {
    if (!chapter || writeBusy || chapter.version === undefined) return;
    setWriteBusy(true); setOriginalError('');
    try {
      await restoreNovelRevision(chapter.id, revision.id, chapter.version);
      setOriginalText(revision.content);
      setServerOriginalText(revision.content);
      setOriginalNotice(`已恢复版本 ${revision.revision_number}，并创建新版本。`);
      await refreshRevisions(chapter.id);
      await onChapterRefresh?.(chapter.id);
    } catch (error: any) {
      setOriginalError(error.message || '恢复失败。');
    } finally {
      setWriteBusy(false);
    }
  };

  return <div className="flex h-full flex-col bg-bg-base">
    <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-2.5">
      <div className="min-w-0 shrink">{conversation ? <InlineConversationTitle title={conversation.title} onSave={async (title) => setConversation(await renameAiConversation(conversation.conversationId, title))} /> : <h2 className="text-sm font-semibold tracking-wide text-text-secondary">创作工作区</h2>}</div>
      <div className="flex items-center gap-0.5" role="tablist" aria-label="创作模式">
        <button onClick={() => setMode('chat')} disabled={streaming} role="tab" aria-selected={mode === 'chat'} className={`relative px-3 py-1.5 text-xs font-medium transition-colors ${mode === 'chat' ? 'text-accent after:absolute after:bottom-0 after:left-2 after:right-2 after:h-0.5 after:rounded-full after:bg-accent' : 'text-text-secondary hover:text-text-primary disabled:opacity-30'}`}><span className="flex items-center gap-1.5"><MessageSquare size={12} />AI 对话</span></button>
        <button onClick={() => setMode('original')} disabled={streaming} role="tab" aria-selected={mode === 'original'} className={`relative px-3 py-1.5 text-xs font-medium transition-colors ${mode === 'original' ? 'text-accent after:absolute after:bottom-0 after:left-2 after:right-2 after:h-0.5 after:rounded-full after:bg-accent' : 'text-text-secondary hover:text-text-primary disabled:opacity-30'}`}><span className="flex items-center gap-1.5"><FileText size={12} />小说原文</span></button>
      </div>
    </div>

    {mode === 'original' ? <div className="flex min-h-0 flex-1 flex-col">
      <div className="shrink-0 px-4 pb-2 pt-3 text-xs leading-relaxed text-text-secondary">小说原文是章节正式正文。手工保存、恢复和 AI 确认写入都会创建历史版本。</div>
      <div className="min-h-0 flex-1 px-4 pb-3"><textarea value={originalText} onChange={(event) => { setOriginalText(event.target.value); setOriginalError(''); setOriginalNotice(''); }} placeholder={`粘贴或撰写小说原文，最长 ${MAX_ORIGINAL_CHARS} 字`} className="h-full w-full resize-none rounded-lg border border-border bg-bg-surface p-3 text-sm leading-relaxed text-text-primary outline-none transition-colors placeholder:text-text-muted focus:border-accent" /></div>
      {showHistory && <div className="mx-4 mb-3 max-h-36 overflow-y-auto rounded-lg border border-border bg-bg-base p-2 text-xs">{revisions.length === 0 ? <p className="px-2 py-1 text-text-muted">暂无历史版本。</p> : revisions.map((revision) => <div key={revision.id} className="flex items-center gap-2 rounded-lg  px-2 py-1.5 hover:bg-bg-surface"><span className="font-medium text-text-primary">v{revision.revision_number}</span><span className="text-text-muted">{revision.source}</span><span className="ml-auto text-text-muted">{new Date(revision.created_at).toLocaleString()}</span><button type="button" onClick={() => handleRestore(revision)} disabled={writeBusy} className="text-accent hover:text-accent-hover disabled:opacity-40">恢复</button></div>)}</div>}
      <div className="flex shrink-0 items-center justify-between gap-3 px-4 pb-3"><div className="text-xs text-text-secondary">{originalText.length.toLocaleString()} / {MAX_ORIGINAL_CHARS.toLocaleString()} 字{originalDirty && <span className="ml-3 text-amber-600">未保存</span>}{originalError && <span className="ml-3 text-red-600" role="alert">{originalError}</span>} {originalNotice && <span className="ml-3 text-accent">{originalNotice}</span>}</div><div className="flex items-center gap-2"><button type="button" onClick={() => setShowHistory((value) => !value)} className="flex items-center gap-1.5 rounded-lg border border-border px-3 py-2 text-xs font-medium text-text-secondary transition-colors hover:text-text-primary" aria-expanded={showHistory}><History size={13} />历史{revisions.length ? ` (${revisions.length})` : ''}</button><button type="button" onClick={handleOriginalSave} disabled={!chapter || writeBusy || !originalText.trim()} className="flex items-center gap-1.5 rounded-lg bg-accent px-4 py-2 text-xs font-medium text-white transition-colors hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-30"><Save size={13} />{writeBusy ? '保存中...' : '保存原文'}</button></div></div>
    </div> : <>
      <div ref={scrollContainerRef} className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
        {messages.length === 0 && !streaming && !pendingDraft && <div className="flex h-full items-center justify-center text-sm text-text-muted">开始和 AI 讨论你的小说创意。</div>}
        {messages.map((message) => <div key={message.local_id ?? message.id} className={`flex ${message.role === 'user' ? 'justify-end' : 'w-full justify-start'}`}><div className={`rounded-xl px-4 py-3 text-sm leading-relaxed ${message.role === 'user' ? 'max-w-[80%] rounded-br-sm bg-accent-muted/40 text-text-primary' : 'w-full rounded-bl-sm border border-border bg-bg-raised text-text-primary shadow-sm'}`}><MarkdownRenderer content={message.content} /></div></div>)}
        {pendingDraft && <NovelDraftCard draft={pendingDraft} onToggle={() => setPendingDraft((value) => value ? { ...value, expanded: !value.expanded } : value)} onDecision={handleDraftDecision} />}
        {streaming && <div className="flex justify-start"><div className="relative w-full rounded-xl rounded-bl-sm border border-border bg-bg-raised px-4 py-3 text-sm leading-relaxed text-text-primary shadow-sm"><div className="gen-beam" />{streamContent ? <><MarkdownRenderer content={streamContent} /><span className="ml-0.5 inline-block h-4 w-1.5 rounded-sm bg-accent" /></> : 'AI 思考中...'}</div></div>}
        <div ref={messagesEndRef} />
      </div>
      <div className="border-t border-border/80 bg-bg-base/80 px-3 py-3 backdrop-blur-md md:px-4">
        <div className="mb-2 flex justify-end"><ModelSwitcher capability="llm" selectedModel={selectedLlmModel} onSelect={setSelectedLlmModel} disabled={streaming || !!pendingDraft?.busy} /></div>
        <div className="group relative overflow-hidden rounded-2xl border border-border bg-bg-surface/95 transition-all duration-200 focus-within:border-accent/70 focus-within:bg-bg-raised focus-within:shadow-[0_0_0_3px_var(--color-accent-muted)]">
          <div className="flex items-end gap-3 px-3 py-3">
            <div className="mb-0.5 hidden h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-border bg-bg-base text-accent md:flex"><Sparkles size={15} aria-hidden="true" /></div>
            <textarea ref={textareaRef} value={input} onChange={(event) => { setInput(event.target.value); autoResize(event.target); }} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); handleSend(); } }} placeholder="描述你的小说想法，或要求 AI 润色、改写、续写并保存..." rows={1} className="min-h-10 flex-1 resize-none bg-transparent py-2 text-[0.9375rem] leading-6 text-text-primary outline-none placeholder:text-text-muted/80" style={{ maxHeight: '10rem', overflow: 'auto' }} />
            {streaming ? <button type="button" onClick={handleAbort} className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent text-white shadow-md shadow-black/20 transition-all duration-200 hover:bg-accent-hover hover:shadow-lg" title="停止生成" aria-label="停止生成"><Square size={17} /></button> : <button type="button" onClick={handleSend} disabled={!input.trim() || !!pendingDraft?.busy} className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent text-white shadow-md shadow-black/20 transition-all duration-200 hover:bg-accent-hover hover:shadow-lg disabled:cursor-not-allowed disabled:bg-bg-raised disabled:text-text-muted disabled:shadow-none" title="发送消息" aria-label="发送消息"><Send size={17} /></button>}
          </div>
        </div>
      </div>
    </>}
  </div>;
}

function NovelDraftCard({ draft, onToggle, onDecision }: {
  draft: PendingDraftState;
  onToggle: () => void;
  onDecision: (decision: 'confirm' | 'discard') => void;
}) {
  const delta = draft.draftWordCount - draft.currentWordCount;
  return <section className="rounded-lg border border-accent/25 bg-bg-raised p-4 text-sm shadow-sm" aria-live="polite">
    <div className="flex items-start justify-between gap-3">
      <div>
        <h3 className="text-sm font-semibold text-text-primary">小说原文草稿</h3>
        <p className="mt-1 text-xs text-text-secondary">基准版本 {draft.baseVersion} · 当前 {draft.currentWordCount.toLocaleString()} 字 · 草稿 {draft.draftWordCount.toLocaleString()} 字 · {delta >= 0 ? '+' : ''}{delta.toLocaleString()}</p>
      </div>
      <button type="button" onClick={onToggle} className="flex h-8 w-8 items-center justify-center rounded-lg border border-border text-text-secondary hover:text-text-primary" aria-expanded={draft.expanded} title={draft.expanded ? '收起草稿' : '展开草稿'}>
        {draft.expanded ? <ChevronUp size={15} /> : <ChevronDown size={15} />}
      </button>
    </div>
    {draft.expanded ? <div className="mt-3 max-h-72 overflow-y-auto rounded-lg border border-border bg-bg-surface p-3 whitespace-pre-wrap leading-relaxed text-text-primary">{draft.content}</div> : <p className="mt-3 line-clamp-3 whitespace-pre-wrap text-text-secondary">{draft.content}</p>}
    {draft.error && <p className="mt-2 text-xs text-red-600" role="alert">{draft.error}</p>}
    <div className="mt-3 flex justify-end gap-2">
      <button type="button" onClick={() => onDecision('discard')} disabled={draft.busy} className="rounded-md border border-border px-3 py-2 text-xs font-medium text-text-secondary hover:text-text-primary disabled:opacity-40">放弃</button>
      <button type="button" onClick={() => onDecision('confirm')} disabled={draft.busy} className="rounded-md bg-accent px-3 py-2 text-xs font-medium text-white hover:bg-accent-hover disabled:opacity-40">{draft.busy ? '处理中...' : '确认写入'}</button>
    </div>
  </section>;
}

function getDisplayMessages(chapter: Chapter): DisplayMessage[] {
  const sourceMessages = chapter.messages ?? [];
  return sourceMessages
    .filter((message) => !isLegacyImportedOriginalMirror(chapter, message))
    .map((message) => ({ ...message, completion_status: message.completion_status ?? 'complete' }));
}

function isLegacyImportedOriginalMirror(chapter: Chapter, message: Pick<DisplayMessage, 'role' | 'content'>): boolean {
  if (chapter.content_source !== 'import') return false;
  const originalText = (chapter.novel_content ?? '').trim();
  return originalText.length > 0 && message.role === 'user' && message.content.trim() === originalText;
}

function createLocalRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
