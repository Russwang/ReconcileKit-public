#!/usr/bin/env python3
"""Replay the saved core demo requests after restart and check fixed query states."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def request(base, method, path, body=None, key=None):
    headers = {"Content-Type": "application/json", "X-Request-ID": "core-restart-check"}
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def run(base, demo_path):
    demo = json.loads(demo_path.read_text(encoding="utf-8"))
    require(demo["result"] == "PASS", "Original demo did not pass")
    originals = demo["originalResponses"]
    require(len(originals) == 2, "Expected the two fixed core manual operations")
    require(len({row["key"] for row in originals}) == len(originals), "Duplicate saved keys")
    replays = []
    for saved in originals:
        body = saved["requestBody"].encode("utf-8")
        require(json.loads(body) == saved["command"], "Saved command/request bytes differ")
        status, raw = request(base, "POST", "/api/v1/allocations", body, saved["key"])
        require(status == saved["status"], f"Replay status differs for {saved['key']}")
        require(raw == saved["body"].encode("utf-8"), f"Exact replay body differs for {saved['key']}")
        replays.append({"key": saved["key"], "status": status, "exactResponseReplay": True,
                        "responseSha256": hashlib.sha256(raw).hexdigest()})

    expected = json.loads((ROOT / "examples/expected-core.json").read_text(encoding="utf-8"))
    require(demo["states"] == expected, "Original demo states differ from the fixed golden answer")
    states = {}
    for kind, identity in (("invoices", "externalInvoiceId"), ("payments", "externalPaymentId")):
        status, raw = request(base, "GET", f"/api/v1/{kind}?size=100")
        require(status == 200, f"Query {kind} failed")
        states[kind] = [{field: row[field] for field in (identity, "status", "remaining")}
                        for row in json.loads(raw)["items"]]
        states[kind].sort(key=lambda row: row[identity])
    require(states == expected, "Restart query states differ from the fixed golden answer")
    status, raw = request(base, "GET", "/api/v1/reconciliation")
    require(status == 200 and json.loads(raw)["items"] == [], "Pending set changed after restart")
    print(json.dumps({"result": "PASS", "replays": replays, "states": states,
                      "pendingCount": 0, "newImportsPerformed": False}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--demo-json", type=Path, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    try:
        run(args.base_url.rstrip("/"), args.demo_json)
    except (AssertionError, OSError, ValueError, KeyError) as error:
        print(f"Restart check failed: {error}", file=sys.stderr)
        sys.exit(1)
