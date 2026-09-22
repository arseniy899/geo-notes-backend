# Deploying WhenHere backend on Hetzner Cloud (CX23)

Single small VM running three containers via Docker Compose:

```
Internet ──443──▶ caddy (TLS, Let's Encrypt) ──▶ app (Ktor :8080) ──▶ db (PostgreSQL 17, volume pgdata)
```

A CX23 (2 vCPU, 4 GB RAM, 40 GB disk) is plenty for the MVP: the server only relays tiny
`{shareId, transition, ts}` events and stores opaque ciphertext; there is no geo computation.

## 1. Create the server

1. Hetzner Cloud Console → *Add Server*: location close to your users (e.g. `nbg1`/`fsn1`/`hel1`),
   image **Ubuntu 24.04**, type **CX23**, add your SSH key, enable **Backups** (optional, +20%).
2. *Firewalls* → create one and attach it: allow inbound **TCP 22** (ideally only from your IP),
   **TCP 80**, **TCP 443**, **UDP 443** (HTTP/3). Deny everything else. Postgres is never exposed.
3. DNS: create an `A` (and `AAAA`) record, e.g. `api.whenhere.app → <server IPv4/IPv6>`.

## 2. Prepare the host

```bash
ssh root@<ip>
apt update && apt -y upgrade
apt -y install ca-certificates curl git rsync unattended-upgrades
dpkg-reconfigure -plow unattended-upgrades

# Docker Engine + compose plugin (official repo)
curl -fsSL https://get.docker.com | sh

# Deploy user (no root SSH afterwards)
adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
mkdir -p /home/deploy/.ssh && cp ~/.ssh/authorized_keys /home/deploy/.ssh/ && chown -R deploy: /home/deploy/.ssh
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/; s/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl reload ssh

mkdir -p /opt/whenhere /var/backups/whenhere && chown deploy: /opt/whenhere /var/backups/whenhere
```

## 3. Configure and start

```bash
su - deploy
git clone <this repo> /opt/whenhere && cd /opt/whenhere
cp .env.example .env && chmod 600 .env
#   edit .env: API_DOMAIN, ACME_EMAIL, POSTGRES_PASSWORD (openssl rand -base64 32), AUTH_MODE=firebase
mkdir -p secrets && chmod 700 secrets
#   copy the Firebase service-account JSON (Firebase console → Project settings → Service accounts)
#   to secrets/firebase-service-account.json and chmod 600 it. The app uses it to verify ID tokens and send FCM.
#   copy the Play billing service-account JSON to secrets/play-service-account.json (chmod 600) and set
#   RTDN_AUDIENCE / RTDN_PUSH_SERVICE_ACCOUNT in .env (see README → "Google Play billing setup").
#   Reusing the Firebase account for Play? Copy it to secrets/play-service-account.json as well: the file must
#   exist, otherwise Docker creates an empty directory at that path and startup fails.
docker compose up -d --build
docker compose ps           # all three healthy
curl https://$API_DOMAIN/health
```

Flyway migrations run automatically on app start.

### Updating

```bash
cd /opt/whenhere && git pull && docker compose up -d --build app && docker image prune -f
```

(Alternatively push images from CI to GHCR and replace `build: .` with `image: ghcr.io/<org>/whenhere-backend:<tag>`.)

### Logs

```bash
docker compose logs -f app     # request logs carry an X-Request-ID; no bodies, tokens or locations are logged
docker compose logs -f caddy
```

## 4. Backups (pg_dump → Hetzner Storage Box)

1. Order a **Storage Box** (BX11 is enough). In its settings enable **SSH support** and, optionally,
   automatic **snapshots** (gives retention on the remote side).
2. Create an SSH key for `deploy` and install it on the Storage Box (port **23**):

   ```bash
   su - deploy
   ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519
   cat ~/.ssh/id_ed25519.pub | ssh -p 23 uXXXXXX@uXXXXXX.your-storagebox.de install-ssh-key
   ssh -p 23 uXXXXXX@uXXXXXX.your-storagebox.de mkdir -p whenhere-db
   ```

3. Cron (daily 03:17 UTC):

   ```bash
   crontab -e
   17 3 * * * STORAGE_BOX=uXXXXXX@uXXXXXX.your-storagebox.de /opt/whenhere/deploy/backup.sh >> /var/backups/whenhere/backup.log 2>&1
   ```

   `deploy/backup.sh` writes `geonotes-<timestamp>.dump` (pg_dump custom format), keeps 7 days
   locally and rsyncs the directory to the Storage Box.

4. **Test a restore** regularly:

   ```bash
   docker compose exec -T db createdb -U geonotes restore_test
   docker compose exec -T db pg_restore -U geonotes -d restore_test --no-owner < /var/backups/whenhere/geonotes-<ts>.dump
   docker compose exec -T db dropdb -U geonotes restore_test
   ```

Data volume is tiny (no coordinates, events expire after 7 days), so dumps stay small.

## 5. Checklist before going live

- [ ] `AUTH_MODE=firebase` (never `dev` in production — it trusts `Bearer dev:<uid>`; `APP_ENV=production` in compose makes the app refuse to start in dev mode).
- [ ] Firebase service account present; startup log does **not** say "Firebase not initialized".
- [ ] Firewall only 22/80/443; Postgres port not published.
- [ ] Backups run and a restore was tested.
- [ ] Real Google Play verification implemented (`billing/PlayPurchaseVerifier.kt` is a stub).
- [ ] Uptime monitor on `https://$API_DOMAIN/health`.
