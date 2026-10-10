# 🖥️ Operations panel

An optional web console for the relay:

- 📈 Live counters per site, straight from the relay
- 🗂️ Per-minute history in Postgres (charts for 1 hour to 14 days)
- ✅ Delivery rate, p95 latency, protected-transport share
- ✏️ Add, edit and delete sites. Changes are written to `tenants.yml` and picked up by the relay within seconds

The panel runs from the same Docker image as the relay with `SPRING_PROFILES_ACTIVE=panel`, as a separate container.

## 1. Secrets

Add these to `.env`:

```bash
# Database password
echo "PANEL_DB_PASSWORD=$(openssl rand -hex 24)" >> .env

# Admin token shared by relay and panel
echo "FP_ADMIN_TOKEN=$(openssl rand -hex 24)" >> .env

# BCrypt hash of your panel password (never the password itself)
docker run --rm httpd:2.4-alpine htpasswd -bnBC 12 "" 'CHOOSE-A-STRONG-PASSWORD' \
  | tr -d ':\n' | sed 's/^/PANEL_ADMIN_PASSWORD_HASH=/' >> .env
```

## 2. Start

```bash
docker compose up -d relay              # restart the relay so it picks up FP_ADMIN_TOKEN
docker compose --profile panel up -d
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8091/login
```

## 3. Expose it on its own hostname

The panel listens on `127.0.0.1:8091`. Use [`deploy/nginx/panel.conf`](../deploy/nginx/panel.conf), get a certificate, and consider an IP allow-list:

```bash
sudo certbot certonly --nginx -d panel.example.com
sudo nginx -t && sudo systemctl reload nginx
```

## Reading the dashboard

| Metric | Meaning |
|---|---|
| **Events** | Envelope and native requests received |
| **Protected share** | Envelopes as a share of all events. Should approach 100% when protected transport is on |
| **Delivery rate** | Requests sGTM answered with `2xx`. Malformed envelopes are counted separately as *rejected* |
| **p95** | Relay to sGTM latency, not page latency |

Live cards keep working if Postgres is down. Only history pauses.

## Tests

```bash
cd relay && mvn test
```

No database or network required.
