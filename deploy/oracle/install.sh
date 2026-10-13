#!/usr/bin/env bash
set +x
set -Eeuo pipefail
umask 077

die() { printf '%s\n' "$*" >&2; exit 1; }
usage() {
  cat <<'USAGE'
Usage: sudo deploy/oracle/install.sh [options]
  --source PATH       Existing source checkout (default: this script's project)
  --install-dir PATH  Persistent deployment directory (default: /opt/hoyoung)
  --hostname NAME    Existing domain; default: hoyoung.<PUBLIC_IP>.sslip.io
  --public-ip IPv4   Public IP for the automatic hostname
  --email ADDRESS    Optional ACME certificate contact email
  --prompt-token     Enter a new access token in a hidden terminal prompt
  --check            Validate host/source only; make no changes
  --help             Show help

Target: Ubuntu24.04 ARM64 Oracle Always Free A1, up to2OCPU/12GB.
This installer does not create a VM, upgrade billing, or modify firewall rules.
USAGE
}
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_SOURCE_DIR="$(realpath "$SCRIPT_DIR/../..")"
INSTALL_DIR=/opt/hoyoung
ASSISTANT_HOSTNAME=
PUBLIC_IP=
ACME_EMAIL=
PROMPT_TOKEN=false
CHECK_ONLY=false
while (($#)); do
  case "$1" in
    --source|--install-dir|--hostname|--public-ip|--email)
      (($# >= 2)) || die "Missing value for $1"
      case "$1" in
        --source) PROJECT_SOURCE_DIR="$2" ;;
        --install-dir) INSTALL_DIR="$2" ;;
        --hostname) ASSISTANT_HOSTNAME="$2" ;;
        --public-ip) PUBLIC_IP="$2" ;;
        --email) ACME_EMAIL="$2" ;;
      esac
      shift 2 ;;
    --prompt-token) PROMPT_TOKEN=true; shift ;;
    --check) CHECK_ONLY=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ "$INSTALL_DIR" =~ ^/[a-zA-Z0-9_./-]+$ && "$INSTALL_DIR" != / ]] || die 'Use an absolute installation path without spaces.'
INSTALL_DIR="$(realpath -m "$INSTALL_DIR")"
PROJECT_SOURCE_DIR="$(realpath "$PROJECT_SOURCE_DIR")"
[[ "$INSTALL_DIR" != "$PROJECT_SOURCE_DIR" && "$INSTALL_DIR" != "$PROJECT_SOURCE_DIR/"* ]] || die 'Installation directory must not be inside the source checkout.'
required_source_files=(
  Dockerfile .dockerignore package.json package-lock.json
  src/server.mjs src/runtime.mjs src/store.mjs src/tools.mjs
  src/extensions.mjs src/model.mjs src/model-login.mjs src/channels.mjs src/defaults.mjs
  public/index.html public/app.js public/styles.css
  deploy/run.mjs deploy/codex-storage.mjs deploy/r2-backup.mjs deploy/snapshot-format.mjs
  deploy/oracle/install.sh deploy/oracle/compose.yaml deploy/oracle/Caddyfile.template
  docs/source-transcript.txt docs/latest-requirements.json
)
for file in "${required_source_files[@]}"; do
  [[ -f "$PROJECT_SOURCE_DIR/$file" ]] || die "Source checkout is missing $file"
done
[[ "$(id -u)" == 0 ]] || die 'Run this installer with sudo on the target VM.'
case "$(uname -m)" in aarch64|arm64) ;; *) die 'This installer targets Oracle A1 ARM64. Current host is not ARM64.' ;; esac
# /etc/os-release is public operating-system metadata, not a credential file.
. /etc/os-release
[[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == 24.04 ]] || die 'Use the official Ubuntu24.04 ARM64 image.'
(( $(nproc --all) <= 2 )) || die 'Host exceeds the2OCPU Always Free target. Resize in the owner console first.'
host_memory_kib="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
(( host_memory_kib <= 12 * 1024 * 1024 )) || die 'Host exceeds the12GB Always Free target.'
[[ -z "$ACME_EMAIL" || "$ACME_EMAIL" =~ ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]] || die 'Invalid ACME email format.'
if $CHECK_ONLY; then
  printf '%s\n' 'Host/source checks passed. Account aggregate quotas and Always Free labels still need owner-console verification.'
  exit 0
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl openssl python3 rsync

