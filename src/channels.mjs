import { randomUUID } from 'node:crypto';

export class ChannelNotReadyError extends Error {
  constructor(message, code = 'CHANNEL_NOT_READY', details = {}) {
    super(message);
    this.name = 'ChannelNotReadyError';
    this.code = code;
    this.retryable = details.retryable ?? true;
    this.details = details;
  }
}

export function canonicalInstagramUsername(value) {
  if (typeof value !== 'string') return null;
  const username = value.trim().replace(/^@/, '').toLowerCase();
  return /^[a-z0-9._]{1,30}$/.test(username) ? username : null;
}

export function instagramThread(value) {
  let url;
  try { url = new URL(value); } catch { throw new ChannelNotReadyError('Enter the complete Instagram DM thread URL.', 'INSTAGRAM_THREAD_INVALID', { retryable: false }); }
  const match = /^\/direct\/t\/(\d+)\/?$/.exec(url.pathname);
  if (url.protocol !== 'https:' || !['instagram.com', 'www.instagram.com'].includes(url.hostname)
    || url.username || url.password || !match) {
    throw new ChannelNotReadyError('Instagram thread URL must be https://www.instagram.com/direct/t/<thread ID>/.', 'INSTAGRAM_THREAD_INVALID', { retryable: false });
  }
  return { id: match[1], url: `https://www.instagram.com/direct/t/${match[1]}/` };
}

function numericId(value) { return typeof value === 'string' && /^\d+$/.test(value) ? value : Number.isSafeInteger(value) && value >= 0 ? String(value) : null; }
function timestampValue(value) {
  if (typeof value === 'string' && /^\d+$/.test(value)) return BigInt(value);
  if (Number.isSafeInteger(value) && value >= 0) return BigInt(value);
  return null;
}

export function instagramReadEvidence(thread, { viewerId, externalId, targetTimestamp } = {}) {
  const lastSeen = thread?.last_seen_at?.[viewerId];
  if (!lastSeen || typeof lastSeen !== 'object' || Array.isArray(lastSeen)) return null;
  if (lastSeen.item_id === externalId) return { viewerId, itemId: lastSeen.item_id, by: 'item_id', serverTimestamp: lastSeen.timestamp ?? null };
  const seenTime = timestampValue(lastSeen.timestamp), targetTime = timestampValue(targetTimestamp);
  if (seenTime !== null && targetTime !== null && targetTime > 0n && seenTime >= targetTime) {
    return { viewerId, itemId: lastSeen.item_id ?? null, by: 'timestamp', serverTimestamp: String(lastSeen.timestamp), targetTimestamp: String(targetTimestamp) };
  }
  return null;
}

/** Translate first-party thread evidence. Message text is never used for sender identity.
 * The caller persists message IDs, including non-text items, before execution or delivery.
 */
export function normalizeInstagramMessages(thread, { ownerUsername, ownerThreadId, viewerId }) {
  const threadId = numericId(thread?.thread_id);
  if (!threadId || !Array.isArray(thread.items) || !Array.isArray(thread.users)) {
    throw new ChannelNotReadyError('Instagram thread response has an unsupported schema.', 'INSTAGRAM_PROTOCOL_CHANGED', { retryable: false });
  }
  const users = new Map();
  for (const user of thread.users) {
    const id = numericId(user.pk ?? user.pk_id);
    const username = canonicalInstagramUsername(user.username);
    if (id && username) users.set(id, username);
  }
  const verifiedOwner = canonicalInstagramUsername(ownerUsername);
  const ownerInThread = [...users.values()].includes(verifiedOwner);
  if (threadId === ownerThreadId && !ownerInThread) {
    throw new ChannelNotReadyError('The configured owner is not a verified participant in the owner DM thread.', 'INSTAGRAM_OWNER_NOT_VERIFIED', { retryable: false });
  }
  const records = [];
  const seen = new Set();
  for (const item of thread.items) {
    if (typeof item.item_id !== 'string' || !item.item_id || seen.has(item.item_id)) {
      throw new ChannelNotReadyError('Instagram messages lack unique stable item IDs.', 'INSTAGRAM_PROTOCOL_CHANGED', { retryable: false });
    }
    seen.add(item.item_id);
    const senderId = numericId(item.user_id);
    if (senderId === viewerId) continue;
    const senderUsername = users.get(senderId) ?? null;
    const verifiedSender = !!senderId && !!senderUsername;
    const isOwner = verifiedSender && ownerInThread && threadId === ownerThreadId && senderUsername === verifiedOwner;
    const timestamp = Number(item.timestamp);
    const epochMs = timestamp > 1e14 ? Math.floor(timestamp / 1000) : timestamp;
    records.push({ id: `instagram:${threadId}:${item.item_id}`, externalId: item.item_id, channel: 'instagram',
      threadId: `https://www.instagram.com/direct/t/${threadId}/`, senderId, senderUsername,
      verifiedSender, isOwner, authority: isOwner ? 'owner' : 'conversation',
      text: typeof item.text === 'string' ? item.text : '', itemType: item.item_type ?? 'unknown',
      receivedAt: Number.isFinite(epochMs) && epochMs > 0 ? new Date(epochMs).toISOString() : null });
  }
  return records.reverse();
}

