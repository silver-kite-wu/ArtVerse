import type { ChangeEvent } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Copy,
  Download,
  Edit3,
  GripVertical,
  ImagePlus,
  Loader2,
  Plus,
  Send,
  Settings2,
  Trash2,
  X,
  Palette,
  ChevronLeft,
  ChevronRight,
  Check,
} from 'lucide-react';
import {
  deleteImageGenRecord,
  generateImage,
  imageGenUrl,
  listImageGenHistory,
  type ImageGenRecord,
} from '../api';

interface Message {
  id: string;
  type: 'user' | 'ai';
  prompt?: string;
  refThumbnails?: string[];
  record?: ImageGenRecord;
}

interface GenTheme {
  id: string;
  name: string;
  createdAt: string;
  messages: Message[];
}

type RefFile = { file: File; preview: string };

interface GenConfig {
  resolution: string;
  aspectRatio: string;
}

const LS_THEMES_KEY = 'artverse.genThemes';
const LS_ACTIVE_THEME_KEY = 'artverse.activeGenTheme';
const LS_GEN_CONFIG_KEY = 'artverse.genConfig';
const LS_CANVAS_OPEN_KEY = 'artverse.genCanvasOpen';
const LS_CANVAS_WIDTH_KEY = 'artverse.genCanvasWidth';

const RESOLUTIONS = [
  { label: '1024×1024', value: '1024x1024', ratio: '1:1' },
  { label: '1152×864', value: '1152x864', ratio: '4:3' },
  { label: '864×1152', value: '864x1152', ratio: '3:4' },
  { label: '1280×720', value: '1280x720', ratio: '16:9' },
  { label: '720×1280', value: '720x1280', ratio: '9:16' },
  { label: '1344×768', value: '1344x768', ratio: '16:9' },
  { label: '768×1344', value: '768x1344', ratio: '9:16' },
];

const ASPECT_RATIOS = [
  { label: '1:1', value: '1:1', sub: '正方形' },
  { label: '4:3', value: '4:3', sub: '横屏' },
  { label: '3:4', value: '3:4', sub: '竖屏' },
  { label: '16:9', value: '16:9', sub: '宽屏' },
  { label: '9:16', value: '9:16', sub: '长屏' },
];

const DEFAULT_CONFIG: GenConfig = { resolution: '1024x1024', aspectRatio: '1:1' };

function generateId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

