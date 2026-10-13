import { mkdir, readdir, lstat, realpath } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

/** Install a tool by writing <dataDir>/tools/<file>.mjs:
 * export default {
 *   name: 'my_tool', description: 'What it does', capability: 'agents',
 *   parameters: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] },
 *   async execute(args, context) { return { result: args.text }; }
 * };
 * context includes signal, authority, taskId, sourceMessageIds, dataDir, and services
 * {store,browser,terminal}. The owning runtime records action results and checkpoints.
 * Installed modules are trusted executable code, not sandboxed data. Their capability
 * is checked at invocation; installation does not override builtins or owner settings.
 * New/changed modules appear in the next definitions() call without restarting.
 */
export const BUILTIN_TOOL_NAMES = Object.freeze(['browser', 'terminal', 'process', 'files', 'memory', 'goals', 'messages', 'settings', 'agents']);
const CAPABILITIES = ['browser', 'terminal', 'files', 'memory', 'goals', 'messages', 'settings', 'agents'];

export class ExtensionError extends Error {
  constructor(message, code = 'EXTENSION_ERROR', details = {}) {
    super(message); this.name = 'ExtensionError'; this.code = code; this.details = details;
  }
}

function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function permitted(settings, capability) {
  const permissions = settings?.permissions ?? settings;
  const value = permissions?.capabilities?.[capability];
  return typeof value === 'boolean' ? value : permissions?.default === 'allow';
}

function validateDefinition(value, { filename, capabilities, builtinNames }) {
  if (!object(value) || typeof value.name !== 'string' || !/^[A-Za-z_][A-Za-z0-9_-]{0,63}$/.test(value.name)) {
    throw new ExtensionError('Module must export a tool with a valid function name.', 'EXTENSION_MANIFEST_INVALID', { filename });
  }
  if (builtinNames.has(value.name)) throw new ExtensionError(`Extension cannot replace builtin tool ${value.name}.`, 'EXTENSION_NAME_COLLISION', { filename, name: value.name });
  if (typeof value.description !== 'string' || !value.description.trim() || typeof value.execute !== 'function') {
    throw new ExtensionError('Tool needs a description and execute(args, context) function.', 'EXTENSION_MANIFEST_INVALID', { filename });
  }
  if (typeof value.capability !== 'string' || !capabilities.has(value.capability)) {
    throw new ExtensionError('Tool capability must be a registered permission capability.', 'EXTENSION_CAPABILITY_INVALID', { filename });
  }
  let parameters;
  try { parameters = JSON.parse(JSON.stringify(value.parameters)); }
  catch { throw new ExtensionError('Tool parameters must be a JSON object schema.', 'EXTENSION_SCHEMA_INVALID', { filename }); }
  if (!object(parameters) || parameters.type !== 'object'
    || (parameters.properties !== undefined && (!object(parameters.properties) || Object.values(parameters.properties).some(v => !object(v) && typeof v !== 'boolean')))
    || (parameters.required !== undefined && (!Array.isArray(parameters.required) || parameters.required.some(v => typeof v !== 'string')))
    || (parameters.additionalProperties !== undefined && typeof parameters.additionalProperties !== 'boolean' && !object(parameters.additionalProperties))) {
    throw new ExtensionError('Tool parameters must have type object, valid properties, and a string required list.', 'EXTENSION_SCHEMA_INVALID', { filename });
  }
  return { name: value.name, description: value.description, parameters, capability: value.capability, execute: value.execute, filename };
}

/** Returns {tools: Map<string, definition>, errors: [{filename,code,message}]}.
 * A single malformed extension stays visible as an error without hiding valid tools.
 * Pass a Map cache to retain imported modules by nanosecond mtime and size.
 */
export async function loadToolExtensions(dataDir, { permissions = {}, builtinNames = BUILTIN_TOOL_NAMES, cache = new Map(), force = false } = {}) {
  const toolsDir = path.resolve(dataDir, 'tools');
  await mkdir(toolsDir, { recursive: true });
  const [dataRoot, actualTools] = await Promise.all([realpath(path.resolve(dataDir)), realpath(toolsDir)]);
  const errors = [], tools = new Map(), duplicateNames = new Set(), seenFiles = new Set();
  if (actualTools !== path.join(dataRoot, 'tools')) {
    return { tools, errors: [{ filename: toolsDir, code: 'EXTENSION_PATH_INVALID', message: 'Tools directory must be a direct directory in the persistent data root.' }] };
  }
  const capabilities = new Set([...CAPABILITIES, ...Object.keys(permissions.capabilities ?? {})]);
  const reserved = new Set([...BUILTIN_TOOL_NAMES, ...builtinNames]);
  const filenames = (await readdir(toolsDir)).filter(name => name.endsWith('.mjs')).sort();
  for (const filename of filenames) {
    const fullPath = path.join(toolsDir, filename);
    seenFiles.add(fullPath);
    try {
      const stat = await lstat(fullPath, { bigint: true });
      if (!stat.isFile() || stat.isSymbolicLink() || path.dirname(await realpath(fullPath)) !== actualTools) {
        throw new ExtensionError('Extension must be a regular module directly in the tools directory.', 'EXTENSION_PATH_INVALID');
      }
      const signature = `${stat.mtimeNs}:${stat.size}:${stat.ino}`;
      let entry = cache.get(fullPath);
      if (force || entry?.signature !== signature) {
        const url = pathToFileURL(fullPath);
        url.searchParams.set('revision', force ? `${signature}:${Date.now()}:${Math.random()}` : signature);
        try { entry = { signature, module: await import(url.href) }; }
        catch (error) { entry = { signature, error: new ExtensionError(`Cannot load module: ${error.message}`, 'EXTENSION_IMPORT_FAILED') }; }
        cache.set(fullPath, entry);
      }
      if (entry.error) throw entry.error;
      const definition = validateDefinition(entry.module.default, { filename, capabilities, builtinNames: reserved });
      if (tools.has(definition.name) || duplicateNames.has(definition.name)) {
        tools.delete(definition.name); duplicateNames.add(definition.name);
        throw new ExtensionError(`Multiple extensions register ${definition.name}; none is selected.`, 'EXTENSION_NAME_COLLISION');
      }
      tools.set(definition.name, definition);
    } catch (error) { errors.push({ filename, code: error.code ?? 'EXTENSION_LOAD_FAILED', message: error.message }); }
  }
  for (const filename of cache.keys()) if (!seenFiles.has(filename)) cache.delete(filename);
  return { tools, errors };
}

