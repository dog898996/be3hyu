import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';

const MAX_BUFFER_BYTES = 8 * 1024 * 1024;
const MAX_PENDING_REQUESTS = 128;
const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
const NATIVE_ERROR_KINDS = new Set(['contextWindowExceeded', 'sessionBudgetExceeded', 'usageLimitExceeded',
  'rateLimitExceeded', 'flexUnavailable', 'serverOverloaded', 'cyberPolicy', 'misalignmentPolicyViolation',
  'tooManyDenials', 'httpConnectionFailed', 'responseStreamConnectionFailed', 'internalServerError',
  'unauthorized', 'badRequest', 'threadRollbackFailed', 'sandboxError', 'responseStreamDisconnected',
  'responseTooManyFailedAttempts', 'activeTurnNotSteerable', 'other']);
function nativeErrorKind(value) {
  if (typeof value === 'string') return NATIVE_ERROR_KINDS.has(value) ? value : undefined;
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const keys = Object.keys(value);
  return keys.length === 1 && NATIVE_ERROR_KINDS.has(keys[0]) ? keys[0] : undefined;
}

/** Errors deliberately omit native stderr, RPC error data, credentials and paths. */
export class CodexTransportError extends Error {
  constructor(code, message, { rpcCode, codexErrorInfo } = {}) {
    super(message);
    this.name = code === 'CODEX_REQUEST_ABORTED' ? 'AbortError' : 'CodexTransportError';
    this.code = code;
    if (Number.isSafeInteger(rpcCode)) this.rpcCode = rpcCode;
    const kind = nativeErrorKind(codexErrorInfo);
    if (kind) this.codexErrorInfo = kind;
  }
}

const aborted = () => new CodexTransportError('CODEX_REQUEST_ABORTED', 'The Codex request was cancelled.');
const disconnected = () => new CodexTransportError('CODEX_TRANSPORT_DISCONNECTED', 'The Codex connection ended before a response.');
const protocolError = () => new CodexTransportError('CODEX_TRANSPORT_PROTOCOL', 'The Codex connection returned invalid protocol data.');

/**
 * One official `codex app-server` subprocess, shared by all native requests.
 * This is a transport, not a model runner: start() only performs initialization.
 * Notification delivery is immediate; the caller routes/buffers thread events.
 */
export class CodexAppServerClient extends EventEmitter {
  constructor({ command = 'codex', codeHome, cwd = process.cwd(), spawnImpl = spawn,
    requestTimeoutMs = 30000, closeTimeoutMs = 1000, experimentalApi = false } = {}) {
    super();
    if (typeof command !== 'string' || !command.trim() || typeof spawnImpl !== 'function') {
      throw new TypeError('A Codex command and subprocess launcher are required.');
    }
    if (typeof experimentalApi !== 'boolean') throw new TypeError('Native experimental API opt-in must be a boolean.');
    for (const value of [requestTimeoutMs, closeTimeoutMs]) {
      if (!Number.isSafeInteger(value) || value < 1 || value > 300000) {
        throw new TypeError('Codex transport timeouts must be bounded positive milliseconds.');
      }
    }
    this.command = command;
    this.codeHome = codeHome;
    this.cwd = cwd;
    this.spawnImpl = spawnImpl;
    this.requestTimeoutMs = requestTimeoutMs;
    this.closeTimeoutMs = closeTimeoutMs;
    this.experimentalApi = experimentalApi;
    this._session = null;
    this._startPromise = null;
    this._closePromise = null;
    this._terminationPromise = null;
    this._nextId = 1;
    this._pending = new Map();
    this._serverRequests = new Set();
    this._serverRequestHandler = null;
    this._ready = false;
    this._initializeResult = null;
  }

  get connected() { return this._session !== null; }
  get ready() { return this._ready && this.connected; }
  get state() { return { connected: this.connected, ready: this.ready }; }

  start() {
    if (this.ready) return Promise.resolve(this._initializeResult);
    if (this._startPromise) return this._startPromise;
    const cleanup = this._closePromise || this._terminationPromise;
    const operation = cleanup
      ? cleanup.then(() => this._initialize())
      : this._initialize();
    this._startPromise = operation;
    operation.then(() => {
      if (this._startPromise === operation) this._startPromise = null;
    }, () => {
      if (this._startPromise === operation) this._startPromise = null;
    });
    return operation;
  }

