# 🌐 Deployment guide

The relay listens on `127.0.0.1:8090` and is meant to sit behind a reverse proxy that terminates TLS. You choose how the browser reaches it.

## Same-origin mode ⭐ recommended

The relay is reachable on a path of your own website, e.g. `https://shop.example.com/metrics/`.

- ✅ The `FPID` cookie is set by your own host in a true first-party context
- ✅ No CORS, no extra DNS record, no extra certificate
- ✅ Longest cookie lifetime across browsers, including Safari

**nginx:** [`deploy/nginx/same-origin.conf`](../deploy/nginx/same-origin.conf)

**Caddy:**

```caddy
shop.example.com {
    handle /metrics/* {
        reverse_proxy 127.0.0.1:8090
    }
    # ... your existing site
}
```

Caddy sets `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host` automatically.

**Settings:** `FP_DEPLOYMENT_MODE=same_origin`, and `FP_COLLECT_PREFIX` (or `collect_prefix` in `tenants.yml`) equal to the path you routed.

## Subdomain mode

The relay gets its own hostname, e.g. `https://metrics.shop.example.com`.

- Needs a DNS record and a TLS certificate
- Needs `FP_DEPLOYMENT_MODE=subdomain` and `FP_SITE_ORIGINS` (or `site_origins` per tenant), because requests are cross-origin
- Safari may limit cookies set through a subdomain that resolves outside your site's own network to 7 days

**nginx:** [`deploy/nginx/subdomain.conf`](../deploy/nginx/subdomain.conf)

To share the cookie with your main site, configure the cookie domain (e.g. `.shop.example.com`) in the GA4 client of your server container.

## Behind a CDN

If your site is served through a CDN, add a rule that sends `/metrics/*` to the relay server as a separate origin, and keep everything else on your normal origin. Make sure the CDN:

- forwards the visitor IP (`X-Forwarded-For` or an equivalent header your proxy converts),
- does not cache responses on that path,
- passes `Set-Cookie` through.

This works with any CDN that supports path-based origin routing, including regional providers such as ArvanCloud.

## Several sites on one server

1. Add one block per hostname to `tenants.yml` (use `tools/onboard.sh` to generate it).
2. Route each site's collect path or subdomain to the relay.
3. Optionally give a busy site its own sGTM container by setting `sgtm` for that tenant.

## Restricted networks 🌍

Some servers cannot reach Docker Hub, `gcr.io` or GitHub's registry. The relay supports that setup:

```bash
# On a machine with registry access: pull, then stream to the server over SSH
./tools/ship-over-ssh.sh user@your-server

# Build from source through a Docker Hub mirror
docker build --build-arg REGISTRY=mirror.example.com/ -t fp-relay:local relay/
```

Then set `FP_RELAY_IMAGE` / `FP_SGTM_IMAGE` in `.env` to the tags you loaded. The server running sGTM still needs outbound access to Google's collection endpoints.

## Production checklist ✅

- [ ] Port `8090` is bound to loopback only (the default). The relay trusts `X-Forwarded-For`, so it must not be reachable directly.
- [ ] `.env` has mode `600` and is not committed.
- [ ] Image versions are pinned (a release tag for the relay, a digest for sGTM).
- [ ] The `/_t/` location is removed or restricted to your IP after testing.
- [ ] `FP_PREVIEW_TOKEN` is empty.
- [ ] GA4 Realtime shows the correct visitor countries.

## Upgrading

```bash
docker compose pull
docker compose up -d
```

To pin sGTM to a digest:

```bash
docker pull gcr.io/cloud-tagging-10302018/gtm-cloud-image:stable
docker inspect --format='{{index .RepoDigests 0}}' gcr.io/cloud-tagging-10302018/gtm-cloud-image:stable
```

Put the result in `FP_SGTM_IMAGE`.