if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  # Refuse to silently remove an existing alternative runtime or distribution
  # Docker package. A fresh official Ubuntu image needs no removal step.
  for package in docker.io docker-compose docker-compose-v2 podman-docker containerd runc; do
    if [[ "$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null || true)" == 'install ok installed' ]]; then
      die "Existing package $package conflicts with Docker's official packages. Review it manually before installing."
    fi
  done
  install -d -m 0755 /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod 0644 /etc/apt/keyrings/docker.asc
  cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-${VERSION_CODENAME:-noble}}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  apt-get update
  apt-get install -y --no-install-recommends docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker
docker info >/dev/null

if [[ -z "$ASSISTANT_HOSTNAME" ]]; then
  if [[ -z "$PUBLIC_IP" ]]; then
    PUBLIC_IP="$(curl -4fsS --connect-timeout 10 --max-time 20 https://checkip.amazonaws.com | tr -d '\r\n')"
  fi
  [[ "$PUBLIC_IP" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || die 'Supply --hostname or a valid --public-ip IPv4 address.'
  IFS=. read -r -a public_octets <<< "$PUBLIC_IP"
  for octet in "${public_octets[@]}"; do (( 10#$octet <= 255 )) || die 'Invalid IPv4 octet.'; done
  ASSISTANT_HOSTNAME="hoyoung.$PUBLIC_IP.sslip.io"
fi
[[ "$ASSISTANT_HOSTNAME" =~ ^([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$ ]] || die 'Use a DNS hostname without scheme, port, slash, or wildcard.'
((${#ASSISTANT_HOSTNAME} <= 253)) || die 'Hostname is too long.'

install -d -m 0700 "$INSTALL_DIR" "$INSTALL_DIR/data" "$INSTALL_DIR/caddy-data" "$INSTALL_DIR/caddy-config"
printf '{"state":"installing"}\n' > "$INSTALL_DIR/.install-status.json"
chmod 0600 "$INSTALL_DIR/.install-status.json"
if [[ "$PROJECT_SOURCE_DIR" != "$INSTALL_DIR/source" ]]; then
  install -d -m 0700 "$INSTALL_DIR/source"
  rsync -a --exclude=.git --exclude=node_modules --exclude=.data --exclude=data --exclude=.env --exclude='.env.*' \
    --exclude='.dev.vars*' --exclude='.wrangler' "$PROJECT_SOURCE_DIR/" "$INSTALL_DIR/source/"
fi
install -m 0600 "$PROJECT_SOURCE_DIR/deploy/oracle/compose.yaml" "$INSTALL_DIR/compose.yaml"
if [[ -n "$ACME_EMAIL" ]]; then
  printf '{\n    email %s\n}\n\n' "$ACME_EMAIL" > "$INSTALL_DIR/Caddyfile"
else
  : > "$INSTALL_DIR/Caddyfile"
fi
cat "$PROJECT_SOURCE_DIR/deploy/oracle/Caddyfile.template" >> "$INSTALL_DIR/Caddyfile"
chmod 0600 "$INSTALL_DIR/Caddyfile"

if [[ ! -f "$INSTALL_DIR/.env" ]] || $PROMPT_TOKEN; then
  if $PROMPT_TOKEN; then
    IFS= read -r -s -p 'New access token (at least32characters, hidden): ' app_token </dev/tty
    printf '\n' >/dev/tty
    [[ "$app_token" =~ ^[a-zA-Z0-9_-]{32,}$ ]] || die 'Use at least32ASCII letters, digits, underscores or hyphens.'
  else
    app_token="$(openssl rand -hex 32)"
  fi
  token_temp="$(mktemp "$INSTALL_DIR/.env.token.XXXXXX")"
  if [[ -f "$INSTALL_DIR/.env" ]]; then sed '/^APP_AUTH_TOKEN=/d' "$INSTALL_DIR/.env" > "$token_temp"; fi
  printf 'APP_AUTH_TOKEN=%s\n' "$app_token" >> "$token_temp"
  mv "$token_temp" "$INSTALL_DIR/.env"
  unset app_token
fi
chmod 0600 "$INSTALL_DIR/.env"
env_temp="$(mktemp "$INSTALL_DIR/.env.update.XXXXXX")"
# Retain owner-added settings and token; replace only this installer's hostname.
sed '/^ASSISTANT_HOSTNAME=/d' "$INSTALL_DIR/.env" > "$env_temp"
printf 'ASSISTANT_HOSTNAME=%s\n' "$ASSISTANT_HOSTNAME" >> "$env_temp"
mv "$env_temp" "$INSTALL_DIR/.env"
chmod 0600 "$INSTALL_DIR/.env"

docker compose --project-directory "$INSTALL_DIR" -f "$INSTALL_DIR/compose.yaml" config --quiet
docker compose --project-directory "$INSTALL_DIR" -f "$INSTALL_DIR/compose.yaml" build --pull assistant
docker compose --project-directory "$INSTALL_DIR" -f "$INSTALL_DIR/compose.yaml" up -d

cat > /etc/systemd/system/hoyoung-assistant.service <<EOF
[Unit]
Description=HoYoung autonomous assistant on the owner's Always Free VM
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$INSTALL_DIR
ExecStart=/usr/bin/docker compose --project-directory $INSTALL_DIR -f $INSTALL_DIR/compose.yaml up -d
ExecStop=/usr/bin/docker compose --project-directory $INSTALL_DIR -f $INSTALL_DIR/compose.yaml stop
TimeoutStartSec=0
TimeoutStopSec=120

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now hoyoung-assistant.service
printf '%s\n' 'Waiting up to 120 seconds for local application health.'
if ! python3 - "$INSTALL_DIR" <<'PY_HEALTH'
import datetime, json, os, pathlib, signal, sys, time, urllib.error, urllib.request

status_file = pathlib.Path(sys.argv[1]) / '.install-status.json'
def record(state):
    temporary = status_file.with_name(status_file.name + '.tmp')
    temporary.write_text(json.dumps({
        'state': state,
        'service': 'autonomous-assistant',
        'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'check': 'local-http',
    }) + '\n')
    temporary.chmod(0o600)
    os.replace(temporary, status_file)

def expired(signum, frame):
    raise TimeoutError('Application readiness deadline reached')

record('waiting')
signal.signal(signal.SIGALRM, expired)
signal.setitimer(signal.ITIMER_REAL, 120)
deadline = time.monotonic() + 120
# Inherited HTTPS proxies must never receive local readiness requests.
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
ready = False
try:
    while time.monotonic() < deadline:
        try:
            with opener.open('http://127.0.0.1:8080/api/health', timeout=min(3, max(0.1, deadline - time.monotonic()))) as response:
                health = json.loads(response.read(16384))
                if response.status == 200 and isinstance(health, dict) and health.get('ok') is True and health.get('service') == 'autonomous-assistant':
                    ready = True
                    break
        except (OSError, urllib.error.URLError, ValueError):
            pass
        time.sleep(min(2, max(0, deadline - time.monotonic())))
except TimeoutError:
    pass
finally:
    signal.setitimer(signal.ITIMER_REAL, 0)
record('ready' if ready else 'failed')
if not ready:
    raise SystemExit('Application health did not become ready; installation completion was not recorded.')
PY_HEALTH
then
  die "Installation failed its application health check. Inspect $INSTALL_DIR/.install-status.json and the container state privately."
fi
printf 'Installation completed. Web address: https://%s\n' "$ASSISTANT_HOSTNAME"
printf '%s\n' 'Local application health passed. Public HTTPS, model login and DM connectivity require their own checks.'
printf '%s\n' 'Access token is stored privately in the deployment .env file; it was not printed.'
printf '%s\n' 'Allow inbound80/443 in the owner VCN/NSG and host firewall while keeping SSH access. TLS needs working DNS and reachable ports.'
printf '%s\n' 'Next: complete fresh official Codex device authentication in the control panel or inside the assistant container.'
