#!/usr/bin/env python3
"""Assert the fixed core JSON demo against an isolated synthetic ledger."""
import argparse
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCAL_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(base, method, path, payload=None, key=None, expected=200):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json", "X-Request-ID": "core-demo"}
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        with LOCAL_OPENER.open(req, timeout=15) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    if status != expected:
        raise AssertionError(f"{method} {path}: expected {expected}, got {status}: {raw[:300]!r}")
    return json.loads(raw), raw


def run(base, require_empty):
    prefix = "/api/v1"
    if require_empty:
        for kind in ("invoices", "payments"):
            page, _ = request(base, "GET", f"{prefix}/{kind}?size=100")
            assert page["items"] == [], "Use a new isolated Compose project/database for this check"
    reports = []
    for kind in ("invoices", "payments"):
        payload = json.loads((ROOT / "examples" / f"{kind}.json").read_text())
        report, _ = request(base, "POST", f"{prefix}/imports/{kind}", payload, expected=201)
        reports.append(report)
        repeated, _ = request(base, "POST", f"{prefix}/imports/{kind}", payload, expected=201)
        assert repeated["inserted"] == 0 and repeated["duplicate"] == len(payload["rows"])
        assert repeated["notImported"] == 0
    invoices, _ = request(base, "GET", f"{prefix}/invoices?size=100")
    payments, _ = request(base, "GET", f"{prefix}/payments?size=100")
    invoice_ids = {row["externalInvoiceId"]: row["id"] for row in invoices["items"]}
    payment_ids = {row["externalPaymentId"]: row["id"] for row in payments["items"]}
    commands = [
        {"payment_id": payment_ids["PAY-001"], "allocations": [
            {"invoice_id": invoice_ids["INV-102"], "amount": "80.00"},
            {"invoice_id": invoice_ids["INV-101"], "amount": "120.00"}]},
        {"payment_id": payment_ids["PAY-002"], "allocations": [
            {"invoice_id": invoice_ids["INV-103"], "amount": "60"}]},
    ]
    operation_ids = []
    original_responses = []
    for index, command in enumerate(commands, 1):
        key = f"core-demo:v1:manual:{index}"
        result, original = request(base, "POST", f"{prefix}/allocations", command, key, 201)
        original_responses.append({"key": key, "command": command, "status": 201,
                                   "body": original.decode("utf-8"),
                                   "requestBody": json.dumps(command)})
        operation_ids.append(result["operationId"])
        _, replay = request(base, "POST", f"{prefix}/allocations", command, key, 201)
        assert replay == original, "Replay must preserve the original exact response body"
        changed = {**command, "allocations": [{**command["allocations"][0], "amount": "1.00"}]}
        request(base, "POST", f"{prefix}/allocations", changed, key, 409)
    request(base, "POST", f"{prefix}/allocations", {
        "payment_id": payment_ids["PAY-002"],
        "allocations": [{"invoice_id": invoice_ids["INV-103"], "amount": "0.01"}]},
        "core-demo:v1:excess", 409)
    expected = json.loads((ROOT / "examples" / "expected-core.json").read_text())
    actual = {}
    for kind, identity in (("invoices", "externalInvoiceId"), ("payments", "externalPaymentId")):
        page, _ = request(base, "GET", f"{prefix}/{kind}?size=100")
        actual[kind] = [{field: row[field] for field in (identity, "status", "remaining")} for row in page["items"]]
        actual[kind].sort(key=lambda row: row[identity])
    assert actual == expected, (actual, expected)
    pending, _ = request(base, "GET", f"{prefix}/reconciliation")
    assert pending["items"] == []
    audit, _ = request(base, "GET", f"{prefix}/audit-events?size=100")
    for operation in operation_ids:
        assert sum(row["operationId"] == operation for row in audit["items"]) == 1
    print(json.dumps({"result": "PASS", "states": actual, "importReports": reports,
                      "manualOperationIds": operation_ids, "pendingCount": 0,
                      "originalResponses": original_responses}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--require-empty", action="store_true")
    args = parser.parse_args()
    try:
        run(args.base_url.rstrip("/"), args.require_empty)
    except (AssertionError, OSError, ValueError, KeyError) as error:
        print(f"Core demo failed: {error}", file=sys.stderr)
        sys.exit(1)
