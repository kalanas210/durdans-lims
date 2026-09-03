#!/usr/bin/env bash
# =====================================================================
# Bring Prometheus + Grafana up on the demo host, behind Caddy at
# https://grafana.<domain>. Idempotent: safe from bootstrap on a fresh host and
# safe to re-run over SSM after config changes.
#
# Lives in S3 as bootstrap/observability.sh (the short key keeps the piped
# fetch+run line in the byte-capped bootstrap.sh under the user_data budget).
# The service definitions live in docker-compose.override.yml (also S3-synced);
# this script owns the pieces that are host state rather than compose config:
# the config files under /opt/lims/observability, the Grafana admin password in
# .env, and the Caddy vhost.
#
# To publish config changes from the repo:
#   aws s3 cp infra/scripts/setup-observability.sh    s3://<bucket>/bootstrap/observability.sh
#   aws s3 cp infra/observability/prometheus-prod.yml s3://<bucket>/bootstrap/observability/prometheus.yml
#   aws s3 cp infra/observability/grafana/provisioning-prod/datasources/datasources.yml \
#       s3://<bucket>/bootstrap/observability/grafana/provisioning/datasources/datasources.yml
#   aws s3 cp infra/observability/grafana/provisioning/dashboards/ \
#       s3://<bucket>/bootstrap/observability/grafana/provisioning/dashboards/ --recursive
# then re-run it on the host over SSM:
#   aws s3 cp s3://<bucket>/bootstrap/observability.sh - | bash
# =====================================================================
set -euo pipefail

cd /opt/lims

# Bootstrap exports these; an SSM re-run reads them back out of .env instead.
AWS_REGION="${AWS_REGION:-$(sed -n 's/^AWS_REGION=//p' .env)}"
S3_BUCKET="${S3_BUCKET:-$(sed -n 's/^S3_BUCKET=//p' .env)}"
DOMAIN_NAME="${DOMAIN_NAME:-$(sed -n 's/^DOMAIN_NAME=//p' .env)}"

# --- Config files (Prometheus scrape config + Grafana provisioning) ---
mkdir -p /opt/lims/observability
aws s3 cp "s3://${S3_BUCKET}/bootstrap/observability/" /opt/lims/observability/ \
  --recursive --region "${AWS_REGION}" --only-show-errors

# --- Grafana admin password: generate once, keep across re-runs ---
if ! grep -q '^GRAFANA_ADMIN_PASSWORD=' .env; then
  echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 12)" >> .env
fi

# --- Caddy vhost (TLS terminates at Caddy; Grafana is never published as a port) ---
if [[ -n "${DOMAIN_NAME}" ]] && ! grep -q "grafana.${DOMAIN_NAME}" Caddyfile; then
  cat >> Caddyfile <<CADDY

grafana.${DOMAIN_NAME} {
  reverse_proxy grafana:3000
}
CADDY
  docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile \
    || docker compose restart caddy
fi

# --- Up (service definitions come from docker-compose.override.yml) ---
docker compose up -d prometheus grafana

echo "Observability up: https://grafana.${DOMAIN_NAME} (admin / GRAFANA_ADMIN_PASSWORD in /opt/lims/.env)"
