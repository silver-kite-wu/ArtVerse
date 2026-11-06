import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, authFetch, loginUser, parseApiError } from '../src/api/auth.ts';

test('CSRF rejection has an actionable Chinese message', () => {
  assert.equal(
    parseApiError(JSON.stringify({ code: 40303, message: 'CSRF verification failed', data: null })),
    '请求来源校验失败，请联系管理员检查站点来源配置。',
  );
  assert.equal(parseApiError('{"code":40304,"message":"密码登录已锁定","data":null}'), '密码登录已锁定');
  assert.equal(parseApiError('服务暂不可用'), '服务暂不可用');
});

test('login retains CSRF error code and status without retrying or refreshing', async (context) => {
  const fetchMock = context.mock.method(globalThis, 'fetch', async (_input: RequestInfo | URL, init?: RequestInit) => {
    assert.equal(new Headers(init?.headers).get('X-ArtVerse-Client'), 'web');
    assert.equal(init?.credentials, 'same-origin');
    return Response.json({ code: 40303, message: 'CSRF verification failed', data: null }, { status: 403 });
  });

  await assert.rejects(loginUser('test@example.test', 'unused'), (error: unknown) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.code, 40303);
    assert.equal(error.status, 403);
    assert.match(error.message, /请求来源校验失败/);
    return true;
  });
  assert.equal(fetchMock.mock.callCount(), 1);
});

test('authenticated requests do not refresh or retry on CSRF rejection', async (context) => {
  const fetchMock = context.mock.method(globalThis, 'fetch', async () =>
    Response.json({ code: 40303, message: 'CSRF verification failed', data: null }, { status: 403 }));

  const response = await authFetch('/api/stories', { method: 'POST' });

  assert.equal(response.status, 403);
  assert.equal(fetchMock.mock.callCount(), 1);
});
