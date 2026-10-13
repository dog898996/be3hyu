import { DatabaseSync } from 'node:sqlite';
import { randomUUID } from 'node:crypto';
import { mkdirSync, rmSync } from 'node:fs';
import { isAbsolute, relative, resolve, sep } from 'node:path';

const MEMORY_KINDS = new Set(['fact', 'inference', 'opinion', 'uncertain']);
const TASK_STATES = new Set(['queued', 'running', 'succeeded', 'failed', 'paused', 'waiting']);
const OUTBOUND_STATES = new Set(['pending', 'sending', 'sent', 'failed', 'uncertain']);
const ACTION_STATES = new Set(['running', 'succeeded', 'failed', 'uncertain']);
const READ_STATES = new Set(['pending', 'reading', 'confirmed', 'unverified', 'failed']);
const json = (value) => JSON.stringify(value ?? null);
const parse = (value, fallback = null) => value == null ? fallback : JSON.parse(value);
const now = () => new Date().toISOString();
const limitValue = (value, fallback = 100) => Math.max(1, Math.min(10000, Math.trunc(Number(value) || fallback)));
const requiredText = (value, field) => {
  if (typeof value !== 'string' || !value.trim()) throw new TypeError(`${field} must be a nonempty string`);
  return value.trim();
};
const isPlain = (value) => value != null && typeof value === 'object' && !Array.isArray(value);
const clone = (value) => JSON.parse(json(value));
const username = (value) => requiredText(value, 'accountUsername').replace(/^@/u, '').toLowerCase();
const dateValue = (value, field) => {
  if (value == null) return null;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new TypeError(`${field} must be an ISO date or an epoch in milliseconds`);
  return date.toISOString();
};

// Settings patches merge objects while arrays and scalar values replace them. Prototype
// keys are never accepted, including when callers parse untrusted JSON from a DM.
function merge(base, patch) {
  if (!isPlain(patch)) throw new TypeError('Settings patch must be an object');
  const result = isPlain(base) ? clone(base) : {};
  for (const [key, value] of Object.entries(patch)) {
    if (['__proto__', 'prototype', 'constructor'].includes(key)) throw new TypeError(`Invalid settings key: ${key}`);
    result[key] = isPlain(value) ? merge(result[key], value) : clone(value);
  }
  return result;
}

function sourceIds(input) {
  if (input == null) return [];
  if (!Array.isArray(input)) throw new TypeError('sourceMessageIds must be an array');
  return [...new Set(input.map((id) => requiredText(id, 'sourceMessageId')))];
}

function messageRecord(row) {
  return row && {
    id: row.id, channel: row.channel, externalId: row.external_id, role: row.role,
    text: row.text, threadId: row.thread_id, metadata: parse(row.metadata_json, {}), createdAt: row.created_at,
  };
}

function goalRecord(row) {
  return row && {
    id: row.id, title: row.title, description: row.description, priority: row.priority,
    continuous: Boolean(row.continuous), state: row.state, status: row.state,
    metadata: parse(row.metadata_json, {}), createdAt: row.created_at, updatedAt: row.updated_at,
  };
}

function taskRecord(row) {
  return row && {
    id: row.id, title: row.title, description: row.description, goalId: row.goal_id,
    dedupKey: row.dedup_key, priority: row.priority, state: row.state, status: row.state,
    metadata: parse(row.metadata_json, {}), result: parse(row.result_json), error: row.error,
    workerId: row.worker_id, leaseToken: row.lease_token, leaseUntil: row.lease_until,
    attempts: row.attempts, nextRunAt: row.next_run_at, createdAt: row.created_at,
    updatedAt: row.updated_at, startedAt: row.started_at, completedAt: row.completed_at,
  };
}

function outboundRecord(row) {
  const metadata = row && parse(row.metadata_json, {});
  return row && {
    id: row.id, channel: row.channel, threadId: row.thread_id, text: row.text,
    idempotencyKey: row.idempotency_key, state: row.state, status: row.state,
    attempts: row.attempts, externalId: row.external_id, error: row.error,
    metadata, receipt: metadata.receipt ?? null, createdAt: row.created_at, updatedAt: row.updated_at,
    sentAt: row.sent_at,
  };
}

function actionRecord(row) {
  return row && {
    id: row.id, taskId: row.task_id, name: row.name, args: parse(row.args_json, {}),
    state: row.state, status: row.state, result: parse(row.result_json), error: row.error,
    createdAt: row.created_at, updatedAt: row.updated_at, completedAt: row.completed_at,
  };
}

function readRecord(row) {
  return row && {
    id: row.id, messageId: row.message_id, channel: row.channel, threadId: row.thread_id,
    externalId: row.external_id, accountUsername: row.account_username, state: row.state, status: row.state,
    attempts: row.attempts, nextAttemptAt: row.next_attempt_at, receipt: parse(row.receipt_json),
    error: row.error, createdAt: row.created_at, updatedAt: row.updated_at, confirmedAt: row.confirmed_at,
  };
}

