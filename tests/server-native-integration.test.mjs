import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import http from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createApplication } from '../src/server.mjs';
import { NativeCloudRuntime } from '../src/native-cloud-runtime.mjs';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const deferred = () => {
  let resolve;
  const promise = new Promise(yes => { resolve = yes; });
  return { promise, resolve };
};
async function eventually(predicate, description) {
  for (let attempt = 0; attempt < 200; attempt++) {
    if (predicate()) return;
    await pause(5);
  }
  assert.fail(description || 'Expected native integration state did not appear');
}

/** Only fake official RPC: no subprocess, authentication or model generation. */
class NativeClient extends EventEmitter {
  constructor() {
    super();
    this.calls = [];
    this.starts = 0;
    this.closes = 0;
    this.ready = this.connected = false;
    this.account = { type: 'chatgpt', planType: 'plus', email: 'private@example.invalid' };
    this.threads = new Map();
    this.turnSequence = 0;
  }
  async start() {
    if (!this.ready) this.starts++;
    this.ready = this.connected = true;
    return {};
  }
  setServerRequestHandler(handler) { this.handler = handler; return this; }
  async request(method, params) {
    await this.start();
    this.calls.push({ method, params });
    if (method === 'account/read') return { account: this.account, workspaceRouting: { chatgptAccountId: 'private-account' } };
    if (method === 'account/rateLimits/read') return { accountId: 'private-account', ordinaryUsageAllowed: true,
      rateLimits: { primary: { usedPercent: 2, resetsAt: Math.floor(Date.now() / 1000) + 3600 } } };
    if (method === 'model/list') return { data: [{ id: 'gpt-6.1-sol', model: 'gpt-6.1-sol', displayName: 'GPT 6.1 Sol',
      isDefault: true, defaultReasoningEffort: 'medium', supportedReasoningEfforts: [{ reasoningEffort: 'medium' }],
      serviceTiers: [{ id: 'priority', name: 'Fast' }], defaultServiceTier: null }], nextCursor: null };
    if (method === 'thread/start') {
      const id = 'thread-' + (this.threads.size + 1);
      this.threads.set(id, { id, turns: [] });
      return this.threadResponse(id, params);
    }
    if (method === 'thread/resume') {
      if (!this.threads.has(params.threadId)) throw Object.assign(new Error('Missing fake archive'), { rpcCode: -32602 });
      return this.threadResponse(params.threadId, params);
    }
    if (method === 'thread/read') return { thread: this.threads.get(params.threadId) };
    if (method === 'turn/start') {
      const turn = { id: 'turn-' + (++this.turnSequence), status: 'inProgress', items: [] };
      this.active = { threadId: params.threadId, turn };
      this.emit('notification', { method: 'turn/started', params: { threadId: params.threadId, turn } });
      return this.onTurnStart ? this.onTurnStart(params, turn) : { turn };
    }
    if (method === 'turn/steer') return { turnId: this.active.turn.id };
    if (method === 'turn/interrupt') { this.finish('interrupted'); return {}; }
    throw new Error('Unexpected fake native method: ' + method);
  }
  threadResponse(id, params) {
    return { thread: { id }, model: params.model, serviceTier: params.serviceTier,
      reasoningEffort: params.config.model_reasoning_effort };
  }
  finish(status = 'completed', text = 'Cloud 작업을 완료했어.') {
    if (!this.active) return;
    const { threadId, turn } = this.active;
    const completed = { ...turn, status, items: status === 'completed'
      ? [{ type: 'agentMessage', id: 'answer-' + turn.id, text, phase: 'final_answer' }] : [] };
    this.threads.get(threadId).turns.push(completed);
    this.emit('notification', { method: 'turn/completed', params: { threadId, turn: completed } });
    this.active = null;
  }
  async close() { this.closes++; this.ready = this.connected = false; }
}

const count = (client, method) => client.calls.filter(call => call.method === method).length;
async function fixture(t, { enabled = true } = {}) {
  const dataDir = await mkdtemp(path.join(tmpdir(), 'hoyoung-server-native-'));
  const client = new NativeClient();
  const loginManager = {
    state: { state: 'idle', authenticated: false },
    status() { return { ...this.state }; },
    cancel() {},
    start() { throw new Error('Integration tests must never start real device authentication'); },
  };
  const browser = { contexts: new Map(), async close() {},
    async operate() { throw new Error('No real browser work is permitted in HTTP integration tests'); } };
  const app = await createApplication({ engine: 'codex-native', nativeClient: client, dataDir,
    token: '', seed: false, browser, loginManager });
  app.store.updateSettings({ autonomy: { enabled } }, 'test');
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  const url = 'http://127.0.0.1:' + app.server.address().port;
  t.after(async () => {
    client.releaseRequests?.();
    try { await app.close(); } finally { await rm(dataDir, { recursive: true, force: true }); }
  });
  const request = async (route, { method = 'GET', body } = {}) => {
    const response = await fetch(url + route, { method,
      ...(body === undefined ? {} : { headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }) });
    return { status: response.status, body: await response.json() };
  };
  return { app, client, loginManager, dataDir, url, request };
}

