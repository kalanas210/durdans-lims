# Static EC2 bootstrap, appended to the Terraform-generated header (see compute.tf
# for what that header exports). Brings up the LIMS compose stack. Byte-capped.
set -euxo pipefail

# --- Docker + compose plugin (Amazon Linux 2023) ---
dnf update -y
dnf install -y docker python3
systemctl enable --now docker

# A small swap file absorbs short JVM/image-pull spikes. The services still have
# explicit memory ceilings below, so swap is a safety valve rather than capacity.
if [[ ! -f /swapfile ]]; then
  dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p /usr/libexec/docker/cli-plugins
curl -SL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64" \
  -o /usr/libexec/docker/cli-plugins/docker-compose
chmod +x /usr/libexec/docker/cli-plugins/docker-compose

# --- Authenticate to ECR (instance role) ---
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

# --- Pull secrets (instance role) ---
get_json() { aws secretsmanager get-secret-value --region "$AWS_REGION" --secret-id "$1" --query SecretString --output text; }
jq_field() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

DB_JSON="$(get_json "$DB_SECRET")"
DB_URL="$(echo "$DB_JSON" | jq_field url)"
DB_USERNAME="$(echo "$DB_JSON" | jq_field username)"
DB_PASSWORD="$(echo "$DB_JSON" | jq_field password)"

WA_DB_JSON="$(get_json "$WA_DB_SECRET")"
WA_DB_URL="$(echo "$WA_DB_JSON" | jq_field url)"
WA_DB_USERNAME="$(echo "$WA_DB_JSON" | jq_field username)"
WA_DB_PASSWORD="$(echo "$WA_DB_JSON" | jq_field password)"

META_JSON="$(get_json "$META_SECRET")"
META_APP_ID="$(echo "$META_JSON" | jq_field app_id)"
META_APP_SECRET="$(echo "$META_JSON" | jq_field app_secret)"
META_VERIFY_TOKEN="$(echo "$META_JSON" | jq_field verify_token)"
META_PHONE_NUMBER_ID="$(echo "$META_JSON" | jq_field phone_number_id)"
META_WABA_ID="$(echo "$META_JSON" | jq_field waba_id)"
META_ACCESS_TOKEN="$(echo "$META_JSON" | jq_field access_token)"
GEMINI_API_KEY="$(echo "$META_JSON" | jq_field gemini_api_key)"

MAIL_JSON="$(get_json "$MAIL_SECRET")"
MAIL_USERNAME="$(echo "$MAIL_JSON" | jq_field username)"
MAIL_PASSWORD="$(echo "$MAIL_JSON" | jq_field password)"
SMS_PROVIDER="$(echo "$MAIL_JSON" | jq_field sms_provider)"
SMS_USER_ID="$(echo "$MAIL_JSON" | jq_field sms_user_id)"
SMS_API_KEY="$(echo "$MAIL_JSON" | jq_field sms_api_key)"
SMS_SENDER_ID="$(echo "$MAIL_JSON" | jq_field sms_sender_id)"

KC_JSON="$(get_json "$KC_SECRET")"
KC_ADMIN_PASSWORD="$(echo "$KC_JSON" | jq_field password)"

# --- Write the stack to /opt/lims ---
mkdir -p /opt/lims
cd /opt/lims

cat > .env <<ENVEOF
DB_URL=${DB_URL}
DB_USERNAME=${DB_USERNAME}
DB_PASSWORD=${DB_PASSWORD}
AWS_REGION=${AWS_REGION}
MAIL_USERNAME=${MAIL_USERNAME}
MAIL_PASSWORD=${MAIL_PASSWORD}
SMS_PROVIDER=${SMS_PROVIDER}
SMS_USER_ID=${SMS_USER_ID}
SMS_API_KEY=${SMS_API_KEY}
SMS_SENDER_ID=${SMS_SENDER_ID}
KEYCLOAK_ADMIN_PASSWORD=${KC_ADMIN_PASSWORD}
KEYCLOAK_DB_PASSWORD=${KC_ADMIN_PASSWORD}
PUBLIC_ADDR=${PUBLIC_ADDR}
DOMAIN_NAME=${DOMAIN_NAME}
FRONTEND_ORIGIN=${FRONTEND_ORIGIN}
API_ORIGIN=${API_ORIGIN}
KEYCLOAK_ORIGIN=${KEYCLOAK_ORIGIN}
S3_BUCKET=${S3_BUCKET}
BACKUP_BUCKET=${BACKUP_BUCKET}
ECR_APP=${ECR_APP}
ECR_FRONTEND=${ECR_FRONTEND}
ECR_WHATSAPP=${ECR_WHATSAPP}
ECR_VOICE=${ECR_VOICE}
APP_TAG=${APP_TAG}
FRONTEND_TAG=${FRONTEND_TAG}
WHATSAPP_TAG=${WHATSAPP_TAG}
VOICE_TAG=${VOICE_TAG}
VOICE_INTERNAL_TOKEN=$(openssl rand -hex 24)
WHATSAPP_ORIGIN=${WHATSAPP_ORIGIN}
WA_DB_URL=${WA_DB_URL}
WA_DB_USERNAME=${WA_DB_USERNAME}
WA_DB_PASSWORD=${WA_DB_PASSWORD}
META_APP_ID=${META_APP_ID}
META_APP_SECRET=${META_APP_SECRET}
META_VERIFY_TOKEN=${META_VERIFY_TOKEN}
META_PHONE_NUMBER_ID=${META_PHONE_NUMBER_ID}
META_WABA_ID=${META_WABA_ID}
META_ACCESS_TOKEN=${META_ACCESS_TOKEN}
META_SECRET=${META_SECRET}
GEMINI_API_KEY=${GEMINI_API_KEY}
AGENT_CLIENT_SECRET=
ENVEOF
chmod 600 .env