/** Durable local state. All mutations and claims are synchronous SQLite transactions. */
export class Store {
  constructor(dataDir, { defaults = {}, leaseMs = 120_000 } = {}) {
    this.dataDir = resolve(requiredText(dataDir, 'dataDir'));
    this.leaseMs = Math.max(1, Number(leaseMs) || 120_000);
    this.claims = new Map();
    mkdirSync(this.dataDir, { recursive: true });
    this.db = new DatabaseSync(resolve(this.dataDir, 'assistant.sqlite'));
    this.db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000; PRAGMA synchronous = FULL; PRAGMA secure_delete = ON;');
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS schema_versions (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);
      INSERT OR IGNORE INTO schema_versions VALUES (1, strftime('%Y-%m-%dT%H:%M:%fZ','now'));
      CREATE TABLE IF NOT EXISTS settings_history (
        version INTEGER PRIMARY KEY AUTOINCREMENT, settings_json TEXT NOT NULL,
        actor TEXT NOT NULL, created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS runtime_state (
        state_key TEXT PRIMARY KEY, value_json TEXT NOT NULL, updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY, channel TEXT NOT NULL, external_id TEXT, role TEXT NOT NULL,
        text TEXT NOT NULL, thread_id TEXT, metadata_json TEXT NOT NULL, created_at TEXT NOT NULL,
        UNIQUE(channel, external_id)
      );
      CREATE INDEX IF NOT EXISTS messages_time ON messages(created_at DESC);
      CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(id UNINDEXED, text, tokenize='unicode61');
      CREATE TRIGGER IF NOT EXISTS messages_fts_insert AFTER INSERT ON messages BEGIN
        INSERT INTO messages_fts(id, text) VALUES (new.id, new.text);
      END;
      CREATE TRIGGER IF NOT EXISTS messages_fts_delete AFTER DELETE ON messages BEGIN
        DELETE FROM messages_fts WHERE id = old.id;
      END;
      CREATE TABLE IF NOT EXISTS memories (
        id TEXT PRIMARY KEY, memory_key TEXT NOT NULL, version INTEGER NOT NULL,
        content TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('fact','inference','opinion','uncertain')),
        confidence REAL NOT NULL CHECK(confidence >= 0 AND confidence <= 1),
        state TEXT NOT NULL CHECK(state IN ('active','superseded','archived')),
        supersedes_id TEXT, superseded_by_id TEXT, evidence_json TEXT NOT NULL, metadata_json TEXT NOT NULL,
        observed_at TEXT, valid_from TEXT, valid_until TEXT, expires_at TEXT,
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(memory_key, version)
      );
      CREATE UNIQUE INDEX IF NOT EXISTS one_active_memory ON memories(memory_key) WHERE state = 'active';
      CREATE INDEX IF NOT EXISTS memories_key ON memories(memory_key, version DESC);
      CREATE TABLE IF NOT EXISTS memory_sources (
        memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
        message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
        PRIMARY KEY(memory_id, message_id)
      );
      CREATE INDEX IF NOT EXISTS memory_sources_message ON memory_sources(message_id);
      CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(id UNINDEXED, memory_key, content, tokenize='unicode61');
      CREATE TRIGGER IF NOT EXISTS memories_fts_insert AFTER INSERT ON memories BEGIN
        INSERT INTO memories_fts(id, memory_key, content) VALUES (new.id, new.memory_key, new.content);
      END;
      CREATE TRIGGER IF NOT EXISTS memories_fts_delete AFTER DELETE ON memories BEGIN
        DELETE FROM memories_fts WHERE id = old.id;
      END;
      CREATE TABLE IF NOT EXISTS goals (
        id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL, priority REAL NOT NULL,
        continuous INTEGER NOT NULL, state TEXT NOT NULL, metadata_json TEXT NOT NULL,
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS tasks (
        id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL,
        goal_id TEXT REFERENCES goals(id) ON DELETE SET NULL, dedup_key TEXT UNIQUE,
        priority REAL NOT NULL, state TEXT NOT NULL CHECK(state IN ('queued','running','succeeded','failed','paused','waiting')),
        metadata_json TEXT NOT NULL, result_json TEXT, error TEXT,
        worker_id TEXT, lease_token TEXT, lease_until TEXT, attempts INTEGER NOT NULL DEFAULT 0,
        next_run_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
        started_at TEXT, completed_at TEXT
      );
      CREATE INDEX IF NOT EXISTS tasks_claim ON tasks(state, priority DESC, created_at);
      CREATE TABLE IF NOT EXISTS actions (
        id TEXT PRIMARY KEY, task_id TEXT REFERENCES tasks(id) ON DELETE CASCADE,
        name TEXT NOT NULL, args_json TEXT NOT NULL,
        state TEXT NOT NULL CHECK(state IN ('running','succeeded','failed','uncertain')),
        result_json TEXT, error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, completed_at TEXT
      );
      CREATE INDEX IF NOT EXISTS actions_task ON actions(task_id, created_at);
      CREATE TABLE IF NOT EXISTS events (
        id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, data_json TEXT NOT NULL, created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS outbound (
        id TEXT PRIMARY KEY, channel TEXT NOT NULL, thread_id TEXT, text TEXT NOT NULL,
        idempotency_key TEXT NOT NULL UNIQUE,
        state TEXT NOT NULL CHECK(state IN ('pending','sending','sent','failed','uncertain')),
        attempts INTEGER NOT NULL DEFAULT 0, external_id TEXT, error TEXT,
        metadata_json TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, sent_at TEXT
      );
      CREATE TABLE IF NOT EXISTS read_receipts (
        id TEXT PRIMARY KEY, message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
        channel TEXT NOT NULL, thread_id TEXT NOT NULL, external_id TEXT NOT NULL, account_username TEXT NOT NULL,
        state TEXT NOT NULL CHECK(state IN ('pending','reading','confirmed','unverified','failed')),
        attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT, receipt_json TEXT, error TEXT,
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL, confirmed_at TEXT,
        UNIQUE(channel, external_id)
      );
      CREATE INDEX IF NOT EXISTS reads_due ON read_receipts(account_username, state, next_attempt_at);
      CREATE TABLE IF NOT EXISTS feedback (
        id TEXT PRIMARY KEY, kind TEXT NOT NULL, text TEXT NOT NULL, rating REAL,
        task_id TEXT REFERENCES tasks(id) ON DELETE SET NULL,
        memory_id TEXT REFERENCES memories(id) ON DELETE CASCADE,
        source_message_id TEXT REFERENCES messages(id) ON DELETE CASCADE,
        metadata_json TEXT NOT NULL, created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS experiences (
        id TEXT PRIMARY KEY, task_id TEXT REFERENCES tasks(id) ON DELETE SET NULL,
        summary TEXT NOT NULL, outcome TEXT NOT NULL, lesson TEXT NOT NULL,
        metadata_json TEXT NOT NULL, created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS artifacts (
        id TEXT PRIMARY KEY, kind TEXT NOT NULL, path TEXT, content TEXT,
        metadata_json TEXT NOT NULL, created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS artifact_memories (
        artifact_id TEXT NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,
        memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
        PRIMARY KEY(artifact_id, memory_id)
      );
      CREATE TABLE IF NOT EXISTS artifact_sources (
        artifact_id TEXT NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,
        message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
        PRIMARY KEY(artifact_id, message_id)
      );
    `);
    // Existing development databases predate the uncertain delivery state. A
    // transaction preserves queued receipts while rebuilding SQLite's CHECK.
    const outboundSchema = this.db.prepare("SELECT sql FROM sqlite_master WHERE name = 'outbound'").get().sql;
    if (!outboundSchema.includes("'uncertain'")) {
      this.transaction(() => {
        this.db.exec(`
          ALTER TABLE outbound RENAME TO outbound_legacy_v1;
          CREATE TABLE outbound (
            id TEXT PRIMARY KEY, channel TEXT NOT NULL, thread_id TEXT, text TEXT NOT NULL,
            idempotency_key TEXT NOT NULL UNIQUE,
            state TEXT NOT NULL CHECK(state IN ('pending','sending','sent','failed','uncertain')),
            attempts INTEGER NOT NULL DEFAULT 0, external_id TEXT, error TEXT,
            metadata_json TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, sent_at TEXT
          );
          INSERT INTO outbound SELECT * FROM outbound_legacy_v1;
          DROP TABLE outbound_legacy_v1;
        `);
      });
    }
    if (!this.db.prepare('SELECT version FROM settings_history LIMIT 1').get()) {
      this.db.prepare('INSERT INTO settings_history(settings_json, actor, created_at) VALUES (?, ?, ?)')
        .run(json(merge({}, defaults)), 'initial-defaults', now());
    }
  }

  transaction(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const result = fn();
      this.db.exec('COMMIT');
      return result;
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
  }

  getSettings() {
    return parse(this.db.prepare('SELECT settings_json FROM settings_history ORDER BY version DESC LIMIT 1').get().settings_json, {});
  }

  updateSettings(patch, actor = 'user', { onceKey } = {}) {
    if (onceKey != null) onceKey = requiredText(onceKey, 'settings revision onceKey');
    return this.transaction(() => {
      if (onceKey != null && this.db.prepare('SELECT state_key FROM runtime_state WHERE state_key = ?').get(onceKey)) {
        return this.getSettings();
      }
      const settings = merge(this.getSettings(), patch);
      const row = this.db.prepare('INSERT INTO settings_history(settings_json, actor, created_at) VALUES (?, ?, ?)')
        .run(json(settings), requiredText(actor, 'actor'), now());
      this.event('settings.updated', { version: Number(row.lastInsertRowid), actor });
      if (onceKey != null) this.setRuntimeState(onceKey, true);
      return settings;
    });
  }

  listSettingsHistory(limit = 100) {
    return this.db.prepare('SELECT * FROM settings_history ORDER BY version DESC LIMIT ?').all(limitValue(limit))
      .map((row) => ({ version: row.version, actor: row.actor, settings: parse(row.settings_json), createdAt: row.created_at }));
  }

  getRuntimeState(key) {
    const row = this.db.prepare('SELECT value_json FROM runtime_state WHERE state_key = ?').get(requiredText(key, 'runtime state key'));
    return row ? parse(row.value_json) : null;
  }

  setRuntimeState(key, value) {
    this.db.prepare(`INSERT INTO runtime_state(state_key, value_json, updated_at) VALUES (?, ?, ?)
      ON CONFLICT(state_key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at`)
      .run(requiredText(key, 'runtime state key'), json(value), now());
    return clone(value);
  }

  addMessage({ id = randomUUID(), channel = 'web', externalId = null, role, text, threadId = null, metadata = {}, createdAt = now() }) {
    requiredText(channel, 'channel');
    requiredText(role, 'role');
    if (typeof text !== 'string') throw new TypeError('text must be a string');
    const existing = externalId == null ? null : this.db.prepare('SELECT * FROM messages WHERE channel = ? AND external_id = ?').get(channel, String(externalId));
    if (existing) return messageRecord(existing);
    const byId = this.db.prepare('SELECT * FROM messages WHERE id = ?').get(id);
    if (byId) return messageRecord(byId);
    // INSERT OR IGNORE also handles two Store instances racing to ingest the same DM.
    this.db.prepare('INSERT OR IGNORE INTO messages VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
      .run(id, channel, externalId == null ? null : String(externalId), role, text, threadId, json(metadata), createdAt);
    return messageRecord(this.db.prepare('SELECT * FROM messages WHERE id = ? OR (channel = ? AND external_id = ?) LIMIT 1').get(id, channel, externalId == null ? null : String(externalId)));
  }

  getMessage(id) {
    return messageRecord(this.db.prepare('SELECT * FROM messages WHERE id = ?').get(id)) ?? null;
  }

  getMessageByExternalId(channel, externalId) {
    if (externalId == null) return null;
    return messageRecord(this.db.prepare('SELECT * FROM messages WHERE channel = ? AND external_id = ?').get(channel, String(externalId))) ?? null;
  }

  listMessages(limit = 100, options = {}) {
    if (isPlain(limit)) { options = limit; limit = options.limit ?? 100; }
    const where = [], values = [];
    if (options.threadId != null) { where.push('thread_id = ?'); values.push(options.threadId); }
    if (options.channel != null) { where.push('channel = ?'); values.push(options.channel); }
    if (options.before != null) { where.push('created_at < ?'); values.push(options.before); }
    return this.db.prepare(`SELECT * FROM messages ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY created_at DESC, rowid DESC LIMIT ?`)
      .all(...values, limitValue(limit)).map(messageRecord);
  }

  searchMessages(query, limit = 100) {
    const { clause, values } = this.searchClause(query, 'messages', 'messages_fts', ['text']);
    return this.db.prepare(`SELECT * FROM messages WHERE ${clause} ORDER BY created_at DESC LIMIT ?`)
      .all(...values, limitValue(limit)).map(messageRecord);
  }

  searchClause(query, table, fts, columns) {
    const terms = requiredText(query, 'query').split(/\s+/u).slice(0, 30);
    const lexical = terms.map((term) => `"${term.replaceAll('"', '""')}"*`).join(' AND ');
    const values = [lexical];
    const substring = terms.map((term) => {
      const escaped = term.replaceAll('\\', '\\\\').replaceAll('%', '\\%').replaceAll('_', '\\_');
      for (let i = 0; i < columns.length; i++) values.push(`%${escaped}%`);
      return `(${columns.map((column) => `${table}.${column} LIKE ? ESCAPE '\\'`).join(' OR ')})`;
    }).join(' AND ');
    // unicode61 cannot split Korean inflections. Substring fallback also searches
    // the middle of Hangul tokens without pretending to be semantic retrieval.
    return { clause: `(${table}.id IN (SELECT id FROM ${fts} WHERE ${fts} MATCH ?) OR (${substring}))`, values };
  }

  memoryRecord(row) {
    if (!row) return null;
    return {
      id: row.id, key: row.memory_key, version: row.version, content: row.content,
      kind: row.kind, confidence: row.confidence, state: row.state, status: row.state,
      archived: row.state !== 'active', supersedesId: row.supersedes_id, supersededById: row.superseded_by_id,
      sourceMessageIds: this.db.prepare('SELECT message_id FROM memory_sources WHERE memory_id = ? ORDER BY message_id').all(row.id).map((source) => source.message_id),
      evidence: parse(row.evidence_json, []), metadata: parse(row.metadata_json, {}),
      observedAt: row.observed_at, validFrom: row.valid_from, validUntil: row.valid_until,
      expiresAt: row.expires_at, createdAt: row.created_at, updatedAt: row.updated_at,
    };
  }

  getMemory(id) {
    return this.memoryRecord(this.db.prepare('SELECT * FROM memories WHERE id = ?').get(id));
  }

  upsertMemory(input) {
    const key = requiredText(input.key, 'memory key');
    const content = requiredText(input.content, 'memory content');
    const kind = input.kind ?? 'uncertain';
    if (!MEMORY_KINDS.has(kind)) throw new TypeError(`Unknown memory kind: ${kind}`);
    const confidence = Number(input.confidence ?? 0.5);
    if (!Number.isFinite(confidence) || confidence < 0 || confidence > 1) throw new TypeError('confidence must be between 0 and 1');
    const sources = sourceIds(input.sourceMessageIds);
    return this.transaction(() => {
      const previous = this.db.prepare('SELECT * FROM memories WHERE memory_key = ? ORDER BY version DESC LIMIT 1').get(key);
      const id = input.id ?? randomUUID(), time = now(), version = (previous?.version ?? 0) + 1;
      if (previous?.state === 'active') this.db.prepare("UPDATE memories SET state = 'superseded', superseded_by_id = ?, updated_at = ? WHERE id = ?").run(id, time, previous.id);
      this.db.prepare(`INSERT INTO memories (
        id, memory_key, version, content, kind, confidence, state, supersedes_id,
        evidence_json, metadata_json, observed_at, valid_from, valid_until, expires_at, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
        .run(id, key, version, content, kind, confidence, previous?.id ?? null,
          json(input.evidence ?? []), json(input.metadata ?? {}), input.observedAt ?? null,
          input.validFrom ?? null, input.validUntil ?? null, input.expiresAt ?? null, time, time);
      const link = this.db.prepare('INSERT INTO memory_sources(memory_id, message_id) VALUES (?, ?)');
      for (const source of sources) link.run(id, source);
      this.event('memory.created', { id, key, version, kind, supersedesId: previous?.id ?? null });
      return this.getMemory(id);
    });
  }

  listMemories({ query, includeArchived = false, includeExpired = false, limit = 1000, key } = {}) {
    const where = [], values = [];
    if (!includeArchived) where.push("memories.state = 'active'");
    if (!includeExpired) { where.push('(expires_at IS NULL OR expires_at > ?)'); values.push(now()); }
    if (key != null) { where.push('memory_key = ?'); values.push(key); }
    if (query?.trim()) {
      const search = this.searchClause(query, 'memories', 'memories_fts', ['memory_key', 'content']);
      where.push(search.clause); values.push(...search.values);
    }
    return this.db.prepare(`SELECT * FROM memories ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY created_at DESC, version DESC, rowid DESC LIMIT ?`)
      .all(...values, limitValue(limit, 1000)).map((row) => this.memoryRecord(row));
  }

  archiveMemory(id) {
    const time = now();
    this.db.prepare("UPDATE memories SET state = 'archived', updated_at = ? WHERE id = ?").run(time, id);
    const memory = this.getMemory(id);
    if (memory) this.event('memory.archived', { id });
    return memory;
  }

  forgetMemory(id, { deleteSources = false } = {}) {
    const memory = this.getMemory(id);
    if (!memory) return { memories: 0, messages: 0, tasks: 0, artifacts: 0, deletedPaths: [], retainedPaths: [] };
    const outcome = this.transaction(() => {
      const versions = this.db.prepare('SELECT id FROM memories WHERE memory_key = ?').all(memory.key).map((row) => row.id);
      const sources = [...new Set(versions.flatMap((version) => this.getMemory(version).sourceMessageIds))];
      const deletedMessages = new Set(deleteSources ? sources : []);
      const deletedTasks = new Set();
      if (deleteSources) {
        const tasks = this.db.prepare('SELECT id, metadata_json FROM tasks').all();
        const messages = this.db.prepare('SELECT id, metadata_json FROM messages').all();
        let changed = true;
        // Propagate explicit provenance: original message -> task -> generated
        // reply -> later source-linked task. Plain unlinked text is not guessed.
        while (changed) {
          changed = false;
          for (const task of tasks) {
            const ids = parse(task.metadata_json, {})?.sourceMessageIds;
            if (!deletedTasks.has(task.id) && Array.isArray(ids) && ids.some((source) => deletedMessages.has(source))) {
              deletedTasks.add(task.id); changed = true;
            }
          }
          for (const message of messages) {
            if (!deletedMessages.has(message.id) && deletedTasks.has(parse(message.metadata_json, {})?.taskId)) {
              deletedMessages.add(message.id); changed = true;
            }
          }
        }
      }
      const deletedVersions = new Set(versions);
      // Collect dependents before cascade deletion so all their artifacts and
      // audit references can also be removed, including shared raw evidence.
      if (deleteSources) for (const source of deletedMessages) {
        for (const dependent of this.db.prepare('SELECT DISTINCT memory_id FROM memory_sources WHERE message_id = ?').all(source)) deletedVersions.add(dependent.memory_id);
      }
      const artifactIds = new Set([...deletedVersions].flatMap((version) => this.db.prepare('SELECT artifact_id FROM artifact_memories WHERE memory_id = ?').all(version).map((row) => row.artifact_id)));
      if (deleteSources) for (const source of deletedMessages) for (const row of this.db.prepare('SELECT artifact_id FROM artifact_sources WHERE message_id = ?').all(source)) artifactIds.add(row.artifact_id);
      const artifacts = [...artifactIds].map((artifactId) => this.db.prepare('SELECT * FROM artifacts WHERE id = ?').get(artifactId)).filter(Boolean);
      for (const artifact of artifacts) this.db.prepare('DELETE FROM artifacts WHERE id = ?').run(artifact.id);
      for (const version of deletedVersions) this.db.prepare('DELETE FROM memories WHERE id = ?').run(version);
      for (const taskId of deletedTasks) {
        this.db.prepare('DELETE FROM experiences WHERE task_id = ?').run(taskId);
        this.db.prepare('DELETE FROM feedback WHERE task_id = ?').run(taskId);
        this.db.prepare('DELETE FROM tasks WHERE id = ?').run(taskId);
      }
      let deletedMessageCount = 0;
      for (const source of deletedMessages) deletedMessageCount += Number(this.db.prepare('DELETE FROM messages WHERE id = ?').run(source).changes);
      const deletedIds = new Set([...deletedVersions, ...artifactIds, ...deletedMessages, ...deletedTasks]);
      const referencesDeleted = (value) => typeof value === 'string' ? [...deletedIds].some((deleted) => value.includes(deleted))
        : Array.isArray(value) ? value.some(referencesDeleted)
        : isPlain(value) ? Object.values(value).some(referencesDeleted) : false;
      let deletedOutbound = 0, redactedActions = 0;
      for (const outbound of this.db.prepare('SELECT id, metadata_json, idempotency_key FROM outbound').all()) {
        if (referencesDeleted(parse(outbound.metadata_json)) || referencesDeleted(outbound.idempotency_key)) {
          deletedOutbound += Number(this.db.prepare('DELETE FROM outbound WHERE id = ?').run(outbound.id).changes);
        }
      }
      for (const action of this.db.prepare('SELECT id, args_json, result_json FROM actions').all()) {
        if (referencesDeleted(parse(action.args_json)) || referencesDeleted(parse(action.result_json))) {
          // Keep receipt identity/state so a forgotten historical side effect
          // cannot be executed again. Remove the linked request/result payloads.
          this.db.prepare('UPDATE actions SET args_json = ?, result_json = ?, error = NULL WHERE id = ?')
            .run(json({ redacted: true }), json({ redacted: true }), action.id);
          redactedActions++;
        }
      }
      for (const task of this.db.prepare('SELECT id, metadata_json FROM tasks').all()) {
        const metadata = parse(task.metadata_json, {});
        const history = metadata?.checkpoint?.history;
        if (Array.isArray(history) && history.some(referencesDeleted)) {
          metadata.checkpoint.history = history.filter((entry) => !referencesDeleted(entry));
          this.db.prepare('UPDATE tasks SET metadata_json = ?, updated_at = ? WHERE id = ?').run(json(metadata), now(), task.id);
        }
      }
      for (const event of this.db.prepare('SELECT id, data_json FROM events').all()) {
        if (referencesDeleted(parse(event.data_json))) this.db.prepare('DELETE FROM events WHERE id = ?').run(event.id);
      }
      // Audit contains counts only, never the forgotten key/content/source IDs.
      this.event('memory.forgotten', { versions: deletedVersions.size, sourcesDeleted: deletedMessageCount, tasks: deletedTasks.size, artifacts: artifacts.length });
      return { memories: deletedVersions.size, messages: deletedMessageCount, tasks: deletedTasks.size, outbound: deletedOutbound, redactedActions, artifacts: artifacts.length, paths: artifacts.map((artifact) => artifact.path).filter(Boolean) };
    });
    const deletedPaths = [], retainedPaths = [];
    for (const path of outcome.paths) {
      const absolute = resolve(this.dataDir, path), rel = relative(this.dataDir, absolute);
      if (!rel || rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) { retainedPaths.push(path); continue; }
      try { rmSync(absolute, { force: true, recursive: false }); deletedPaths.push(path); }
      catch { retainedPaths.push(path); }
    }
    const { paths, ...counts } = outcome;
    for (const taskId of this.claims.keys()) if (!this.getTask(taskId)) this.claims.delete(taskId);
    // secure_delete scrubs freed SQLite pages; truncate old local WAL frames when
    // no other reader holds them. External backups/provider history are separate.
    this.db.prepare('PRAGMA wal_checkpoint(TRUNCATE)').get();
    return { ...counts, deletedPaths, retainedPaths };
  }

  addGoal({ id = randomUUID(), title, description = '', priority = 0, continuous = true, metadata = {} }) {
    this.db.prepare('INSERT INTO goals VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
      .run(id, requiredText(title, 'goal title'), String(description), Number(priority) || 0, continuous ? 1 : 0, 'active', json(metadata), now(), now());
    return goalRecord(this.db.prepare('SELECT * FROM goals WHERE id = ?').get(id));
  }

  listGoals({ state, limit = 1000 } = {}) {
    return this.db.prepare(`SELECT * FROM goals ${state ? 'WHERE state = ?' : ''} ORDER BY priority DESC, created_at ASC LIMIT ?`)
      .all(...(state ? [state] : []), limitValue(limit, 1000)).map(goalRecord);
  }

  updateGoal(id, patch) {
    const goal = goalRecord(this.db.prepare('SELECT * FROM goals WHERE id = ?').get(id));
    if (!goal) return null;
    const state = patch.state ?? patch.status ?? goal.state;
    if (!['active', 'paused', 'completed', 'archived'].includes(state)) throw new TypeError('Invalid goal state');
    this.db.prepare('UPDATE goals SET title = ?, description = ?, priority = ?, continuous = ?, state = ?, metadata_json = ?, updated_at = ? WHERE id = ?')
      .run(patch.title ?? goal.title, patch.description ?? goal.description, patch.priority ?? goal.priority,
        (patch.continuous ?? goal.continuous) ? 1 : 0, state, json(patch.metadata ?? goal.metadata), now(), id);
    return goalRecord(this.db.prepare('SELECT * FROM goals WHERE id = ?').get(id));
  }

  addTask({ id = randomUUID(), title, description = '', goalId = null, dedupKey = null, priority = 0, metadata = {}, nextRunAt = null }) {
    requiredText(title, 'task title');
    if (dedupKey != null) {
      dedupKey = requiredText(dedupKey, 'dedupKey');
      const existing = this.db.prepare('SELECT * FROM tasks WHERE dedup_key = ?').get(dedupKey);
      if (existing) return taskRecord(existing);
    }
    const time = now();
    this.db.prepare(`INSERT OR IGNORE INTO tasks (id, title, description, goal_id, dedup_key, priority, state, metadata_json, next_run_at, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, 'queued', ?, ?, ?, ?)`)
      .run(id, title, String(description), goalId, dedupKey, Number(priority) || 0, json(metadata), nextRunAt, time, time);
    return taskRecord(this.db.prepare('SELECT * FROM tasks WHERE id = ? OR dedup_key = ? LIMIT 1').get(id, dedupKey));
  }

  getTask(id) {
    return taskRecord(this.db.prepare('SELECT * FROM tasks WHERE id = ?').get(id)) ?? null;
  }

  listTasks({ state, status, goalId, limit = 1000 } = {}) {
    state ??= status;
    const where = [], values = [];
    if (state) { where.push('state = ?'); values.push(state); }
    if (goalId) { where.push('goal_id = ?'); values.push(goalId); }
    return this.db.prepare(`SELECT * FROM tasks ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY created_at DESC, rowid DESC LIMIT ?`)
      .all(...values, limitValue(limit, 1000)).map(taskRecord);
  }

  claimTask(workerId, { leaseMs = this.leaseMs } = {}) {
    requiredText(workerId, 'workerId');
    return this.transaction(() => {
      const time = now();
      const row = this.db.prepare(`SELECT tasks.* FROM tasks LEFT JOIN goals ON goals.id = tasks.goal_id
        WHERE tasks.state = 'queued' AND (next_run_at IS NULL OR next_run_at <= ?)
        AND (tasks.goal_id IS NULL OR goals.state = 'active') ORDER BY tasks.priority DESC, tasks.created_at ASC, tasks.rowid ASC LIMIT 1`).get(time);
      if (!row) return null;
      const token = randomUUID(), until = new Date(Date.now() + Math.max(1, Number(leaseMs) || this.leaseMs)).toISOString();
      this.db.prepare(`UPDATE tasks SET state = 'running', worker_id = ?, lease_token = ?, lease_until = ?, attempts = attempts + 1,
        started_at = ?, completed_at = NULL, error = NULL, updated_at = ? WHERE id = ? AND state = 'queued'`)
        .run(workerId, token, until, time, time, row.id);
      this.event('task.started', { id: row.id, workerId, attempt: row.attempts + 1 });
      this.claims.set(row.id, token);
      return this.getTask(row.id);
    });
  }

  assertLease(task, { leaseToken, workerId } = {}) {
    if (!task || task.state !== 'running') throw new Error('Task is not running');
    // Same-instance callers may omit the token, but still use the token captured
    // at claim time. They cannot finish a newer lease owned by another Store.
    const token = leaseToken ?? this.claims.get(task.id);
    if (!token) throw new Error('Task lease token is required');
    if (task.leaseToken !== token) throw new Error('Task lease token no longer owns this task');
    if (workerId != null && task.workerId !== workerId) throw new Error('Task belongs to another worker');
    if (task.leaseUntil && task.leaseUntil <= now()) throw new Error('Task lease has expired');
  }

  renewTaskLease(id, { leaseToken, workerId, leaseMs = this.leaseMs } = {}) {
    return this.transaction(() => {
      this.assertLease(this.getTask(id), { leaseToken, workerId });
      this.db.prepare('UPDATE tasks SET lease_until = ?, updated_at = ? WHERE id = ?')
        .run(new Date(Date.now() + Math.max(1, Number(leaseMs) || this.leaseMs)).toISOString(), now(), id);
      return this.getTask(id);
    });
  }

  completeTask(id, result, ownership = {}) {
    return this.finishTask(id, 'succeeded', result, null, ownership);
  }

  failTask(id, error, ownership = {}) {
    return this.finishTask(id, 'failed', null, error instanceof Error ? error.message : String(error), ownership);
  }

  finishTask(id, state, result, error, ownership) {
    return this.transaction(() => {
      this.assertLease(this.getTask(id), ownership);
      const time = now();
      this.db.prepare('UPDATE tasks SET state = ?, result_json = ?, error = ?, worker_id = NULL, lease_token = NULL, lease_until = NULL, updated_at = ?, completed_at = ? WHERE id = ?')
        .run(state, result == null ? null : json(result), error, time, time, id);
      this.event(`task.${state}`, { id });
      this.claims.delete(id);
      return this.getTask(id);
    });
  }

  steerTask(id, patch = {}) {
    return this.transaction(() => {
      const task = this.getTask(id);
      if (!task) return null;
      const state = patch.state ?? patch.status ?? task.state;
      if (!TASK_STATES.has(state)) throw new TypeError(`Unknown task state: ${state}`);
      if (state === 'running' && task.state !== 'running') throw new TypeError('Only claimTask can start a task');
      const keepLease = state === 'running';
      this.db.prepare(`UPDATE tasks SET title = ?, description = ?, priority = ?, state = ?, metadata_json = ?, next_run_at = ?,
        worker_id = ?, lease_token = ?, lease_until = ?, error = ?, result_json = ?, updated_at = ?, completed_at = ? WHERE id = ?`)
        .run(patch.title ?? task.title, patch.description ?? task.description, patch.priority ?? task.priority, state,
          json(patch.metadata ? merge(task.metadata, patch.metadata) : task.metadata), patch.nextRunAt === undefined ? task.nextRunAt : patch.nextRunAt,
          keepLease ? task.workerId : null, keepLease ? task.leaseToken : null, keepLease ? task.leaseUntil : null,
          state === 'queued' ? null : task.error, state === 'queued' ? null : json(task.result), now(),
          ['succeeded', 'failed'].includes(state) ? (task.completedAt ?? now()) : null, id);
      this.event('task.steered', { id, state });
      if (!keepLease) this.claims.delete(id);
      return this.getTask(id);
    });
  }

  checkpointTask(id, checkpoint) {
    if (!isPlain(checkpoint)) throw new TypeError('checkpoint must be an object');
    return this.transaction(() => {
      const task = this.getTask(id);
      if (!task) return null;
      const metadata = { ...task.metadata, checkpoint: clone(checkpoint) };
      this.db.prepare('UPDATE tasks SET metadata_json = ?, updated_at = ? WHERE id = ?').run(json(metadata), now(), id);
      return this.getTask(id);
    });
  }

  beginAction({ id, taskId = null, name, args = {} }) {
    requiredText(id, 'action id');
    requiredText(name, 'action name');
    return this.transaction(() => {
      const existing = this.getAction(id);
      if (existing) {
        if (existing.name !== name || existing.taskId !== taskId || json(existing.args) !== json(args)) {
          throw new Error('Action ID was already used with different arguments');
        }
        return { created: false, ...existing };
      }
      const time = now();
      this.db.prepare(`INSERT INTO actions (id, task_id, name, args_json, state, created_at, updated_at)
        VALUES (?, ?, ?, ?, 'running', ?, ?)`)
        .run(id, taskId, name, json(args), time, time);
      this.event('action.started', { id, taskId, name });
      return { created: true, ...this.getAction(id) };
    });
  }

  getAction(id) {
    return actionRecord(this.db.prepare('SELECT * FROM actions WHERE id = ?').get(id)) ?? null;
  }

  finishAction(id, { state = 'succeeded', result, error = null } = {}) {
    if (!ACTION_STATES.has(state) || state === 'running') throw new TypeError('Action completion state must be succeeded, failed or uncertain');
    return this.transaction(() => {
      const action = this.getAction(id);
      if (!action) throw new Error('Action not found');
      if (['succeeded', 'failed'].includes(action.state)) {
        if (state !== action.state) throw new Error('Completed action cannot change state');
        return action;
      }
      const time = now();
      this.db.prepare('UPDATE actions SET state = ?, result_json = ?, error = ?, updated_at = ?, completed_at = ? WHERE id = ?')
        .run(state, json(result === undefined ? action.result : result), error instanceof Error ? error.message : error,
          time, state === 'uncertain' ? null : time, id);
      this.event(`action.${state}`, { id, taskId: action.taskId, name: action.name });
      return this.getAction(id);
    });
  }

  listActions({ taskId, state, limit = 1000 } = {}) {
    const where = [], values = [];
    if (taskId) { where.push('task_id = ?'); values.push(taskId); }
    if (state) { where.push('state = ?'); values.push(state); }
    return this.db.prepare(`SELECT * FROM actions ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY created_at ASC, rowid ASC LIMIT ?`)
      .all(...values, limitValue(limit, 1000)).map(actionRecord);
  }

  recoverActions({ taskId, before } = {}) {
    return this.transaction(() => {
      const where = ["state = 'running'"], values = [];
      if (taskId) { where.push('task_id = ?'); values.push(taskId); }
      if (before) { where.push('updated_at < ?'); values.push(before); }
      const interrupted = this.db.prepare(`SELECT id FROM actions WHERE ${where.join(' AND ')}`).all(...values);
      this.db.prepare(`UPDATE actions SET state = 'uncertain', error = 'Action interrupted; reconcile before replay', updated_at = ? WHERE ${where.join(' AND ')}`)
        .run(now(), ...values);
      if (interrupted.length) this.event('actions.recovered', { count: interrupted.length });
      return interrupted.map(({ id }) => this.getAction(id));
    });
  }

  recoverTasks({ force = false, workerId } = {}) {
    return this.transaction(() => {
      const where = ["state = 'running'"], values = [];
      if (!force) { where.push('(lease_until IS NULL OR lease_until <= ?)'); values.push(now()); }
      if (workerId) { where.push('worker_id = ?'); values.push(workerId); }
      const tasks = this.db.prepare(`SELECT id FROM tasks WHERE ${where.join(' AND ')}`).all(...values);
      const time = now();
      this.db.prepare(`UPDATE tasks SET state = 'queued', worker_id = NULL, lease_token = NULL, lease_until = NULL, updated_at = ? WHERE ${where.join(' AND ')}`)
        .run(time, ...values);
      if (tasks.length) this.event('tasks.recovered', { count: tasks.length, force });
      for (const task of tasks) this.claims.delete(task.id);
      return tasks.map(({ id }) => this.getTask(id));
    });
  }

  event(kind, data = {}) {
    const time = now();
    const result = this.db.prepare('INSERT INTO events(kind, data_json, created_at) VALUES (?, ?, ?)').run(requiredText(kind, 'event kind'), json(data), time);
    return { id: Number(result.lastInsertRowid), kind, data: clone(data), createdAt: time };
  }

  listEvents(limit = 100) {
    return this.db.prepare('SELECT * FROM events ORDER BY id DESC LIMIT ?').all(limitValue(limit))
      .map((row) => ({ id: row.id, kind: row.kind, data: parse(row.data_json), createdAt: row.created_at }));
  }

  queueOutbound({ id = randomUUID(), channel, threadId = null, text, idempotencyKey, metadata = {} }) {
    requiredText(channel, 'outbound channel');
    requiredText(text, 'outbound text');
    idempotencyKey = requiredText(idempotencyKey, 'idempotencyKey');
    const time = now();
    this.db.prepare(`INSERT OR IGNORE INTO outbound (id, channel, thread_id, text, idempotency_key, state, metadata_json, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?)`)
      .run(id, channel, threadId, text, idempotencyKey, json(metadata), time, time);
    return outboundRecord(this.db.prepare('SELECT * FROM outbound WHERE idempotency_key = ?').get(idempotencyKey));
  }

  listPendingOutbound(limit = 100, { includeFailed = false } = {}) {
    return this.db.prepare(`SELECT * FROM outbound WHERE state = 'pending' ${includeFailed ? "OR state = 'failed'" : ''} ORDER BY created_at ASC, rowid ASC LIMIT ?`)
      .all(limitValue(limit)).map(outboundRecord);
  }

  listOutbound({ state, status, limit = 100 } = {}) {
    state ??= status;
    return this.db.prepare(`SELECT * FROM outbound ${state ? 'WHERE state = ?' : ''} ORDER BY created_at DESC, rowid DESC LIMIT ?`)
      .all(...(state ? [state] : []), limitValue(limit)).map(outboundRecord);
  }

  updateOutbound(id, patch) {
    if (typeof patch === 'string') patch = { state: patch };
    const row = outboundRecord(this.db.prepare('SELECT * FROM outbound WHERE id = ?').get(id));
    if (!row) return null;
    const requestedState = patch.state ?? patch.status ?? row.state;
    const state = requestedState === 'confirmed' ? 'sent' : requestedState;
    if (!OUTBOUND_STATES.has(state)) throw new TypeError(`Unknown outbound state: ${state}`);
    // A delivered item cannot be accidentally returned to the pending queue.
    if (row.state === 'sent' && state !== 'sent') throw new Error('Sent outbound messages cannot be resent; use a new idempotency key');
    this.db.prepare('UPDATE outbound SET state = ?, attempts = ?, external_id = ?, error = ?, metadata_json = ?, updated_at = ?, sent_at = ? WHERE id = ?')
      .run(state, patch.attempts ?? (state === 'sending' && row.state !== 'sending' ? row.attempts + 1 : row.attempts),
        patch.externalId ?? patch.receipt?.externalId ?? patch.receipt?.itemId ?? row.externalId,
        patch.error === undefined ? row.error : patch.error,
        json({ ...(patch.metadata ?? row.metadata), ...(patch.receipt === undefined ? {} : { receipt: patch.receipt }) }),
        now(), state === 'sent' ? (row.sentAt ?? now()) : null, id);
    return outboundRecord(this.db.prepare('SELECT * FROM outbound WHERE id = ?').get(id));
  }

  recoverOutbound() {
    // Delivery may have succeeded before a crash. Keep interrupted sends uncertain
    // for reconciliation; blindly resending would cause duplicate DMs.
    const result = this.db.prepare("UPDATE outbound SET state = 'uncertain', error = 'Delivery interrupted; reconcile provider externalId before retrying', updated_at = ? WHERE state = 'sending'").run(now());
    return Number(result.changes);
  }

  queueRead({ id = randomUUID(), messageId, channel = 'instagram', threadId, externalId, accountUsername }) {
    requiredText(messageId, 'read source messageId');
    requiredText(channel, 'read channel');
    requiredText(threadId, 'read threadId');
    externalId = requiredText(externalId == null ? null : String(externalId), 'read externalId');
    accountUsername = username(accountUsername);
    return this.transaction(() => {
      const existing = this.db.prepare('SELECT * FROM read_receipts WHERE channel = ? AND external_id = ?').get(channel, externalId);
      if (existing) return readRecord(existing);
      const time = now();
      this.db.prepare(`INSERT INTO read_receipts
        (id, message_id, channel, thread_id, external_id, account_username, state, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)`)
        .run(id, messageId, channel, threadId, externalId, accountUsername, time, time);
      return this.getRead(id);
    });
  }

  getRead(id) {
    return readRecord(this.db.prepare('SELECT * FROM read_receipts WHERE id = ?').get(id)) ?? null;
  }

  listPendingReads({ now: dueNow = now(), accountUsername, priorityThread, limit = 100 } = {}) {
    const where = [
      "(state = 'pending' OR (state IN ('unverified','failed') AND next_attempt_at IS NOT NULL))",
      '(next_attempt_at IS NULL OR next_attempt_at <= ?)',
    ];
    const values = [dateValue(dueNow, 'now')];
    if (accountUsername != null) { where.push('account_username = ?'); values.push(username(accountUsername)); }
    const priorityOrder = priorityThread == null ? '' : 'CASE WHEN thread_id = ? THEN 0 ELSE 1 END, ';
    if (priorityThread != null) values.push(requiredText(priorityThread, 'priorityThread'));
    return this.db.prepare(`SELECT * FROM read_receipts WHERE ${where.join(' AND ')} ORDER BY ${priorityOrder}created_at ASC, rowid ASC LIMIT ?`)
      .all(...values, limitValue(limit)).map(readRecord);
  }

  listReads({ state, status, accountUsername, limit = 100 } = {}) {
    state ??= status;
    const where = [], values = [];
    if (state) { where.push('state = ?'); values.push(state); }
    if (accountUsername != null) { where.push('account_username = ?'); values.push(username(accountUsername)); }
    return this.db.prepare(`SELECT * FROM read_receipts ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY created_at DESC, rowid DESC LIMIT ?`)
      .all(...values, limitValue(limit)).map(readRecord);
  }

  updateRead(id, patch) {
    if (!isPlain(patch)) throw new TypeError('Read receipt patch must be an object');
    return this.transaction(() => {
      const read = this.getRead(id);
      if (!read) return null;
      const state = patch.state ?? patch.status ?? read.state;
      if (!READ_STATES.has(state)) throw new TypeError(`Unknown read state: ${state}`);
      if (read.state === 'confirmed' && state !== 'confirmed') throw new Error('Confirmed read receipts cannot be requeued');
      const attempts = patch.attempts ?? (state === 'reading' && read.state !== 'reading' ? read.attempts + 1 : read.attempts);
      if (!Number.isSafeInteger(attempts) || attempts < 0) throw new TypeError('Read attempts must be a nonnegative integer');
      const nextAttemptAt = state === 'confirmed' ? null
        : patch.nextAttemptAt === undefined ? read.nextAttemptAt : dateValue(patch.nextAttemptAt, 'nextAttemptAt');
      const receipt = patch.receipt === undefined ? read.receipt : patch.receipt;
      const error = patch.error === undefined ? (state === 'confirmed' ? null : read.error)
        : patch.error instanceof Error ? patch.error.message : patch.error;
      const time = now();
      this.db.prepare(`UPDATE read_receipts SET state = ?, attempts = ?, next_attempt_at = ?, receipt_json = ?, error = ?,
        updated_at = ?, confirmed_at = ? WHERE id = ?`)
        .run(state, attempts, nextAttemptAt, receipt == null ? null : json(receipt), error, time,
          state === 'confirmed' ? (read.confirmedAt ?? time) : null, id);
      return this.getRead(id);
    });
  }

  recoverReads() {
    // Opening a thread/marking seen can be retried safely; it never sends a DM.
    return this.transaction(() => Number(this.db.prepare("UPDATE read_receipts SET state = 'pending', updated_at = ? WHERE state = 'reading'").run(now()).changes));
  }

  addFeedback({ id = randomUUID(), kind = 'user', text, rating = null, taskId = null, memoryId = null, sourceMessageId = null, metadata = {} }) {
    this.db.prepare('INSERT INTO feedback VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
      .run(id, kind, requiredText(text, 'feedback text'), rating, taskId, memoryId, sourceMessageId, json(metadata), now());
    return this.listFeedback({ limit: 1 })[0];
  }

  listFeedback({ taskId, limit = 100 } = {}) {
    return this.db.prepare(`SELECT * FROM feedback ${taskId ? 'WHERE task_id = ?' : ''} ORDER BY created_at DESC, rowid DESC LIMIT ?`)
      .all(...(taskId ? [taskId] : []), limitValue(limit)).map((row) => ({
        id: row.id, kind: row.kind, text: row.text, rating: row.rating, taskId: row.task_id,
        memoryId: row.memory_id, sourceMessageId: row.source_message_id, metadata: parse(row.metadata_json), createdAt: row.created_at,
      }));
  }

  addExperience({ id = randomUUID(), taskId = null, summary, outcome = '', lesson = '', metadata = {} }) {
    this.db.prepare('INSERT INTO experiences VALUES (?, ?, ?, ?, ?, ?, ?)')
      .run(id, taskId, requiredText(summary, 'experience summary'), String(outcome), String(lesson), json(metadata), now());
    return this.listExperiences({ limit: 1 })[0];
  }

  listExperiences({ taskId, limit = 100 } = {}) {
    return this.db.prepare(`SELECT * FROM experiences ${taskId ? 'WHERE task_id = ?' : ''} ORDER BY created_at DESC, rowid DESC LIMIT ?`)
      .all(...(taskId ? [taskId] : []), limitValue(limit)).map((row) => ({
        id: row.id, taskId: row.task_id, summary: row.summary, outcome: row.outcome, lesson: row.lesson,
        metadata: parse(row.metadata_json), createdAt: row.created_at,
      }));
  }

  addArtifact({ id = randomUUID(), kind = 'file', path = null, content = null, metadata = {}, memoryIds = [], sourceMessageIds = [] }) {
    return this.transaction(() => {
      this.db.prepare('INSERT INTO artifacts VALUES (?, ?, ?, ?, ?, ?)').run(id, kind, path, content, json(metadata), now());
      for (const memoryId of new Set(memoryIds)) this.db.prepare('INSERT INTO artifact_memories VALUES (?, ?)').run(id, memoryId);
      for (const messageId of sourceIds(sourceMessageIds)) this.db.prepare('INSERT INTO artifact_sources VALUES (?, ?)').run(id, messageId);
      return { id, kind, path, content, metadata, memoryIds: [...new Set(memoryIds)], sourceMessageIds: sourceIds(sourceMessageIds) };
    });
  }

  listArtifacts() {
    return this.db.prepare('SELECT * FROM artifacts ORDER BY created_at DESC').all().map((row) => ({
      id: row.id, kind: row.kind, path: row.path, content: row.content, metadata: parse(row.metadata_json), createdAt: row.created_at,
    }));
  }

  counts() {
    const count = (table, where = '') => Number(this.db.prepare(`SELECT count(*) AS n FROM ${table} ${where}`).get().n);
    return {
      messages: count('messages'), memories: count('memories', "WHERE state = 'active'"), memoryVersions: count('memories'),
      goals: count('goals'), tasks: count('tasks'), queuedTasks: count('tasks', "WHERE state = 'queued'"),
      runningTasks: count('tasks', "WHERE state = 'running'"), events: count('events'),
      pendingOutbound: count('outbound', "WHERE state = 'pending'"), outbound: count('outbound'),
      uncertainOutbound: count('outbound', "WHERE state = 'uncertain'"),
      readReceipts: count('read_receipts'), pendingReads: count('read_receipts', "WHERE state = 'pending'"),
      feedback: count('feedback'), experiences: count('experiences'), artifacts: count('artifacts'),
      actions: count('actions'), uncertainActions: count('actions', "WHERE state = 'uncertain'"),
    };
  }

  close() {
    this.db.close();
  }
}

export default Store;
