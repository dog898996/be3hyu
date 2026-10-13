import test from 'node:test';
import assert from 'node:assert/strict';
import { PendingMessageQueue } from '../public/message-queue.js';

function storageFixture() {
  const values = new Map();
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), values };
}

function transportFixture() {
  const messages = new Map(); const requests = []; let tasks = 0;
  return {
    messages, requests,
    get tasks() { return tasks; },
    async send(payload) {
      requests.push({ ...payload });
      const saved = messages.get(payload.clientMessageId);
      if (saved) {
        if (saved.text !== payload.message) throw Object.assign(new Error('Conflict'), { status: 409 });
        return { message: saved, duplicate: true };
      }
      const message = { id: `server-${++tasks}`, channel: 'web', externalId: `app:${payload.clientMessageId}`, role: 'user', text: payload.message };
      messages.set(payload.clientMessageId, message);
      return { message };
    }
  };
}

test('an accepted request with a lost acknowledgement retries its durable ID without creating a second cloud task', async () => {
  const storage = storageFixture(); const transport = transportFixture();
  const original = new PendingMessageQueue({ storage, makeId: () => 'stable-first-message', send: async payload => { await transport.send(payload); throw new TypeError('Acknowledgement lost'); } });
  original.enqueue('인스타 작업을 이어 해줘', { draftKey: 'chat-message' });
  assert.equal((await original.flush()).blocked, 'connection');
  assert.equal(transport.tasks, 1);
  assert.equal(original.list().length, 1);
  const reloaded = new PendingMessageQueue({ storage, send: transport.send });
  assert.equal((await reloaded.flush()).accepted, 1);
  assert.deepEqual(transport.requests.map(request => request.clientMessageId), ['stable-first-message', 'stable-first-message']);
  assert.equal(transport.tasks, 1, 'same ID must reuse the cloud receipt');
  assert.deepEqual(reloaded.list(), []);
});

test('state reconciliation clears an ambiguously accepted message before reconnect flush without another POST', async () => {
  const storage = storageFixture(); const transport = transportFixture(); const acknowledgements = [];
  const original = new PendingMessageQueue({ storage, makeId: () => 'ambiguous', send: async payload => { await transport.send(payload); throw new Error('Connection lost'); } });
  original.enqueue('클라우드에서 작업해'); await original.flush();
  const reloaded = new PendingMessageQueue({ storage, send: transport.send, onAccepted: (entry, response) => acknowledgements.push({ entry, response }) });
  reloaded.reconcile([...transport.messages.values()]);
  assert.equal((await reloaded.flush()).accepted, 0);
  assert.equal(transport.requests.length, 1);
  assert.equal(transport.tasks, 1);
  assert.equal(acknowledgements[0].response.reconciled, true);
  assert.equal(acknowledgements[0].entry.message, '클라우드에서 작업해');
});

test('offline text and composer identity survive reload and multiple reconnect signals share one drain', async () => {
  const storage = storageFixture(); const transport = transportFixture(); let online = false;
  const original = new PendingMessageQueue({ storage, makeId: () => 'offline-queue', canSend: () => online, send: transport.send });
  const queued = original.enqueue('연결되면 처리해', { draftKey: 'quick-message' });
  assert.equal((await original.flush()).blocked, 'offline');
  assert.equal(transport.requests.length, 0);
  let release;
  const suspended = new Promise(resolve => { release = resolve; });
  let inFlight = 0; let maximum = 0;
  const reloaded = new PendingMessageQueue({ storage, canSend: () => online, send: async payload => { inFlight++; maximum = Math.max(maximum, inFlight); await suspended; const receipt = await transport.send(payload); inFlight--; return receipt; } });
  assert.deepEqual(reloaded.list(), [queued]);
  online = true;
  const first = reloaded.flush(); const reconnect = reloaded.flush(); const visibleAgain = reloaded.flush();
  assert.equal(first, reconnect); assert.equal(first, visibleAgain);
  assert.equal(inFlight, 1);
  release(); await Promise.all([first, reconnect, visibleAgain]);
  assert.equal(maximum, 1);
  assert.equal(transport.requests.length, 1);
  assert.equal(transport.requests[0].clientMessageId, 'offline-queue');
});

test('authentication failure retains text and pauses requests across reload until a confirmed connection', async () => {
  const storage = storageFixture(); const transport = transportFixture(); let attempts = 0;
  const original = new PendingMessageQueue({ storage, makeId: () => 'auth-pending', send: async () => { attempts++; throw Object.assign(new Error('Access required'), { status: 401 }); } });
  original.enqueue('사용량이 풀리면 계속해');
  assert.equal((await original.flush()).blocked, 'authentication');
  await original.flush(); await original.flush();
  assert.equal(attempts, 1);
  const reloaded = new PendingMessageQueue({ storage, send: async payload => { attempts++; return transport.send(payload); } });
  assert.equal(reloaded.list()[0].state, 'auth_required');
  assert.equal((await reloaded.flush()).blocked, 'authentication');
  assert.equal(attempts, 1);
  reloaded.confirmConnection();
  assert.equal((await reloaded.flush()).accepted, 1);
  assert.equal(attempts, 2);
  assert.equal(transport.tasks, 1);
  const persisted = [...storage.values.values()].join('');
  assert.doesNotMatch(persisted, /token|authorization|password|cookie/i);
});

test('a known conflicting ID or rejected message is preserved without endless automatic retries', async () => {
  const storage = storageFixture(); const transport = transportFixture();
  await transport.send({ clientMessageId: 'conflicting', message: 'original accepted text' });
  const queue = new PendingMessageQueue({ storage, makeId: () => 'conflicting', send: transport.send });
  queue.enqueue('different pending text');
  assert.equal((await queue.flush()).blocked, 'rejected');
  assert.equal(queue.list()[0].state, 'conflict');
  queue.confirmConnection(); await queue.flush();
  assert.equal(transport.requests.length, 2);
  assert.equal(queue.list()[0].message, 'different pending text');
  assert.equal(transport.tasks, 1);
});

test('unrelated state data cannot erase pending text and storage failure prevents an unjournaled send', async () => {
  const storage = storageFixture(); const transport = transportFixture();
  const queue = new PendingMessageQueue({ storage, makeId: () => 'my-message', send: transport.send });
  queue.enqueue('내 지시');
  queue.reconcile([{ channel: 'web', externalId: 'app:other-message', role: 'user', text: '내 지시' }]);
  queue.reconcile([{ channel: 'instagram', externalId: 'app:my-message', role: 'user', text: '내 지시' }]);
  assert.equal(queue.list().length, 1);
  const blocked = new PendingMessageQueue({ storage: { getItem: () => null, setItem: () => { throw new Error('Disk full'); } }, send: transport.send });
  assert.throws(() => blocked.enqueue('잃으면 안 되는 입력'), /저장하지 못했습니다/);
  await blocked.flush();
  assert.equal(transport.requests.length, 0);
});

test('invalid stored queue is left intact instead of being replaced by new text', async () => {
  const storage = storageFixture(); storage.setItem('hoyoung.pending-text.v1', '{unreadable');
  const queue = new PendingMessageQueue({ storage, send: () => assert.fail('Must not send after failed storage read') });
  assert.throws(() => queue.enqueue('새 입력'), /메시지를 읽을 수 없습니다/);
  assert.equal((await queue.flush()).blocked, 'storage');
  assert.equal(storage.getItem('hoyoung.pending-text.v1'), '{unreadable');
});
