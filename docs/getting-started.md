# 🚀 Getting started

This guide takes you from an empty server to GA4 events flowing through your own domain. Plan for about 30 minutes.

## 1. What you need

- A Linux server (a small VPS is fine) with **Docker** and the **Compose plugin**
- A website on a domain you control, served over **HTTPS**
- Access to **Google Tag Manager** and a **GA4** property (a separate test property is a good idea for the first run)

## 2. Create the server container

1. In Google Tag Manager, create a new container and choose **Server**.
2. Choose **Manually provision tagging server** and copy the **container config** string.
3. In the server container, open **Clients** and make sure the **GA4** client exists (it is created by default).
4. Add a tag: **Google Analytics: GA4**. It can inherit the Measurement ID from the incoming event.
5. Add a trigger: **Custom Event**, event name `.*`, with **Use regex matching** ticked. Without the checkbox, GTM matches `.*` literally and nothing fires.
6. Publish the server container.

## 3. Configure the relay

```bash
git clone https://github.com/HojatGhanad/open-server-side-first-party-event-relay.git
cd open-server-side-first-party-event-relay
cp .env.example .env
cp tenants.example.yml tenants.yml
chmod 600 .env
```

Edit `.env`:

| Variable | Value |
|---|---|
| `CONTAINER_CONFIG` | The string from step 2 |
| `FP_TRACE_TOKEN` | A random value, e.g. `openssl rand -hex 24` |
| `FP_COLLECT_PREFIX` | The path you will use on your site, e.g. `/metrics` |

`tenants.yml` must exist before the first start. For a single site you can leave the example as it is: requests for hostnames not listed there use the values from `.env`.

## 4. Start

```bash
docker compose up -d
sleep 30          # sGTM needs a moment for its first health check
docker compose ps
curl -s "http://127.0.0.1:8090/_t/<FP_TRACE_TOKEN>/health.json"
```

Look for `"sgtm_tagging": "up (200)"`.

## 5. Send a test event from the server

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  -H "X-Forwarded-For: 203.0.113.10" \
  "http://127.0.0.1:8090/metrics/g/collect?v=2&tid=G-XXXXXXXXXX&cid=123.456&en=page_view&dl=https%3A%2F%2Fexample.com%2F&_s=1"
```

Replace `G-XXXXXXXXXX` with your Measurement ID. A `2xx` means sGTM accepted the request. Open **GA4 → Reports → Realtime** to confirm Google received it.

## 6. Route a path on your site to the relay

Add [`deploy/nginx/same-origin.conf`](../deploy/nginx/same-origin.conf) to your site's HTTPS server block, adjust the path to match `FP_COLLECT_PREFIX`, then:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

Using a CDN or another proxy? See the [deployment guide](deployment.md).

## 7. Point your Google tag at your domain

In your **web** GTM container, open the **Google tag** and add the configuration parameter:

```
server_container_url = https://your-domain.com/metrics
```

Publish. Browse your site and check:

- 🧪 Browser dev tools: requests go to `https://your-domain.com/metrics/g/collect`
- 🍪 The response sets an `FPID` cookie on your domain
- 📊 GA4 Realtime shows the events with the correct visitor country

## Next steps

- 🛡️ Turn on [protected transport](protected-transport.md)
- 🏢 Add more sites in `tenants.yml` ([configuration](configuration.md))
- 🖥️ Start the [operations panel](panel.md)
