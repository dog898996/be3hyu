const KEY = 'hoyoung.pending-text.v1';
const validId = value => typeof value === 'string' && /^[A-Za-z0-9._:-]{1,160}$/.test(value);
const states = new Set(['pending', 'auth_required', 'conflict', 'rejected']);
function createMessageId() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  const bytes = globalThis.crypto?.getRandomValues(new Uint8Array(16));
  if (!bytes) throw new Error('이 브라우저에서 고유 메시지 ID를 만들 수 없습니다. 입력을 보존했으니 브라우저를 확인하세요.');
  bytes[6] = (bytes[6] & 0x0f) | 0x40; bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

// A transport receipt means accepted by the cloud queue, not finished by the model.
// Persist only message text/IDs; subscription or remote-access credentials never enter this queue.
export class PendingMessageQueue {
  constructor({ storage, send, canSend = () => true, makeId = createMessageId, now = () => new Date().toISOString(), onChange = () => {}, onAccepted = () => {} }) {
    this.storage = storage; this.send = send; this.canSend = canSend; this.makeId = makeId; this.now = now;
    this.onChange = onChange; this.onAccepted = onAccepted; this.flushing = null; this.loadError = null;
    try {
      const raw = storage?.getItem(KEY);
      const entries = raw ? JSON.parse(raw) : [];
      if (!Array.isArray(entries) || entries.some(entry => !validId(entry?.clientMessageId) || typeof entry.message !== 'string' || !entry.message.trim() || typeof entry.createdAt !== 'string' || !states.has(entry.state) || (entry.draftKey !== undefined && !['chat-message', 'quick-message'].includes(entry.draftKey)))) throw new Error('Invalid pending message data');
      if (new Set(entries.map(entry => entry.clientMessageId)).size !== entries.length) throw new Error('Duplicate pending message ids');
      this.entries = entries.map(({ clientMessageId, message, createdAt, state, draftKey }) => ({ clientMessageId, message, createdAt, state, ...(draftKey ? { draftKey } : {}) }));
    } catch { this.entries = []; this.loadError = new Error('저장된 전송 대기 메시지를 읽을 수 없습니다. 메시지를 지우지 않고 보존했습니다.'); }
    this.authBlocked = this.entries.some(entry => entry.state === 'auth_required');
  }

  list() { return this.entries.map(entry => ({ ...entry })); }

  save(entries) {
    if (this.loadError) throw this.loadError;
    if (!this.storage) throw new Error('기기 저장소를 사용할 수 없습니다. 입력을 보존했으니 저장소 설정을 확인하세요.');
    try { this.storage.setItem(KEY, JSON.stringify(entries)); }
    catch { throw new Error('전송 대기 메시지를 기기에 저장하지 못했습니다. 입력을 보존했으니 저장 공간을 확인하세요.'); }
    this.entries = entries;
    this.onChange(this.list());
  }

  enqueue(message, { clientMessageId = this.makeId(), draftKey } = {}) {
    message = typeof message === 'string' ? message.trim() : '';
    if (!message || !validId(clientMessageId)) throw new Error('올바른 메시지와 메시지 ID가 필요합니다.');
    const previous = this.entries.find(entry => entry.clientMessageId === clientMessageId);
    if (previous) { if (previous.message !== message) throw new Error('같은 메시지 ID의 내용은 바꿀 수 없습니다.'); return { ...previous }; }
    const entry = { clientMessageId, message, createdAt: this.now(), state: this.authBlocked ? 'auth_required' : 'pending', ...(['chat-message', 'quick-message'].includes(draftKey) ? { draftKey } : {}) };
    this.save([...this.entries, entry]);
    return { ...entry };
  }

  pauseForAuthentication() {
    this.authBlocked = true;
    const changed = this.entries.map(entry => entry.state === 'pending' ? { ...entry, state: 'auth_required' } : entry);
    if (changed.some((entry, index) => entry !== this.entries[index])) this.save(changed);
  }

  // Invoke only after an authenticated state fetch succeeds, not on a timer or online event.
  confirmConnection() {
    const changed = this.entries.map(entry => entry.state === 'auth_required' ? { ...entry, state: 'pending' } : entry);
    if (changed.some((entry, index) => entry !== this.entries[index])) this.save(changed);
    this.authBlocked = false;
  }

  acknowledge(entry, response) {
    if (!this.entries.some(item => item.clientMessageId === entry.clientMessageId)) return;
    this.save(this.entries.filter(item => item.clientMessageId !== entry.clientMessageId));
    this.onAccepted({ ...entry }, response);
  }

  reconcile(messages) {
    if (!Array.isArray(messages)) return;
    for (const entry of this.list()) {
      const saved = messages.find(message => message?.channel === 'web' && message?.externalId === `app:${entry.clientMessageId}`);
      if (!saved) continue;
      if (saved.role === 'user' && saved.text === entry.message) this.acknowledge(entry, { message: saved, reconciled: true });
      else this.save(this.entries.map(item => item.clientMessageId === entry.clientMessageId ? { ...item, state: 'conflict' } : item));
    }
  }

  flush() {
    if (this.flushing) return this.flushing;
    if (this.authBlocked || this.loadError || !this.canSend()) return Promise.resolve({ accepted: 0, blocked: this.authBlocked ? 'authentication' : this.loadError ? 'storage' : 'offline' });
    this.flushing = this.drain().finally(() => { this.flushing = null; });
    return this.flushing;
  }

  async drain() {
    let accepted = 0;
    while (!this.authBlocked && this.canSend()) {
      const entry = this.entries.find(item => item.state === 'pending');
      if (!entry) break;
      try {
        const response = await this.send({ message: entry.message, clientMessageId: entry.clientMessageId });
        const saved = response?.message;
        if (saved?.channel !== 'web' || saved?.externalId !== `app:${entry.clientMessageId}` || saved?.role !== 'user' || saved?.text !== entry.message) throw new Error('서버의 메시지 접수 확인을 받지 못했습니다. 같은 ID로 다시 확인합니다.');
        this.acknowledge(entry, response);
        accepted++;
      } catch (error) {
        const status = Number(error?.status);
        if (status === 401 || status === 403) this.pauseForAuthentication();
        else if (status === 409 || (status >= 400 && status < 500 && status !== 408 && status !== 429)) this.save(this.entries.map(item => item.clientMessageId === entry.clientMessageId ? { ...item, state: status === 409 ? 'conflict' : 'rejected' } : item));
        return { accepted, blocked: this.authBlocked ? 'authentication' : status >= 400 && status < 500 && status !== 408 && status !== 429 ? 'rejected' : 'connection', error };
      }
    }
    return { accepted };
  }
}