  async _initialize() {
    let child;
    try {
      child = this.spawnImpl(this.command, ['app-server', '--listen', 'stdio://'], {
        cwd: this.cwd,
        stdio: ['pipe', 'pipe', 'pipe'],
        shell: false,
        env: this.codeHome ? { ...process.env, CODEX_HOME: this.codeHome } : { ...process.env },
      });
    } catch {
      throw new CodexTransportError('CODEX_TRANSPORT_START', 'The Codex subprocess could not be started.');
    }
    const session = { child, buffer: Buffer.alloc(0), terminating: null };
    this._session = session;
    this._ready = false;
    const onError = () => {
      if (this._session === session) this._disconnect(session, disconnected());
    };
    child.on('error', onError);
    child.on('close', () => {
      if (this._session === session) this._disconnect(session, disconnected(), false);
    });
    if (!child.stdout || !child.stdin || !child.stderr || typeof child.stdin.write !== 'function') {
      const error = new CodexTransportError('CODEX_TRANSPORT_START', 'The Codex subprocess pipes are unavailable.');
      this._disconnect(session, error);
      throw error;
    }
    child.stdout.on('data', chunk => this._read(session, chunk));
    child.stdout.on('error', onError);
    child.stdout.on('end', onError);
    // Drain stderr without retaining or publishing potentially private native logs.
    child.stderr.on('data', () => {});
    child.stderr.on('error', () => {});
    child.stdin.on('error', onError);
    try {
      const result = await this._request('initialize', {
        clientInfo: { name: 'hoyoung_private_app', title: '호영이', version: '0.1.0' },
        capabilities: { experimentalApi: this.experimentalApi },
      }, { timeoutMs: this.requestTimeoutMs });
      if (this._session !== session) throw disconnected();
      // The executable's native default session source is retained; no source spoofing.
      this._write({ method: 'initialized' });
      this._initializeResult = result;
      this._ready = true;
      return result;
    } catch (error) {
      if (this._session === session) this._disconnect(session, error);
      throw error;
    }
  }

  async request(method, params, { timeoutMs = this.requestTimeoutMs, signal } = {}) {
    if (signal?.aborted) throw aborted();
    await this._awaitInitialization(this.start(), signal);
    return this._request(method, params, { timeoutMs, signal });
  }

  _awaitInitialization(operation, signal) {
    if (!signal) return operation;
    return new Promise((resolve, reject) => {
      const onAbort = () => { signal.removeEventListener('abort', onAbort); reject(aborted()); };
      signal.addEventListener('abort', onAbort, { once: true });
      if (signal.aborted) onAbort();
      operation.then(result => {
        signal.removeEventListener('abort', onAbort);
        resolve(result);
      }, error => {
        signal.removeEventListener('abort', onAbort);
        reject(error);
      });
    });
  }

  _request(method, params, { timeoutMs = this.requestTimeoutMs, signal } = {}) {
    if (typeof method !== 'string' || !method || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 300000) {
      return Promise.reject(new TypeError('A Codex RPC method and bounded timeout are required.'));
    }
    if (signal?.aborted) return Promise.reject(aborted());
    if (!this.connected) return Promise.reject(disconnected());
    if (this._pending.size >= MAX_PENDING_REQUESTS || this._nextId >= Number.MAX_SAFE_INTEGER) {
      return Promise.reject(new CodexTransportError('CODEX_TRANSPORT_CAPACITY', 'Too many Codex requests are waiting.'));
    }
    const id = this._nextId++;
    return new Promise((resolve, reject) => {
      const entry = { resolve, reject, timer: null, signal, onAbort: null };
      entry.timer = setTimeout(() => this._finish(id, new CodexTransportError(
        'CODEX_REQUEST_TIMEOUT', 'The Codex request timed out; native work may still be running.')), timeoutMs);
      entry.onAbort = () => this._finish(id, aborted());
      this._pending.set(id, entry);
      signal?.addEventListener('abort', entry.onAbort, { once: true });
      if (signal?.aborted) { entry.onAbort(); return; }
      try {
        this._write({ id, method, ...(params === undefined ? {} : { params }) });
      } catch (error) {
        this._finish(id, error);
      }
    });
  }

  /** Sends a protocol notification; it does not start a model turn. */
  notify(method, params) {
    if (typeof method !== 'string' || !method) throw new TypeError('A Codex notification method is required.');
    this._write({ method, ...(params === undefined ? {} : { params }) });
  }

  /** Respond to a native server request, such as an explicitly handled approval. */
  respond(id, result) {
    if (!this._serverRequests.has(id)) return false;
    this._write({ id, result: result === undefined ? null : result });
    this._serverRequests.delete(id);
    return true;
  }

  setServerRequestHandler(handler) {
    if (handler !== null && typeof handler !== 'function') throw new TypeError('A native server request handler must be a function.');
    this._serverRequestHandler = handler;
    return this;
  }

  _write(message) {
    const session = this._session;
    if (!session) throw disconnected();
    let data;
    try { data = Buffer.from(JSON.stringify(message) + '\n'); } catch {
      throw new CodexTransportError('CODEX_TRANSPORT_SERIALIZE', 'The Codex message could not be encoded.');
    }
    if (data.length > MAX_BUFFER_BYTES || (session.child.stdin.writableLength || 0) + data.length > MAX_BUFFER_BYTES) {
      throw new CodexTransportError('CODEX_TRANSPORT_CAPACITY', 'The Codex message buffer is full.');
    }
    try {
      session.child.stdin.write(data, error => {
        if (error && this._session === session) this._disconnect(session, disconnected());
      });
    } catch {
      const error = disconnected();
      this._disconnect(session, error);
      throw error;
    }
  }

