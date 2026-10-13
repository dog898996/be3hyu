import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { ExtensibleTools } from '../src/extensions.mjs';

async function fixture(t) {
  const dataDir = await mkdtemp(path.join(tmpdir(), 'assistant-extensions-'));
  await mkdir(path.join(dataDir, 'tools'));
  t.after(() => rm(dataDir, { recursive: true, force: true }));
  const settings = { permissions: { default: 'allow', capabilities: { agents: true, memory: true } } };
  const services = { store: { getSettings: () => settings }, marker: 'actual-service-reference' };
  return { dataDir, settings, services, manager: new ExtensibleTools({ dataDir, services }),
    install: (filename, source) => writeFile(path.join(dataDir, 'tools', filename), source) };
}
const moduleSource = (name = 'echo_extension', expression = 'args.text') => `export default {
  name: ${JSON.stringify(name)}, description: 'Return real plugin output', capability: 'agents',
  parameters: { type:'object', properties:{ text:{ type:'string' } }, required:['text'], additionalProperties:false },
  async execute(args, context) { return { text: ${expression}, marker:context.services.marker, authority:context.authority }; }
};`;

test('new tools become available next model iteration and execute with host services', async t => {
  const f = await fixture(t);
  assert.deepEqual(await f.manager.definitions(f.settings), []);
  await f.install('echo.mjs', moduleSource());
  assert.equal((await f.manager.definitions(f.settings))[0].name, 'echo_extension');
  assert.deepEqual(await f.manager.run('echo_extension', { text: 'real output' }, { authority: 'owner' }),
    { text: 'real output', marker: 'actual-service-reference', authority: 'owner' });
  await f.install('echo.mjs', moduleSource('echo_extension', 'args.text.toUpperCase()'));
  assert.equal((await f.manager.run('echo_extension', { text: 'updated' }, { authority: 'autonomous' })).text, 'UPDATED');
  await rm(path.join(f.dataDir, 'tools', 'echo.mjs'));
  assert.deepEqual(await f.manager.definitions(f.settings), []);
  await assert.rejects(f.manager.run('echo_extension', { text: 'removed' }, { authority: 'owner' }), { code: 'EXTENSION_NOT_REGISTERED' });
});

test('live permission changes and external DM authority deny invocation', async t => {
  const f = await fixture(t);
  await f.install('echo.mjs', moduleSource());
  await f.manager.definitions(f.settings);
  f.settings.permissions.capabilities.agents = false;
  assert.deepEqual(await f.manager.definitions(f.settings), []);
  await assert.rejects(f.manager.run('echo_extension', { text: 'no' }, { authority: 'owner' }), { code: 'EXTENSION_PERMISSION_DENIED' });
  f.settings.permissions.capabilities.agents = true;
  await assert.rejects(f.manager.run('echo_extension', { text: 'no' }, { authority: 'third_party' }), { code: 'EXTENSION_AUTHORITY_DENIED' });
});

test('builtin collisions, duplicate names and malformed modules remain diagnostic errors', async t => {
  const f = await fixture(t);
  await f.install('builtin.mjs', moduleSource('terminal'));
  await f.install('first.mjs', moduleSource('duplicated'));
  await f.install('second.mjs', moduleSource('duplicated'));
  await f.install('syntax.mjs', 'export default { broken syntax');
  await f.install('schema.mjs', `export default {name:'bad_schema',description:'Invalid',capability:'agents',parameters:{type:'string'},execute(){}};`);
  assert.deepEqual(await f.manager.definitions(f.settings), []);
  assert.ok(f.manager.errors.some(error => error.code === 'EXTENSION_NAME_COLLISION'));
  assert.ok(f.manager.errors.some(error => error.code === 'EXTENSION_IMPORT_FAILED'));
  assert.ok(f.manager.errors.some(error => error.code === 'EXTENSION_SCHEMA_INVALID'));
});

test('regular .mjs files only; symlinked modules are never implicitly imported', async t => {
  const f = await fixture(t);
  await writeFile(path.join(f.dataDir, 'outside.mjs'), moduleSource('outside_tool'));
  await symlink(path.join(f.dataDir, 'outside.mjs'), path.join(f.dataDir, 'tools', 'linked.mjs'));
  assert.deepEqual(await f.manager.definitions(f.settings), []);
  assert.equal(f.manager.errors[0].code, 'EXTENSION_PATH_INVALID');
});

test('argument contract is enforced before extension effects', async t => {
  const f = await fixture(t);
  await f.install('echo.mjs', moduleSource());
  for (const args of [{}, { text: 4 }, { text: 'yes', unexpected: true }, []]) {
    await assert.rejects(f.manager.run('echo_extension', args, { authority: 'owner' }), { code: 'EXTENSION_ARGUMENTS_INVALID' });
  }
  const signal = AbortSignal.abort(new Error('Owner stopped this operation'));
  await assert.rejects(f.manager.run('echo_extension', { text: 'cancelled' }, { authority: 'owner', signal }), /Owner stopped/);
});
