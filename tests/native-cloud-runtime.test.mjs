import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';
import { DEFAULTS, deepMerge } from '../src/defaults.mjs';
import { ToolRegistry } from '../src/tools.mjs';
import { NativeCloudRuntime } from '../src/native-cloud-runtime.mjs';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
async function eventually(predicate, message = 'Condition did not become true') {
  for (let attempt = 0; attempt < 150; attempt++) { if (predicate()) return; await delay(5); }
  assert.fail(message);
}
const model = { id: 'gpt-6.1-sol', model: 'gpt-6.1-sol', displayName: 'GPT 6.1 Sol', isDefault: true,
  defaultReasoningEffort: 'medium', supportedReasoningEfforts: [{ reasoningEffort: 'medium' }, { reasoningEffort: 'high' }],
  serviceTiers: [{ id: 'priority', name: 'Fast' }], defaultServiceTier: null };

class FakeClient extends EventEmitter {
  constructor() {
    super(); this.calls = []; this.ready = false; this.connected = false; this.starts = 0; this.closes = 0; this.nextThread = 1; this.nextTurn = 1;
    this.account = { type: 'chatgpt', planType: 'plus', email: 'private-placeholder@example.invalid' };
    this.routingId = 'test-account'; this.quota = { accountId: 'test-account', ordinaryUsageAllowed: true, rateLimits: { primary: { usedPercent: 1, resetsAt: Math.floor(Date.now() / 1000) + 300 } } };
    this.catalog = [model]; this.threads = new Map(); this.active = null;
  }
  async start() { if (!this.ready) this.starts++; this.ready = this.connected = true; return {}; }
  setServerRequestHandler(handler) { this.handler = handler; }
  async request(method, params) {
    await this.start(); this.calls.push({ method, params });
    if (this.overrides?.[method]) return this.overrides[method](params);
    if (method === 'account/read') return { account: this.account, workspaceRouting: { chatgptAccountId: this.routingId } };
    if (method === 'account/rateLimits/read') { if (this.quotaError) throw this.quotaError; return this.quota; }
    if (method === 'model/list') return { data: this.catalog, nextCursor: null };
    if (method === 'thread/start') {
      const id = 'thread-' + this.nextThread++; this.threads.set(id, { id, turns: [] });
      return { thread: { id }, model: params.model, serviceTier: params.serviceTier, reasoningEffort: params.config.model_reasoning_effort };
    }
    if (method === 'thread/resume') {
      if (!this.threads.has(params.threadId)) throw Object.assign(new Error('Missing archive'), { rpcCode: -32602 });
      return { thread: { id: params.threadId }, model: params.model, serviceTier: params.serviceTier, reasoningEffort: params.config.model_reasoning_effort };
    }
    if (method === 'thread/read') return { thread: this.threads.get(params.threadId) };
    if (method === 'thread/turns/list') return { data: [...this.threads.get(params.threadId).turns].reverse(), nextCursor: null };
    if (method === 'thread/items/list') return { data: (this.threads.get(params.threadId).turns.find(turn => turn.id === params.turnId)?.items || []).map(item => ({ turnId: params.turnId, item })), nextCursor: null };
    if (method === 'turn/start') {
      const turn = { id: 'turn-' + this.nextTurn++, status: 'inProgress', items: [] };
      this.active = { threadId: params.threadId, turn };
      this.emit('notification', { method: 'turn/started', params: { threadId: params.threadId, turn } });
      if (this.onStart) return this.onStart(params, turn);
      return { turn };
    }
    if (method === 'turn/steer') {
      if (this.onSteer) return this.onSteer(params);
      return { turnId: this.active.turn.id };
    }
    if (method === 'turn/interrupt') { this.finish({ status: 'interrupted' }); return {}; }
    throw new Error('Unexpected native RPC: ' + method);
  }
  finish({ status = 'completed', error, items = [] } = {}) {
    if (!this.active) return;
    const { threadId, turn } = this.active, completed = { ...turn, status, error, items };
    const thread = this.threads.get(threadId);
    if (thread) { thread.turns = thread.turns.filter(item => item.id !== completed.id); thread.turns.push(completed); }
    this.emit('notification', { method: 'turn/completed', params: { threadId, turn: completed } });
    this.active = null;
  }
  notification(method, fields = {}) { this.emit('notification', { method, params: { threadId: this.active?.threadId, turnId: this.active?.turn.id, ...fields } }); }
  disconnect() { this.ready = this.connected = false; this.emit('disconnect', new Error('Private raw error must never be published')); }
  async close() { this.closes++; this.ready = this.connected = false; }
}

