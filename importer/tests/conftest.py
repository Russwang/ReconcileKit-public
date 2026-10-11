import csv
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import threading
from uuid import uuid4
import pytest

from reconcilekit_importer.validation import HEADERS


@pytest.fixture
def csv_file(tmp_path):
    def write(kind="invoices", rows=None, header=None, raw=None):
        path = tmp_path / f"{uuid4()}.csv"
        if raw is not None:
            path.write_bytes(raw if isinstance(raw, bytes) else raw.encode("utf-8"))
            return path
        if rows is None:
            rows = [["demo-invoices", "INV-1", "60.00", "GBP", "2026-10-01"]] if kind == "invoices" else [
                ["demo-payments", "PAY-1", "60.00", "GBP", "2026-10-02", "", "", ""]]
        stream = io.StringIO(newline="")
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(header if header is not None else HEADERS[kind])
        writer.writerows(rows)
        path.write_text(stream.getvalue(), encoding="utf-8", newline="")
        return path
    return write


def import_report(payload, request_id, *, outcome="COMMITTED", inserted=None, duplicate=0, errors=None):
    total = len(payload["rows"])
    committed = outcome == "COMMITTED"
    return {"attemptId": str(uuid4()), "source": payload["source"], "contentHash": "a" * 64,
            "outcome": outcome, "total": total, "inserted": total - duplicate if inserted is None and committed else inserted or 0,
            "duplicate": duplicate if committed else 0, "notImported": 0 if committed else total,
            "rowErrors": errors or [], "errorSummary": None if committed else "Rejected",
            "requestId": request_id, "createdAt": "2026-10-08T10:00:00Z"}


def failed_envelope(report, *, saved=True, unknown=False, code="BUSINESS_CONFLICT"):
    if not saved:
        report["createdAt"] = None
    return {"code": code, "message": "Import rejected", "request_id": report["requestId"],
            "attemptId": report["attemptId"], "rowErrors": report["rowErrors"], "report": report,
            "attemptSaved": saved, "outcomeUnknown": unknown}


@pytest.fixture
def http_server():
    servers = []
    def start(behavior=None):
        captures = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                payload = json.loads(raw)
                request_id = self.headers.get("X-Request-ID")
                capture = {"path": self.path, "payload": payload, "raw": raw,
                           "requestId": request_id, "headers": dict(self.headers)}
                captures.append(capture)
                status, body, extra = behavior(capture, len(captures)) if behavior else (
                    201, import_report(payload, request_id), {})
                encoded = body if isinstance(body, bytes) else json.dumps(body).encode()
                self.send_response(status)
                headers = {"Content-Type": "application/json", "X-Request-ID": request_id} | extra
                for key, value in headers.items():
                    if value is not None:
                        self.send_header(key, value)
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                try:
                    self.wfile.write(encoded)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # Timeout tests deliberately abandon a possibly committed request.
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.daemon_threads = True
        worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        worker.start()
        servers.append((server, worker))
        return f"http://127.0.0.1:{server.server_port}", captures
    yield start
    for server, worker in servers:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)
