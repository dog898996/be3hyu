import test from 'node:test';
import assert from 'node:assert/strict';
import { ChatGptBrowserAdapter, NativeWebBridgeAdapter, ModelNotReadyError, ModelProtocolError,
  parseAgentPlan, compileBrowserPrompt, ModelRateLimitError, rateLimitFromEvidence, CodexCliAdapter, evaluateModelPreferences, resolveNativeModelPreferences, NATIVE_PLANNING_FEATURES_DISABLED, readCodexAccountRateLimits } from '../src/model.mjs';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { access, mkdir, mkdtemp, readFile, readdir, rm, stat, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const tools = [{ type: 'function', name: 'memory_add', parameters: { type: 'object' } }];
const response = JSON.stringify({ message: '완료 결과 확인 중', actions: [{ name: 'memory_add', arguments: { content: '사용자가 직접 밝힌 선호' } }], memories: [] });

function browserFixture({ authenticated = true, model = 'Observed model', pressError = false, alerts = [],
  initialUrl = 'https://chatgpt.com/', transitions = {} } = {}) {
  let submitted = 0;
  let filled = 0;
  let prompt = '';
  let currentUrl = initialUrl;
  const navigations = [];
  const transition = stage => { if (transitions[stage]) currentUrl = transitions[stage]; };
  const page = {
    url: () => currentUrl,
    goto: async url => { navigations.push(url); currentUrl = url; transition('goto'); },
    evaluate: async fn => {
      if (fn.constructor.name === 'AsyncFunction') {
        transition('session');
        return { authenticated, composerCount: 1, modelLabels: [model], reason: authenticated ? null : 'login_required' };
      }
      if (!submitted) transition('turns');
      return submitted ? { generating: false, serviceAlerts: alerts, knownIds: ['user:new', 'assistant:new'],
        users: [{ id: 'user:new', text: prompt }], assistants: [{ id: 'assistant:new', text: response, complete: true }] }
        : { generating: false, knownIds: ['user:old', 'assistant:old'], users: [], assistants: [] };
    },
    locator: () => {
      const locator = { filter: () => locator, first: () => locator, waitFor: async () => {}, count: async () => { transition('sendCount'); return 1; },
        fill: async text => { filled++; prompt = text; transition('fill'); }, locator: () => locator,
        click: async () => { submitted++; if (pressError) throw new Error('A post-click transport error'); } };
      return locator;
    },
  };
  return { browser: { withPage: async (owner, callback) => { assert.equal(owner, 'chatgpt'); return callback(page); } },
    submissions: () => submitted, fills: () => filled, navigations: () => [...navigations] };
}

test('plan parser accepts actual registered actions and distinguishes the user reply', () => {
  const parsed = parseAgentPlan(response, tools);
  assert.equal(parsed.text, '완료 결과 확인 중');
  assert.deepEqual(parsed.toolCalls[0], { id: 'action_1', name: 'memory_add', arguments: { content: '사용자가 직접 밝힌 선호' } });
});

test('actual rate-limit evidence retains a real reset header but never fabricates missing reset times', () => {
  const now = Date.parse('2026-01-01T00:00:00Z');
  const error = rateLimitFromEvidence({ status: 429, retryAfter: '120', now });
  assert.ok(error instanceof ModelRateLimitError);
  assert.equal(error.retryAfterMs, 120000);
  assert.equal(error.retryAt, '2026-01-01T00:02:00.000Z');
  assert.equal(error.resetKnown, true);
  assert.equal(rateLimitFromEvidence({ message: '사용 한도에 도달했습니다.' }).resetKnown, false);
  assert.equal(rateLimitFromEvidence({ message: 'Network connection failed' }), null);
});

test('visible model limit after Send stops response processing without resubmitting', async () => {
  const fixture = browserFixture({ alerts: ["You've reached your message limit. Try again later."] });
  const adapter = new ChatGptBrowserAdapter({}, fixture);
  await assert.rejects(adapter.respond({ tools }), error => error.code === 'MODEL_RATE_LIMIT' && error.details.submitted === true);
  assert.equal(fixture.submissions(), 1);
});

test('native HTTP 429 remains a typed limit even when its body is not JSON', async () => {
  const adapter = new NativeWebBridgeAdapter({ endpoint: 'http://127.0.0.1:17841' }, {
    fetchImpl: async () => new Response('Too many requests', { status: 429, headers: { 'retry-after': '30' } }),
  });
  await assert.rejects(adapter.respond({ nativeRequest: { model: 'chatgpt-web/observed', client_metadata: { 'x-codex-turn-metadata': 'real-native-request' } } }),
    error => error.code === 'MODEL_RATE_LIMIT' && error.retryAfterMs === 30000);
});

const nativeModel = { id: 'gpt-6.1-sol', model: 'gpt-6.1-sol', displayName: 'GPT-6.1-Sol', isDefault: true,
  defaultReasoningEffort: 'low', supportedReasoningEfforts: ['low', 'medium', 'high', 'xhigh', 'max', 'ultra'].map(reasoningEffort => ({ reasoningEffort })),
  serviceTiers: [{ id: 'priority', name: 'Fast' }], defaultServiceTier: null, base_instructions: 'MUST_NOT_EXPOSE_PRIVATE_CATALOG_TEXT' };
const nativeQuota = { ordinaryUsageAllowed: true, accountId: 'MUST_NOT_EXPOSE_ACCOUNT_ID',
  rateLimits: { primary: { usedPercent: 20, resetsAt: 1893456000 }, secondary: null, spendControlReached: false,
    rateLimitReachedType: null, individualLimit: null, planType: 'pro' }, rateLimitsByLimitId: null, rateLimitUpsell: null };
function cliFixture({ login = 'Logged in using ChatGPT', quota = false, models = [nativeModel], configMismatch = false, controlsMismatch = false,
  threadId = 'native-thread-123', account = { type: 'chatgpt', email: 'MUST_NOT_EXPOSE_EMAIL', planType: 'pro' },
  rateLimits = nativeQuota, quotaRpcError = null, quotaRpcExit = false, quotaRpcHang = false, accountId = null } = {}) {
  const calls = [];
  return { calls, spawnImpl: (command, args, options) => {
    const invocation = { command, args, options, input: '' };
    calls.push(invocation);
    const child = new EventEmitter();
    child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.stdin = new PassThrough(); child.kill = () => {};
    child.stdin.on('data', chunk => { invocation.input += chunk.toString(); });
    if (args[0] === 'app-server') {
      child.stdin.on('data', chunk => {
        for (const line of chunk.toString().trim().split('\n')) {
          const request = JSON.parse(line);
          if (!request.id) continue;
          let result = {};
          if (request.method === 'model/list') result = { data: models, nextCursor: null };
          else if (request.method === 'account/read') result = { account,
            workspaceRouting: accountId ? { chatgptAccountId: accountId, backendOrigin: 'MUST_NOT_EXPOSE_ORIGIN' } : null };
          else if (request.method === 'account/rateLimits/read') {
            if (quotaRpcError) {
              queueMicrotask(() => {
                child.stderr.write('MUST_NOT_EXPOSE_STDERR_TOKEN');
                child.stdout.write(JSON.stringify({ id: request.id, error: quotaRpcError }) + '\n');
              });
              continue;
            }
            if (quotaRpcExit) { queueMicrotask(() => child.emit('close', 1)); continue; }
            if (quotaRpcHang) continue;
            result = typeof rateLimits === 'function' ? rateLimits() : rateLimits;
          }
          else if (request.method === 'config/read') {
            const config = { base_instructions: 'MUST_NOT_EXPOSE_CONFIG_OR_AUTH', auth: 'MUST_NOT_EXPOSE_AUTH' };
            for (let i = 0; i < args.length; i++) if (args[i] === '-c') {
              const [key, raw] = args[i + 1].split('='); config[key] = JSON.parse(raw);
            }
            if (configMismatch) config.service_tier = 'standard';
            result = { config };
          }
          queueMicrotask(() => child.stdout.write(JSON.stringify({ id: request.id, result }) + '\n'));
        }
      });
      return child;
    }
    queueMicrotask(() => {
      if (args[0] === 'login') child.stderr.write(login);
      else if (args.includes('features')) child.stdout.write(NATIVE_PLANNING_FEATURES_DISABLED.map(name => `${name} stable ${controlsMismatch && name === 'shell_tool' ? 'true' : 'false'}`).join('\n') + '\n');
      else if (quota) child.stdout.write(JSON.stringify({ type: 'turn.failed', error: { code: 'rate_limit_exceeded', message: 'Quota exhausted', retry_after: 45 } }) + '\n');
      else {
        if (threadId) child.stdout.write(JSON.stringify({ type: 'thread.started', thread_id: threadId }) + '\n');
        child.stdout.write(JSON.stringify({ type: 'item.completed', item: { type: 'agent_message', text: response } }) + '\n');
      }
      child.emit('close', quota && args[0] === 'exec' ? 1 : 0);
    });
    return child;
  } };
}

test('CLI requires real ChatGPT login rather than silently selecting paid API auth', async () => {
  const fixture = cliFixture({ login: 'Logged in using an API key' });
  const adapter = new CodexCliAdapter({}, fixture);
  assert.equal((await adapter.health()).ready, false);
  await assert.rejects(adapter.respond({ tools, authority: 'owner' }), { code: 'CODEX_LOGIN_REQUIRED' });
  assert.ok(fixture.calls.every(call => call.args[0] === 'login'));
});

test('CLI executes without a shell, parses its genuine JSONL boundary and surfaces typed quota events', async () => {
  const fixture = cliFixture();
  const adapter = new CodexCliAdapter({}, fixture);
  const result = await adapter.respond({ tools, authority: 'owner' });
  assert.equal(result.toolCalls[0].name, 'memory_add');
  assert.equal(result.id, 'native-thread-123');
  assert.equal(result.nativeSessionRetention, 'local-session-configured');
  assert.equal(result.nativeSessionPersistenceVerified, false);
  assert.equal(result.nativeStorageLocationVerified, false);
  const invocation = fixture.calls.find(call => call.args[0] === 'exec');
  assert.equal(invocation.options.shell, false);
  assert.ok(invocation.args.includes('read-only'));
  assert.ok(!invocation.args.includes('--ephemeral'));
  await assert.rejects(new CodexCliAdapter({}, cliFixture({ quota: true })).respond({ tools, authority: 'owner' }),
    error => error.code === 'MODEL_RATE_LIMIT' && error.retryAfterMs === 45000);
});

test('CLI reports configured native retention without claiming observed persistence or storage location', async () => {
  for (const ephemeral of [undefined, false, 'true', true]) {
    const fixture = cliFixture();
    const adapter = new CodexCliAdapter({ ephemeral }, fixture);
    const health = await adapter.health();
    const configuredMode = ephemeral === true ? 'ephemeral-configured' : 'local-session-configured';
    assert.equal(health.nativeSessionRetention, configuredMode);
    assert.equal(health.nativeSessionPersistenceVerified, false);
    assert.equal(health.nativeStorageLocationVerified, false);
    const result = await adapter.respond({ tools, authority: 'owner' });
    assert.equal(result.nativeSessionRetention, configuredMode);
    assert.equal(result.nativeSessionPersistenceVerified, false);
    assert.equal(result.nativeStorageLocationVerified, false);
    assert.equal(fixture.calls.find(call => call.args[0] === 'exec').args.includes('--ephemeral'), ephemeral === true);
  }
});

test('native quota transport sends only read-only account RPC and discards private account data', async () => {
  const fixture = cliFixture();
  const observed = await readCodexAccountRateLimits(fixture);
  assert.equal(observed.ordinaryUsageAllowed, true);
  assert.equal(observed.quotaObservation, 'allowed');
  assert.equal(observed.quotaRecoveryVerified, true);
  assert.equal(observed.quotaExhausted, false);
  assert.equal(observed.planType, 'pro');
  assert.ok(!JSON.stringify(observed).includes('MUST_NOT_EXPOSE'));
  assert.equal(fixture.calls.length, 1);
  assert.equal(fixture.calls[0].options.shell, false);
  const requests = fixture.calls[0].input.trim().split('\n').map(line => JSON.parse(line));
  assert.deepEqual(requests.map(request => request.method), ['initialize', 'initialized', 'account/read', 'account/rateLimits/read']);
  assert.deepEqual(requests[2].params, { refreshToken: false });
  assert.deepEqual(requests[3].params, { supportsLunaReserve: false, excludeResetCreditDetails: true });
});

test('known account quota blocks health and native turns with a typed error and reported check time', async () => {
  const now = Date.parse('2026-01-01T00:00:00Z');
  const reset = now / 1000 + 45;
  const blocked = { ...nativeQuota, ordinaryUsageAllowed: false,
    rateLimits: { ...nativeQuota.rateLimits, primary: { usedPercent: 100, resetsAt: reset }, credits: { hasCredits: true, unlimited: false } } };
  const observed = await readCodexAccountRateLimits({ ...cliFixture({ rateLimits: blocked }), now });
  assert.equal(observed.quotaObservation, 'blocked');
  assert.equal(observed.ordinaryUsageAllowed, false);
  assert.equal(observed.quotaRecoveryVerified, false);
  assert.equal(observed.quotaExhausted, true);
  assert.equal(observed.rateLimited, true);
  assert.equal(observed.resetKnown, true);
  assert.equal(observed.retryAt, '2026-01-01T00:00:45.000Z');
  assert.equal(observed.retryAfterMs, 45000);

  // A separate catalog failure must not replace an already observed quota limit.
  const fixture = cliFixture({ rateLimits: blocked, models: [] });
  const adapter = new CodexCliAdapter({}, fixture);
  const health = await adapter.health();
  assert.equal(health.ready, false);
  assert.equal(health.state, 'quota_exhausted');
  assert.equal(health.code, 'MODEL_RATE_LIMIT');
  assert.ok(!JSON.stringify(health).includes('MUST_NOT_EXPOSE'));
  await assert.rejects(adapter.respond({ authority: 'owner', tools }), error => error instanceof ModelRateLimitError
    && error.code === 'MODEL_RATE_LIMIT' && error.resetKnown === true && error.retryAt === observed.retryAt);
  assert.ok(!fixture.calls.some(call => call.args[0] === 'exec'));
  assert.ok(!fixture.calls.some(call => call.input.includes('model/list')));
});

test('quota reset passage, percentages and unknown banners never establish recovery', async () => {
  const cases = [
    { ordinaryUsageAllowed: null },
    { ordinaryUsageAllowed: false, rateLimits: { primary: { usedPercent: 0, resetsAt: 1 } } },
    { rateLimits: { ...nativeQuota.rateLimits, spendControlReached: true } },
    { rateLimits: { ...nativeQuota.rateLimits, rateLimitReachedType: 'workspace_member_usage_limit_reached' } },
    { rateLimits: { ...nativeQuota.rateLimits, primary: { usedPercent: 100, resetsAt: null } } },
    { rateLimits: { ...nativeQuota.rateLimits, individualLimit: { remainingPercent: 0, resetsAt: null } } },
    { rateLimitUpsell: { unsupported: 'MUST_NOT_EXPOSE_BANNER' } },
  ];
  for (const override of cases) {
    const observed = await readCodexAccountRateLimits(cliFixture({ rateLimits: { ...nativeQuota, ...override } }));
    assert.equal(observed.quotaRecoveryVerified, false);
    assert.notEqual(observed.quotaObservation, 'allowed');
    assert.ok(!JSON.stringify(observed).includes('MUST_NOT_EXPOSE'));
  }
  const noReset = await readCodexAccountRateLimits(cliFixture({ rateLimits: {
    ...nativeQuota, ordinaryUsageAllowed: false, rateLimits: { primary: { usedPercent: 100, resetsAt: null } },
  } }));
  assert.equal(noReset.resetKnown, false);
  assert.equal(noReset.retryAt, null);
  assert.equal(noReset.retryAfterMs, null);
});

test('unknown or unavailable quota permits initial use without claiming account recovery', async () => {
  for (const options of [
    { rateLimits: { rateLimits: { primary: { usedPercent: 10, resetsAt: 1 } } } },
    { quotaRpcError: { code: -1, message: 'MUST_NOT_EXPOSE_RPC_TOKEN', data: { token: 'MUST_NOT_EXPOSE_TOKEN' } } },
    { quotaRpcExit: true },
  ]) {
    const fixture = cliFixture(options);
    const adapter = new CodexCliAdapter({}, fixture);
    const health = await adapter.health();
    assert.equal(health.ready, true);
    assert.equal(health.ordinaryUsageAllowed, null);
    assert.equal(health.quotaObservation, 'unknown');
    assert.equal(health.quotaRecoveryVerified, false);
    assert.ok(!JSON.stringify(health).includes('MUST_NOT_EXPOSE'));
    await adapter.respond({ authority: 'owner', tools });
    assert.equal(fixture.calls.filter(call => call.args[0] === 'exec').length, 1);
  }
  const timedOut = await readCodexAccountRateLimits({ ...cliFixture({ quotaRpcHang: true }), timeoutMs: 10 });
  assert.equal(timedOut.quotaObservation, 'unknown');
  assert.equal(timedOut.quotaRecoveryVerified, false);
  const unavailable = await readCodexAccountRateLimits({ spawnImpl: () => { throw new Error('MUST_NOT_EXPOSE_SPAWN'); } });
  assert.equal(unavailable.quotaObservation, 'unknown');
  assert.ok(!JSON.stringify(unavailable).includes('MUST_NOT_EXPOSE'));
});

test('native usage account must be ChatGPT and never falls back to paid API or other credentials', async () => {
  for (const account of [null, { type: 'apiKey', token: 'MUST_NOT_EXPOSE_API_KEY' }, { type: 'amazonBedrock' }]) {
    const fixture = cliFixture({ account });
    const adapter = new CodexCliAdapter({}, fixture);
    const health = await adapter.health();
    assert.equal(health.ready, false);
    assert.equal(health.authenticated, false);
    assert.equal(health.code, 'CODEX_LOGIN_REQUIRED');
    assert.equal(health.quotaRecoveryVerified, false);
    assert.ok(!JSON.stringify(health).includes('MUST_NOT_EXPOSE'));
    await assert.rejects(adapter.respond({ authority: 'owner', tools }), { code: 'CODEX_LOGIN_REQUIRED' });
    assert.ok(!fixture.calls.some(call => call.args[0] === 'exec'));
    assert.ok(!fixture.calls.some(call => call.input.includes('account/rateLimits/read')));
  }
});

test('missing or mismatched snapshot identity and malformed quota cannot authorize recovery', async () => {
  for (const options of [
    { rateLimits: { ...nativeQuota, accountId: null } },
    { accountId: 'different-active-account' },
    { rateLimits: { ...nativeQuota, rateLimits: null } },
    { rateLimits: { ...nativeQuota, rateLimitsByLimitId: 'MUST_NOT_EXPOSE_MAP' } },
    { rateLimits: { ...nativeQuota, rateLimits: { ...nativeQuota.rateLimits, spendControlReached: 'MUST_NOT_EXPOSE_SPEND' } } },
    { rateLimits: { ...nativeQuota, rateLimits: { primary: { usedPercent: -1 } } } },
    { rateLimits: { ...nativeQuota, rateLimits: { individualLimit: { remainingPercent: 101 } } } },
  ]) {
    const observed = await readCodexAccountRateLimits(cliFixture(options));
    assert.equal(observed.quotaObservation, 'unknown');
    assert.equal(observed.quotaRecoveryVerified, false);
    assert.ok(!JSON.stringify(observed).includes('MUST_NOT_EXPOSE'));
  }
  const unknownPlan = await readCodexAccountRateLimits(cliFixture({ account: { type: 'chatgpt', planType: 'MUST_NOT_EXPOSE_PLAN' } }));
  assert.equal(unknownPlan.planType, 'unknown');
});

test('every health read refreshes quota independently of cached model selection without generating a turn', async () => {
  let rateLimits = nativeQuota;
  const fixture = cliFixture({ rateLimits: () => rateLimits });
  const adapter = new CodexCliAdapter({}, fixture);
  assert.equal((await adapter.health()).quotaRecoveryVerified, true);
  const catalogReads = fixture.calls.filter(call => call.input.includes('model/list')).length;
  rateLimits = { ...nativeQuota, ordinaryUsageAllowed: false };
  assert.equal((await adapter.health()).quotaObservation, 'blocked');
  rateLimits = { ...nativeQuota, ordinaryUsageAllowed: null };
  assert.equal((await adapter.health()).quotaRecoveryVerified, false);
  rateLimits = nativeQuota;
  assert.equal((await adapter.health()).quotaRecoveryVerified, true);
  assert.equal(fixture.calls.filter(call => call.input.includes('account/rateLimits/read')).length, 4);
  assert.equal(fixture.calls.filter(call => call.input.includes('model/list')).length, catalogReads);
  assert.ok(fixture.calls.every(call => !call.input.includes('thread/start') && !call.input.includes('turn/start') && call.args[0] !== 'exec'));
});

test('native catalog maps maximum to highest supported effort and Fast to the actual service tier', async () => {
  const config = { preferences: { preferredModel: 'gpt-6.1-sol', reasoningEffort: 'max', speed: 'fast' } };
  const fixture = cliFixture();
  const adapter = new CodexCliAdapter(config, fixture);
  const health = await adapter.health();
  assert.equal(health.ready, true);
  assert.equal(health.configurationVerified, true);
  assert.equal(health.preferenceApplied, true);
  assert.deepEqual(health.actualConfigured, { model: 'gpt-6.1-sol', reasoningEffort: 'ultra', serviceTier: 'priority' });
  assert.ok(!JSON.stringify(health).includes('MUST_NOT_EXPOSE'));
  await adapter.respond({ tools, authority: 'owner' });
  const invocation = fixture.calls.find(call => call.args[0] === 'exec');
  assert.ok(invocation.args.includes('gpt-6.1-sol'));
  assert.ok(invocation.args.includes('model_reasoning_effort="ultra"'));
  assert.ok(invocation.args.includes('service_tier="priority"'));
});

test('explicit Medium keeps the preferred model and verified Fast tier without raising reasoning effort', async () => {
  const fixture = cliFixture();
  const adapter = new CodexCliAdapter({ preferences: { preferredModel: 'gpt-6.1-sol', reasoningEffort: 'medium', speed: 'fast' } }, fixture);
  const health = await adapter.health();
  assert.equal(health.ready, true);
  assert.equal(health.configurationVerified, true);
  assert.equal(health.preferenceApplied, true);
  assert.deepEqual(health.actualConfigured, { model: 'gpt-6.1-sol', reasoningEffort: 'medium', serviceTier: 'priority' });
  await adapter.respond({ authority: 'owner', tools });
  const invocation = fixture.calls.find(call => call.args[0] === 'exec');
  assert.ok(invocation.args.includes('gpt-6.1-sol'));
  assert.ok(invocation.args.includes('model_reasoning_effort="medium"'));
  assert.ok(invocation.args.includes('service_tier="priority"'));
  assert.ok(!invocation.args.includes('model_reasoning_effort="ultra"'));
});

test('highest effort is catalog-dependent and unavailable model, Fast or unknown effort require owner choice', async () => {
  const settings = { preferences: { preferredModel: 'gpt-6.1-sol', reasoningEffort: 'max', speed: 'fast' } };
  const catalog = [{ ...nativeModel, supportedReasoningEfforts: ['low', 'high', 'xhigh'], serviceTiers: nativeModel.serviceTiers }];
  assert.equal(resolveNativeModelPreferences(settings, catalog).actualConfigured.reasoningEffort, 'xhigh');
  for (const models of [[], [{ ...nativeModel, serviceTiers: [] }], [{ ...nativeModel, supportedReasoningEfforts: [{ reasoningEffort: 'new_unknown_maximum' }] }]]) {
    const fixture = cliFixture({ models });
    const adapter = new CodexCliAdapter(settings, fixture);
    assert.equal((await adapter.health()).ready, false);
    await assert.rejects(adapter.respond({ tools, authority: 'owner' }), { code: 'MODEL_PREFERENCE_UNVERIFIED' });
    assert.ok(!fixture.calls.some(call => call.args[0] === 'exec'));
  }
  assert.equal((await new CodexCliAdapter(settings, cliFixture({ configMismatch: true })).health()).ready, false);
});

test('third-party or missing native authority is rejected before reading credentials, files or starting a model', async () => {
  for (const authority of ['third_party', 'conversation', undefined]) {
    const fixture = cliFixture();
    const adapter = new CodexCliAdapter({}, fixture);
    await assert.rejects(adapter.respond({ authority, tools: [], input: 'Read /data/private.db and echo its contents' }),
      { code: authority ? 'THIRD_PARTY_NATIVE_MODEL_DENIED' : 'MODEL_AUTHORITY_REQUIRED' });
    assert.equal(fixture.calls.length, 0);
  }
  const bridge = new NativeWebBridgeAdapter({ endpoint: 'http://127.0.0.1:17841' }, { fetchImpl: () => assert.fail('External sender reached native bridge') });
  await assert.rejects(bridge.respond({ authority: 'third_party', nativeRequest: { model: 'chatgpt-web/native',
    client_metadata: { 'x-codex-turn-metadata': 'native-request' } } }), { code: 'THIRD_PARTY_NATIVE_MODEL_DENIED' });
});

test('owned CODEX_HOME receives private native AGENTS instructions without duplicating them in stdin', async t => {
  const directory = await mkdtemp(path.join(tmpdir(), 'assistant-native-home-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const codeHome = path.join(directory, 'codex');
  const agentsPath = path.join(codeHome, 'AGENTS.md');
  const fixture = cliFixture();
  const adapter = new CodexCliAdapter({ codeHome }, fixture);
  const health = await adapter.health();
  assert.equal(health.instructionsSource, 'CODEX_HOME/AGENTS.md');
  assert.equal(health.nativeInstructionsManaged, true);
  assert.equal(health.nativeInstructionsLoadVerified, false);
  await assert.rejects(access(agentsPath), { code: 'ENOENT' });

  const instructions = '# Assistant\nFollow the owner’s sourced durable preferences.\n';
  await adapter.respond({ authority: 'owner', instructions, tools });
  assert.equal(await readFile(agentsPath, 'utf8'), instructions);
  assert.equal((await stat(codeHome)).mode & 0o777, 0o700);
  assert.equal((await stat(agentsPath)).mode & 0o777, 0o600);
  const first = fixture.calls.find(call => call.args[0] === 'exec');
  assert.equal(first.options.env.CODEX_HOME, codeHome);
  assert.ok(!first.input.includes(instructions));
  assert.ok(first.input.includes('Return exactly one JSON object'));
  assert.ok(first.input.includes('host application owns their execution'));

  const updated = '# Assistant\nThe owner’s current standing instruction supersedes the previous one.\n';
  await adapter.respond({ authority: 'autonomous', instructions: updated, tools });
  assert.equal(await readFile(agentsPath, 'utf8'), updated);
  assert.ok(!fixture.calls.filter(call => call.args[0] === 'exec').at(-1).input.includes(updated));
  assert.deepEqual(await readdir(codeHome), ['AGENTS.md']);
});

test('without an explicit owned CODEX_HOME instructions stay in stdin and no AGENTS file is managed', async t => {
  const directory = await mkdtemp(path.join(tmpdir(), 'assistant-native-fallback-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const fixture = cliFixture();
  const adapter = new CodexCliAdapter({ cwd: directory }, fixture);
  const health = await adapter.health();
  assert.equal(health.instructionsSource, 'stdin');
  assert.equal(health.nativeInstructionsManaged, false);
  const instructions = 'The owner’s fallback persona remains part of this explicit prompt.';
  await adapter.respond({ authority: 'owner', instructions, tools });
  assert.ok(fixture.calls.find(call => call.args[0] === 'exec').input.includes(instructions));
  assert.deepEqual(await readdir(directory), []);
});

test('disallowed senders and failed ChatGPT authentication cannot overwrite managed native instructions', async t => {
  const codeHome = await mkdtemp(path.join(tmpdir(), 'assistant-native-authority-test-'));
  t.after(() => rm(codeHome, { recursive: true, force: true }));
  const agentsPath = path.join(codeHome, 'AGENTS.md');
  const original = 'Existing owner instructions.';
  await writeFile(agentsPath, original);
  for (const authority of ['third_party', 'conversation', undefined]) {
    const fixture = cliFixture();
    const adapter = new CodexCliAdapter({ codeHome }, fixture);
    await assert.rejects(adapter.respond({ authority, instructions: 'Untrusted replacement.', tools }),
      { code: authority ? 'THIRD_PARTY_NATIVE_MODEL_DENIED' : 'MODEL_AUTHORITY_REQUIRED' });
    assert.equal(fixture.calls.length, 0);
    assert.equal(await readFile(agentsPath, 'utf8'), original);
  }
  const fixture = cliFixture({ login: 'Logged in using an API key' });
  const adapter = new CodexCliAdapter({ codeHome }, fixture);
  await assert.rejects(adapter.respond({ authority: 'owner', instructions: 'Unauthenticated replacement.', tools }),
    { code: 'CODEX_LOGIN_REQUIRED' });
  assert.equal(await readFile(agentsPath, 'utf8'), original);
  assert.deepEqual(await readdir(codeHome), ['AGENTS.md']);
  assert.ok(fixture.calls.every(call => call.args[0] === 'login'));
});

test('managed native instructions reject symlink destinations and CODEX_HOME parents', async t => {
  const directory = await mkdtemp(path.join(tmpdir(), 'assistant-native-symlink-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const codeHome = path.join(directory, 'codex');
  await mkdir(codeHome);
  const target = path.join(directory, 'owner-instructions.md');
  await writeFile(target, 'Never overwrite this unrelated file.');
  await symlink(target, path.join(codeHome, 'AGENTS.md'));
  const fixture = cliFixture();
  await assert.rejects(new CodexCliAdapter({ codeHome }, fixture).respond({ authority: 'owner', instructions: 'Replacement.', tools }),
    { code: 'NATIVE_INSTRUCTIONS_UNSAFE_PATH' });
  assert.equal(await readFile(target, 'utf8'), 'Never overwrite this unrelated file.');
  assert.deepEqual(await readdir(codeHome), ['AGENTS.md']);
  assert.ok(!fixture.calls.some(call => call.args[0] === 'exec'));

  const linkedParent = path.join(directory, 'linked');
  await symlink(directory, linkedParent);
  const parentFixture = cliFixture();
  await assert.rejects(new CodexCliAdapter({ codeHome: path.join(linkedParent, 'new-home') }, parentFixture)
    .respond({ authority: 'owner', instructions: 'Replacement.', tools }), { code: 'NATIVE_INSTRUCTIONS_UNSAFE_PATH' });
  await assert.rejects(access(path.join(directory, 'new-home')), { code: 'ENOENT' });
  assert.ok(!parentFixture.calls.some(call => call.args[0] === 'exec'));
});

test('nonempty native instruction overrides block changes and native turns without altering human files', async t => {
  const codeHome = await mkdtemp(path.join(tmpdir(), 'assistant-native-override-test-'));
  t.after(() => rm(codeHome, { recursive: true, force: true }));
  const agentsPath = path.join(codeHome, 'AGENTS.md');
  const overridePath = path.join(codeHome, 'AGENTS.override.md');
  await writeFile(agentsPath, 'Previous managed owner instructions.');
  await writeFile(overridePath, 'Human-owned higher-priority instructions.');
  const fixture = cliFixture();
  await assert.rejects(new CodexCliAdapter({ codeHome }, fixture)
    .respond({ authority: 'owner', instructions: 'New owner instructions.', tools }),
    error => error.code === 'NATIVE_INSTRUCTIONS_OVERRIDE_CONFLICT' && error.retryable === false && error.details.needsOwnerInput === true);
  assert.equal(await readFile(agentsPath, 'utf8'), 'Previous managed owner instructions.');
  assert.equal(await readFile(overridePath, 'utf8'), 'Human-owned higher-priority instructions.');
  assert.deepEqual((await readdir(codeHome)).sort(), ['AGENTS.md', 'AGENTS.override.md']);
  assert.ok(!fixture.calls.some(call => call.args[0] === 'exec'));
});

test('native instruction override symlinks are rejected even when their target is empty', async t => {
  const directory = await mkdtemp(path.join(tmpdir(), 'assistant-native-override-link-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const codeHome = path.join(directory, 'codex');
  await mkdir(codeHome);
  const target = path.join(directory, 'human-override.md');
  await writeFile(target, '');
  const overridePath = path.join(codeHome, 'AGENTS.override.md');
  await symlink(target, overridePath);
  const fixture = cliFixture();
  await assert.rejects(new CodexCliAdapter({ codeHome }, fixture)
    .respond({ authority: 'owner', instructions: 'New owner instructions.', tools }), { code: 'NATIVE_INSTRUCTIONS_OVERRIDE_CONFLICT' });
  assert.equal(await readFile(target, 'utf8'), '');
  assert.deepEqual(await readdir(codeHome), ['AGENTS.override.md']);
  await assert.rejects(access(path.join(codeHome, 'AGENTS.md')), { code: 'ENOENT' });
  assert.ok(!fixture.calls.some(call => call.args[0] === 'exec'));
});

test('an empty regular native instruction override permits managed AGENTS instructions and remains unchanged', async t => {
  const codeHome = await mkdtemp(path.join(tmpdir(), 'assistant-native-empty-override-test-'));
  t.after(() => rm(codeHome, { recursive: true, force: true }));
  const overridePath = path.join(codeHome, 'AGENTS.override.md');
  await writeFile(overridePath, '');
  const fixture = cliFixture();
  await new CodexCliAdapter({ codeHome }, fixture)
    .respond({ authority: 'owner', instructions: 'Current owner instructions.', tools });
  assert.equal(await readFile(path.join(codeHome, 'AGENTS.md'), 'utf8'), 'Current owner instructions.');
  assert.equal(await readFile(overridePath, 'utf8'), '');
  assert.ok(fixture.calls.some(call => call.args[0] === 'exec'));
});

test('owner planning disables supported native surfaces without claiming every native tool is absent', async () => {
  const fixture = cliFixture(); const adapter = new CodexCliAdapter({}, fixture);
  const status = await adapter.health();
  assert.equal(status.nativePlanningControlsVerified, true);
  assert.equal(status.nativeConversationOnly, false);
  assert.equal(status.thirdPartyConversationSupported, false);
  await adapter.respond({ tools, authority: 'autonomous' });
  const invocation = fixture.calls.find(call => call.args[0] === 'exec');
  for (const name of NATIVE_PLANNING_FEATURES_DISABLED) assert.ok(invocation.args.includes(`features.${name}=false`));
  assert.ok(invocation.args.includes('web_search="disabled"'));
  assert.ok(invocation.args.includes('--ignore-user-config'));
  assert.ok(invocation.args.includes('--ignore-rules'));
  assert.ok(invocation.args.includes('project_doc_max_bytes=0'));
  assert.equal(path.dirname(invocation.options.cwd), tmpdir());
  assert.ok(path.basename(invocation.options.cwd).startsWith('assistant-native-plan-'));
  await assert.rejects(access(invocation.options.cwd), { code: 'ENOENT' });
  const mismatch = cliFixture({ controlsMismatch: true });
  await assert.rejects(new CodexCliAdapter({}, mismatch).respond({ tools, authority: 'owner' }), { code: 'NATIVE_PLANNING_CONTROLS_UNVERIFIED' });
  assert.ok(!mismatch.calls.some(call => call.args[0] === 'exec'));
});

test('failed native turns also remove their isolated planning directory without reading the owner project', async t => {
  const codeHome = await mkdtemp(path.join(tmpdir(), 'assistant-native-failure-test-'));
  t.after(() => rm(codeHome, { recursive: true, force: true }));
  const fixture = cliFixture({ quota: true });
  const adapter = new CodexCliAdapter({ cwd: '/data/owner-private-project', codeHome }, fixture);
  await assert.rejects(adapter.respond({ tools, authority: 'owner' }), { code: 'MODEL_RATE_LIMIT' });
  const invocation = fixture.calls.find(call => call.args[0] === 'exec');
  assert.notEqual(invocation.options.cwd, '/data/owner-private-project');
  assert.equal(invocation.options.env.CODEX_HOME, codeHome);
  await assert.rejects(access(invocation.options.cwd), { code: 'ENOENT' });
});

test('malformed JSON, absent response, invented tools and duplicate call IDs fail explicitly', () => {
  for (const text of ['', 'I did it', '{}', JSON.stringify({ message: 'done', actions: [{ name: 'imaginary', arguments: {} }] }),
    JSON.stringify({ message: '', actions: [{ id: 'x', name: 'memory_add', arguments: {} }, { id: 'x', name: 'memory_add', arguments: {} }] })]) {
    assert.throws(() => parseAgentPlan(text, tools), ModelProtocolError);
  }
});

test('prompt retains complete supplied context and registered tool definitions', () => {
  const input = [{ role: 'user', content: 'Preserve this history.' }];
  const prompt = compileBrowserPrompt({ input, tools, requestId: 'test-request' });
  assert.ok(prompt.includes('[assistant-request:test-request]'));
  assert.ok(prompt.includes(JSON.stringify({ availableTools: tools, conversation: input })));
  assert.ok(prompt.includes('Never claim an action succeeded'));
});

test('disconnected browser never creates a fabricated model response', async () => {
  const adapter = new ChatGptBrowserAdapter();
  assert.equal((await adapter.health()).ready, false);
  await assert.rejects(adapter.respond({}), error => error instanceof ModelNotReadyError && error.code === 'BROWSER_UNAVAILABLE');
});

test('composer presence without a verified account is not a login or a response', async () => {
  const fixture = browserFixture({ authenticated: false });
  const adapter = new ChatGptBrowserAdapter({}, fixture);
  assert.equal((await adapter.health()).ready, false);
  await assert.rejects(adapter.respond({ tools }), { code: 'CHATGPT_LOGIN_REQUIRED' });
  assert.equal(fixture.submissions(), 0);
});

test('one authenticated, bound and completed browser response produces real normalized actions', async () => {
  const fixture = browserFixture();
  const adapter = new ChatGptBrowserAdapter({}, fixture);
  const result = await adapter.respond({ turnId: 'request-123', input: [{ role: 'user', content: 'remember' }], tools });
  assert.equal(fixture.submissions(), 1);
  assert.equal(result.id, 'assistant:new');
  assert.equal(result.toolCalls[0].name, 'memory_add');
});

test('browser health and requests replace old configured saved chat URLs with a fresh Temporary Chat', async () => {
  for (const url of ['https://chatgpt.com/', 'https://chatgpt.com/c/previous-conversation',
    'https://chatgpt.com/g/project/c/previous-conversation', 'https://chatgpt.com/?temporary-chat=true']) {
    const fixture = browserFixture({ initialUrl: url });
    const adapter = new ChatGptBrowserAdapter({ url }, fixture);
    const health = await adapter.health();
    assert.equal(health.ready, true);
    assert.equal(health.temporaryChatVerified, true);
    await adapter.respond({ tools });
    assert.deepEqual(fixture.navigations(), ['https://chatgpt.com/?temporary-chat=true', 'https://chatgpt.com/?temporary-chat=true']);
    assert.equal(fixture.fills(), 1);
    assert.equal(fixture.submissions(), 1);
  }
});

test('browser redirects to regular chats, other origins or ambiguous temporary URLs fail before inserting a prompt', async () => {
  for (const url of ['https://chatgpt.com/', 'https://chatgpt.com/?temporary-chat=false',
    'https://chatgpt.com/c/saved-chat?temporary-chat=true', 'https://chatgpt.com/g/project?temporary-chat=true',
    'https://example.com/?temporary-chat=true', 'about:blank', 'https://chatgpt.com/?temporary-chat=true&temporary-chat=false']) {
    const fixture = browserFixture({ transitions: { goto: url } });
    const adapter = new ChatGptBrowserAdapter({}, fixture);
    const health = await adapter.health();
    assert.equal(health.ready, false);
    assert.equal(health.temporaryChatVerified, false);
    assert.equal(health.state, 'temporary_chat_required');
    assert.equal(health.code, 'CHATGPT_TEMPORARY_CHAT_REQUIRED');
    await assert.rejects(adapter.respond({ tools }), error => error instanceof ModelNotReadyError
      && error.code === 'CHATGPT_TEMPORARY_CHAT_REQUIRED' && error.retryable === false);
    assert.equal(fixture.fills(), 0);
    assert.equal(fixture.submissions(), 0);
  }
});

test('a Temporary Chat lost during session or context observation is unready and receives no prompt', async () => {
  for (const stage of ['session', 'turns']) {
    const fixture = browserFixture({ transitions: { [stage]: 'https://chatgpt.com/c/saved-chat' } });
    const adapter = new ChatGptBrowserAdapter({}, fixture);
    assert.equal((await adapter.health()).state, 'temporary_chat_required');
    await assert.rejects(adapter.respond({ tools }), { code: 'CHATGPT_TEMPORARY_CHAT_REQUIRED' });
    assert.equal(fixture.fills(), 0);
    assert.equal(fixture.submissions(), 0);
  }
});

test('losing Temporary Chat while preparing the prompt or locating Send prevents the click', async () => {
  for (const stage of ['fill', 'sendCount']) {
    const fixture = browserFixture({ transitions: { [stage]: 'https://chatgpt.com/' } });
    const adapter = new ChatGptBrowserAdapter({}, fixture);
    await assert.rejects(adapter.respond({ tools }), { code: 'CHATGPT_TEMPORARY_CHAT_REQUIRED' });
    assert.equal(fixture.fills(), 1);
    assert.equal(fixture.submissions(), 0);
  }
});

test('invalid browser URL configuration cannot navigate, insert a prompt or send', async () => {
  for (const url of ['https://example.com/', 'https://user:password@chatgpt.com/', 'not-a-url']) {
    const fixture = browserFixture();
    const adapter = new ChatGptBrowserAdapter({ url }, fixture);
    assert.equal((await adapter.health()).ready, false);
    await assert.rejects(adapter.respond({ tools }), { code: 'MODEL_CONFIG_INVALID' });
    assert.deepEqual(fixture.navigations(), []);
    assert.equal(fixture.fills(), 0);
    assert.equal(fixture.submissions(), 0);
  }
});

test('model mismatch fails before Send without choosing a substitute', async () => {
  const fixture = browserFixture();
  const adapter = new ChatGptBrowserAdapter({ model: 'An unavailable model' }, fixture);
  assert.equal((await adapter.health()).state, 'model_not_verified');
  await assert.rejects(adapter.respond({ tools }), { code: 'MODEL_NOT_VERIFIED' });
  assert.equal(fixture.submissions(), 0);
});

test('requested Fast, max and preferred model cannot silently fall back without owner choice', async () => {
  const config = { preferences: { preferredModel: 'gpt-6.1-sol', reasoningEffort: 'max', speed: 'fast' }, allowFallback: false };
  const fixture = browserFixture();
  const adapter = new ChatGptBrowserAdapter(config, fixture);
  const status = await adapter.health();
  assert.equal(status.preferenceApplied, false);
  assert.equal(status.ready, false);
  assert.equal(status.needsOwnerInput, true);
  await assert.rejects(adapter.respond({ tools }), { code: 'MODEL_PREFERENCE_UNVERIFIED' });
  assert.equal(fixture.submissions(), 0);
  assert.equal(evaluateModelPreferences({ ...config, allowFallback: true }).needsOwnerInput, false);
  assert.equal(evaluateModelPreferences(config, { verifiedModel: 'gpt-6.1-sol', verifiedEffort: 'max', verifiedSpeed: 'fast' }).preferenceApplied, true);
});

test('accepted click followed by transport failure does not replay a submitted turn', async () => {
  const fixture = browserFixture({ pressError: true });
  const adapter = new ChatGptBrowserAdapter({}, fixture);
  await assert.rejects(adapter.respond({ tools }), error => error.retryable === false && error.details.submitted === true);
  assert.equal(fixture.submissions(), 1);
});

test('upstream healthz alone never establishes login and generic requests cannot invent a native harness', async () => {
  let requests = 0;
  const adapter = new NativeWebBridgeAdapter({ endpoint: 'http://127.0.0.1:17841' }, { fetchImpl: async () => {
    requests++;
    return Response.json({ status: 'ok', mode: 'full' });
  } });
  assert.equal((await adapter.health()).ready, false);
  await assert.rejects(adapter.respond({ instructions: 'hello', input: [] }), { code: 'CODEX_NATIVE_HARNESS_REQUIRED' });
  assert.equal(requests, 1);
});
