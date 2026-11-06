import { readApiData, BASE, authFetch, parseApiError, LS_LLM_API_KEY, LS_IMAGE_API_KEY, LS_WORKFLOW_API_KEY, LS_LEGACY_DEEPSEEK_API_KEY, LS_LEGACY_COZE_API_KEY, LS_PROVIDER_SETTINGS, LS_PROVIDER_SETTINGS_LEGACY } from './auth';

export const API_KEY_CHANGE_EVENT = 'lorevista:api-key-change';

export type ApiCapability = 'llm' | 'image' | 'workflow';
type ProviderMode = 'official' | 'custom';

interface ProviderEndpointConfig {
  configId?: number;
  active?: boolean;
  apiKeyMasked?: string;
  presetId: string;
  label: string;
  apiKey: string;
  baseUrl: string;
  model: string;
}

export interface ProviderPresetConfig {
  remoteId?: number;
  active?: boolean;
  apiKeyMasked?: string;
  presetId: string;
  label: string;
  mode: ProviderMode;
  apiKey: string;
  baseUrl: string;
  selectedModels: string[];
  availableModels: string[];
}

export interface ProviderModelOption {
  value: string;
  model: string;
  entryId: string;
  configId?: number;
  providerLabel: string;
  providerPresetId: string;
  apiKey: string;
  baseUrl: string;
}

interface CapabilityProviderSettings {
  activePresetId: string;
  entries: Record<string, ProviderPresetConfig>;
}

export interface ApiKeySettings {
  providers: Record<ApiCapability, CapabilityProviderSettings>;
}

