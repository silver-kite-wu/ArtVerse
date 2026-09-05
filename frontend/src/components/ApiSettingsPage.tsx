import { useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  ArrowLeft, Bot, ChevronRight, CircleCheck, Database, ExternalLink, Eye, EyeOff,
  Image as ImageIcon, KeyRound, LoaderCircle, Pencil, Play, Plus, PowerOff, RefreshCw, Save, Trash2, Workflow,
} from 'lucide-react';
import {
  activateUserProviderConfig,
  deactivateUserProviderConfig,
  deleteUserProviderConfig,
  discoverProviderModels,
  getApiKeySettings,
  getUserProviderApiKey,
  getUserProviderConfigs,
  mergeServerProviderConfigs,
  saveApiKeySettings,
  saveUserProviderConfig,
  toProviderEndpointConfig,
  type ApiCapability,
  type ApiKeySettings,
  type ProviderPresetConfig,
} from '../api';
import EmbeddingSettingsPanel from './EmbeddingSettingsPanel';

type ProviderPreset = {
  id: string;
  label: string;
  docsUrl?: string;
  baseUrl: string;
  models: string[];
};

type Editor = { mode: 'list' } | { mode: 'edit'; entryId: string; isNew: boolean };
type CapabilityTab = ApiCapability | 'embedding';

