import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApplication } from '../src/server.mjs';
import { AssistantRuntime } from '../src/runtime.mjs';

const token = 'test-owner-token';
async function fixture(t, { loginManager, authToken = token } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'assistant-server-'));
  const applications = [];
  const browserCalls = [];
  const open = async ({ seed = false } = {}) => {
    const browser = { contexts: new Map(), async close() {}, async operate(args) { browserCalls.push(args); return { url: args.url || 'https://example.com/', title: 'Test page', screenshot: '/api/screenshots/general.png' }; } };
    const app = await createApplication({ dataDir: dir, token: authToken, browser, loginManager, seed, runtimeFactory(options) {
      return new AssistantRuntime({ ...options, modelFactory: () => ({ async health() { return { ready: true }; }, async respond() { throw new Error('HTTP tests must not call a model'); } }) });
    } });
    await new Promise((resolve) => app.server.listen(0, '127.0.0.1', resolve));
    const url = `http://127.0.0.1:${app.server.address().port}`;
    applications.push(app);
    const request = async (route, { auth = true, method = 'GET', body, headers = {} } = {}) => {
      const response = await fetch(url + route, { method, headers: { ...(auth && authToken ? { authorization: `Bearer ${authToken}` } : {}), ...(body ? { 'content-type': 'application/json' } : {}), ...headers }, ...(body ? { body: JSON.stringify(body) } : {}) });
      const payload = await response.json();
      return { response, body: payload };
    };
    return { app, url, request };
  };
  t.after(async () => {
    for (const app of applications) { try { await app.close(); } catch {} }
    rmSync(dir, { recursive: true, force: true });
  });
  return { ...await open(), open, browserCalls };
}

test('HTTP health is public while state, chat, settings and browser controls require owner authentication', async (t) => {
  const { request, app, browserCalls } = await fixture(t);
  const health = await request('/api/health', { auth: false });
  assert.equal(health.response.status, 200);
  assert.equal(health.body.ok, true);
  for (const [path, method, body] of [['/api/state', 'GET'], ['/api/settings', 'PATCH', { persona: { instructions: 'unauthorized' } }], ['/api/chat', 'POST', { message: 'unauthorized' }], ['/api/browser', 'POST', { action: 'open', url: 'https://example.com/' }]]) {
    const result = await request(path, { method, body, auth: false });
    assert.equal(result.response.status, 401);
    assert.equal(result.body.code, 'AUTH_REQUIRED');
  }
  assert.equal(app.store.listMessages().length, 0);
  assert.equal(browserCalls.length, 0);
  assert.notEqual(app.store.getSettings().persona.instructions, 'unauthorized');
});

test('session login accepts the owner token and cross-origin mutations are rejected', async (t) => {
  const { request, url } = await fixture(t);
  assert.equal((await request('/api/session', { auth: false, method: 'POST', body: { token: 'wrong' } })).response.status, 401);
  const login = await request('/api/session', { auth: false, method: 'POST', body: { token } });
  assert.equal(login.response.status, 200);
  const setCookie = login.response.headers.get('set-cookie');
  assert.match(setCookie, /HttpOnly/);
  assert.match(setCookie, /SameSite=Strict/);
  const cookie = setCookie.split(';')[0];
  const state = await request('/api/state', { auth: false, headers: { cookie } });
  assert.equal(state.response.status, 200);
  const denied = await request('/api/settings', { method: 'PATCH', body: { persona: { instructions: 'cross-origin' } }, headers: { origin: 'https://other.example' } });
  assert.equal(denied.response.status, 403);
  const allowed = await request('/api/settings', { auth: false, method: 'PATCH', body: { persona: { instructions: 'same origin' } }, headers: { cookie, origin: url } });
  assert.equal(allowed.response.status, 200);
});

