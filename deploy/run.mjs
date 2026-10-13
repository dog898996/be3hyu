import { spawn } from 'node:child_process';
import { mkdir, access } from 'node:fs/promises';
import { resolve } from 'node:path';
import { homedir } from 'node:os';
import { backupToStorage, restoreFromStorage, storageConfigured } from './r2-backup.mjs';
import { ensureCodexPersistence } from './codex-storage.mjs';

const dataDir = resolve(process.env.DATA_DIR || '/data');
await mkdir(dataDir, { recursive: true });
if (process.env.REQUIRE_BACKUP === 'true' && !storageConfigured()) {
  throw new Error('REQUIRE_BACKUP=true needs BACKUP_ENDPOINT+BACKUP_HTTP_TOKEN or all S3 storage settings.');
}
if (storageConfigured()) await restoreFromStorage();

if (process.env.PERSIST_CODEX_AUTH === 'true') {
  const result = await ensureCodexPersistence(dataDir, resolve(homedir(), '.codex'));
  if (!result.persisted) console.error('Existing Codex configuration path kept; it is not linked to the persistent data volume.');
}

// Node's NODE_EXTRA_CA_CERTS does not configure Chromium. The managed
// development proxy's public CA can be mounted and explicitly trusted at boot.
if (process.env.CHROMIUM_PROXY_CA_FILE) {
  const nssDir = resolve(homedir(), '.pki/nssdb');
  await mkdir(nssDir, { recursive: true });
  const execute = args => new Promise((res, rej) => {
    const proc = spawn('certutil', args, { stdio: 'inherit' });
    proc.once('error', rej);
    proc.once('exit', code => code === 0 ? res() : rej(new Error(`Chromium CA import failed (${code}).`)));
  });
  try { await access(resolve(nssDir, 'cert9.db')); }
  catch { await execute(['-N', '-d', `sql:${nssDir}`, '--empty-password']); }
  await execute(['-A', '-d', `sql:${nssDir}`, '-n', 'managed-development-proxy', '-t', 'C,,', '-i', process.env.CHROMIUM_PROXY_CA_FILE]);
}

// Agent-installed Python/Node packages live on the same persistent volume.
if (process.env.VIRTUAL_ENV) {
  const python = resolve(process.env.VIRTUAL_ENV, 'bin/python');
  try { await access(python); }
  catch {
    await new Promise((res, rej) => {
      const installer = spawn('python3', ['-m', 'venv', process.env.VIRTUAL_ENV], { stdio: 'inherit' });
      installer.once('error', rej);
      installer.once('exit', code => code === 0 ? res() : rej(new Error(`Python venv setup failed (${code}).`)));
    });
  }
}

const server = spawn(process.execPath, ['src/server.mjs'], { stdio: 'inherit', env: process.env });
let snapshot = null;
function save() {
  if (!storageConfigured()) return Promise.resolve();
  snapshot ??= backupToStorage().catch(error => {
    console.error('Persistent snapshot failed:', error.message);
  }).finally(() => { snapshot = null; });
  return snapshot;
}
const intervalSeconds = Math.max(30, Number(process.env.BACKUP_INTERVAL_SECONDS) || 300);
const timer = storageConfigured() ? setInterval(save, intervalSeconds * 1000) : null;
let stopping = false;
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    if (stopping) return;
    stopping = true;
    if (timer) clearInterval(timer);
    server.kill(signal);
    // Allow enough time for the application to close its browser and for a final snapshot.
    setTimeout(() => server.kill('SIGKILL'), 60_000).unref();
  });
}
server.once('error', error => { console.error('Server launch failed:', error.message); process.exit(1); });
server.once('exit', async (code, signal) => {
  if (timer) clearInterval(timer);
  if (snapshot) await snapshot;
  await save();
  process.exit(code ?? (stopping ? 0 : 1));
});
