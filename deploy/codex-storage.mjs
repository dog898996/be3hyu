import { lstat, mkdir, realpath, symlink } from 'node:fs/promises';
import { resolve } from 'node:path';

export async function ensureCodexPersistence(dataDir, codexHome) {
  const codexData = resolve(dataDir, 'codex');
  await mkdir(codexData, { recursive: true, mode: 0o700 });
  let existing;
  try { existing = await lstat(codexHome); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (!existing) {
    await symlink(codexData, codexHome, 'dir');
    return { persisted: true, created: true };
  }
  let same = false;
  try { same = await realpath(codexHome) === await realpath(codexData); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  return { persisted: same, created: false };
}