test('private local app opens and accepts app chat without an app account or subscription login', async (t) => {
  const loginManager = { starts: 0, status() { return { state: 'idle', authenticated: false }; }, cancel() {}, start() { this.starts++; throw new Error('App access must not start model authentication'); } };
  const { request, url, app, open } = await fixture(t, { authToken: '', loginManager });
  const page = await fetch(url);
  await page.text();
  assert.equal(page.status, 200);
  const state = await request('/api/state', { auth: false });
  assert.equal(state.response.status, 200);
  assert.equal(state.body.settings.channels.default, 'web');
  assert.equal(state.body.settings.channels.instagram.enabled, false);
  assert.equal(state.body.settings.channels.instagram.observeInbox, false);
  assert.equal(state.body.settings.channels.instagram.markReadOnReceive, false);
  assert.equal(state.body.settings.model.ephemeral, false, 'Codex local transcripts are retained by default');
  assert.deepEqual(state.body.settings.model.preferences, { preferredModel: 'gpt-6.1-sol', reasoningEffort: 'medium', speed: 'fast' });
  const chat = await request('/api/chat', { auth: false, method: 'POST', body: { message: '앱에서 하는 내 대화를 기억해줘.' } });
  assert.equal(chat.response.status, 202);
  assert.equal(chat.body.message.channel, 'web');
  assert.equal(chat.body.message.metadata.authority, 'owner');
  assert.equal(loginManager.starts, 0, 'subscription authentication is independent of app access');
  const denied = await request('/api/chat', { auth: false, method: 'POST', body: { message: 'cross-origin' }, headers: { origin: 'https://other.example' } });
  assert.equal(denied.response.status, 403);
  assert.equal(app.store.listMessages().length, 1);
  await app.close();
  const restarted = await open();
  const restored = await restarted.request('/api/state', { auth: false });
  assert.equal(restored.response.status, 200);
  assert.equal(restored.body.messages[0].id, chat.body.message.id);
  assert.equal(restored.body.settings.channels.default, 'web');
});

test('settings use real validated durable persistence, and rejected patches leave history unchanged', async (t) => {
  const { request, app, open } = await fixture(t);
  const patch = { persona: { instructions: '새 맞춤 지침 원문' }, permissions: { agentCanChange: false, capabilities: { terminal: false } }, autonomy: { heartbeatSeconds: 120 }, model: { preferences: { preferredModel: 'gpt-6.1-sol', reasoningEffort: 'max', speed: 'fast' } } };
  const saved = await request('/api/settings', { method: 'PATCH', body: patch });
  assert.equal(saved.response.status, 200);
  assert.equal(saved.body.settings.persona.instructions, patch.persona.instructions);
  assert.equal(saved.body.settings.permissions.capabilities.terminal, false);
  assert.equal(saved.body.settings.permissions.capabilities.browser, true, 'partial permission update retains the other capabilities');
  const historyCount = app.store.listSettingsHistory().length;
  for (const bad of [{ autonomy: { heartbeatSeconds: 0 } }, { permissions: { capabilities: { files: 'yes' } } }, { channels: { instagram: { enabled: true, ownerUsername: '', ownerThreadUrl: '' } } }, { model: { provider: 'invented-model' } }, { model: { ephemeral: 'yes' } }]) {
    assert.equal((await request('/api/settings', { method: 'PATCH', body: bad })).response.status, 400);
  }
  assert.equal(app.store.listSettingsHistory().length, historyCount);
  await app.close();
  const restarted = await open();
  const state = await restarted.request('/api/state');
  assert.equal(state.body.settings.persona.instructions, patch.persona.instructions);
  assert.equal(state.body.settings.permissions.agentCanChange, false);
  assert.equal(state.body.settings.autonomy.heartbeatSeconds, 120);
  assert.deepEqual(state.body.settings.model.preferences, patch.model.preferences);
});

test('web chat stores owner authority and pause/resume persist the execution setting', async (t) => {
  const { request, app } = await fixture(t);
  const chat = await request('/api/chat', { method: 'POST', body: { message: '지금 작업을 바꿔줘.' } });
  assert.equal(chat.response.status, 202);
  assert.equal(chat.body.message.metadata.authority, 'owner');
  assert.equal(app.store.listTasks()[0].metadata.authority, 'owner');
  assert.equal(app.store.listTasks()[0].priority, 100);
  const paused = await request('/api/control', { method: 'POST', body: { action: 'pause' } });
  assert.equal(paused.response.status, 200);
  assert.equal(app.store.getSettings().autonomy.enabled, false);
  const resumed = await request('/api/control', { method: 'POST', body: { action: 'resume' } });
  assert.equal(resumed.response.status, 200);
  assert.equal(app.store.getSettings().autonomy.enabled, true);
});