function fixture(t, { settings = {}, now = Date.now, client = new FakeClient() } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'hoyoung-native-runtime-'));
  const stores = [], runtimes = [];
  const browser = { contexts: new Map(), async close() {}, async operate(args) { return { requested: args.action, url: 'https://example.invalid/' }; } };
  const open = (newClient = client) => {
    const store = new Store(dir, { defaults: deepMerge(DEFAULTS, settings) }); stores.push(store);
    let runtime;
    const tools = new ToolRegistry({ store, browser, dataDir: dir, terminal: { close() {} },
      onSettings(patch, actor) { const result = store.updateSettings(patch, actor); runtime.settingsChanged(); return result; } });
    runtime = new NativeCloudRuntime({ store, tools, browser, client: newClient, dataDir: dir, now, tickIntervalMs: 60_000 });
    runtimes.push(runtime); return { runtime, store, tools, client: newClient };
  };
  t.after(async () => {
    for (const runtime of runtimes) await runtime.stop();
    for (const store of stores) { try { store.close(); } catch {} }
    rmSync(dir, { recursive: true, force: true });
  });
  return { ...open(), open, dir, browser };
}
const send = (runtime, text = '작업을 시작해.', extra = {}) => runtime.receive({ text, role: 'user', channel: 'web', ...extra });
const count = (client, method) => client.calls.filter(call => call.method === method).length;
const latest = (client, method) => client.calls.filter(call => call.method === method).at(-1);

test('native readiness is read-only and private account data never reaches app state or events', async t => {
  const { runtime, store, client } = fixture(t, { settings: { autonomy: { enabled: false } } });
  await runtime.start();
  assert.deepEqual(client.calls.map(call => call.method), ['account/read', 'account/rateLimits/read', 'model/list']);
  assert.deepEqual(latest(client, 'account/read').params, { refreshToken: false });
  assert.deepEqual(latest(client, 'account/rateLimits/read').params, { supportsLunaReserve: false, excludeResetCreditDetails: true });
  assert.equal(runtime.state().state, 'paused');
  assert.equal(runtime.state().model.actualEffort, 'medium');
  assert.equal(runtime.state().model.actualServiceTier, 'priority');
  const publicState = JSON.stringify({ state: runtime.state(), events: store.listEvents() });
  assert.equal(publicState.includes(client.account.email), false);
  assert.equal(publicState.includes(client.routingId), false);
  assert.equal(count(client, 'thread/start'), 0); assert.equal(count(client, 'turn/start'), 0);
});

test('API account is not silently used as subscription authentication', async t => {
  const { runtime, client } = fixture(t);
  client.account = { type: 'apiKey' };
  send(runtime); await runtime.start();
  assert.equal(runtime.state().model.code, 'CODEX_LOGIN_REQUIRED');
  assert.equal(count(client, 'account/rateLimits/read'), 0);
  assert.equal(count(client, 'model/list'), 0); assert.equal(count(client, 'turn/start'), 0);
});

