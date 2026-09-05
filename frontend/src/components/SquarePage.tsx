import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  BookOpenText,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Compass,
  Image as ImageIcon,
  LibraryBig,
  Loader2,
  Search,
  Settings2,
  Sparkles,
} from 'lucide-react';
import {
  coverImageUrl,
  getSquareStoryDetail,
  listSquareStories,
  mangaImageUrl,
  type SquareFormat,
  type SquareStory,
  type SquareStoryDetail,
} from '../api';
import { useTheme } from '../hooks/useTheme';

const PAGE_SIZE = 12;

type ContentFormat = Exclude<SquareFormat, 'all'>;
type SquareLocation =
  | { kind: 'list'; format: ContentFormat; page: number; query: string }
  | { kind: 'detail'; format: ContentFormat; storyId: number; chapterId?: number };

type ReadingState = {
  chapterId?: number;
  progress?: number;
  fontSize?: number;
  lineHeight?: number;
  width?: number;
};

const readingKey = (storyId: number, format: ContentFormat) => `artverse.square.reading.${storyId}.${format}`;

function location(): SquareLocation {
  const raw = window.location.hash.slice(1);
  const [path, queryString = ''] = raw.split('?');
  const query = new URLSearchParams(queryString);
  const detail = /^\/square\/(novel|manga)\/(\d+)(?:\/chapter\/(\d+))?$/.exec(path);
  if (detail) {
    return {
      kind: 'detail',
      format: detail[1] as ContentFormat,
      storyId: Number(detail[2]),
      ...(detail[3] ? { chapterId: Number(detail[3]) } : {}),
    };
  }

  return {
    kind: 'list',
    format: query.get('format') === 'manga' ? 'manga' : 'novel',
    page: Math.max(0, Number(query.get('page')) || 0),
    query: query.get('q') || '',
  };
}

function go(next: SquareLocation, replace = false): void {
  const hash = next.kind === 'detail'
    ? `#/square/${next.format}/${next.storyId}${next.chapterId ? `/chapter/${next.chapterId}` : ''}`
    : `#/square?format=${next.format}&page=${next.page}${next.query ? `&q=${encodeURIComponent(next.query)}` : ''}`;

  if (window.location.hash === hash) {
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    return;
  }

  if (replace) {
    window.history.replaceState(null, '', hash);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    return;
  }

  window.location.hash = hash;
}

function storyHref(format: ContentFormat, storyId: number): string {
  return `#/square/${format}/${storyId}`;
}

function chapterTitle(chapter: SquareStoryDetail['chapters'][number]): string {
  return chapter.display_title || `第${chapter.chapter_number}章`;
}

function formatLabel(format: ContentFormat): string {
  return format === 'novel' ? '小说' : '漫画';
}

function displayDate(value: string | null): string {
  if (!value) return '近期发布';
  return new Date(value).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
}

