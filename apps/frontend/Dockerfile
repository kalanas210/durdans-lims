# syntax=docker/dockerfile:1

# =====================================================================
# Multi-stage production image for the Next.js 16 frontend.
# Uses `output: 'standalone'` (next.config.ts) so the runtime image carries only
# the traced server bundle + minimal node_modules. Final image runs as non-root.
#
# NEXT_PUBLIC_* are inlined at BUILD time and reach the browser, so they must be
# the HOST-facing URLs the browser can reach (published compose ports), NOT the
# in-network service DNS names. Defaults below match local docker-compose.
# =====================================================================

# --- Dependencies ---
FROM node:26-alpine AS deps
WORKDIR /app
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

# --- Build ---
FROM node:26-alpine AS build
WORKDIR /app
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable
COPY --from=deps /app/node_modules ./node_modules
COPY . .

ARG NEXT_PUBLIC_API_URL=http://localhost:11000
ARG NEXT_PUBLIC_KEYCLOAK_URL=http://localhost:8081
ARG NEXT_PUBLIC_KEYCLOAK_REALM=lims-realm
ARG NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=lims-frontend
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL \
    NEXT_PUBLIC_KEYCLOAK_URL=$NEXT_PUBLIC_KEYCLOAK_URL \
    NEXT_PUBLIC_KEYCLOAK_REALM=$NEXT_PUBLIC_KEYCLOAK_REALM \
    NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=$NEXT_PUBLIC_KEYCLOAK_CLIENT_ID \
    NEXT_TELEMETRY_DISABLED=1

RUN pnpm build

# --- Runtime ---
FROM node:26-alpine AS runner
WORKDIR /app

# Patch OS packages in the shipped layer. The Trivy release gate runs with
# ignore-unfixed:true, so it only fails on HIGH/CRITICAL vulns that HAVE a fix
# upstream (e.g. an openssl/libcrypto3 advisory Alpine has already patched).
# Only this stage ships, so upgrading here is what the scan actually sees.
RUN apk upgrade --no-cache

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
RUN addgroup -g 1001 -S nodejs && adduser -S -u 1001 -G nodejs nextjs

# The runtime executes only `node server.js`; npm, npx and the Pebble ACME test
# binary are build tooling. Remove them from the production image and its
# vulnerability surface.
RUN rm -rf /usr/local/lib/node_modules/npm \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/bin/pebble

# Standalone output: server.js + minimal node_modules, plus static assets.
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD node -e "fetch('http://localhost:3000/').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "server.js"]