function submitAndDisconnect(url, message) {
  return new Promise((resolve, reject) => {
    const bytes = JSON.stringify(message);
    let accepted = false;
    const request = http.request(url + '/api/chat', { method: 'POST', headers: {
      'content-type': 'application/json', 'content-length': Buffer.byteLength(bytes), connection: 'close',
    } }, response => {
      accepted = true;
      const status = response.statusCode;
      response.destroy();
      request.destroy();
      resolve(status);
    });
    request.on('error', error => { if (!accepted) reject(error); });
    request.end(bytes);
  });
}

test('accepted HTTP input survives phone disconnect and native start ACK is never task completion', async t => {
  const { app, client, url, request } = await fixture(t);
  assert.ok(app.runtime instanceof NativeCloudRuntime);
  const acknowledgement = deferred();
  client.onTurnStart = (params, turn) => acknowledgement.promise.then(() => ({ turn }));
  client.releaseRequests = acknowledgement.resolve;
  assert.equal(await submitAndDisconnect(url, { message: '폰 연결이 끊겨도 인스타 작업을 계속해.', clientMessageId: 'offline-1' }), 202);
  const starting = app.runtime.start();
  await eventually(() => count(client, 'turn/start') === 1, 'Cloud turn must start after the phone socket closes');
  const pending = await request('/api/state');
  assert.equal(pending.status, 200);
  assert.equal(pending.body.engine, 'codex-native');
  assert.equal(pending.body.tasks[0].state, 'running');
  assert.equal(pending.body.status.busy, true);
  assert.equal(count(client, 'turn/interrupt'), 0);
  acknowledgement.resolve();
  await starting;
  assert.equal(app.store.listTasks()[0].state, 'running');
  assert.equal(count(client, 'turn/interrupt'), 0);
  client.finish();
  await eventually(() => app.store.listTasks()[0].state === 'succeeded');
  assert.equal(app.store.listMessages().filter(message => message.role === 'assistant').length, 1);
});

test('a mismatched native turn ACK pauses accepted HTTP work and retries never duplicate dispatch', async t => {
  const { app, client, request } = await fixture(t);
  client.onTurnStart = () => ({ turn: { id: 'different-native-turn', status: 'completed' } });
  const input = { message: '실제 실행 기록으로 완료를 확인해.', clientMessageId: 'wrong-ack-1' };
  assert.equal((await request('/api/chat', { method: 'POST', body: input })).status, 202);
  await app.runtime.start();
  const state = await request('/api/state');
  assert.equal(state.body.status.state, 'reconciliation_required');
  assert.equal(state.body.tasks[0].state, 'paused');
  assert.equal(state.body.status.continuity.reconciliationRequired, true);
  const retry = await request('/api/chat', { method: 'POST', body: input });
  assert.equal(retry.body.duplicate, true);
  await app.runtime.tick();
  assert.equal(count(client, 'turn/start'), 1);
  assert.equal(app.store.listTasks().length, 1);
  assert.equal(app.store.listEvents().some(event => event.type === 'task.completed'), false);
});

test('HTTP conversation changes reuse the same worker, client and workspace while retries stay idempotent', async t => {
  const { app, client, dataDir, request } = await fixture(t);
  const first = { message: '첫 대화의 Cloud 작업', conversationId: 'chat-a', clientMessageId: 'message-a-1' };
  const accepted = await request('/api/chat', { method: 'POST', body: first });
  await app.runtime.start();
  const worker = app.runtime.state().continuity.workerId;
  const retry = await request('/api/chat', { method: 'POST', body: first });
  assert.equal(retry.status, 202);
  assert.equal(retry.body.duplicate, true);
  assert.equal(retry.body.message.id, accepted.body.message.id);
  assert.equal(app.store.listTasks().length, 1);
  assert.equal(count(client, 'turn/start'), 1);
  assert.equal(count(client, 'turn/steer'), 0);
  client.finish();
  await eventually(() => !app.runtime.state().busy);
  await request('/api/chat', { method: 'POST', body: { message: '둘째 대화의 Cloud 작업', conversationId: 'chat-b', clientMessageId: 'message-b-1' } });
  await eventually(() => count(client, 'turn/start') === 2);
  client.finish();
  await eventually(() => !app.runtime.state().busy);
  await request('/api/chat', { method: 'POST', body: { message: '첫 대화 이어서 작업', conversationId: 'chat-a', clientMessageId: 'message-a-2' } });
  await eventually(() => count(client, 'turn/start') === 3);
  const nativeThreads = client.calls.filter(call => ['thread/start', 'thread/resume'].includes(call.method));
  assert.equal(count(client, 'thread/start'), 2);
  assert.equal(count(client, 'thread/resume'), 1);
  assert.equal(nativeThreads.at(-1).params.threadId, 'thread-1');
  assert.ok(nativeThreads.every(call => call.params.cwd === path.join(dataDir, 'workspace')));
  assert.equal(client.starts, 1);
  assert.equal(app.runtime.client, client);
  assert.equal(app.runtime.state().continuity.workerId, worker);
  assert.equal(app.runtime.state().continuity.conversationChangesReplaceHost, false);
  assert.equal(app.runtime.state().continuity.phoneConnectionRequired, false);
  assert.equal(count(client, 'turn/interrupt'), 0);
  client.finish();
  await eventually(() => !app.runtime.state().busy);
  assert.equal(app.store.listTasks().filter(task => task.state === 'succeeded').length, 3);
});

