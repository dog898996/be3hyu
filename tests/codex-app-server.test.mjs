import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { CodexAppServerClient, CodexTransportError } from '../src/codex-app-server.mjs';

const nextTick = () => new Promise(resolve => setImmediate(resolve));

function fixture({ initialize = true, onRequest, killCloses = true, experimentalApi = false } = {}) {
  const calls = [];
  const spawnImpl = (command, args, options) => {
    const child = new EventEmitter();
    child.stdin = new PassThrough();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.exitCode = null;
    const call = { command, args, options, child, input: [], kills: [] };
    calls.push(call);
    let input = '';
    child.stdin.on('data', bytes => {
      input += bytes.toString();
      let end;
      while ((end = input.indexOf('\n')) !== -1) {
        const message = JSON.parse(input.slice(0, end));
        input = input.slice(end + 1);
        call.input.push(message);
        if (message.method === 'initialize' && initialize) {
          queueMicrotask(() => send(call, { id: message.id, result: { userAgent: 'codex/test' } }));
        }
        onRequest?.(message, call);
      }
    });
    child.kill = signal => {
      call.kills.push(signal);
      if (killCloses && child.exitCode === null) {
        child.exitCode = 0;
        queueMicrotask(() => child.emit('close', 0, signal));
      }
      return true;
    };
    return child;
  };
  const client = new CodexAppServerClient({ spawnImpl, requestTimeoutMs: 1000, closeTimeoutMs: 25,
    codeHome: '/application-owned/native', cwd: '/fixed/cloud/workspace', experimentalApi });
  return { client, calls };
}

function send(call, message) {
  call.child.stdout.write(JSON.stringify(message) + '\n');
}

test('concurrent startup shares one official stdio process and performs initialization only', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  await Promise.all([client.start(), client.start(), client.start()]);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].args, ['app-server', '--listen', 'stdio://']);
  assert.equal(calls[0].options.shell, false);
  assert.equal(calls[0].options.cwd, '/fixed/cloud/workspace');
  assert.equal(calls[0].options.env.CODEX_HOME, '/application-owned/native');
  assert.deepEqual(calls[0].input.map(message => message.method), ['initialize', 'initialized']);
  assert.equal(calls[0].input[0].params.capabilities.experimentalApi, false);
  assert.equal(Object.hasOwn(calls[0].input[0].params, 'sessionSource'), false);
  assert.deepEqual(client.state, { connected: true, ready: true });
});

test('requests reuse the resident process and correlate responses arriving out of order', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  await client.start();
  const first = client.request('thread/read', { threadId: 'one' });
  const second = client.request('thread/read', { threadId: 'two' });
  await nextTick();
  const requests = calls[0].input.filter(message => message.method === 'thread/read');
  assert.equal(requests.length, 2);
  assert.ok(requests.every(message => Number.isSafeInteger(message.id)));
  send(calls[0], { id: requests[1].id, result: { value: 'two' } });
  send(calls[0], { id: requests[0].id, result: { value: 'one' } });
  assert.deepEqual(await Promise.all([first, second]), [{ value: 'one' }, { value: 'two' }]);
  assert.equal(calls.length, 1);
  assert.equal(client._pending.size, 0);
});

test('experimental native APIs require an explicit boolean client opt-in', async t => {
  assert.throws(() => new CodexAppServerClient({ experimentalApi: 'true' }), TypeError);
  const { client, calls } = fixture({ experimentalApi: true });
  t.after(() => client.close());
  await client.start();
  assert.equal(calls[0].input[0].params.capabilities.experimentalApi, true);
  assert.deepEqual(calls[0].input.map(message => message.method), ['initialize', 'initialized']);
});

test('native events are delivered immediately before the turn/start acknowledgement', async t => {
  const seen = [];
  const { client } = fixture({ onRequest: (message, call) => {
    if (message.method !== 'turn/start') return;
    send(call, { method: 'turn/started', params: { threadId: 'thread', turn: { id: 'turn' } } });
    send(call, { method: 'turn/completed', params: { threadId: 'thread', turn: { id: 'turn', status: 'completed' } } });
    queueMicrotask(() => send(call, { id: message.id, result: { turn: { id: 'turn' } } }));
  } });
  t.after(() => client.close());
  client.on('notification', message => seen.push(message.method));
  await client.request('turn/start', { threadId: 'thread', input: [] });
  seen.push('ack');
  assert.deepEqual(seen, ['turn/started', 'turn/completed', 'ack']);
});

test('fragmented JSONL preserves multibyte content and routes several lines in one chunk', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  await client.start();
  const seen = [];
  client.on('notification', message => seen.push(message));
  const bytes = Buffer.from(JSON.stringify({ method: 'item/agentMessage/delta', params: { delta: '호영이' } }) + '\n');
  const split = bytes.indexOf(Buffer.from('호')) + 1;
  calls[0].child.stdout.write(bytes.subarray(0, split));
  assert.equal(seen.length, 0);
  calls[0].child.stdout.write(Buffer.concat([bytes.subarray(split), Buffer.from('\n' + JSON.stringify({ method: 'account/updated', params: {} }) + '\n')]));
  assert.equal(seen[0].params.delta, '호영이');
  assert.equal(seen[1].method, 'account/updated');
  assert.equal(client.ready, true);
});