test('private messages enqueue idempotently and caller signal cannot end a native cloud turn', async t => {
  const { runtime, store, client } = fixture(t);
  const phone = new AbortController();
  const original = send(runtime, '인스타 관리를 이어가.', { externalId: 'phone-request-1', signal: phone.signal, metadata: { authority: 'third_party' } });
  assert.equal(send(runtime, '재전송', { externalId: 'phone-request-1' }).id, original.id);
  await runtime.start();
  phone.abort('Phone disconnected');
  assert.equal(client.starts, 1); assert.equal(count(client, 'turn/start'), 1);
  assert.equal(count(client, 'turn/interrupt'), 0); assert.equal(store.listTasks().length, 1);
  assert.equal(store.getMessage(original.id).metadata.authority, 'owner');
  assert.equal(runtime.state().busy, true);
  assert.equal(store.listTasks()[0].state, 'running', 'start RPC ACK is not completion');
  const start = latest(client, 'thread/start').params;
  assert.equal(start.cwd, join(runtime.dataDir, 'workspace'));
  assert.equal(start.ephemeral, false); assert.equal(start.approvalPolicy, 'never'); assert.equal(start.sandbox, 'danger-full-access');
  assert.equal(start.config['memories.generate_memories'], false, 'native background inference remains separately controlled');
  assert.equal(start.config.model_reasoning_effort, 'medium');
  assert.ok(start.dynamicTools.some(tool => tool.name === 'app_browser' && tool.type === 'function' && tool.inputSchema));
  assert.equal(start.dynamicTools.some(tool => ['terminal', 'files', 'app_terminal', 'app_files'].includes(tool.name)), false, 'built-in native tools remain native');
  assert.equal(latest(client, 'turn/start').params.effort, 'medium');
  assert.equal(latest(client, 'turn/start').params.serviceTier, 'priority');
  client.finish({ items: [{ id: 'answer-1', type: 'agentMessage', text: '완료했어.', phase: 'final_answer' }] });
  await eventually(() => !runtime.state().busy);
  assert.equal(store.listTasks()[0].state, 'succeeded');
  assert.equal(store.listMessages().filter(message => message.role === 'assistant').length, 1);
});

test('native completion and message notifications before start ACK are retained and finish once', async t => {
  const { runtime, store, client } = fixture(t);
  const ack = deferred();
  client.onStart = (params, turn) => {
    client.notification('item/completed', { item: { id: 'early-item', type: 'agentMessage', text: '먼저 도착한 완료 답변', phase: 'final_answer' } });
    client.finish({ items: [{ id: 'early-item', type: 'agentMessage', text: '먼저 도착한 완료 답변', phase: 'final_answer' }] });
    return ack.promise.then(() => ({ turn }));
  };
  send(runtime); const starting = runtime.start();
  await eventually(() => count(client, 'turn/start') === 1);
  assert.equal(store.listTasks()[0].state, 'running');
  assert.equal(runtime.state().busy, true);
  ack.resolve(); await starting;
  assert.equal(store.listTasks()[0].state, 'succeeded');
  assert.equal(store.listMessages().filter(message => message.role === 'assistant').length, 1);
  assert.equal(count(client, 'turn/start'), 1);
});

test('owner steering reaches active turn without restarting engine and completion waits for steering ACK', async t => {
  const { runtime, store, client } = fixture(t);
  send(runtime, '먼저 보고서를 만들어.'); await runtime.start();
  const originalTurn = runtime.active.turnId, ack = deferred();
  client.onSteer = () => ack.promise.then(() => ({ turnId: originalTurn }));
  const steering = send(runtime, '그 보고서에 사진도 넣어.', { externalId: 'steer-1' });
  await eventually(() => count(client, 'turn/steer') === 1);
  const steerCall = latest(client, 'turn/steer').params;
  assert.equal(steerCall.expectedTurnId, originalTurn);
  assert.equal(steerCall.clientUserMessageId, steering.id);
  assert.equal(count(client, 'turn/interrupt'), 0);
  client.finish();
  await delay(5);
  assert.equal(runtime.state().busy, true, 'pending steer cannot accidentally become a fresh duplicate turn');
  ack.resolve();
  await eventually(() => !runtime.state().busy);
  assert.ok(store.listTasks().every(task => task.state === 'succeeded'));
  assert.equal(count(client, 'turn/start'), 1);
  assert.equal(client.starts, 1);
});

test('explicit unsupported steering preserves message for next turn; unknown steering delivery pauses without replay', async t => {
  const { runtime, store, client } = fixture(t);
  send(runtime); await runtime.start();
  client.onSteer = () => { throw Object.assign(new Error('not steerable'), { codexErrorInfo: 'activeTurnNotSteerable' }); };
  const next = send(runtime, '검토가 끝나면 이 변경도 해.');
  await eventually(() => count(client, 'turn/steer') === 1);
  await eventually(() => store.listTasks().find(task => task.metadata.sourceMessageIds?.includes(next.id)).state === 'queued');
  client.finish(); await eventually(() => !runtime.state().busy);
  await runtime.tick();
  assert.equal(count(client, 'turn/start'), 2);
  client.onSteer = () => { throw Object.assign(new Error('unknown network outcome'), { code: 'CODEX_RPC_TIMEOUT' }); };
  const uncertain = send(runtime, '저장까지 해.');
  await eventually(() => runtime.state().state === 'reconciliation_required');
  assert.equal(store.listTasks().find(task => task.metadata.sourceMessageIds?.includes(uncertain.id)).state, 'paused');
  await runtime.tick(); await runtime.tick();
  assert.equal(count(client, 'turn/start'), 2);
});