# Realm seed from S3 (user_data is byte-capped; the JSON is ~80 KB).
mkdir -p /opt/lims/keycloak-imports
aws s3 cp "s3://${S3_BUCKET}/bootstrap/lims-dev-seed.json" \
  /opt/lims/keycloak-imports/lims-dev-seed.json --region "${AWS_REGION}"

mkdir -p /opt/lims/keycloak-themes/lims-theme/login
aws s3 cp "s3://${S3_BUCKET}/bootstrap/keycloak-themes/lims-theme/login/" \
  /opt/lims/keycloak-themes/lims-theme/login/ --recursive --region "${AWS_REGION}"

# The committed demo realm only trusts localhost. Patch its public client to the
# Terraform-computed live origin before the first (and only) realm import.
export FRONTEND_ORIGIN
python3 <<'PY'
import json
import os
from pathlib import Path

realm_path = Path("/opt/lims/keycloak-imports/lims-dev-seed.json")
realm = json.loads(realm_path.read_text(encoding="utf-8"))
origin = os.environ["FRONTEND_ORIGIN"].rstrip("/")

for client in realm.get("clients", []):
    if client.get("clientId") != "lims-frontend":
        continue
    client["rootUrl"] = origin
    client["baseUrl"] = origin
    client["redirectUris"] = [f"{origin}/*"]
    client["webOrigins"] = [origin]
    client.setdefault("attributes", {})["post.logout.redirect.uris"] = f"{origin}/*"
    break
else:
    raise SystemExit("lims-frontend client is missing from the realm seed")

realm_path.write_text(json.dumps(realm, indent=2) + "\n", encoding="utf-8")
PY

aws s3 cp "s3://${S3_BUCKET}/bootstrap/deploy-service.sh" \
  /opt/lims/deploy-service.sh --region "${AWS_REGION}"
chmod 700 /opt/lims/deploy-service.sh

# Non-boot-glue scripts live in S3 (user_data byte cap); each file says why it exists.
aws s3 cp "s3://${S3_BUCKET}/bootstrap/provision-wa-db.sh" \
  /opt/lims/provision-wa-db.sh --region "${AWS_REGION}"
aws s3 cp "s3://${S3_BUCKET}/bootstrap/refresh-meta.sh" \
  /opt/lims/refresh-meta.sh --region "${AWS_REGION}"
aws s3 cp "s3://${S3_BUCKET}/bootstrap/fetch-agent-secret.sh" \
  /opt/lims/fetch-agent-secret.sh --region "${AWS_REGION}"
aws s3 cp "s3://${S3_BUCKET}/bootstrap/docker-compose.override.yml" \
  /opt/lims/docker-compose.override.yml --region "${AWS_REGION}"
chmod 700 /opt/lims/provision-wa-db.sh /opt/lims/refresh-meta.sh /opt/lims/fetch-agent-secret.sh

# Never fatal: the lab must not fail to boot because the agent's database could not
# be provisioned. The service fails its own health check instead, in isolation.
/opt/lims/provision-wa-db.sh || true

if [[ -n "${DOMAIN_NAME}" ]]; then
  cat > Caddyfile <<CADDY
${DOMAIN_NAME} {
  reverse_proxy frontend:3000
}

api.${DOMAIN_NAME} {
  reverse_proxy app:11000
}

auth.${DOMAIN_NAME} {
  reverse_proxy keycloak:8080
}

# The Meta webhook. Terminating TLS here is not optional dressing: Meta will only
# call a publicly trusted HTTPS endpoint on 443, and it refuses to register a
# callback it cannot validate. The service itself is never published to the host.
wa.${DOMAIN_NAME} {
  reverse_proxy whatsapp:11010
}
CADDY
else
  cat > Caddyfile <<'CADDY'
