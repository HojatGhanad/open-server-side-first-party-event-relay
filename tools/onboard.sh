#!/usr/bin/env bash
# Prints everything needed to add one site: a tenants.yml block with fresh secrets,
# the tagging server URL for your Google tag, and a page snippet.
#
#   ./tools/onboard.sh <collect-host> <serving-path> [collect-prefix] [loader-alias]
#
#   ./tools/onboard.sh shop.example.com /AbC123xyZ /metrics
#   ./tools/onboard.sh metrics.example.com /AbC123xyZ "" /assets/js/app.7c31f9a2.js
#
#   collect-host    hostname the browser sends events to
#   serving-path    tag serving path of the Web Container client in your server container
#   collect-prefix  path the relay is mounted on (same-origin mode). Empty for a subdomain.
#   loader-alias    optional public path for the loader. Generated if omitted.
set -euo pipefail

HOST="${1:?collect host, e.g. shop.example.com}"
SERVING="${2:?tag serving path, e.g. /AbC123xyZ}"
PREFIX="${3:-}"
ALIAS="${4:-}"

rand() { python3 -c "
import secrets,string
a = string.ascii_letters + string.digits
print(''.join(secrets.choice(a) for _ in range($1)))"; }

SEGMENT="$(rand 10)"
KEY="$(rand 32)"

if [ -z "$ALIAS" ]; then
  HASH="$(python3 -c "import secrets; print(secrets.token_hex(4))")"
  ALIAS="/assets/js/app.${HASH}.js"
fi

LOADER="https://${HOST}${ALIAS}"

cat <<OUT
# ─── 1. tenants.yml ──────────────────────────────────────────────────────────

  ${HOST}:
    sgtm:           http://sgtm-tagging:8080
    collect_prefix: ${PREFIX}
    serving_path:   ${SERVING}
    loader_alias:   ${ALIAS}
    segment:        ${SEGMENT}
    key:            ${KEY}
    # site_origins: [https://example.com, https://www.example.com]   # subdomain mode only

# ─── 2. Google tag setting: server_container_url ─────────────────────────────

    https://${HOST}${PREFIX}

# ─── 3. reverse proxy ────────────────────────────────────────────────────────
# Route ${PREFIX:-/}  and  ${ALIAS}  on ${HOST} to the relay (see deploy/nginx/).

# ─── 4. page snippet (replaces the standard GTM snippet) ─────────────────────

<script>
(function () {
  var q = window.dataLayer = window.dataLayer || [];
  var m = {};
  m[['gtm', 'start'].join('.')] = Date.now();
  m.event = 'gtm' + '.js';
  q.push(m);
  var el = document.createElement('script');
  el.async = true;
  el.src = '${LOADER}';
  (document.head || document.documentElement).appendChild(el);
})();
</script>
OUT
