import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Check, ChevronDown, Search, Sparkles } from 'lucide-react';
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

  useEffect(() => {
    const sync = () => setModels(getProviderModelSelections(capability));
    sync();
    window.addEventListener(API_KEY_CHANGE_EVENT, sync);
    return () => window.removeEventListener(API_KEY_CHANGE_EVENT, sync);
  }, [capability]);

  const close = useCallback(() => {
    setOpen(false);
    setSearch('');
    setFocusedIndex(-1);
  }, []);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) close();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open, close]);

  useEffect(() => {
    if (!open) return;
    const timer = setTimeout(() => searchInputRef.current?.focus(), 80);
    return () => clearTimeout(timer);
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

  const selectedOption = useMemo(
    () => models.find((option) => option.value === selectedModel) || null,
    [models, selectedModel],
  );
  const selectedProvider = useMemo(
    () => detectProvider(selectedOption?.providerPresetId || selectedModel),
    [selectedOption?.providerPresetId, selectedModel],
  );
  const providerLabel = selectedOption?.providerLabel || selectedProvider.label;
  const triggerLabel = selectedOption ? `${providerLabel} · ${selectedOption.model}` : null;

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!open) return;

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setFocusedIndex((prev) => (prev + 1 < flatModelList.length ? prev + 1 : 0));
        break;
      case 'ArrowUp':
        e.preventDefault();
        setFocusedIndex((prev) => (prev - 1 >= 0 ? prev - 1 : flatModelList.length - 1));
        break;
      case 'Enter':
        e.preventDefault();
        if (focusedIndex >= 0 && focusedIndex < flatModelList.length) {
          onSelect(flatModelList[focusedIndex]);
          close();
        }
        break;
      case 'Escape':
        e.preventDefault();
        close();
        break;
    }
  };

  const handleSearchKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      close();
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      handleKeyDown(e);
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      if (flatModelList.length === 1) {
        onSelect(flatModelList[0]);
        close();
      } else if (focusedIndex >= 0 && focusedIndex < flatModelList.length) {
        onSelect(flatModelList[focusedIndex]);
        close();
      }
    }
  };

  return (
    <div ref={containerRef} className="relative inline-flex items-center" onKeyDown={handleKeyDown}>
      <button
        type="button"
        disabled={disabled}
        onClick={() => {
          if (!disabled) setOpen((prev) => !prev);
        }}
        className={
          'inline-flex h-7 max-w-[180px] items-center gap-1.5 rounded-lg border px-2 text-sm font-medium transition-all duration-200 sm:max-w-[240px] ' +
          (open
            ? 'border-accent/40 bg-accent-soft text-accent shadow-sm'
            : 'border-border bg-bg-surface/80 text-text-secondary hover:border-accent/30 hover:bg-bg-base hover:text-text-primary') +
          (disabled ? ' cursor-not-allowed opacity-50' : ' cursor-pointer')
        }
        title={selectedModel ? `${selectedOption?.model || selectedModel}\n${providerLabel}` : '选择模型'}
      >
        {triggerLabel ? (
          <>
            <span className="text-[0.75rem] leading-none" aria-hidden>{selectedProvider.emoji}</span>
            <span className="truncate text-[0.75rem]">{triggerLabel}</span>
          </>
        ) : (
          <>
            <Sparkles size={12} className="text-text-muted" />
            <span className="text-[0.75rem] text-text-muted">选择模型</span>
          </>
        )}
        <ChevronDown
          size={12}
          className={`shrink-0 text-text-muted transition-transform duration-200 ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {open && (
        <div className="absolute right-0 bottom-[calc(100%+6px)] z-30 flex w-[min(300px,calc(100vw-2rem))] flex-col overflow-hidden rounded-xl border border-border/80 bg-bg-raised animate-fade-in">
          <div className="flex items-center gap-2 border-b border-border/60 px-3 py-2.5">
            <Search size={14} className="shrink-0 text-text-muted" />
            <input
              ref={searchInputRef}
              type="text"
              value={search}
              onChange={(e) => updateSearch(e.target.value)}
              onKeyDown={handleSearchKeyDown}
              placeholder="搜索模型..."
              className="min-w-0 flex-1 bg-transparent text-[0.8125rem] text-text-primary placeholder:text-text-muted/60 outline-none"
            />
            {search && (
              <button
                type="button"
                onClick={() => updateSearch('')}
                className="rounded p-0.5 text-text-muted hover:text-text-secondary transition-colors"
                aria-label="清除搜索"
              >
                <span className="text-[0.6875rem]">×</span>
              </button>
            )}
          </div>

          <div className="max-h-[280px] overflow-y-auto overscroll-contain p-2">
            {groupedModels.length === 0 ? (
              <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
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
                <div key={group.id} className="mb-0.5 last:mb-0">
                  <div className="flex items-center gap-1.5 px-2 py-1.5">
                    <span
                      className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-lg  text-[0.6875rem] leading-none ${group.provider.color}`}
                      aria-hidden
                    >
                      {group.provider.emoji}
                    </span>
                    <span className="text-[0.6875rem] font-medium uppercase tracking-wider text-text-muted/80">
                      {group.provider.label}
                    </span>
                    <span className="text-[0.625rem] text-text-muted/50">{group.models.length}</span>
                  </div>
                  {group.models.map((option) => {
                    const selected = option.value === selectedModel;
                    const flatIdx = flatModelList.indexOf(option.value);
                    const focused = flatIdx === focusedIndex;
                    return (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => {
                          onSelect(option.value);
                          close();
                        }}
                        onMouseEnter={() => setFocusedIndex(flatIdx)}
                        className={
                          'flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left transition-colors duration-100 ' +
                          (selected
                            ? 'bg-accent-soft text-accent'
                            : focused
                              ? 'bg-bg-surface text-text-primary'
                              : 'text-text-primary hover:bg-bg-surface')
                        }
                      >
                        <span className="truncate text-[0.8125rem] font-medium">{option.model}</span>
                        {selected && <Check size={14} className="ml-auto shrink-0 text-accent" />}
                      </button>
                    );
                  })}
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