test('unknown native server requests receive an error and are never automatically approved', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  await client.start();
  send(calls[0], { id: 'approval-1', method: 'item/commandExecution/requestApproval', params: { command: 'private value' } });
  const response = calls[0].input.find(message => message.id === 'approval-1');
  assert.equal(response.error.code, -32601);
  assert.equal(Object.hasOwn(response, 'result'), false);
  assert.equal(JSON.stringify(response).includes('private value'), false);
  assert.equal(client.respond('approval-1', { decision: 'accept' }), false);
});

test('an explicit native request handler responds once and sanitizes handler failures', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  client.setServerRequestHandler(async method => {
    if (method === 'known') return { decision: 'decline' };
    throw new Error('MUST_NOT_EXPOSE_CREDENTIAL');
  });
  await client.start();
  send(calls[0], { id: 41, method: 'known', params: {} });
  send(calls[0], { id: 42, method: 'unknown', params: {} });
  await nextTick();
  const responses = calls[0].input.filter(message => [41, 42].includes(message.id));
  assert.deepEqual(responses[0], { id: 41, result: { decision: 'decline' } });
  assert.equal(responses[1].error.code, -32603);
  assert.equal(JSON.stringify(responses).includes('MUST_NOT_EXPOSE_CREDENTIAL'), false);
  assert.equal(client._serverRequests.size, 0);
});

test('RPC errors retain only numeric codes and never expose native messages, data or stderr', async t => {
  const { client, calls } = fixture({ onRequest: (message, call) => {
    if (message.method !== 'account/read') return;
    call.child.stderr.write('MUST_NOT_EXPOSE_STDERR');
    send(call, { id: message.id, error: { code: -32001, message: 'MUST_NOT_EXPOSE_TOKEN', data: { token: 'MUST_NOT_EXPOSE_AUTH' } } });
  } });
  t.after(() => client.close());
  await assert.rejects(client.request('account/read', {}), error => {
    assert.ok(error instanceof CodexTransportError);
    assert.equal(error.code, 'CODEX_RPC_ERROR');
    assert.equal(error.rpcCode, -32001);
    assert.equal(JSON.stringify(error).includes('MUST_NOT_EXPOSE'), false);
    assert.equal(error.message.includes('MUST_NOT_EXPOSE'), false);
    return true;
  });
  assert.equal(calls.length, 1);
  assert.equal(client.ready, true);
});

test('a malformed protocol frame rejects waiting requests and disconnects without inventing completion', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  await client.start();
  const notifications = [];
  const disconnects = [];
  client.on('notification', value => notifications.push(value));
  client.on('disconnect', error => disconnects.push(error));
  const waiting = client.request('turn/start', {});
  const rejection = assert.rejects(waiting, { code: 'CODEX_TRANSPORT_PROTOCOL' });
  await nextTick();
  calls[0].child.stdout.write('{"private token": invalid}\n');
  await rejection;
  assert.equal(client.ready, false);
  assert.equal(client.connected, false);
  assert.equal(disconnects.length, 1);
  assert.equal(disconnects[0].message.includes('private token'), false);
  assert.deepEqual(notifications, []);
  assert.equal(client._pending.size, 0);
});

test('RPC errors preserve only whitelisted native error kinds for quota and steering decisions', async t => {
  const variants = ['usageLimitExceeded', 'rateLimitExceeded',
    { activeTurnNotSteerable: { turnKind: 'review', token: 'MUST_NOT_EXPOSE_NESTED' } },
    'MUST_NOT_EXPOSE_UNKNOWN_KIND', { MUST_NOT_EXPOSE_UNKNOWN_KEY: { token: 'private' } }];
  const { client } = fixture({ onRequest: (message, call) => {
    if (message.method !== 'turn/steer') return;
    send(call, { id: message.id, error: { code: -32000, message: 'MUST_NOT_EXPOSE_MESSAGE',
      data: { codexErrorInfo: variants.shift(), otherPrivateField: 'MUST_NOT_EXPOSE_DATA' } } });
  } });
  t.after(() => client.close());
  for (const expected of ['usageLimitExceeded', 'rateLimitExceeded', 'activeTurnNotSteerable', undefined, undefined]) {
    await assert.rejects(client.request('turn/steer', {}), error => {
      assert.equal(error.codexErrorInfo, expected);
      assert.equal(error.rpcCode, -32000);
      assert.equal(JSON.stringify(error).includes('MUST_NOT_EXPOSE'), false);
      assert.equal(Object.hasOwn(error, 'data'), false);
      return true;
    });
  }
});

