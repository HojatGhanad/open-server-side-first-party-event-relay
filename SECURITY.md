# 🔐 Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's
[private vulnerability reporting](https://github.com/HojatGhanad/open-server-side-first-party-event-relay/security/advisories/new)
instead. You will get a reply as soon as possible, and a fix will be coordinated with you before any public disclosure.

## Hardening notes

- Keep the relay bound to `127.0.0.1`. It trusts `X-Forwarded-For` from the proxy in front of it.
- Treat `FP_TRACE_TOKEN`, `FP_ADMIN_TOKEN` and `CONTAINER_CONFIG` as secrets.
- Remove or IP-restrict the `/_t/` location after testing.
- Put the panel on its own hostname, behind TLS, ideally with an IP allow-list.
- The protected-transport key is delivered to browsers. It is not a secret and not encryption.