function validateArguments(args, schema) {
  if (!object(args)) throw new ExtensionError('Extension arguments must be an object.', 'EXTENSION_ARGUMENTS_INVALID');
  for (const name of schema.required ?? []) {
    if (!Object.hasOwn(args, name)) throw new ExtensionError(`Missing required argument: ${name}.`, 'EXTENSION_ARGUMENTS_INVALID');
  }
  for (const [name, value] of Object.entries(args)) {
    const property = schema.properties?.[name];
    if (property === undefined) {
      if (schema.additionalProperties === false) throw new ExtensionError(`Unexpected argument: ${name}.`, 'EXTENSION_ARGUMENTS_INVALID');
      continue;
    }
    if (property === false) throw new ExtensionError(`Argument ${name} is disallowed.`, 'EXTENSION_ARGUMENTS_INVALID');
    if (property === true) continue;
    const type = value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value;
    const types = Array.isArray(property.type) ? property.type : property.type ? [property.type] : [];
    if (types.length && !types.some(expected => expected === type || expected === 'integer' && Number.isInteger(value))) {
      throw new ExtensionError(`Argument ${name} has an invalid type.`, 'EXTENSION_ARGUMENTS_INVALID');
    }
    if (property.enum && !property.enum.some(candidate => JSON.stringify(candidate) === JSON.stringify(value))) {
      throw new ExtensionError(`Argument ${name} is outside its enum.`, 'EXTENSION_ARGUMENTS_INVALID');
    }
  }
}

export class ExtensibleTools {
  constructor({ dataDir, services = {}, builtinNames = BUILTIN_TOOL_NAMES } = {}) {
    if (!dataDir) throw new ExtensionError('Persistent dataDir is required.', 'EXTENSION_CONFIG_INVALID');
    Object.assign(this, { dataDir, services, builtinNames });
    this.cache = new Map(); this.manifest = new Map(); this.errors = []; this.loading = Promise.resolve();
  }

  async reload({ force = false, settings } = {}) {
    const next = this.loading.catch(() => {}).then(async () => {
      const current = settings ?? this.services.store?.getSettings?.() ?? { permissions: { default: 'deny', capabilities: {} } };
      const result = await loadToolExtensions(this.dataDir, { permissions: current.permissions, builtinNames: this.builtinNames, cache: this.cache, force });
      this.manifest = result.tools; this.errors = result.errors;
      return { tools: [...result.tools.values()].map(({ execute, ...definition }) => definition), errors: result.errors };
    });
    this.loading = next; return next;
  }

  async definitions(settings) {
    const current = settings ?? this.services.store?.getSettings?.();
    await this.reload({ settings: current });
    return [...this.manifest.values()].filter(tool => permitted(current, tool.capability))
      .map(tool => ({ type: 'function', name: tool.name, description: tool.description, parameters: structuredClone(tool.parameters) }));
  }

  async run(name, args = {}, context = {}) {
    if (!['owner', 'autonomous'].includes(context.authority)) throw new ExtensionError('Only the owner or delegated autonomous agent can invoke extension tools.', 'EXTENSION_AUTHORITY_DENIED');
    await this.reload();
    const tool = this.manifest.get(name);
    if (!tool) throw new ExtensionError(`No valid installed extension tool: ${name}.`, 'EXTENSION_NOT_REGISTERED');
    const settings = this.services.store?.getSettings?.() ?? context.settings;
    if (!permitted(settings, tool.capability)) throw new ExtensionError(`Capability disabled: ${tool.capability}.`, 'EXTENSION_PERMISSION_DENIED');
    context.signal?.throwIfAborted();
    validateArguments(args, tool.parameters);
    // The live owner permission is checked after loading, immediately before the effect.
    return tool.execute(args, { ...context, dataDir: this.dataDir, settings, services: this.services });
  }
}
