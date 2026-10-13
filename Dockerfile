# syntax=docker/dockerfile:1
FROM node:24-bookworm-slim

ENV NODE_ENV=production \
    DATA_DIR=/data \
    PORT=8080 \
    HOST=0.0.0.0 \
    PLAYWRIGHT_BROWSERS_PATH=/opt/playwright \
    NPM_CONFIG_PREFIX=/data/npm \
    VIRTUAL_ENV=/data/python-venv \
    PATH=/data/python-venv/bin:/data/npm/bin:/opt/agent-tools/bin:$PATH
WORKDIR /app

# Optional BuildKit CA mounts support the managed development environment's
# HTTPS proxy while keeping its CA out of the deployed image.
RUN --mount=type=secret,id=system_ca \
    if [ -f /run/secrets/system_ca ]; then \
      printf 'Acquire::https::CaInfo "/run/secrets/system_ca";\n' > /etc/apt/apt.conf.d/99-build-proxy-ca; \
    fi; \
    apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates curl git jq python3 python3-pip python3-venv tini \
    && rm -f /etc/apt/apt.conf.d/99-build-proxy-ca \
    && rm -rf /var/lib/apt/lists/*

COPY package.json package-lock.json ./
RUN --mount=type=secret,id=proxy_ca \
    if [ -f /run/secrets/proxy_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/proxy_ca; fi; \
    npm ci --omit=dev --strict-ssl=true \
    && npm install --global --prefix /opt/agent-tools @openai/codex@0.159.2 --strict-ssl=true
RUN --mount=type=secret,id=proxy_ca \
    --mount=type=secret,id=system_ca \
    if [ -f /run/secrets/proxy_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/proxy_ca; fi; \
    if [ -f /run/secrets/system_ca ]; then \
      printf 'Acquire::https::CaInfo "/run/secrets/system_ca";\n' > /etc/apt/apt.conf.d/99-build-proxy-ca; \
    fi; \
    npx playwright install --with-deps chromium \
    && rm -f /etc/apt/apt.conf.d/99-build-proxy-ca \
    && rm -rf /var/lib/apt/lists/*

RUN --mount=type=secret,id=system_ca \
    if [ -f /run/secrets/system_ca ]; then \
      printf 'Acquire::https::CaInfo "/run/secrets/system_ca";\n' > /etc/apt/apt.conf.d/99-build-proxy-ca; \
    fi; \
    apt-get update \
    && apt-get install -y --no-install-recommends libnss3-tools \
    && rm -f /etc/apt/apt.conf.d/99-build-proxy-ca \
    && rm -rf /var/lib/apt/lists/*

# .dockerignore excludes runtime data, secrets, node_modules, and all docs except
# the redacted source transcript and current accepted requirements. One copy avoids full filesystem duplication
# between multiple COPY layers on a vfs development Docker daemon.
COPY . .
ENV NODE_USE_ENV_PROXY=1
ENV PERSIST_CODEX_AUTH=true
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8080/api/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "deploy/run.mjs"]