test('conversation changes choose new threads while client, persistent native home and host identity stay fixed', async t => {
  const { runtime, store, client } = fixture(t);
  send(runtime, '첫 대화 작업', { threadId: 'conversation-a' }); await runtime.start();
  const worker = runtime.state().continuity.workerId;
  client.finish(); await eventually(() => !runtime.state().busy);
  send(runtime, '둘째 대화 작업', { threadId: 'conversation-b' });
  await eventually(() => count(client, 'turn/start') === 2);
  client.finish(); await eventually(() => !runtime.state().busy);
  send(runtime, '첫 대화 계속', { threadId: 'conversation-a' });
  await eventually(() => count(client, 'turn/start') === 3);
  assert.equal(client.starts, 1);
  assert.equal(count(client, 'thread/start'), 2); assert.equal(count(client, 'thread/resume'), 1);
  assert.equal(latest(client, 'turn/start').params.threadId, 'thread-1');
  assert.equal(runtime.state().continuity.workerId, worker);
  assert.equal(runtime.state().continuity.phoneConnectionRequired, false);
  assert.equal(runtime.state().continuity.conversationChangesReplaceHost, false);
  assert.equal(store.getRuntimeState('codex-native-runtime:v1').threads['conversation-b'], 'thread-2');
});

test('heartbeat does not overlap active work and resumes on fixed host without a phone', async t => {
  let now = Date.now();
  const { runtime, store, client } = fixture(t, { now: () => now });
  await runtime.start();
  assert.equal(count(client, 'turn/start'), 1);
  now += 180_000; await runtime.tick(); await runtime.tick();
  assert.equal(count(client, 'turn/start'), 1); assert.equal(store.listTasks().length, 1);
  client.finish(); await eventually(() => !runtime.state().busy);
  now += 60_000; await runtime.tick();
  assert.equal(count(client, 'turn/start'), 2); assert.equal(client.starts, 1);
  assert.equal(count(client, 'thread/start'), 1); assert.equal(count(client, 'thread/resume'), 1);
});

test('known quota blocks all turn dispatch, incoming owner messages get local notice, null is not recovery', async t => {
  let now = Date.now();
  const { runtime, store, client } = fixture(t, { now: () => now });
  client.quota = { accountId: 'test-account', ordinaryUsageAllowed: false, rateLimits: { primary: { usedPercent: 100, resetsAt: Math.floor(now / 1000) - 1 } } };
  const first = send(runtime, '한도 복구 후 이어서 해.', { externalId: 'quota-owner-1' });
  await runtime.start();
  assert.equal(runtime.state().quota.exhausted, true); assert.equal(count(client, 'turn/start'), 0);
  send(runtime, '추가 지시야.', { externalId: 'quota-owner-2' });
  send(runtime, '중복 지시야.', { externalId: 'quota-owner-2' });
  assert.equal(store.listMessages().filter(message => message.metadata.quotaNotice).length, 2);
  now += 120_000;
  client.quota = { accountId: 'test-account', ordinaryUsageAllowed: null, rateLimits: { primary: { usedPercent: 0, resetsAt: Math.floor(now / 1000) - 1 } } };
  await runtime.tick();
  assert.equal(count(client, 'turn/start'), 0); assert.equal(runtime.state().quota.exhausted, true);
  assert.equal(store.listEvents().filter(event => event.kind === 'quota.recovered').length, 0);
  const quotaReads = count(client, 'account/rateLimits/read'); await runtime.tick(); await runtime.tick();
  assert.equal(count(client, 'account/rateLimits/read'), quotaReads, 'unknown quota rechecks are bounded');
  client.quota = { accountId: 'test-account', ordinaryUsageAllowed: true, rateLimits: { primary: { usedPercent: 0, resetsAt: Math.floor(now / 1000) + 100 } } };
  runtime.control('check-quota'); await eventually(() => !runtime.state().quota.exhausted);
  await runtime.tick();
  assert.equal(count(client, 'turn/start'), 1); assert.equal(store.getMessage(first.id).text, '한도 복구 후 이어서 해.');
});

