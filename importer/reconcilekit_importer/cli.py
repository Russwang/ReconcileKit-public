import argparse
import json
from pathlib import Path
import sys
from .client import import_file
from .validation import ValidationError


def main(arguments=None) -> int:
    parser = argparse.ArgumentParser(description="Validate an entire CSV, then import separate atomic batches. No automatic retry or allocation.")
    parser.add_argument("--kind", required=True, choices=("invoices", "payments"))
    parser.add_argument("--file", required=True, type=Path)
    parser.add_argument("--base-url", required=True, help="HTTP(S) origin, e.g. http://127.0.0.1:8080")
    parser.add_argument("--batch-size", default=1000, type=int)
    parser.add_argument("--timeout", default=10.0, type=float)
    parser.add_argument("--trace-dir", type=Path, help="New private directory for payloads and correlation reports; must not exist")
    options = parser.parse_args(arguments)
    try:
        report, status = import_file(kind=options.kind, path=options.file, origin=options.base_url,
                                    batch_size=options.batch_size, timeout=options.timeout, trace_dir=options.trace_dir)
    except ValidationError as error:
        report, status = {"version": 1, "status": "INVALID", "fileAtomic": False,
                          "knownCommittedRows": 0, "inserted": 0, "duplicate": 0, "unknownRows": 0,
                          "batches": [], "error": error.report()}, 2
    print(json.dumps(report, ensure_ascii=False, separators=(",", ":"), allow_nan=False))
    return status