// In-page fetch keeps credentials inside the browser's persistent Instagram account.
// The direct_v2 endpoints are Instagram's browser/private protocol, not a supported
// public bot API. Schema/endpoint drift is reported explicitly and needs a live login test.
export async function readInstagramSession(page, { threadId, scanInbox = false, appId = '936619743392459', inboxLimit = 10, requestTimeoutMs = 10000 } = {}) {
  return page.evaluate(async ({ threadId, scanInbox, appId, inboxLimit, requestTimeoutMs }) => {
    if (!['https://www.instagram.com', 'https://instagram.com'].includes(location.origin)) {
      return { ok: false, reason: 'unexpected_origin' };
    }
    const request = async path => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), Math.max(1000, Math.min(10000, requestTimeoutMs)));
      try {
        const response = await fetch(path, { credentials: 'include', cache: 'no-store', redirect: 'error',
          headers: { accept: 'application/json', 'X-IG-App-ID': appId, 'X-Requested-With': 'XMLHttpRequest' }, signal: controller.signal });
        if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) {
          return { ok: false, reason: response.status === 401 || response.status === 403 ? 'login_or_checkpoint_required' : `instagram_http_${response.status}` };
        }
        const body = await response.json();
        if (body.status && body.status !== 'ok') return { ok: false, reason: body.message === 'login_required' ? 'login_required' : 'instagram_request_rejected' };
        return { ok: true, body };
      } catch { return { ok: false, reason: 'instagram_request_failed' }; }
      finally { clearTimeout(timer); }
    };
    const account = await request('/api/v1/accounts/current_user/?edit=true');
    if (!account.ok || !account.body?.user) return { ok: false, reason: account.reason ?? 'account_schema_changed' };
    // Retain only public account identity; no cookie, CSRF value or auth token leaves the page.
    const rawViewerId = account.body.user.pk ?? account.body.user.pk_id;
    const viewer = { id: typeof rawViewerId === 'string' ? rawViewerId
      : Number.isSafeInteger(rawViewerId) ? String(rawViewerId) : '', username: account.body.user.username ?? '' };
    const owner = await request(`/api/v1/direct_v2/threads/${encodeURIComponent(threadId)}/?limit=30`);
    if (!owner.ok || !owner.body?.thread || String(owner.body.thread.thread_id) !== String(threadId)
      || !Array.isArray(owner.body.thread.items) || !Array.isArray(owner.body.thread.users)) {
      return { ok: false, reason: owner.reason ?? 'thread_schema_changed', viewer };
    }
    const threads = [owner.body.thread];
    if (scanInbox) {
      const inbox = await request(`/api/v1/direct_v2/inbox/?limit=${Math.max(1, Math.min(20, inboxLimit))}&thread_message_limit=10`);
      if (!inbox.ok || !Array.isArray(inbox.body?.inbox?.threads)) return { ok: false, reason: inbox.reason ?? 'inbox_schema_changed', viewer };
      for (const thread of inbox.body.inbox.threads) {
        if (String(thread.thread_id) !== String(threadId)) threads.push(thread);
      }
    }
    return { ok: true, viewer, threads };
  }, { threadId, scanInbox, appId, inboxLimit, requestTimeoutMs });
}