function loadThemes(): GenTheme[] {
  try {
    const raw = localStorage.getItem(LS_THEMES_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as GenTheme[];
  } catch {
    return [];
  }
}

function saveThemes(themes: GenTheme[]) {
  localStorage.setItem(LS_THEMES_KEY, JSON.stringify(themes));
}

function loadActiveThemeId(): string | null {
  return localStorage.getItem(LS_ACTIVE_THEME_KEY);
}

function saveActiveThemeId(id: string) {
  localStorage.setItem(LS_ACTIVE_THEME_KEY, id);
}

function loadGenConfig(): GenConfig {
  try {
    const raw = localStorage.getItem(LS_GEN_CONFIG_KEY);
    if (!raw) return DEFAULT_CONFIG;
    return JSON.parse(raw) as GenConfig;
  } catch {
    return DEFAULT_CONFIG;
  }
}

function saveGenConfig(config: GenConfig) {
  localStorage.setItem(LS_GEN_CONFIG_KEY, JSON.stringify(config));
}

function fmtRes(v: string | null | undefined): string {
  if (!v) return '';
  return v.split('x').join('×');
}

function AspectRatioLabel({ aspectRatio }: { aspectRatio: string }) {
  const found = ASPECT_RATIOS.find((a) => a.value === aspectRatio);
  if (!found) return <span className="text-cream-dim">{aspectRatio}</span>;
  return (
    <span className="text-cream-dim">
      {found.value} {found.sub}
    </span>
  );
}

function ConfigPopover({
  config,
  onChange,
  onClose,
}: {
  config: GenConfig;
  onChange: (config: GenConfig) => void;
  onClose: () => void;
}) {
  const handleResolutionSelect = (value: string, ratio: string) => {
    onChange({ resolution: value, aspectRatio: ratio });
  };

  const handleAspectRatioSelect = (value: string) => {
    const matching = RESOLUTIONS.find((r) => r.ratio === value);
    if (matching) {
      onChange({ resolution: matching.value, aspectRatio: value });
    } else {
      onChange({ ...config, aspectRatio: value });
    }
  };

  return (
    <div className="absolute left-0 bottom-full mb-2 z-50 w-72 origin-bottom-left animate-fade-in">
      <div className="overflow-hidden rounded-2xl border border-ink-border bg-ink-light shadow-2xl shadow-black/50">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-ink-border px-4 py-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-cream">
            <Settings2 size={14} className="text-coral" />
            图片配置
          </h3>
          <button onClick={onClose} className="text-warm-gray hover:text-cream transition-colors" aria-label="关闭">
            <X size={14} />
          </button>
        </div>

        <div className="max-h-80 overflow-y-auto px-4 py-3 space-y-4">
          {/* Resolution */}
          <div>
            <div className="mb-2 text-xs font-medium text-cream-dim uppercase tracking-wider">分辨率</div>
            <div className="grid grid-cols-2 gap-1.5">
              {RESOLUTIONS.map((res) => (
                <button
                  key={res.value}
                  onClick={() => handleResolutionSelect(res.value, res.ratio)}
                  className={
                    'flex items-center justify-between rounded-xl border px-3 py-2 text-xs transition-all duration-150 ' +
                    (config.resolution === res.value
                      ? 'border-coral/40 bg-coral/10 text-coral'
                      : 'border-ink-border text-cream-dim hover:border-ink-muted hover:text-cream bg-ink')
                  }
                >
                  <span>{res.label}</span>
                  {config.resolution === res.value && <Check size={12} className="shrink-0" />}
                </button>
              ))}
            </div>
          </div>

          {/* Aspect Ratio */}
          <div>
            <div className="mb-2 text-xs font-medium text-cream-dim uppercase tracking-wider">宽高比</div>
            <div className="flex flex-wrap gap-1.5">
              {ASPECT_RATIOS.map((ar) => (
                <button
                  key={ar.value}
                  onClick={() => handleAspectRatioSelect(ar.value)}
                  className={
                    'flex flex-col items-center gap-1 rounded-xl border px-3 py-2 text-xs transition-all duration-150 min-w-[64px] ' +
                    (config.aspectRatio === ar.value
                      ? 'border-coral/40 bg-coral/10 text-coral'
                      : 'border-ink-border text-cream-dim hover:border-ink-muted hover:text-cream bg-ink')
                  }
                >
                  <svg viewBox="0 0 20 20" className="w-[18px] h-[18px] fill-none stroke-current stroke-[1.5] opacity-70">
                    <rect x={0} y={0} width={20} height={20} rx={1.5} className={ar.value === '1:1' ? '' : 'hidden'} />
                    <rect x={0} y={2.5} width={20} height={15} rx={1.5} className={ar.value === '4:3' ? '' : 'hidden'} />
                    <rect x={2.5} y={0} width={15} height={20} rx={1.5} className={ar.value === '3:4' ? '' : 'hidden'} />
                    <rect x={0} y={4} width={20} height={12} rx={1.5} className={ar.value === '16:9' ? '' : 'hidden'} />
                    <rect x={4} y={0} width={12} height={20} rx={1.5} className={ar.value === '9:16' ? '' : 'hidden'} />
                  </svg>
                  <span className="font-medium leading-tight">{ar.label}</span>
                  <span className="text-[10px] opacity-60 leading-tight">{ar.sub}</span>
                </button>
              ))}
            </div>
          </div>

          {/* Current config summary */}
          <div className="rounded-xl border border-ink-border bg-ink px-3 py-2 text-xs text-cream-dim">
            当前配置：{fmtRes(config.resolution)} · <AspectRatioLabel aspectRatio={config.aspectRatio} />
          </div>
        </div>
      </div>
    </div>
  );
}

function Composer({
  compact = false,
  refFiles,
  prompt,
  config,
  generating,
  canSend,
  onPromptChange,
  onAddRef,
  onRemoveRef,
  onSend,
  onConfigChange,
  onPasteImage,
}: {
  compact?: boolean;
  refFiles: RefFile[];
  prompt: string;
  config: GenConfig;
  generating: boolean;
  canSend: boolean;
  onPromptChange: (value: string) => void;
  onAddRef: (e: ChangeEvent<HTMLInputElement>) => void;
  onRemoveRef: (idx: number) => void;
  onSend: () => void;
  onConfigChange: (config: GenConfig) => void;
  onPasteImage?: (file: File) => void;
}) {
  const [configOpen, setConfigOpen] = useState(false);
  const [pasteToast, setPasteToast] = useState(false);

  const handlePaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const items = e.clipboardData.items;
    let pastedImage: File | null = null;
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (item.type.startsWith('image/')) {
        const file = item.getAsFile();
        if (file && file.size <= 10 * 1024 * 1024) {
          pastedImage = file;
          break;
        }
      }
    }
    if (pastedImage && onPasteImage) {
      e.preventDefault();
      onPasteImage(pastedImage);
      setPasteToast(true);
      setTimeout(() => setPasteToast(false), 2000);
    }
  };

  return (
    <div className={compact ? 'w-full' : 'w-full max-w-5xl mx-auto'}>
      {/* Split the card: text section has overflow-hidden, footer does not */}
      <div className="rounded-2xl border border-ink-border shadow-2xl shadow-coral/5">
        <div className="overflow-hidden rounded-t-2xl bg-ink-light/85">
          <div className="relative p-4 sm:p-5">
            {refFiles.length > 0 && (
              <div className="mb-3 flex flex-wrap gap-2">
                {refFiles.map((rf, i) => (
                  <div key={i} className="relative h-14 w-14 shrink-0 overflow-hidden rounded-xl border border-ink-border bg-ink-lighter">
                    <img src={rf.preview} alt={`参考图 ${i + 1}`} className="h-full w-full object-cover" />
                    <button
                      onClick={() => onRemoveRef(i)}
                      className="absolute right-0 top-0 rounded-bl-md bg-sumi/60 p-0.5 text-white"
                      aria-label={`移除参考图 ${i + 1}`}
                    >
                      <X size={10} />
                    </button>
                  </div>
                ))}
              </div>
            )}

            <textarea
              value={prompt}
              onChange={(e) => onPromptChange(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  onSend();
                }
              }}
              onPaste={handlePaste}
              placeholder="描述你想生成的画面、风格、主体和细节（支持粘贴图片作为参考图）"
              disabled={generating}
              rows={compact ? 4 : 5}
              className="w-full resize-none bg-transparent text-[17px] leading-7 text-cream outline-none placeholder:text-cream-dim"
            />
            {pasteToast && (
              <div className="absolute right-4 top-4 z-10 animate-fade-in rounded-lg bg-coral/90 px-3 py-1.5 text-xs text-white shadow-lg">
                已添加参考图
              </div>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2 border-t border-ink-border px-3 py-3 sm:px-4 bg-ink-light/85 rounded-b-2xl">
          <label className={'flex cursor-pointer items-center gap-2 rounded-xl border border-ink-border px-3 py-2 text-cream-dim transition-colors hover:bg-ink-lighter ' + (refFiles.length >= 3 ? 'pointer-events-none opacity-40' : '')}>
            <ImagePlus size={16} />
            <span className="text-sm">参考图</span>
            <input type="file" accept="image/*" multiple onChange={onAddRef} className="hidden" />
          </label>

          {/* Config button */}
          <div className="relative">
            <button
              type="button"
              onClick={() => setConfigOpen(!configOpen)}
              className="flex items-center gap-1.5 rounded-xl border border-ink-border px-3 py-2 text-cream-dim transition-colors hover:bg-ink-lighter hover:text-cream"
              title="图片配置"
            >
              <Settings2 size={15} />
              <span className="text-sm hidden sm:inline">{fmtRes(config.resolution)}</span>
            </button>
            {configOpen && (
              <ConfigPopover
                config={config}
                onChange={(c) => { onConfigChange(c); setConfigOpen(false); }}
                onClose={() => setConfigOpen(false)}
              />
            )}
          </div>

          <button
            type="button"
            onClick={onSend}
            disabled={!canSend}
            className="ml-auto inline-flex h-10 w-10 items-center justify-center rounded-xl bg-coral text-cream transition-colors hover:bg-coral-light disabled:cursor-not-allowed disabled:opacity-30"
            aria-label={generating ? '正在生成' : '发送生成请求'}
          >
            {generating ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
          </button>
        </div>
      </div>
    </div>
  );
}

function ThemeSidebar({
  themes,
  activeThemeId,
  onSelectTheme,
  onCreateTheme,
  onDeleteTheme,
  onRenameTheme,
  sidebarOpen,
  onToggleSidebar,
}: {
  themes: GenTheme[];
  activeThemeId: string | null;
  onSelectTheme: (id: string) => void;
  onCreateTheme: () => void;
  onDeleteTheme: (id: string) => void;
  onRenameTheme: (id: string, name: string) => void;
  sidebarOpen: boolean;
  onToggleSidebar: () => void;
}) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const editInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (editingId && editInputRef.current) {
      editInputRef.current.focus();
      editInputRef.current.select();
    }
  }, [editingId]);

  const handleStartRename = (theme: GenTheme) => {
    setEditingId(theme.id);
    setEditName(theme.name);
  };

  const handleFinishRename = () => {
    if (editingId && editName.trim()) {
      onRenameTheme(editingId, editName.trim());
    }
    setEditingId(null);
    setEditName('');
  };

  return (
    <aside className={'flex shrink-0 flex-col border-r border-ink-border bg-ink-light transition-all duration-300 ' + (sidebarOpen ? 'w-56' : 'w-0 overflow-hidden')}>
      <div className="flex h-14 items-center justify-between border-b border-ink-border px-3">
        <span className="flex items-center gap-1.5 text-sm font-bold tracking-wide text-coral">
          <Palette size={14} />
          主题列表
        </span>
        <button onClick={onToggleSidebar} className="text-warm-gray hover:text-cream transition-colors" aria-label="收起侧边栏">
          <ChevronLeft size={16} />
        </button>
      </div>

      <div className="px-2 py-3">
        <button
          onClick={onCreateTheme}
          className="flex w-full items-center gap-2 rounded-lg border border-dashed border-ink-muted px-3 py-2.5 text-sm font-medium text-cream-dim hover:border-coral/40 hover:text-coral transition-colors"
        >
          <Plus size={15} />
          创作新主题
        </button>
      </div>

      <nav className="flex-1 overflow-y-auto px-2 pb-3 space-y-0.5">
        {themes.length === 0 && (
          <p className="px-3 py-4 text-xs text-ink-muted text-center">暂无主题</p>
        )}
        {themes.map((theme) => (
          <div key={theme.id} className="group relative">
            <button
              onClick={() => onSelectTheme(theme.id)}
              className={
                'w-full rounded-lg px-3 py-2.5 text-left text-sm transition-all duration-200 flex items-center gap-1 ' +
                (theme.id === activeThemeId
                  ? 'bg-coral/10 text-coral border border-coral/20'
                  : 'text-cream-dim hover:bg-ink-lighter hover:text-cream border border-transparent')
              }
            >
              {editingId === theme.id ? (
                <input
                  ref={editInputRef}
                  value={editName}
                  onChange={(e) => setEditName(e.target.value)}
                  onBlur={handleFinishRename}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') handleFinishRename();
                    if (e.key === 'Escape') setEditingId(null);
                  }}
                  className="min-w-0 flex-1 bg-ink rounded px-1 py-0.5 text-sm text-cream outline-none border border-coral/40"
                  onClick={(e) => e.stopPropagation()}
                />
              ) : (
                <span className="flex-1 truncate">{theme.name}</span>
              )}
              {editingId !== theme.id && theme.id === activeThemeId && (
                <span className="hidden group-hover:flex items-center gap-0.5 shrink-0 ml-1">
                  <span
                    role="button"
                    tabIndex={0}
                    onClick={(e) => { e.stopPropagation(); e.preventDefault(); handleStartRename(theme); }}
                    onKeyDown={(e) => { if (e.key === 'Enter') handleStartRename(theme); }}
                    className="rounded p-0.5 text-ink-muted hover:text-cream hover:bg-ink-lighter transition-colors cursor-pointer"
                    title="重命名"
                  >
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>
                    </svg>
                  </span>
                  <span
                    role="button"
                    tabIndex={0}
                    onClick={(e) => { e.stopPropagation(); e.preventDefault(); onDeleteTheme(theme.id); }}
                    onKeyDown={(e) => { if (e.key === 'Enter') onDeleteTheme(theme.id); }}
                    className="rounded p-0.5 text-ink-muted hover:text-coral hover:bg-ink-lighter transition-colors cursor-pointer"
                    title="删除主题"
                  >
                    <X size={12} />
                  </span>
                </span>
              )}
            </button>
          </div>
        ))}
      </nav>
    </aside>
  );
}

