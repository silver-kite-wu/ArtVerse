import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react';
import { Bot, BookOpenText, Loader2, Send, Sparkles } from 'lucide-react';
import {
  getMangaAgentMessages,
  listChapters,
  listStories,
  runMangaAgent,
  type Chapter,
  type MangaAgentMessage,
  type Story,
} from '../api';

interface Message {
  role: 'user' | 'assistant';
  content: string;
  requestId?: string;
}

const STARTER_PROMPTS = [
  '帮我检查这一话的漫画进度，并告诉我下一步做什么',
  '根据当前内容，先帮我生成这一话的分镜',
  '帮我看看分镜是否还需要润色，再给出修改建议',
];

function toMessages(items: MangaAgentMessage[]): Message[] {
  return items
    .filter((item) => item.role === 'user' || item.role === 'assistant')
    .map((item) => ({
      role: item.role,
      content: item.content,
      requestId: item.requestId ?? item.request_id,
    }));
}

function createRequestId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
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
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [bootLoading, setBootLoading] = useState(true);
  const [chapterLoading, setChapterLoading] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [error, setError] = useState('');
  const chapterIdRef = useRef('');

  useEffect(() => {
    chapterIdRef.current = chapterId;
  }, [chapterId]);

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
        if (active) setError(err.message || '加载故事失败');
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
        if (active) setError(err.message || '加载章节失败');
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
      if (!chapterId) {
        return;
      }
      setHistoryLoading(true);
      try {
        const list = await getMangaAgentMessages(Number(chapterId));
        if (!active) return;
        setMessages(toMessages(list));
      } catch (err: any) {
        if (active) setError(err.message || '加载对话记录失败');
      } finally {
        if (active) setHistoryLoading(false);
      }
    };
    load();
    return () => {
      active = false;
    };
  }, [chapterId]);

  const send = async (override?: string) => {
    const requestChapterId = chapterId;
    const id = Number(requestChapterId);
    const text = (override ?? input).trim();
    if (!id || !text || loading) return;

    const requestId = createRequestId();
    setLoading(true);
    setError('');
    setMessages((prev) => [...prev, { role: 'user', content: text, requestId }]);
    setInput('');

    try {
      const result = await runMangaAgent(id, text, requestId);
      if (chapterIdRef.current === requestChapterId) {
        setMessages((prev) => [
          ...prev,
          { role: 'assistant', content: result.reply, requestId: result.requestId ?? result.request_id ?? requestId },
        ]);
      }
    } catch (err: any) {
      if (chapterIdRef.current !== requestChapterId) return;
      setError(err.message || '请求失败');
      try {
        const list = await getMangaAgentMessages(id);
        if (chapterIdRef.current === requestChapterId) {
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
            <h1 className="text-lg font-semibold text-white">墨染创作</h1>
            <p className="text-sm text-gray-400">用对话串起分镜、章节与漫画生成的创作首页</p>
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 flex-col gap-4 p-4 lg:flex-row">
        <aside className="w-full shrink-0 rounded-3xl border border-white/10 bg-black/20 p-4 backdrop-blur-sm lg:w-[320px]">
          <div className="space-y-4">
            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">故事</p>
              <select
                value={storyId}
                onChange={(e) => setStoryId(e.target.value)}
                className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-3 text-sm text-gray-100 outline-none transition focus:border-amber-400/50"
              >
                {stories.length === 0 ? <option value="">暂无故事</option> : null}
                {stories.map((story) => (
                  <option key={story.id} value={story.id} className="bg-gray-900 text-gray-100">
                    {story.title}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">章节</p>
              <div className="relative">
                <select
                  value={chapterId}
                  onChange={(e) => setChapterId(e.target.value)}
                  disabled={chapterLoading || chapters.length === 0}
                  className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-3 text-sm text-gray-100 outline-none transition focus:border-amber-400/50 disabled:opacity-40"
                >
                  {chapters.length === 0 ? <option value="">暂无章节</option> : null}
                  {chapters.map((chapter) => (
                    <option key={chapter.id} value={chapter.id} className="bg-gray-900 text-gray-100">
                      第 {chapter.chapter_number} 话
                    </option>
                  ))}
                </select>
                {chapterLoading && <Loader2 size={16} className="absolute right-3 top-1/2 -translate-y-1/2 animate-spin text-gray-500" />}
              </div>
            </div>

            <div className="rounded-3xl border border-amber-300/10 bg-amber-300/[0.06] p-4">
              <p className="text-xs uppercase tracking-[0.22em] text-amber-200/70">当前工作台</p>
              <div className="mt-3 space-y-2">
                <div>
                  <div className="text-xs text-gray-500">故事名</div>
                  <div className="text-sm text-gray-100">{activeStory?.title || '未选择故事'}</div>
                </div>
                <div>
                  <div className="text-xs text-gray-500">章节</div>
                  <div className="text-sm text-gray-100">{activeChapter ? `第 ${activeChapter.chapter_number} 话` : '未选择章节'}</div>
                </div>
              </div>
            </div>

            <div>
              <p className="mb-2 text-xs uppercase tracking-[0.22em] text-gray-500">快捷发起</p>
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
              <div className="text-sm font-medium text-white">漫画智能体</div>
              <div className="text-xs text-gray-500">对话记录按当前章节隔离保存</div>
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
                <h2 className="text-3xl font-semibold text-white">墨染创作</h2>
                <p className="mt-3 max-w-xl text-sm leading-7 text-gray-400">
                  从这一页开始，用对话推进你的漫画创作。选定故事与章节后，智能体会帮你检查上下文、生成分镜、整理下一步。
                </p>
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
                          : 'border border-white/10 bg-white/[0.04] text-gray-200')
                      }
                    >
                      {msg.role === 'assistant' ? <MarkdownMessage content={msg.content} /> : msg.content}
                    </div>
                  </div>
                ))}
                {loading && (
                  <div className="flex justify-start">
                    <div className="inline-flex items-center gap-2 rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-3 text-sm text-gray-400">
                      <Loader2 size={15} className="animate-spin" />
                      正在思考当前章节...
                    </div>
                  </div>
                )}
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
                placeholder={chapterId ? '例如：帮我先检查这一话是否可以直接生成漫画' : '先在左侧选择故事和章节'}
                className="min-h-[58px] flex-1 resize-none rounded-3xl border border-white/10 bg-white/[0.04] px-4 py-3 text-sm text-gray-100 outline-none transition placeholder:text-gray-500 focus:border-amber-300/40"
              />
              <button
                onClick={() => void send()}
                disabled={loading || historyLoading || !chapterId || !input.trim()}
                className="inline-flex h-auto min-w-[110px] items-center justify-center gap-2 rounded-3xl bg-amber-300 px-4 py-3 text-sm font-medium text-gray-950 transition hover:bg-amber-200 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {loading ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
                发送
              </button>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}