/** BrowserTools.withPage('instagram', callback) serializes this account independently
 * of ChatGPT actions. poll() returns evidence, not commands; root verifies authority
 * and stores dedup/cursors. Root's durable outbox owns retries and uncertain attempts.
 */
export class InstagramDmChannel {
  constructor({ browser, config = {} } = {}) { this.browser = browser; this.config = config; this.name = 'instagram'; }

  configured() {
    if (!this.config.enabled) return null;
    const ownerUsername = canonicalInstagramUsername(this.config.ownerUsername);
    if (!ownerUsername) throw new ChannelNotReadyError('Configure the owner Instagram username before enabling DM commands.', 'INSTAGRAM_OWNER_REQUIRED', { retryable: false });
    const ownerThread = instagramThread(this.config.ownerThreadUrl);
    if (!this.browser?.withPage) throw new ChannelNotReadyError('Persistent Instagram browser is unavailable.', 'BROWSER_UNAVAILABLE');
    return { ownerUsername, ownerThread };
  }

  async withSession(callback, { scanInbox = false, targetThread, observer = true } = {}) {
    const configured = this.configured();
    if (!configured) throw new ChannelNotReadyError('Instagram DM channel is disabled.', 'CHANNEL_DISABLED', { retryable: false });
    return this.browser.withPage(observer ? 'instagram-observer' : 'instagram', async page => {
      const target = targetThread ?? configured.ownerThread;
      if (!['https://www.instagram.com', 'https://instagram.com'].includes(new URL(page.url()).origin)) {
        await page.goto(target.url, { waitUntil: 'domcontentloaded', timeout: 30000 });
      }
      const session = await readInstagramSession(page, { threadId: configured.ownerThread.id, scanInbox,
        appId: this.config.appId || '936619743392459', inboxLimit: this.config.inboxLimit ?? 10 });
      if (!session.ok) throw new ChannelNotReadyError(`Instagram account or DM protocol is unavailable (${session.reason}). Sign in and verify the browser session.`, 'INSTAGRAM_SESSION_UNVERIFIED', { reason: session.reason });
      if (!numericId(session.viewer.id) || !canonicalInstagramUsername(session.viewer.username)) throw new ChannelNotReadyError('Instagram current-account identity could not be verified.', 'INSTAGRAM_SESSION_UNVERIFIED');
      if (this.config.accountLabel && canonicalInstagramUsername(session.viewer.username) !== canonicalInstagramUsername(this.config.accountLabel)) {
        throw new ChannelNotReadyError('The signed-in Instagram account does not match the assigned assistant account.', 'INSTAGRAM_ACCOUNT_MISMATCH', { retryable: false });
      }
      // This validates the participant before sending to any thread as well as polling.
      normalizeInstagramMessages(session.threads[0], { ownerUsername: configured.ownerUsername,
        ownerThreadId: configured.ownerThread.id, viewerId: session.viewer.id });
      return callback(page, session, configured);
    });
  }

  async inspect() {
    if (!this.config.enabled) return { channel: this.name, configured: false, ready: false, state: 'disabled' };
    try {
      return await this.withSession(async (_page, session, configured) => ({ channel: this.name, configured: true,
        ready: true, authenticated: true, state: 'ready', accountUsername: canonicalInstagramUsername(session.viewer.username),
        ownerUsername: configured.ownerUsername, ownerThreadUrl: configured.ownerThread.url }));
    } catch (error) { return { channel: this.name, configured: true, ready: false, authenticated: false, state: error.code ?? 'unavailable', message: error.message }; }
  }

  async poll() {
    if (!this.config.enabled) return [];
    return this.withSession(async (_page, session, configured) => session.threads.flatMap(thread => normalizeInstagramMessages(thread, {
      ownerUsername: configured.ownerUsername, ownerThreadId: configured.ownerThread.id, viewerId: session.viewer.id,
    })), { scanInbox: this.config.observeInbox !== false });
  }