test('app retry after a lost acknowledgement reuses a durable message and task after reopen', async (t) => {
  const { request, app, open } = await fixture(t);
  const input={message:'Cloud에서 이어서 작업해줘.',clientMessageId:'mobile-message-1',conversationId:'main'};
  const first=await request('/api/chat',{method:'POST',body:input});
  assert.equal(first.response.status,202);
  const retry=await request('/api/chat',{method:'POST',body:input});
  assert.equal(retry.response.status,202);
  assert.equal(retry.body.duplicate,true);
  assert.equal(retry.body.message.id,first.body.message.id);
  assert.equal(app.store.listMessages().length,1);
  assert.equal(app.store.listTasks().length,1);
  await app.close();
  const reopened=await open();
  const afterRestart=await reopened.request('/api/chat',{method:'POST',body:input});
  assert.equal(afterRestart.body.duplicate,true);
  assert.equal(afterRestart.body.message.id,first.body.message.id);
  assert.equal(reopened.app.store.listTasks().length,1);
  for(const conflict of [{...input,message:'다른 작업'},{...input,conversationId:'different'}]){
    const rejected=await reopened.request('/api/chat',{method:'POST',body:conflict});
    assert.equal(rejected.response.status,409);
    assert.equal(rejected.body.code,'MESSAGE_ID_CONFLICT');
  }
  assert.equal(reopened.app.store.listMessages().length,1);
  assert.equal(reopened.app.store.listTasks().length,1);
});

test('new app conversation IDs preserve workspace state and cannot select an execution host', async (t) => {
  const { request, app } = await fixture(t);
  const input={message:'프로젝트 이어가기',clientMessageId:'message-a',conversationId:'conversation-a'};
  await request('/api/chat',{method:'POST',body:input});
  await request('/api/chat',{method:'POST',body:{...input,clientMessageId:'message-b',conversationId:'conversation-b',workerUrl:'https://other.example'}});
  assert.deepEqual(new Set(app.store.listMessages().map(message=>message.threadId)),new Set(['conversation-a','conversation-b']));
  assert.deepEqual(new Set(app.store.listTasks().map(task=>task.metadata.threadId)),new Set(['conversation-a','conversation-b']));
  assert.equal(app.store.getSettings().model.endpoint,'http://127.0.0.1:17841');
  for(const patch of [{clientMessageId:'invalid id'},{conversationId:'invalid/id'}]){
    const rejected=await request('/api/chat',{method:'POST',body:{...input,...patch}});
    assert.equal(rejected.response.status,400);
  }
  assert.equal(app.store.listTasks().length,2);
});

test('manual memory corrections retain evidence, old versions, and durable edits; forgetting erases all versions', async (t) => {
  const { request, app, open } = await fixture(t);
  const created = await request('/api/memories', { method: 'POST', body: { key: 'preference.coffee', content: '커피를 좋아한다.', kind: 'fact' } });
  assert.equal(created.response.status, 201);
  const original = created.body.memory;
  assert.equal(original.version, 1);
  assert.equal(original.sourceMessageIds.length, 1);
  assert.equal(app.store.getMessage(original.sourceMessageIds[0]).text, '커피를 좋아한다.');
  const corrected = await request('/api/memories/' + original.id, { method: 'PATCH', body: { content: '이제 커피를 끊었다.', confidence: 1, kind: 'fact' } });
  assert.equal(corrected.response.status, 200);
  assert.equal(corrected.body.memory.version, 2);
  assert.equal(corrected.body.memory.supersedesId, original.id);
  assert.equal(app.store.getMemory(original.id).state, 'superseded');
  assert.ok(corrected.body.memory.sourceMessageIds.some((id) => app.store.getMessage(id)?.text === '이제 커피를 끊었다.'), 'a manual correction needs its own raw owner evidence');
  await app.close();
  const restarted = await open();
  const current = restarted.app.store.listMemories()[0];
  assert.equal(current.content, '이제 커피를 끊었다.');
  assert.equal(current.version, 2);
  assert.equal(restarted.app.store.listMemories({ includeArchived: true }).length, 2);
  const deleted = await restarted.request('/api/memories/' + current.id, { method: 'DELETE' });
  assert.equal(deleted.response.status, 200);
  assert.equal(deleted.body.result.memories, 2);
  assert.equal(restarted.app.store.listMemories({ includeArchived: true }).length, 0);
  assert.equal(restarted.app.store.listMessages().length, 0);
});