test('quota identity mismatch or unavailable usage RPC never unblocks a previously exhausted account', async t => {
  const { runtime, store, client } = fixture(t, { settings: { autonomy: { enabled: false } } });
  store.setRuntimeState('quota', { exhausted: true, detectedAt: Date.now() - 10000, retryAt: 0 });
  runtime.status.quota = store.getRuntimeState('quota');
  client.quota = { accountId: 'different-account', ordinaryUsageAllowed: true, rateLimits: { primary: { usedPercent: 0 } } };
  await runtime.start();
  assert.equal(runtime.state().quota.exhausted, true);
  client.quotaError = Object.assign(new Error('Private upstream raw error'), { code: 'CODEX_RPC_ERROR' });
  await runtime.refreshHealth();
  assert.equal(runtime.state().quota.exhausted, true); assert.equal(runtime.state().model.quotaRecoveryVerified, false);
  assert.equal(count(client, 'turn/start'), 0);
  assert.equal(JSON.stringify(runtime.state()).includes('Private upstream raw error'), false);
});

test('typed native quota completion keeps queued work; a quota failure after side effects is paused', async t => {
  const { runtime, store, client } = fixture(t);
  send(runtime); await runtime.start();
  client.finish({ status: 'failed', error: { codexErrorInfo: 'usageLimitExceeded', message: 'Private details' } });
  await eventually(() => !runtime.state().busy);
  assert.equal(store.listTasks()[0].state, 'queued'); assert.equal(runtime.state().quota.exhausted, true);
  client.quota.ordinaryUsageAllowed = true;
  runtime.control('check-quota'); await eventually(() => !runtime.state().quota.exhausted); await runtime.tick();
  client.notification('item/started', { item: { id: 'native-command', type: 'commandExecution' } });
  client.finish({ status: 'failed', error: { codexErrorInfo: 'rateLimitExceeded' } });
  await eventually(() => !runtime.state().busy);
  assert.equal(store.listTasks()[0].state, 'paused');
  assert.equal(store.listTasks()[0].metadata.nativeReconciliationRequired, true);
});

test('disconnect and reboot do not replay unknown native side effects; completed history reconciles once', async t => {
  const { runtime, store, client, open } = fixture(t);
  send(runtime, '외부 계정 변경 작업'); await runtime.start();
  const worker = runtime.state().continuity.workerId, task = store.listTasks()[0], active = client.active;
  client.notification('item/started', { item: { id: 'change-command', type: 'commandExecution' } });
  client.disconnect();
  assert.equal(store.getTask(task.id).state, 'paused'); assert.equal(runtime.state().state, 'reconciliation_required');
  await runtime.tick(); assert.equal(count(client, 'turn/start'), 1);
  await runtime.stop();
  const replacement = new FakeClient(); replacement.threads = client.threads;
  replacement.threads.get(active.threadId).turns = [{ ...active.turn, status: 'inProgress', items: [] }];
  const restarted = open(replacement); await restarted.runtime.start();
  assert.equal(restarted.runtime.state().continuity.workerId, worker);
  assert.equal(restarted.runtime.state().state, 'reconciliation_required'); assert.equal(count(replacement, 'turn/start'), 0);
  replacement.threads.get(active.threadId).turns = [{ ...active.turn, status: 'completed', items: [{ type: 'agentMessage', id: 'reconciled-answer', text: '서버 기록에서 완료를 확인했어.', phase: 'final_answer' }] }];
  assert.equal(await restarted.runtime.reconcile(), true);
  assert.equal(store.getTask(task.id).state, 'succeeded');
  assert.equal(count(replacement, 'turn/start'), 0, 'history reconciliation must not execute side effects again');
  assert.equal(await restarted.runtime.reconcile(), true);
  assert.equal(store.listMessages().filter(message => message.text === '서버 기록에서 완료를 확인했어.').length, 1);
});

