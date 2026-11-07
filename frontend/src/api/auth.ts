export const BASE = '';

const LS_REFRESH_TOKEN = 'artverse.refreshToken';
const LS_USER = 'artverse.user';
export const LS_LLM_API_KEY = 'lorevista.llmApiKey';
export const LS_IMAGE_API_KEY = 'lorevista.imageApiKey';
export const LS_WORKFLOW_API_KEY = 'lorevista.workflowApiKey';
export const LS_LEGACY_DEEPSEEK_API_KEY = 'lorevista.deepseekApiKey';
export const LS_LEGACY_COZE_API_KEY = 'lorevista.cozeApiKey';
export const LS_PROVIDER_SETTINGS = 'lorevista.apiProviderSettings.v3';
export const LS_PROVIDER_SETTINGS_LEGACY = 'lorevista.apiProviderSettings.v2';
export interface UserInfo {
  id: number;
  username: string;
  email: string;
  avatar_url?: string | null;
}

export interface ChallengeConfig {
  enabled: boolean;
  provider: string;
  siteKey: string;
  registrationRequired: boolean;
}

interface ChallengeConfigResponse {
  enabled?: boolean;
  provider?: string;
  site_key?: string;
  registration_required?: boolean;
}

export class ApiError extends Error {
  code?: number;
  status?: number;
  data?: Record<string, unknown>;

  constructor(message: string, options?: { code?: number; status?: number; data?: Record<string, unknown> }) {
    super(message);
    this.name = 'ApiError';
    this.code = options?.code;
    this.status = options?.status;
    this.data = options?.data;
  }
}

// unwrapApiData/readApiData 是前端与后端 JSON 之间唯一的未类型化边界：
// 后端 data 字段形状未知，此处显式保留 any 语义，由各 API 函数在返回前收窄为具体类型。
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function unwrapApiData<T = any>(payload: T): any {
  if (payload && typeof payload === 'object') {
    const envelope = payload as { code?: unknown; message?: unknown; data?: unknown };
    if (typeof envelope.code === 'number' && typeof envelope.message === 'string' && 'data' in envelope) {
      return envelope.data;
    }
  }
  return payload;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export async function readApiData<T = any>(response: Response): Promise<T> {
  return unwrapApiData(await response.json()) as T;
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === 'string') return error;
  if (error && typeof error === 'object' && 'message' in error) {
    return String((error as { message: unknown }).message);
  }
  return error == null ? '' : String(error);
}

let currentUser: UserInfo | null = null;
const userListeners = new Set<() => void>();
export function subscribeUser(listener: () => void): () => void {
  userListeners.add(listener);
  return () => { userListeners.delete(listener); };
}
export function cacheUser(user: UserInfo | null): void {
  currentUser = user;
  userListeners.forEach(listener => listener());
}
let sessionBootstrapPromise: Promise<boolean> | null = null;

export function getUser(): UserInfo | null {
  return currentUser;
}

export function isAuthenticated(): boolean {
  return currentUser !== null;
}

export function clearAuth(): void {
  cacheUser(null);
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
        const res = await fetch(`${BASE}/api/auth/refresh`, {
          method: 'POST',
          credentials: 'same-origin',
          headers: requestHeaders('POST'),
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
  cacheUser(await readApiData<UserInfo>(res));
}

export async function getChallengeConfig(): Promise<ChallengeConfig> {
  const res = await fetch(`${BASE}/api/auth/challenge/config`, {
    credentials: 'same-origin',
  });
  if (!res.ok) throw await toApiError(res);
  const payload = await readApiData<ChallengeConfigResponse>(res);
  return {
    enabled: Boolean(payload.enabled),
    provider: payload.provider ?? '',
    siteKey: payload.site_key ?? '',
    registrationRequired: Boolean(payload.registration_required),
  };
}

export async function getCaptchaImage(): Promise<{ captchaId: string; image: string }> {
  const res = await fetch(`${BASE}/api/auth/captcha/image`, {
    credentials: 'same-origin',
  });
  if (!res.ok) throw await toApiError(res);
  const payload = await readApiData(res);
  return { captchaId: payload.captcha_id, image: payload.image };
}

export async function getMailConfig(): Promise<{ enabled: boolean }> {
  const res = await fetch(`${BASE}/api/mail/config`, {
    credentials: 'same-origin',
  });
  if (!res.ok) throw await toApiError(res);
  const enabled = await readApiData<boolean>(res);
  return { enabled };
}

export async function loginUser(email: string, password: string, challengeToken?: string): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: requestHeaders('POST', true),
    credentials: 'same-origin',
    body: JSON.stringify({ email, password, challenge_token: challengeToken }),
  });
  if (!res.ok) throw await toApiError(res);
  await fetchAndCacheUser();
  clearLegacyAuthStorage();
  clearAppDataStorage();
}