function displayDateTime(value: string | null): string {
  if (!value) return '近期发布';
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatCount(format: ContentFormat, count: number): string {
  return format === 'novel'
    ? `${count.toLocaleString('zh-CN')} 字`
    : `${count.toLocaleString('zh-CN')} 页`;
}

function readReadingState(storyId: number, format: ContentFormat): ReadingState {
  try {
    const raw = localStorage.getItem(readingKey(storyId, format));
    if (!raw) return {};
    const parsed = JSON.parse(raw) as ReadingState;
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function progressPercent(progress: number | undefined): number {
  if (typeof progress !== 'number' || Number.isNaN(progress)) return 0;
  return Math.max(0, Math.min(100, Math.round(progress * 100)));
}

function chapterMetaLabel(chapter: SquareStoryDetail['chapters'][number], format: ContentFormat): string {
  return `${chapter.chapter_number.toString().padStart(2, '0')} · ${formatCount(format, chapter.content_count)}`;
}

export default function SquarePage() {
  const [route, setRoute] = useState<SquareLocation>(location);

  useEffect(() => {
    const onHash = () => setRoute(location());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  return route.kind === 'list' ? <SquareList route={route} /> : <StoryView route={route} />;
}

function SquareList({ route }: { route: Extract<SquareLocation, { kind: 'list' }> }) {
  const [stories, setStories] = useState<SquareStory[]>([]);
  const [facets, setFacets] = useState<Record<SquareFormat, number>>({ all: 0, novel: 0, manga: 0 });
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [input, setInput] = useState(route.query);

  useEffect(() => setInput(route.query), [route.query]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const data = await listSquareStories(route.page, PAGE_SIZE, route.query || undefined, route.format);
      setStories(data.content);
      setTotalPages(data.total_pages);
      setFacets(data.facets);
    } catch {
      setError('作品加载失败，请检查网络后重试。');
    } finally {
      setLoading(false);
    }
  }, [route]);

  useEffect(() => {
    void load();
  }, [load]);

  const switchFormat = (format: ContentFormat) => go({ kind: 'list', format, page: 0, query: route.query });
  const activeLabel = formatLabel(route.format);

  return (
    <main id="square-main" className="page-atmosphere relative flex min-h-0 flex-1 flex-col overflow-y-auto">
      <a href="#square-content" className="sr-only focus:not-sr-only">跳到作品列表</a>
      <div className="relative mx-auto w-full max-w-6xl px-4 py-7 sm:px-6 sm:py-10 lg:px-8">
        <header className="border-b border-border pb-6 sm:pb-8">
          <div className="flex items-center gap-2 text-accent">
            <Compass size={19} />
            <span className="text-sm font-semibold tracking-[0.18em]">作品广场</span>
          </div>
          <div className="mt-4 flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <h1 className="font-display text-3xl font-bold tracking-tight text-text-primary sm:text-4xl">
                找一部，读进去
              </h1>
              <p className="mt-2 max-w-xl text-sm leading-6 text-text-secondary">
                小说与漫画分栏展示，沿着你的阅读偏好继续发现下一段故事。
              </p>
            </div>
            <div className="hidden items-center gap-2 text-sm text-text-muted sm:flex">
              <Sparkles size={16} className="text-accent" />
              已收录 {facets.novel + facets.manga} 部公开作品
            </div>
          </div>
        </header>

        <div className="sticky top-0 z-10 -mx-4 border-b border-border bg-bg-base/95 px-4 py-4 backdrop-blur sm:-mx-6 sm:px-6 lg:-mx-8 lg:px-8">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <nav className="flex w-fit items-center rounded-xl bg-bg-surface p-1 ring-1 ring-border" aria-label="作品类型">
              {(['novel', 'manga'] as ContentFormat[]).map((format) => (
                <button
                  key={format}
                  type="button"
                  onClick={() => switchFormat(format)}
                  aria-pressed={route.format === format}
                  className={`min-h-10 rounded-lg px-4 text-sm font-semibold transition-all ${
                    route.format === format
                      ? 'bg-accent text-white shadow-sm'
                      : 'text-text-secondary hover:bg-accent-soft hover:text-text-primary'
                  }`}
                >
                  {formatLabel(format)}
                  <span className={`ml-1.5 inline-flex min-w-5 items-center justify-center rounded-full px-1.5 text-[0.625rem] font-bold ${
                    route.format === format
                      ? 'bg-white/20 text-white'
                      : 'bg-accent-muted text-accent'
                  }`}>{facets[format]}</span>
                </button>
              ))}
            </nav>

            <form
              className="flex w-full gap-2 sm:w-80"
              onSubmit={(event) => {
                event.preventDefault();
                go({ kind: 'list', format: route.format, page: 0, query: input.trim() });
              }}
            >
              <label className="relative min-w-0 flex-1">
                <span className="sr-only">搜索{activeLabel}</span>
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted" size={17} />
                <input
                  className="input-field h-10 w-full rounded-lg pl-9 text-sm"
                  value={input}
                  onChange={(event) => setInput(event.target.value)}
                  placeholder={`搜索${activeLabel}标题或简介`}
                />
              </label>
              <button
                className="h-10 rounded-lg bg-accent px-4 text-sm font-semibold text-white transition hover:brightness-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
                type="submit"
              >
                搜索
              </button>
            </form>
          </div>
        </div>

        <section id="square-content" className="pt-6" aria-live="polite">
          <div className="mb-5 flex items-baseline justify-between">
            <h2 className="font-display text-xl font-bold text-text-primary">{activeLabel}书架</h2>
            <span className="text-sm text-text-muted">第 {route.page + 1} 页</span>
          </div>

          {loading ? (
            <div className="grid gap-4 md:grid-cols-2">
              {Array.from({ length: 6 }).map((_, index) => (
                <div key={index} className="h-48 rounded-2xl border border-border bg-bg-surface/50 shimmer-bg" />
              ))}
            </div>
          ) : error ? (
            <Empty icon={<Compass size={42} />} text={error} action="重试" onAction={() => void load()} />
          ) : stories.length === 0 ? (
            <Empty
              icon={<Search size={42} />}
              text={`没有找到符合条件的${activeLabel}`}
              action="清除搜索"
              onAction={() => go({ kind: 'list', format: route.format, page: 0, query: '' })}
            />
          ) : (
            <>
              <div className="grid gap-4 md:grid-cols-2">
                {stories.map((story) => <StoryCard key={`${story.id}:${story.format}`} story={story} />)}
              </div>
              <Pagination route={route} totalPages={totalPages} />
            </>
          )}
        </section>
      </div>
    </main>
  );
}

function StoryCard({ story }: { story: SquareStory }) {
  return (
    <article className="group overflow-hidden rounded-2xl border border-border bg-bg-surface transition duration-200 hover:-translate-y-0.5 hover:border-accent/40">
      <a
        href={storyHref(story.format, story.id)}
        target="_blank"
        rel="noopener noreferrer"
        className="flex min-h-48 w-full gap-4 p-4 text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-3px] focus-visible:outline-accent"
        aria-label={`在新标签页打开${story.title}`}
      >
        <BookCover story={story} variant="card" />
        <div className="flex min-w-0 flex-1 flex-col py-0.5">
          <div className="flex items-center justify-between gap-3">
            <span className="rounded-md bg-accent-soft px-2 py-1 text-xs font-semibold text-accent">
              {formatLabel(story.format)}
            </span>
            <span className="shrink-0 text-xs text-text-muted">{displayDate(story.published_at)}</span>
          </div>
          <h3 className="mt-3 line-clamp-1 font-display text-lg font-bold text-text-primary transition-colors group-hover:text-accent">
            {story.title}
          </h3>
          <p className="mt-2 line-clamp-3 text-sm leading-6 text-text-secondary">
            {story.description || '作者暂未留下简介。'}
          </p>
          <div className="mt-auto flex items-center gap-3 pt-3 text-xs text-text-muted">
            <span>{story.chapter_count} 章</span>
            <span className="h-3 w-px bg-border" />
            <span>{formatCount(story.format, story.content_count)}</span>
            <span className="ml-auto inline-flex items-center gap-1 font-medium text-accent opacity-0 transition-opacity group-hover:opacity-100">
              去详情
              <ArrowRight size={14} />
            </span>
          </div>
        </div>
      </a>
    </article>
  );
}

function BookCover({
  story,
  variant,
}: {
  story: Pick<SquareStory, 'title' | 'cover_url'>;
  variant: 'card' | 'detail';
}) {
  const [failed, setFailed] = useState(false);
  const coverUrl = coverImageUrl(story.cover_url);
  const hasCover = Boolean(coverUrl) && !failed;
  const sizeClass = variant === 'detail'
    ? 'h-[240px] w-[172px] rounded-[22px] sm:h-[280px] sm:w-[198px]'
    : 'h-40 w-28 rounded-lg';

  return (
    <div className={`fanqie-book-cover relative shrink-0 overflow-hidden bg-accent-soft ${sizeClass}`}>
      <div className="absolute inset-0 bg-gradient-to-br from-white/14 via-transparent to-black/14" />
      <div className="absolute inset-y-0 left-0 w-5 bg-gradient-to-r from-black/18 to-transparent" />
      {hasCover ? (
        <img
          src={coverUrl!}
          alt={`${story.title}封面`}
          className="h-full w-full object-cover"
          loading="lazy"
          onError={() => setFailed(true)}
        />
      ) : (
        <div className="flex h-full flex-col justify-between p-3 sm:p-4">
          <ImageIcon size={variant === 'detail' ? 26 : 20} className="text-accent/70" />
          <span className={`font-display font-bold leading-5 text-accent ${variant === 'detail' ? 'text-xl' : 'text-base'}`}>
            {story.title}
          </span>
        </div>
      )}
    </div>
  );
}

function Pagination({
  route,
  totalPages,
}: {
  route: Extract<SquareLocation, { kind: 'list' }>;
  totalPages: number;
}) {
  return (
    <div className="mt-8 flex items-center justify-center gap-3">
      <button
        className="flex h-11 items-center gap-1 rounded-lg border border-border px-3 text-sm text-text-secondary transition hover:border-accent disabled:cursor-not-allowed disabled:opacity-40"
        disabled={route.page === 0}
        onClick={() => go({ ...route, page: route.page - 1 })}
      >
        <ChevronLeft size={17} />
        上一页
      </button>
      <span className="text-sm text-text-muted">{route.page + 1} / {Math.max(totalPages, 1)}</span>
      <button
        className="flex h-11 items-center gap-1 rounded-lg border border-border px-3 text-sm text-text-secondary transition hover:border-accent disabled:cursor-not-allowed disabled:opacity-40"
        disabled={route.page + 1 >= totalPages}
        onClick={() => go({ ...route, page: route.page + 1 })}
      >
        下一页
        <ChevronRight size={17} />
      </button>
    </div>
  );
}

function Empty({
  icon,
  text,
  action,
  onAction,
}: {
  icon: ReactNode;
  text: string;
  action: string;
  onAction: () => void;
}) {
  return (
    <div className="flex min-h-72 flex-col items-center justify-center rounded-2xl border border-dashed border-border bg-bg-surface/50 px-4 text-center text-text-muted">
      <div className="animate-float [&>svg]:h-12 [&>svg]:w-12 [&>svg]:text-accent/40">{icon}</div>
      <p className="mt-5 text-sm">{text}</p>
      <button
        className="mt-5 min-h-11 rounded-lg bg-accent px-5 text-sm font-medium text-white transition-colors hover:bg-accent-hover"
        onClick={onAction}
      >
        {action}
      </button>
    </div>
  );
}

function StoryView({ route }: { route: Extract<SquareLocation, { kind: 'detail' }> }) {
  const [detail, setDetail] = useState<SquareStoryDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    void getSquareStoryDetail(route.storyId, route.format)
      .then((data) => {
        if (active) setDetail(data);
      })
      .catch(() => {
        if (active) setError('作品详情加载失败或尚未公开。');
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [route.format, route.storyId]);

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Loader2 className="animate-spin text-accent" />
        <span className="ml-2 text-sm text-text-secondary">正在打开作品</span>
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Empty
          icon={<BookOpenText size={42} />}
          text={error}
          action="返回广场"
          onAction={() => go({ kind: 'list', format: route.format, page: 0, query: '' })}
        />
      </div>
    );
  }

  if (route.chapterId == null) {
    return <Detail detail={detail} onRead={(chapterId) => go({ kind: 'detail', format: detail.format, storyId: detail.id, chapterId })} />;
  }

  const current = detail.chapters.find((chapter) => chapter.id === route.chapterId);
  if (!current) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Empty
          icon={<BookOpenText size={42} />}
          text="章节不存在或尚未公开。"
          action="返回作品详情"
          onAction={() => go({ kind: 'detail', format: detail.format, storyId: detail.id })}
        />
      </div>
    );
  }

  return <Reader detail={detail} chapterId={current.id} onBack={() => go({ kind: 'detail', format: detail.format, storyId: detail.id })} />;
}

