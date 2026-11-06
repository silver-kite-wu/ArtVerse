import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Check, ChevronDown, Search, Sparkles } from 'lucide-react';
import { createPortal } from 'react-dom';
import {
  API_KEY_CHANGE_EVENT,
  getProviderModelSelections,
  type ApiCapability,
  type ProviderModelOption,
} from '../api';

interface ModelSwitcherProps {
  capability: ApiCapability;
  selectedModel: string;
  onSelect: (model: string) => void;
  disabled?: boolean;
}

interface ProviderMeta {
  label: string;
  emoji: string;
  color: string;
}

interface GroupedModels {
  id: string;
  provider: ProviderMeta;
  models: ProviderModelOption[];
}

const PROVIDER_PATTERNS: Array<{ test: (id: string) => boolean; label: string; emoji: string; color: string }> = [
  { test: (id) => id.startsWith('deepseek'), label: 'DeepSeek', emoji: 'D', color: 'bg-blue-100 text-blue-700' },
  { test: (id) => /^gpt-|^o[134]-|^chatgpt/.test(id), label: 'OpenAI', emoji: 'O', color: 'bg-emerald-100 text-emerald-700' },
  { test: (id) => id.startsWith('claude'), label: 'Claude', emoji: 'C', color: 'bg-amber-100 text-amber-700' },
  { test: (id) => id.startsWith('gemini'), label: 'Gemini', emoji: 'G', color: 'bg-sky-100 text-sky-700' },
  { test: (id) => id.startsWith('doubao'), label: 'Doubao', emoji: 'B', color: 'bg-teal-100 text-teal-700' },
  { test: (id) => id.startsWith('qwen'), label: 'Qwen', emoji: 'Q', color: 'bg-purple-100 text-purple-700' },
  { test: (id) => id.startsWith('grok'), label: 'Grok', emoji: 'X', color: 'bg-indigo-100 text-indigo-700' },
  { test: (id) => id.startsWith('glm'), label: 'GLM', emoji: 'Z', color: 'bg-cyan-100 text-cyan-700' },
  { test: (id) => id.startsWith('kimi'), label: 'Kimi', emoji: 'K', color: 'bg-violet-100 text-violet-700' },
  { test: (id) => /minimax|abab/.test(id), label: 'MiniMax', emoji: 'M', color: 'bg-fuchsia-100 text-fuchsia-700' },
  { test: (id) => /seedance|jimeng/.test(id), label: 'Jimeng', emoji: 'J', color: 'bg-rose-100 text-rose-700' },
  { test: (id) => /flux|stable|schnell/.test(id), label: 'Stability', emoji: 'S', color: 'bg-lime-100 text-lime-700' },
  { test: (id) => /black-forest|midjourney/.test(id), label: 'Image', emoji: 'I', color: 'bg-pink-100 text-pink-700' },
  { test: (id) => id.includes('/'), label: 'OpenRouter', emoji: 'R', color: 'bg-orange-100 text-orange-700' },
];

function detectProvider(modelId: string): ProviderMeta {
  const lower = modelId.toLowerCase();
  for (const pattern of PROVIDER_PATTERNS) {
    if (pattern.test(lower)) {
      return { label: pattern.label, emoji: pattern.emoji, color: pattern.color };
    }
  }
  return { label: 'Custom', emoji: 'U', color: 'bg-bg-raised text-text-secondary' };
}