  /** Enter the actual conversation as a separate, owner-authorized read effect.
   * This never sends a message or calls a private read/write endpoint. A fresh
   * server last_seen_at for the assigned account alone confirms the read; DOM
   * navigation without that evidence stays unverified. Root owns the durable
   * read queue, independently of model availability, quota and outbound delivery.
   */
  async markRead({ threadId, externalId, signal } = {}) {
    const configured = this.configured();
    if (!configured) throw new ChannelNotReadyError('Instagram DM channel is disabled.', 'CHANNEL_DISABLED', { retryable: false });
    const target = instagramThread(threadId);
    if (typeof externalId !== 'string' || !externalId) throw new ChannelNotReadyError('Read confirmation requires a stable incoming item ID.', 'MESSAGE_INVALID', { retryable: false });
    const assignedAccount = canonicalInstagramUsername(this.config.accountLabel);
    const base = { channel: this.name, threadId: target.url, externalId, verified: false, entered: false };
    if (!assignedAccount) return { ...base, state: 'unverified', reason: 'assigned_account_not_configured' };
    return this.browser.withPage('instagram-reader', async page => {
      const deadline = Date.now() + Math.max(3000, Math.min(30000, Number(this.config.readTimeoutSeconds || 15) * 1000));
      let entered = false;
      const pending = reason => ({ ...base, entered, state: 'pending', reason });
      const unverified = reason => ({ ...base, entered, state: 'unverified', reason });
      const session = async () => {
        signal?.throwIfAborted();
        const remaining = deadline - Date.now();
        if (remaining < 2000) throw new ChannelNotReadyError('Read confirmation deadline reached.', 'READ_CONFIRMATION_TIMEOUT');
        return readInstagramSession(page, { threadId: target.id, appId: this.config.appId || '936619743392459',
          requestTimeoutMs: Math.min(3000, Math.floor(remaining / 2)) });
      };
      try {
        signal?.throwIfAborted();
        if (!['https://www.instagram.com', 'https://instagram.com'].includes(new URL(page.url()).origin)) {
          // Establish the account on a neutral page before entering a target thread.
          await page.goto('https://www.instagram.com/', { waitUntil: 'domcontentloaded', timeout: Math.min(5000, deadline - Date.now()) });
        }
        const before = await session();
        if (!before.ok) return unverified(before.reason ?? 'instagram_session_unverified');
        const viewerId = numericId(before.viewer?.id), accountUsername = canonicalInstagramUsername(before.viewer?.username);
        const beforeThread = before.threads?.[0];
        if (!viewerId || accountUsername !== assignedAccount) return unverified('assigned_account_mismatch');
        if (String(beforeThread?.thread_id) !== target.id || !Array.isArray(beforeThread.items)) return unverified('target_thread_unverified');
        const item = beforeThread.items.find(item => item.item_id === externalId);
        if (!item) return unverified('incoming_item_not_observed');
        if (numericId(item.user_id) === viewerId) return unverified('target_item_is_outbound');
        signal?.throwIfAborted();
        if (deadline - Date.now() < 2000) return pending('read_confirmation_timeout');
        // Instagram's normal read behavior may require the document to be active.
        // This reader tab is independent of observation, sending and browser actions.
        let focusTimer;
        try {
          await Promise.race([page.bringToFront(), new Promise((_, reject) => {
            focusTimer = setTimeout(() => reject(new ChannelNotReadyError('Read page focus timed out.', 'READ_CONFIRMATION_TIMEOUT')), deadline - Date.now());
          })]);
        } finally { clearTimeout(focusTimer); }
        signal?.throwIfAborted();
        if (deadline - Date.now() < 2000) return pending('read_confirmation_timeout');
        // The browser's normal thread entry performs Instagram's own read behavior.
        // A navigation timeout may still have entered it, so retain that uncertainty.
        entered = true;
        await page.goto(target.url, { waitUntil: 'domcontentloaded', timeout: Math.min(10000, deadline - Date.now()) });
        if (instagramThread(page.url()).id !== target.id) return unverified('target_navigation_unverified');
        for (let attempt = 0; attempt < 3; attempt++) {
          const after = await session();
          if (!after.ok) return unverified(after.reason ?? 'read_receipt_unavailable');
          if (numericId(after.viewer?.id) !== viewerId || canonicalInstagramUsername(after.viewer?.username) !== assignedAccount) {
            return unverified('assigned_account_changed');
          }
          const afterThread = after.threads?.[0];
          if (String(afterThread?.thread_id) !== target.id) return unverified('target_thread_changed');
          const receipt = instagramReadEvidence(afterThread, { viewerId, externalId, targetTimestamp: item.timestamp });
          if (receipt) return { ...base, entered: true, state: 'confirmed', verified: true, accountUsername,
            receipt, confirmedAt: new Date().toISOString() };
          if (deadline - Date.now() < 2500) return pending('read_confirmation_timeout');
          signal?.throwIfAborted();
          await page.waitForTimeout(250);
        }
        return unverified('viewer_last_seen_at_unavailable');
      } catch (error) {
        if (signal?.aborted || error.name === 'AbortError') return pending('read_confirmation_interrupted');
        if (error.code === 'READ_CONFIRMATION_TIMEOUT' || error.name === 'TimeoutError') return pending('read_confirmation_timeout');
        return unverified(error.code ?? 'read_confirmation_failed');
      }
    });
  }

