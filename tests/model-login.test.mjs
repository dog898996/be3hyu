import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { ModelLoginManager, parseDeviceAuthPrompt } from '../src/model-login.mjs';

// Exact public formatter shape from OpenAI Codex device_code_prompt(), not an issued code.
const prompt = '\nWelcome to Codex [v\x1b[90m0.159.2\x1b[0m]\n'
  + '\nFollow these steps to sign in with ChatGPT using device code authorization:\n'
  + '\n1. Open this link in your browser and sign in to your account\n   \x1b[94mhttps://auth.openai.com/codex/device\x1b[0m\n'
  + '\n2. Enter this one-time code \x1b[90m(expires in 15 minutes)\x1b[0m\n   \x1b[94mABCD-EFGH\x1b[0m\n'
  + '\nContinue only if you started this login in Codex. If a website or another person gave you this code, cancel.\n';

function fixture() {
  const calls = [];
  const spawnImpl = (command, args, options) => {
    const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
    child.kills = []; child.kill = signal => child.kills.push(signal);
    calls.push({ command, args, options, child }); return child;
  };
  return { calls, manager: new ModelLoginManager({ spawnImpl, codeHome: '/data/codex', now: () => Date.parse('2026-01-01T00:00:00Z') }) };
}

test('parser recognizes only the observed official public code formatter', () => {
  assert.deepEqual(parseDeviceAuthPrompt(prompt), { verificationUrl: 'https://auth.openai.com/codex/device', userCode: 'ABCD-EFGH', expiresInSeconds: 900 });
  assert.equal(parseDeviceAuthPrompt('Some URL https://auth.openai.com and arbitrary ABCD-EFGH'), null);
  assert.deepEqual(parseDeviceAuthPrompt(prompt.replace('auth.openai.com', 'attacker.example')), { rejected: true });
});

test('owner login starts one official CLI process and handles chunked public prompts', () => {
  const { manager, calls } = fixture();
  assert.equal(manager.start().state, 'starting');
  manager.start(); assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].args, ['login', '--device-auth']);
  assert.equal(calls[0].options.shell, false);
  assert.equal(calls[0].options.env.CODEX_HOME, '/data/codex');
  calls[0].child.stdout.write(prompt.slice(0, 170));
  calls[0].child.stdout.write(prompt.slice(170));
  assert.equal(manager.status().state, 'awaiting_authorization');
  assert.equal(manager.status().userCode, 'ABCD-EFGH');
  assert.equal(manager.status().expiresAt, '2026-01-01T00:15:00.000Z');
  assert.equal(manager.status().authenticated, false);
  manager.cancel(); calls[0].child.emit('close', 1);
});

test('successful exit requests real health verification without exposing tokens or assuming auth', () => {
  const { manager, calls } = fixture(); manager.start();
  calls[0].child.stdout.write(prompt); calls[0].child.emit('close', 0);
  assert.equal(manager.status().state, 'completed');
  assert.equal(manager.status().needsHealthCheck, true);
  assert.equal(manager.status().authenticated, false);
  assert.equal(manager.status().userCode, null);
});

test('token-like stdout is rejected and raw stderr or output never reaches public status', () => {
  const { manager, calls } = fixture(); manager.start();
  calls[0].child.stderr.write('private diagnostic with secret-value');
  calls[0].child.stdout.write(prompt + '\naccess_token: private-token-value\n');
  assert.equal(manager.status().errorCode, 'DEVICE_LOGIN_OUTPUT_REJECTED');
  assert.ok(!JSON.stringify(manager.status()).includes('private'));
  assert.equal(manager.status().userCode, null);
  assert.ok(calls[0].child.kills.includes('SIGTERM'));
  calls[0].child.emit('close', 1);
});

test('cancel and login failure clear public codes and preserve generic outcomes', () => {
  const { manager, calls } = fixture(); manager.start(); calls[0].child.stdout.write(prompt);
  assert.equal(manager.cancel().state, 'cancelled');
  assert.equal(manager.status().userCode, null);
  calls[0].child.emit('close', 0);
  assert.equal(manager.status().state, 'cancelled');
  manager.start(); calls[1].child.stderr.write('arbitrary raw secret error'); calls[1].child.emit('close', 2);
  assert.equal(manager.status().errorCode, 'DEVICE_LOGIN_FAILED');
  assert.ok(!JSON.stringify(manager.status()).includes('secret'));
});
