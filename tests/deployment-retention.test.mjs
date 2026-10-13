import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { handleSnapshot } from '../deploy/cloudflare/snapshot-route.mjs';

test('native snapshot commits retain three generations and reclaim only old owned objects', async () => {
  const objects = new Map(), uploaded = new Map();
  let failDelete = false;
  const bucket = {
    objects,
    async put(key, value) { objects.set(key, Buffer.from(await new Response(value).arrayBuffer())); uploaded.set(key, new Date()); },
    async get(key) { const bytes = objects.get(key); return bytes ? { body: new Blob([bytes]).stream(), size: bytes.length } : null; },
    async delete(keys) {
      if (failDelete) throw new Error('temporary delete failure fixture');
      for (const key of Array.isArray(keys) ? keys : [keys]) { objects.delete(key); uploaded.delete(key); }
    },
    async list({ prefix }) { return { objects: [...objects.keys()].filter(key => key.startsWith(prefix)).map(key => ({ key, uploaded: uploaded.get(key) })), truncated: false }; },
  };
  const env = { APP_AUTH_TOKEN: 'fixture', SNAPSHOTS: bucket };
  const headers = { authorization: 'Bearer fixture', 'content-type': 'application/json' };
  const generations = [];
  for (let index = 0; index < 5; index++) {
    // The fourth commit must still publish its valid snapshot when an old
    // generation cleanup fails; the fifth commit retries that pending deletion.
    failDelete = index === 3;
    const generation = randomUUID(), bytes = Buffer.from(`snapshot${index}`), part = `${generation}/0`;
    generations.push(generation);
    await bucket.put(`assistant/chunks/${part}`, bytes);
    const manifest = { format: 'chunks-v1', generation, archiveBytes: bytes.length, parts: [{ part, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') }] };
    assert.equal((await handleSnapshot(new Request('https://fixture/internal/snapshot', { method: 'PUT', headers, body: JSON.stringify(manifest) }), env)).status, 204);
    if (index === 3) {
      const checkpoint = JSON.parse(objects.get('assistant/latest.json'));
      assert.ok(checkpoint.cleanupGenerations.includes(generations[0]));
      assert.equal(objects.has(`assistant/chunks/${generations[0]}/0`), true);
    }
  }
  const latest = JSON.parse(objects.get('assistant/latest.json'));
  assert.deepEqual(latest.retainedGenerations, generations.slice(2).reverse());
  for (const generation of generations.slice(0, 2)) {
    assert.equal(objects.has(`assistant/chunks/${generation}/0`), false);
    assert.equal(objects.has(`assistant/manifests/${generation}.json`), false);
  }
  for (const generation of generations.slice(2)) assert.equal(objects.has(`assistant/chunks/${generation}/0`), true);
  const old = `assistant/chunks/${randomUUID()}/0`, fresh = `assistant/chunks/${randomUUID()}/0`, outside = `another-app/chunks/${randomUUID()}/0`;
  for (const key of [old, fresh, outside]) await bucket.put(key, 'uncommitted');
  uploaded.set(old, new Date(Date.now() - 25 * 3600_000));
  uploaded.set(outside, new Date(Date.now() - 25 * 3600_000));
  for (const generation of latest.retainedGenerations) uploaded.set(`assistant/chunks/${generation}/0`, new Date(Date.now() - 25 * 3600_000));
  assert.equal((await handleSnapshot(new Request('https://fixture/internal/snapshot', { method: 'PUT', headers, body: JSON.stringify(latest) }), env)).status, 204);
  assert.equal(objects.has(old), false);
  assert.equal(objects.has(fresh), true);
  assert.equal(objects.has(outside), true);
  for (const generation of latest.retainedGenerations) assert.equal(objects.has(`assistant/chunks/${generation}/0`), true);
});