export default function ImageGenPage() {
  const [themes, setThemes] = useState<GenTheme[]>([]);
  const [activeThemeId, setActiveThemeId] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(true);
  const [prompt, setPrompt] = useState('');
  const [refFiles, setRefFiles] = useState<RefFile[]>([]);
  const [generatingThemes, setGeneratingThemes] = useState<Record<string, boolean>>({});
  const [config, setConfig] = useState<GenConfig>(DEFAULT_CONFIG);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [canvasOpen, setCanvasOpen] = useState(() => {
    try {
      return localStorage.getItem(LS_CANVAS_OPEN_KEY) === 'true';
    } catch {
      return false;
    }
  });
  const [canvasWidth, setCanvasWidth] = useState(() => {
    try {
      const saved = localStorage.getItem(LS_CANVAS_WIDTH_KEY);
      if (saved) {
        const w = parseInt(saved, 10);
        if (!isNaN(w) && w >= 360 && w <= 1200) return w;
      }
    } catch { /* ignore */ }
    return 520;
  });
  const [excalidrawKey, setExcalidrawKey] = useState(0);
  const [pasteHint, setPasteHint] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const canvasPanelRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const dragRef = useRef<{
    isDragging: boolean;
    lastX: number;
    currentWidth: number;
  }>({ isDragging: false, lastX: 0, currentWidth: 0 });

  function copyImageToClipboard(imageUrl: string, recordId: number) {
    const fullUrl = imageUrl.startsWith('http') ? imageUrl : window.location.origin + imageUrl;
    fetch(fullUrl)
      .then((r) => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.blob();
      })
      .then((blob) => createImageBitmap(blob))
      .then((bitmap) => {
        // Render to canvas → PNG blob (universal clipboard format)
        const canvas = document.createElement('canvas');
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('No 2D context');
        ctx.drawImage(bitmap, 0, 0);
        bitmap.close();
        return new Promise<Blob>((resolve, reject) => {
          canvas.toBlob((b) => {
            canvas.remove();
            if (b) resolve(b);
            else reject(new Error('toBlob returned null'));
          }, 'image/png');
        });
      })
      .then((pngBlob) => {
        if (!navigator.clipboard) throw new Error('navigator.clipboard unavailable');
        return navigator.clipboard.write([new ClipboardItem({ 'image/png': pngBlob })]).then(() => {
          setCopiedId(String(recordId));
          setTimeout(() => setCopiedId(null), 1500);
        });
      })
      .catch((e) => {
        console.warn('copyImageToClipboard failed:', e);
        // Fallback: copy image URL text
        const ta = document.createElement('textarea');
        ta.value = fullUrl;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        try { document.execCommand('copy'); } catch {}
        document.body.removeChild(ta);
        setCopiedId(recordId);
        setTimeout(() => setCopiedId(null), 1500);
      });
  }

  // Initialize: load themes and config from localStorage
  useEffect(() => {
    let stored = loadThemes();
    if (stored.length === 0) {
      // First visit: seed default theme from backend history
      (async () => {
        const defaultTheme: GenTheme = {
          id: generateId(),
          name: '默认主题',
          createdAt: new Date().toISOString(),
          messages: [],
        };
        try {
          const r = await listImageGenHistory(0, 50);
          const msgs: Message[] = [];
          for (const record of [...r.content].reverse()) {
            msgs.push({ id: 'u-' + record.id, type: 'user', prompt: record.prompt });
            msgs.push({ id: 'a-' + record.id, type: 'ai', record });
          }
          defaultTheme.messages = msgs;
        } catch {
          // Empty history is fine.
        }
        stored = [defaultTheme];
        saveThemes(stored);
        setThemes(stored);
        setActiveThemeId(defaultTheme.id);
        setConfig(loadGenConfig());
        setLoaded(true);
        setLoading(false);
      })();
    } else {
      const savedThemeId = loadActiveThemeId();
      const activeId = savedThemeId && stored.some((t) => t.id === savedThemeId)
        ? savedThemeId
        : stored[0].id;
      setThemes(stored);
      setActiveThemeId(activeId);
      setConfig(loadGenConfig());
      setLoaded(true);
      setLoading(false);
    }
  }, []);

  // When themes change, persist to localStorage
  useEffect(() => {
    if (!loaded) return;
    saveThemes(themes);
    if (activeThemeId) saveActiveThemeId(activeThemeId);
  }, [themes, activeThemeId, loaded]);

  // Persist config
  useEffect(() => {
    if (!loaded) return;
    saveGenConfig(config);
  }, [config, loaded]);

  // Persist canvas open state
  useEffect(() => {
    localStorage.setItem(LS_CANVAS_OPEN_KEY, String(canvasOpen));
  }, [canvasOpen]);

  // Persist canvas width
  useEffect(() => {
    localStorage.setItem(LS_CANVAS_WIDTH_KEY, String(canvasWidth));
  }, [canvasWidth]);

  // Derived states
  const isGenerating = useMemo(
    () => Object.values(generatingThemes).some(Boolean),
    [generatingThemes],
  );
  const isActiveThemeGenerating = activeThemeId ? !!generatingThemes[activeThemeId] : false;

  // Scroll to bottom on new messages
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [themes, activeThemeId]);

  const activeTheme = useMemo(
    () => themes.find((t) => t.id === activeThemeId) ?? null,
    [themes, activeThemeId],
  );

  const messages = activeTheme?.messages ?? [];
  const hasMessages = messages.length > 0;

  const canSend = useMemo(
    () => !isGenerating && (prompt.trim().length > 0 || refFiles.length > 0),
    [isGenerating, prompt, refFiles.length],
  );

  const handleCreateTheme = () => {
    const now = new Date().toISOString();
    const count = themes.length + 1;
    const newTheme: GenTheme = {
      id: generateId(),
      name: `新主题 ${count}`,
      createdAt: now,
      messages: [],
    };
    setThemes((prev) => [...prev, newTheme]);
    setActiveThemeId(newTheme.id);
    setPrompt('');
    setRefFiles([]);
  };

  const handleDeleteTheme = (id: string) => {
    const isDeletingActive = id === activeThemeId;
    const remaining = themes.filter((t) => t.id !== id);

    if (remaining.length === 0) {
      const newId = generateId();
      setThemes([{ id: newId, name: '默认主题', createdAt: new Date().toISOString(), messages: [] }]);
      setActiveThemeId(newId);
    } else {
      setThemes(remaining);
      if (isDeletingActive) {
        setActiveThemeId(remaining[0].id);
      }
    }
    setPrompt('');
    setRefFiles([]);
  };

  const handleRenameTheme = (id: string, name: string) => {
    setThemes((prev) =>
      prev.map((t) => (t.id === id ? { ...t, name } : t)),
    );
  };

  const handleSelectTheme = (id: string) => {
    setActiveThemeId(id);
    setPrompt('');
    setRefFiles([]);
  };

  const addRefFiles = useCallback((filesToAdd: File[]) => {
    setRefFiles((prev) => {
      const remaining = 3 - prev.length;
      const toAdd = Math.min(filesToAdd.length, remaining);
      const newRefs: RefFile[] = [];
      for (let i = 0; i < toAdd; i++) {
        const f = filesToAdd[i];
        if (f.size > 10 * 1024 * 1024) {
          alert(`${f.name} 超过 10MB，请压缩后再上传`);
          continue;
        }
        newRefs.push({ file: f, preview: URL.createObjectURL(f) });
      }
      return [...prev, ...newRefs].slice(0, 3);
    });
  }, []);

  const handleAddRef = (e: ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files) return;
    addRefFiles(Array.from(files));
    e.target.value = '';
  };

  const handlePasteImage = useCallback((file: File) => {
    addRefFiles([file]);
  }, [addRefFiles]);

  const removeRef = (idx: number) => {
    setRefFiles((prev) => {
      URL.revokeObjectURL(prev[idx].preview);
      return prev.filter((_, i) => i !== idx);
    });
  };

  const handleSend = async () => {
    if (!prompt.trim() && refFiles.length === 0) return;
    if (!activeThemeId) return;

    // Abort any previous generation for this theme
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    const controller = new AbortController();
    abortRef.current = controller;

    // Capture the target theme ID at the moment of sending,
    // so that switching themes mid-generation doesn't misroute the result.
    const targetThemeId = activeThemeId;

    const userMsg: Message = {
      id: 'u-temp-' + Date.now(),
      type: 'user',
      prompt: prompt.trim() || '仅使用参考图生成',
      refThumbnails: refFiles.map((f) => f.preview),
    };
    setThemes((prev) =>
      prev.map((t) =>
        t.id === targetThemeId ? { ...t, messages: [...t.messages, userMsg] } : t,
      ),
    );

    const promptText = prompt.trim() || '仅使用参考图生成';
    const filesToSend = refFiles;
    const currentConfig = config;
    setPrompt('');
    setRefFiles([]);
    // Mark only this theme as generating
    setGeneratingThemes((prev) => ({ ...prev, [targetThemeId]: true }));

    try {
      const refBase64: string[] = [];
      for (const rf of filesToSend) {
        const b64 = await new Promise<string>((resolve) => {
          const reader = new FileReader();
          reader.onload = () => resolve((reader.result as string).split(',')[1]);
          reader.readAsDataURL(rf.file);
        });
        refBase64.push(b64);
      }

      const record = await generateImage(
        promptText,
        refBase64.length > 0 ? refBase64 : undefined,
        currentConfig.resolution,
        controller.signal,
      );
      // Clear abort ref if this request completed
      if (abortRef.current === controller) abortRef.current = null;
      setThemes((prev) =>
        prev.map((t) =>
          t.id === targetThemeId
            ? { ...t, messages: [...t.messages, { id: 'a-' + record.id, type: 'ai' as const, record }] }
            : t,
        ),
      );
    } catch (e: any) {
      // If aborted, don't show error — user is editing
      if (e instanceof DOMException && e.name === 'AbortError') return;
      setThemes((prev) =>
        prev.map((t) =>
          t.id === targetThemeId
            ? {
                ...t,
                messages: [
                  ...t.messages,
                  { id: 'err-' + Date.now(), type: 'ai' as const, prompt: '生成失败：' + (e.message || '未知错误') },
                ],
              }
            : t,
        ),
      );
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      setGeneratingThemes((prev) => ({ ...prev, [targetThemeId]: false }));
    }
  };

  const handleDelete = async (id: number, msgId: string) => {
    if (!activeThemeId) return;
    const targetThemeId = activeThemeId;
    try {
      await deleteImageGenRecord(id);
      setThemes((prev) =>
        prev.map((t) =>
          t.id === targetThemeId
            ? { ...t, messages: t.messages.filter((m) => m.id !== 'u-' + id && m.id !== 'a-' + id && m.id !== msgId) }
            : t,
        ),
      );
    } catch (e: any) {
      alert('删除失败：' + (e.message || '未知错误'));
    }
  };

  const handleDeleteUserMessage = (msgId: string) => {
    if (!activeThemeId) return;
    setThemes((prev) =>
      prev.map((t) =>
        t.id === activeThemeId
          ? { ...t, messages: t.messages.filter((m) => m.id !== msgId) }
          : t,
      ),
    );
  };

  const handleCopyMessage = (text: string, msgId: string) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopiedId(msgId);
      setTimeout(() => setCopiedId(null), 1500);
    }).catch(() => {});
  };

  const handleEditMessage = (msg: Message) => {
    // Abort ongoing generation if editing while generating
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    // Fill the prompt text into the composer
    setPrompt(msg.prompt || '');

    // Restore reference images from stored blob URLs
    if (msg.refThumbnails && msg.refThumbnails.length > 0) {
      Promise.all(
        msg.refThumbnails.map(async (url) => {
          const resp = await fetch(url);
          const blob = await resp.blob();
          const file = new File([blob], `ref-${Date.now()}.png`, { type: blob.type });
          return { file, preview: URL.createObjectURL(file) } as RefFile;
        }),
      ).then((refs) => {
        setRefFiles(refs.slice(0, 3));
      });
    }

    // Remove the message and restore generation config from the AI response that follows this user message
    if (activeThemeId) {
      setThemes((prev) => {
        const theme = prev.find((t) => t.id === activeThemeId);
        if (theme) {
          const msgIndex = theme.messages.findIndex((m) => m.id === msg.id);
          const nextMsg = theme.messages[msgIndex + 1];
          if (nextMsg?.type === 'ai' && nextMsg.record?.size) {
            const size = nextMsg.record.size;
            const matching = RESOLUTIONS.find((r) => r.value === size);
            if (matching) {
              setConfig({ resolution: matching.value, aspectRatio: matching.ratio });
            }
          }
        }
        return prev.map((t) =>
          t.id === activeThemeId
            ? { ...t, messages: t.messages.filter((m) => m.id !== msg.id) }
            : t,
        );
      });
    }
  };

  const handleOpenInCanvas = (imageUrl: string) => {
    // Reset Excalidraw to blank state
    setExcalidrawKey((k) => k + 1);
    setCanvasOpen(true);
    // Show paste hint persistently until user dismisses it
    setPasteHint(true);
    // Pre-fill prompt with canvas annotation instructions
    setPrompt('按照画布标注修改图片');
    // Copy image to clipboard so user can paste it into Excalidraw
    // Also add the original image as a reference in the chat composer
    const fullUrl = imageUrl.startsWith('http') ? imageUrl : window.location.origin + imageUrl;
    fetch(fullUrl)
      .then((r) => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.blob();
      })
      .then((blob) => {
        // Add original image as reference in the chat
        const file = new File([blob], `canvas-original-${Date.now()}.png`, { type: 'image/png' });
        addRefFiles([file]);
        return createImageBitmap(blob);
      })
      .then((bitmap) => {
        const canvas = document.createElement('canvas');
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('No 2D context');
        ctx.drawImage(bitmap, 0, 0);
        bitmap.close();
        return new Promise<Blob>((resolve, reject) => {
          canvas.toBlob((b) => {
            canvas.remove();
            if (b) resolve(b);
            else reject(new Error('toBlob returned null'));
          }, 'image/png');
        });
      })
      .then((pngBlob) => {
        if (!navigator.clipboard) return;
        navigator.clipboard.write([new ClipboardItem({ 'image/png': pngBlob })]).catch(() => {
          // Silently fail — user can still manually copy
        });
      })
      .catch(() => {
        // Silently fail — user can still manually copy
      });
  };

  const handleDividerMouseDown = useCallback(
    (e: React.PointerEvent) => {
      e.preventDefault();
      const panel = canvasPanelRef.current;
      const container = containerRef.current;
      if (!panel || !container) return;
      // Capture pointer on the divider element so we get events even outside the window
      (e.target as HTMLElement).setPointerCapture(e.pointerId);

      const containerRect = container.getBoundingClientRect();
      const cw = containerRect.width;
      const maxW = cw * 0.7;

      const d = dragRef.current;
      d.isDragging = true;
      d.lastX = e.clientX;
      d.currentWidth = Math.round(Math.min(Math.max(canvasWidth, 360), maxW));

      // Ensure DOM matches initial state to avoid flicker
      panel.style.width = d.currentWidth + 'px';

      const maxAllowed = maxW;

      const handlePointerMove = (ev: PointerEvent) => {
        if (!dragRef.current.isDragging) return;
        const delta = dragRef.current.lastX - ev.clientX;
        dragRef.current.lastX = ev.clientX;
        const raw = dragRef.current.currentWidth + delta;
        dragRef.current.currentWidth = Math.round(Math.min(Math.max(raw, 360), maxAllowed));
        panel.style.width = dragRef.current.currentWidth + 'px';
      };

      const handlePointerUp = () => {
        if (!dragRef.current.isDragging) return;
        dragRef.current.isDragging = false;
        document.removeEventListener('pointermove', handlePointerMove);
        document.removeEventListener('pointerup', handlePointerUp);
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
        // Sync final width back to React state
        setCanvasWidth(dragRef.current.currentWidth);
      };

      document.addEventListener('pointermove', handlePointerMove);
      document.addEventListener('pointerup', handlePointerUp);
      document.body.style.cursor = 'col-resize';
      document.body.style.userSelect = 'none';
    },
    [canvasWidth],
  );

  if (!loaded || loading) {
    return (
      <div className="flex-1 flex items-center justify-center bg-ink">
        <Loader2 size={28} className="animate-spin text-coral" />
      </div>
    );
  }

  return (
    <div ref={containerRef} className="flex-1 min-h-0 bg-ink text-cream flex">
      {/* Theme Sidebar */}
      <ThemeSidebar
        themes={themes}
        activeThemeId={activeThemeId}
        onSelectTheme={handleSelectTheme}
        onCreateTheme={handleCreateTheme}
        onDeleteTheme={handleDeleteTheme}
        onRenameTheme={handleRenameTheme}
        sidebarOpen={sidebarOpen}
        onToggleSidebar={() => setSidebarOpen(false)}
      />

      {/* Main Content */}
      <div className="flex min-h-0 flex-1 flex-col">
        {!sidebarOpen && (
          <div className="flex h-12 items-center border-b border-ink-border px-3 bg-ink-light/80">
            <button
              onClick={() => setSidebarOpen(true)}
              className="flex items-center gap-1.5 text-sm text-cream-dim hover:text-cream transition-colors"
            >
              <ChevronRight size={16} />
              <span>主题列表</span>
            </button>
          </div>
        )}

        {!activeTheme ? (
          <div className="flex-1 flex items-center justify-center px-4">
            <div className="text-center">
              <p className="text-cream-dim mb-4">请选择一个主题</p>
              <button
                onClick={handleCreateTheme}
                className="inline-flex items-center gap-2 rounded-xl bg-coral px-5 py-3 text-sm font-medium text-white hover:bg-coral-light transition-colors"
              >
                <Plus size={16} />
                创作新主题
              </button>
            </div>
          </div>
        ) : !hasMessages ? (
          <div className="flex-1 flex items-center justify-center px-4">
            <div className="w-full max-w-5xl">
              <div className="mb-8 text-center">
                <h2 className="text-4xl font-semibold tracking-tight text-cream sm:text-5xl">即刻创作图片</h2>
                <p className="mt-2 text-sm text-cream-dim">
                  当前主题：{activeTheme.name}
                </p>
              </div>
              <Composer
                refFiles={refFiles}
                prompt={prompt}
                config={config}
                generating={isGenerating}
                canSend={canSend}
                onPromptChange={setPrompt}
                onAddRef={handleAddRef}
                onRemoveRef={removeRef}
                onSend={handleSend}
                onConfigChange={setConfig}
                onPasteImage={handlePasteImage}
              />
            </div>
          </div>
        ) : (
          <div className="flex-1 min-h-0 flex flex-col">
            <div className="flex h-12 items-center justify-between border-b border-ink-border px-4 bg-ink-light/80">
              <div className="flex items-center gap-2">
                <Palette size={14} className="text-coral" />
                <span className="text-sm font-medium text-cream truncate">{activeTheme.name}</span>
                <span className="text-xs text-cream-dim">
                  ({Math.ceil(messages.filter((m) => m.type === 'ai' && m.record).length)} 张图片)
                </span>
              </div>
              <div className="flex items-center gap-2">
                {canvasOpen ? (
                  <button
                    onClick={() => setCanvasOpen(false)}
                    className="flex items-center gap-1.5 rounded-lg border border-ink-border px-3 py-1.5 text-xs text-cream-dim hover:text-cream hover:bg-ink-lighter transition-colors"
                    title="收起画布"
                  >
                    <Edit3 size={12} />
                    画布
                  </button>
                ) : (
                  <button
                    onClick={() => setCanvasOpen(true)}
                    className="flex items-center gap-1.5 rounded-lg border border-coral/30 px-3 py-1.5 text-xs text-coral hover:bg-coral/10 transition-colors"
                    title="打开画布"
                  >
                    <Edit3 size={12} />
                    画布
                  </button>
                )}
              </div>
            </div>

            <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-6 sm:px-6 lg:px-10">
              <div className="mx-auto w-full max-w-6xl space-y-8">
                {messages.map((msg) => {
                  if (msg.type === 'user') {
                    return (
                      <div key={msg.id} className="group flex justify-end">
                        <div className="max-w-[78%]">
                          <div className="inline-flex items-center gap-2 rounded-2xl border border-ink-border bg-ink-lighter px-4 py-3 text-sm text-cream shadow-sm">
                            {msg.refThumbnails && msg.refThumbnails.length > 0 && (
                              <div className="flex gap-1">
                                {msg.refThumbnails.map((src, i) => (
                                  <img key={i} src={src} alt={`参考图 ${i + 1}`} className="h-10 w-10 rounded-lg object-cover" />
                                ))}
                              </div>
                            )}
                            <span className="whitespace-pre-wrap break-words">{msg.prompt}</span>
                          </div>
                          <div className="mt-1 flex justify-end gap-1 opacity-0 transition-opacity group-hover:opacity-100">
                            <button
                              onClick={() => handleCopyMessage(msg.prompt || '', msg.id)}
                              className="rounded-md p-1.5 transition-colors"
                              title={copiedId === msg.id ? '已复制' : '复制提示词'}
                            >
                              {copiedId === msg.id ? <Check size={14} className="text-coral" /> : <Copy size={14} className="text-cream-dim" />}
                            </button>
                            <button
                              onClick={() => handleEditMessage(msg)}
                              className="rounded-md p-1.5 text-cream-dim hover:text-coral hover:bg-ink-lighter transition-colors"
                              title="修改提示词"
                            >
                              <Edit3 size={14} />
                            </button>
                            <button
                              onClick={() => handleDeleteUserMessage(msg.id)}
                              className="rounded-md p-1.5 text-cream-dim hover:text-red-400 hover:bg-ink-lighter transition-colors"
                              title="删除这条消息"
                            >
                              <Trash2 size={14} />
                            </button>
                          </div>
                        </div>
                      </div>
                    );
                  }

                  const record = msg.record;
                  if (record) {
                    const imageUrl = imageGenUrl(record.image_url);
                    return (
                      <div key={msg.id} className="space-y-3">
                        <div className="flex items-center justify-between text-xs text-cream-dim">
                          <div className="flex items-center gap-2">
                            <span className="font-medium text-cream">{record.model || 'gpt-image-2'}</span>
                            <span>{record.size ? fmtRes(record.size) : '生成图片'}</span>
                          </div>
                          <span>{new Date(record.created_at).toLocaleString()}</span>
                        </div>

                        <div className="flex justify-start">
                          <div className="inline-flex max-w-full items-center justify-center overflow-hidden rounded-2xl border border-ink-border bg-ink-light shadow-sm">
                            <img
                              src={imageUrl}
                              alt={record.prompt}
                              className="block h-auto max-h-[75vh] max-w-full object-contain"
                              loading="lazy"
                            />
                          </div>
                        </div>

                        <div className="flex items-center gap-2 text-cream-dim">
                          <button
                            className="rounded-lg p-2 hover:bg-ink-lighter transition-colors"
                            title="复制图片到剪贴板"
                            onClick={() => copyImageToClipboard(imageUrl, record.id)}
                          >
                            {copiedId === String(record.id) ? (
                              <Check size={14} className="text-coral" />
                            ) : (
                              <Copy size={14} />
                            )}
                          </button>
                          <a href={imageUrl} download className="rounded-lg p-2 hover:bg-ink-lighter" title="下载图片">
                            <Download size={14} />
                          </a>
                          <button
                            onClick={() => handleOpenInCanvas(imageUrl)}
                            className="rounded-lg p-2 hover:bg-ink-lighter transition-colors"
                            title="在画布中标注"
                          >
                            <Edit3 size={14} />
                          </button>
                          <button onClick={() => handleDelete(record.id, msg.id)} className="rounded-lg p-2 hover:bg-ink-lighter" title="删除记录">
                            <Trash2 size={14} />
                          </button>
                        </div>
                      </div>
                    );
                  }

                  return (
                    <div key={msg.id} className="rounded-md border border-vermilion/20 bg-vermilion-light/20 px-4 py-3 text-sm text-vermilion">
                      {msg.prompt}
                    </div>
                  );
                })}

                {isActiveThemeGenerating && (
                  <div className="flex justify-start">
                    <div className="inline-flex items-center gap-2 rounded-2xl border border-ink-border bg-ink-light px-4 py-3 text-sm text-cream-dim shadow-sm">
                      <Loader2 size={16} className="animate-spin text-coral" />
                      正在生成图片...
                    </div>
                  </div>
                )}
              </div>
            </div>

            <div className="border-t border-ink-border glass px-4 py-5 sm:px-6 lg:px-10">
              <Composer
                compact
                refFiles={refFiles}
                prompt={prompt}
                config={config}
                generating={isGenerating}
                canSend={canSend}
                onPromptChange={setPrompt}
                onAddRef={handleAddRef}
                onRemoveRef={removeRef}
                onSend={handleSend}
                onConfigChange={setConfig}
                onPasteImage={handlePasteImage}
              />
            </div>
          </div>
        )}
      </div>

      {/* Draggable Divider — always mounted, hidden when closed */}
      <div
        className={(
          'flex w-[5px] shrink-0 cursor-col-resize items-center justify-center bg-transparent transition-colors hover:bg-coral/40 active:bg-coral/60 group touch-none '
          + (canvasOpen ? '' : 'hidden')
        )}
        onPointerDown={handleDividerMouseDown}
        title="拖拽调整画布宽度"
      >
        <div className="flex h-8 w-0.5 items-center justify-center rounded-full bg-ink-muted opacity-0 transition-opacity group-hover:opacity-100">
          <GripVertical size={10} className="text-ink-muted" />
        </div>
      </div>

      {/* Excalidraw Canvas Panel — always mounted, hidden when closed */}
      <div
        ref={canvasPanelRef}
        className={(
          'flex shrink-0 flex-col border-l border-ink-border bg-ink-light '
          + (canvasOpen ? '' : 'hidden')
        )}
        style={{ width: canvasWidth }}
      >
          {/* Panel Header */}
          <div className="flex h-12 shrink-0 items-center justify-between border-b border-ink-border px-4 bg-ink-light/80">
            <span className="flex items-center gap-1.5 text-sm font-bold tracking-wide text-coral">
              <Edit3 size={14} />
              画布
            </span>
            <button
              onClick={() => setCanvasOpen(false)}
              className="rounded-lg p-1.5 text-cream-dim hover:text-cream hover:bg-ink-lighter transition-colors"
              title="收起画布"
            >
              <ChevronRight size={16} />
            </button>
          </div>
          {/* Paste hint banner */}
          {pasteHint && (
            <div className="flex shrink-0 items-center justify-between gap-2 bg-coral/15 px-4 py-2 text-xs text-coral border-b border-coral/20 animate-fade-in">
              <span>原图已添加到聊天框，在画布中标注后粘贴回来一起发送</span>
              <button
                onClick={() => setPasteHint(false)}
                className="shrink-0 rounded p-0.5 text-coral/60 hover:text-coral hover:bg-coral/20 transition-colors"
                aria-label="关闭提示"
              >
                <X size={14} />
              </button>
            </div>
          )}
          {/* Excalidraw iframe */}
          <div className="flex-1 min-h-0">
            <iframe
              key={excalidrawKey}
              src="https://excalidraw.com/"
              className="h-full w-full border-0"
              title="Excalidraw 无限画布"
              allow="clipboard-read; clipboard-write"
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads"
            />
          </div>
        </div>
    </div>
  );
}