test('dynamic app memory uses real owner sources, rejects forged requests and deduplicates actions', async t => {
  const { runtime, store, client } = fixture(t);
  const source = send(runtime, '나는 답변이 짧은 게 좋아.'); await runtime.start();
  const params = { threadId: runtime.active.threadId, turnId: runtime.active.turnId, callId: 'save-preference', namespace: null,
    tool: 'app_memory', arguments: { action: 'save', key: 'preference.length', content: '짧은 답변을 선호', kind: 'fact', confidence: 0.9, sourceMessageIds: [source.id] } };
  const first = await client.handler('item/tool/call', params);
  assert.equal(first.success, true); assert.equal(first.contentItems[0].type, 'inputText');
  assert.deepEqual(await client.handler('item/tool/call', params), first);
  assert.equal(store.listMemories({ query: 'preference.length' }).length, 1);
  assert.deepEqual(store.listMemories({ query: 'preference.length' })[0].sourceMessageIds, [source.id]);
  assert.equal((await client.handler('item/tool/call', { ...params, turnId: 'forged-turn' })).success, false);
  const assistant = store.addMessage({ channel: 'web', role: 'assistant', text: '내가 제안한 정보' });
  const unsourced = await client.handler('item/tool/call', { ...params, callId: 'invalid-sources', arguments: { ...params.arguments, key: 'owner.unsourced', sourceMessageIds: [assistant.id, 'does-not-exist'] } });
  assert.equal(unsourced.success, true);
  const memory = store.listMemories({ query: 'owner.unsourced' })[0];
  assert.equal(memory.kind, 'inference'); assert.ok(memory.confidence <= 0.4); assert.deepEqual(memory.sourceMessageIds, []);
  assert.equal(store.listActions().length, 2);
});

test('dynamic tool arriving before turn-start notification adopts actual native turn without dropping the call', async t => {
  const { runtime, store, client } = fixture(t);
  client.overrides = { 'turn/start': async params => {
    client.active = { threadId: params.threadId, turn: { id: 'pre-ack-turn', status: 'inProgress', items: [] } };
    const tool = await client.handler('item/tool/call', { threadId: params.threadId, turnId: 'pre-ack-turn', callId: 'early-message', namespace: null,
      tool: 'app_messages', arguments: { text: '백그라운드에서 작업을 시작했어.' } });
    assert.equal(tool.success, true);
    return { turn: client.active.turn };
  } };
  send(runtime); await runtime.start();
  assert.equal(runtime.active.turnId, 'pre-ack-turn');
  assert.equal(store.listMessages().filter(message => message.role === 'assistant').length, 1);
  assert.equal(count(client, 'turn/start'), 1);
});

test('external device login restarts an idle auth cache but defers restart during native work', async t => {
  const { runtime, client } = fixture(t, { settings: { autonomy: { enabled: false } } });
  client.account = null; await runtime.start();
  assert.equal(runtime.state().model.ready, false); assert.equal(client.starts, 1);
  client.account = { type: 'chatgpt', planType: 'plus' };
  const idle = await runtime.authenticationChanged();
  assert.equal(idle.deferred, false); assert.equal(runtime.state().model.ready, true);
  assert.equal(client.starts, 2); assert.equal(client.closes, 1);
  runtime.store.updateSettings({ autonomy: { enabled: true } }); runtime.settingsChanged();
  send(runtime); await eventually(() => runtime.active?.turnId);
  const worker = runtime.state().continuity.workerId;
  const busy = await runtime.authenticationChanged();
  assert.equal(busy.deferred, true); assert.equal(client.closes, 1); assert.equal(count(client, 'turn/interrupt'), 0);
  client.finish(); await eventually(() => !runtime.state().busy); await runtime.tick();
  assert.equal(client.closes, 2); assert.equal(client.starts, 3);
  assert.equal(runtime.state().continuity.workerId, worker);
});

test('permission revocation interrupts native work and blocks unsupported restricted native tool profiles', async t => {
  const { runtime, store, client } = fixture(t);
  send(runtime); await runtime.start();
  store.updateSettings({ permissions: { capabilities: { browser: false } } }); runtime.settingsChanged();
  await eventually(() => count(client, 'turn/interrupt') === 1);
  await eventually(() => !runtime.state().busy);
  send(runtime, '새 작업'); await runtime.tick();
  assert.equal(count(client, 'turn/start'), 1);
  assert.equal(runtime.state().model.code, 'NATIVE_PERMISSION_CONFIGURATION');
});