function Detail({
  detail,
  onRead,
}: {
  detail: SquareStoryDetail;
  onRead: (chapterId: number) => void;
}) {
  const saved = useMemo(() => readReadingState(detail.id, detail.format), [detail.format, detail.id]);
  const chapterCount = detail.chapters.length;
  const totalContent = detail.chapters.reduce((sum, chapter) => sum + chapter.content_count, 0);
  const resume = detail.chapters.find((chapter) => chapter.id === saved.chapterId);
  const firstChapter = detail.chapters[0];
  const latestChapter = detail.chapters[detail.chapters.length - 1];
  const primaryChapter = resume ?? firstChapter ?? null;
  const otherFormats = detail.available_formats.filter((format) => format !== detail.format);
  const intro = detail.description?.trim() || '作者暂未留下简介。';
  const savedProgress = progressPercent(saved.progress);
  const handleBack = () => {
    if (window.history.length > 1) {
      window.history.back();
      return;
    }
    go({ kind: 'list', format: detail.format, page: 0, query: '' }, true);
  };

  return (
    <main className="fanqie-detail-shell flex min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-6xl px-4 py-6 sm:px-6 lg:px-8">
        <button
          className="mb-5 inline-flex min-h-11 items-center gap-2 text-sm text-text-secondary transition hover:text-text-primary"
          onClick={handleBack}
        >
          <ArrowLeft size={18} />
          返回广场
        </button>

        <section className="fanqie-paper-card overflow-hidden">
          <div className="grid gap-8 border-b border-border/80 px-5 py-6 sm:px-8 sm:py-8 xl:grid-cols-[200px_minmax(0,1fr)_240px]">
            <BookCover story={detail} variant="detail" />

            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="fanqie-tag-primary">{formatLabel(detail.format)}</span>
                <span className="fanqie-tag-secondary">{chapterCount} 章</span>
                <span className="fanqie-tag-secondary">{formatCount(detail.format, totalContent)}</span>
              </div>

              <h1 className="mt-4 font-display text-3xl font-bold tracking-tight text-text-primary sm:text-[2.5rem]">
                {detail.title}
              </h1>

              <div className="mt-5 grid gap-3 sm:grid-cols-3">
                <StatCard label="总章节" value={`${chapterCount} 章`} />
                <StatCard label={detail.format === 'novel' ? '全文体量' : '总页数'} value={formatCount(detail.format, totalContent)} />
                <StatCard label="公开时间" value={displayDate(detail.published_at)} />
              </div>

              <div className="mt-5 rounded-2xl border border-border bg-bg-base/55 p-4">
                <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-sm text-text-secondary">
                  <span className="inline-flex items-center gap-2">
                    <Clock3 size={16} className="text-accent" />
                    最近更新
                  </span>
                  {latestChapter ? (
                    <button
                      className="font-medium text-text-primary underline decoration-accent/35 decoration-2 underline-offset-4 transition hover:text-accent"
                      onClick={() => onRead(latestChapter.id)}
                    >
                      {chapterTitle(latestChapter)}
                    </button>
                  ) : (
                    <span className="text-text-muted">暂无更新</span>
                  )}
                  <span className="text-text-muted">{displayDateTime(detail.published_at)}</span>
                </div>

                <p className="mt-4 line-clamp-4 whitespace-pre-wrap text-sm leading-7 text-text-secondary">
                  {intro}
                </p>
              </div>

              <div className="mt-6 flex flex-wrap gap-3">
                {primaryChapter ? (
                  <button className="fanqie-cta-primary" onClick={() => onRead(primaryChapter.id)}>
                    {resume ? `继续阅读：${chapterTitle(resume)}` : '开始阅读'}
                  </button>
                ) : (
                  <span className="inline-flex min-h-11 items-center rounded-xl border border-border px-4 text-sm text-text-muted">
                    暂无可阅读章节
                  </span>
                )}

                {latestChapter && primaryChapter && latestChapter.id !== primaryChapter.id && (
                  <button className="fanqie-cta-secondary" onClick={() => onRead(latestChapter.id)}>
                    阅读最新：{chapterTitle(latestChapter)}
                  </button>
                )}

                {otherFormats.map((format) => (
                  <button
                    key={format}
                    className="fanqie-cta-secondary"
                    onClick={() => go({ kind: 'detail', format, storyId: detail.id })}
                  >
                    查看{formatLabel(format)}版
                  </button>
                ))}
              </div>
            </div>

            <DetailInfoPanel
              className="hidden xl:block"
              detail={detail}
              resume={resume}
              latestChapter={latestChapter}
              savedProgress={savedProgress}
            />
          </div>

          <DetailInfoPanel
            className="border-b border-border/80 px-5 py-5 sm:px-8 xl:hidden"
            detail={detail}
            resume={resume}
            latestChapter={latestChapter}
            savedProgress={savedProgress}
          />

          <div className="px-5 py-6 sm:px-8 sm:py-7">
            <SectionHeading title="作品简介" subtitle="阅读前先看一眼故事底色与题材氛围。" />
            <p className="fanqie-intro-copy mt-5 whitespace-pre-wrap">{intro}</p>
          </div>
        </section>

        <section className="fanqie-paper-card mt-6 overflow-hidden">
          <div className="border-b border-border/80 px-5 py-6 sm:px-8">
            <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
              <SectionHeading
                title="章节目录"
                subtitle={`${chapterCount} 章 · ${formatCount(detail.format, totalContent)}`}
              />
              {primaryChapter && (
                <button className="fanqie-cta-secondary self-start md:self-auto" onClick={() => onRead(primaryChapter.id)}>
                  {resume ? '回到上次阅读' : '从第一章开始'}
                </button>
              )}
            </div>
          </div>

          {detail.chapters.length === 0 ? (
            <div className="px-5 py-8 sm:px-8">
              <Empty
                icon={<LibraryBig size={42} />}
                text="作品还没有公开章节。"
                action="返回广场"
                onAction={() => go({ kind: 'list', format: detail.format, page: 0, query: '' })}
              />
            </div>
          ) : (
            <ol className="grid gap-x-8 px-5 pb-3 pt-2 sm:px-8 md:grid-cols-2 xl:grid-cols-3">
              {detail.chapters.map((chapter) => (
                <li key={chapter.id} className="border-b border-border/75">
                  <button
                    className="group flex min-h-20 w-full items-center justify-between gap-3 py-4 text-left"
                    onClick={() => onRead(chapter.id)}
                  >
                    <span className="min-w-0">
                      <span className="block text-[0.6875rem] uppercase tracking-[0.18em] text-text-muted">
                        {chapterMetaLabel(chapter, detail.format)}
                      </span>
                      <span className="mt-1 block truncate text-[0.9375rem] text-text-primary transition-colors group-hover:text-accent">
                        {chapterTitle(chapter)}
                      </span>
                    </span>
                    <span className="shrink-0 text-xs text-text-muted transition group-hover:text-accent">
                      阅读
                    </span>
                  </button>
                </li>
              ))}
            </ol>
          )}
        </section>
      </div>
    </main>
  );
}

