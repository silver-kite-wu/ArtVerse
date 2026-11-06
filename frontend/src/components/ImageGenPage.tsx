import ImageRequestStatusPanel from './ImageRequestStatusPanel';
import { refreshImageRequestRecord, IMAGE_STATUS_LABELS } from '../api';
import { openAutomationComposer } from '../api/automations';
import type { ChangeEvent } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Copy,
  ArrowUp,
  CalendarClock,
  Download,
  Edit3,
  GripVertical,
  ImagePlus,
  Loader2,
  Plus,
  Settings2,
  Trash2,
  X,
  Palette,
  ChevronLeft,
  ChevronRight,
  Check,
} from 'lucide-react';
import {
  API_KEY_CHANGE_EVENT,
  deleteImageGenRecord,
  errorMessage,
  generateImage,
  getPrimaryProviderModel,
  getProviderModelOptions,
  imageGenUrl,
  listImageGenHistory,
  listAiConversations,
  createAiConversation,
  renameAiConversation,
  archiveAiConversation,
} from '../api';
import {
  cacheImage,
  deleteCachedImage,
  generatedImageCacheKey,
  loadPersistentImage,
  referenceImageCacheKey,
} from '../imageCache';
import ModelSwitcher from './ModelSwitcher';
import {
  ASPECT_RATIOS, LS_CANVAS_OPEN_KEY, LS_CANVAS_WIDTH_KEY,
  RESOLUTIONS, type GenConfig, type GenTheme, type Message,
  fmtRes, generateId, historyMessages, loadActiveThemeId, loadGenConfig,
  loadThemes, mergeHistoryIntoThemes, removeMessageRound, saveActiveThemeId,
  saveGenConfig, saveThemes,
  imageThemeManualTitle, imageThemePromptTitle, mergeImageConversationTitles, recoverImageThemeConversation,
} from './imageGenModel';
import { useToast } from '../ui/toast/useToast';
import { copyText } from '../utils/browserSecurity';

type RefFile = { file: File; preview: string };

function PersistentImage({
  cacheKey,
  sourceUrl,
  alt,
  className,
  placeholderClassName,
  compact = false,
}: {
  cacheKey: string;
  sourceUrl?: string;
  alt: string;
  className: string;
  placeholderClassName: string;
  compact?: boolean;
}) {
  const [resolvedUrl, setResolvedUrl] = useState<string | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;

    void loadPersistentImage(cacheKey, sourceUrl)
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setResolvedUrl(objectUrl);
      })
      .catch(() => {
        if (!cancelled) setUnavailable(true);
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [attempt, cacheKey, sourceUrl]);

  if (!resolvedUrl) {
    if (unavailable) {
      return (
        <button
          type="button"
          className={placeholderClassName}
          onClick={() => {
            setUnavailable(false);
            setAttempt((current) => current + 1);
          }}
          aria-label={`${alt}加载失败，点击重试`}
          title="点击重试"
        >
          {compact ? <ImagePlus size={14} className="text-text-secondary" /> : (
            <span className="px-3 text-center text-xs text-text-secondary">图片尚未缓存，点击重试</span>
          )}
        </button>
      );
    }
    return (
      <div className={placeholderClassName} role="img" aria-label={alt}>
        <Loader2 size={18} className="animate-spin text-accent" />
      </div>
    );
  }

  return <img src={resolvedUrl} alt={alt} className={className} loading="lazy" />;
}

async function toPngBlob(blob: Blob): Promise<Blob> {
  const bitmap = await createImageBitmap(blob);
  const canvas = document.createElement('canvas');
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  const context = canvas.getContext('2d');
  if (!context) {
    bitmap.close();
    throw new Error('No 2D context');
  }
  context.drawImage(bitmap, 0, 0);
  bitmap.close();

  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((pngBlob) => {
      canvas.remove();
      if (pngBlob) resolve(pngBlob);
      else reject(new Error('Failed to convert image to PNG'));
    }, 'image/png');
  });
}

