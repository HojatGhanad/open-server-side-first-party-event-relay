#!/usr/bin/env python3
"""Synthetic GA4 traffic generator for smoke and soak tests.

Sends realistic GA4 collect requests to the relay (or directly to sGTM) at a fixed
interval and reports delivery rate and latency as it goes. Use a GA4 *test* property.

    python3 tools/synthetic.py --url https://example.com/metrics --tid G-XXXXXXX
    python3 tools/synthetic.py --url http://127.0.0.1:8090/metrics --tid G-XXXXXXX \
        --interval 30 --duration 86400

Standard library only.
"""

from __future__ import annotations

import argparse
import random
import statistics
import string
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36")


def client_id() -> str:
    return f"{random.randint(10**9, 2*10**9)}.{int(time.time())}"


def rand(n: int) -> str:
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=n))


def build(tid: str, event: str, page: str) -> str:
    """A GA4 measurement request shaped like the one gtag actually sends."""
    params = {
        "v": "2",
        "tid": tid,
        "gtm": "45je3bd0v" + rand(6),
        "_p": str(random.randint(10**9, 2 * 10**9)),
        "cid": client_id(),
        "sid": str(int(time.time())),
        "sct": "1",
        "seg": "1",
        "dl": page,
        "dt": "synthetic probe",
        "en": event,
        "_et": str(random.randint(100, 9000)),
    }
    return urllib.parse.urlencode(params)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True,
                    help="collect base, e.g. https://example.com/metrics (no /g/collect)")
    ap.add_argument("--tid", required=True, help="GA4 measurement ID of a TEST property")
    ap.add_argument("--interval", type=float, default=10.0, help="seconds between hits")
    ap.add_argument("--duration", type=float, default=600.0, help="total seconds; 0 = forever")
    ap.add_argument("--page", default="https://example.com/probe")
    args = ap.parse_args()

    target_base = args.url.rstrip("/") + "/g/collect"
    started = time.time()
    sent = ok = fail = 0
    latencies: list[float] = []

    print(f"probing {target_base}  tid={args.tid}  every {args.interval}s")
    print("ts                        status  ms     rate    note")

    try:
        while args.duration == 0 or (time.time() - started) < args.duration:
            event = random.choice(["page_view", "scroll", "user_engagement", "select_item"])
            url = f"{target_base}?{build(args.tid, event, args.page)}"
            req = urllib.request.Request(url, method="POST", data=b"",
                                         headers={"User-Agent": UA, "Origin": args.page})

            t0 = time.perf_counter()
            note = ""
            try:
                with urllib.request.urlopen(req, timeout=15) as resp:
                    status = resp.status
                    resp.read()
            except urllib.error.HTTPError as e:
                status = e.code
                note = "http error"
            except Exception as e:  # noqa: BLE001
                status = 0
                note = type(e).__name__
            ms = (time.perf_counter() - t0) * 1000

            sent += 1
            if 200 <= status < 300:
                ok += 1
                latencies.append(ms)
            else:
                fail += 1

            stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            print(f"{stamp}  {status:>6}  {ms:6.0f}  {ok/sent:6.1%}  {note}", flush=True)

            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\ninterrupted")

    print("\n--- summary ---")
    print(f"sent      : {sent}")
    print(f"delivered : {ok}")
    print(f"failed    : {fail}")
    if sent:
        print(f"rate      : {ok/sent:.2%}")
    if latencies:
        latencies.sort()
        p95 = latencies[max(0, int(len(latencies) * 0.95) - 1)]
        print(f"latency   : p50 {statistics.median(latencies):.0f} ms | p95 {p95:.0f} ms")
    # A 2xx means the endpoint accepted the hit, not that GA4 will report it.
    print("\nNOTE: 2xx = accepted. Confirm the events actually appear in the GA4 "
          "test property's Realtime report before trusting this number.")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