function DetailInfoPanel({
  detail,
  resume,
  latestChapter,
  savedProgress,
  className,
}: {
  detail: SquareStoryDetail;
  resume: SquareStoryDetail['chapters'][number] | undefined;
  latestChapter: SquareStoryDetail['chapters'][number] | undefined;
  savedProgress: number;
  className?: string;
}) {
  const items = [
    { label: '上次阅读', value: resume ? chapterTitle(resume) : '尚未开始' },
    { label: '阅读进度', value: `${savedProgress}%` },
    { label: '最近更新', value: latestChapter ? chapterTitle(latestChapter) : '暂无更新' },
    { label: '开放形态', value: detail.available_formats.map(formatLabel).join(' / ') || formatLabel(detail.format) },
  ];

  return (
    <aside className={`fanqie-side-note ${className ?? ''}`}>
      <div className="flex items-center gap-2 text-sm font-semibold text-text-primary">
        <Sparkles size={16} className="text-accent" />
        阅读信息
      </div>

      <div className="mt-4 space-y-4">
        {items.map((item) => (
          <div key={item.label}>
            <div className="text-xs uppercase tracking-[0.18em] text-text-muted">{item.label}</div>
            <div className="mt-1 text-sm leading-6 text-text-primary">{item.value}</div>
          </div>
        ))}
      </div>

      <div className="mt-5">
        <div className="flex items-center justify-between text-xs text-text-muted">
          <span>当前阅读记忆</span>
          <span>{savedProgress}%</span>
        </div>
        <div className="mt-2 h-2 overflow-hidden rounded-full bg-accent-soft">
          <div className="h-full rounded-full bg-accent transition-[width] duration-300" style={{ width: `${savedProgress}%` }} />
        </div>
      </div>
    </aside>
  );
}

