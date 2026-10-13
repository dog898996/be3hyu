export const CHUNK_BYTES = 8 * 1024 * 1024;
export const GENERATION_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const PART_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/(0|[1-9][0-9]{0,8})$/;

export function validateManifest(manifest) {
  if (!manifest || manifest.format !== 'chunks-v1' || !GENERATION_PATTERN.test(manifest.generation || '')
    || !Array.isArray(manifest.parts) || !manifest.parts.length || manifest.parts.length > 4096) {
    throw new Error('Invalid snapshot manifest.');
  }
  let bytes = 0;
  for (const [index, part] of manifest.parts.entries()) {
    if (part.part !== `${manifest.generation}/${index}` || !Number.isSafeInteger(part.size)
      || part.size < 1 || part.size > CHUNK_BYTES || !/^[0-9a-f]{64}$/.test(part.sha256 || '')) {
      throw new Error('Invalid snapshot manifest part.');
    }
    bytes += part.size;
  }
  if (!Number.isSafeInteger(manifest.archiveBytes) || manifest.archiveBytes !== bytes) {
    throw new Error('Snapshot manifest size does not match its parts.');
  }
  return manifest;
}