test('browser controls enforce the current permission toggle before any browser operation', async (t) => {
  const { request, browserCalls } = await fixture(t);
  const open = await request('/api/browser', { method: 'POST', body: { action: 'open', owner: 'general', url: 'https://example.com/' } });
  assert.equal(open.response.status, 200);
  assert.equal(browserCalls.length, 1);
  await request('/api/settings', { method: 'PATCH', body: { permissions: { capabilities: { browser: false } } } });
  const denied = await request('/api/browser', { method: 'POST', body: { action: 'open', url: 'https://example.com/' } });
  assert.equal(denied.response.status, 400);
  assert.equal(browserCalls.length, 1);
});

test('model login requires owner authentication, pauses autonomy, exposes only public code/status, and cancels', async (t) => {
  const loginManager = {
    privateCredential: 'fixture-private-credential',
    rawOutput: 'fixture-private-process-output',
    state: { state: 'idle', authenticated: false, verificationUrl: null, userCode: null, errorCode: null },
    starts: [], statusCalls: 0, cancelCalls: 0,
    status() { this.statusCalls++; return structuredClone(this.state); },
    start(options) {
      this.starts.push(options);
      this.state = { state: 'awaiting_authorization', authenticated: false, verificationUrl: 'https://auth.openai.com/codex/device', userCode: 'ABCD-EFGH', expiresAt: '2026-10-12T00:15:00.000Z', errorCode: null };
      return this.status();
    },
    cancel() {
      this.cancelCalls++;
      this.state = { state: 'cancelled', authenticated: false, verificationUrl: null, userCode: null, errorCode: null };
      return this.status();
    },
  };
  const { request, app, browserCalls } = await fixture(t, { loginManager });
  await request('/api/settings', { method: 'PATCH', body: { autonomy: { enabled: true } } });
  for (const method of ['POST', 'GET', 'DELETE']) {
    const denied = await request('/api/model/login', { method, auth: false });
    assert.equal(denied.response.status, 401);
    assert.equal(denied.body.code, 'AUTH_REQUIRED');
  }
  assert.equal(loginManager.starts.length, 0);
  assert.equal(loginManager.statusCalls, 0);
  assert.equal(loginManager.cancelCalls, 0);
  assert.equal(app.store.getSettings().autonomy.enabled, true, 'unauthenticated login cannot alter execution settings');
  const controller = new AbortController();
  app.runtime.currentAbort = controller;
  const started = await request('/api/model/login', { method: 'POST' });
  assert.equal(started.response.status, 202);
  assert.equal(app.store.getSettings().autonomy.enabled, false);
  assert.equal(controller.signal.aborted, true, 'login pause must also steer an active task');
  assert.deepEqual(loginManager.starts, [{ command: app.store.getSettings().model.command || 'codex' }]);
  assert.equal(started.body.state, 'awaiting_authorization');
  assert.equal(started.body.userCode, 'ABCD-EFGH');
  assert.equal(started.body.verificationUrl, 'https://auth.openai.com/codex/device');
  assert.equal(started.body.authenticated, false, 'displaying a login code is not authenticated completion');
  const observed = await request('/api/model/login');
  assert.equal(observed.response.status, 200);
  assert.deepEqual(observed.body, started.body);
  const state = await request('/api/state');
  assert.deepEqual(state.body.modelLogin, observed.body);
  for (const response of [started.body, observed.body, state.body.modelLogin]) {
    assert.equal(JSON.stringify(response).includes(loginManager.privateCredential), false);
    assert.equal(JSON.stringify(response).includes(loginManager.rawOutput), false);
    assert.equal(Object.hasOwn(response, 'starts'), false);
  }
  const cancelled = await request('/api/model/login', { method: 'DELETE' });
  assert.equal(cancelled.response.status, 200);
  assert.equal(cancelled.body.state, 'cancelled');
  assert.equal(cancelled.body.userCode, null);
  assert.equal(cancelled.body.verificationUrl, null);
  assert.equal(loginManager.cancelCalls, 1);
  assert.equal(app.store.getSettings().autonomy.enabled, false, 'cancelling login does not silently restart autonomous work');
  assert.equal(browserCalls.length, 0);
  assert.equal(app.store.listTasks().length, 0);
  await request('/api/settings', { method: 'PATCH', body: { permissions: { capabilities: { settings: false } } } });
  const blocked = await request('/api/model/login', { method: 'POST' });
  assert.equal(blocked.response.status, 400);
  assert.equal(loginManager.starts.length, 1, 'disabled model connection permission must prevent another login start');
});