export async function registerUser(
  username: string,
  email: string,
  password: string,
  challengeToken?: string,
  emailCode?: string,
): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/register`, {
    method: 'POST',
    headers: requestHeaders('POST', true),
    credentials: 'same-origin',
    body: JSON.stringify({ username, email, password, challenge_token: challengeToken, email_code: emailCode }),
  });
  if (!res.ok) throw await toApiError(res);
  await fetchAndCacheUser();
  clearLegacyAuthStorage();
  clearAppDataStorage();
}

export type EmailCodeScenario = 'register' | 'login' | 'reset_password';

export async function sendEmailCode(email: string, scenario: EmailCodeScenario): Promise<void> {
  const res = await fetch(`${BASE}/api/mail/send-code`, {
    method: 'POST',
    headers: requestHeaders('POST', true),
    credentials: 'same-origin',
    body: JSON.stringify({ email, scenario }),
  });
  if (!res.ok) throw await toApiError(res);
}

export async function loginByCode(email: string, code: string): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/login-by-code`, {
    method: 'POST',
    headers: requestHeaders('POST', true),
    credentials: 'same-origin',
    body: JSON.stringify({ email, code }),
  });
  if (!res.ok) throw await toApiError(res);
  await fetchAndCacheUser();
  clearLegacyAuthStorage();
  clearAppDataStorage();
}

/** 找回密码第一步：校验验证码，成功返回一次性重置票据 */
export async function verifyResetCode(email: string, code: string): Promise<string> {
  const res = await fetch(`${BASE}/api/auth/password/verify-code`, {
    method: 'POST',
    headers: requestHeaders('POST', true),
    credentials: 'same-origin',
    body: JSON.stringify({ email, code }),
  });
  if (!res.ok) throw await toApiError(res);
  const payload = await readApiData(res);
  const ticket = typeof payload?.reset_ticket === 'string' ? payload.reset_ticket : '';
  if (!ticket) throw new Error('验证码校验响应缺少重置凭证');
  return ticket;
}

/** 找回密码第二步：凭一次性票据重置密码 */
export async function resetPassword(resetTicket: string, newPassword: string): Promise<void> {
  const res = await fetch(`${BASE}/api/auth/password/reset`, {
    method: 'POST',
    headers: requestHeaders('POST', true),
    credentials: 'same-origin',
    body: JSON.stringify({ reset_ticket: resetTicket, new_password: newPassword }),
  });
  if (!res.ok) throw await toApiError(res);
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

export function apiHeaders(json = false, method = 'GET'): Record<string, string> {
  const headers: Record<string, string> = {};
  if (json) headers['Content-Type'] = 'application/json';
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method.toUpperCase())) {
    headers['X-ArtVerse-Client'] = 'web';
  }
  return headers;
}

export async function authFetch(input: RequestInfo, init?: RequestInit): Promise<Response> {
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

function parseApiErrorResponse(text: string): { message: string; code?: number } {
  try {
    const data = JSON.parse(text);
    if (data?.code === 40303) {
      return { message: '请求来源校验失败，请联系管理员检查站点来源配置。', code: data.code };
    }
    if (typeof data?.message === 'string') {
      return { message: data.message, code: typeof data?.code === 'number' ? data.code : undefined };
    }
    if (typeof data?.detail === 'string') {
      return { message: data.detail, code: typeof data?.code === 'number' ? data.code : undefined };
    }
    if (typeof data?.error === 'string') {
      return { message: data.error, code: typeof data?.code === 'number' ? data.code : undefined };
    }
  } catch {
    // Plain text response.
  }
  return { message: text || 'Request failed' };
}

export function parseApiError(text: string): string {
  return parseApiErrorResponse(text).message;
}

export async function refreshAuthSession(): Promise<boolean> {
  const refreshed = await tryRefreshToken();
  if (!refreshed) notifyAuthExpired();
  return refreshed;
}

async function toApiError(response: Response): Promise<ApiError> {
  const text = await response.text();
  const parsed = parseApiErrorResponse(text);
  return new ApiError(parsed.message, { code: parsed.code, status: response.status });
}