test('native agent settings update confirms saved instructions without aborting its own turn', async t => {
  const { runtime, store, client } = fixture(t);
  send(runtime, '말투를 바꿔.'); await runtime.start();
  const result = await client.handler('item/tool/call', { threadId: runtime.active.threadId, turnId: runtime.active.turnId, callId: 'new-instructions', namespace: null,
    tool: 'app_settings', arguments: { action: 'update', patch: { persona: { instructions: '짧고 정확하게 말해.' } } } });
  assert.equal(result.success, true); assert.equal(store.getSettings().persona.instructions, '짧고 정확하게 말해.');
  assert.equal(count(client, 'turn/interrupt'), 0); assert.equal(runtime.state().busy, true);
});

test('settings or owner task changes during native thread setup never dispatch stale permissions or create false uncertain work', async t => {
  const { runtime, store, client } = fixture(t);
  const threadAck = deferred();
  client.overrides = { 'thread/start': async params => {
    await threadAck.promise;
    const id = 'delayed-thread'; client.threads.set(id, { id, turns: [] });
    return { thread: { id }, model: params.model, serviceTier: params.serviceTier, reasoningEffort: params.config.model_reasoning_effort };
  } };
  send(runtime); const starting = runtime.start();
  await eventually(() => count(client, 'thread/start') === 1);
  const task = store.listTasks()[0];
  store.updateSettings({ persona: { instructions: '새 지침' } }); runtime.settingsChanged();
  store.steerTask(task.id, { state: 'paused', description: '주인이 수정한 설명' });
  await runtime.taskChanged(task.id);
  threadAck.resolve(); await starting;
  assert.equal(count(client, 'turn/start'), 0);
  assert.equal(store.getTask(task.id).state, 'paused');
  assert.equal(store.getTask(task.id).description, '주인이 수정한 설명');
  assert.equal(runtime.state().continuity.reconciliationRequired, false);
  assert.equal(store.getRuntimeState('codex-native-runtime:v1').active, null);
});

test('owner editing an unrelated task does not cancel the active native turn', async t => {
  const { runtime, store, client } = fixture(t);
  send(runtime); await runtime.start();
  const unrelated = store.addTask({ title: '나중에 할 일', description: '현재 작업과 다름' });
  store.steerTask(unrelated.id, { state: 'paused' }); await runtime.taskChanged(unrelated.id);
  assert.equal(count(client, 'turn/interrupt'), 0);
  const activeTask = runtime.active.task.id;
  store.steerTask(activeTask, { state: 'paused' }); await runtime.taskChanged(activeTask);
  await eventually(() => !runtime.state().busy);
  assert.equal(count(client, 'turn/interrupt'), 1);
  assert.equal(store.getTask(activeTask).state, 'paused');
});

test('native reconciliation pages recent turn summaries and only the required turn items, without hydrating lifetime history', async t => {
  const { runtime, store, client } = fixture(t);
  send(runtime); await runtime.start();
  const journal = store.getRuntimeState('codex-native-runtime:v1'), turnId = runtime.active.turnId;
  client.disconnect();
  const turn = { id: turnId, status: 'completed', items: [] };
  client.overrides = {
    'thread/read': async params => { assert.equal(params.includeTurns, false); return { thread: { id: journal.active.threadId } }; },
    'thread/turns/list': async params => {
      assert.equal(params.itemsView, 'summary'); assert.equal(params.limit, 100);
      return params.cursor ? { data: [turn], nextCursor: null } : { data: [{ id: 'newer-turn', status: 'completed' }], nextCursor: 'turn-page-2' };
    },
    'thread/items/list': async params => {
      assert.equal(params.turnId, turnId); assert.equal(params.limit, 100);
      return params.cursor ? { data: [{ turnId, item: { id: 'paged-answer-2', type: 'agentMessage', text: '나머지 기록' } }], nextCursor: null }
        : { data: [{ turnId, item: { id: 'paged-answer-1', type: 'agentMessage', text: '첫 기록' } }], nextCursor: 'item-page-2' };
    },
  };
  assert.equal(await runtime.reconcile(), true);
  assert.equal(count(client, 'thread/turns/list'), 2); assert.equal(count(client, 'thread/items/list'), 2);
  assert.equal(count(client, 'turn/start'), 1);
  assert.equal(store.getTask(journal.active.taskId).state, 'succeeded');
  assert.equal(store.listMessages().filter(message => /^native:item:/.test(store.listOutbound().find(out => out.receipt?.messageId === message.id)?.idempotencyKey || '')).length, 2);
});

