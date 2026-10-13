import { Container, getContainer } from '@cloudflare/containers';
import { handleSnapshot } from './snapshot-route.mjs';

export class AssistantContainer extends Container {
  defaultPort = 8080;
  sleepAfter = '10m';
  enableInternet = true;

  constructor(ctx, env) {
    super(ctx, env);
    this.envVars = {
      NODE_ENV: 'production',
      DATA_DIR: '/data',
      HOST: '0.0.0.0',
      PORT: '8080',
      REQUIRE_BACKUP: 'true',
      ...(env.PUBLIC_BASE_URL && env.SNAPSHOTS ? {
        BACKUP_ENDPOINT: `${String(env.PUBLIC_BASE_URL).replace(/\/+$/, '')}/internal/snapshot`,
        BACKUP_HTTP_TOKEN: env.APP_AUTH_TOKEN,
      } : {}),
      ...Object.fromEntries([
        'APP_AUTH_TOKEN', 'S3_ENDPOINT', 'S3_BUCKET', 'S3_PREFIX', 'S3_REGION',
        'S3_ACCESS_KEY_ID', 'S3_SECRET_ACCESS_KEY', 'BACKUP_INTERVAL_SECONDS', 'CODEX_BIN',
      ].filter(key => env[key]).map(key => [key, String(env[key])])),
    };
  }

  async keepAlive() {
    const response = await this.containerFetch('http://localhost/api/health');
    if (!response.ok) throw new Error(`Assistant health check failed (${response.status}).`);
    this.renewActivityTimeout();
  }

  async onActivityExpired() {
    // 24-hour autonomy is intentional. Cron also wakes the instance after an
    // unexpected stop. This keeps compute billable while idle.
    this.renewActivityTimeout();
  }
}

function missingSettings(env) {
  const missing = env.APP_AUTH_TOKEN ? [] : ['APP_AUTH_TOKEN'];
  if (!env.PUBLIC_BASE_URL || !env.SNAPSHOTS) {
    const s3Missing = ['S3_ENDPOINT', 'S3_BUCKET', 'S3_ACCESS_KEY_ID', 'S3_SECRET_ACCESS_KEY'].filter(key => !env[key]);
    if (s3Missing.length) missing.push(...s3Missing, 'PUBLIC_BASE_URL (native R2 alternative)');
  }
  return missing;
}

export default {
  async fetch(request, env, context) {
    if (new URL(request.url).pathname === '/internal/snapshot') return handleSnapshot(request, env, context);
    const missing = missingSettings(env);
    if (missing.length) {
      return Response.json({ error: 'Deployment configuration incomplete.', missing }, { status: 503 });
    }
    // The application authenticates the web UI, API, and browser controls using
    // APP_AUTH_TOKEN. The Worker keeps the same single-user instance for every path.
    return getContainer(env.ASSISTANT, 'personal-assistant').fetch(request);
  },
  async scheduled(_controller, env) {
    const missing = missingSettings(env);
    if (missing.length) throw new Error(`Configure required deployment settings: ${missing.join(', ')}`);
    await getContainer(env.ASSISTANT, 'personal-assistant').keepAlive();
  },
};
