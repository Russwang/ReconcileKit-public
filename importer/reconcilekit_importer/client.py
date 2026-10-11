"""Keep durable private retry payloads and honest progress for separate atomic batches."""
import json
import os
from pathlib import Path
from uuid import uuid4
from .batching import make_batches
from .transport import HttpTransport, verify_reply
from .validation import ValidationError, read_csv


def _write_private(path: Path, data: bytes):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as file:
        file.write(data)
        file.flush()
        os.fsync(file.fileno())


def _journal(directory: Path, report: dict):
    temporary = directory / "report.tmp"
    if temporary.exists():
        temporary.unlink()
    _write_private(temporary, (json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))
    os.replace(temporary, directory / "report.json")


def _totals(report: dict):
    committed = [batch for batch in report["batches"] if batch["status"] == "COMMITTED"]
    report.update(knownCommittedRows=sum(batch["total"] for batch in committed),
                  inserted=sum(batch["inserted"] for batch in committed),
                  duplicate=sum(batch["duplicate"] for batch in committed),
                  unknownRows=sum(batch["total"] for batch in report["batches"] if batch["status"] == "UNKNOWN"),
                  notSentRows=sum(batch["total"] for batch in report["batches"] if batch["status"] == "NOT_SENT"))


def import_file(*, kind: str, path: Path, origin: str, batch_size: int = 1000,
                timeout: float = 10, trace_dir: Path | None = None, transport=None) -> tuple[dict, int]:
    # Full file validation, byte-size planning and URL/timeout checks precede HTTP.
    validated = read_csv(path, kind)
    batches = make_batches(validated, batch_size)
    transport = transport or HttpTransport(origin, timeout)
    run_id = str(uuid4())
    directory = (trace_dir if trace_dir is not None else
                 Path(__file__).resolve().parents[2] / ".runtime" / "importer" / run_id).absolute()
    report = {"version": 1, "runId": run_id, "status": "NOT_STARTED", "kind": kind,
              "source": validated.source, "totalRows": len(validated.rows), "fileSha256": validated.sha256,
              "fileAtomic": False, "traceDirectory": str(directory), "batches": [], "error": None}
    try:
        directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        for batch in batches:
            payload_file = directory / f"batch-{batch.number:04d}.json"
            _write_private(payload_file, batch.payload)
            report["batches"].append({"batch": batch.number, "total": len(batch.rows), "status": "NOT_SENT",
                "requestId": str(uuid4()), "payloadSha256": batch.sha256, "payloadFile": str(payload_file),
                "requestBytes": len(batch.payload), "csvRows": [row.trace(index) for index, row in enumerate(batch.rows, 1)],
                "attemptId": None, "httpStatus": None, "inserted": None, "duplicate": None,
                "notImported": None, "attemptSaved": None, "outcomeUnknown": False, "rowErrors": [], "error": None})
        _totals(report)
        _journal(directory, report)
    except OSError:
        raise ValidationError("TRACE_UNAVAILABLE", "A new private trace directory and payload files are required before HTTP") from None
    exit_code = 0
    for batch, entry in zip(batches, report["batches"], strict=True):
        # A process interruption after this journal is conservatively UNKNOWN.
        entry.update(status="UNKNOWN", outcomeUnknown=True)
        report["status"] = "IN_PROGRESS"
        _totals(report)
        try:
            _journal(directory, report)
        except OSError:
            entry.update(status="NOT_SENT", outcomeUnknown=False)
            report["error"] = {"code": "TRACE_UNAVAILABLE", "message": "Could not persist request-start trace; this batch was not sent"}
            exit_code = 3
            break
        reply = transport.post(kind, batch.payload, entry["requestId"])
        entry.update(verify_reply(reply, source=validated.source, total=len(batch.rows), request_id=entry["requestId"]))
        for error in entry["rowErrors"]:
            if error["row"] > 0:
                error.update(entry["csvRows"][error["row"] - 1])
        _totals(report)
        try:
            _journal(directory, report)
        except OSError:
            report["error"] = {"code": "TRACE_UNAVAILABLE", "message": "Response verified but trace update failed; remaining batches were not sent"}
            exit_code = 4 if entry["status"] == "UNKNOWN" else 3
            break
        if entry["status"] != "COMMITTED" or entry["error"] is not None:
            exit_code = 4 if entry["status"] == "UNKNOWN" else 3
            break
    _totals(report)
    if exit_code == 0:
        report["status"] = "COMMITTED"
    elif report["unknownRows"]:
        report["status"] = "UNKNOWN"
    elif report["knownCommittedRows"] == report["totalRows"]:
        report["status"] = "COMMITTED_WITH_ERROR"
    elif report["knownCommittedRows"]:
        report["status"] = "PARTIAL"
    else:
        report["status"] = "FAILED"
    try:
        _journal(directory, report)
    except OSError:
        report["error"] = {"code": "TRACE_UNAVAILABLE", "message": "Final report could not be saved; stdout preserves verified counts and outcomes"}
        if exit_code == 0:
            exit_code = 3
            report["status"] = "COMMITTED_WITH_ERROR" if report["knownCommittedRows"] == report["totalRows"] else "FAILED"
    return report, exit_code