test('owner chat-history preference is imported with its source and applied once across restarts', async (t) => {
  const { app, request, open } = await fixture(t);
  await request('/api/settings', { method: 'PATCH', body: { model: { url: 'https://chatgpt.com/c/old-saved-chat' } } });
  app.store.setRuntimeState('source-imported', true);
  app.store.setRuntimeState('latest-requirements-imported', true);
  await app.close();
  const migrated = await open({ seed: true });
  assert.equal(migrated.app.store.getSettings().model.url, 'https://chatgpt.com/?temporary-chat=true');
  const memory = migrated.app.store.listMemories({ key: 'owner-requirement:chatgpt_history' })[0];
  assert.match(memory.content, /Codex CLI의 로컬 대화 기록은 유지/);
  assert.match(memory.content, /일반 ChatGPT 채팅 목록은 별개/);
  const prior = migrated.app.store.getMemory(memory.supersedesId);
  assert.match(prior.content, /내 지피티에서 채팅은 안남았으면 좋겠는데/);
  assert.equal(prior.state, 'superseded');
  const source = migrated.app.store.getMessage(memory.sourceMessageIds[0]);
  assert.equal(source.text, memory.content);
  assert.match(source.externalId, /owner-single-user-app-2026-10-13/);
  const messageCount = migrated.app.store.listMessages().length;
  const memoryCount = migrated.app.store.listMemories({ includeArchived: true }).length;
  await migrated.request('/api/settings', { method: 'PATCH', body: { model: { url: 'https://chatgpt.com/?temporary-chat=true&model=owner-choice' } } });
  await migrated.app.close();
  const reopened = await open({ seed: true });
  assert.equal(reopened.app.store.getSettings().model.url, 'https://chatgpt.com/?temporary-chat=true&model=owner-choice');
  assert.equal(reopened.app.store.listMessages().length, messageCount);
  assert.equal(reopened.app.store.listMemories({ includeArchived: true }).length, memoryCount);
});

test('model preference revision survives app-scope migration and the prior DM-read requirement remains in memory history', async (t) => {
  const { app, request, open } = await fixture(t);
  await request('/api/settings', { method: 'PATCH', body: { model: { preferences: { reasoningEffort: 'max' } }, channels: { instagram: { markReadOnReceive: false } } } });
  // Model an existing installation whose original requirement import already ran.
  app.store.setRuntimeState('source-imported', true);
  app.store.setRuntimeState('latest-requirements-imported', true);
  const oldText = '모델은 가능한 GPT-6.1 Sol, Fast, 제공되는 최고 추론 수준을 사용한다.';
  const oldSource = app.store.addMessage({ channel: 'requirements-import', externalId: 'current-user-instructions-2026-10-12:model', role: 'user', text: oldText, metadata: { authority: 'historical_owner', acceptedRequirement: true } });
  const prior = app.store.upsertMemory({ key: 'owner-requirement:model', content: oldText, kind: 'fact', confidence: 1, sourceMessageIds: [oldSource.id] });
  await app.close();
  const migrated = await open({ seed: true });
  const state = await migrated.request('/api/state');
  assert.equal(state.body.settings.model.preferences.reasoningEffort, 'medium');
  assert.equal(state.body.settings.model.preferences.preferredModel, 'gpt-6.1-sol');
  assert.equal(state.body.settings.model.preferences.speed, 'fast');
  assert.equal(state.body.settings.channels.instagram.markReadOnReceive, false);
  const modelRequirement = migrated.app.store.listMemories({ key: 'owner-requirement:model' })[0];
  assert.equal(modelRequirement.version, 2);
  assert.equal(modelRequirement.supersedesId, prior.id);
  assert.equal(migrated.app.store.getMemory(prior.id).state, 'superseded');
  assert.match(modelRequirement.content, /중간\(medium\)/);
  const readRequirement = migrated.app.store.listMemories({ key: 'owner-requirement:dm_read' })[0];
  assert.match(readRequirement.content, /전용 앱.*개발 범위에서 제외/);
  const oldReadRequirement = migrated.app.store.getMemory(readRequirement.supersedesId);
  assert.match(oldReadRequirement.content, /모델 판단.*기다리지 않고/);
  assert.equal(oldReadRequirement.state, 'superseded');
  for (const memory of [modelRequirement, readRequirement]) {
    assert.equal(memory.kind, 'fact');
    assert.equal(memory.confidence, 1);
    assert.equal(memory.sourceMessageIds.length, 1);
    const source = migrated.app.store.getMessage(memory.sourceMessageIds[0]);
    assert.equal(source.text, memory.content);
    assert.match(source.externalId, memory === modelRequirement ? /owner-medium-and-dm-read-2026-10-12/ : /owner-single-user-app-2026-10-13/);
  }
  const memoryCount = migrated.app.store.listMemories({ includeArchived: true }).length;
  const messageCount = migrated.app.store.listMessages().length;
  await migrated.request('/api/settings', { method: 'PATCH', body: { model: { preferences: { reasoningEffort: 'high' } }, channels: { instagram: { markReadOnReceive: false } } } });
  await migrated.app.close();
  const reopened = await open({ seed: true });
  assert.equal(reopened.app.store.getSettings().model.preferences.reasoningEffort, 'high', 'reopening cannot override a subsequent manual setting');
  assert.equal(reopened.app.store.getSettings().channels.instagram.markReadOnReceive, false);
  assert.equal(reopened.app.store.listMemories({ includeArchived: true }).length, memoryCount);
  assert.equal(reopened.app.store.listMessages().length, messageCount);
  assert.equal(reopened.app.store.listMemories({ key: 'owner-requirement:model' })[0].version, 2);
});

