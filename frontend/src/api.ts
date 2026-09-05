import { EventType, HttpAgent, type AGUIEvent, type RunAgentInput } from '@ag-ui/client';

const BASE = '';
export const DEEPSEEK_USAGE_URL = 'https://platform.deepseek.com/usage';
export const IMAGE2_CONSOLE_URL = 'https://api.duojie.games/console/token';


const LS_REFRESH_TOKEN = 'artverse.refreshToken';
const LS_USER = 'artverse.user';

export interface UserInfo {
  id: number;
  username: string;
  email: string;
}

export interface ChallengeConfig {
  enabled: boolean;
  provider: string;
  siteKey: string;
  registrationRequired: boolean;
  loginMode: 'always' | 'adaptive' | 'disabled';
}

interface ChallengeConfigResponse {
  enabled?: boolean;
  provider?: string;
  site_key?: string;
  registration_required?: boolean;
  login_mode?: 'always' | 'adaptive' | 'disabled';
}

export class ApiError extends Error {
  code?: string;
  status?: number;

  constructor(message: string, options?: { code?: string; status?: number }) {
    super(message);
    this.name = 'ApiError';
    this.code = options?.code;
    this.status = options?.status;
  }
}

let currentUser: UserInfo | null = null;
let sessionBootstrapPromise: Promise<boolean> | null = null;

export function getUser(): UserInfo | null {
  return currentUser;
}

export function isAuthenticated(): boolean {
  return currentUser !== null;
}

export function clearAuth(): void {
  currentUser = null;
  clearLegacyAuthStorage();
  clearAppDataStorage();
}

function notifyAuthExpired(): void {
  clearAuth();
  window.dispatchEvent(new CustomEvent('artverse:auth-expired'));
}

let refreshPromise: Promise<boolean> | null = null;

function clearLegacyAuthStorage(): void {
  localStorage.removeItem(LS_REFRESH_TOKEN);
  localStorage.removeItem(LS_USER);
}

function clearAppDataStorage(): void {
  localStorage.removeItem(LS_LLM_API_KEY);
  localStorage.removeItem(LS_IMAGE_API_KEY);
  localStorage.removeItem(LS_WORKFLOW_API_KEY);
  localStorage.removeItem(LS_LEGACY_DEEPSEEK_API_KEY);
  localStorage.removeItem(LS_LEGACY_COZE_API_KEY);
  localStorage.removeItem(LS_PROVIDER_SETTINGS);
  localStorage.removeItem(LS_PROVIDER_SETTINGS_LEGACY);
  localStorage.removeItem('lorevista.currentStoryId');
  localStorage.removeItem('lorevista.currentChapterId');
  localStorage.removeItem('lorevista.currentChapterIdx');
  localStorage.removeItem('artverse.genThemes');
  localStorage.removeItem('artverse.activeGenTheme');
  localStorage.removeItem('artverse.genConfig');
  localStorage.removeItem('artverse.genCanvasOpen');
  localStorage.removeItem('artverse.genCanvasWidth');
  for (let i = localStorage.length - 1; i >= 0; i--) {
    const key = localStorage.key(i);
    if (key && key.startsWith('artverse.square.reading.')) {
      localStorage.removeItem(key);
    }
  }
}

export async function hydrateAuthSession(): Promise<boolean> {
  if (currentUser) return true;
  if (!sessionBootstrapPromise) {
    sessionBootstrapPromise = (async () => {
      try {
        await fetchAndCacheUser();
        clearLegacyAuthStorage();
        return true;
      } catch {
        const refreshed = await tryRefreshToken();
        if (!refreshed) {
          clearAuth();
          return false;
        }
        try {
          await fetchAndCacheUser();
          clearLegacyAuthStorage();
          return true;
        } catch {
          notifyAuthExpired();
          return false;
        }
      }
    })();
  }
  const currentPromise = sessionBootstrapPromise;
  try {
    return await currentPromise;
  } finally {
    if (sessionBootstrapPromise === currentPromise) {
      sessionBootstrapPromise = null;
    }
  }
}

async function tryRefreshToken(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const refreshToken = localStorage.getItem(LS_REFRESH_TOKEN);
        const body = refreshToken ? JSON.stringify({ refresh_token: refreshToken }) : undefined;
        const res = await fetch(`${BASE}/api/auth/refresh`, {
          method: 'POST',
          credentials: 'same-origin',
          headers: requestHeaders('POST', !!body),
          body,
        });
        if (!res.ok) {
          if (res.status === 401) {
            clearLegacyAuthStorage();
          }
          return false;
        }
        clearLegacyAuthStorage();
        return true;
      } catch {
        return false;
      }
    })();
  }
  const currentRefresh = refreshPromise;
  try {
    return await currentRefresh;
  } finally {
    if (refreshPromise === currentRefresh) {
      refreshPromise = null;
    }
  }
}

async function fetchAndCacheUser(): Promise<void> {
  const res = await fetch(`${BASE}/api/user/me`, {
    credentials: 'same-origin',
  });
  if (!res.ok) throw await toApiError(res);
  currentUser = await res.json();
}

export async function getChallengeConfig(): Promise<ChallengeConfig> {
  const res = await fetch(`${BASE}/api/auth/challenge/config`, {
    credentials: 'same-origin',
  });
  if (!res.ok) throw await toApiError(res);
  const payload = await res.json() as ChallengeConfigResponse;
  const mode = payload.login_mode;
  return {
    enabled: Boolean(payload.enabled),
    provider: payload.provider ?? '',
    siteKey: payload.site_key ?? '',
    registrationRequired: Boolean(payload.registration_required),
    loginMode: mode === 'always' ? 'always' : mode === 'adaptive' ? 'adaptive' : 'disabled',
  };
}

export async function getCaptchaImage(): Promise<{ captchaId: string; image: string }> {
  const res = await fetch(`${BASE}/api/auth/captcha/image`, {
    credentials: 'same-origin',
  });
  if (!res.ok) throw await toApiError(res);
  const payload = await res.json();
  return { captchaId: payload.captcha_id, image: payload.image };
}

export async function loginUser(username: string, password: string, challengeToken?: string): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: requestHeaders('POST', true),
    credentials: 'same-origin',
    body: JSON.stringify({ username, password, challenge_token: challengeToken }),
  });
  if (!res.ok) throw await toApiError(res);
  await fetchAndCacheUser();
  clearLegacyAuthStorage();
  clearAppDataStorage();
}