const DEFAULT_PROVIDER_LIBRARY: Record<ApiCapability, Array<{ presetId: string; label: string; baseUrl: string; models: string[] }>> = {
  llm: [
    { presetId: 'deepseek', label: 'DeepSeek Official', baseUrl: 'https://api.deepseek.com', models: ['deepseek-v4-flash', 'deepseek-chat'] },
    { presetId: 'openai', label: 'OpenAI Official', baseUrl: 'https://api.openai.com/v1', models: ['gpt-4.1-mini', 'gpt-4.1'] },
    { presetId: 'openrouter', label: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', models: ['openai/gpt-4.1-mini', 'anthropic/claude-3.7-sonnet'] },
    { presetId: 'siliconflow', label: 'SiliconFlow', baseUrl: 'https://api.siliconflow.cn/v1', models: ['deepseek-ai/DeepSeek-V3', 'Qwen/Qwen3-32B'] },
    { presetId: 'qwen', label: 'Qwen Bailian', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', models: ['qwen-plus', 'qwen-max'] },
    { presetId: 'ark', label: 'Volcengine Ark', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', models: ['doubao-seed-1-6-flash-250615', 'doubao-1-5-pro-32k-250115'] },
  ],
  image: [
    { presetId: 'image2', label: 'Image2 Official', baseUrl: 'https://api.duojie.games/v1', models: ['gpt-image-2'] },
    { presetId: 'openai-images', label: 'OpenAI Images', baseUrl: 'https://api.openai.com/v1', models: ['gpt-image-1'] },
    { presetId: 'openrouter-images', label: 'OpenRouter Images', baseUrl: 'https://openrouter.ai/api/v1', models: ['openai/gpt-image-1'] },
    { presetId: 'siliconflow-images', label: 'SiliconFlow Images', baseUrl: 'https://api.siliconflow.cn/v1', models: ['black-forest-labs/FLUX.1-schnell', 'stabilityai/stable-image-ultra'] },
  ],
  workflow: [
    { presetId: 'coze', label: 'Coze Official', baseUrl: 'https://api.coze.cn', models: ['workflow'] },
    { presetId: 'dify', label: 'Dify Workflow', baseUrl: 'https://api.dify.ai/v1', models: ['workflow'] },
  ],
};

const DEFAULT_ACTIVE_PRESET: Record<ApiCapability, string> = {
  llm: 'deepseek',
  image: 'image2',
  workflow: 'coze',
};

const MODEL_SELECTION_SEPARATOR = '::';

function readStorageWithLegacy(primaryKey: string, legacyKey?: string): string {
  return localStorage.getItem(primaryKey) || (legacyKey ? localStorage.getItem(legacyKey) : '') || '';
}

export function parseProviderModels(value: string | string[] | null | undefined): string[] {
  if (Array.isArray(value)) {
    return Array.from(new Set(value.map((item) => String(item || '').trim()).filter(Boolean)));
  }
  return Array.from(new Set(
    String(value || '')
      .split(/[\n,]+/)
      .map((item) => item.trim())
      .filter(Boolean),
  ));
}

function serializeProviderModels(modelIds: string[]): string {
  return modelIds.join('\n');
}

function createDefaultPresetConfig(
  presetId: string,
  label: string,
  baseUrl: string,
  models: string[],
): ProviderPresetConfig {
  return {
    active: true,
    presetId,
    label,
    mode: 'official',
    apiKey: '',
    baseUrl,
    selectedModels: [...models],
    availableModels: [...models],
  };
}

function createDefaultEntryKey(capability: ApiCapability): string {
  return `${capability}-default`;
}

function getProviderTemplate(
  capability: ApiCapability,
  presetId: string,
): { presetId: string; label: string; baseUrl: string; models: string[] } {
  return DEFAULT_PROVIDER_LIBRARY[capability].find((preset) => preset.presetId === presetId)
    || {
      presetId: 'custom',
      label: capability === 'llm'
        ? 'Custom OpenAI-Compatible'
        : capability === 'image'
          ? 'Custom Image Gateway'
          : 'Custom Workflow Gateway',
      baseUrl: capability === 'llm'
        ? 'https://your-gateway.example.com/v1'
        : capability === 'image'
          ? 'https://your-image-gateway.example.com/v1'
          : 'https://your-workflow.example.com/v1',
      models: [capability === 'workflow' ? 'workflow-or-agent' : 'your-model-name'],
    };
}

function createDefaultCapabilitySettings(capability: ApiCapability): CapabilityProviderSettings {
  const defaultPreset = getProviderTemplate(capability, DEFAULT_ACTIVE_PRESET[capability]);
  const defaultEntryKey = createDefaultEntryKey(capability);
  return {
    activePresetId: defaultEntryKey,
    entries: {
      [defaultEntryKey]: createDefaultPresetConfig(
        defaultPreset.presetId,
        defaultPreset.label,
        defaultPreset.baseUrl,
        defaultPreset.models,
      ),
    },
  };
}

function createDefaultSettings(): ApiKeySettings {
  return {
    providers: {
      llm: createDefaultCapabilitySettings('llm'),
      image: createDefaultCapabilitySettings('image'),
      workflow: createDefaultCapabilitySettings('workflow'),
    },
  };
}

function normalizePresetConfig(
  raw: Partial<ProviderPresetConfig> | null | undefined,
  fallback: ProviderPresetConfig,
): ProviderPresetConfig {
  const hasSelectedModels = !!raw && Object.prototype.hasOwnProperty.call(raw, 'selectedModels');
  const hasAvailableModels = !!raw && Object.prototype.hasOwnProperty.call(raw, 'availableModels');
  const selectedModels = parseProviderModels(raw?.selectedModels);
  const availableModels = Array.from(new Set(
    hasAvailableModels || hasSelectedModels
      ? [
        ...parseProviderModels(raw?.availableModels),
        ...selectedModels,
      ]
      : [
        ...parseProviderModels(raw?.availableModels),
        ...selectedModels,
        ...fallback.availableModels,
      ],
  ));
  return {
    remoteId: typeof raw?.remoteId === 'number' ? raw.remoteId : undefined,
    active: typeof raw?.active === 'boolean' ? raw.active : fallback.active,
    apiKeyMasked: String(raw?.apiKeyMasked || ''),
    presetId: String(raw?.presetId || fallback.presetId),
    label: String(raw?.label || fallback.label),
    mode: raw?.mode === 'custom' ? 'custom' : 'official',
    apiKey: String(raw?.apiKey || ''),
    baseUrl: String(raw?.baseUrl || fallback.baseUrl),
    selectedModels: hasSelectedModels ? selectedModels : (selectedModels.length > 0 ? selectedModels : [...fallback.selectedModels]),
    availableModels,
  };
}

function normalizeCapabilitySettings(
  capability: ApiCapability,
  raw: Partial<CapabilityProviderSettings> | null | undefined,
): CapabilityProviderSettings {
  const fallback = createDefaultCapabilitySettings(capability);
  const entries: Record<string, ProviderPresetConfig> = {};
  const rawEntries = raw?.entries && typeof raw.entries === 'object' ? raw.entries : null;
  const legacyPresetsSource = raw as Partial<CapabilityProviderSettings> & { presets?: Record<string, Partial<ProviderPresetConfig>> };
  const legacyPresets = !rawEntries && legacyPresetsSource?.presets && typeof legacyPresetsSource.presets === 'object'
    ? legacyPresetsSource.presets
    : null;

  if (rawEntries) {
    Object.entries(rawEntries).forEach(([entryId, preset]) => {
      const typedPreset = preset as Partial<ProviderPresetConfig>;
      const template = getProviderTemplate(capability, String(typedPreset.presetId || 'custom'));
      const entryFallback = createDefaultPresetConfig(
        template.presetId,
        String(typedPreset.label || template.label),
        String(typedPreset.baseUrl || template.baseUrl),
        parseProviderModels(typedPreset.selectedModels).length > 0
          ? parseProviderModels(typedPreset.selectedModels)
          : template.models,
      );
      entries[entryId] = normalizePresetConfig(typedPreset, entryFallback);
    });
  } else if (legacyPresets) {
    const legacyActiveId = String(raw?.activePresetId || '');
    Object.entries(legacyPresets).forEach(([legacyId, preset]) => {
      const typedPreset = preset as Partial<ProviderPresetConfig>;
      const template = getProviderTemplate(capability, legacyId);
      const entryFallback = createDefaultPresetConfig(
        template.presetId,
        template.label,
        template.baseUrl,
        template.models,
      );
      const normalized = normalizePresetConfig(typedPreset, entryFallback);
      const changed = normalized.apiKey.trim()
        || normalized.baseUrl !== entryFallback.baseUrl
        || normalized.label !== entryFallback.label
        || normalized.mode !== entryFallback.mode
        || normalized.selectedModels.join('\n') !== entryFallback.selectedModels.join('\n');
      if (changed || legacyId === legacyActiveId) {
        entries[legacyId] = normalized;
      }
    });
  }

  if (Object.keys(entries).length === 0) {
    return fallback;
  }

  const activePresetId = String(raw?.activePresetId || fallback.activePresetId);
  return {
    activePresetId: entries[activePresetId] ? activePresetId : Object.keys(entries)[0],
    entries,
  };
}

function createMigratedEntry(
  capability: ApiCapability,
  legacy: Partial<ProviderEndpointConfig>,
  fallbackApiKey: string,
): ProviderPresetConfig {
  const presetId = String(legacy.presetId || DEFAULT_ACTIVE_PRESET[capability]);
  const template = getProviderTemplate(capability, presetId);
  const preset = createDefaultPresetConfig(
    template.presetId,
    String(legacy.label || template.label),
    String(legacy.baseUrl || template.baseUrl),
    parseProviderModels(legacy.model).length > 0 ? parseProviderModels(legacy.model) : template.models,
  );
  preset.apiKey = String(legacy.apiKey || fallbackApiKey);
  preset.selectedModels = parseProviderModels(legacy.model).length > 0 ? parseProviderModels(legacy.model) : preset.selectedModels;
  preset.availableModels = Array.from(new Set([...preset.availableModels, ...preset.selectedModels]));
  preset.mode = presetId === 'custom' || preset.baseUrl !== template.baseUrl ? 'custom' : 'official';
  return preset;
}

function isConfiguredProviderEntry(capability: ApiCapability, entry: ProviderPresetConfig): boolean {
  const template = getProviderTemplate(capability, entry.presetId);
  if (entry.remoteId !== undefined && entry.apiKeyMasked) return true;
  const trimmedKey = entry.apiKey.trim();
  const trimmedLabel = entry.label.trim();
  const trimmedBaseUrl = entry.baseUrl.trim();
  const templateModels = parseProviderModels(template.models);
  const selectedModels = parseProviderModels(entry.selectedModels);
  const availableModels = parseProviderModels(entry.availableModels);

  if (trimmedKey) return true;
  if (trimmedLabel && trimmedLabel !== template.label) return true;
  if (trimmedBaseUrl && trimmedBaseUrl !== template.baseUrl) return true;
  if (entry.mode !== (entry.presetId === 'custom' ? 'custom' : 'official')) return true;
  if (selectedModels.join('\n') !== templateModels.join('\n')) return true;
  if (availableModels.join('\n') !== templateModels.join('\n')) return true;
  return false;
}

function normalizeProviderConfigValue(value: string | null | undefined): string {
  return String(value || '').trim().replace(/\/+$/, '');
}

function providerConfigFingerprint(
  presetId: string,
  label: string,
  baseUrl: string,
  models: string | string[] | null | undefined,
): string {
  return JSON.stringify([
    presetId.trim(),
    label.trim(),
    normalizeProviderConfigValue(baseUrl),
    [...parseProviderModels(models)].sort(),
  ]);
}

function isSameProviderConfig(local: ProviderPresetConfig, remote: ProviderEndpointConfig): boolean {
  return providerConfigFingerprint(local.presetId, local.label, local.baseUrl, local.selectedModels)
    === providerConfigFingerprint(remote.presetId, remote.label, remote.baseUrl, remote.model);
}

function getMeaningfulEntryIds(settings: CapabilityProviderSettings): string[] {
  return Object.keys(settings.entries);
}

function sanitizeCapabilitySettings(
  capability: ApiCapability,
  settings: CapabilityProviderSettings,
): CapabilityProviderSettings {
  const normalized = normalizeCapabilitySettings(capability, settings);
  const meaningfulIds = getMeaningfulEntryIds(normalized);
  if (meaningfulIds.length === 0) return createDefaultCapabilitySettings(capability);
  return {
    activePresetId: meaningfulIds.includes(normalized.activePresetId) ? normalized.activePresetId : meaningfulIds[0],
    entries: Object.fromEntries(meaningfulIds.map((entryId) => [entryId, normalized.entries[entryId]])),
  };
}

function migrateLegacySettings(): ApiKeySettings {
  const settings = createDefaultSettings();
  let storedProviders: Partial<Record<ApiCapability, Partial<ProviderEndpointConfig>>> = {};
  try {
    const raw = localStorage.getItem(LS_PROVIDER_SETTINGS_LEGACY);
    if (raw) storedProviders = JSON.parse(raw);
  } catch {
    storedProviders = {};
  }
  const fallbackKeys: Record<ApiCapability, string> = {
    llm: readStorageWithLegacy(LS_LLM_API_KEY, LS_LEGACY_DEEPSEEK_API_KEY),
    image: localStorage.getItem(LS_IMAGE_API_KEY) || '',
    workflow: readStorageWithLegacy(LS_WORKFLOW_API_KEY, LS_LEGACY_COZE_API_KEY),
  };
  (['llm', 'image', 'workflow'] as ApiCapability[]).forEach((capability) => {
    const legacy = storedProviders[capability];
    if (legacy) {
      const entryId = createDefaultEntryKey(capability);
      settings.providers[capability] = {
        activePresetId: entryId,
        entries: {
          [entryId]: createMigratedEntry(capability, legacy, fallbackKeys[capability]),
        },
      };
    } else if (fallbackKeys[capability]) {
      const entryId = createDefaultEntryKey(capability);
      const activeEntry = settings.providers[capability].entries[entryId];
      activeEntry.apiKey = fallbackKeys[capability];
    }
  });
  return settings;
}

export function mergeServerProviderConfigs(
  local: ApiKeySettings,
  remote: Partial<Record<ApiCapability, ProviderEndpointConfig[]>>,
): ApiKeySettings {
  const next = JSON.parse(JSON.stringify(local)) as ApiKeySettings;
  (['llm', 'image', 'workflow'] as ApiCapability[]).forEach((capability) => {
    const remoteEntries = remote[capability];
    if (!remoteEntries) return;
    const current = next.providers[capability];
    const localEntries = Object.entries(current.entries);
    const entries: Record<string, ProviderPresetConfig> = {};
    const matchedLocalEntryIds = new Set<string>();
    remoteEntries.forEach((remoteConfig) => {
      const entryId = `server-${remoteConfig.configId}`;
      const localMatchTuple = localEntries.find(([localEntryId, entry]) =>
        !matchedLocalEntryIds.has(localEntryId)
        && (entry.remoteId === remoteConfig.configId
          || (entry.remoteId === undefined && isSameProviderConfig(entry, remoteConfig))),
      );
      if (localMatchTuple) matchedLocalEntryIds.add(localMatchTuple[0]);
      const localMatch = localMatchTuple?.[1];
      const template = getProviderTemplate(capability, remoteConfig.presetId);
      const selectedModels = parseProviderModels(remoteConfig.model);
      entries[entryId] = {
        remoteId: remoteConfig.configId,
        active: remoteConfig.active,
        apiKeyMasked: remoteConfig.apiKeyMasked,
        presetId: remoteConfig.presetId || template.presetId,
        label: remoteConfig.label || template.label,
        mode: remoteConfig.presetId === 'custom' ? 'custom' : 'official',
        apiKey: localMatch?.apiKey || '',
        baseUrl: remoteConfig.baseUrl || template.baseUrl,
        selectedModels,
        availableModels: Array.from(new Set([...selectedModels, ...(localMatch?.availableModels || [])])),
      };
    });
    const hasRemoteActive = remoteEntries.some((entry) => entry.active);
    localEntries
      .filter(([entryId, entry]) =>
        !matchedLocalEntryIds.has(entryId)
        && entry.remoteId === undefined
        && isConfiguredProviderEntry(capability, entry),
      )
      .forEach(([entryId, entry]) => { entries[entryId] = { ...entry, active: hasRemoteActive ? false : entry.active }; });
    const activeEntryId = Object.entries(entries).find(([, entry]) => entry.active)?.[0] || Object.keys(entries)[0] || current.activePresetId;
    next.providers[capability] = { activePresetId: activeEntryId, entries };
  });
  return next;
}

export function getApiKeySettings(): ApiKeySettings {
  try {
    const raw = localStorage.getItem(LS_PROVIDER_SETTINGS);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<ApiKeySettings>;
      return {
        providers: {
          llm: normalizeCapabilitySettings('llm', parsed.providers?.llm),
          image: normalizeCapabilitySettings('image', parsed.providers?.image),
          workflow: normalizeCapabilitySettings('workflow', parsed.providers?.workflow),
        },
      };
    }
  } catch {
    // Fall through to legacy migration.
  }
  return migrateLegacySettings();
}

function encodeProviderModelSelection(entryId: string, model: string): string {
  return `${entryId}${MODEL_SELECTION_SEPARATOR}${model}`;
}

function decodeProviderModelSelection(value: string): { entryId: string; model: string } {
  const idx = value.indexOf(MODEL_SELECTION_SEPARATOR);
  if (idx < 0) {
    return { entryId: '', model: value };
  }
  return {
    entryId: value.slice(0, idx),
    model: value.slice(idx + MODEL_SELECTION_SEPARATOR.length),
  };
}

export function getProviderModelSelections(capability: ApiCapability): ProviderModelOption[] {
  const settings = getApiKeySettings();
  return Object.entries(settings.providers[capability].entries)
    .filter(([, entry]) => entry.active && isConfiguredProviderEntry(capability, entry))
    .flatMap(([entryId, entry]) =>
      entry.selectedModels.map((model) => ({
      value: encodeProviderModelSelection(entryId, model),
      model,
      entryId,
      configId: entry.remoteId,
      providerLabel: entry.label || getProviderTemplate(capability, entry.presetId).label,
      providerPresetId: entry.presetId,
      apiKey: entry.apiKey,
      baseUrl: entry.baseUrl,
    })),
    );
}

function getProviderModelSelectionMeta(
  capability: ApiCapability,
  selection: string,
): ProviderModelOption | null {
  if (!selection) return null;
  const options = getProviderModelSelections(capability);
  const exact = options.find((option) => option.value === selection);
  if (exact) return exact;
  const decoded = decodeProviderModelSelection(selection);
  return options.find((option) =>
    option.model === decoded.model && (!decoded.entryId || option.entryId === decoded.entryId),
  ) || null;
}

export function getProviderModelOptions(capability: ApiCapability): string[] {
  return getProviderModelSelections(capability).map((option) => option.value);
}

export function getPrimaryProviderModel(capability: ApiCapability): string {
  const settings = getApiKeySettings();
  const activeEntryId = settings.providers[capability].activePresetId;
  const options = getProviderModelSelections(capability);
  return options.find((option) => option.entryId === activeEntryId)?.value
    || options[0]?.value
    || '';
}

export function getProviderRequestPayload(
  capability: ApiCapability,
  selection?: string,
): Record<string, string> {
  if (!selection) return {};
  const option = getProviderModelSelectionMeta(capability, selection);
  if (option) {
    return {
      model: option.model,
      provider: option.providerPresetId,
      label: option.providerLabel,
      config_id: option.configId ? String(option.configId) : '',
      api_key: option.apiKey,
      apiKey: option.apiKey,
      base_url: option.baseUrl,
      baseUrl: option.baseUrl,
    };
  }
  return { model: decodeProviderModelSelection(selection).model };
}

export function getImageGenerationProviderPayload(selection?: string): Record<string, string | number> {
  if (!selection) return {};
  const option = getProviderModelSelectionMeta('image', selection);
  if (!option) {
    return { model: decodeProviderModelSelection(selection).model };
  }
  return {
    model: option.model,
    ...(option.configId ? { config_id: option.configId } : { provider: option.providerPresetId, api_key: option.apiKey, base_url: option.baseUrl, label: option.providerLabel }),
  };
}

export function toProviderEndpointConfig(preset: ProviderPresetConfig): ProviderEndpointConfig {
  return {
    configId: preset.remoteId,
    active: preset.active,
    apiKeyMasked: preset.apiKeyMasked,
    presetId: preset.presetId,
    label: preset.label,
    apiKey: preset.apiKey,
    baseUrl: preset.baseUrl,
    model: serializeProviderModels(preset.selectedModels),
  };
}

export function saveApiKeySettings(settings: ApiKeySettings): void {
  const normalized: ApiKeySettings = {
    providers: {
      llm: sanitizeCapabilitySettings('llm', settings.providers.llm),
      image: sanitizeCapabilitySettings('image', settings.providers.image),
      workflow: sanitizeCapabilitySettings('workflow', settings.providers.workflow),
    },
  };
  const persisted = JSON.parse(JSON.stringify(normalized)) as ApiKeySettings;
  (['llm', 'image', 'workflow'] as ApiCapability[]).forEach((capability) => {
    Object.values(persisted.providers[capability].entries).forEach((entry) => {
      if (entry.remoteId !== undefined) entry.apiKey = '';
    });
  });
  localStorage.setItem(LS_PROVIDER_SETTINGS, JSON.stringify(persisted));
  const storageMap: Record<ApiCapability, string> = {
    llm: LS_LLM_API_KEY,
    image: LS_IMAGE_API_KEY,
    workflow: LS_WORKFLOW_API_KEY,
  };
  (['llm', 'image', 'workflow'] as ApiCapability[]).forEach((capability) => {
    const provider = normalized.providers[capability];
    const primary = provider.entries[provider.activePresetId];
    const active = primary?.active ? primary : Object.values(provider.entries).find((entry) => entry.active);
    const value = active && active.remoteId === undefined ? active.apiKey.trim() : '';
    if (value) localStorage.setItem(storageMap[capability], value);
    else localStorage.removeItem(storageMap[capability]);
  });
  localStorage.removeItem(LS_LEGACY_DEEPSEEK_API_KEY);
  localStorage.removeItem(LS_LEGACY_COZE_API_KEY);
  localStorage.removeItem(LS_PROVIDER_SETTINGS_LEGACY);
  try { window.dispatchEvent(new Event(API_KEY_CHANGE_EVENT)); } catch { /* ignore */ }
}

export async function saveUserProviderConfig(capability: ApiCapability, config: ProviderEndpointConfig): Promise<ProviderEndpointConfig> {
  const res = await authFetch(`${BASE}/api/user/provider-configs`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      slot: capability,
      config_id: config.configId,
      active: config.active,
      provider: config.presetId,
      label: config.label,
      api_key: config.apiKey,
      base_url: config.baseUrl,
      model: config.model,
    }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return providerConfigFromResponse(await readApiData(res));
}

export async function deleteUserProviderConfig(configId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/user/provider-configs/${configId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export async function activateUserProviderConfig(configId: number): Promise<ProviderEndpointConfig> {
  const res = await authFetch(`${BASE}/api/user/provider-configs/${configId}/activate`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return providerConfigFromResponse(await readApiData(res));
}

export async function deactivateUserProviderConfig(configId: number): Promise<ProviderEndpointConfig> {
  const res = await authFetch(`${BASE}/api/user/provider-configs/${configId}/deactivate`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return providerConfigFromResponse(await readApiData(res));
}

function providerConfigFromResponse(item: Record<string, unknown>): ProviderEndpointConfig {
  const configId = Number(item.config_id ?? item.configId);
  if (!Number.isSafeInteger(configId) || configId <= 0) {
    throw new Error('后端返回的供应商配置缺少有效 config_id，请确认前后端版本一致。');
  }
  const apiKeyMasked = String(item.api_key_masked || item.apiKeyMasked || '');
  return {
    configId,
    active: Boolean(item.active),
    apiKeyMasked: apiKeyMasked === '(not set)' ? '' : apiKeyMasked,
    presetId: String(item.provider || item.preset_id || item.presetId || ''),
    label: String(item.label || ''),
    apiKey: '',
    baseUrl: String(item.base_url || item.baseUrl || ''),
    model: String(item.model || ''),
  };
}

export async function getUserProviderApiKey(configId: number): Promise<string> {
  const res = await authFetch(`${BASE}/api/user/provider-configs/${configId}/api-key`, {
    cache: 'no-store',
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await readApiData(res);
  return String(data.api_key ?? data.apiKey ?? '');
}

export async function getUserProviderConfigs(): Promise<Partial<Record<ApiCapability, ProviderEndpointConfig[]>>> {
  const res = await authFetch(`${BASE}/api/user/provider-configs`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await readApiData(res);
  const result: Partial<Record<ApiCapability, ProviderEndpointConfig[]>> = {};
  (Array.isArray(data) ? data : []).forEach((item: Record<string, unknown>) => {
    const capability = String(item.slot || '') as ApiCapability;
    if (!['llm', 'image', 'workflow'].includes(capability)) return;
    result[capability] = [...(result[capability] || []), providerConfigFromResponse(item)];
  });
  return result;
}

export async function discoverProviderModels(
  capability: ApiCapability,
  config: Pick<ProviderEndpointConfig, 'configId' | 'presetId' | 'apiKey' | 'baseUrl'>,
): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/user/provider-models/discover`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      slot: capability,
      config_id: config.configId,
      provider: config.presetId,
      api_key: config.apiKey,
      base_url: config.baseUrl,
    }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await readApiData(res);
  return Array.isArray(data?.models)
    ? data.models.map((item: unknown) => String(item || '').trim()).filter(Boolean)
    : [];
}