test('native thread ACK must verify requested model, Fast and medium before starting any turn', async t => {
  const { runtime, store, client } = fixture(t);
  client.overrides = { 'thread/start': async params => {
    client.threads.set('wrong-tier-thread', { id: 'wrong-tier-thread', turns: [] });
    return { thread: { id: 'wrong-tier-thread' }, model: params.model, serviceTier: 'default', reasoningEffort: 'high' };
  } };
  send(runtime); await runtime.start();
  assert.equal(count(client, 'turn/start'), 0);
  assert.equal(runtime.state().model.code, 'MODEL_PREFERENCE_UNVERIFIED');
  assert.equal(runtime.state().model.authenticated, true);
  assert.equal(store.listTasks()[0].state, 'queued');
  assert.equal(runtime.state().continuity.reconciliationRequired, false);
});

test('native terminal event cannot finish before an already accepted dynamic app tool commits its journal', async t => {
  const { runtime, store, client, browser } = fixture(t);
  send(runtime); await runtime.start();
  const browserAck = deferred(); browser.operate = () => browserAck.promise;
  const call = client.handler('item/tool/call', { threadId: runtime.active.threadId, turnId: runtime.active.turnId, callId: 'pending-browser', namespace: null,
    tool: 'app_browser', arguments: { action: 'read' } });
  await eventually(() => store.listActions()[0]?.state === 'running');
  client.finish(); await delay(5);
  assert.equal(runtime.state().busy, true); assert.equal(store.listTasks()[0].state, 'running');
  browserAck.resolve({ read: true }); assert.equal((await call).success, true);
  await eventually(() => !runtime.state().busy);
  assert.equal(store.listActions()[0].state, 'succeeded'); assert.equal(store.listTasks()[0].state, 'succeeded');
});

test('reconciled interrupted native history releases the worker while keeping that task paused rather than replaying it', async t => {
  const { runtime, store, client } = fixture(t);
  send(runtime); await runtime.start();
  const task = runtime.active.task, native = client.active;
  client.disconnect();
  client.threads.get(native.threadId).turns = [{ ...native.turn, status: 'interrupted', items: [] }];
  assert.equal(await runtime.reconcile(), true);
  assert.equal(runtime.state().continuity.reconciliationRequired, false);
  assert.equal(store.getTask(task.id).state, 'paused');
  assert.equal(store.getTask(task.id).metadata.nativeStatus, 'interrupted');
  assert.equal(count(client, 'turn/start'), 1);
});

test('confirmed quota exhaustion is reported even when preferred-model discovery fails', async t => {
  const { runtime, store, client } = fixture(t);
  client.catalog = [];
  client.quota = { accountId: 'test-account', ordinaryUsageAllowed: false, rateLimits: { primary: { usedPercent: 100 } } };
  send(runtime); await runtime.start();
  assert.equal(runtime.state().quota.exhausted, true);
  assert.equal(runtime.state().model.authenticated, true);
  assert.equal(count(client, 'turn/start'), 0);
  assert.equal(store.listMessages().filter(message => message.metadata.quotaNotice).length, 1);
});

test('owner instruction accepted during an autonomous turn grants owner authority to its app setting change', async t => {
  const { runtime, store, client } = fixture(t);
  await runtime.start();
  assert.equal(runtime.active.task.metadata.authority, 'autonomous');
  send(runtime, '운영 예산을 월 2만원으로 바꿔.');
  await eventually(() => runtime.active?.acceptedTasks.length === 1);
  const result = await client.handler('item/tool/call', { threadId: runtime.active.threadId, turnId: runtime.active.turnId, callId: 'owner-budget', namespace: null,
    tool: 'app_settings', arguments: { action: 'update', patch: { budget: { mode: 'limited', maxMonthlyCost: 20000, allowPaidProvisioning: false } } } });
  assert.equal(result.success, true); assert.equal(store.getSettings().budget.maxMonthlyCost, 20000);
  assert.equal(count(client, 'turn/interrupt'), 0);
});