export async function registerUser(username: string, email: string, password: string, challengeToken?: string): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/register`, {
    method: 'POST',
    headers: requestHeaders('POST', true),
    credentials: 'same-origin',
    body: JSON.stringify({ username, email, password, challenge_token: challengeToken }),
  });
  if (!res.ok) throw await toApiError(res);
  await fetchAndCacheUser();
  clearLegacyAuthStorage();
  clearAppDataStorage();
}

export async function logoutUser(): Promise<void> {
  try {
    await fetch(`${BASE}/api/auth/logout`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: requestHeaders('POST'),
    });
  } catch { /* ignore */ }
  clearAuth();
}


const LS_LLM_API_KEY = 'lorevista.llmApiKey';
const LS_IMAGE_API_KEY = 'lorevista.imageApiKey';
const LS_WORKFLOW_API_KEY = 'lorevista.workflowApiKey';
const LS_LEGACY_DEEPSEEK_API_KEY = 'lorevista.deepseekApiKey';
const LS_LEGACY_COZE_API_KEY = 'lorevista.cozeApiKey';
const LS_PROVIDER_SETTINGS = 'lorevista.apiProviderSettings.v3';
const LS_PROVIDER_SETTINGS_LEGACY = 'lorevista.apiProviderSettings.v2';
export const API_KEY_CHANGE_EVENT = 'lorevista:api-key-change';

export type ApiCapability = 'llm' | 'image' | 'workflow';
export type ProviderMode = 'official' | 'custom';

export interface ProviderEndpointConfig {
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

export interface CapabilityProviderSettings {
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

function getFirstEntry(settings: CapabilityProviderSettings): ProviderPresetConfig | null {
  const firstEntryId = Object.keys(settings.entries)[0];
  return firstEntryId ? settings.entries[firstEntryId] : null;
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

export function getActiveProviderPreset(settings: ApiKeySettings, capability: ApiCapability): ProviderPresetConfig {
  const capabilitySettings = settings.providers[capability];
  return capabilitySettings.entries[capabilitySettings.activePresetId]
    || getFirstEntry(capabilitySettings)
    || createDefaultCapabilitySettings(capability).entries[createDefaultEntryKey(capability)];
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

export function getProviderModelSelectionMeta(
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
    ...(option.configId ? { config_id: option.configId } : {}),
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

export function clearApiKeySettings(): void {
  saveApiKeySettings(createDefaultSettings());
}

export async function getUserApiKeys(): Promise<{ provider: string; api_key_masked: string }[]> {
  const res = await authFetch(`${BASE}/api/user/api-keys`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function saveUserApiKey(provider: string, apiKey: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/user/api-keys`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, api_key: apiKey }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
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
  return providerConfigFromResponse(await res.json());
}

export async function deleteUserProviderConfig(configId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/user/provider-configs/${configId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export async function activateUserProviderConfig(configId: number): Promise<ProviderEndpointConfig> {
  const res = await authFetch(`${BASE}/api/user/provider-configs/${configId}/activate`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return providerConfigFromResponse(await res.json());
}

export async function deactivateUserProviderConfig(configId: number): Promise<ProviderEndpointConfig> {
  const res = await authFetch(`${BASE}/api/user/provider-configs/${configId}/deactivate`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return providerConfigFromResponse(await res.json());
}

function providerConfigFromResponse(item: any): ProviderEndpointConfig {
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
  const data = await res.json();
  return String(data.api_key ?? data.apiKey ?? '');
}

export async function getUserProviderConfigs(): Promise<Partial<Record<ApiCapability, ProviderEndpointConfig[]>>> {
  const res = await authFetch(`${BASE}/api/user/provider-configs`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  const result: Partial<Record<ApiCapability, ProviderEndpointConfig[]>> = {};
  (Array.isArray(data) ? data : []).forEach((item: any) => {
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
  const data = await res.json();
  return Array.isArray(data?.models)
    ? data.models.map((item: unknown) => String(item || '').trim()).filter(Boolean)
    : [];
}


function requestHeaders(method = 'GET', json = false, existing?: HeadersInit): Headers {
  const headers = new Headers(existing || undefined);
  if (json && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method.toUpperCase())) {
    headers.set('X-ArtVerse-Client', 'web');
  }
  return headers;
}

function apiHeaders(json = false, method = 'GET'): Record<string, string> {
  const headers: Record<string, string> = {};
  if (json) headers['Content-Type'] = 'application/json';
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method.toUpperCase())) {
    headers['X-ArtVerse-Client'] = 'web';
  }
  return headers;
}

async function authFetch(input: RequestInfo, init?: RequestInit): Promise<Response> {
  try {
    const method = init?.method || 'GET';
    let res = await fetch(input, {
      ...init,
      credentials: 'same-origin',
      headers: requestHeaders(method, false, init?.headers),
    });
    if (res.status === 401) {
      const refreshed = await tryRefreshToken();
      if (refreshed) {
        res = await fetch(input, {
          ...init,
          credentials: 'same-origin',
          headers: requestHeaders(method, false, init?.headers),
        });
      } else {
        notifyAuthExpired();
      }
    }
    return res;
  } catch (cause) {
    if (cause instanceof TypeError) {
      throw new Error('无法连接后端服务，请确认 8080 端口的 ArtVerse 后端已启动后重试。', { cause });
    }
    throw cause;
  }
}

export interface Story {
  id: number;
  title: string;
  description?: string;
  cover_image?: string | null;
  has_character_profiles?: boolean;
  has_ref_image?: boolean;
  created_at: string;
}

export interface ChatMessage {
  id: number;
  chapter_id?: number;
  role: string;
  content: string;
  completion_status?: 'complete' | 'partial';
  created_at: string;
}

export interface MangaImage {
  id: number;
  chapter_id: number;
  image_number: number;
  image_path: string;
  prompt: string | null;
  created_at: string;
}

export interface Chapter {
  id: number;
  story_id: number;
  chapter_number: number;
  version?: number;
  novel_content: string | null;
  content_source?: 'chat' | 'import' | null;
  created_at: string;
  messages: ChatMessage[];
  images: MangaImage[];
}


export async function createStory(title: string = 'Untitled Story', description: string = ''): Promise<Story> {
  const res = await authFetch(`${BASE}/api/stories`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, description }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function listStories(): Promise<Story[]> {
  const res = await authFetch(`${BASE}/api/stories`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function updateStory(storyId: number, data: { title?: string; description?: string }): Promise<Story> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStory(storyId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}

export async function exportStory(story: Story): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${story.id}/export`);
  if (!res.ok) throw new Error(await res.text());
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const safeTitle = (story.title || `story-${story.id}`).replace(/[\\/:*?"<>|]+/g, '_');
  const a = document.createElement('a');
  a.href = url;
  a.download = `${safeTitle}_lorevista.zip`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export interface ImportStoryProgress {
  phase: 'uploading' | 'processing';
  percent?: number;
  message: string;
}

function parseApiErrorResponse(text: string): { detail: string; code?: string } {
  try {
    const data = JSON.parse(text);
    if (typeof data?.detail === 'string') {
      return { detail: data.detail, code: typeof data?.code === 'string' ? data.code : undefined };
    }
    if (Array.isArray(data?.detail)) {
      return {
        detail: data.detail.map((item: any) => item?.msg || JSON.stringify(item)).join('; '),
        code: typeof data?.code === 'string' ? data.code : undefined,
      };
    }
    if (typeof data?.error === 'string') {
      return { detail: data.error, code: typeof data?.code === 'string' ? data.code : undefined };
    }
  } catch {
    // Plain text response.
  }
  return { detail: text || 'Request failed' };
}

function parseApiError(text: string): string {
  return parseApiErrorResponse(text).detail;
}

async function toApiError(response: Response): Promise<ApiError> {
  const text = await response.text();
  const parsed = parseApiErrorResponse(text);
  return new ApiError(parsed.detail, { code: parsed.code, status: response.status });
}

export function importStoryPackage(
  file: File,
  onProgress?: (progress: ImportStoryProgress) => void,
): Promise<Story> {
  return new Promise((resolve, reject) => {
    const doSend = () => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}/api/stories/import`);
      xhr.withCredentials = true;
      const headers = apiHeaders(false, 'POST');
      Object.entries(headers).forEach(([key, value]) => {
        if (typeof value === 'string') xhr.setRequestHeader(key, value);
      });
      xhr.responseType = 'text';

      const formData = new FormData();
      formData.append('file', file);

      xhr.upload.onprogress = (event) => {
        if (!event.lengthComputable) {
          onProgress?.({ phase: 'uploading', message: '...' });
          return;
        }
        const percent = Math.max(1, Math.min(99, Math.round((event.loaded / event.total) * 100)));
        onProgress?.({ phase: 'uploading', percent, message: `婵犳鍠楃换鎰緤閽樺鑰挎い蹇撴噽閳绘柨鈹戦悩杈厡闁绘劕锕ら湁闁挎繂妫涢惌濠囨煙娴ｅ啿娲ょ粈?${percent}%` });
      };

      xhr.onload = async () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress?.({ phase: 'processing', percent: 100, message: '...' });
          try {
            resolve(JSON.parse(xhr.responseText));
          } catch {
            reject(new Error('Import failed'));
          }
          return;
        }
        if (xhr.status === 401) {
          const refreshed = await tryRefreshToken();
          if (refreshed) {
            doSend();
            return;
          }
          notifyAuthExpired();
          reject(new Error('Login expired'));
          return;
        }
        reject(new Error(parseApiError(xhr.responseText)));
      };

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.onabort = () => reject(new Error('Cancelled'));
      xhr.ontimeout = () => reject(new Error('Timeout'));

      onProgress?.({ phase: 'uploading', percent: 0, message: '...' });
      xhr.send(formData);
      xhr.upload.onload = () => {
        onProgress?.({ phase: 'processing', percent: 100, message: '...' });
      };
    };
    doSend();
  });
}

