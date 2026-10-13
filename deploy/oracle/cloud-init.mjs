import { randomBytes } from 'node:crypto';
import { lstat, mkdir, readFile, realpath, stat, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const MAX_USER_DATA_BYTES = 16_000;

export function validateSourceUrl(value) {
  if (typeof value !== 'string' || /[\r\n\0]/.test(value)) throw new Error('Supply one private OCI Object Storage HTTPS object PAR URL.');
  let url;
  try { url = new URL(value.trim()); } catch { throw new Error('Invalid source URL.'); }
  const legacy = /^objectstorage\.[a-z0-9-]+\.oraclecloud\.com$/;
  const dedicated = /^[a-z0-9-]+\.objectstorage\.[a-z0-9-]+\.oci\.customer-oci\.com$/;
  if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hash || url.search
    || !(legacy.test(url.hostname) || dedicated.test(url.hostname))
    || !/^\/p\/[^/]+\/n\/[^/]+\/b\/[^/]+\/o\/.+/.test(url.pathname)) {
    throw new Error('Use an OCI HTTPS object PAR URL without userinfo, query, port or fragment.');
  }
  return url.href;
}

function validateOptions(options) {
  if (!/^[a-f0-9]{64}$/i.test(options.sourceSha256 || '')) throw new Error('A64hex source archive SHA-256 is required.');
  const installDir = options.installDir || '/opt/hoyoung';
  if (!/^\/[a-zA-Z0-9_./-]+$/.test(installDir) || resolve(installDir) === '/') throw new Error('Use an absolute non-root install directory without spaces.');
  const hostname = options.hostname || '';
  if (hostname && (!/^([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$/.test(hostname) || hostname.length > 253)) {
    throw new Error('Invalid DNS hostname.');
  }
  const publicIp = options.publicIp || '';
  if (publicIp && (!/^([0-9]{1,3}\.){3}[0-9]{1,3}$/.test(publicIp) || publicIp.split('.').some(part => Number(part) > 255))) {
    throw new Error('Invalid public IPv4 address.');
  }
  if (!/^[a-zA-Z0-9_-]{32,}$/.test(options.adminToken || '')) throw new Error('Admin token must contain at least32ASCII letters, digits, underscores or hyphens.');
  return { installDir: resolve(installDir), hostname, publicIp, sourceSha256: options.sourceSha256.toLowerCase() };
}

export async function createCloudInit(options) {
  const sourceUrl = validateSourceUrl(options.sourceUrl);
  const settings = validateOptions(options);
  const script = await readFile(new URL('./cloud-init.bootstrap.sh', import.meta.url), 'utf8');
  const encodedFile = (path, content, permissions = '0600') => ({ path, owner: 'root:root', permissions, encoding: 'b64', content: Buffer.from(content).toString('base64') });
  const config = {
    package_update: true,
    packages: ['ca-certificates', 'curl', 'python3'],
    write_files: [
      encodedFile('/var/lib/hoyoung-bootstrap/source-url', sourceUrl + '\n'),
      encodedFile('/var/lib/hoyoung-bootstrap/settings.json', JSON.stringify(settings) + '\n'),
      encodedFile(settings.installDir + '/.env', `APP_AUTH_TOKEN=${options.adminToken}\n`),
      encodedFile('/usr/local/sbin/hoyoung-bootstrap', script, '0700'),
    ],
    runcmd: [['bash', '/usr/local/sbin/hoyoung-bootstrap']],
    final_message: 'Cloud-init stage ended. Verify the application HTTPS health endpoint; the private bootstrap log records success or failure.',
  };
  // JSON is a YAML subset and avoids interpolation of owner URLs or tokens.
  const output = '#cloud-config\n' + JSON.stringify(config, null, 2) + '\n';
  if (Buffer.byteLength(output) > MAX_USER_DATA_BYTES) throw new Error('Cloud-init exceeds the16,000-byte raw limit reserved for OCI metadata headroom.');
  return output;
}

async function canonicalPath(path) {
  let current = resolve(path);
  const suffix = [];
  for (;;) {
    try { return resolve(await realpath(current), ...suffix); }
    catch (error) {
      if (error.code !== 'ENOENT') throw error;
      const parent = dirname(current);
      if (parent === current) throw error;
      suffix.unshift(relative(parent, current)); current = parent;
    }
  }
}
async function outsideProject(path) {
  const canonical = await canonicalPath(path);
  const location = relative(await realpath(projectRoot), canonical);
  if (!location || !(location === '..' || location.startsWith('..' + sep) || isAbsolute(location))) {
    throw new Error('Private input and generated user-data files must be outside the source repository.');
  }
  // Also reject an unrelated checkout or parent monorepo. A .git directory or
  // worktree marker is sufficient; no Git configuration/credentials are read.
  for (let directory = dirname(canonical); ; directory = dirname(directory)) {
    try {
      await lstat(resolve(directory, '.git'));
      throw new Error('Private input and generated user-data files must be outside Git checkouts.');
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (dirname(directory) === directory) break;
  }
  return canonical;
}
async function privateInput(path) {
  const checked = await outsideProject(path);
  const info = await stat(checked);
  if ((info.mode & 0o077) || !info.isFile()) throw new Error('Private input files must be regular files with no group/other permissions.');
  return (await readFile(checked, 'utf8')).trim();
}

export async function writeCloudInit(options) {
  const outputPath = await outsideProject(options.out);
  const sourceUrl = await privateInput(options.sourceUrlFile);
  const adminToken = options.adminTokenFile ? await privateInput(options.adminTokenFile) : randomBytes(32).toString('hex');
  const output = await createCloudInit({ ...options, sourceUrl, adminToken });
  await mkdir(dirname(outputPath), { recursive: true, mode: 0o700 });
  const tokenPath = options.adminTokenFile ? null : await outsideProject(outputPath + '.admin-token');
  // Refuse overwrites instead of following an existing user-data file or key.
  if (tokenPath) await writeFile(tokenPath, adminToken + '\n', { mode: 0o600, flag: 'wx' });
  await writeFile(outputPath, output, { mode: 0o600, flag: 'wx' });
  return { outputPath, tokenPath, bytes: Buffer.byteLength(output) };
}

function parseArgs(argv) {
  const names = { '--source-url-file': 'sourceUrlFile', '--source-sha256': 'sourceSha256', '--out': 'out', '--admin-token-file': 'adminTokenFile', '--hostname': 'hostname', '--public-ip': 'publicIp', '--install-dir': 'installDir' };
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = names[argv[index]];
    if (!key || !argv[index + 1]) throw new Error('Unknown option or missing value; use --help.');
    options[key] = argv[index + 1];
  }
  if (!options.sourceUrlFile || !options.out) throw new Error('--source-url-file and --out are required.');
  return options;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (process.argv.includes('--help')) {
    process.stdout.write('Usage: node deploy/oracle/cloud-init.mjs --source-url-file <private0600file> --source-sha256 <64hex> --out <privatefileoutsidegit> [--admin-token-file <private0600file>] [--hostname <domain>] [--public-ip <IPv4>] [--install-dir /opt/hoyoung]\n');
  } else {
    try {
      const result = await writeCloudInit(parseArgs(process.argv.slice(2)));
      process.stdout.write(`Private cloud-init written: ${result.outputPath} (${result.bytes} bytes).\n`);
      if (result.tokenPath) process.stdout.write(`Private initial admin token file: ${result.tokenPath}. Values were not printed.\n`);
    } catch (error) { process.stderr.write(`Cloud-init generation failed: ${error.message}\n`); process.exitCode = 1; }
  }
}