function AspectRatioLabel({ aspectRatio }: { aspectRatio: string }) {
  const found = ASPECT_RATIOS.find((a) => a.value === aspectRatio);
  if (!found) return <span className="text-text-secondary">{aspectRatio}</span>;
  return (
    <span className="text-text-secondary">
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
    <div role="dialog" aria-label="图片配置" className="studio-config-popover absolute left-0 bottom-full mb-2 z-50 w-72 origin-bottom-left animate-fade-in">
      <div className="overflow-hidden rounded-2xl border border-border bg-bg-surface shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-text-primary">
            <Settings2 size={14} className="text-accent" />
            图片配置
          </h3>
          <button onClick={onClose} className="text-text-muted hover:text-text-primary transition-colors" aria-label="关闭">
            <X size={14} />
          </button>
        </div>

        <div className="max-h-80 overflow-y-auto px-4 py-3 space-y-4">
          {/* Resolution */}
          <div>
            <div className="mb-2 text-xs font-medium text-text-secondary uppercase tracking-wider">分辨率</div>
            <div className="grid grid-cols-2 gap-1.5">
              {RESOLUTIONS.map((res) => (
                <button
                  key={res.value}
                  onClick={() => handleResolutionSelect(res.value, res.ratio)}
                  className={
                    'flex items-center justify-between rounded-xl border px-3 py-2 text-xs transition-all duration-150 ' +
                    (config.resolution === res.value
                      ? 'border-accent bg-gradient-to-b from-accent-soft to-accent-muted text-accent shadow-sm'
                      : 'border-border text-text-secondary hover:border-accent/40 hover:text-text-primary bg-bg-base')
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
            <div className="mb-2 text-xs font-medium text-text-secondary uppercase tracking-wider">宽高比</div>
            <div className="flex flex-wrap gap-1.5">
              {ASPECT_RATIOS.map((ar) => (
                <button
                  key={ar.value}
                  onClick={() => handleAspectRatioSelect(ar.value)}
                  className={
                    'flex flex-col items-center gap-1 rounded-xl border px-3 py-2 text-xs transition-all duration-150 min-w-[64px] ' +
                    (config.aspectRatio === ar.value
                      ? 'border-accent bg-gradient-to-b from-accent-soft to-accent-muted text-accent shadow-sm'
                      : 'border-border text-text-secondary hover:border-accent/40 hover:text-text-primary bg-bg-base')
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
                  <span className="text-[0.625rem] opacity-60 leading-tight">{ar.sub}</span>
                </button>
              ))}
            </div>
          </div>

          {/* Current config summary */}
          <div className="rounded-xl border border-border bg-bg-base px-3 py-2 text-xs text-text-secondary">
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
  selectedModel,
  onSelectModel,
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
  selectedModel: string;
  onSelectModel: (model: string) => void;
}) {
  const [configOpen, setConfigOpen] = useState(false);
  const [pasteToast, setPasteToast] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

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
      <div className={`ai-composer${compact ? ' ai-composer--compact' : ''}`}>
        {refFiles.length > 0 && (
          <div className="ai-composer__attachments">
            {refFiles.map((rf, i) => (
              <div key={i} className="ai-composer__attachment">
                <img src={rf.preview} alt={`参考图 ${i + 1}`} />
                <button
                  type="button"
                  onClick={() => onRemoveRef(i)}
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
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && e.keyCode !== 229) {
              e.preventDefault();
              if (canSend) onSend();
            }
          }}
          onPaste={handlePaste}
          aria-label="生图提示词"
          placeholder="描述你想生成的画面、风格、主体和细节（支持粘贴图片作为参考图）"
          disabled={generating}
          rows={compact ? 3 : 4}
          className="ai-composer__input"
        />
        {pasteToast && (
          <div className="absolute right-4 top-4 z-10 animate-fade-in rounded-lg bg-accent/90 px-3 py-1.5 text-xs text-white">
            已添加参考图
          </div>
        )}
        <div className="ai-composer__toolbar">
          <div className="ai-composer__tools">
            <button type="button" className="ai-composer__tool" disabled={refFiles.length >= 3 || generating} onClick={() => fileInputRef.current?.click()}>
              <ImagePlus size={16} />
              <span>参考图</span>
            </button>
            <input ref={fileInputRef} type="file" accept="image/*" multiple onChange={onAddRef} className="ai-composer__file-input" aria-label="添加参考图" />

            {/* Config button */}
            <div className="relative">
              <button
                type="button"
                onClick={() => setConfigOpen(!configOpen)}
                className="ai-composer__tool"
                title="图片配置"
                aria-label={`图片配置：${fmtRes(config.resolution)}`}
                aria-expanded={configOpen}
              >
                <Settings2 size={15} />
                <span>{config.aspectRatio}</span>
              </button>
              {configOpen && (
                <ConfigPopover
                  config={config}
                  onChange={(c) => { onConfigChange(c); setConfigOpen(false); }}
                  onClose={() => setConfigOpen(false)}
                />
              )}
            </div>

            <button type="button" disabled={!prompt.trim() || generating} onClick={() => openAutomationComposer({ executorKey: 'IMAGE_GEN', instruction: prompt, size: config.resolution, model: selectedModel, referenceFiles: refFiles.map(item => item.file), origin: 'IMAGE_GEN' })} className="ai-composer__tool" title="预约执行"><CalendarClock size={15} /><span>预约</span></button>
          </div>
          <div className="ai-composer__model">
            <ModelSwitcher
              capability="image"
              selectedModel={selectedModel}
              onSelect={onSelectModel}
              disabled={generating}
            />
          </div>

          <button
            type="button"
            onClick={onSend}
            disabled={!canSend}
            className="ai-composer__send"
            aria-label={generating ? '正在生成' : '发送生成请求'}
          >
            {generating ? <Loader2 size={16} className="animate-spin" /> : <ArrowUp size={18} />}
          </button>
        </div>
      </div>
      <div className="ai-composer__caption"><span>支持粘贴参考图 · 最多 3 张</span><span>Enter 生成 · Shift + Enter 换行</span></div>
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
    <aside inert={!sidebarOpen} aria-label="生图主题" className={'studio-theme-sidebar flex shrink-0 flex-col border-r border-border glass-panel transition-all duration-300 ' + (sidebarOpen ? 'is-open w-56' : 'w-0 overflow-hidden')}>
      <div className="flex h-14 items-center justify-between border-b border-border px-3">
        <span className="flex items-center gap-1.5 text-sm font-bold tracking-wide text-accent">
          <Palette size={14} />
          主题列表
        </span>
        <button onClick={onToggleSidebar} className="rounded-lg p-1 text-text-muted hover:bg-accent-soft hover:text-accent transition-colors" aria-label="收起侧边栏">
          <ChevronLeft size={16} />
        </button>
      </div>

      <div className="px-2 py-3">
        <button
          onClick={onCreateTheme}
          className="flex w-full items-center gap-2 rounded-xl border border-dashed border-border px-3 py-2.5 text-sm font-medium text-text-secondary transition-all duration-200 hover:border-accent/50 hover:bg-accent-soft hover:text-accent"
        >
          <Plus size={15} />
          创作新主题
        </button>
      </div>

      <nav className="flex-1 overflow-y-auto px-2 pb-3 space-y-0.5">
        {themes.length === 0 && (
          <p className="px-3 py-4 text-xs text-text-muted text-center">暂无主题</p>
        )}
        {themes.map((theme) => (
          <div key={theme.id} className="group relative">
            <button
              onClick={() => onSelectTheme(theme.id)}
              className={
                'w-full rounded-xl px-3 py-2.5 text-left text-sm transition-all duration-200 flex items-center gap-1 ' +
                (theme.id === activeThemeId
                  ? 'bg-accent-muted text-accent border border-accent/30 shadow-sm'
                  : 'text-text-secondary hover:bg-bg-raised hover:text-text-primary border border-transparent')
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
                  className="min-w-0 flex-1 bg-bg-base rounded-lg  px-1 py-0.5 text-sm text-text-primary outline-none border border-accent/40"
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
                    className="rounded p-0.5 text-text-muted hover:text-text-primary hover:bg-bg-raised transition-colors cursor-pointer"
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
                    className="rounded p-0.5 text-text-muted hover:text-accent hover:bg-bg-raised transition-colors cursor-pointer"
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
  const toast = useToast();
  const [initialThemes] = useState(loadThemes);
  const [themes, setThemes] = useState<GenTheme[]>(initialThemes);
  const [activeThemeId, setActiveThemeId] = useState<string | null>(() => {
    const saved = loadActiveThemeId();
    return initialThemes.find(theme => theme.id === saved)?.id ?? initialThemes[0]?.id ?? null;
  });
  const [loaded, setLoaded] = useState(initialThemes.length > 0);
  const [loading, setLoading] = useState(true);
  const [titleRefreshVersion, setTitleRefreshVersion] = useState(0);
  const [prompt, setPrompt] = useState('');
  const [refFiles, setRefFiles] = useState<RefFile[]>([]);
  const [generatingThemes, setGeneratingThemes] = useState<Record<string, boolean>>({});
  const [config, setConfig] = useState<GenConfig>(loadGenConfig);
  const [selectedImageModel, setSelectedImageModel] = useState(() => getPrimaryProviderModel('image'));
  const [sidebarOpen, setSidebarOpen] = useState(() => window.matchMedia('(min-width: 1280px)').matches);
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
  const sendingRef = useRef(false);
  const themesRef = useRef(themes);
  useEffect(() => { themesRef.current = themes; }, [themes]);
  const dragRef = useRef<{
    isDragging: boolean;
    lastX: number;
    currentWidth: number;
  }>({ isDragging: false, lastX: 0, currentWidth: 0 });

  useEffect(() => {
    const syncModels = () => {
      const options = getProviderModelOptions('image');
      setSelectedImageModel((prev) => (prev && options.includes(prev) ? prev : (options[0] || '')));
    };
    syncModels();
    window.addEventListener(API_KEY_CHANGE_EVENT, syncModels);
    return () => window.removeEventListener(API_KEY_CHANGE_EVENT, syncModels);
  }, []);

  function copyImageToClipboard(cacheKey: string, imageUrl: string, recordId: number) {
    loadPersistentImage(cacheKey, imageUrl)
      .then(toPngBlob)
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
        ta.value = new URL(imageUrl, window.location.origin).href;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        try { document.execCommand('copy'); } catch { /* 忽略旧 API 复制失败 */ }
        document.body.removeChild(ta);
        setCopiedId(String(recordId));
        setTimeout(() => setCopiedId(null), 1500);
      });
  }

  function downloadImage(cacheKey: string, imageUrl: string, recordId: number) {
    void loadPersistentImage(cacheKey, imageUrl)
      .then((blob) => {
        const objectUrl = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.download = `artverse-${recordId}.png`;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
      })
      .catch((error) => {
        console.warn('downloadImage failed:', error);
      });
  }

  // Initialize from the local cache, then reconcile persisted history. A request
  // can finish while this page is unmounted, so cache contents alone are stale.
  useEffect(() => {
    let stored = initialThemes;
    let cancelled = false;
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
          defaultTheme.messages = historyMessages(r.content);
        } catch {
          // Empty history is fine.
        }
        if (cancelled) return;
        stored = [recoverImageThemeConversation(defaultTheme)];
        saveThemes(stored);
        setThemes(stored);
        setActiveThemeId(defaultTheme.id);
        setLoaded(true);
        setLoading(false);
      })();
    }
    if (stored.length > 0) void (async () => {
      try {
        const response = await listImageGenHistory(0, 50);
        if (!cancelled) {
          setThemes((current) => mergeHistoryIntoThemes(current, response.content));
        }
      } catch {
        // The cached conversation remains usable when history is unavailable.
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => { cancelled = true; };
  }, [initialThemes]);

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

  const hasRunningRecords = useMemo(
    () => themes.some((theme) => theme.messages.some((message) => message.record && !['SUCCEEDED', 'FAILED', 'STALE', 'NEEDS_REVIEW', 'PARTIAL_FAILED'].includes(message.record.status))),
    [themes],
  );

  const titleConversationKey = themes.flatMap(theme => theme.conversationId ? [theme.conversationId] : []).sort().join('|');
  const pendingTitleKey = themes.filter(theme => theme.conversationId && !imageThemeManualTitle(theme)
    && (theme.titleState === 'GENERATING' || (theme.titleSource == null || theme.titleSource === 'DEFAULT')
      && theme.messages.some(message => message.type === 'user')))
    .map(theme => theme.conversationId).sort().join('|');

  useEffect(() => {
    if (!loaded || !titleConversationKey) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempts = 0;
    const pendingIds = new Set(pendingTitleKey.split('|').filter(Boolean));
    const refresh = async () => {
      let pending = pendingIds.size > 0;
      try {
        const summaries = await listAiConversations('IMAGE_GEN');
        if (cancelled) return;
        setThemes(current => mergeImageConversationTitles(current, summaries));
        pending = summaries.some(summary => pendingIds.has(summary.conversationId) && summary.titleState !== 'FINALIZED');
      } catch {
        // Keep cached/prompt titles when metadata refresh is temporarily unavailable.
      }
      if (!cancelled && pending && ++attempts < 90) timer = setTimeout(() => void refresh(), 2000);
    };
    void refresh();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [loaded, titleConversationKey, pendingTitleKey, titleRefreshVersion]);

  useEffect(() => {
    if (!hasRunningRecords) return;
    const refreshHistory = () => {
      const pending = themes.flatMap(theme => theme.messages.flatMap(message => message.record?.request_id ? [message.record] : []));
      void Promise.all(pending.map(record => refreshImageRequestRecord(record).catch(() => record)))
        .then(records => setThemes(current => mergeHistoryIntoThemes(current, records)));
      void listImageGenHistory(0, 50).then(response => setThemes(current => mergeHistoryIntoThemes(current, response.content))).catch(() => {});
    };
    const intervalId = window.setInterval(refreshHistory, 3000);
    return () => window.clearInterval(intervalId);
  }, [hasRunningRecords, themes]);

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
      titleSource: 'DEFAULT',
      titleState: 'WAITING',
      createdAt: now,
      messages: [],
    };
    setThemes((prev) => [...prev, newTheme]);
    setActiveThemeId(newTheme.id);
    setPrompt('');
    setRefFiles([]);
  };

  const handleDeleteTheme = (id: string) => {
    const deletedTheme = themes.find((theme) => theme.id === id);
    if (deletedTheme?.conversationId) void archiveAiConversation(deletedTheme.conversationId).catch(() => {});
    deletedTheme?.messages.forEach((message) => {
      message.refImageKeys?.forEach((key) => { void deleteCachedImage(key); });
    });
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
    const conversationId = themes.find((theme) => theme.id === id)?.conversationId;
    setThemes((prev) =>
      prev.map((t) => (t.id === id ? { ...t, name, titleSource: 'USER', titleState: 'FINALIZED', manualTitlePending: true } : t)),
    );
    if (conversationId) void renameAiConversation(conversationId, name).then(() => {
      setThemes(current => current.map(theme => theme.id === id && theme.name === name
        ? { ...theme, manualTitlePending: false } : theme));
    }).catch(error => toast.error(errorMessage(error) || '名称暂未同步，已保存在本地，下次发送时重试。'));
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
          toast.warning(`${f.name} 超过 10MB，请压缩后再上传`);
          continue;
        }
        newRefs.push({ file: f, preview: URL.createObjectURL(f) });
      }
      return [...prev, ...newRefs].slice(0, 3);
    });
  }, [toast]);

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
    if (!activeThemeId || sendingRef.current) return;
    sendingRef.current = true;
    try {

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
      const filesToSend = refFiles;
      const userMessageId = 'u-temp-' + generateId();
      const refImageKeys = filesToSend.map((_, index) => referenceImageCacheKey(userMessageId, index));
      const cacheResults = await Promise.all(
        filesToSend.map((ref, index) => cacheImage(refImageKeys[index], ref.file)),
      );

      const userMsg: Message = {
        id: userMessageId,
        type: 'user',
        prompt: prompt.trim() || '仅使用参考图生成',
        refImageKeys,
        refThumbnails: cacheResults.every(Boolean) ? undefined : filesToSend.map((ref) => ref.preview),
      };
      setThemes((prev) =>
        prev.map((t) =>
          t.id === targetThemeId ? { ...imageThemePromptTitle(t, userMsg.prompt || ''), messages: [...t.messages, userMsg] } : t,
        ),
      );

      const promptText = prompt.trim() || '仅使用参考图生成';
      const currentConfig = config;
      setPrompt('');
      setRefFiles([]);
      if (cacheResults.every(Boolean)) {
        filesToSend.forEach((ref) => URL.revokeObjectURL(ref.preview));
      }
      // Mark only this theme as generating
      setGeneratingThemes((prev) => ({ ...prev, [targetThemeId]: true }));

      try {
        const target = themesRef.current.find(theme => theme.id === targetThemeId);
        if (!target) return;
        // Every local theme gets its own persisted conversation before image submission.
        let conversationId = target.conversationId;
        if (!conversationId) {
          const created = await createAiConversation('IMAGE_GEN', imageThemeManualTitle(target));
          conversationId = created.conversationId;
          if (!themesRef.current.some(theme => theme.id === targetThemeId)) {
            void archiveAiConversation(conversationId).catch(() => {});
            return;
          }
          setThemes(current => current.map(theme => theme.id === targetThemeId ? { ...theme, conversationId } : theme));
        }
        const latest = themesRef.current.find(theme => theme.id === targetThemeId);
        const manualTitle = latest && imageThemeManualTitle(latest);
        if (manualTitle) {
          await renameAiConversation(conversationId, manualTitle);
          setThemes(current => current.map(theme => theme.id === targetThemeId && theme.name === manualTitle
            ? { ...theme, titleSource: 'USER', titleState: 'FINALIZED', manualTitlePending: false } : theme));
        }
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
          selectedImageModel,
          controller.signal,
          conversationId,
        );
        // Clear abort ref if this request completed
        if (abortRef.current === controller) abortRef.current = null;
        setTitleRefreshVersion(version => version + 1);
        setThemes((prev) =>
          prev.map((t) =>
            t.id === targetThemeId
              ? {
                  ...t,
                  conversationId: record.conversation_id || conversationId,
                  messages: t.messages.flatMap((message) => {
                    if (message.id !== userMsg.id) return [message];
                    return [
                      { ...message, id: 'u-' + record.id },
                      { id: 'a-' + record.id, type: 'ai' as const, record },
                    ];
                  }),
                }
              : t,
          ),
        );
      } catch (e) {
        // If aborted, don't show error — user is editing
        if (e instanceof DOMException && e.name === 'AbortError') return;
        setThemes((prev) =>
          prev.map((t) =>
            t.id === targetThemeId
              ? {
                  ...t,
                  messages: [
                    ...t.messages,
                    { id: 'err-' + Date.now(), type: 'ai' as const, prompt: '生成失败：' + (errorMessage(e) || '未知错误') },
                  ],
                }
              : t,
          ),
        );
      } finally {
        if (abortRef.current === controller) abortRef.current = null;
        setGeneratingThemes((prev) => ({ ...prev, [targetThemeId]: false }));
      }
    } finally { sendingRef.current = false; }
  };

  const handleDelete = async (id: number, msgId: string, imagePath: string | null) => {
    if (!activeThemeId) return;
    const targetThemeId = activeThemeId;
    const targetTheme = themes.find((theme) => theme.id === targetThemeId);
    const removedMessages = targetTheme?.messages.filter(
      (message) => message.id === 'u-' + id || message.id === 'a-' + id || message.id === msgId,
    ) ?? [];
    try {
      await deleteImageGenRecord(id);
      if (imagePath) await deleteCachedImage(generatedImageCacheKey(imagePath));
      removedMessages.forEach((message) => {
        message.refImageKeys?.forEach((key) => { void deleteCachedImage(key); });
      });
      setThemes((prev) =>
        prev.map((t) =>
          t.id === targetThemeId
            ? { ...t, messages: t.messages.filter((m) => m.id !== 'u-' + id && m.id !== 'a-' + id && m.id !== msgId) }
            : t,
        ),
      );
    } catch (e) {
      toast.error('删除失败：' + (errorMessage(e) || '未知错误'));
    }
  };

  const handleDeleteUserMessage = (msgId: string) => {
    if (!activeThemeId) return;
    const targetMessage = themes
      .find((theme) => theme.id === activeThemeId)
      ?.messages.find((message) => message.id === msgId);
    targetMessage?.refImageKeys?.forEach((key) => { void deleteCachedImage(key); });
    setThemes((prev) =>
      prev.map((t) =>
        t.id === activeThemeId
          ? { ...t, messages: removeMessageRound(t.messages, msgId) }
          : t,
      ),
    );
  };

  const handleCopyMessage = (text: string, msgId: string) => {
    void copyText(text).then((ok) => {
      if (!ok) return;
      setCopiedId(msgId);
      setTimeout(() => setCopiedId(null), 1500);
    });
  };

  const handleEditMessage = (msg: Message) => {
    // Abort ongoing generation if editing while generating
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    // Fill the prompt text into the composer
    setPrompt(msg.prompt || '');

    // Restore references from IndexedDB, with legacy blob URLs as a best-effort fallback.
    if ((msg.refImageKeys && msg.refImageKeys.length > 0) || (msg.refThumbnails && msg.refThumbnails.length > 0)) {
      const referenceCount = Math.max(msg.refImageKeys?.length ?? 0, msg.refThumbnails?.length ?? 0);
      Promise.all(
        Array.from({ length: referenceCount }, async (_, index) => {
          const cacheKey = msg.refImageKeys?.[index];
          const legacyUrl = msg.refThumbnails?.[index];
          const blob = cacheKey
            ? await loadPersistentImage(cacheKey, legacyUrl)
            : await fetch(legacyUrl as string).then((response) => response.blob());
          const file = new File([blob], `ref-${Date.now()}.png`, { type: blob.type });
          return { file, preview: URL.createObjectURL(file) } as RefFile;
        }),
      ).then((refs) => {
        setRefFiles(refs.slice(0, 3));
      }).catch(() => {});
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

  const handleOpenInCanvas = (cacheKey: string, imageUrl: string) => {
    // Reset Excalidraw to blank state
    setExcalidrawKey((k) => k + 1);
    setCanvasOpen(true);
    // Show paste hint persistently until user dismisses it
    setPasteHint(true);
    // Pre-fill prompt with canvas annotation instructions
    setPrompt('按照画布标注修改图片');
    // Copy image to clipboard so user can paste it into Excalidraw
    // Also add the original image as a reference in the chat composer
    loadPersistentImage(cacheKey, imageUrl)
      .then((blob) => {
        // Add original image as reference in the chat
        const file = new File([blob], `canvas-original-${Date.now()}.png`, { type: 'image/png' });
        addRefFiles([file]);
        return toPngBlob(blob);
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
      <div className="flex-1 flex items-center justify-center bg-bg-base">
        <Loader2 size={28} className="animate-spin text-accent" />
      </div>
    );
  }

  return (
    <div ref={containerRef} className="studio-imagegen relative flex min-h-0 min-w-0 flex-1 bg-bg-base text-text-primary">
      {/* Theme Sidebar */}
      {sidebarOpen && <button type="button" className="studio-theme-backdrop" aria-label="关闭主题列表" onClick={() => setSidebarOpen(false)} />}
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
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        {!sidebarOpen && (
          <div className="flex h-12 items-center border-b border-border px-3 bg-bg-surface/80">
            <button
              onClick={() => setSidebarOpen(true)}
              className="flex items-center gap-1.5 text-sm text-text-secondary hover:text-text-primary transition-colors"
            >
              <ChevronRight size={16} />
              <span>主题列表</span>
            </button>
          </div>
        )}

        {!activeTheme ? (
          <div className="flex-1 flex items-center justify-center overflow-y-auto px-4 py-6">
            <div className="text-center">
              <p className="text-text-secondary mb-4">请选择一个主题</p>
              <button
                onClick={handleCreateTheme}
                className="inline-flex items-center gap-2 rounded-xl btn-accent px-5 py-3 text-sm font-medium"
              >
                <Plus size={16} />
                创作新主题
              </button>
            </div>
          </div>
        ) : !hasMessages ? (
          <div className="studio-image-welcome flex min-h-0 flex-1 items-center justify-center overflow-y-auto px-4 py-6">
            <div className="w-full max-w-5xl">
              <div className="studio-image-intro mb-8 text-center">
                <div className="studio-action-icon is-sage"><Palette size={26} strokeWidth={1.5} /></div>
                <span className="studio-eyebrow">A CANVAS FOR YOUR IMAGINATION</span>
                <h2 className="text-3xl tracking-tight text-text-primary sm:text-4xl">把想象，变成看得见的画面</h2>
                <p className="mt-3 text-sm text-text-secondary">
                  描述脑海中的灵感，让 AI 为你落笔。<span className="text-text-muted"> · {activeTheme.name}</span>
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
                selectedModel={selectedImageModel}
                onSelectModel={setSelectedImageModel}
              />
              <div className="studio-image-suggestions" aria-label="创作灵感">
                {['水彩绘本里的森林小屋', '雨夜霓虹下的未来城市', '温暖治愈的猫咪咖啡馆'].map((idea) => <button type="button" key={idea} onClick={() => setPrompt(idea)}><span aria-hidden="true">✧ </span>{idea}</button>)}
              </div>
            </div>
          </div>
        ) : (
          <div className="flex-1 min-h-0 flex flex-col">
            <div className="flex h-12 items-center justify-between border-b border-border px-4 bg-bg-surface/80">
              <div className="flex items-center gap-2">
                <Palette size={14} className="text-accent" />
                <span className="text-sm font-medium text-text-primary truncate">{activeTheme.name}</span>
                <span className="text-xs text-text-secondary">
                  ({Math.ceil(messages.filter((m) => m.type === 'ai' && m.record).length)} 张图片)
                </span>
              </div>
              <div className="flex items-center gap-2">
                {canvasOpen ? (
                  <button
                    onClick={() => setCanvasOpen(false)}
                    className="flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs text-text-secondary hover:text-text-primary hover:bg-bg-raised transition-colors"
                    title="收起画布"
                  >
                    <Edit3 size={12} />
                    画布
                  </button>
                ) : (
                  <button
                    onClick={() => setCanvasOpen(true)}
                    className="flex items-center gap-1.5 rounded-lg border border-accent/30 px-3 py-1.5 text-xs text-accent hover:bg-accent/10 transition-colors"
                    title="打开画布"
                  >
                    <Edit3 size={12} />
                    画布
                  </button>
                )}
              </div>
            </div>

            <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-6 sm:px-6 lg:px-10">
              <ImageRequestStatusPanel scope={`standalone.${activeTheme?.conversationId || 'default'}`} />
              <div className="mx-auto w-full max-w-6xl space-y-8">
                {messages.map((msg) => {
                  if (msg.type === 'user') {
                    const referenceCount = Math.max(msg.refImageKeys?.length ?? 0, msg.refThumbnails?.length ?? 0);
                    return (
                      <div key={msg.id} className="group flex justify-end">
                        <div className="max-w-[78%]">
                          <div className="inline-flex items-center gap-2 rounded-2xl border border-border bg-bg-raised px-4 py-3 text-sm text-text-primary shadow-md">
                            {referenceCount > 0 && (
                              <div className="flex gap-1">
                                {Array.from({ length: referenceCount }, (_, index) => (
                                  <PersistentImage
                                    key={msg.refImageKeys?.[index] ?? index}
                                    cacheKey={msg.refImageKeys?.[index] ?? `legacy-reference:${msg.id}:${index}`}
                                    sourceUrl={msg.refThumbnails?.[index]}
                                    alt={`参考图 ${index + 1}`}
                                    className="h-10 w-10 rounded-lg object-cover"
                                    placeholderClassName="flex h-10 w-10 items-center justify-center rounded-lg bg-bg-surface"
                                    compact
                                  />
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
                              {copiedId === msg.id ? <Check size={14} className="text-accent" /> : <Copy size={14} className="text-text-secondary" />}
                            </button>
                            <button
                              onClick={() => handleEditMessage(msg)}
                              className="rounded-md p-1.5 text-text-secondary hover:text-accent hover:bg-bg-raised transition-colors"
                              title="修改提示词"
                            >
                              <Edit3 size={14} />
                            </button>
                            <button
                              onClick={() => handleDeleteUserMessage(msg.id)}
                              className="rounded-md p-1.5 text-text-secondary hover:text-red-400 hover:bg-bg-raised transition-colors"
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
                    const imageUrl = record.image_url ? imageGenUrl(record.image_url) : '';
                    const imageCacheKey = record.image_url ? generatedImageCacheKey(record.image_url) : '';
                    return (
                      <div key={msg.id} className="space-y-3">
                        <div className="flex items-center justify-between text-xs text-text-secondary">
                          <div className="flex items-center gap-2">
                            <span className="font-medium text-text-primary">{record.model || 'gpt-image-2'}</span>
                            <span>{record.size ? fmtRes(record.size) : '生成图片'}</span>
                          </div>
                          <span>{record.status === 'RUNNING' ? '生成中' : IMAGE_STATUS_LABELS[record.status] || new Date(record.created_at).toLocaleString()}</span>
                        </div>

                        {record.request_id && <ImageRequestStatusPanel requestId={record.request_id} />}
                        {!['SUCCEEDED', 'FAILED', 'STALE', 'NEEDS_REVIEW', 'PARTIAL_FAILED'].includes(record.status) ? (
                          <div className="flex justify-start">
                            <div className="inline-flex items-center gap-2 rounded-2xl border border-border bg-bg-surface px-4 py-3 text-sm text-text-secondary shadow-md">
                              <Loader2 size={16} className="animate-spin text-accent" />
                              姝ｅ湪鐢熸垚鍥剧墖...
                            </div>
                          </div>
                        ) : record.status === 'FAILED' ? (
                          <div className="rounded-xl border border-red-400/30 bg-red-400/10 px-4 py-3 text-sm text-red-300">
                            生成失败：{record.failure_reason || '未知错误'}
                          </div>
                        ) : (
                        <div className="flex justify-start">
                          <div className="inline-flex max-w-full items-center justify-center overflow-hidden rounded-2xl border border-border bg-bg-surface shadow-md transition-shadow duration-200 hover:shadow-lg">
                            <PersistentImage
                              cacheKey={imageCacheKey}
                              sourceUrl={imageUrl}
                              alt={record.prompt}
                              className="block h-auto max-h-[75vh] max-w-full object-contain"
                              placeholderClassName="flex min-h-64 w-80 max-w-full items-center justify-center bg-bg-surface"
                            />
                          </div>
                        </div>
                        )}

                        {record.status === 'SUCCEEDED' && <div className="flex items-center gap-2 text-text-secondary">
                          <button
                            className="rounded-lg p-2 hover:bg-bg-raised transition-colors"
                            title="复制图片到剪贴板"
                            onClick={() => copyImageToClipboard(imageCacheKey, imageUrl, record.id)}
                          >
                            {copiedId === String(record.id) ? (
                              <Check size={14} className="text-accent" />
                            ) : (
                              <Copy size={14} />
                            )}
                          </button>
                          <button
                            type="button"
                            onClick={() => downloadImage(imageCacheKey, imageUrl, record.id)}
                            className="rounded-lg p-2 hover:bg-bg-raised"
                            title="下载图片"
                          >
                            <Download size={14} />
                          </button>
                          <button
                            onClick={() => handleOpenInCanvas(imageCacheKey, imageUrl)}
                            className="rounded-lg p-2 hover:bg-bg-raised transition-colors"
                            title="在画布中标注"
                          >
                            <Edit3 size={14} />
                          </button>
                          <button onClick={() => handleDelete(record.id, msg.id, record.image_url)} className="rounded-lg p-2 hover:bg-bg-raised" title="删除记录">
                            <Trash2 size={14} />
                          </button>
                        </div>}
                      </div>
                    );
                  }

                  return (
                    <div key={msg.id} className="rounded-md border border-accent/20 bg-accent-soft px-4 py-3 text-sm text-accent">
                      {msg.prompt}
                    </div>
                  );
                })}

                {isActiveThemeGenerating && (
                  <div className="flex justify-start">
                    <div className="inline-flex items-center gap-2 rounded-2xl border border-border bg-bg-surface px-4 py-3 text-sm text-text-secondary shadow-md">
                      <Loader2 size={16} className="animate-spin text-accent" />
                      正在生成图片...
                    </div>
                  </div>
                )}
              </div>
            </div>

            <div className="border-t border-border glass px-4 py-5 sm:px-6 lg:px-10">
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
                selectedModel={selectedImageModel}
                onSelectModel={setSelectedImageModel}
              />
            </div>
          </div>
        )}
      </div>

      {/* Draggable Divider — always mounted, hidden when closed */}
      <div
        className={(
          'flex w-[5px] shrink-0 cursor-col-resize items-center justify-center bg-transparent transition-colors hover:bg-accent/40 active:bg-accent/60 group touch-none '
          + (canvasOpen ? '' : 'hidden')
        )}
        onPointerDown={handleDividerMouseDown}
        title="拖拽调整画布宽度"
      >
        <div className="flex h-8 w-0.5 items-center justify-center rounded-full bg-bg-base-muted opacity-0 transition-opacity group-hover:opacity-100">
          <GripVertical size={10} className="text-text-muted" />
        </div>
      </div>

      {/* Excalidraw Canvas Panel — always mounted, hidden when closed */}
      <div
        ref={canvasPanelRef}
        className={(
          'flex shrink-0 flex-col border-l border-border bg-bg-surface '
          + (canvasOpen ? '' : 'hidden')
        )}
        style={{ width: canvasWidth }}
      >
          {/* Panel Header */}
          <div className="flex h-12 shrink-0 items-center justify-between border-b border-border px-4 bg-bg-surface/80">
            <span className="flex items-center gap-1.5 text-sm font-bold tracking-wide text-accent">
              <Edit3 size={14} />
              画布
            </span>
            <button
              onClick={() => setCanvasOpen(false)}
              className="rounded-lg p-1.5 text-text-secondary hover:text-text-primary hover:bg-bg-raised transition-colors"
              title="收起画布"
            >
              <ChevronRight size={16} />
            </button>
          </div>
          {/* Paste hint banner */}
          {pasteHint && (
            <div className="flex shrink-0 items-center justify-between gap-2 bg-accent/15 px-4 py-2 text-xs text-accent border-b border-accent/20 animate-fade-in">
              <span>原图已添加到聊天框，在画布中标注后粘贴回来一起发送</span>
              <button
                onClick={() => setPasteHint(false)}
                className="shrink-0 rounded-lg  p-0.5 text-accent/60 hover:text-accent hover:bg-accent/20 transition-colors"
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