export async function uploadStoryCover(storyId: number, base64: string): Promise<string> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/upload-cover`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cover_image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.cover_image;
}

function mangaStaticPath(imagePath: string): string {
  return imagePath.replace(/^manga_outputs\//, '');
}

function encodeStaticPath(imagePath: string): string {
  return mangaStaticPath(imagePath).split('/').map(encodeURIComponent).join('/');
}

export function mangaThumbUrl(imagePath: string | null | undefined, width = 720, cacheBust?: string | number): string | null {
  if (!imagePath) return null;
  const url = `${BASE}/static/manga/_thumb/${encodeStaticPath(imagePath)}?w=${width}`;
  return cacheBust ? `${url}&v=${encodeURIComponent(String(cacheBust))}` : url;
}

export function coverImageUrl(coverPath: string | null | undefined): string | null {
  if (!coverPath) return null;
  return `${BASE}/static/manga/${mangaStaticPath(coverPath)}`;
}


export async function getChapter(chapterId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function listChapters(storyId: number): Promise<Chapter[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/chapters`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function createNextChapter(storyId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/chapters`, { method: 'POST' });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteChapter(chapterId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}


export function chatStream(
  chapterId: number,
  content: string,
  onToken: (token: string) => void,
  _onDone: (message: ChatMessage) => void,
  onError: (err: string) => void,
  model?: string,
): AbortController {
  const controller = new AbortController();

  authFetch(`${BASE}/api/chapters/${chapterId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: content, ...getProviderRequestPayload('llm', model) }),
    signal: controller.signal,
  })
    .then(async (res) => {
      if (!res.ok) {
        onError(await res.text());
        return;
      }
      const reader = res.body?.getReader();
      if (!reader) return;
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';
      const handleLine = (line: string) => {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith(':')) return;
        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trim();
        } else if (trimmed.startsWith('data:')) {
          const dataStr = trimmed.slice(5).trim();
          try {
            const data = JSON.parse(dataStr);
            if (currentEvent === 'token' && data.content !== undefined) {
              onToken(data.content);
            } else if (currentEvent === 'done' && data.content !== undefined) {
              _onDone(data.message || {
                id: Date.now(),
                role: 'assistant',
                content: data.content,
                completion_status: 'complete',
                created_at: new Date().toISOString(),
              });
            } else if (currentEvent === 'error' || data.error) {
              onError(data.error);
            }
          } catch {
            // ignore
          }
          currentEvent = 'message';
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          handleLine(line);
        }
      }
      if (buffer.trim()) handleLine(buffer);

      // If stream ended without a done event, call onDone with empty
      // This handles edge cases where connection closes unexpectedly
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onError(err.message);
      }
    });

  return controller;
}


export async function generateNovel(chapterId: number): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/generate-novel`, { method: 'POST' });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function importNovel(chapterId: number, content: string): Promise<Chapter> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/import-novel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}


export async function generateScenes(chapterId: number, signal?: AbortSignal): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/generate-scenes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({}),
    signal,
  });
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.scenes;
}

export async function generateScenesWithModel(chapterId: number, model?: string, signal?: AbortSignal): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/generate-scenes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(model ? { model } : {}),
    signal,
  });
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.scenes;
}

export async function getScenes(chapterId: number): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/scenes`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.scenes;
}

export async function updateScenes(chapterId: number, scenes: string[]): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/scenes`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(scenes),
  });
  if (!res.ok) throw new Error(await res.text());
}


// Story-level (global)
export async function getStoryCharacters(storyId: number): Promise<string> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.characters;
}

export async function saveStoryCharacters(storyId: number, characters: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characters }),
  });
  if (!res.ok) throw new Error(await res.text());
}

// Chapter-level (with source info)
export type CharacterSource = 'chapter' | 'asset_group' | 'story' | 'none';

export async function getCharacters(chapterId: number): Promise<{ characters: string; source: CharacterSource; group_id?: number; group_name?: string }> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/characters`);
  if (!res.ok) throw new Error(await res.text());
  return await res.json();
}

export async function saveCharacters(chapterId: number, characters: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/characters`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characters }),
  });
  if (!res.ok) throw new Error(await res.text());
}

export async function resetChapterCharacters(chapterId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/characters`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
}


// ===== Individual Character Profiles =====
export interface CharacterProfile {
  id: number;
  name: string;
  description: string;
  created_at: string;
  updated_at: string;
}