export default function ModelSwitcher({ capability, selectedModel, onSelect, disabled }: ModelSwitcherProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [models, setModels] = useState<ProviderModelOption[]>(() => getProviderModelSelections(capability));
  const [focusedIndex, setFocusedIndex] = useState(-1);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const menuId = useId();
  const listId = `${menuId}-list`;

  useEffect(() => {
    const sync = () => setModels(getProviderModelSelections(capability));
    sync();
    window.addEventListener(API_KEY_CHANGE_EVENT, sync);
    return () => window.removeEventListener(API_KEY_CHANGE_EVENT, sync);
  }, [capability]);

  const close = useCallback((restoreFocus = false) => {
    setOpen(false);
    setSearch('');
    setFocusedIndex(-1);
    if (restoreFocus) triggerRef.current?.focus({ preventScroll: true });
  }, []);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node) && !menuRef.current?.contains(e.target as Node)) close();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open, close]);

  useEffect(() => {
    if (open) searchInputRef.current?.focus({ preventScroll: true });
  }, [open]);

  const filteredModels = useMemo(() => {
    if (!search.trim()) return models;
    const q = search.toLowerCase();
    return models.filter((option) => {
      if (option.model.toLowerCase().includes(q)) return true;
      if (option.providerLabel.toLowerCase().includes(q)) return true;
      return detectProvider(option.providerPresetId).label.toLowerCase().includes(q);
    });
  }, [models, search]);

  useLayoutEffect(() => {
    if (!open) return;
    const menu = menuRef.current;
    const trigger = triggerRef.current;
    if (!menu || !trigger) return;
    const composer = trigger.closest('.ai-composer');
    const positionMenu = () => {
      const anchor = trigger.getBoundingClientRect();
      const composerBounds = composer?.getBoundingClientRect();
      const viewport = window.visualViewport;
      const viewportLeft = viewport?.offsetLeft ?? 0;
      const viewportTop = viewport?.offsetTop ?? 0;
      const viewportWidth = viewport?.width ?? window.innerWidth;
      const viewportHeight = viewport?.height ?? window.innerHeight;
      const padding = 12;
      const gap = 8;
      const leftEdge = viewportLeft + padding;
      const rightEdge = viewportLeft + viewportWidth - padding;
      const topEdge = viewportTop + padding;
      const bottomEdge = viewportTop + viewportHeight - padding;
      if (anchor.bottom < topEdge || anchor.top > bottomEdge || anchor.right < leftEdge || anchor.left > rightEdge) {
        close();
        return;
      }
      const width = Math.max(0, Math.min(282, (composerBounds?.width ?? 302) - 20, rightEdge - leftEdge));
      const above = Math.max(0, anchor.top - topEdge - gap);
      const below = Math.max(0, bottomEdge - anchor.bottom - gap);
      const openAbove = above >= Math.min(260, below);
      menu.style.width = `${width}px`;
      menu.style.maxHeight = `${openAbove ? above : below}px`;
      // Anchor to the button itself, including after the panel is resized.
      // The composer supplies only the width limit, never the horizontal origin.
      menu.style.left = `${Math.max(leftEdge, Math.min(anchor.right - width, rightEdge - width))}px`;
      menu.style.top = `${openAbove ? Math.max(topEdge, anchor.top - gap - menu.getBoundingClientRect().height) : anchor.bottom + gap}px`;
      menu.dataset.placement = openAbove ? 'top-end' : 'bottom-end';
    };
    positionMenu();
    const observer = new ResizeObserver(positionMenu);
    observer.observe(menu);
    observer.observe(trigger);
    if (composer) observer.observe(composer);
    const onScroll = (event: Event) => {
      if (!menu.contains(event.target as Node)) positionMenu();
    };
    window.addEventListener('resize', positionMenu);
    window.addEventListener('scroll', onScroll, true);
    window.visualViewport?.addEventListener('resize', positionMenu);
    window.visualViewport?.addEventListener('scroll', positionMenu);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', positionMenu);
      window.removeEventListener('scroll', onScroll, true);
      window.visualViewport?.removeEventListener('resize', positionMenu);
      window.visualViewport?.removeEventListener('scroll', positionMenu);
    };
  }, [open, close]);

  const groupedModels = useMemo<GroupedModels[]>(() => {
    const groups: Record<string, GroupedModels> = {};
    for (const option of filteredModels) {
      const providerLabel = option.providerLabel;
      const providerMeta = detectProvider(option.providerPresetId);
      if (!groups[option.entryId]) {
        groups[option.entryId] = {
          id: option.entryId,
          provider: { ...providerMeta, label: providerLabel },
          models: [],
        };
      }
      groups[option.entryId].models.push(option);
    }
    return Object.values(groups).sort((a, b) => a.provider.label.localeCompare(b.provider.label));
  }, [filteredModels]);

  const flatModelList = useMemo(
    () => groupedModels.flatMap((group) => group.models.map((option) => option.value)),
    [groupedModels],
  );

  const updateSearch = useCallback((value: string) => {
    setSearch(value);
    setFocusedIndex(-1);
  }, []);

  useEffect(() => {
    if (open && focusedIndex >= 0) {
      document.getElementById(`${menuId}-option-${focusedIndex}`)?.scrollIntoView({ block: 'nearest' });
    }
  }, [open, focusedIndex, menuId]);

  const selectedOption = useMemo(
    () => models.find((option) => option.value === selectedModel) || null,
    [models, selectedModel],
  );
  const selectedProvider = useMemo(
    () => detectProvider(selectedOption?.providerPresetId || selectedModel),
    [selectedOption?.providerPresetId, selectedModel],
  );
  const providerLabel = selectedOption?.providerLabel || selectedProvider.label;
  const triggerLabel = selectedOption?.model || null;

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!open || e.nativeEvent.isComposing || e.keyCode === 229) return;

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        e.stopPropagation();
        if (!flatModelList.length) break;
        setFocusedIndex((prev) => (prev + 1 < flatModelList.length ? prev + 1 : 0));
        break;
      case 'ArrowUp':
        e.preventDefault();
        e.stopPropagation();
        if (!flatModelList.length) break;
        setFocusedIndex((prev) => (prev - 1 >= 0 ? prev - 1 : flatModelList.length - 1));
        break;
      case 'Enter':
        e.preventDefault();
        e.stopPropagation();
        if (flatModelList.length === 1 || (focusedIndex >= 0 && focusedIndex < flatModelList.length)) {
          onSelect(flatModelList[flatModelList.length === 1 ? 0 : focusedIndex]);
          close(true);
        }
        break;
      case 'Escape':
        e.preventDefault();
        e.stopPropagation();
        close(true);
        break;
      case 'Tab':
        close(true);
        break;
    }
  };

  return (
    <div ref={containerRef} className="model-switcher" onKeyDown={handleKeyDown}>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        onClick={() => {
          if (!disabled) {
            if (open) close();
            else setOpen(true);
          }
        }}
        className="model-switcher__trigger"
        aria-label={triggerLabel ? `选择模型：${triggerLabel}` : '选择模型'}
        aria-expanded={open}
        aria-haspopup="listbox"
        aria-controls={open ? listId : undefined}
        title={selectedModel ? `${selectedOption?.model || selectedModel}\n${providerLabel}` : '选择模型'}
      >
        {triggerLabel ? (
          <>
            <span className="model-switcher__label">{triggerLabel}</span>
          </>
        ) : (
          <>
            <Sparkles size={12} className="text-text-muted" />
            <span className="model-switcher__label">选择模型</span>
          </>
        )}
        <ChevronDown
          size={14}
          className="model-switcher__chevron"
        />
      </button>

      {open && createPortal(
        <div ref={menuRef} id={menuId} className="model-switcher__menu">
          <div className="model-switcher__heading"><span>选择模型</span><span>{models.length} 个可用</span></div>
          <div className="model-switcher__search">
            <Search size={14} className="shrink-0 text-text-muted" />
            <input
              ref={searchInputRef}
              type="text"
              value={search}
              onChange={(e) => updateSearch(e.target.value)}
              role="combobox"
              aria-label="搜索模型"
              aria-expanded="true"
              aria-controls={listId}
              aria-autocomplete="list"
              aria-activedescendant={focusedIndex >= 0 && focusedIndex < flatModelList.length ? `${menuId}-option-${focusedIndex}` : undefined}
              placeholder="搜索模型..."
            />
            {search && (
              <button
                type="button"
                onClick={() => updateSearch('')}
                className="model-switcher__clear"
                aria-label="清除搜索"
              >
                <span className="text-[0.6875rem]">×</span>
              </button>
            )}
          </div>

          <div id={listId} role="listbox" aria-label="可用模型" className="model-switcher__list">
            {groupedModels.length === 0 ? (
              <div className="model-switcher__empty" role="presentation">
                <Search size={20} className="text-text-muted/30" />
                <p className="text-[0.8125rem] text-text-muted">
                  {search ? '没有匹配的模型' : '暂无可选模型'}
                </p>
                {search && (
                  <button
                    type="button"
                    onClick={() => updateSearch('')}
                    className="text-[0.75rem] text-accent hover:text-accent-hover transition-colors"
                  >
                    清除搜索
                  </button>
                )}
              </div>
            ) : (
              groupedModels.map((group) => (
                <div key={group.id} className="model-switcher__group" role="group" aria-label={group.provider.label}>
                  <div className="model-switcher__provider" aria-hidden="true">
                    <span className="model-switcher__provider-name">
                      {group.provider.label}
                    </span>
                    <span>{group.models.length}</span>
                  </div>
                  {group.models.map((option) => {
                    const selected = option.value === selectedModel;
                    const flatIdx = flatModelList.indexOf(option.value);
                    const focused = flatIdx === focusedIndex;
                    return (
                      <button
                        key={option.value}
                        type="button"
                        id={`${menuId}-option-${flatIdx}`}
                        role="option"
                        aria-selected={selected}
                        tabIndex={-1}
                        onClick={() => {
                          onSelect(option.value);
                          close(true);
                        }}
                        className={`model-switcher__option${focused ? ' is-focused' : ''}`}
                      >
                        <span className="model-switcher__option-label" title={option.model}>{option.model}{selected && <small>当前使用</small>}</span>
                        {selected && <Check size={14} />}
                      </button>
                    );
                  })}
                </div>
              ))
            )}
          </div>
        </div>, document.body
      )}
    </div>
  );
}
