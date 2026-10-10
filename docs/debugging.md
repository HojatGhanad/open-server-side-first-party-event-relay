# 🔍 Debugging and troubleshooting

## Debug endpoints

All responses are JSON with `Cache-Control: no-store`. Replace `<token>` with `FP_TRACE_TOKEN`. A wrong or empty token returns `404`.

| Endpoint | Shows |
|---|---|
| `/_t/<token>/health.json` | Relay status, sGTM reachability, preview session, tenants, counters |
| `/_t/<token>/summary.json` | Received, delivered, failed, success rate, p50 and p95 latency per tenant |
| `/_t/<token>/recent.json?limit=20` | Full request and response for traced clients |
| `/_t/<token>/trace-me` | Enrols the caller's IP for full traces (`FP_TRACE_ENROLL_HOURS`) |
| `/_t/<token>/trace-off` | Clears all enrolments |

Full traces are recorded only for enrolled IPs. Everyone else only increments counters, so visitor payloads are never exposed.

Add a random `?cb=...` parameter when polling through a cache.

> `delivered` means sGTM answered with `2xx`. It does not prove Google accepted the event. GA4 Realtime is the final check.

## GTM Preview

1. Open **Preview** in your server container and copy the `X-Gtm-Server-Preview` header value.
2. Set `FP_PREVIEW_TOKEN` (or `preview_token` for one tenant) and restart the relay (or save `tenants.yml`).
3. Events now appear in the GTM Preview UI.
4. **Clear it afterwards.** While set, all traffic goes to the preview container.

## Test without Google

`tools/mock_sgtm.py` stands in for sGTM and echoes what it received:

```bash
python3 tools/mock_sgtm.py &
FP_SGTM_URL=http://127.0.0.1:9999 FP_SGTM_PREVIEW_URL=http://127.0.0.1:9999 FP_TRACE_TOKEN=t \
  java -jar relay/target/fp-relay.jar

curl -i -X POST 'http://127.0.0.1:8090/metrics/g/collect?v=2&en=page_view' \
  -H 'X-Forwarded-For: 203.0.113.7' -H 'Host: shop.example.com' -d ''
```

Expect `204`, `set-cookie: FPID=...`, `x-mock-saw-xff: 203.0.113.7` and `x-mock-saw-host: shop.example.com`.

## Soak test

```bash
python3 tools/synthetic.py --url http://127.0.0.1:8090/metrics --tid G-XXXXXXX \
  --interval 30 --duration 86400
```

Sends realistic GA4 events and reports delivery rate and latency over time. Use a test property.

## Common problems

| Symptom | Cause and fix |
|---|---|
| All visitors appear in one country (your server's) | `X-Forwarded-For` is not set by the proxy in front of the relay. Add it. |
| No `FPID` cookie | A proxy strips `Set-Cookie`. Add `proxy_pass_header Set-Cookie` (nginx) or the equivalent. |
| Events reach sGTM but no GA4 tag fires | The `.*` Custom Event trigger needs **Use regex matching** ticked. |
| `sgtm-tagging` keeps restarting with `Invalid preview server URL` | `PREVIEW_SERVER_URL` must be an `https://` URL. Leave it unset. The relay routes preview traffic itself. |
| Loader returns `400` with an empty body | The serving path is missing its trailing slash. Configure `serving_path`, the relay adds the slash. |
| CORS errors in subdomain mode | Set `FP_DEPLOYMENT_MODE=subdomain` and exact `FP_SITE_ORIGINS`. Do not add CORS headers in the proxy as well. |
| A second site is ignored | `tenants.yml` did not exist at first start, so Docker created a directory. Remove it, create the file, restart. |
| `/_admin/*` returns `404` | `FP_ADMIN_TOKEN` is empty on the relay, or the panel's token does not match. |
