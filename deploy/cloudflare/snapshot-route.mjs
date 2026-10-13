import { CHUNK_BYTES, GENERATION_PATTERN, PART_PATTERN, validateManifest } from '../snapshot-format.mjs';

function equal(a, b) {
  const x = new TextEncoder().encode(a || ''), y = new TextEncoder().encode(b || '');
  if (x.length !== y.length) return false;
  let difference = 0;
  for (let i = 0; i < x.length; i++) difference |= x[i] ^ y[i];
  return difference === 0;
}

async function readManifest(bucket, key) {
  const object = await bucket.get(key);
  if (!object) return null;
  return validateManifest(await new Response(object.body).json());
}

async function deleteKeys(bucket, keys) {
  for (let index = 0; index < keys.length; index += 128) await bucket.delete(keys.slice(index, index + 128));
}

async function cleanupSnapshots(bucket, prefix, keep, obsolete) {
  const retained = new Set(keep);
  for (const generation of obsolete) {
    if (retained.has(generation)) continue;
    const key = `${prefix}/manifests/${generation}.json`;
    const manifest = await readManifest(bucket, key);
    if (manifest) await deleteKeys(bucket, manifest.parts.map(part => `${prefix}/chunks/${part.part}`));
    await bucket.delete(key);
  }
  const cutoff = Date.now() - 24 * 60 * 60 * 1000;
  for (const category of ['chunks', 'manifests']) {
    const objectPrefix = `${prefix}/${category}/`;
    let cursor;
    do {
      const page = await bucket.list({ prefix: objectPrefix, cursor, limit: 1000 });
      const remove = [];
      for (const object of page.objects) {
        const relative = object.key.slice(objectPrefix.length);
        const generation = category === 'chunks' ? relative.split('/')[0] : relative.replace(/\.json$/, '');
        if (!GENERATION_PATTERN.test(generation) || retained.has(generation)) continue;
        if (new Date(object.uploaded).getTime() < cutoff) remove.push(object.key);
      }
      await deleteKeys(bucket, remove);
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
  }
}

export async function handleSnapshot(request, env, context) {
  if (!env.APP_AUTH_TOKEN || !env.SNAPSHOTS) {
    return Response.json({ error: 'Snapshot storage is not configured.' }, { status: 503 });
  }
  if (!equal(request.headers.get('authorization'), `Bearer ${env.APP_AUTH_TOKEN}`)) {
    return Response.json({ error: 'Authentication required.' }, { status: 401 });
  }
  const prefix = String(env.S3_PREFIX || 'assistant').replace(/^\/+|\/+$/g, '');
  const part = new URL(request.url).searchParams.get('part');
  if (part != null && !PART_PATTERN.test(part)) {
    return Response.json({ error: 'Invalid snapshot part.' }, { status: 400 });
  }
  const key = part != null ? `${prefix}/chunks/${part}` : `${prefix}/latest.json`;
  if (request.method === 'GET') {
    const object = await env.SNAPSHOTS.get(key);
    if (!object) return new Response(null, { status: 404 });
    return new Response(object.body, { headers: {
      'content-type': part != null ? 'application/octet-stream' : 'application/json', 'cache-control': 'no-store',
      ...(object.size != null ? { 'content-length': String(object.size) } : {}),
      ...(object.httpEtag ? { etag: object.httpEtag } : {}),
    } });
  }
  if (request.method === 'PUT') {
    if (!request.body) return Response.json({ error: 'Snapshot body is required.' }, { status: 400 });
    if (part != null) {
      const contentLength = Number(request.headers.get('content-length'));
      if (contentLength > CHUNK_BYTES) return new Response(null, { status: 413 });
      // R2.put needs a known-length body. Buffer at most one 8MiB part rather
      // than handing it a transformed stream with unknown length.
      let received = 0;
      const chunks = [], reader = request.body.getReader();
      try {
        for (;;) {
          const next = await reader.read();
          if (next.done) break;
          received += next.value.byteLength;
          if (received > CHUNK_BYTES) { await reader.cancel(); return new Response(null, { status: 413 }); }
          chunks.push(next.value);
        }
      } finally { reader.releaseLock(); }
      const bytes = new Uint8Array(received);
      let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
      await env.SNAPSHOTS.put(key, bytes, {
        httpMetadata: { contentType: 'application/octet-stream' },
        customMetadata: { created: new Date().toISOString() },
      });
    } else {
      let manifest;
      try { manifest = validateManifest(await request.json()); }
      catch { return Response.json({ error: 'Invalid snapshot manifest.' }, { status: 400 }); }
      const previous = await readManifest(env.SNAPSHOTS, key);
      const previousGenerations = [...new Set([
        previous?.generation, ...(previous?.retainedGenerations || []),
      ].filter(generation => GENERATION_PATTERN.test(generation || '') && generation !== manifest.generation))];
      const retainedGenerations = [manifest.generation, ...previousGenerations].slice(0, 3);
      const cleanupGenerations = [];
      for (const generation of [...new Set([
        ...(Array.isArray(previous?.cleanupGenerations) ? previous.cleanupGenerations : []),
        ...previousGenerations.slice(2),
      ])]) {
        if (!GENERATION_PATTERN.test(generation || '') || retainedGenerations.includes(generation)) continue;
        // Successful cleanup removes this manifest after its parts. Persist
        // pending generations so transient delete errors retry on the next commit.
        if (await env.SNAPSHOTS.get(`${prefix}/manifests/${generation}.json`)) cleanupGenerations.push(generation);
      }
      manifest = { ...manifest, retainedGenerations, cleanupGenerations };
      const serialized = JSON.stringify(manifest);
      await env.SNAPSHOTS.put(`${prefix}/manifests/${manifest.generation}.json`, serialized, {
        httpMetadata: { contentType: 'application/json' },
        customMetadata: { created: new Date().toISOString() },
      });
      await env.SNAPSHOTS.put(key, serialized, {
        httpMetadata: { contentType: 'application/json' },
        customMetadata: { created: new Date().toISOString() },
      });
      const cleanup = cleanupSnapshots(env.SNAPSHOTS, prefix, retainedGenerations, cleanupGenerations)
        .catch(error => console.error('Snapshot cleanup failed:', error.message));
      if (context?.waitUntil) context.waitUntil(cleanup);
      else await cleanup;
    }
    return new Response(null, { status: 204 });
  }
  return new Response(null, { status: 405, headers: { allow: 'GET, PUT' } });
}