export async function listCharacterProfiles(storyId: number): Promise<CharacterProfile[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function createCharacterProfile(storyId: number, name: string, description: string): Promise<CharacterProfile> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function updateCharacterProfile(storyId: number, characterId: number, name: string, description: string): Promise<CharacterProfile> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function deleteCharacterProfile(storyId: number, characterId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export interface CharRefImage {
  filename: string;
  object_key: string;
  size_kb: number;
}

export async function listCharRefImages(storyId: number, characterId: number): Promise<CharRefImage[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}/ref-images`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function addCharRefImage(storyId: number, characterId: number, base64: string): Promise<CharRefImage> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function deleteCharRefImage(storyId: number, characterId: number, filename: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/characters/${characterId}/ref-images/${encodeURIComponent(filename)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export type RefSource = 'chapter' | 'asset_group' | 'story' | 'none';

export interface RefImage {
  filename: string;
  image_path: string;
  size_kb: number;
}

export interface RefImagesPayload {
  images: RefImage[];
  max: number;
  source?: RefSource;
  group_id?: number;
  group_name?: string;
}

export function refImageUrl(imagePath: string): string {
  return `${BASE}/static/manga/${mangaStaticPath(imagePath)}`;
}

export interface AssetGroupCharacter {
  id: number;
  name: string;
  description: string;
}

export interface AssetGroup {
  id: number | null;
  name: string;
  description: string;
  is_default?: boolean;
  characters: AssetGroupCharacter[];
}

export interface AssetGroupsPayload {
  groups: AssetGroup[];
  max: number;
  selected_group_id?: number | null;
}

export interface AssetGroupSinglePayload {
  group: AssetGroup;
  groups: AssetGroup[];
}

export async function getStoryAssetGroups(storyId: number): Promise<AssetGroup[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function createStoryAssetGroup(storyId: number, name: string, description?: string, characterIds?: number[]): Promise<AssetGroup> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description: description || '', characterIds: characterIds || [] }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function updateStoryAssetGroup(groupId: number, data: { name?: string; description?: string; characterIds?: number[] }): Promise<AssetGroup> {
  const res = await authFetch(`${BASE}/api/asset-groups/${groupId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStoryAssetGroup(storyId: number, groupId: number): Promise<AssetGroup[]> {
  const res = await authFetch(`${BASE}/api/asset-groups/${groupId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await res.text());
  return getStoryAssetGroups(storyId);
}

export async function addStoryAssetGroupRefImage(storyId: number, groupId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/asset-groups/${groupId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStoryAssetGroupRefImage(storyId: number, groupId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/stories/${storyId}/asset-groups/${groupId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function getChapterAssetGroup(chapterId: number): Promise<AssetGroupsPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/asset-group`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function setChapterAssetGroup(chapterId: number, groupId: number | null): Promise<AssetGroupsPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/asset-group`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ group_id: groupId }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// Story-level
export async function getStoryRefImages(storyId: number): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/ref-images`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function addStoryRefImage(storyId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteStoryRefImage(storyId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/stories/${storyId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// Chapter-level (with story fallback)
export async function getChapterRefImages(chapterId: number): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/ref-images`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function addChapterRefImage(chapterId: number, base64: string): Promise<RefImagesPayload> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/ref-images`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: base64 }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteChapterRefImage(chapterId: number, filename: string): Promise<RefImagesPayload> {
  const res = await authFetch(
    `${BASE}/api/chapters/${chapterId}/ref-images/${encodeURIComponent(filename)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}


export type MangaStyle = 'japanese_manga' | 'korean_webtoon' | 'american_comic' | 'ligne_claire' | 'chinese_ink' | 'semi_realistic' | 'realistic' | 'oil_painting' | 'flat_design' | 'pixel_art' | 'watercolor' | 'cyberpunk';

export const MANGA_STYLE_LABELS: Record<MangaStyle, string> = {
  japanese_manga: '日式漫画',
  korean_webtoon: '韩式网漫',
  american_comic: '美式漫画',
  ligne_claire: '欧式清线',
  chinese_ink: '中国水墨',
  semi_realistic: '半写实',
  realistic: '写实',
  oil_painting: '油画',
  flat_design: '扁平设计',
  pixel_art: '像素艺术',
  watercolor: '水彩',
  cyberpunk: '赛博朋克',
};

export async function getMangaStyle(storyId: number): Promise<MangaStyle> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/manga-style`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.manga_style || 'japanese_manga';
}

export async function setMangaStyle(storyId: number, style: MangaStyle): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/manga-style`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ manga_style: style }),
  });
  if (!res.ok) throw new Error(await res.text());
}


export type ColorMode = 'bw' | 'grayscale' | 'color' | 'duotone';

export const COLOR_MODE_LABELS: Record<ColorMode, string> = {
  bw: '黑白',
  grayscale: '灰度',
  color: '彩色',
  duotone: '双色调',
};


export async function getColorMode(chapterId: number): Promise<ColorMode> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/color-mode`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.color_mode || 'bw';
}

export async function setColorMode(chapterId: number, mode: ColorMode): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/color-mode`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ color_mode: mode }),
  });
  if (!res.ok) throw new Error(await res.text());
}


export const ALLOWED_IMAGE_COUNTS = [4, 6, 8, 10, 12, 15, 20] as const;

export async function getImageCount(chapterId: number): Promise<number> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/image-count`);
  if (!res.ok) throw new Error(await res.text());
  const data = await res.json();
  return data.image_count ?? 10;
}

export async function setImageCount(chapterId: number, count: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/image-count`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image_count: count }),
  });
  if (!res.ok) throw new Error(await res.text());
}

export async function regenerateImage(
  chapterId: number,
  imageNumber: number,
  prompt: string,
  model?: string,
): Promise<{ id: number; image_number: number; image_path: string; prompt: string }> {
  const providerPayload = getImageGenerationProviderPayload(model);
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/regenerate-image/${imageNumber}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt, ...providerPayload }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}


export interface MangaProgress {
  type: 'status' | 'scenes' | 'progress' | 'image' | 'image_error' | 'done' | 'error';
  data: any;
}

export function generateMangaStream(
  chapterId: number,
  assetGroupId: number | null | undefined,
  onEvent: (event: MangaProgress) => void,
  model?: string,
): AbortController {
  const controller = new AbortController();
  let reconnectAttempts = 0;
  let reconnectTimer: number | undefined;
  const maxReconnectAttempts = 120;

  const scheduleReconnect = (reason: string) => {
    if (controller.signal.aborted) return;
    reconnectAttempts += 1;
    if (reconnectAttempts > maxReconnectAttempts) {
      onEvent({ type: 'error', data: { error: reason || 'Stream disconnected too many times.' } });
      return;
    }
    onEvent({
      type: 'status',
      data: { message: `Reconnecting... (${reconnectAttempts}/${maxReconnectAttempts})` },
    });
    reconnectTimer = window.setTimeout(connect, Math.min(5000, 1000 + reconnectAttempts * 500));
  };

  controller.signal.addEventListener('abort', () => {
    if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer);
  });

  const connect = () => {
    const providerPayload = getImageGenerationProviderPayload(model);
    authFetch(`${BASE}/api/chapters/${chapterId}/generate-manga-stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ assetGroupId: assetGroupId ?? null, ...providerPayload }),
      signal: controller.signal,
    })
      .then(async (res) => {
      if (!res.ok) {
        onEvent({ type: 'error', data: { error: await res.text() } });
        return;
      }
      const reader = res.body?.getReader();
      if (!reader) return;
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';
      let receivedTerminalEvent = false;
      const handleLine = (line: string) => {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith(':')) return;
        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trim();
        } else if (trimmed.startsWith('data:')) {
          const dataStr = trimmed.slice(5).trim();
          try {
            const data = JSON.parse(dataStr);
            const eventType = currentEvent as MangaProgress['type'];
            if (eventType === 'done' || eventType === 'error') receivedTerminalEvent = true;
            onEvent({ type: eventType, data });
          } catch {
            // ignore unparseable data
          }
          currentEvent = 'message';
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          handleLine(line);
        }
      }
      if (buffer.trim()) handleLine(buffer);
      if (!receivedTerminalEvent && !controller.signal.aborted) {
        scheduleReconnect('Stream disconnected unexpectedly.');
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        scheduleReconnect(err.message || 'Stream disconnected unexpectedly.');
      }
    });
  };

  connect();

  return controller;
}

export function mangaImageUrl(imagePath: string, cacheBust?: number): string {
  // imagePath is like "manga_outputs/chapter_1/panel_01_abc12345.png"
  // Served at /static/manga/chapter_1/panel_01_abc12345.png
  const url = `${BASE}/static/manga/${mangaStaticPath(imagePath)}`;
  return cacheBust ? `${url}?t=${cacheBust}` : url;
}