:80 {
  reverse_proxy frontend:3000
}
CADDY
fi

cat > docker-compose.yml <<'COMPOSE'
name: durdans-lims-prod
networks: { lims-net: { driver: bridge } }
volumes: { kc_db: {}, caddy_data: {}, caddy_config: {} }

services:
  kc-db:
    image: postgres:15
    mem_limit: 384m
    environment:
      POSTGRES_DB: keycloak
      POSTGRES_USER: keycloak
      POSTGRES_PASSWORD: ${KEYCLOAK_DB_PASSWORD}
    volumes: [ "kc_db:/var/lib/postgresql/data" ]
    networks: [lims-net]
    healthcheck: { test: ["CMD-SHELL","pg_isready -U keycloak"], interval: 10s, timeout: 5s, retries: 5 }
    restart: always

  keycloak:
    image: quay.io/keycloak/keycloak:26.7.1
    mem_limit: 768m
    command: start-dev --import-realm
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://kc-db:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: ${KEYCLOAK_DB_PASSWORD}
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD}
      KC_HEALTH_ENABLED: "true"
      KC_HOSTNAME_STRICT: "false"
      KC_HOSTNAME: ${KEYCLOAK_ORIGIN}
      KC_PROXY_HEADERS: xforwarded
      JAVA_OPTS_KC_HEAP: -Xms128m -Xmx512m
    ports: [ "8081:8080" ]
    # --import-realm above is a no-op without this mount. Mirrors the same mount
    # in infra/docker-compose.yml.
    volumes:
      - "/opt/lims/keycloak-imports:/opt/keycloak/data/import:ro"
      - "/opt/lims/keycloak-themes:/opt/keycloak/themes"
    networks: [lims-net]
    depends_on: { kc-db: { condition: service_healthy } }
    restart: always

  # Must stay in step with infra/docker-compose.yml. bitnami/kafka was removed
  # from Docker Hub in 2025, so the old pin made this whole script exit non-zero
  # under `set -euxo pipefail`. The Apache image also drops the bitnami-only
  # KAFKA_CFG_ prefix and ALLOW_PLAINTEXT_LISTENER, and ships kafka-topics.sh
  # under /opt/kafka/bin rather than on PATH.
  kafka:
    image: apache/kafka:3.8.1
    mem_limit: 768m
    environment:
      KAFKA_HEAP_OPTS: -Xms128m -Xmx512m
      KAFKA_NODE_ID: "1"
      KAFKA_PROCESS_ROLES: "broker,controller"
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
      KAFKA_LISTENERS: "PLAINTEXT://:9092,CONTROLLER://:9093"
      KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://kafka:9092"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
      KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      KAFKA_INTER_BROKER_LISTENER_NAME: "PLAINTEXT"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1"
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: "1"
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: "1"
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: "0"
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    networks: [lims-net]
    healthcheck: { test: ["CMD-SHELL","/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 || exit 1"], interval: 15s, timeout: 10s, retries: 10, start_period: 30s }
    restart: always

  app:
    image: ${ECR_APP}:${APP_TAG}
    mem_limit: 1280m
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DB_URL: ${DB_URL}
      DB_USERNAME: ${DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD}
      KEYCLOAK_REALM: lims-realm
      KEYCLOAK_INTERNAL_URL: http://keycloak:8080
      KEYCLOAK_PUBLIC_URL: ${KEYCLOAK_ORIGIN}
      APP_SECURITY_CORS_ALLOWED_ORIGIN_PATTERNS: ${FRONTEND_ORIGIN}
      # Real S3 via the instance role: blank endpoint + blank static keys.
      AWS_S3_ENDPOINT: ""
      AWS_ACCESS_KEY: ""
      AWS_SECRET_KEY: ""
      AWS_S3_BUCKET: ${S3_BUCKET}
      AWS_REGION: ${AWS_REGION}
      MAIL_USERNAME: ${MAIL_USERNAME}
      MAIL_PASSWORD: ${MAIL_PASSWORD}
      SMS_PROVIDER: ${SMS_PROVIDER}
      SMS_USER_ID: ${SMS_USER_ID}
      SMS_API_KEY: ${SMS_API_KEY}
      SMS_SENDER_ID: ${SMS_SENDER_ID}
      APP_VERIFICATION_BASE_URL: ${API_ORIGIN}
      APP_SERVICES_PATIENT_BASE_URL: ${API_ORIGIN}
      APP_REPORTS_PORTAL_URL: ${FRONTEND_ORIGIN}/patient-portal/orders
    ports: [ "11000:11000" ]
    networks: [lims-net]
    depends_on: { kafka: { condition: service_healthy }, keycloak: { condition: service_started } }
    restart: always

  whatsapp:
    image: ${ECR_WHATSAPP}:${WHATSAPP_TAG}
    mem_limit: 640m
    environment:
      WA_DB_URL: ${WA_DB_URL}
      WA_DB_USERNAME: ${WA_DB_USERNAME}
      WA_DB_PASSWORD: ${WA_DB_PASSWORD}
      META_APP_ID: ${META_APP_ID}
      META_APP_SECRET: ${META_APP_SECRET}
      META_VERIFY_TOKEN: ${META_VERIFY_TOKEN}
      META_PHONE_NUMBER_ID: ${META_PHONE_NUMBER_ID}
      META_WABA_ID: ${META_WABA_ID}
      META_ACCESS_TOKEN: ${META_ACCESS_TOKEN}
      GEMINI_API_KEY: ${GEMINI_API_KEY}
      AGENT_CLIENT_SECRET: ${AGENT_CLIENT_SECRET}
      # There is no OTLP collector on this host. Turning export off is deliberate:
      # aiming the exporter at an absent collector produces a warning every few
      # seconds forever. Set TRACING_ENABLED=true once Tempo is deployed here.
      TRACING_ENABLED: "false"
    # No ports: Caddy reaches it over the compose network. The webhook is the only
    # thing this service exposes and it should reach the internet through TLS, not
    # through a published container port.
    networks: [lims-net]
    restart: always

  frontend:
    image: ${ECR_FRONTEND}:${FRONTEND_TAG}
    mem_limit: 384m
    environment:
      TZ: Asia/Colombo   # SSR date formatting; no database behind it
    ports: [ "3000:3000" ]
    networks: [lims-net]
    depends_on: { app: { condition: service_started } }
    restart: always

  caddy:
    image: caddy:2.10.2-alpine
    mem_limit: 192m
    ports: [ "80:80", "443:443" ]
    volumes:
      - "/opt/lims/Caddyfile:/etc/caddy/Caddyfile:ro"
      - "caddy_data:/data"
      - "caddy_config:/config"
    networks: [lims-net]
    restart: always
