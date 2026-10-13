import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, realpath, lstat, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ensureCodexPersistence } from '../deploy/codex-storage.mjs';

test('fresh Codex state persists across creation while pre-existing configuration stays untouched', async () => {
  const root = await mkdtemp(join(tmpdir(), 'codex-persistence-test-'));
  try {
    const dataDir = join(root, 'data'), fresh = join(root, 'fresh');
    assert.deepEqual(await ensureCodexPersistence(dataDir, fresh), { persisted: true, created: true });
    await writeFile(join(fresh, 'auth-fixture.json'), 'persistent fixture');
    assert.equal(await readFile(join(dataDir, 'codex/auth-fixture.json'), 'utf8'), 'persistent fixture');
    assert.deepEqual(await ensureCodexPersistence(dataDir, fresh), { persisted: true, created: false });
    assert.equal(await realpath(fresh), join(dataDir, 'codex'));

    const existing = join(root, 'existing');
    await mkdir(existing); await writeFile(join(existing, 'owner-config'), 'existing fixture');
    assert.deepEqual(await ensureCodexPersistence(dataDir, existing), { persisted: false, created: false });
    assert.equal((await lstat(existing)).isSymbolicLink(), false);
    assert.equal(await readFile(join(existing, 'owner-config'), 'utf8'), 'existing fixture');

    const dangling = join(root, 'dangling'); await symlink(join(root, 'missing'), dangling);
    assert.deepEqual(await ensureCodexPersistence(dataDir, dangling), { persisted: false, created: false });
    assert.equal((await lstat(dangling)).isSymbolicLink(), true);
  } finally { await rm(root, { recursive: true, force: true }); }
});