// ---- Publish ----
export async function publishStory(storyId: number, format: 'novel' | 'manga', isPublished: boolean, chapterIds?: number[]): Promise<Story> {
  const res = await authFetch(BASE+'/api/stories/'+storyId+'/publish', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ format, is_published: isPublished, chapter_ids: chapterIds }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function updateChapterOrder(storyId: number, orders: { chapter_id: number; display_order: number; display_title?: string }[]): Promise<void> {
  const res = await authFetch(BASE+'/api/stories/'+storyId+'/chapter-order', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ orders }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

// ---- Square ----
export type SquareFormat = 'all' | 'novel' | 'manga';
export interface SquareStory { id: number; format: Exclude<SquareFormat, 'all'>; title: string; description: string; cover_url: string; manga_style: string; published_at: string | null; chapter_count: number; content_count: number; }
export interface SquareStoryDetail { id: number; format: Exclude<SquareFormat, 'all'>; title: string; description: string; cover_url: string; manga_style: string; published_at: string | null; available_formats: Exclude<SquareFormat, 'all'>[]; chapters: { id: number; chapter_number: number; display_title: string; content: string | null; content_count: number; images: { id: number; image_number: number; image_url: string }[] }[]; }

export async function listSquareStories(page = 0, size = 12, search?: string, format: SquareFormat = 'manga'): Promise<{ content: SquareStory[]; total_pages: number; total_elements: number; facets: Record<SquareFormat, number> }> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (search) params.set('search', search);
  params.set('format', format);
  const res = await fetch(BASE+'/api/square/stories?'+params);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function getSquareStoryDetail(id: number, format: Exclude<SquareFormat, 'all'> = 'manga'): Promise<SquareStoryDetail> {
  const res = await fetch(BASE+'/api/square/stories/'+id+'?format='+format);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

// ---- My Works ----
export interface MyWorkChapter {
  id: number;
  chapter_number: number;
  is_published: boolean;
  manga_is_published: boolean;
  novel_is_published: boolean;
  has_novel_content: boolean;
  novel_char_count: number;
  manga_image_count: number;
  display_order: number;
  display_title: string;
  status: string;
}
export interface MyWork {
  id: number;
  title: string;
  description: string;
  cover_image: string;
  is_published: boolean;
  manga_is_published: boolean;
  novel_is_published: boolean;
  published_at: string | null;
  manga_published_at: string | null;
  novel_published_at: string | null;
  created_at: string | null;
  chapters: MyWorkChapter[];
}

export async function listMyWorks(): Promise<MyWork[]> {
  const res = await authFetch(BASE+'/api/works');
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

// ---- Image Gen ----
export interface ImageGenRecord {
  id: number;
  conversation_id?: string;
  prompt: string;
  image_url: string | null;
  model: string;
  size: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  failure_reason: string | null;
  created_at: string;
  completed_at: string | null;
}

// ---- Story knowledge base ----
export type KnowledgeUnitType = 'CHARACTER_CARD' | 'CHARACTER_RELATION' | 'WORLDVIEW' | 'TIMELINE' | 'FORESHADOWING';
export interface KnowledgeUnit {
  id: number;
  type: KnowledgeUnitType;
  title: string;
  body: string;
  summary: string;
  structuredData: Record<string, unknown>;
  importance: number;
  effectiveFromChapter: number | null;
  effectiveToChapter: number | null;
  status: 'ACTIVE' | 'ARCHIVED';
  version: number;
  indexStatus: string;
  updatedAt: string;
}
export interface KnowledgeUnitInput {
  type: KnowledgeUnitType;
  title: string;
  body: string;
  summary: string;
  structuredData: Record<string, unknown>;
  importance: number;
  effectiveFromChapter?: number | null;
  effectiveToChapter?: number | null;
}
export interface EmbeddingConfigInfo {
  id: number;
  displayName: string;
  baseUrl: string;
  model: string;
  apiKeyMasked: string;
  customHeaders: string;
  status: 'UNVERIFIED' | 'VERIFIED' | 'RETIRED';
  actualDimension: number | null;
  configVersion: number;
  active: boolean;
  usedByStories: boolean;
  usedByStoryTitles?: string[];
}
export interface EmbeddingConfigInput {
  configId?: number;
  displayName: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  customHeaders: string;
}
export interface KnowledgeRecallPreview {
  items: Array<{ knowledgeUnitId: number; version: number; type: KnowledgeUnitType; title: string; content: string; score: number }>;
  context: string;
  contextHash: string;
  embeddingSpaceId: number;
  snapshotId?: number | null;
}

export type NovelRevisionSource = 'manual' | 'ai' | 'restore' | 'legacy_import' | 'generated';

export interface NovelRevision {
  id: number;
  revision_number: number;
  content: string;
  content_hash: string;
  source: NovelRevisionSource;
  created_at: string;
}

export interface NovelContentSaveResult {
  changed: boolean;
  chapter_version: number;
  revision_id?: number;
  revision_number?: number;
  content_hash?: string;
  auditId?: string;
}

export async function saveNovelContent(
  chapterId: number,
  content: string,
  baseVersion: number,
): Promise<NovelContentSaveResult> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/novel-content`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content, base_version: baseVersion }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function listNovelRevisions(chapterId: number): Promise<NovelRevision[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/novel-content/revisions`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function restoreNovelRevision(
  chapterId: number,
  revisionId: number,
  baseVersion: number,
): Promise<NovelContentSaveResult> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/novel-content/revisions/${revisionId}/restore`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ base_version: baseVersion }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export interface AiConversationSummary {
  conversationId: string;
  type: 'MANGA_AGENT' | 'STORY_CHAT' | 'IMAGE_GEN';
  title: string;
  titleSource: 'DEFAULT' | 'AI' | 'FALLBACK' | 'USER';
  titleState: 'WAITING' | 'GENERATING' | 'FINALIZED';
  status: 'ACTIVE' | 'ARCHIVED';
  chapterId: number | null;
  lastActivityAt: string;
}

export async function listAiConversations(type: AiConversationSummary['type'], chapterId?: number): Promise<AiConversationSummary[]> {
  const params = new URLSearchParams({ type });
  if (chapterId != null) params.set('chapterId', String(chapterId));
  const res = await authFetch(`${BASE}/api/ai/conversations?${params}`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function renameAiConversation(conversationId: string, title: string): Promise<AiConversationSummary> {
  const res = await authFetch(`${BASE}/api/ai/conversations/${conversationId}/title`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function createAiConversation(type: AiConversationSummary['type'], title?: string, chapterId?: number): Promise<AiConversationSummary> {
  const res = await authFetch(`${BASE}/api/ai/conversations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, title, ...(chapterId != null ? { chapterId } : {}) }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function archiveAiConversation(conversationId: string): Promise<void> {
  const res = await authFetch(`${BASE}/api/ai/conversations/${conversationId}/archive`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export type KnowledgeCandidateStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED';
export interface KnowledgeCandidate {
  id: number;
  sourceType: string;
  sourceId: string | null;
  knowledgeType: KnowledgeUnitType;
  title: string;
  body: string;
  summary: string;
  structuredData: Record<string, unknown>;
  importance: number;
  effectiveFromChapter: number | null;
  effectiveToChapter: number | null;
  status: KnowledgeCandidateStatus;
  approvedKnowledgeUnitId: number | null;
  rejectionReason: string | null;
  createdAt: string;
  reviewedAt: string | null;
}

function knowledgeCandidateFromResponse(item: any): KnowledgeCandidate {
  return {
    id: Number(item.id),
    sourceType: String(item.source_type ?? item.sourceType ?? ''),
    sourceId: item.source_id == null && item.sourceId == null ? null : String(item.source_id ?? item.sourceId),
    knowledgeType: String(item.knowledge_type ?? item.knowledgeType) as KnowledgeUnitType,
    title: String(item.title ?? ''),
    body: String(item.body ?? ''),
    summary: String(item.summary ?? ''),
    structuredData: (item.structured_data ?? item.structuredData ?? {}) as Record<string, unknown>,
    importance: Number(item.importance ?? 3),
    effectiveFromChapter: item.effective_from_chapter == null && item.effectiveFromChapter == null ? null : Number(item.effective_from_chapter ?? item.effectiveFromChapter),
    effectiveToChapter: item.effective_to_chapter == null && item.effectiveToChapter == null ? null : Number(item.effective_to_chapter ?? item.effectiveToChapter),
    status: String(item.status ?? 'PENDING') as KnowledgeCandidateStatus,
    approvedKnowledgeUnitId: item.approved_knowledge_unit_id == null && item.approvedKnowledgeUnitId == null ? null : Number(item.approved_knowledge_unit_id ?? item.approvedKnowledgeUnitId),
    rejectionReason: item.rejection_reason == null && item.rejectionReason == null ? null : String(item.rejection_reason ?? item.rejectionReason),
    createdAt: String(item.created_at ?? item.createdAt ?? ''),
    reviewedAt: item.reviewed_at == null && item.reviewedAt == null ? null : String(item.reviewed_at ?? item.reviewedAt),
  };
}

export async function listKnowledgeCandidates(storyId: number, status: KnowledgeCandidateStatus | '' = 'PENDING'): Promise<KnowledgeCandidate[]> {
  const query = status ? `?status=${status}` : '';
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/candidates${query}`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return Array.isArray(data) ? data.map(knowledgeCandidateFromResponse) : [];
}

export async function approveKnowledgeCandidate(storyId: number, candidateId: number): Promise<KnowledgeCandidate> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/candidates/${candidateId}/approve`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return knowledgeCandidateFromResponse(await res.json());
}

export async function rejectKnowledgeCandidate(storyId: number, candidateId: number, reason = ''): Promise<KnowledgeCandidate> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/candidates/${candidateId}/reject`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return knowledgeCandidateFromResponse(await res.json());
}

export async function listAgentSkills(): Promise<UserAgentSkill[]> {
  const res = await authFetch(`${BASE}/api/user/agent-skills`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return Array.isArray(data) ? data.map(userAgentSkillFromResponse) : [];
}

export async function setAgentSkillEnabled(skillKey: string, enabled: boolean): Promise<UserAgentSkill> {
  const res = await authFetch(`${BASE}/api/user/agent-skills/${encodeURIComponent(skillKey)}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled }),
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return userAgentSkillFromResponse(await res.json());
}

function userAgentSkillFromResponse(item: any): UserAgentSkill {
  const manifest = item.manifest ?? {};
  return {
    enabled: Boolean(item.enabled),
    manifest: {
      skillKey: String(manifest.skill_key ?? manifest.skillKey ?? ''),
      semanticVersion: String(manifest.semantic_version ?? manifest.semanticVersion ?? ''),
      checksum: String(manifest.checksum ?? ''),
      status: String(manifest.status ?? ''),
      supportedRoutes: (manifest.supported_routes ?? manifest.supportedRoutes ?? []).map(String),
      capabilities: (manifest.capabilities ?? []).map(String),
      promptVersion: String(manifest.prompt_version ?? manifest.promptVersion ?? ''),
      allowedToolGroups: (manifest.allowed_tool_groups ?? manifest.allowedToolGroups ?? []).map(String),
      budgetPolicy: (manifest.budget_policy ?? manifest.budgetPolicy ?? {}) as Record<string, unknown>,
      evaluatorKey: manifest.evaluator_key ?? manifest.evaluatorKey ?? null,
      hitlPolicy: String(manifest.hitl_policy ?? manifest.hitlPolicy ?? 'NONE'),
      userConfigurable: Boolean(manifest.user_configurable ?? manifest.userConfigurable),
    },
  };
}

function knowledgePayload(input: KnowledgeUnitInput): Record<string, unknown> {
  return {
    type: input.type,
    title: input.title,
    body: input.body,
    summary: input.summary,
    structured_data: input.structuredData,
    importance: input.importance,
    effective_from_chapter: input.effectiveFromChapter ?? null,
    effective_to_chapter: input.effectiveToChapter ?? null,
  };
}
export async function listKnowledge(storyId: number): Promise<KnowledgeUnit[]> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}
export async function createKnowledge(storyId: number, input: KnowledgeUnitInput): Promise<KnowledgeUnit> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(knowledgePayload(input)) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}
export async function updateKnowledge(storyId: number, id: number, input: KnowledgeUnitInput): Promise<KnowledgeUnit> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(knowledgePayload(input)) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}
export async function archiveKnowledge(storyId: number, id: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}
export async function getEmbeddingConfigs(): Promise<EmbeddingConfigInfo[]> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return Array.isArray(data) ? data.map(embeddingConfigFromResponse) : [];
}
function embeddingConfigPayload(input: EmbeddingConfigInput): Record<string, unknown> {
  return { config_id: input.configId, display_name: input.displayName, base_url: input.baseUrl, api_key: input.apiKey, model: input.model, custom_headers: input.customHeaders };
}
function embeddingConfigFromResponse(item: any): EmbeddingConfigInfo {
  return {
    id: Number(item.id),
    displayName: String(item.display_name ?? item.displayName ?? ''),
    baseUrl: String(item.base_url ?? item.baseUrl ?? ''),
    model: String(item.model ?? ''),
    apiKeyMasked: String(item.api_key_masked ?? item.apiKeyMasked ?? ''),
    customHeaders: String(item.custom_headers ?? item.customHeaders ?? '{}'),
    status: String(item.status ?? 'UNVERIFIED') as EmbeddingConfigInfo['status'],
    actualDimension: item.actual_dimension == null && item.actualDimension == null ? null : Number(item.actual_dimension ?? item.actualDimension),
    configVersion: Number(item.config_version ?? item.configVersion ?? 1),
    active: Boolean(item.active),
    usedByStories: Boolean(item.used_by_stories ?? item.usedByStories),
    usedByStoryTitles: (item.used_by_story_titles ?? item.usedByStoryTitles ?? []).map((title: unknown) => String(title)),
  };
}
export async function saveEmbeddingConfig(input: EmbeddingConfigInput): Promise<EmbeddingConfigInfo> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(embeddingConfigPayload(input)) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return embeddingConfigFromResponse(await res.json());
}
export async function testEmbeddingConfig(configId: number): Promise<{ config: EmbeddingConfigInfo; embeddingSpaceId: number; dimension: number }> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs/test`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ config_id: configId }) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return { config: embeddingConfigFromResponse(data.config), embeddingSpaceId: Number(data.embedding_space_id ?? data.embeddingSpaceId), dimension: Number(data.dimension) };
}
export async function activateEmbeddingConfig(configId: number): Promise<EmbeddingConfigInfo> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs/${configId}/activate`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return embeddingConfigFromResponse(await res.json());
}
export async function deactivateEmbeddingConfig(configId: number): Promise<EmbeddingConfigInfo> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs/${configId}/deactivate`, { method: 'POST' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return embeddingConfigFromResponse(await res.json());
}
export async function discoverEmbeddingModels(input: EmbeddingConfigInput): Promise<string[]> {
  const res = await authFetch(`${BASE}/api/user/embedding-configs/models/discover`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(embeddingConfigPayload(input)) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return Array.isArray(data.models) ? data.models.map((model: unknown) => String(model)).filter(Boolean) : [];
}
export async function rebuildKnowledge(storyId: number, configId: number): Promise<void> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/rebuild`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ config_id: configId }) });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}
export async function previewKnowledge(storyId: number, chapterNumber: number, query: string): Promise<KnowledgeRecallPreview> {
  const res = await authFetch(`${BASE}/api/stories/${storyId}/knowledge/preview?chapterNumber=${chapterNumber}&query=${encodeURIComponent(query)}`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function generateImage(prompt: string, referenceImages?: string[], size?: string, model?: string, signal?: AbortSignal, conversationId?: string): Promise<ImageGenRecord> {
  const res = await authFetch(BASE+'/api/image-gen/generate', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt, reference_images: referenceImages || [], ...(size ? { size } : {}), ...(conversationId ? { conversation_id: conversationId } : {}), ...getProviderRequestPayload('image', model) }),
    signal,
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function listImageGenHistory(page = 0, size = 12, conversationId?: string): Promise<{ content: ImageGenRecord[]; total_pages: number; total_elements: number }> {
  const res = await authFetch(BASE+'/api/image-gen/history?page='+page+'&size='+size+(conversationId ? '&conversation_id='+encodeURIComponent(conversationId) : ''));
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export async function deleteImageGenRecord(id: number): Promise<void> {
  const res = await authFetch(BASE+'/api/image-gen/'+id, { method: 'DELETE' });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
}

export function imageGenUrl(objectKey: string): string {
  return `${BASE}/static/manga/${encodeStaticPath(objectKey)}`;
}

export interface GuardActionStats {
  action: string;
  total: number;
  leader: number;
  follower: number;
  success_hit: number;
  failed_hit: number;
  follower_rejected: number;
  processing_rejected: number;
  failed: number;
  hit_rate: number;
  reuse_rate: number;
  single_flight_rate: number;
  reject_rate: number;
}

export interface GuardStatsPayload {
  updated_at: string;
  actions: GuardActionStats[];
}

export async function getGuardStats(): Promise<GuardStatsPayload> {
  const res = await fetch(BASE + '/api/internal/guard/stats');
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export interface GuardMetricBucket {
  bucket_type: string;
  bucket_start: string;
  action: string;
  total: number;
  leader: number;
  follower: number;
  success_hit: number;
  failed_hit: number;
  follower_rejected: number;
  processing_rejected: number;
  failed: number;
}

export async function getGuardMetrics(bucket = 'HOUR', range = 24): Promise<{ updated_at: string; bucket_type: string; items: GuardMetricBucket[] }> {
  const res = await fetch(BASE + '/api/internal/guard/metrics?bucket=' + bucket + '&range=' + range);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export interface GuardEvent {
  id: string;
  time: string;
  action: string;
  scope: string;
  decision: string;
  result: string;
  key_hash: string;
  duration_ms?: number | null;
  summary?: Record<string, unknown>;
  message?: string;
}

export async function getGuardEvents(limit = 100): Promise<{ events: GuardEvent[] }> {
  const res = await fetch(BASE + '/api/internal/guard/events?limit=' + limit);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export type MangaWorkflowRoute = 'CONVERSATION' | 'CREATIVE' | 'STORYBOARD' | 'REVIEW' | 'DIRECTOR';

export type MangaAgentRunEvent =
  | { type: 'status'; data: { message?: string; requestId?: string; request_id?: string } }
  | { type: 'run_event'; data: AgentRunTimelineEvent }
  | { type: 'tool'; data: { tool?: string; succeeded?: boolean; saved?: boolean; scenes_count?: number; error?: string } }
  | { type: 'user_input_requested'; data: AgentUserInputRequest }
  | { type: 'done'; data: { reply?: string; requestId?: string; request_id?: string } }
  | { type: 'error'; data: { detail?: string; error?: string; requestId?: string; request_id?: string } }
  | { type: 'ag_ui_event'; data: ArtVerseAgUiEvent };

export type ArtVerseAgUiEvent = AGUIEvent & {
  protocol?: 'ag-ui';
  runId?: string;
  route?: MangaWorkflowRoute;
  rawEvent?: AgentRunTimelineEvent | Record<string, unknown>;
  snapshot?: {
    requestId?: string;
    runId?: string;
    status?: string;
    message?: string;
    route?: MangaWorkflowRoute;
  };
  result?: {
    reply?: string;
  };
  outcome?: {
    type?: 'success' | 'interrupt';
    interrupts?: Array<{
      id: string;
      reason: string;
      message?: string;
      metadata?: {
        question?: string;
        options?: AgentUserInputOption[];
        allowFreeText?: boolean;
        purpose?: AgentUserInputRequest['purpose'];
        reason?: string;
      };
    }>;
  };
};

export interface AgentRunTimelineEvent {
  type: string;
  phase?: string;
  label?: string;
  toolName?: string;
  status?: string;
  text?: string;
  data?: Record<string, unknown>;
  createdAt?: string;
}

export type MangaAgentRunStatus = 'RUNNING' | 'WAITING_USER' | 'SUCCEEDED' | 'DEGRADED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';

export interface AgentRunPersistedEvent {
  eventId?: number;
  eventName: MangaAgentRunEvent['type'];
  data: MangaAgentRunEvent['data'];
  createdAt?: string;
}

export interface MangaAgentRunSnapshot {
  requestId: string;
  request_id?: string;
  route?: MangaWorkflowRoute;
  status: MangaAgentRunStatus;
  inputMessage?: string;
  finalReply?: string;
  errorMessage?: string;
  userInputRequest?: AgentUserInputRequest | null;
  events: AgentRunPersistedEvent[];
  createdAt?: string;
  updatedAt?: string;
  completedAt?: string | null;
  lastProgressAt?: string;
  currentPhase?: 'MODEL' | 'TOOL' | string;
  routeSource?: 'AUTO' | 'RESUME_FIXED' | 'RESUME_RECLASSIFIED' | 'SHADOW' | 'FALLBACK' | string;
  routeConfidence?: number | null;
  routerVersion?: string | null;
  workflowVersion?: string;
  traceId?: string;
  skillVersions?: Record<string, string>;
  modelConfigId?: number | null;
  knowledgeSnapshotId?: number | null;
  budgetUsage?: Record<string, number>;
  runAttributes?: Record<string, unknown>;
  contextSnapshot?: {
    storyId?: number | null;
    chapterId?: number | null;
    storyTitle?: string | null;
    chapterDisplayName?: string | null;
    sceneCount?: number;
    imageCount?: number;
    contextHash?: string | null;
    knowledgeRecallHash?: string | null;
    requiredFields?: string[];
    warnings?: string[];
  } | null;
  steps?: MangaAgentRunStep[];
  artifacts?: MangaAgentArtifactSummary[];
}

export interface MangaAgentRunStep {
  planId: string;
  sequence: number;
  route: MangaWorkflowRoute;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
  mutating: boolean;
  skillKey?: string | null;
  skillVersion?: string | null;
  inputSummary?: string | null;
  outputSummary?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
}

export interface MangaAgentArtifactSummary {
  artifactId: string;
  type: string;
  status: 'DRAFT' | 'VALIDATED' | 'REJECTED' | 'COMMITTED' | 'SUPERSEDED';
  schemaVersion: string;
  evaluation: Record<string, unknown>;
  checksum: string;
}

export interface MangaAgentArtifact extends MangaAgentArtifactSummary {
  requestId: string;
  payload: Record<string, unknown>;
}

export interface AgentSkillManifest {
  skillKey: string;
  semanticVersion: string;
  checksum: string;
  status: string;
  supportedRoutes: string[];
  capabilities: string[];
  promptVersion: string;
  allowedToolGroups: string[];
  budgetPolicy: Record<string, unknown>;
  evaluatorKey?: string | null;
  hitlPolicy: string;
  userConfigurable: boolean;
}

export interface UserAgentSkill { manifest: AgentSkillManifest; enabled: boolean; }

export interface AgentUserInputOption {
  id: string;
  label: string;
  description?: string;
  recommended?: boolean;
}

export interface AgentUserInputRequest {
  requestId?: string;
  request_id?: string;
  question: string;
  options: AgentUserInputOption[];
  allowFreeText?: boolean;
  reason?: string;
  purpose?: 'ROUTING' | 'MUTATION_CONFIRMATION' | 'BUSINESS_CONFIRMATION' | string;
}

class ArtVerseMangaAgentHttpAgent extends HttpAgent {
  private readonly message: string;
  private readonly requestId?: string;
  private readonly answer?: string | Record<string, unknown>;
  private readonly model?: string;

  constructor(
    url: string,
    message: string,
    requestId: string | undefined,
    abortController: AbortController,
    answer?: string | Record<string, unknown>,
    model?: string,
  ) {
    super({
      url,
      headers: apiHeaders(true, 'POST'),
    });
    this.message = message;
    this.requestId = requestId;
    this.answer = answer;
    this.model = model;
    this.abortController = abortController;
  }

  protected override requestInit(input: RunAgentInput): RequestInit {
    const providerPayload = getProviderRequestPayload('llm', this.model);
    return {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        ...this.headers,
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(this.answer === undefined
        ? {
          message: this.message,
          requestId: this.requestId || input.runId,
          ...providerPayload,
        }
        : typeof this.answer === 'string'
          ? {
            answer: this.answer,
            ...providerPayload,
          }
          : {
            ...this.answer,
            ...providerPayload,
          }),
      signal: this.abortController.signal,
    };
  }
}

function streamErrorMessage(err: unknown, fallback: string): string {
  const payload = (err as { payload?: { detail?: unknown; error?: unknown } } | null)?.payload;
  const detail = payload?.detail ?? payload?.error;
  if (typeof detail === 'string' && detail.trim()) return detail;
  const message = (err as { message?: unknown } | null)?.message;
  return typeof message === 'string' && message ? message : fallback;
}

export function runStoryChatAgUiStream(
  chapterId: number,
  conversationId: string,
  message: string,
  requestId: string | undefined,
  onEvent: (event: MangaAgentRunEvent) => void,
  onErrorOrModel?: ((error: string) => void) | string,
  modelArg?: string,
): AbortController {
  const controller = new AbortController();
  const onError = typeof onErrorOrModel === 'function' ? onErrorOrModel : undefined;
  const model = typeof onErrorOrModel === 'string' ? onErrorOrModel : modelArg;
  const agent = new ArtVerseMangaAgentHttpAgent(
    `${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/ag-ui/run`,
    message,
    requestId,
    controller,
    undefined,
    model,
  );
  const runId = requestId || createClientRequestId();
  const subscription = agent.run({
    threadId: `story-chat-${chapterId}-${conversationId}`,
    runId,
    state: {},
    messages: [{ id: `user-${runId}`, role: 'user', content: message }],
    tools: [],
    context: [],
    forwardedProps: {},
  }).subscribe({
    next: (event) => onEvent({ type: 'ag_ui_event', data: event as ArtVerseAgUiEvent }),
    error: (err) => {
      if (!controller.signal.aborted) {
        const detail = streamErrorMessage(err, 'Story chat stream disconnected');
        onError?.(detail);
        onEvent({ type: 'error', data: { detail, requestId: runId } });
      }
    },
  });
  controller.signal.addEventListener('abort', () => subscription.unsubscribe());
  return controller;
}

export function resumeStoryChatAgUiStream(
  chapterId: number,
  conversationId: string,
  requestId: string,
  decision: 'confirm' | 'discard',
  artifactId: string,
  onEvent: (event: MangaAgentRunEvent) => void,
  model?: string,
): AbortController {
  const controller = new AbortController();
  const agent = new ArtVerseMangaAgentHttpAgent(
    `${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/ag-ui/runs/${requestId}/resume`,
    '',
    requestId,
    controller,
    { decision, artifact_id: artifactId, artifactId },
    model,
  );
  const subscription = agent.run({
    threadId: `story-chat-${chapterId}-${conversationId}`,
    runId: requestId,
    state: {},
    messages: [],
    tools: [],
    context: [],
    forwardedProps: {},
  }).subscribe({
    next: (event) => onEvent({ type: 'ag_ui_event', data: event as ArtVerseAgUiEvent }),
    error: (err) => {
      if (!controller.signal.aborted) {
        onEvent({ type: 'error', data: { detail: streamErrorMessage(err, 'Story chat resume disconnected'), requestId } });
      }
    },
  });
  controller.signal.addEventListener('abort', () => subscription.unsubscribe());
  return controller;
}

async function consumeMangaAgentEventResponse(
  response: Response,
  requestId: string | undefined,
  onEvent: (event: MangaAgentRunEvent) => void,
  onEventId?: (eventId: number) => void,
): Promise<void> {
  void requestId;
  const reader = response.body?.getReader();
  if (!reader) throw new Error('Agent stream is unavailable');
  const decoder = new TextDecoder();
  let buffer = '';
  let currentEvent = 'message';
  const handleLine = (line: string) => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith(':')) return;
    if (trimmed.startsWith('id:')) {
      const parsed = Number(trimmed.slice(3).trim());
      if (Number.isSafeInteger(parsed) && parsed >= 0) onEventId?.(parsed);
      return;
    }
    if (trimmed.startsWith('event:')) {
      currentEvent = trimmed.slice(6).trim();
      return;
    }
    if (!trimmed.startsWith('data:')) return;

    const dataStr = trimmed.slice(5).trim();
    try {
      const data = JSON.parse(dataStr);
      if (isAgUiEventPayload(data) && (currentEvent === 'message' || currentEvent === 'ag_ui_event')) {
        onEvent({ type: 'ag_ui_event', data });
      } else if (currentEvent === 'status'
        || currentEvent === 'run_event'
        || currentEvent === 'tool'
        || currentEvent === 'user_input_requested'
        || currentEvent === 'done'
        || currentEvent === 'error') {
        onEvent({ type: currentEvent, data } as MangaAgentRunEvent);
      }
    } catch {
      // Ignore malformed stream chunks; the durable cursor remains unchanged.
    } finally {
      currentEvent = 'message';
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';
    for (const line of lines) handleLine(line);
  }
  if (buffer.trim()) handleLine(buffer);
}

function isAgUiEventPayload(value: unknown): value is ArtVerseAgUiEvent {
  if (!value || typeof value !== 'object') return false;
  const type = (value as { type?: unknown }).type;
  return typeof type === 'string' && Object.values(EventType).includes(type as EventType);
}

function createClientRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export async function getStoryChatRunArtifacts(
  chapterId: number,
  conversationId: string,
  requestId: string,
): Promise<MangaAgentArtifact[]> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/runs/${requestId}/artifacts`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return Array.isArray(data) ? data.map((item: any) => ({
    artifactId: String(item.artifact_id ?? item.artifactId ?? ''),
    requestId: String(item.request_id ?? item.requestId ?? requestId),
    type: String(item.type ?? ''),
    status: String(item.status ?? 'DRAFT') as MangaAgentArtifact['status'],
    schemaVersion: String(item.schema_version ?? item.schemaVersion ?? '1'),
    payload: (item.payload ?? {}) as Record<string, unknown>,
    evaluation: (item.evaluation ?? {}) as Record<string, unknown>,
    checksum: String(item.checksum ?? ''),
  })) : [];
}

export async function getOpenStoryChatConversationRun(
  chapterId: number,
  conversationId: string,
): Promise<MangaAgentRunSnapshot | null> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/runs/open`);
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  const data = await res.json();
  return data.run || null;
}

export async function cancelStoryChatConversationRun(
  chapterId: number,
  conversationId: string,
  requestId: string,
): Promise<MangaAgentRunSnapshot> {
  const res = await authFetch(`${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/runs/${requestId}/cancel`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error(parseApiError(await res.text()));
  return res.json();
}

export function replayStoryChatRunEvents(
  chapterId: number,
  conversationId: string,
  requestId: string,
  afterEventId: number,
  onEvent: (event: MangaAgentRunEvent) => void,
): AbortController {
  const controller = new AbortController();
  let cursor = Number.isSafeInteger(afterEventId) && afterEventId > 0 ? afterEventId : 0;

  const tail = async () => {
    while (!controller.signal.aborted) {
      try {
        const response = await authFetch(
          `${BASE}/api/chapters/${chapterId}/story-chat/conversations/${conversationId}/runs/${requestId}/events`,
          {
            headers: {
              Accept: 'text/event-stream',
              'Last-Event-ID': String(cursor),
            },
            signal: controller.signal,
          },
        );
        if (!response.ok) throw new Error(parseApiError(await response.text()));
        await consumeMangaAgentEventResponse(response, requestId, onEvent, (eventId) => { cursor = eventId; });
        return;
      } catch (error: any) {
        if (controller.signal.aborted || error?.name === 'AbortError') return;
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
      }
    }
  };
  void tail();
  return controller;
}

