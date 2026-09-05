import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { ArrowLeft, Check, CheckCircle2, ChevronRight, Database, Eye, EyeOff, Pencil, Plus, PowerOff, RefreshCw, Save, Wifi } from 'lucide-react';
import { activateEmbeddingConfig, deactivateEmbeddingConfig, discoverEmbeddingModels, getEmbeddingConfigs, saveEmbeddingConfig, testEmbeddingConfig, type EmbeddingConfigInfo } from '../api';

type Draft = { configId?: number; displayName: string; baseUrl: string; apiKey: string; model: string; customHeaders: string };
type View = { mode: 'list' } | { mode: 'edit'; configId?: number };

const emptyDraft = (): Draft => ({ displayName: '', baseUrl: '', apiKey: '', model: '', customHeaders: '{}' });

function draftFromConfig(config: EmbeddingConfigInfo): Draft {
  return { configId: config.id, displayName: config.displayName, baseUrl: config.baseUrl, apiKey: '', model: config.model, customHeaders: config.customHeaders || '{}' };
}

export default function EmbeddingSettingsPanel() {
  const [configs, setConfigs] = useState<EmbeddingConfigInfo[]>([]);
  const [view, setView] = useState<View>({ mode: 'list' });
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [loadingModels, setLoadingModels] = useState(false);
  const [switchingId, setSwitchingId] = useState<number | null>(null);
  const [availableModels, setAvailableModels] = useState<string[]>([]);
  const [showKey, setShowKey] = useState(false);
  const [message, setMessage] = useState('');

  const editingConfig = useMemo(() => view.mode === 'edit' && view.configId ? configs.find((config) => config.id === view.configId) || null : null, [configs, view]);
  const activeConfig = useMemo(() => configs.find((config) => config.active) || null, [configs]);
  const load = async () => {
    setLoading(true);
    try { setConfigs(await getEmbeddingConfigs()); }
    catch (error) { setMessage(error instanceof Error ? error.message : '读取向量模型配置失败'); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, []);

  const startNew = () => { setDraft(emptyDraft()); setView({ mode: 'edit' }); setMessage(''); setShowKey(false); setAvailableModels([]); };
  const startEdit = (config: EmbeddingConfigInfo) => { setDraft(draftFromConfig(config)); setView({ mode: 'edit', configId: config.id }); setMessage(''); setShowKey(false); setAvailableModels([config.model]); };
  const backToList = () => { setView({ mode: 'list' }); setMessage(''); setShowKey(false); };
  const validDraft = () => {
    if (!draft.displayName.trim() || !draft.baseUrl.trim() || !draft.model.trim()) { setMessage('请填写配置名称、Base URL 和 Embedding 模型。'); return; }
    if (!draft.configId && !draft.apiKey.trim()) { setMessage('新配置必须填写 API Key。'); return; }
    return true;
  };
  const replaceConfig = (config: EmbeddingConfigInfo) => setConfigs((current) => [config, ...current.filter((item) => item.id !== config.id)]);
  const save = async () => {
    if (!validDraft()) return;
    setSaving(true); setMessage('');
    try {
      const config = await saveEmbeddingConfig(draft);
      replaceConfig(config);
      setDraft(draftFromConfig(config));
      setView({ mode: 'edit', configId: config.id });
      setMessage(config.status === 'VERIFIED' ? '配置已保存，连接验证状态保持不变。' : '配置已保存，请测试连接以获取实际向量维度。');
    } catch (error) { setMessage(error instanceof Error ? error.message : '保存配置失败'); }
    finally { setSaving(false); }
  };
  const test = async () => {
    if (!draft.configId) { setMessage('请先保存配置，再测试连接。'); return; }
    const dirty = !editingConfig || draft.apiKey.trim() !== '' || draft.displayName.trim() !== editingConfig.displayName || draft.baseUrl.trim() !== editingConfig.baseUrl || draft.model.trim() !== editingConfig.model || draft.customHeaders.trim() !== editingConfig.customHeaders.trim();
    if (dirty) { setMessage('当前内容尚未保存，请先保存再测试。'); return; }
    setTesting(true); setMessage('');
    try {
      const result = await testEmbeddingConfig(draft.configId);
      replaceConfig(result.config);
      setMessage(`连接验证成功，实际向量维度为 ${result.dimension}。`);
    } catch (error) { setMessage(error instanceof Error ? error.message : '连接测试失败'); }
    finally { setTesting(false); }
  };
  const fetchModels = async () => {
    if (!draft.baseUrl.trim()) { setMessage('请先填写 Base URL。'); return; }
    if (!draft.configId && !draft.apiKey.trim()) { setMessage('请先填写 API Key。'); return; }
    setLoadingModels(true); setMessage('');
    try {
      const models = await discoverEmbeddingModels(draft);
      setAvailableModels(models);
      if (models.length === 0) setMessage('供应商的 /models 接口未声明向量模型，请手动填写模型名称。');
      else setMessage(`已获取 ${models.length} 个向量模型，请从下方列表中选择一个。`);
    } catch (error) { setMessage(error instanceof Error ? error.message : '获取向量模型失败'); }
    finally { setLoadingModels(false); }
  };
  const activate = async (config: EmbeddingConfigInfo) => {
    setSwitchingId(config.id); setMessage('');
    try {
      const activated = await activateEmbeddingConfig(config.id);
      setConfigs((current) => [activated, ...current.filter((item) => item.id !== activated.id).map((item) => ({ ...item, active: false }))]);
      setMessage(`已将 ${activated.model} 设为默认向量模型。`);
    } catch (error) { setMessage(error instanceof Error ? error.message : '启用向量模型失败'); }
    finally { setSwitchingId(null); }
  };
  const deactivate = async (config: EmbeddingConfigInfo) => {
    setSwitchingId(config.id); setMessage('');
    try {
      const deactivated = await deactivateEmbeddingConfig(config.id);
      replaceConfig(deactivated);
      setMessage(`已停用默认向量模型 ${deactivated.model}。`);
    } catch (error) { setMessage(error instanceof Error ? error.message : '停用向量模型失败'); }
    finally { setSwitchingId(null); }
  };

  if (view.mode === 'edit') return <EmbeddingEditor draft={draft} editingConfig={editingConfig} saving={saving} testing={testing} loadingModels={loadingModels} availableModels={availableModels} showKey={showKey} message={message} onBack={backToList} onChange={(patch) => setDraft((current) => ({ ...current, ...patch }))} onShowKey={() => setShowKey((value) => !value)} onSave={() => void save()} onTest={() => void test()} onFetchModels={() => void fetchModels()} />;

  return <section>
    <div className="mb-5 flex flex-wrap items-start justify-between gap-3"><div><h2 className="text-base font-semibold text-text-primary">向量模型</h2><p className="mt-1 text-sm text-text-secondary">为知识库配置 OpenAI 兼容的 <code>/embeddings</code> 服务。</p></div><button type="button" onClick={startNew} className="inline-flex items-center gap-1.5 rounded-lg bg-accent px-3 py-2 text-sm font-medium text-white hover:bg-accent-hover"><Plus size={16} />新建配置</button></div>
    {!loading && configs.length > 0 ? <div className={`mb-4 flex items-center gap-3 border px-4 py-3 ${activeConfig ? 'border-accent/30 bg-accent-muted/15' : 'border-border bg-bg-surface'}`}><div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${activeConfig ? 'bg-accent text-white' : 'bg-bg-raised text-text-muted'}`}><Database size={17} /></div><div className="min-w-0"><div className="text-xs text-text-secondary">当前默认向量模型</div>{activeConfig ? <div className="mt-0.5 flex flex-wrap items-baseline gap-x-2"><strong className="text-sm text-text-primary">{activeConfig.model}</strong><span className="text-xs text-text-muted">{activeConfig.displayName} · {activeConfig.actualDimension}D</span></div> : <div className="mt-0.5 text-sm font-medium text-text-primary">尚未设置，请从已验证配置中选择</div>}</div></div> : null}
    {message ? <div role="status" className="mb-4 border border-border bg-bg-surface px-4 py-3 text-sm text-text-secondary">{message}</div> : null}
    {loading ? <div className="border border-border bg-bg-surface px-4 py-8 text-center text-sm text-text-secondary">正在读取已保存的向量模型配置...</div> : null}
    {!loading && configs.length === 0 ? <div className="border border-dashed border-border bg-bg-surface/60 px-6 py-16 text-center"><div className="text-sm font-medium text-text-primary">还没有向量模型配置</div><p className="mt-2 text-sm text-text-secondary">配置并验证后，才能为作品知识库创建检索索引。</p><button type="button" onClick={startNew} className="mt-5 inline-flex items-center gap-1.5 rounded-lg border border-border bg-white px-3 py-2 text-sm text-text-secondary hover:border-accent/40 hover:text-accent"><Plus size={16} />新建配置</button></div> : null}
    {!loading && configs.length > 0 ? <div className="space-y-3">{configs.map((config) => <EmbeddingRow key={config.id} config={config} switching={switchingId === config.id} onEdit={() => startEdit(config)} onActivate={() => void activate(config)} onDeactivate={() => void deactivate(config)} />)}</div> : null}
  </section>;
}

function EmbeddingRow({ config, switching, onEdit, onActivate, onDeactivate }: { config: EmbeddingConfigInfo; switching: boolean; onEdit: () => void; onActivate: () => void; onDeactivate: () => void }) {
  const verified = config.status === 'VERIFIED';
  const statusLabel = verified ? '已验证' : config.status === 'UNVERIFIED' ? '待验证' : '已停用';
  return <article className={`group flex min-h-24 items-center gap-4 border px-5 py-4 transition-colors ${config.active ? 'border-accent/50 bg-accent-muted/10' : 'border-border bg-bg-surface/55 hover:border-accent/20/50'}`}>
    <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full border ${config.active ? 'border-accent bg-accent text-white' : 'border-border bg-bg-surface text-accent-tertiary'}`}><Database size={17} /></div>
    <div className="min-w-0 flex-1">
      <div className="flex flex-wrap items-center gap-2"><h3 className="truncate font-medium text-text-primary">{config.displayName || config.model}</h3>{config.active ? <span className="inline-flex items-center gap-1 rounded-full bg-accent-muted px-2 py-0.5 text-xs text-accent"><Check size={12} />默认使用</span> : null}<span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs ${verified ? 'bg-success/10 text-success' : 'bg-bg-raised text-text-secondary'}`}>{verified ? <CheckCircle2 size={12} /> : null}{statusLabel}</span><span className="rounded-full bg-bg-raised px-2 py-0.5 text-xs text-text-secondary">v{config.configVersion}</span></div>
      <div className="mt-1 truncate text-sm text-accent-tertiary">{config.baseUrl}</div>
      <div className="mt-1 flex flex-wrap gap-x-4 text-xs text-text-muted"><span className={config.active ? 'font-medium text-accent' : ''}>{config.model}</span><span>{config.actualDimension ? `${config.actualDimension}D` : '未获取维度'}</span><span>{config.apiKeyMasked ? '密钥已保存' : '尚未填写密钥'}</span><span>{config.usedByStories ? `已用于：${config.usedByStoryTitles?.join('、')}` : '尚未绑定作品'}</span></div>
    </div>
    <div className="flex shrink-0 items-center gap-1">{config.active ? <button type="button" disabled={switching} title="停用默认向量模型" onClick={onDeactivate} className="inline-flex items-center gap-1 rounded-lg border border-border bg-bg-surface p-2 text-xs text-text-secondary hover:border-accent/40 hover:text-accent disabled:opacity-50 sm:px-2.5"><PowerOff size={14} /><span className="hidden sm:inline">停用</span></button> : <button type="button" disabled={switching || !verified} title={verified ? '设为默认向量模型' : '连接验证通过后才能设为默认'} onClick={onActivate} className="inline-flex items-center gap-1 rounded-lg border border-border bg-bg-surface p-2 text-xs text-text-secondary hover:border-accent/40 hover:text-accent disabled:cursor-not-allowed disabled:opacity-40 sm:px-2.5"><Check size={14} /><span className="hidden sm:inline">设为默认</span></button>}<button type="button" title="编辑并重新验证" onClick={onEdit} className="rounded-md p-2 text-text-secondary hover:bg-white hover:text-accent"><Pencil size={17} /></button><ChevronRight size={17} className="hidden text-text-muted sm:block" /></div>
  </article>;
}

function EmbeddingEditor({ draft, editingConfig, saving, testing, loadingModels, availableModels, showKey, message, onBack, onChange, onShowKey, onSave, onTest, onFetchModels }: { draft: Draft; editingConfig: EmbeddingConfigInfo | null; saving: boolean; testing: boolean; loadingModels: boolean; availableModels: string[]; showKey: boolean; message: string; onBack: () => void; onChange: (patch: Partial<Draft>) => void; onShowKey: () => void; onSave: () => void; onTest: () => void; onFetchModels: () => void }) {
  const busy = saving || testing || loadingModels;
  return <section className="pb-20"><div className="mb-6 flex flex-wrap items-center justify-between gap-3"><button type="button" onClick={onBack} className="inline-flex items-center gap-1.5 text-sm text-text-secondary hover:text-text-primary"><ArrowLeft size={16} />返回配置列表</button><div className="flex items-center gap-2"><button type="button" disabled={busy || !draft.configId} onClick={onTest} className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-bg-surface px-3 py-2 text-sm font-medium text-text-secondary hover:border-accent-tertiary/40 hover:text-accent-tertiary disabled:opacity-50">{testing ? <RefreshCw size={16} className="animate-spin" /> : <Wifi size={16} />}{testing ? '测试中...' : '测试连接'}</button><button type="button" disabled={busy} onClick={onSave} className="inline-flex items-center gap-1.5 rounded-lg bg-accent px-3 py-2 text-sm font-medium text-white hover:bg-accent-hover disabled:opacity-50">{saving ? <RefreshCw size={16} className="animate-spin" /> : <Save size={16} />}{saving ? '保存中...' : '保存配置'}</button></div></div>
    <div className="border-b border-border pb-5"><h2 className="text-base font-semibold text-text-primary">{editingConfig ? `编辑 ${editingConfig.displayName || editingConfig.model}` : '新建向量模型配置'}</h2><p className="mt-1 text-sm text-text-secondary">保存和连接测试互不混淆。已被知识库使用的配置发生连接参数变更时，会自动保存为新版本。</p></div>
    <div className="grid gap-5 py-6 md:grid-cols-2"><Field label="配置名称"><input value={draft.displayName} onChange={(event) => onChange({ displayName: event.target.value })} placeholder="例如：主用 Embedding" className="input-field" /></Field><Field label="接口类型" hint="当前仅支持 OpenAI 兼容的 embeddings 接口。"><div className="input-field flex items-center gap-2 bg-bg-surface text-text-secondary"><Database size={16} className="text-accent-tertiary" />OpenAI Compatible /embeddings</div></Field></div>
    <div className="space-y-5">
      <Field label="API Key" hint={editingConfig?.apiKeyMasked && !draft.apiKey ? `已保存：${editingConfig.apiKeyMasked}` : '密钥会加密保存，不会在此页面回显。'}>
        <div className="relative"><input type={showKey ? 'text' : 'password'} value={draft.apiKey} onChange={(event) => onChange({ apiKey: event.target.value })} placeholder={editingConfig?.apiKeyMasked ? '留空以保留已保存的密钥' : 'sk-...'} className="input-field pr-11" autoComplete="off" /><button type="button" title={showKey ? '隐藏密钥' : '显示密钥'} onClick={onShowKey} className="absolute right-1 top-1/2 -translate-y-1/2 rounded-lg p-2 text-text-muted hover:text-text-primary">{showKey ? <EyeOff size={17} /> : <Eye size={17} />}</button></div>
      </Field>
      <Field label="Base URL" hint="填写 API 根地址，例如 https://api.example.com/v1；不要填写控制台或网页地址。">
        <input value={draft.baseUrl} onChange={(event) => onChange({ baseUrl: event.target.value })} placeholder="https://api.example.com/v1" className="input-field" />
      </Field>
      <Field label="Embedding 模型" hint="优先从供应商拉取；供应商未公开向量模型目录时仍可手动填写。">
        <div className="flex gap-2"><input value={draft.model} onChange={(event) => onChange({ model: event.target.value })} placeholder="例如：text-embedding-v4" className="input-field min-w-0 flex-1" /><button type="button" disabled={busy} onClick={onFetchModels} className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-border bg-bg-surface px-3 text-sm text-text-secondary hover:border-accent-tertiary/40 hover:text-accent-tertiary disabled:opacity-50">{loadingModels ? <RefreshCw size={16} className="animate-spin" /> : <RefreshCw size={16} />}获取模型</button></div>
        {availableModels.length > 0 ? <fieldset className="mt-3 border border-border bg-bg-surface/45 p-3"><legend className="px-1 text-xs font-medium text-text-secondary">已获取 {availableModels.length} 个向量模型</legend><div className="grid gap-2 sm:grid-cols-2">{availableModels.map((model) => <label key={model} className={`flex min-h-10 cursor-pointer items-center gap-2 border px-3 py-2 text-sm transition-colors ${draft.model === model ? 'border-accent/50 bg-accent-soft text-accent' : 'border-border bg-bg-surface text-text-secondary hover:border-accent-tertiary/40'}`}><input type="radio" name="embedding-model" value={model} checked={draft.model === model} onChange={() => onChange({ model })} className="accent-[var(--color-accent)]" /><span className="min-w-0 break-all">{model}</span></label>)}</div></fieldset> : null}
      </Field>
      <details className="border border-border bg-bg-surface/45"><summary className="cursor-pointer px-4 py-3 text-sm font-medium text-text-primary">高级选项</summary><div className="border-t border-border px-4 py-4"><Field label="自定义请求头（JSON）" hint="仅在服务商要求额外认证或路由请求头时填写。"><textarea value={draft.customHeaders} onChange={(event) => onChange({ customHeaders: event.target.value })} rows={5} className="input-field resize-y font-mono text-xs" /></Field></div></details>
      {message ? <div role="alert" className="border border-border bg-bg-surface px-4 py-3 text-sm text-text-secondary">{message}</div> : null}
    </div>
  </section>;
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return <label className="block"><span className="mb-1.5 block text-sm font-medium text-text-primary">{label}</span>{children}{hint ? <span className="mt-1.5 block text-xs leading-5 text-text-secondary">{hint}</span> : null}</label>;
}