COMPOSE

# The ECR repositories are empty on the first Terraform apply. Start the base
# services immediately, then deploy app images only when they already exist.
docker compose up -d kc-db keycloak kafka caddy
/opt/lims/deploy-service.sh app "${APP_TAG}" || true
/opt/lims/deploy-service.sh frontend "${FRONTEND_TAG}" || true
# Same `|| true` as the others, and it matters more here: the lab must not fail to
# boot because the chatbot's image has not been published yet. Caddy still comes up
# and answers the ACME challenge for wa.<domain>, so the certificate is issued and
# the hostname simply 502s until the first deploy lands.
/opt/lims/deploy-service.sh whatsapp "${WHATSAPP_TAG}" || true
/opt/lims/deploy-service.sh voice "${VOICE_TAG}" || true
/opt/lims/fetch-agent-secret.sh || true
aws s3 cp "s3://${S3_BUCKET}/bootstrap/observability.sh" - --region "$AWS_REGION"|bash||true


# Keycloak uses its own Postgres volume on this cost-optimized host. Back it up
# off-host every night so an EC2/EBS loss does not also remove the identity DB.
cat >/usr/local/bin/backup-keycloak.sh <<BACKUP
#!/usr/bin/env bash
set -euo pipefail
stamp=\$(date -u +%Y%m%dT%H%M%SZ)
cd /opt/lims
docker compose exec -T kc-db pg_dump -Fc -U keycloak keycloak \
  | aws s3 cp - "s3://${BACKUP_BUCKET}/keycloak/keycloak_\${stamp}.dump" \
      --region "${AWS_REGION}" --only-show-errors
BACKUP
chmod 0750 /usr/local/bin/backup-keycloak.sh

cat >/etc/systemd/system/lims-keycloak-backup.service <<'SERVICE'
[Unit]
Description=Back up the LIMS Keycloak database to S3
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/backup-keycloak.sh
SERVICE

cat >/etc/systemd/system/lims-keycloak-backup.timer <<'TIMER'
[Unit]
Description=Nightly LIMS Keycloak database backup

[Timer]
OnCalendar=*-*-* 02:15:00 UTC
Persistent=true
RandomizedDelaySec=15m

[Install]
WantedBy=timers.target
TIMER

systemctl daemon-reload
systemctl enable --now lims-keycloak-backup.timer

echo "LIMS host bootstrapped. Frontend: ${FRONTEND_ORIGIN}  API: ${API_ORIGIN}  Keycloak: ${KEYCLOAK_ORIGIN}"
