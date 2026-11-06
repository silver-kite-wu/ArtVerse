import test from 'node:test';
import assert from 'node:assert/strict';
import { applyStoryChatActivity, restoreStoryChatActivity, type StoryChatActivities } from '../src/components/storyChatActivityState.ts';
import type { MangaAgentRunEvent, MangaAgentRunSnapshot } from '../src/api/agentRuns.ts';

const event = (type: string, data = {}, at = '2026-11-04T13:00:00Z'): MangaAgentRunEvent => ({
  type: 'run_event', data: { type, createdAt: at, ...data },
});

test('creative proposals display preparation and actual request labels', () => {
  const names = {
    draft_story_settings: '准备作品设定修改', draft_character_profile: '准备人物资料修改',
    draft_knowledge_change: '追加待审知识候选', list_generation_models: '查看可用生图模型',
    preview_manga_generation: '预览漫画生图范围', propose_manga_generation: '准备生图审核提案',
    get_generation_request: '查看图片请求实际状态', propose_generation_recovery: '准备图片恢复审核提案',
  };
  let state: StoryChatActivities = {};
  for (const name of Object.keys(names)) state = applyStoryChatActivity(state, 'creative-run', event('tool_started', { toolName: name, data: { toolCallId: name } }));
  assert.deepEqual(state['creative-run'].steps.map(step => step.label), Object.values(names));
});
test('one tool call stays one row across prepare, start, result and replay', () => {
  let state: StoryChatActivities = {};
  const call = { toolName: 'get_novel_context', data: { toolCallId: 'call-1' } };
  for (const type of ['tool_call_started', 'tool_call_ready', 'tool_started', 'tool_finished']) {
    state = applyStoryChatActivity(state, 'run-1', event(type, { ...call, status: type === 'tool_finished' ? 'success' : 'running' }));
  }
  state = applyStoryChatActivity(state, 'run-1', event('tool_finished', { ...call, status: 'success' }));
  assert.equal(state['run-1'].steps.length, 1);
  assert.equal(state['run-1'].steps[0].status, 'succeeded');
});

test('failed tool remains failed when its audit arrives before framework start', () => {
  const call = { toolName: 'draft_novel_content', data: { toolCallId: 'draft-1' } };
  let state = applyStoryChatActivity({}, 'run', event('tool_call_started', call));
  state = applyStoryChatActivity(state, 'run', { type: 'tool', data: { tool: 'draft_novel_content', succeeded: false, error: '正文版本已变化' } });
  state = applyStoryChatActivity(state, 'run', event('tool_started', call));
  assert.equal(state.run.steps[0].status, 'failed');
  assert.equal(state.run.steps[0].detail, '正文版本已变化');
});

test('HITL waits, accepts the actual review result, and continues the same run', () => {
  let state = applyStoryChatActivity({}, 'run', event('tool_call_started', { toolName: 'ask_user', data: { toolCallId: 'ask-1' } }));
  state = applyStoryChatActivity(state, 'run', { type: 'user_input_requested', data: { question: '审阅修改', options: [] } });
  assert.equal(state.run.status, 'waiting');
  state = applyStoryChatActivity(state, 'run', event('review_resumed', { label: '人工审核完成，继续总结' }, '2026-11-04T13:00:05Z'));
  assert.equal(state.run.status, 'running');
  assert.equal(state.run.steps[0].status, 'succeeded');
  state = applyStoryChatActivity(state, 'run', { type: 'done', data: { reply: '已保存并总结' } }, Date.parse('2026-11-04T13:00:08Z'));
  assert.equal(state.run.status, 'succeeded');
});

test('failure retains earlier successful steps and exposes the actual error', () => {
  let state = applyStoryChatActivity({}, 'run', event('tool_finished', { toolName: 'get_novel_context', status: 'success', data: { toolCallId: 'read' } }));
  state = applyStoryChatActivity(state, 'run', event('model_started', {}, '2026-11-04T13:00:01Z'));
  state = applyStoryChatActivity(state, 'run', { type: 'error', data: { detail: '模型服务拒绝请求（HTTP 400）' } });
  assert.equal(state.run.steps[0].status, 'succeeded');
  assert.equal(state.run.steps[1].status, 'failed');
  assert.match(state.run.error || '', /HTTP 400/);
});

test('durable history restores tools without reverting more recent live state', () => {
  const read = event('tool_finished', { toolName: 'get_novel_context', status: 'success', data: { toolCallId: 'read' } });
  const run = { requestId: 'run', status: 'WAITING_USER', updatedAt: '2026-11-04T13:00:02Z', events: [
    { eventId: 1, eventName: 'run_event', data: read.data, createdAt: '2026-11-04T13:00:00Z' },
  ] } as MangaAgentRunSnapshot;
  let state = restoreStoryChatActivity({}, run);
  assert.equal(state.run.steps[0].toolName, 'get_novel_context');
  assert.equal(state.run.status, 'waiting');
  state = applyStoryChatActivity(state, 'run', { type: 'done', data: {} }, Date.parse('2026-11-04T13:00:10Z'));
  state = restoreStoryChatActivity(state, run);
  assert.equal(state.run.status, 'succeeded');
});

test('retrying a saved review clears the previous summary error while retaining its history', () => {
  let state = applyStoryChatActivity({}, 'run', event('model_started'));
  state = applyStoryChatActivity(state, 'run', { type: 'error', data: { detail: '模型服务暂时不可用' } });
  state = applyStoryChatActivity(state, 'run', event('review_resumed', { label: '继续总结' }, '2026-11-04T13:01:00Z'));
  state = applyStoryChatActivity(state, 'run', { type: 'done', data: { reply: '已保存并完成总结' } });
  assert.equal(state.run.status, 'succeeded');
  assert.equal(state.run.error, undefined);
  assert.equal(state.run.steps[0].status, 'failed');
});

test('post-completion history merges server-only save events even when the live finish arrived later', () => {
  let state = applyStoryChatActivity({}, 'run', { type: 'done', data: { reply: '已保存' } }, Date.parse('2026-11-04T13:01:00Z'));
  const run = { requestId: 'run', status: 'SUCCEEDED', updatedAt: '2026-11-04T13:00:59Z', events: [
    { eventId: 8, eventName: 'run_event', data: { type: 'review_saved', label: '人工审核已确认，正文已保存' }, createdAt: '2026-11-04T13:00:50Z' },
  ] } as MangaAgentRunSnapshot;
  state = restoreStoryChatActivity(state, run);
  assert.equal(state.run.status, 'succeeded');
  assert.equal(state.run.steps[0].id, 'review_saved');
});
