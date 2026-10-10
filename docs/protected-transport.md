# 🛡️ Protected transport

First-party routing moves measurement requests onto your own domain. The request itself still has Google's familiar shape (`/g/collect?v=2&tid=...`). Protected transport changes that shape between the browser and the relay.

## How it works

1. The browser loads the tag loader **through the relay**. The relay prepends a small script, `fp-transport.js`, rendered with your site's values.
2. The script hooks `navigator.sendBeacon`, `fetch` and `XMLHttpRequest`. Requests that target your collect path are wrapped in an opaque envelope and posted to one random path segment, e.g. `/metrics/Rk3mQ8sLp2`.
3. The relay unwraps the envelope and forwards the original request to sGTM. Your server container sees a normal GA4 request.

Requests to any other URL are never touched. If anything fails in the browser, the script falls back to the original call, and the relay keeps accepting standard requests.

> The envelope is obfuscated with a per-site key that is delivered to the browser. It is not encryption. TLS protects the data in transit.

## Setup

**1. Generate values**

```bash
./tools/onboard.sh shop.example.com /AbC123xyZ /metrics
```

`/AbC123xyZ` is the tag serving path of the **Web Container** client in your server container. The script prints a `tenants.yml` block with a fresh `segment` and `key`, the `server_container_url`, and a page snippet.

**2. Add the block to `tenants.yml`.** The relay reloads it within seconds.

**3. Route the loader path** (if you use `loader_alias`) to the relay. See the commented block in [`deploy/nginx/same-origin.conf`](../deploy/nginx/same-origin.conf).

**4. Replace the GTM snippet** on your pages with the snippet printed by `onboard.sh`. It loads the loader from your domain and seeds `dataLayer` exactly like the standard snippet.

## Verify

- In dev tools, measurement requests go to `/metrics/<segment>` with a `text/plain` body.
- In the [panel](panel.md), **protected share** rises towards 100% as cached loaders expire.
- `/_t/<token>/recent.json` shows traces of kind `envelope`.

## Tips

- 🔑 Use different `segment` and `key` values for every site.
- 🧩 Choose a `loader_alias` that looks like your own build output.
- 🧪 Roll out on one site first and compare GA4 numbers before and after.
