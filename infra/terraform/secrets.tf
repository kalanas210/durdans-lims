# Generated, never hard-coded. RDS rejects '/', '@', '"', space — exclude them.
resource "random_password" "db" {
  length           = 24
  special          = true
  override_special = "!#$%^&*()-_=+[]{}"
}

resource "random_password" "keycloak_admin" {
  length           = 20
  special          = true
  override_special = "!#$%^&*()-_=+"
}

# --- DB credentials (consumed by the app via the instance role) ---
resource "aws_secretsmanager_secret" "db" {
  name                    = "${local.name}/db"
  description             = "LIMS application Postgres credentials"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    username = var.db_username
    password = random_password.db.result
    host     = aws_db_instance.lims.address
    port     = aws_db_instance.lims.port
    dbname   = var.db_name
    url      = "jdbc:postgresql://${aws_db_instance.lims.endpoint}/${var.db_name}"
  })
}

# --- Outbound notifications: SMTP + SMS (fill in after apply; each channel stays
#     disabled while its fields are blank) ---
#
# The SMS fields live here rather than in a secret of their own because
# bootstrap.sh is capped at 15000 bytes by EC2's user_data limit and a second
# fetch does not fit. They are the same kind of thing — the credentials the lab
# sends patient messages with — and they are read in the same place.
#
# sms_provider is "ozonedesk" to send real messages and "mock" to swallow them.
# Left blank it resolves to mock, which is why patient OTPs silently went
# nowhere on the live host: nothing distinguished "not configured" from
# "configured to do nothing" (MockSmsService now says so at startup).
resource "aws_secretsmanager_secret" "mail" {
  name                    = "${local.name}/mail"
  description             = "Outbound notification credentials: SMTP + SMS gateway"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "mail" {
  secret_id = aws_secretsmanager_secret.mail.id
  secret_string = jsonencode({
    username      = ""
    password      = ""
    sms_provider  = ""
    sms_user_id   = ""
    sms_api_key   = ""
    sms_sender_id = ""
  })

  # Same reason the meta secret carries this, and the omission here was the bug
  # that comment predicted: an operator types the SMTP password and the SMS key
  # into the console, and the next apply resets both to "" — mail and OTP stop
  # working at the next instance replacement, with nothing pointing at why.
  lifecycle {
    ignore_changes = [secret_string]
  }
}

# --- Keycloak admin ---
resource "aws_secretsmanager_secret" "keycloak_admin" {
  name                    = "${local.name}/keycloak-admin"
  description             = "Keycloak bootstrap admin password"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "keycloak_admin" {
  secret_id     = aws_secretsmanager_secret.keycloak_admin.id
  secret_string = jsonencode({ username = "admin", password = random_password.keycloak_admin.result })
}

# --- WhatsApp agent database role -------------------------------------------
# The agent gets its OWN database and its OWN Postgres role on the same RDS
# instance. That is the mechanism behind the claim in docs/whatsapp-agent-design.md
# that a defect in the agent cannot reach a specimen record: sharing the LIMS
# credentials would make the boundary a convention, whereas a separate role that
# was never granted anything on durdans_lims_db makes it something Postgres
# enforces. bootstrap.sh creates the role and database idempotently on first boot.
#
# Same character restrictions as the app password: RDS rejects '/', '@', '"' and
# space, and the role is created with a quoted SQL literal so ''' must not appear.
resource "random_password" "wa_db" {
  length           = 24
  special          = true
  override_special = "!#$%^&*()-_=+[]{}"
}

resource "aws_secretsmanager_secret" "wa_db" {
  name                    = "${local.name}/whatsapp-db"
  description             = "Postgres role and database for lims-whatsapp-service"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "wa_db" {
  secret_id = aws_secretsmanager_secret.wa_db.id
  secret_string = jsonencode({
    username = "lims_wa"
    password = random_password.wa_db.result
    dbname   = "durdans_wa_db"
    url      = "jdbc:postgresql://${aws_db_instance.lims.endpoint}/durdans_wa_db"
  })
}

# --- Meta / WhatsApp Cloud API credentials ----------------------------------
# Deliberately created EMPTY and filled in by hand after apply, exactly like the
# mail secret. Two reasons: these are Meta's credentials rather than ones we
# generate, and an empty app secret makes the service reject every webhook rather
# than accept unverified ones — so a half-configured deployment fails closed.
#
# Fill it in with:
#   aws secretsmanager put-secret-value --secret-id durdans-lims/meta \
#     --secret-string file://meta.json
resource "aws_secretsmanager_secret" "meta" {
  name                    = "${local.name}/meta"
  description             = "WhatsApp Cloud API app credentials and webhook verify token"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "meta" {
  secret_id = aws_secretsmanager_secret.meta.id
  secret_string = jsonencode({
    app_id          = ""
    app_secret      = ""
    verify_token    = ""
    phone_number_id = ""
    waba_id         = ""
    access_token    = ""
  })

  # Terraform must never overwrite what an operator typed into the console. The
  # mail secret has the same problem; this one is worse, because clobbering the
  # verify token silently breaks the registered webhook and Meta stops delivering.
  lifecycle {
    ignore_changes = [secret_string]
  }
}