test('an explicit owner HTTP task pause interrupts its native turn exactly once', async t => {
  const { app, client, request } = await fixture(t);
  await request('/api/chat', { method: 'POST', body: { message: '명시적으로 중단하기 전까지 작업해.', clientMessageId: 'pause-task-1' } });
  await app.runtime.start();
  const active = app.runtime.active;
  const result = await request('/api/tasks/' + active.task.id, { method: 'PATCH', body: { state: 'paused' } });
  assert.equal(result.status, 200);
  await eventually(() => !app.runtime.state().busy);
  const interrupts = client.calls.filter(call => call.method === 'turn/interrupt');
  assert.equal(interrupts.length, 1);
  assert.deepEqual(interrupts[0].params, { threadId: active.threadId, turnId: active.turnId });
  assert.equal(app.store.getTask(active.task.id).state, 'paused');
  assert.equal(app.store.getTask(active.task.id).metadata.nativeStatus, 'interrupted');
  assert.equal(count(client, 'turn/start'), 1);
});

test('completed official device login reloads the idle native auth cache on the same worker and client', async t => {
  const { app, client, loginManager, request } = await fixture(t, { enabled: false });
  client.account = null;
  await app.runtime.start();
  assert.equal(app.runtime.state().model.authenticated, false);
  const worker = app.runtime.state().continuity.workerId;
  client.account = { type: 'chatgpt', planType: 'plus', email: 'new-private@example.invalid' };
  loginManager.state = { state: 'completed', authenticated: false, needsHealthCheck: true };
  const result = await request('/api/model/login');
  assert.equal(result.status, 200);
  assert.equal(result.body.authenticated, true);
  assert.equal(result.body.needsHealthCheck, false);
  assert.equal(app.runtime.state().model.authenticated, true);
  assert.equal(app.runtime.client, client);
  assert.equal(app.runtime.state().continuity.workerId, worker);
  assert.equal(client.starts, 2);
  assert.equal(client.closes, 1);
  assert.equal(count(client, 'turn/start'), 0);
  const publicState = await request('/api/state');
  assert.equal(JSON.stringify(publicState.body).includes('new-private@example.invalid'), false);
  assert.equal(JSON.stringify(publicState.body).includes('private-account'), false);
});

test('device login completion stays deferred during active Cloud work and cannot interrupt it', async t => {
  const { app, client, loginManager, request } = await fixture(t);
  await request('/api/chat', { method: 'POST', body: { message: '인증 상태 조회 중에도 이 작업을 유지해.', clientMessageId: 'auth-active-1' } });
  await app.runtime.start();
  const worker = app.runtime.state().continuity.workerId;
  const nativeTurn = app.runtime.active.turnId;
  loginManager.state = { state: 'completed', authenticated: false, needsHealthCheck: true };
  const deferredLogin = await request('/api/model/login');
  assert.equal(deferredLogin.status, 200);
  assert.equal(deferredLogin.body.needsHealthCheck, true);
  assert.equal(client.closes, 0);
  assert.equal(client.starts, 1);
  assert.equal(count(client, 'turn/interrupt'), 0);
  assert.equal(app.runtime.active.turnId, nativeTurn);
  assert.equal(app.store.listTasks()[0].state, 'running');
  client.finish();
  await eventually(() => !app.runtime.state().busy);
  const idleLogin = await request('/api/model/login');
  assert.equal(idleLogin.body.needsHealthCheck, false);
  assert.equal(idleLogin.body.authenticated, true);
  assert.ok(client.closes >= 1);
  assert.equal(app.runtime.client, client);
  assert.equal(app.runtime.state().continuity.workerId, worker);
  assert.equal(app.store.listTasks()[0].state, 'succeeded');
  assert.equal(count(client, 'turn/interrupt'), 0);
  assert.equal(count(client, 'turn/start'), 1);
});