  _read(session, chunk) {
    if (this._session !== session) return;
    const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    if (bytes.length + session.buffer.length > MAX_BUFFER_BYTES) {
      this._disconnect(session, new CodexTransportError('CODEX_TRANSPORT_OVERFLOW', 'The Codex protocol message exceeded its buffer limit.'));
      return;
    }
    session.buffer = Buffer.concat([session.buffer, bytes]);
    let end;
    while (this._session === session && (end = session.buffer.indexOf(10)) !== -1) {
      const line = session.buffer.subarray(0, end);
      session.buffer = session.buffer.subarray(end + 1);
      if (!line.toString('utf8').trim()) continue;
      let message;
      try { message = JSON.parse(line.toString('utf8')); } catch {
        this._disconnect(session, protocolError());
        return;
      }
      this._dispatch(session, message);
    }
  }

  _dispatch(session, message) {
    if (!message || typeof message !== 'object' || Array.isArray(message)) {
      this._disconnect(session, protocolError());
      return;
    }
    if (typeof message.method === 'string' && message.method) {
      if (!hasOwn(message, 'id')) {
        this.emit('notification', { method: message.method, params: message.params });
        return;
      }
      if (!(typeof message.id === 'string' || Number.isSafeInteger(message.id)) || this._serverRequests.has(message.id)) {
        this._disconnect(session, protocolError());
        return;
      }
      if (this._serverRequests.size >= MAX_PENDING_REQUESTS) {
        this._disconnect(session, new CodexTransportError('CODEX_TRANSPORT_CAPACITY', 'Too many native server requests are waiting.'));
        return;
      }
      this._serverRequests.add(message.id);
      const handler = this._serverRequestHandler;
      if (!handler) {
        this._respondError(session, message.id, -32601, 'No handler is configured for this native server request.');
        return;
      }
      Promise.resolve().then(() => handler(message.method, message.params)).then(result => {
        if (this._session === session && this._serverRequests.has(message.id)) this.respond(message.id, result);
      }).catch(() => {
        this._respondError(session, message.id, -32603, 'The native server request handler failed.');
      });
      return;
    }
    if (!Number.isSafeInteger(message.id) || hasOwn(message, 'result') === hasOwn(message, 'error')) {
      this._disconnect(session, protocolError());
      return;
    }
    if (hasOwn(message, 'error')) {
      this._finish(message.id, new CodexTransportError('CODEX_RPC_ERROR', 'The Codex request failed.', {
        rpcCode: message.error?.code,
        codexErrorInfo: message.error?.data?.codexErrorInfo,
      }));
    } else this._finish(message.id, null, message.result);
  }

  _respondError(session, id, code, message) {
    if (this._session !== session || !this._serverRequests.has(id)) return;
    try { this._write({ id, error: { code, message } }); } catch {
      if (this._session === session) this._disconnect(session, disconnected());
    }
    this._serverRequests.delete(id);
  }

  _finish(id, error, result) {
    const entry = this._pending.get(id);
    if (!entry) return; // Late responses after timeout/cancellation cannot complete another request.
    this._pending.delete(id);
    clearTimeout(entry.timer);
    entry.signal?.removeEventListener('abort', entry.onAbort);
    if (error) entry.reject(error);
    else entry.resolve(result);
  }

  _disconnect(session, error, terminate = true, emit = true) {
    if (this._session !== session) return;
    this._session = null;
    this._ready = false;
    this._initializeResult = null;
    session.buffer = Buffer.alloc(0);
    for (const id of [...this._pending.keys()]) this._finish(id, error);
    this._serverRequests.clear();
    if (terminate) this._trackTermination(this._terminate(session));
    if (emit) this.emit('disconnect', error);
  }

  _terminate(session) {
    if (session.terminating) return session.terminating;
    session.terminating = new Promise(resolve => {
      let timer;
      const done = () => {
        clearTimeout(timer);
        session.child.removeListener('close', done);
        resolve();
      };
      session.child.once('close', done);
      timer = setTimeout(() => {
        try { session.child.kill('SIGKILL'); } catch {}
        done();
      }, this.closeTimeoutMs);
      try { session.child.stdin?.end(); } catch {}
      try { session.child.kill('SIGTERM'); } catch {}
      if (session.child.exitCode !== null && session.child.exitCode !== undefined) done();
    });
    return session.terminating;
  }

  _trackTermination(operation) {
    this._terminationPromise = operation;
    operation.then(() => {
      if (this._terminationPromise === operation) this._terminationPromise = null;
    });
    return operation;
  }

  close() {
    if (this._closePromise) return this._closePromise;
    const session = this._session;
    if (!session) return this._terminationPromise || Promise.resolve();
    this._disconnect(session, new CodexTransportError('CODEX_TRANSPORT_CLOSED', 'The Codex transport was explicitly closed.'), false, false);
    const operation = this._trackTermination(this._terminate(session));
    this._closePromise = operation;
    operation.then(() => { if (this._closePromise === operation) this._closePromise = null; });
    return operation;
  }
}