const PROVIDER_PRESETS: Record<ApiCapability, ProviderPreset[]> = {
  llm: [
    { id: 'deepseek', label: 'DeepSeek Official', docsUrl: 'https://platform.deepseek.com/usage', baseUrl: 'https://api.deepseek.com', models: ['deepseek-v4-flash', 'deepseek-chat'] },
    { id: 'openai', label: 'OpenAI Official', docsUrl: 'https://platform.openai.com/api-keys', baseUrl: 'https://api.openai.com/v1', models: ['gpt-4.1-mini', 'gpt-4.1'] },
    { id: 'openrouter', label: 'OpenRouter', docsUrl: 'https://openrouter.ai/keys', baseUrl: 'https://openrouter.ai/api/v1', models: ['openai/gpt-4.1-mini', 'anthropic/claude-3.7-sonnet'] },
    { id: 'siliconflow', label: 'SiliconFlow', docsUrl: 'https://cloud.siliconflow.cn/account/ak', baseUrl: 'https://api.siliconflow.cn/v1', models: ['deepseek-ai/DeepSeek-V3', 'Qwen/Qwen3-32B'] },
    { id: 'qwen', label: 'Qwen Bailian', docsUrl: 'https://bailian.console.aliyun.com/', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', models: ['qwen-plus', 'qwen-max'] },
    { id: 'ark', label: 'Volcengine Ark', docsUrl: 'https://console.volcengine.com/ark', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', models: ['doubao-seed-1-6-flash-250615', 'doubao-1-5-pro-32k-250115'] },
    { id: 'custom', label: '自定义 OpenAI 兼容服务', baseUrl: 'https://your-gateway.example.com/v1', models: ['your-model-name'] },
  ],
  image: [
    { id: 'image2', label: 'Image2 Official', docsUrl: 'https://api.duojie.games/console/token', baseUrl: 'https://api.duojie.games/v1', models: ['gpt-image-2'] },
    { id: 'openai-images', label: 'OpenAI Images', docsUrl: 'https://platform.openai.com/api-keys', baseUrl: 'https://api.openai.com/v1', models: ['gpt-image-1'] },
    { id: 'openrouter-images', label: 'OpenRouter Images', docsUrl: 'https://openrouter.ai/keys', baseUrl: 'https://openrouter.ai/api/v1', models: ['openai/gpt-image-1'] },
    { id: 'siliconflow-images', label: 'SiliconFlow Images', docsUrl: 'https://cloud.siliconflow.cn/account/ak', baseUrl: 'https://api.siliconflow.cn/v1', models: ['black-forest-labs/FLUX.1-schnell', 'stabilityai/stable-image-ultra'] },
    { id: 'custom', label: '自定义图像服务', baseUrl: 'https://your-image-gateway.example.com/v1', models: ['your-image-model'] },
  ],
  workflow: [
    { id: 'coze', label: 'Coze Official', docsUrl: 'https://www.coze.cn/open/docs/developer_guides/pat', baseUrl: 'https://api.coze.cn', models: ['workflow'] },
    { id: 'dify', label: 'Dify Workflow', docsUrl: 'https://cloud.dify.ai/apps', baseUrl: 'https://api.dify.ai/v1', models: ['workflow'] },
    { id: 'custom', label: '自定义工作流服务', baseUrl: 'https://your-workflow.example.com/v1', models: ['workflow-or-agent'] },
  ],
};

const TABS: Array<{ id: CapabilityTab; label: string; description: string; icon: typeof Bot }> = [
  { id: 'llm', label: '对话模型', description: '小说创作与智能体使用的语言模型', icon: Bot },
  { id: 'image', label: '图像模型', description: '漫画与插图生成服务', icon: ImageIcon },
  { id: 'workflow', label: '工作流 / Agent', description: 'Coze、Dify 与自定义工作流', icon: Workflow },
  { id: 'embedding', label: '向量模型', description: '知识库检索所用的嵌入模型', icon: Database },
];

function createEntryId(capability: ApiCapability) {
  return `${capability}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function getPreset(capability: ApiCapability, presetId: string) {
  return PROVIDER_PRESETS[capability].find((preset) => preset.id === presetId) || PROVIDER_PRESETS[capability][0];
}

function createEntry(capability: ApiCapability, active: boolean): ProviderPresetConfig {
  const preset = PROVIDER_PRESETS[capability][0];
  return {
    active,
    presetId: preset.id,
    label: preset.label,
    mode: 'official',
    apiKey: '',
    baseUrl: preset.baseUrl,
    selectedModels: [...preset.models],
    availableModels: [...preset.models],
  };
}

function updateEntry(settings: ApiKeySettings, capability: ApiCapability, entryId: string, patch: Partial<ProviderPresetConfig>): ApiKeySettings {
  const entry = settings.providers[capability].entries[entryId];
  if (!entry) return settings;
  return {
    ...settings,
    providers: {
      ...settings.providers,
      [capability]: {
        ...settings.providers[capability],
        entries: { ...settings.providers[capability].entries, [entryId]: { ...entry, ...patch } },
      },
    },
  };
}

function entriesFor(settings: ApiKeySettings, capability: ApiCapability): Array<[string, ProviderPresetConfig]> {
  return Object.entries(settings.providers[capability].entries)
    .filter(([, entry]) => entry.remoteId !== undefined || entry.apiKey.trim() || entry.label !== getPreset(capability, entry.presetId).label)
    .sort(([, left], [, right]) => Number(right.active) - Number(left.active) || left.label.localeCompare(right.label));
}

function supportsMultipleActiveProviders(capability: ApiCapability): boolean {
  return capability === 'llm' || capability === 'image';
}

function CapabilityTabs({ active, onChange, settings }: { active: CapabilityTab; onChange: (tab: CapabilityTab) => void; settings: ApiKeySettings }) {
  return (
    <nav className="flex gap-1 overflow-x-auto border-b border-border px-5" aria-label="API 配置分类">
      {TABS.map((tab) => {
        const Icon = tab.icon;
        const count = tab.id === 'embedding' ? null : entriesFor(settings, tab.id).length;
        const selected = tab.id === active;
        return (
          <button key={tab.id} type="button" onClick={() => onChange(tab.id)} className={`flex shrink-0 items-center gap-2 border-b-2 px-3 py-3 text-sm transition-colors ${selected ? 'border-accent text-accent' : 'border-transparent text-text-secondary hover:text-text-primary'}`}>
            <Icon size={16} />
            <span>{tab.label}</span>
            {count !== null && count > 0 ? <span className={`rounded-full px-1.5 py-0.5 text-[0.6875rem] ${selected ? 'bg-accent-muted text-accent' : 'bg-bg-surface text-text-muted'}`}>{count}</span> : null}
          </button>
        );
      })}
    </nav>
  );
}

export default function ApiSettingsPage() {
  const [settings, setSettings] = useState<ApiKeySettings>(() => getApiKeySettings());
  const [activeTab, setActiveTab] = useState<CapabilityTab>('llm');
  const [editor, setEditor] = useState<Editor>({ mode: 'list' });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadingModels, setLoadingModels] = useState(false);
  const [loadingKey, setLoadingKey] = useState(false);
  const [switchingEntryId, setSwitchingEntryId] = useState<string | null>(null);
  const [showKey, setShowKey] = useState(false);
  const [error, setError] = useState('');

  const capability = activeTab === 'embedding' ? null : activeTab;
  const entries = useMemo(() => capability ? entriesFor(settings, capability) : [], [settings, capability]);
  const editingEntry = useMemo(() => capability && editor.mode === 'edit' ? settings.providers[capability].entries[editor.entryId] : null, [settings, capability, editor]);

  const commit = (next: ApiKeySettings) => {
    setSettings(next);
    saveApiKeySettings(next);
  };

  const loadRemote = async () => {
    setLoading(true);
    try {
      const remote = await getUserProviderConfigs();
      const merged = mergeServerProviderConfigs(getApiKeySettings(), remote);
      commit(merged);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '读取 API 配置失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void loadRemote(); }, []);

  const switchTab = (next: CapabilityTab) => {
    setActiveTab(next);
    setEditor({ mode: 'list' });
    setError('');
    setShowKey(false);
  };

  const startNew = () => {
    if (!capability) return;
    const entryId = createEntryId(capability);
    const next: ApiKeySettings = {
      ...settings,
      providers: {
        ...settings.providers,
        [capability]: {
          ...settings.providers[capability],
          entries: { ...settings.providers[capability].entries, [entryId]: createEntry(capability, entries.length === 0) },
        },
      },
    };
    commit(next);
    setEditor({ mode: 'edit', entryId, isNew: true });
    setShowKey(false);
  };

  const cancelEdit = () => {
    if (!capability || editor.mode !== 'edit') return;
    if (editor.isNew) {
      const nextEntries = { ...settings.providers[capability].entries };
      delete nextEntries[editor.entryId];
      commit({ ...settings, providers: { ...settings.providers, [capability]: { ...settings.providers[capability], entries: nextEntries } } });
    }
    setEditor({ mode: 'list' });
    setError('');
  };

  const startEdit = async (entryId: string, entry: ProviderPresetConfig) => {
    setEditor({ mode: 'edit', entryId, isNew: false });
    setShowKey(false);
    setError('');
    if (!entry.remoteId) return;
    setLoadingKey(true);
    try {
      const apiKey = await getUserProviderApiKey(entry.remoteId);
      setSettings((current) => updateEntry(current, capability!, entryId, { apiKey }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '读取 API Key 失败');
    } finally {
      setLoadingKey(false);
    }
  };

  const saveEntry = async () => {
    if (!capability || editor.mode !== 'edit' || !editingEntry) return;
    if (!editingEntry.label.trim() || !editingEntry.baseUrl.trim()) {
      setError('请填写配置名称和 Base URL。');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const saved = await saveUserProviderConfig(capability, toProviderEndpointConfig(editingEntry));
      const selectedModels = saved.model
        .split(/[\n,]+/)
        .map((model) => model.trim())
        .filter(Boolean);
      let next = updateEntry(settings, capability, editor.entryId, {
        remoteId: saved.configId,
        active: saved.active,
        apiKeyMasked: saved.apiKeyMasked,
        apiKey: editingEntry.apiKey,
        presetId: saved.presetId,
        label: saved.label,
        baseUrl: saved.baseUrl,
        selectedModels,
        availableModels: Array.from(new Set([...selectedModels, ...editingEntry.availableModels])),
      });
      if (saved.active) {
        const provider = next.providers[capability];
        const multiple = supportsMultipleActiveProviders(capability);
        next = {
          ...next,
          providers: {
            ...next.providers,
            [capability]: {
              activePresetId: editor.entryId,
              entries: Object.fromEntries(Object.entries(provider.entries).map(([id, entry]) => [id, {
                ...entry,
                active: id === editor.entryId ? true : multiple ? entry.active : false,
              }])),
            },
          },
        };
      }
      commit(next);
      setEditor({ mode: 'list' });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '保存配置失败');
    } finally {
      setSaving(false);
    }
  };

  const fetchModels = async () => {
    if (!capability || editor.mode !== 'edit' || !editingEntry) return;
    if (!editingEntry.apiKey.trim() && !editingEntry.apiKeyMasked) {
      setError('请先填写 API Key，再获取模型。');
      return;
    }
    setLoadingModels(true);
    setError('');
    try {
      const models = await discoverProviderModels(capability, toProviderEndpointConfig(editingEntry));
      commit(updateEntry(settings, capability, editor.entryId, {
        availableModels: Array.from(new Set([...models, ...editingEntry.selectedModels])),
        selectedModels: editingEntry.selectedModels.length > 0 ? editingEntry.selectedModels : models.slice(0, 1),
      }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '获取模型失败');
    } finally {
      setLoadingModels(false);
    }
  };

  const setActive = async (entryId: string, entry: ProviderPresetConfig) => {
    if (!capability || entry.active) return;
    if (!entry.remoteId) {
      setError(`“${entry.label}”尚未同步到后端，请编辑并保存配置后再启用。`);
      return;
    }
    setSwitchingEntryId(entryId);
    try {
      setError('');
      await activateUserProviderConfig(entry.remoteId);
      const provider = settings.providers[capability];
      const multiple = supportsMultipleActiveProviders(capability);
      commit({
        ...settings,
        providers: {
          ...settings.providers,
          [capability]: {
            activePresetId: entryId,
            entries: Object.fromEntries(Object.entries(provider.entries).map(([id, item]) => [id, {
              ...item,
              active: id === entryId ? true : multiple ? item.active : false,
            }])),
          },
        },
      });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '切换启用配置失败');
    } finally {
      setSwitchingEntryId(null);
    }
  };

  const setInactive = async (entryId: string, entry: ProviderPresetConfig) => {
    if (!capability || !entry.active) return;
    if (!entry.remoteId) {
      setError(`“${entry.label}”尚未同步到后端，无法更改启用状态。`);
      return;
    }
    setSwitchingEntryId(entryId);
    try {
      setError('');
      await deactivateUserProviderConfig(entry.remoteId);
      const provider = settings.providers[capability];
      const entries = Object.fromEntries(Object.entries(provider.entries).map(([id, item]) => [id, {
        ...item,
        active: id === entryId ? false : item.active,
      }]));
      const activePresetId = provider.activePresetId === entryId
        ? Object.entries(entries).find(([, item]) => item.active)?.[0] || Object.keys(entries)[0] || entryId
        : provider.activePresetId;
      commit({
        ...settings,
        providers: {
          ...settings.providers,
          [capability]: { activePresetId, entries },
        },
      });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '停用配置失败');
    } finally {
      setSwitchingEntryId(null);
    }
  };

  const deleteEntry = async (entryId: string, entry: ProviderPresetConfig) => {
    if (!capability || !confirm(`确定删除“${entry.label}”吗？`)) return;
    try {
      if (entry.remoteId) await deleteUserProviderConfig(entry.remoteId);
      const nextEntries = { ...settings.providers[capability].entries };
      delete nextEntries[entryId];
      commit({ ...settings, providers: { ...settings.providers, [capability]: { ...settings.providers[capability], entries: nextEntries } } });
      await loadRemote();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '删除配置失败');
    }
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-bg-base">
      <header className="border-b border-border bg-bg-surface/80 px-5 py-4 backdrop-blur-sm">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg border border-accent/15 bg-accent-soft"><KeyRound size={18} className="text-accent" /></div>
          <div><h1 className="font-display text-lg font-semibold text-text-primary">API 配置</h1><p className="text-xs text-text-secondary">管理可用于创作、绘图与工作流的供应商配置。</p></div>
        </div>
      </header>

      <CapabilityTabs active={activeTab} onChange={switchTab} settings={settings} />

      <main className="min-h-0 flex-1 overflow-y-auto px-5 py-5">
        <div className="mx-auto w-full max-w-6xl">
          {error ? <div role="alert" className="mb-4 border border-accent/25 bg-accent-muted/25 px-4 py-3 text-sm text-accent">{error}</div> : null}
          {activeTab === 'embedding' ? <EmbeddingSettingsPanel /> : null}
          {capability && editor.mode === 'list' ? (
            <section>
              <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
                <div><h2 className="text-base font-semibold text-text-primary">{TABS.find((tab) => tab.id === capability)?.label}</h2><p className="mt-1 text-sm text-text-secondary">{TABS.find((tab) => tab.id === capability)?.description}</p></div>
                <button type="button" onClick={startNew} className="inline-flex items-center gap-1.5 rounded-lg bg-accent px-3 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover"><Plus size={16} />新建供应商</button>
              </div>
              {loading ? <div className="border border-border bg-bg-surface px-4 py-8 text-center text-sm text-text-secondary">正在读取已保存的供应商配置...</div> : null}
              {!loading && entries.length === 0 ? <div className="border border-dashed border-border bg-bg-surface/60 px-6 py-16 text-center"><div className="text-sm font-medium text-text-primary">还没有供应商配置</div><p className="mt-2 text-sm text-text-secondary">新建一条配置后，可在创作和生成页面选择其中的模型。</p><button type="button" onClick={startNew} className="mt-5 inline-flex items-center gap-1.5 rounded-lg border border-border bg-bg-surface px-3 py-2 text-sm text-text-secondary hover:border-accent/40 hover:text-accent"><Plus size={16} />新建供应商</button></div> : null}
              {!loading && entries.length > 0 ? <div className="space-y-3">{entries.map(([entryId, entry]) => <ProviderRow key={entryId} capability={capability} entry={entry} switching={switchingEntryId === entryId} onEdit={() => void startEdit(entryId, entry)} onActivate={() => void setActive(entryId, entry)} onDeactivate={() => void setInactive(entryId, entry)} onDelete={() => void deleteEntry(entryId, entry)} />)}</div> : null}
            </section>
          ) : null}
          {capability && editor.mode === 'edit' && editingEntry ? <ProviderEditor capability={capability} entry={editingEntry} showKey={showKey} loadingKey={loadingKey} saving={saving} loadingModels={loadingModels} onBack={cancelEdit} onSave={() => void saveEntry()} onShowKey={() => setShowKey((value) => !value)} onChange={(patch) => commit(updateEntry(settings, capability, editor.entryId, patch))} onPresetChange={(presetId) => { const preset = getPreset(capability, presetId); commit(updateEntry(settings, capability, editor.entryId, { presetId, label: editingEntry.label === getPreset(capability, editingEntry.presetId).label ? preset.label : editingEntry.label, baseUrl: preset.baseUrl, mode: presetId === 'custom' ? 'custom' : 'official', availableModels: preset.models, selectedModels: preset.models })); }} onFetchModels={() => void fetchModels()} /> : null}
        </div>
      </main>
    </div>
  );
}

function ProviderRow({ capability, entry, switching, onEdit, onActivate, onDeactivate, onDelete }: { capability: ApiCapability; entry: ProviderPresetConfig; switching: boolean; onEdit: () => void; onActivate: () => void; onDeactivate: () => void; onDelete: () => void }) {
  const preset = getPreset(capability, entry.presetId);
  return <article aria-busy={switching} className={`group flex min-h-24 items-center gap-4 rounded-lg border px-5 py-4 transition-[border-color,background-color,box-shadow] ${entry.active ? 'border-accent/55 bg-accent-muted/15 shadow-sm' : 'border-border bg-bg-surface/55 hover:border-accent/30 hover:bg-bg-surface'}`}>
    <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border text-sm font-semibold ${entry.active ? 'border-accent/25 bg-bg-surface text-accent' : 'border-border bg-bg-raised/70 text-text-secondary'}`}>{entry.label.slice(0, 1).toUpperCase()}</div>
    <div className="min-w-0 flex-1"><div className="flex flex-wrap items-center gap-2"><h3 className="truncate font-medium text-text-primary">{entry.label}</h3>{entry.active ? <span className="inline-flex items-center gap-1 rounded-full bg-success/10 px-2 py-0.5 text-xs text-success"><CircleCheck size={12} />已启用</span> : null}<span className="rounded-full bg-bg-raised px-2 py-0.5 text-xs text-text-secondary">{preset.label}</span></div><div className="mt-1 truncate text-sm text-accent-tertiary">{entry.baseUrl}</div><div className="mt-1 flex flex-wrap gap-x-4 text-xs text-text-muted"><span>{entry.selectedModels.length} 个已选模型</span><span>{entry.apiKey || entry.apiKeyMasked ? '密钥已保存' : '尚未填写密钥'}</span></div></div>
    <div className="flex shrink-0 items-center gap-1">{entry.active ? <button type="button" disabled={switching} title="停用供应商" onClick={onDeactivate} className="inline-flex min-w-20 items-center justify-center gap-1.5 rounded-lg border border-border bg-bg-surface px-3 py-2 text-xs font-medium text-text-secondary transition-colors hover:border-accent/40 hover:text-accent disabled:cursor-wait disabled:opacity-60">{switching ? <LoaderCircle size={14} className="animate-spin" /> : <PowerOff size={14} />}<span>{switching ? '处理中' : '停用'}</span></button> : <button type="button" disabled={switching} title={entry.remoteId ? '设为使用中' : '保存配置后即可启用'} onClick={onActivate} className="inline-flex min-w-20 items-center justify-center gap-1.5 rounded-lg bg-accent px-3 py-2 text-xs font-semibold text-white transition-[background-color,box-shadow] hover:bg-accent-hover hover:shadow disabled:cursor-wait disabled:opacity-60">{switching ? <LoaderCircle size={15} className="animate-spin" /> : <Play size={15} fill="currentColor" />}<span>{switching ? '启用中' : '启用'}</span></button>}<button type="button" disabled={switching} title="编辑供应商" onClick={onEdit} className="rounded-md p-2 text-text-secondary transition-colors hover:bg-bg-surface hover:text-accent disabled:opacity-40"><Pencil size={17} /></button><button type="button" disabled={switching} title="删除供应商" onClick={onDelete} className="rounded-md p-2 text-text-muted transition-colors hover:bg-accent-muted/30 hover:text-accent disabled:opacity-40"><Trash2 size={17} /></button><ChevronRight size={17} className="hidden text-text-muted sm:block" /></div>
  </article>;
}

function ProviderEditor({ capability, entry, showKey, loadingKey, saving, loadingModels, onBack, onSave, onShowKey, onChange, onPresetChange, onFetchModels }: { capability: ApiCapability; entry: ProviderPresetConfig; showKey: boolean; loadingKey: boolean; saving: boolean; loadingModels: boolean; onBack: () => void; onSave: () => void; onShowKey: () => void; onChange: (patch: Partial<ProviderPresetConfig>) => void; onPresetChange: (presetId: string) => void; onFetchModels: () => void }) {
  const preset = getPreset(capability, entry.presetId);
  const supportsModels = capability !== 'workflow';
  return <section className="pb-20"><div className="mb-6 flex items-center justify-between gap-3"><button type="button" onClick={onBack} className="inline-flex items-center gap-1.5 text-sm text-text-secondary hover:text-text-primary"><ArrowLeft size={16} />返回供应商列表</button><button type="button" disabled={saving} onClick={onSave} className="inline-flex items-center gap-1.5 rounded-lg bg-accent px-3 py-2 text-sm font-medium text-white hover:bg-accent-hover disabled:opacity-50"><Save size={16} />{saving ? '保存中...' : '保存配置'}</button></div>
    <div className="border-b border-border pb-5"><h2 className="text-base font-semibold text-text-primary">{entry.remoteId ? `编辑 ${entry.label}` : '新建供应商'}</h2><p className="mt-1 text-sm text-text-secondary">保存后可在对应的模型选择器中使用此供应商。</p></div>
    <div className="grid gap-5 py-6 md:grid-cols-2"><Field label="配置名称"><input value={entry.label} onChange={(event) => onChange({ label: event.target.value })} placeholder="例如：主用 DeepSeek" className="input-field" /></Field><Field label="服务商预设"><div className="flex gap-2"><select value={entry.presetId} onChange={(event) => onPresetChange(event.target.value)} className="input-field"><option value={entry.presetId}>{preset.label}</option>{PROVIDER_PRESETS[capability].filter((item) => item.id !== entry.presetId).map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select>{preset.docsUrl ? <a title="打开服务商文档" href={preset.docsUrl} target="_blank" rel="noreferrer" className="flex shrink-0 items-center justify-center rounded-lg border border-border bg-bg-surface px-3 text-accent-tertiary hover:border-accent-tertiary/40"><ExternalLink size={16} /></a> : null}</div></Field></div>
    <div className="space-y-5"><Field label="API Key" hint={loadingKey ? '正在读取已保存的密钥...' : entry.apiKey ? '密钥已从后端安全读取，可点击右侧图标查看。' : '尚未配置密钥，请填写后保存。'}><div className="relative"><input type={showKey ? 'text' : 'password'} value={entry.apiKey} disabled={loadingKey} onChange={(event) => onChange({ apiKey: event.target.value })} placeholder={loadingKey ? '正在读取...' : 'sk-...'} className="input-field pr-11 disabled:cursor-wait disabled:opacity-60" autoComplete="off" /><button type="button" disabled={loadingKey || !entry.apiKey} title={showKey ? '隐藏密钥' : '显示密钥'} onClick={onShowKey} className="absolute right-1 top-1/2 -translate-y-1/2 rounded-lg p-2 text-text-muted hover:text-text-primary disabled:cursor-not-allowed disabled:opacity-40">{loadingKey ? <LoaderCircle size={17} className="animate-spin" /> : showKey ? <EyeOff size={17} /> : <Eye size={17} />}</button></div></Field>
      <Field label="Base URL" hint="填写 API 根地址，例如 https://api.example.com/v1；不要填写控制台或网页地址。"><input value={entry.baseUrl} onChange={(event) => onChange({ baseUrl: event.target.value })} placeholder="https://api.example.com/v1" className="input-field" /></Field>
      {supportsModels ? <div className="rounded-xl border border-border/60 bg-bg-surface/40 p-4"><div className="flex flex-wrap items-start justify-between gap-3"><div><h3 className="text-sm font-medium text-text-primary">模型列表</h3><p className="mt-1 text-xs leading-5 text-text-secondary">拉取可用模型后，勾选要在应用中展示的模型。</p></div><button type="button" disabled={loadingModels} onClick={onFetchModels} className="inline-flex items-center gap-1.5 rounded-full border border-border bg-bg-surface px-3.5 py-1.5 text-xs font-medium text-text-secondary transition-colors hover:border-accent/40 hover:bg-accent-soft/30 hover:text-accent disabled:opacity-50">{loadingModels ? <RefreshCw size={13} className="animate-spin" /> : <RefreshCw size={13} />}{loadingModels ? '获取中...' : '获取模型'}</button></div><div className="mt-4 grid gap-2.5 sm:grid-cols-2">{entry.availableModels.length ? entry.availableModels.map((model) => { const checked = entry.selectedModels.includes(model); return <label key={model} className={`flex cursor-pointer items-center gap-2.5 rounded-lg border px-3.5 py-2.5 text-sm transition-all ${checked ? 'border-accent/50 bg-accent-soft text-accent shadow-sm' : 'border-border/70 bg-bg-surface text-text-secondary hover:border-accent/30 hover:bg-accent-soft/30'}`}><input type="checkbox" checked={checked} onChange={() => onChange({ selectedModels: checked ? entry.selectedModels.filter((item) => item !== model) : [...entry.selectedModels, model] })} className="h-4 w-4 accent-[var(--color-accent)]" /><span className="break-all">{model}</span></label>; }) : <p className="py-4 text-center text-sm text-text-muted">填写密钥和 Base URL 后获取模型。</p>}</div></div> : <div className="rounded-xl border border-border/60 bg-bg-surface/40 px-4 py-3 text-sm leading-6 text-text-secondary">工作流供应商只保存密钥与服务地址，具体工作流或 Agent 由对应服务端配置决定。</div>}
    </div>
  </section>;
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return <label className="block"><span className="mb-1.5 block text-sm font-medium text-text-primary">{label}</span>{children}{hint ? <span className="mt-1.5 block text-xs leading-5 text-text-secondary">{hint}</span> : null}</label>;
}
