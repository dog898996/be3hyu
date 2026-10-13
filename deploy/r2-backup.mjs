import { S3Client, GetObjectCommand, PutObjectCommand } from '@aws-sdk/client-s3';
import { DatabaseSync, backup } from 'node:sqlite';
import { createReadStream, createWriteStream } from 'node:fs';
import { cp, mkdir, mkdtemp, open, readdir, rm, stat } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { CHUNK_BYTES, validateManifest } from './snapshot-format.mjs';

const required = ['S3_ENDPOINT', 'S3_BUCKET', 'S3_ACCESS_KEY_ID', 'S3_SECRET_ACCESS_KEY'];
const s3Configured = () => required.every(key => Boolean(process.env[key]));
const httpConfigured = () => Boolean(process.env.BACKUP_ENDPOINT && process.env.BACKUP_HTTP_TOKEN);
export const storageConfigured = () => httpConfigured() || s3Configured();
const dataDir = () => resolve(process.env.DATA_DIR || '/data');
const objectKey = () => `${(process.env.S3_PREFIX || 'assistant').replace(/^\/+|\/+$/g, '')}/latest.tar.gz`;
const client = () => {
  if (!s3Configured()) throw new Error('S3 storage is not configured.');
  return new S3Client({
    endpoint: process.env.S3_ENDPOINT,
    region: process.env.S3_REGION || 'auto',
    forcePathStyle: true,
    requestChecksumCalculation: 'WHEN_REQUIRED',
    responseChecksumValidation: 'WHEN_REQUIRED',
    credentials: { accessKeyId: process.env.S3_ACCESS_KEY_ID, secretAccessKey: process.env.S3_SECRET_ACCESS_KEY },
  });
};

function run(command, args) {
  return new Promise((res, rej) => {
    const proc = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '', err = '';
    proc.stdout.on('data', chunk => { out += chunk; });
    proc.stderr.on('data', chunk => { err += chunk; });
    proc.once('error', rej);
    proc.once('exit', code => code === 0 ? res(out) : rej(new Error(`${command} failed (${code}): ${err.slice(0, 300)}`)));
  });
}

async function databaseFiles(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const full = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await databaseFiles(full));
    else if (entry.isFile() && /\.(sqlite|sqlite3|db)$/.test(entry.name)) result.push(full);
  }
  return result;
}

export async function createSnapshot(destination) {
  const source = dataDir();
  const stage = await mkdtemp(join(tmpdir(), 'assistant-snapshot-'));
  try {
    const stagedData = join(stage, 'data');
    const databases = await databaseFiles(source);
    const dbSet = new Set(databases);
    await cp(source, stagedData, {
      recursive: true,
      // SQLite online backup below includes committed WAL pages. Never copy a
      // live SQLite file or its WAL/SHM alongside the coherent backup.
      filter: path => !dbSet.has(path) && !/\.(sqlite|sqlite3|db)-(wal|shm|journal)$/.test(path)
        && !['SingletonLock', 'SingletonSocket', 'SingletonCookie'].includes(basename(path)),
    });
    for (const path of databases) {
      const target = join(stagedData, path.slice(source.length + 1));
      await mkdir(dirname(target), { recursive: true });
      const db = new DatabaseSync(path, { readOnly: true });
      try { await backup(db, target); } finally { db.close(); }
    }
    await run('tar', ['-czf', destination, '-C', stagedData, '.']);
  } finally { await rm(stage, { recursive: true, force: true }); }
}

function httpRequest(url, options = {}) {
  return fetch(url, { ...options, headers: {
    authorization: `Bearer ${process.env.BACKUP_HTTP_TOKEN}`, ...options.headers,
  }, signal: AbortSignal.timeout(120_000), redirect: 'error' });
}
function partUrl(part) {
  const url = new URL(process.env.BACKUP_ENDPOINT);
  url.searchParams.set('part', part);
  return url;
}
async function uploadHttpArchive(archive) {
  const archiveBytes = (await stat(archive)).size;
  const generation = randomUUID(), parts = [];
  const file = await open(archive, 'r');
  try {
    for (let offset = 0; offset < archiveBytes; offset += CHUNK_BYTES) {
      const size = Math.min(CHUNK_BYTES, archiveBytes - offset);
      const chunk = Buffer.allocUnsafe(size);
      let filled = 0;
      while (filled < size) {
        const { bytesRead } = await file.read(chunk, filled, size - filled, offset + filled);
        if (!bytesRead) throw new Error('Snapshot archive changed during upload.');
        filled += bytesRead;
      }
      const part = `${generation}/${parts.length}`;
      const response = await httpRequest(partUrl(part), {
        method: 'PUT', headers: { 'content-type': 'application/octet-stream' }, body: chunk,
      });
      await response.body?.cancel();
      if (!response.ok) throw new Error(`HTTP snapshot part upload failed (${response.status}).`);
      parts.push({ part, size, sha256: createHash('sha256').update(chunk).digest('hex') });
    }
    const manifest = validateManifest({ format: 'chunks-v1', generation, archiveBytes, parts, created: new Date().toISOString() });
    // The latest pointer is replaced only after every chunk has been uploaded.
    const response = await httpRequest(process.env.BACKUP_ENDPOINT, {
      method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(manifest),
    });
    await response.body?.cancel();
    if (!response.ok) throw new Error(`HTTP snapshot commit failed (${response.status}).`);
  } finally { await file.close(); }
}
async function downloadHttpArchive(manifestResponse, archive) {
  const manifest = validateManifest(await manifestResponse.json());
  const file = await open(archive, 'w');
  try {
    for (const part of manifest.parts) {
      const response = await httpRequest(partUrl(part.part));
      if (!response.ok) {
        await response.body?.cancel();
        const error = new Error(`HTTP snapshot part restore failed (${response.status}).`);
        if (response.status === 404) error.code = 'SNAPSHOT_PART_MISSING';
        throw error;
      }
      const chunk = Buffer.from(await response.arrayBuffer());
      if (chunk.length !== part.size || createHash('sha256').update(chunk).digest('hex') !== part.sha256) {
        throw new Error('Snapshot chunk integrity verification failed.');
      }
      await file.writeFile(chunk);
    }
  } finally { await file.close(); }
}