test('single-user app revision supersedes DM scope once while retaining user memories, raw conversation, tasks and legacy connection data', async (t) => {
  const { app, request, open } = await fixture(t);
  const legacyInstagram = { enabled: true, accountLabel: 'legacy_assistant', ownerUsername: 'legacy_owner', ownerThreadUrl: 'https://www.instagram.com/direct/t/123456/', observeInbox: true, markReadOnReceive: true };
  const saved = await request('/api/settings', { method: 'PATCH', body: { channels: { default: 'instagram-dm', instagram: legacyInstagram }, model: { ephemeral: true, preferences: { preferredModel: 'gpt-6.1-sol', reasoningEffort: 'medium', speed: 'fast' } } } });
  assert.equal(saved.response.status, 200);
  app.store.setRuntimeState('source-imported', true);
  app.store.setRuntimeState('latest-requirements-imported', true);
  for (const id of ['owner-medium-and-dm-read-2026-10-12', 'owner-chatgpt-history-2026-10-13', 'owner-native-instructions-and-memory-2026-10-13']) app.store.setRuntimeState('requirements-revision:' + id, true);
  const evidence = app.store.addMessage({ channel: 'web', role: 'user', text: '나는 커피를 좋아하고 이 대화는 영구적으로 보존해줘.', metadata: { authority: 'owner' } });
  const personalMemory = app.store.upsertMemory({ key: 'preference.coffee', content: '커피를 좋아한다.', kind: 'fact', sourceMessageIds: [evidence.id] });
  const goalTitle = '지속적으로 행동하고 개인화하는 호영이';
  const legacyGoal = app.store.addGoal({ title: goalTitle, description: '주인의 대화·목표·맡긴 책임을 계속 기억한다. 상황과 실제 작업 결과를 보고 자율적으로 다음 행동을 고르고, 주인 DM에는 작업 중에도 대응한다. 운영 예산은 0원이며 유료 전환 없이 무료 범위를 유지한다.', continuous: true, priority: 10 });
  const manualGoal = app.store.addGoal({ title: goalTitle, description: '이것은 내가 직접 수정한 목표다.', continuous: true, priority: 20 });
  const pendingTask = app.store.addTask({ title: '진행 중인 앱 작업', description: '재시작 후 이어갈 작업', metadata: { channel: 'web', authority: 'owner' } });
  const prior = new Map();
  for (const key of ['autonomy', 'identity', 'authority', 'permissions', 'quota', 'dm_read']) {
    const source = app.store.addMessage({ channel: 'requirements-import', externalId: 'legacy-dm-scope:' + key, role: 'user', text: '이전 Instagram DM 운영 요구사항: ' + key, metadata: { authority: 'historical_owner' } });
    prior.set(key, app.store.upsertMemory({ key: 'owner-requirement:' + key, content: source.text, kind: 'fact', confidence: 1, sourceMessageIds: [source.id] }));
  }
  await app.close();
  const migrated = await open({ seed: true });
  const settings = migrated.app.store.getSettings();
  assert.equal(settings.channels.default, 'web');
  assert.equal(settings.channels.instagram.enabled, false);
  assert.equal(settings.channels.instagram.observeInbox, false);
  assert.equal(settings.channels.instagram.markReadOnReceive, false);
  assert.equal(settings.model.ephemeral, false, 'latest scope retains local CLI transcripts');
  for (const key of ['accountLabel', 'ownerUsername', 'ownerThreadUrl']) assert.equal(settings.channels.instagram[key], legacyInstagram[key], 'migration preserves legacy connection data');
  assert.deepEqual(settings.model.preferences, saved.body.settings.model.preferences);
  assert.deepEqual(migrated.app.store.getMemory(personalMemory.id), personalMemory);
  assert.deepEqual(migrated.app.store.getMessage(evidence.id), evidence);
  assert.deepEqual(migrated.app.store.getTask(pendingTask.id), pendingTask);
  const updatedGoal = migrated.app.store.listGoals().find(goal => goal.id === legacyGoal.id);
  assert.match(updatedGoal.description, /전용 앱의 사용자 메시지/);
  assert.equal(updatedGoal.priority, legacyGoal.priority);
  assert.equal(updatedGoal.continuous, true);
  assert.deepEqual(migrated.app.store.listGoals().find(goal => goal.id === manualGoal.id), manualGoal, 'manually edited goals are preserved');
  for (const [key, old] of prior) {
    const current = migrated.app.store.listMemories({ key: 'owner-requirement:' + key })[0];
    assert.equal(current.version, 2);
    assert.equal(current.supersedesId, old.id);
    assert.equal(migrated.app.store.getMemory(old.id).state, 'superseded');
    assert.ok(migrated.app.store.getMessage(old.sourceMessageIds[0]), 'superseded source evidence is retained');
    assert.match(migrated.app.store.getMessage(current.sourceMessageIds[0]).externalId, /owner-single-user-app-2026-10-13/);
  }
  const raw = migrated.app.store.listMemories({ key: 'owner-requirement:single_user_app' })[0];
  assert.equal(raw.content, '애초에 우리 전용 채팅앱을 개발허기로 했으니 주인만 명령하고 다른 사람한테 디엠오는 경우 이런건 이제 신경 안써도 되지\n나만쓸거니까계정 문제는 필요없은 로그인 이런거 안만들어도 됨. 한도 소진 이런 이슈만 관리하고 어차피 cli는 로컬에 대화내용 저장하니 기록 남을 걱정 없는거 아니야?\nCli좀 제대로 공부해와');
  assert.equal(migrated.app.store.getMessage(raw.sourceMessageIds[0]).text, raw.content);
  assert.match(migrated.app.store.listMemories({ key: 'owner-requirement:application_scope' })[0].content, /앱 회원가입·로그인 계정.*만들지 않는다/);
  const memoryCount = migrated.app.store.listMemories({ includeArchived: true }).length;
  const messageCount = migrated.app.store.listMessages().length;
  const manual = await migrated.request('/api/settings', { method: 'PATCH', body: { autonomy: { heartbeatSeconds: 90 }, model: { preferences: { reasoningEffort: 'high' } } } });
  assert.equal(manual.response.status, 200);
  const edited = await migrated.request('/api/memories/' + personalMemory.id, { method: 'PATCH', body: { content: '요즘은 디카페인 커피를 좋아한다.' } });
  assert.equal(edited.response.status, 200);
  const editedGoal = await migrated.request('/api/goals/' + legacyGoal.id, { method: 'PATCH', body: { description: '마이그레이션 뒤에 내가 직접 수정한 목표다.' } });
  assert.equal(editedGoal.response.status, 200);
  await migrated.app.close();
  const reopened = await open({ seed: true });
  assert.equal(reopened.app.store.getSettings().autonomy.heartbeatSeconds, 90);
  assert.equal(reopened.app.store.getSettings().model.preferences.reasoningEffort, 'high', 'revision cannot overwrite later manual model preferences');
  assert.equal(reopened.app.store.getSettings().channels.default, 'web');
  assert.equal(reopened.app.store.listGoals().find(goal => goal.id === legacyGoal.id).description, '마이그레이션 뒤에 내가 직접 수정한 목표다.', 'one-time migration preserves later goal edits');
  assert.equal(reopened.app.store.listMemories({ key: 'preference.coffee' })[0].content, '요즘은 디카페인 커피를 좋아한다.');
  assert.equal(reopened.app.store.listMemories({ includeArchived: true }).length, memoryCount + 1, 'only the manual memory revision adds a version');
  assert.equal(reopened.app.store.listMessages().length, messageCount + 1, 'only manual correction evidence adds a message');
});
