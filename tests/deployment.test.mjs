import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, mkdir, writeFile, readFile, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { pipeline } from 'node:stream/promises';
import { handleSnapshot } from '../deploy/cloudflare/snapshot-route.mjs';
import { CHUNK_BYTES } from '../deploy/snapshot-format.mjs';
import { backupToStorage, restoreFromStorage, storageConfigured } from '../deploy/r2-backup.mjs';

function fakeBucket() {
  const objects = new Map();
  const uploaded = new Map();
  return {
    objects, uploaded,
    async put(key, value) {
      const bytes = Buffer.from(await new Response(value).arrayBuffer());
      objects.set(key, bytes);
      uploaded.set(key, new Date());
    },
    async get(key) {
      const bytes = objects.get(key);
      return bytes ? { body: new Blob([bytes]).stream(), size: bytes.length } : null;
    },
    async delete(keys) { for (const key of Array.isArray(keys) ? keys : [keys]) { objects.delete(key); uploaded.delete(key); } },
    async list({ prefix }) {
      return { objects: [...objects.keys()].filter(key => key.startsWith(prefix)).map(key => ({ key, uploaded: uploaded.get(key) })), truncated: false };
    },
  };
}

test('native R2 snapshot route requires owner authentication and strict part names', async () => {
  const env = { APP_AUTH_TOKEN: 'fixture', SNAPSHOTS: fakeBucket() };
  assert.equal((await handleSnapshot(new Request('https://fixture/internal/snapshot'), env)).status, 401);
  const headers = { authorization: 'Bearer fixture' };
  assert.equal((await handleSnapshot(new Request('https://fixture/internal/snapshot', { headers }), env)).status, 404);
  assert.equal((await handleSnapshot(new Request('https://fixture/internal/snapshot?part=../../other', { headers }), env)).status, 400);
  assert.equal((await handleSnapshot(new Request('https://fixture/internal/snapshot', { method: 'PUT', headers, body: '{"format":"invalid"}' }), env)).status, 400);
  assert.equal(env.SNAPSHOTS.objects.size, 0);
});

test('HTTP snapshots chunk large archives, retain WAL memory, and reject corruption before restore', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'http-snapshot-test-'));
  const env = { APP_AUTH_TOKEN: 'fixture', SNAPSHOTS: fakeBucket() };
  let failPartUpload = false;
  let staleManifest = null;
  const server = http.createServer(async (req, res) => {
    try {
      if (staleManifest && req.method === 'GET' && !new URL(req.url, 'http://fixture').searchParams.has('part')) {
        const stale = staleManifest; staleManifest = null;
        res.writeHead(200, { 'content-type': 'application/json' }); res.end(stale); return;
      }
      if (failPartUpload && req.method === 'PUT' && new URL(req.url, 'http://fixture').searchParams.has('part')) {
        req.resume(); res.writeHead(503); res.end(); return;
      }
      const request = new Request(`http://fixture${req.url}`, {
        method: req.method, headers: req.headers,
        ...(req.method === 'PUT' ? { body: req, duplex: 'half' } : {}),
      });
      const response = await handleSnapshot(request, env);
      res.writeHead(response.status, Object.fromEntries(response.headers));
      if (response.body) await pipeline(response.body, res); else res.end();
    } catch { res.writeHead(500); res.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const keys = ['DATA_DIR', 'BACKUP_ENDPOINT', 'BACKUP_HTTP_TOKEN', 'S3_ENDPOINT', 'S3_BUCKET', 'S3_ACCESS_KEY_ID', 'S3_SECRET_ACCESS_KEY'];
  const previous = new Map(keys.map(key => [key, process.env[key]]));
  let db;
  try {
    for (const key of keys) delete process.env[key];
    process.env.DATA_DIR = join(directory, 'data');
    process.env.BACKUP_ENDPOINT = `http://127.0.0.1:${server.address().port}/internal/snapshot`;
    process.env.BACKUP_HTTP_TOKEN = 'fixture';
    assert.equal(storageConfigured(), true);
    await restoreFromStorage();
    db = new DatabaseSync(join(process.env.DATA_DIR, 'assistant.sqlite'));
    db.exec("PRAGMA journal_mode=WAL; CREATE TABLE memories(value TEXT); INSERT INTO memories VALUES ('committed WAL memory');");
    const large = randomBytes(CHUNK_BYTES + 1024 * 1024);
    await writeFile(join(process.env.DATA_DIR, 'large-artifact.bin'), large);
    await backupToStorage();
    const latest = Buffer.from(env.SNAPSHOTS.objects.get('assistant/latest.json'));
    const manifest = JSON.parse(latest);
    assert.ok(manifest.parts.length > 1);
    assert.ok(manifest.parts.every(part => part.size <= CHUNK_BYTES));
    assert.ok(env.SNAPSHOTS.objects.has(`assistant/manifests/${manifest.generation}.json`));

    failPartUpload = true;
    await assert.rejects(backupToStorage(), /part upload failed/);
    assert.deepEqual(env.SNAPSHOTS.objects.get('assistant/latest.json'), latest);
    failPartUpload = false;

    db.close(); db = null;
    await rm(process.env.DATA_DIR, { recursive: true });
    await restoreFromStorage();
    assert.deepEqual(await readFile(join(process.env.DATA_DIR, 'large-artifact.bin')), large);
    db = new DatabaseSync(join(process.env.DATA_DIR, 'assistant.sqlite'), { readOnly: true });
    assert.equal(db.prepare('SELECT value FROM memories').get().value, 'committed WAL memory');
    db.close(); db = null;
    await writeFile(join(process.env.DATA_DIR, 'local-newer.txt'), 'keep');
    await restoreFromStorage();
    assert.equal(await readFile(join(process.env.DATA_DIR, 'local-newer.txt'), 'utf8'), 'keep');

    await backupToStorage();
    const currentManifest = JSON.parse(env.SNAPSHOTS.objects.get('assistant/latest.json'));
    env.SNAPSHOTS.objects.delete(`assistant/chunks/${manifest.parts[0].part}`);
    await rm(process.env.DATA_DIR, { recursive: true });
    staleManifest = latest;
    await restoreFromStorage();
    assert.equal(await readFile(join(process.env.DATA_DIR, 'local-newer.txt'), 'utf8'), 'keep');

    await rm(process.env.DATA_DIR, { recursive: true });
    env.SNAPSHOTS.objects.get(`assistant/chunks/${currentManifest.parts[0].part}`)[0] ^= 1;
    await assert.rejects(restoreFromStorage(), /integrity verification failed/);
    assert.deepEqual(await readdir(process.env.DATA_DIR), []);
  } finally {
    db?.close();
    for (const [key, value] of previous) value === undefined ? delete process.env[key] : process.env[key] = value;
    await new Promise(resolve => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});