export async function backupToStorage() {
  if (!storageConfigured()) throw new Error('Configure HTTP snapshot storage or S3 storage.');
  const stage = await mkdtemp(join(tmpdir(), 'assistant-upload-'));
  const s3 = httpConfigured() ? null : client();
  try {
    const archive = join(stage, 'data.tar.gz');
    await createSnapshot(archive);
    if (httpConfigured()) {
      await uploadHttpArchive(archive);
    } else {
      await s3.send(new PutObjectCommand({
        Bucket: process.env.S3_BUCKET, Key: objectKey(),
        Body: createReadStream(archive), ContentLength: (await stat(archive)).size,
        ContentType: 'application/gzip', Metadata: { created: new Date().toISOString() },
      }));
    }
    console.log('Persistent snapshot uploaded.');
  } finally { s3?.destroy(); await rm(stage, { recursive: true, force: true }); }
}

export async function restoreFromStorage() {
  if (!storageConfigured()) throw new Error('Configure HTTP snapshot storage or S3 storage.');
  const destination = dataDir();
  await mkdir(destination, { recursive: true });
  if ((await readdir(destination)).length) {
    console.log('Existing local data kept; remote snapshot restore skipped.');
    return;
  }
  const stage = await mkdtemp(join(tmpdir(), 'assistant-restore-'));
  const s3 = httpConfigured() ? null : client();
  try {
    let response;
    try {
      if (httpConfigured()) {
        response = await httpRequest(process.env.BACKUP_ENDPOINT);
        if (response.status === 404) {
          await response.body?.cancel();
          console.log('No previous remote snapshot; starting new data directory.'); return;
        }
        if (!response.ok) {
          await response.body?.cancel();
          throw new Error(`HTTP snapshot restore failed (${response.status}).`);
        }
      } else {
        response = await s3.send(new GetObjectCommand({ Bucket: process.env.S3_BUCKET, Key: objectKey() }));
      }
    }
    catch (error) {
      if (error.name === 'NoSuchKey' || error.$metadata?.httpStatusCode === 404) {
        console.log('No previous remote snapshot; starting new data directory.'); return;
      }
      throw error;
    }
    const archive = join(stage, 'data.tar.gz');
    if (httpConfigured()) {
      for (let attempt = 0; ; attempt++) {
        try { await downloadHttpArchive(response, archive); break; }
        catch (error) {
          if (error.code !== 'SNAPSHOT_PART_MISSING' || attempt >= 2) throw error;
          response = await httpRequest(process.env.BACKUP_ENDPOINT);
          if (!response.ok) {
            await response.body?.cancel();
            throw new Error(`HTTP snapshot refresh failed (${response.status}).`);
          }
        }
      }
    }
    else await pipeline(response.Body, createWriteStream(archive));
    const entries = (await run('tar', ['-tzf', archive])).trim().split('\n');
    if (entries.some(path => path.startsWith('/') || path.split('/').includes('..'))) {
      throw new Error('Snapshot archive contains paths outside the data directory.');
    }
    // Extract into staging first; a failed extraction must not leave a partial
    // local directory that would be mistaken for valid persisted state.
    const extracted = join(stage, 'data');
    await mkdir(extracted);
    await run('tar', ['-xzf', archive, '--no-same-owner', '-C', extracted]);
    await cp(extracted, destination, { recursive: true });
    console.log('Persistent snapshot restored.');
  } finally { s3?.destroy(); await rm(stage, { recursive: true, force: true }); }
}

// Existing callers can keep their S3-era names; transport now follows settings.
export const backupToS3 = backupToStorage;
export const restoreFromS3 = restoreFromStorage;

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const command = process.argv[2];
  if (command === 'backup') await backupToStorage();
  else if (command === 'restore') await restoreFromStorage();
  else if (command === 'snapshot' && process.argv[3]) await createSnapshot(resolve(process.argv[3]));
  else throw new Error('Usage: node deploy/r2-backup.mjs backup|restore|snapshot <path>');
}
