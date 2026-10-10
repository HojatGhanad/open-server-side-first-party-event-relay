# ⚙️ Configuration reference

The relay reads environment variables (usually from `.env`) and an optional routing table, `tenants.yml`.

- **One site:** environment variables are enough.
- **Several sites:** add one block per hostname to `tenants.yml`. Hostnames not listed there fall back to the environment values.

## Relay environment variables

| Variable | Default | Description |
|---|---|---|
| `FP_SGTM_URL` | `http://sgtm-tagging:8080` | sGTM tagging container |
| `FP_SGTM_PREVIEW_URL` | `http://sgtm-preview:8080` | sGTM preview container |
| `FP_DEPLOYMENT_MODE` | `same_origin` | `same_origin` or `subdomain` |
| `FP_COLLECT_PREFIX` | `/metrics` | Path prefix stripped before forwarding. Empty for a dedicated subdomain |
| `FP_SITE_ORIGINS` | | Subdomain mode: allowed page origins, comma-separated, no wildcards |
| `FP_TENANTS_FILE` | | Path of the routing table inside the container (`/etc/fp/tenants.yml` in compose) |
| `FP_PROTECTED_SEGMENT` | | Protected transport path segment. Empty disables the feature |
| `FP_PROTECTED_KEY` | | Protected transport obfuscation key |
| `FP_LOADER_PATH` | | Tag serving path of the Web Container client in your server container |
| `FP_TRACE_TOKEN` | | Secret path segment for the `/_t/` debug endpoints. Empty disables them |
| `FP_TRACE_CLIENT_IPS` | | IPs that always get full traces, comma-separated |
| `FP_TRACE_ENROLL_HOURS` | `8` | How long `/trace-me` enrolment lasts |
| `FP_TRACE_BUFFER_SIZE` | `200` | Number of traces kept in memory |
| `FP_PREVIEW_TOKEN` | | `X-Gtm-Server-Preview` value. When set, all traffic goes to the preview container |
| `FP_ADMIN_TOKEN` | | Bearer token for `/_admin/stats` and `/_admin/health`. Empty disables them |
| `FP_UPSTREAM_TIMEOUT_MS` | `10000` | Timeout for requests to sGTM |

## `tenants.yml`

```yaml
tenants:
  shop.example.com:
    sgtm:           http://sgtm-tagging:8080
    collect_prefix: /metrics
    serving_path:   /AbC123xyZ
    loader_alias:   /assets/js/app.3f9c1a7e.js
    segment:        Rk3mQ8sLp2
    key:            <32 random alphanumerics>
    aliases:        [www.shop.example.com]
    site_origins:   []
    preview_token:  ""
```

| Key | Description |
|---|---|
| `sgtm` | sGTM container for this site. Lets you give a site its own container |
| `sgtm_preview` | Preview container for this site (defaults to `FP_SGTM_PREVIEW_URL`) |
| `collect_prefix` | Path the relay is mounted on. Empty for a dedicated subdomain |
| `serving_path` | Tag serving path of the Web Container client. The trailing slash is added automatically |
| `loader_alias` | Optional public path for the tag loader, matched exactly |
| `segment` | Protected transport path segment. Empty disables protected transport for this site |
| `key` | Protected transport obfuscation key |
| `aliases` | Other hostnames that share this configuration |
| `site_origins` | Allowed page origins when the collect host differs from the page host |
| `preview_token` | Routes this site's traffic to the preview container while debugging |

Behaviour:

- 🔁 The file is checked every few seconds and reloaded when it changes. No restart needed.
- 🧯 If a new version cannot be parsed, the previous table keeps serving and an error is logged.
- 🔑 Use different `segment` and `key` values for every site. `tools/onboard.sh` generates them.

## Panel environment variables

| Variable | Default | Description |
|---|---|---|
| `PANEL_DB_PASSWORD` | | Postgres password (also used by the postgres container) |
| `PANEL_ADMIN_USER` | `admin` | Login name |
| `PANEL_ADMIN_PASSWORD_HASH` | | BCrypt hash of the password. Empty means nobody can log in |
| `PANEL_RELAY_URL` | `http://relay:8090` | Where the panel polls stats |
| `PANEL_RELAY_TOKEN` | | Must equal the relay's `FP_ADMIN_TOKEN` |
| `PANEL_TENANTS_FILE` | `/etc/fp/tenants.yml` | Routing table the panel edits |
| `PANEL_POLL_SECONDS` | `10` | Poll interval |
| `PANEL_SESSION_HOURS` | `12` | Login session lifetime |
| `PANEL_RETENTION_DAYS` | `90` | How long per-minute history is kept |
| `PANEL_PORT` | `8091` | Listen port |