function SectionHeading({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <div>
      <h2 className="font-display text-[1.9rem] font-bold tracking-tight text-text-primary">{title}</h2>
      <p className="mt-2 text-sm text-text-muted">{subtitle}</p>
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-2xl border border-border bg-bg-base/55 px-4 py-3">
      <div className="text-xs uppercase tracking-[0.18em] text-text-muted">{label}</div>
      <div className="mt-2 text-lg font-semibold text-text-primary">{value}</div>
    </div>
  );
}

function parseParagraphs(content: string | null): string[] {
  if (!content) return [];
  return content
    .split('\n')
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
}

function Reader({
  detail,
  chapterId,
  onBack,
}: {
  detail: SquareStoryDetail;
  chapterId: number;
  onBack: () => void;
}) {
  const index = detail.chapters.findIndex((chapter) => chapter.id === chapterId);
  const chapter = detail.chapters[index];
  const savedState = useMemo(() => readReadingState(detail.id, detail.format), [detail.format, detail.id]);
  const progressRef = useRef<HTMLDivElement>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [fontSize, setFontSize] = useState(savedState.fontSize ?? 18);
  const [lineHeight, setLineHeight] = useState(savedState.lineHeight ?? 1.8);
  const [width, setWidth] = useState(savedState.width ?? 800);
  const { mode, toggleMode } = useTheme();

  useEffect(() => {
    setFontSize(savedState.fontSize ?? 18);
    setLineHeight(savedState.lineHeight ?? 1.8);
    setWidth(savedState.width ?? 800);
  }, [savedState.fontSize, savedState.lineHeight, savedState.width]);

  useEffect(() => {
    const key = readingKey(detail.id, detail.format);
    const onScroll = () => {
      const el = progressRef.current;
      if (!el) return;

      const progress = el.scrollHeight > el.clientHeight
        ? el.scrollTop / (el.scrollHeight - el.clientHeight)
        : 0;

      localStorage.setItem(key, JSON.stringify({
        chapterId,
        progress,
        fontSize,
        lineHeight,
        width,
      }));
    };

    const el = progressRef.current;
    el?.addEventListener('scroll', onScroll, { passive: true });
    return () => el?.removeEventListener('scroll', onScroll);
  }, [chapterId, detail.format, detail.id, fontSize, lineHeight, width]);

  useEffect(() => {
    const el = progressRef.current;
    if (!el) return;

    const restore = savedState.chapterId === chapterId ? savedState.progress ?? 0 : 0;
    const frame = window.requestAnimationFrame(() => {
      window.requestAnimationFrame(() => {
        if (restore > 0 && el.scrollHeight > el.clientHeight) {
          el.scrollTop = restore * (el.scrollHeight - el.clientHeight);
          return;
        }
        el.scrollTop = 0;
      });
    });

    return () => window.cancelAnimationFrame(frame);
  }, [chapterId, fontSize, lineHeight, savedState.chapterId, savedState.progress, width]);

  const navigate = (offset: -1 | 1) => {
    const next = detail.chapters[index + offset];
    if (next) go({ kind: 'detail', format: detail.format, storyId: detail.id, chapterId: next.id });
  };

  // Keyboard navigation
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement || e.target instanceof HTMLSelectElement) return;
      if (e.key === 'ArrowLeft') {
        e.preventDefault();
        navigate(-1);
      } else if (e.key === 'ArrowRight') {
        e.preventDefault();
        navigate(1);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index, detail.chapters]);

  const hasContent = detail.format === 'novel'
    ? Boolean(chapter.content?.trim())
    : chapter.images.length > 0;

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
      <header className="border-b border-border bg-bg-surface/95 backdrop-blur">
        <div className="mx-auto flex min-h-14 max-w-5xl items-center justify-between gap-3 px-3 sm:px-6">
          <button className="flex min-h-10 items-center gap-1 rounded-lg px-2 text-sm text-text-secondary transition hover:text-text-primary hover:bg-accent-soft" onClick={onBack}>
            <ChevronLeft size={18} />
            <span className="hidden sm:inline">详情</span>
          </button>
          <div className="min-w-0 text-center">
            <div className="truncate text-sm font-semibold text-text-primary">{chapterTitle(chapter)}</div>
            <div className="truncate text-xs text-text-muted">{detail.title} · {chapterMetaLabel(chapter, detail.format)}</div>
          </div>
          <button
            aria-label="阅读设置"
            className="flex h-10 w-10 items-center justify-center rounded-lg border border-border text-text-secondary transition hover:border-accent hover:text-accent"
            onClick={() => setSettingsOpen((value) => !value)}
          >
            <Settings2 size={17} />
          </button>
        </div>
      </header>

      {settingsOpen && (
        <div className="border-b border-border bg-bg-surface">
          <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-center gap-4 px-4 py-3 text-sm sm:px-6">
            <div className="flex items-center gap-2">
              <span className="text-xs text-text-muted">字号</span>
              <button
                className="flex h-8 w-8 items-center justify-center rounded-lg border border-border text-text-secondary transition hover:border-accent hover:text-accent disabled:opacity-30"
                disabled={fontSize <= 14}
                onClick={() => setFontSize((v) => v - 2)}
                aria-label="缩小字号"
              >
                A<span className="text-[0.625rem]">-</span>
              </button>
              <span className="min-w-[2.5ch] text-center text-sm font-medium tabular-nums">{fontSize}</span>
              <button
                className="flex h-8 w-8 items-center justify-center rounded-lg border border-border text-text-secondary transition hover:border-accent hover:text-accent disabled:opacity-30"
                disabled={fontSize >= 28}
                onClick={() => setFontSize((v) => v + 2)}
                aria-label="放大字号"
              >
                A<span className="text-[0.625rem]">+</span>
              </button>
            </div>
            <label className="fanqie-setting-field">
              <span>行距</span>
              <select value={lineHeight} onChange={(event) => setLineHeight(Number(event.target.value))}>
                {[1.6, 1.8, 2.0, 2.2, 2.4].map((value) => <option key={value} value={value}>{value}</option>)}
              </select>
            </label>
            <label className="fanqie-setting-field">
              <span>宽度</span>
              <select value={width} onChange={(event) => setWidth(Number(event.target.value))}>
                {[680, 760, 840, 920].map((value) => <option key={value} value={value}>{value}px</option>)}
              </select>
            </label>
            <button
              className="fanqie-setting-field cursor-pointer hover:border-accent transition"
              onClick={toggleMode}
              aria-label="切换日间/夜间模式"
            >
              <span>主题</span>
              <span className="text-text-primary text-xs">
                {mode === 'light' ? '☀️ 日间' : '🌙 夜间'}
              </span>
            </button>
          </div>
        </div>
      )}

      <main ref={progressRef} className="flex-1 overflow-y-auto px-4 py-6 sm:px-6 sm:py-8">
        <article className="mx-auto w-full" style={{ maxWidth: detail.format === 'novel' ? width : 'min(920px, 100%)' }}>
          <header className="fanqie-reader-header">
            <div className="text-xs uppercase tracking-[0.2em] text-text-muted">
              {detail.title}
            </div>
            <h1 className="mt-3 font-display text-3xl font-bold text-text-primary">{chapterTitle(chapter)}</h1>
            <div className="mt-3 text-sm text-text-muted">{chapterMetaLabel(chapter, detail.format)}</div>
          </header>

          {hasContent ? (
            detail.format === 'novel' ? (
              <div className="fanqie-reader-page mt-6">
                <div
                  className="fanqie-reader-copy text-text-primary"
                  style={{ fontSize: `${fontSize / 16}rem`, lineHeight }}
                >
                  {parseParagraphs(chapter.content).map((paragraph, i) => (
                    <p key={i} className="reader-paragraph">{paragraph}</p>
                  ))}
                </div>
              </div>
            ) : (
              <div className="mt-6 space-y-4">
                {chapter.images.map((image) => (
                  <div key={image.id} className="fanqie-reader-page overflow-hidden p-2 sm:p-3">
                    <img
                      className="mx-auto block max-w-full rounded-xl"
                      src={mangaImageUrl(image.image_url)}
                      alt={`${chapterTitle(chapter)}第 ${image.image_number} 页`}
                      loading="lazy"
                    />
                  </div>
                ))}
              </div>
            )
          ) : (
            <div className="mt-6">
              <Empty
                icon={<BookOpenText size={42} />}
                text="本章节暂时没有可阅读内容。"
                action="返回作品详情"
                onAction={onBack}
              />
            </div>
          )}

          {hasContent && (
            <div className="mt-12 flex flex-col items-center gap-3 pb-2">
              <span className="seal-stamp h-11! min-w-11! text-base!">完</span>
              <div className="text-xs tracking-[0.2em] text-text-muted">
                {detail.title}{detail.published_at ? ` · ${displayDate(detail.published_at)}` : ''}
              </div>
            </div>
          )}

          <nav className="fanqie-reader-footer mt-8">
            <button
              disabled={index === 0}
              className="fanqie-reader-nav disabled:opacity-40"
              onClick={() => navigate(-1)}
              aria-label="上一章"
            >
              <ArrowLeft size={18} />
              <span className="hidden sm:inline">上一章</span>
            </button>

            <label className="fanqie-reader-select">
              <span className="sr-only">切换章节</span>
              <select
                aria-label="切换章节"
                value={chapterId}
                onChange={(event) => go({
                  kind: 'detail',
                  format: detail.format,
                  storyId: detail.id,
                  chapterId: Number(event.target.value),
                })}
              >
                {detail.chapters.map((item) => (
                  <option key={item.id} value={item.id}>{chapterTitle(item)}</option>
                ))}
              </select>
            </label>

            <button
              disabled={index >= detail.chapters.length - 1}
              className="fanqie-reader-nav disabled:opacity-40"
              onClick={() => navigate(1)}
              aria-label="下一章"
            >
              <span className="hidden sm:inline">下一章</span>
              <ArrowRight size={18} />
            </button>
          </nav>

          <div className="mt-4 flex items-center justify-center gap-4 text-xs text-text-muted">
            <span>💡 键盘左右键切换章节</span>
          </div>
        </article>
      </main>
    </div>
  );
}
