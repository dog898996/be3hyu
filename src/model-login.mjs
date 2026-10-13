import { spawn } from 'node:child_process';

// Prompt format inspected from OpenAI Codex source:
// a9b0815167c27ab13719dd12d13b63e02b70d221/codex-rs/login/src/device_code_auth.rs
// device_code_prompt(), plus server.rs DEFAULT_ISSUER. No OAuth flow is reproduced.
// Codex owns token exchange/storage; this module exposes only its public device prompt.
const SECRET_OUTPUT = /(?:access[_-]token|refresh[_-]token|id[_-]token|authorization[_-]code|code[_-]verifier|\bBearer\s+\S+|\bsk-(?:proj-)?[A-Za-z0-9_-]{8,})/i;

export function parseDeviceAuthPrompt(raw) {
  const text = String(raw).replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '');
  if (SECRET_OUTPUT.test(text)) return { rejected: true };
  const urlMatch = /Open this link in your browser[^\n]*\n\s*(https:\/\/[^\s]+)/i.exec(text);
  const codeMatch = /Enter this one-time code[^\n]*\n\s*([A-Z0-9]{4}-[A-Z0-9]{4})\s*(?:\n|$)/i.exec(text);
  if (!urlMatch || !codeMatch) return null;
  let url;
  try { url = new URL(urlMatch[1]); } catch { return { rejected: true }; }
  if (url.origin !== 'https://auth.openai.com' || url.pathname !== '/codex/device'
    || url.username || url.password || url.search || url.hash) return { rejected: true };
  const expiration = /Enter this one-time code[^\n]*expires in (\d+) minutes/i.exec(text);
  const expiresInSeconds = expiration && Number(expiration[1]) > 0 && Number(expiration[1]) <= 60 ? Number(expiration[1]) * 60 : null;
  return { verificationUrl: url.href, userCode: codeMatch[1].toUpperCase(), expiresInSeconds };
}

/** start() is called only by the authenticated owner's login UI. The production VM
 * performs its own official Codex login; no development-account artifact is copied.
 * Constructor codeHome selects a persistent CODEX_HOME without exporting credentials.
 * A completed process requests a separate real health check; it never claims login
 * merely because a public code appeared, and never returns raw stdout/stderr.
 */
export class ModelLoginManager {
  constructor({ command = 'codex', codeHome, spawnImpl = spawn, now = Date.now, timeoutMs = 15 * 60 * 1000 } = {}) {
    Object.assign(this, { command, codeHome, spawnImpl, now, timeoutMs });
    this.child = null; this.timer = null; this.promptBuffer = '';
    this.state = { state: 'idle', authenticated: false, verificationUrl: null, userCode: null, errorCode: null };
  }

  status() { return { ...this.state }; }

  start({ command = this.command } = {}) {
    if (this.child) return this.status();
    if (typeof command !== 'string' || !command.trim()) {
      this.state = { state: 'failed', authenticated: false, verificationUrl: null, userCode: null, errorCode: 'DEVICE_LOGIN_COMMAND_INVALID' };
      return this.status();
    }
    this.promptBuffer = '';
    this.state = { state: 'starting', authenticated: false, verificationUrl: null, userCode: null, errorCode: null,
      startedAt: new Date(this.now()).toISOString() };
    let child;
    try {
      child = this.spawnImpl(command, ['login', '--device-auth'], { shell: false, stdio: ['ignore', 'pipe', 'pipe'],
        env: this.codeHome ? { ...process.env, CODEX_HOME: this.codeHome } : process.env });
    } catch { this.fail('DEVICE_LOGIN_UNAVAILABLE'); return this.status(); }
    this.child = child;
    this.timer = setTimeout(() => this.fail('DEVICE_LOGIN_TIMEOUT'), this.timeoutMs); this.timer.unref?.();
    child.stdout.on('data', chunk => {
      if (!['starting', 'awaiting_authorization'].includes(this.state.state)) return;
      this.promptBuffer = (this.promptBuffer + chunk.toString()).slice(-16384);
      const parsed = parseDeviceAuthPrompt(this.promptBuffer);
      if (parsed?.rejected) { this.fail('DEVICE_LOGIN_OUTPUT_REJECTED'); return; }
      if (!parsed || this.state.state === 'awaiting_authorization') return;
      this.state = { ...this.state, state: 'awaiting_authorization', verificationUrl: parsed.verificationUrl, userCode: parsed.userCode,
        expiresAt: parsed.expiresInSeconds ? new Date(this.now() + parsed.expiresInSeconds * 1000).toISOString() : null };
      // Public prompt text is unnecessary after extracting the two permitted fields.
      this.promptBuffer = '';
    });
    child.stderr.on('data', () => {});
    child.on('error', () => this.fail('DEVICE_LOGIN_UNAVAILABLE'));
    child.on('close', code => {
      clearTimeout(this.timer); this.timer = null; this.promptBuffer = '';
      if (this.child === child) this.child = null;
      if (['failed', 'cancelled'].includes(this.state.state)) return;
      this.state = { ...this.state, state: code === 0 ? 'completed' : 'failed', verificationUrl: null, userCode: null,
        expiresAt: null, errorCode: code === 0 ? null : 'DEVICE_LOGIN_FAILED', needsHealthCheck: code === 0,
        completedAt: new Date(this.now()).toISOString() };
    });
    return this.status();
  }

  stopProcess() {
    clearTimeout(this.timer); this.timer = null;
    if (!this.child) return;
    const child = this.child;
    child.kill('SIGTERM');
    const timer = setTimeout(() => { if (this.child === child) child.kill('SIGKILL'); }, 2000); timer.unref?.();
  }

  fail(errorCode) {
    this.state = { ...this.state, state: 'failed', authenticated: false, verificationUrl: null, userCode: null, expiresAt: null, errorCode };
    this.promptBuffer = ''; this.stopProcess();
  }

  cancel() {
    if (!this.child) return this.status();
    this.state = { ...this.state, state: 'cancelled', authenticated: false, verificationUrl: null, userCode: null, expiresAt: null, errorCode: null };
    this.promptBuffer = ''; this.stopProcess();
    return this.status();
  }
}