test('an oversized unfinished frame is bounded and ends the connection', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  await client.start();
  const event = new Promise(resolve => client.once('disconnect', resolve));
  calls[0].child.stdout.write(Buffer.alloc(8 * 1024 * 1024 + 1, 120));
  assert.equal((await event).code, 'CODEX_TRANSPORT_OVERFLOW');
  assert.equal(client.connected, false);
});

test('RPC timeouts remove bookkeeping without killing native work or accepting late responses', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  await client.start();
  await assert.rejects(client.request('turn/start', {}, { timeoutMs: 10 }), { code: 'CODEX_REQUEST_TIMEOUT' });
  const timedOut = calls[0].input.find(message => message.method === 'turn/start');
  send(calls[0], { id: timedOut.id, result: { turn: { id: 'late-native-turn' } } });
  assert.equal(client._pending.size, 0);
  assert.deepEqual(calls[0].kills, []);
  assert.equal(client.ready, true);
  const followup = client.request('turn/read', {});
  await nextTick();
  const read = calls[0].input.find(message => message.method === 'turn/read');
  send(calls[0], { id: read.id, result: { turn: { id: 'late-native-turn' } } });
  assert.equal((await followup).turn.id, 'late-native-turn');
});

test('request cancellation does not terminate the process or send a turn interrupt', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  await client.start();
  const controller = new AbortController();
  const request = client.request('turn/start', {}, { signal: controller.signal });
  const rejection = assert.rejects(request, { code: 'CODEX_REQUEST_ABORTED' });
  await nextTick();
  controller.abort();
  await rejection;
  assert.deepEqual(calls[0].kills, []);
  assert.equal(calls[0].input.some(message => message.method === 'turn/interrupt'), false);
  assert.equal(client.ready, true);
  assert.equal(client._pending.size, 0);
});

test('cancelling one caller during startup does not cancel shared initialization', async t => {
  const { client, calls } = fixture({ initialize: false });
  t.after(() => client.close());
  const controller = new AbortController();
  const request = client.request('account/read', {}, { signal: controller.signal });
  const rejection = assert.rejects(request, { code: 'CODEX_REQUEST_ABORTED' });
  const started = client.start();
  controller.abort();
  await rejection;
  const initialize = calls[0].input.find(message => message.method === 'initialize');
  send(calls[0], { id: initialize.id, result: {} });
  await started;
  assert.equal(client.ready, true);
  assert.equal(calls[0].input.some(message => message.method === 'account/read'), false);
  assert.deepEqual(calls[0].kills, []);
});

test('unexpected process exit rejects all requests and permits an explicit clean restart', async t => {
  const { client, calls } = fixture();
  t.after(() => client.close());
  await client.start();
  const first = client.request('thread/read', {});
  const second = client.request('account/read', {});
  const rejections = Promise.all([
    assert.rejects(first, { code: 'CODEX_TRANSPORT_DISCONNECTED' }),
    assert.rejects(second, { code: 'CODEX_TRANSPORT_DISCONNECTED' }),
  ]);
  await nextTick();
  calls[0].child.exitCode = 1;
  calls[0].child.emit('close', 1);
  await rejections;
  assert.equal(client._pending.size, 0);
  assert.equal(client.ready, false);
  await client.start();
  assert.equal(calls.length, 2);
  assert.equal(client.ready, true);
});

test('restart waits for the failed subprocess to terminate before starting its replacement', async t => {
  const { client, calls } = fixture({ killCloses: false });
  t.after(() => client.close());
  await client.start();
  calls[0].child.stdout.write('invalid protocol\n');
  const restarted = client.start();
  await nextTick();
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].kills, ['SIGTERM']);
  await restarted;
  assert.deepEqual(calls[0].kills, ['SIGTERM', 'SIGKILL']);
  assert.equal(calls.length, 2);
  assert.equal(client.ready, true);
});

test('explicit close rejects pending acknowledgements and performs bounded graceful shutdown', async () => {
  const { client, calls } = fixture({ killCloses: false });
  await client.start();
  const waiting = client.request('turn/start', {});
  const rejection = assert.rejects(waiting, { code: 'CODEX_TRANSPORT_CLOSED' });
  await nextTick();
  await client.close();
  await rejection;
  assert.equal(client._pending.size, 0);
  assert.equal(client.connected, false);
  assert.equal(calls[0].child.stdin.writableEnded, true);
  assert.deepEqual(calls[0].kills, ['SIGTERM', 'SIGKILL']);
});

test('startup failures sanitize native spawn exceptions and pre-aborted requests spawn nothing', async () => {
  let spawned = 0;
  const client = new CodexAppServerClient({ spawnImpl: () => { spawned++; throw new Error('MUST_NOT_EXPOSE_AUTH_PATH'); } });
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(client.request('account/read', {}, { signal: controller.signal }), { code: 'CODEX_REQUEST_ABORTED' });
  assert.equal(spawned, 0);
  await assert.rejects(client.start(), error => error.code === 'CODEX_TRANSPORT_START' && !error.message.includes('MUST_NOT_EXPOSE'));
  assert.equal(client.connected, false);
});