  async send({ threadId, text, idempotencyKey = randomUUID(), signal } = {}) {
    if (typeof text !== 'string' || !text.trim()) throw new ChannelNotReadyError('A DM requires non-empty message text.', 'MESSAGE_INVALID', { retryable: false });
    const target = instagramThread(threadId || this.config.ownerThreadUrl);
    return this.withSession(async (page, _session, configured) => {
      let attempted = false;
      try {
        signal?.throwIfAborted();
        await page.goto(target.url, { waitUntil: 'domcontentloaded', timeout: 30000 });
        // Obtain stable pre-effect IDs from the actual destination, independently of owner thread.
        const before = await readInstagramSession(page, { threadId: target.id, appId: this.config.appId });
        if (!before.ok || !before.threads?.[0]) throw new ChannelNotReadyError('Destination DM thread could not be verified.', 'INSTAGRAM_DESTINATION_UNVERIFIED');
        const baselineIds = new Set(before.threads[0].items.map(item => item.item_id));
        const selector = this.config.composerSelector || 'textarea[placeholder="Message..."], textarea[placeholder="메시지 보내기..."], textarea[aria-label="Message"], [contenteditable="true"][role="textbox"]';
        const composer = page.locator(selector).filter({ visible: true });
        await composer.first().waitFor({ state: 'visible', timeout: 15000 });
        if (await composer.count() !== 1) throw new ChannelNotReadyError('Instagram composer is not uniquely identifiable. Configure its observed selector.', 'INSTAGRAM_DOM_CHANGED', { retryable: false });
        await composer.fill(text);
        signal?.throwIfAborted();
        // Enter is the first effect boundary. A failed press may still have sent the message.
        attempted = true;
        await composer.press('Enter', { timeout: 10000 });
        for (let attempt = 0; attempt < 5; attempt++) {
          signal?.throwIfAborted();
          const after = await readInstagramSession(page, { threadId: target.id, appId: this.config.appId });
          if (after.ok && after.viewer.id === before.viewer.id) {
            const matches = after.threads[0].items.filter(item => typeof item.item_id === 'string'
              && !baselineIds.has(item.item_id) && numericId(item.user_id) === before.viewer.id && item.text === text);
            if (matches.length === 1) return { state: 'confirmed', delivered: true, channel: this.name,
              threadId: target.url, externalId: matches[0].item_id, idempotencyKey, confirmedAt: new Date().toISOString() };
            if (matches.length > 1) break;
          }
          await page.waitForTimeout(500);
        }
        return { state: 'uncertain', delivered: false, channel: this.name, threadId: target.url, idempotencyKey,
          message: 'The send was attempted but no unique server receipt was confirmed. Inspect this thread before retrying.' };
      } catch (error) {
        if (!attempted) throw error;
        return { state: 'uncertain', delivered: false, channel: this.name, threadId: target.url, idempotencyKey,
          message: 'The send may have been accepted. Inspect the thread before retrying.', error: error.code ?? error.name };
      }
    }, { targetThread: target, observer: false });
  }
}

export function createInstagramChannel(config, { browser } = {}) { return new InstagramDmChannel({ config, browser }); }
