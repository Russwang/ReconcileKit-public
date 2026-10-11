import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import time
import pytest
from conftest import failed_envelope, import_report
from reconcilekit_importer.client import import_file
from reconcilekit_importer.transport import base_url, timeout_value
from reconcilekit_importer.validation import ValidationError


def run(csv_file, http_server, tmp_path, *, behavior=None, rows=None, kind="invoices", batch_size=1000, timeout=10):
    file = csv_file(kind, rows=rows)
    origin, captures = http_server(behavior)
    trace = tmp_path / "trace"
    report, code = import_file(kind=kind, path=file, origin=origin, batch_size=batch_size, timeout=timeout, trace_dir=trace)
    return report, code, captures, trace


def test_real_http_success_keeps_private_payload_correlation_and_exact_counts(csv_file, http_server, tmp_path):
    report, code, captures, trace = run(csv_file, http_server, tmp_path)
    assert code == 0 and report["status"] == "COMMITTED" and report["knownCommittedRows"] == 1
    assert report["inserted"] == 1 and report["duplicate"] == 0
    assert report["fileAtomic"] is False
    batch = report["batches"][0]
    assert captures[0]["path"] == "/api/v1/imports/invoices"
    assert captures[0]["requestId"] == batch["requestId"]
    assert Path(batch["payloadFile"]).read_bytes() == captures[0]["raw"]
    assert hashlib.sha256(captures[0]["raw"]).hexdigest() == batch["payloadSha256"]
    assert stat.S_IMODE(trace.stat().st_mode) == 0o700
    assert stat.S_IMODE(Path(batch["payloadFile"]).stat().st_mode) == 0o600
    assert stat.S_IMODE((trace / "report.json").stat().st_mode) == 0o600
    assert json.loads((trace / "report.json").read_text()) == report
    assert "INV-1" not in json.dumps(report)


def test_report_duplicate_is_known_committed_input_row_not_new_business_row(csv_file, http_server, tmp_path):
    def duplicate(capture, _):
        return 201, import_report(capture["payload"], capture["requestId"], duplicate=1), {}
    report, code, captures, _ = run(csv_file, http_server, tmp_path, behavior=duplicate)
    assert code == 0 and report["knownCommittedRows"] == 1
    assert report["inserted"] == 0 and report["duplicate"] == 1 and len(captures) == 1


@pytest.mark.parametrize("bad_last", [
    ["demo-invoices", "INV-2", "0.001", "GBP", "2026-10-01"],
    ["demo-invoices", "INV-1", "61", "GBP", "2026-10-01"],
    ["different", "INV-2", "60", "GBP", "2026-10-01"]])
def test_complete_file_validation_sends_no_valid_prefix(csv_file, http_server, tmp_path, bad_last):
    origin, captures = http_server()
    file = csv_file(rows=[["demo-invoices", "INV-1", "60", "GBP", "2026-10-01"], bad_last])
    with pytest.raises(ValidationError):
        import_file(kind="invoices", path=file, origin=origin, batch_size=1, trace_dir=tmp_path / "trace")
    assert captures == [] and not (tmp_path / "trace").exists()


def test_late_oversized_utf8_row_is_rejected_before_any_smaller_batch_is_sent(csv_file, http_server, tmp_path):
    origin, captures = http_server()
    file = csv_file("payments", rows=[
        ["demo-payments", "P1", "1", "GBP", "2026-10-01", "", "", ""],
        ["demo-payments", "P2", "1", "GBP", "2026-10-01", "😀" * 524_288, "", ""]])
    with pytest.raises(ValidationError) as error:
        import_file(kind="payments", path=file, origin=origin, batch_size=1, trace_dir=tmp_path / "trace")
    assert error.value.code == "ROW_TOO_LARGE"
    assert captures == [] and not (tmp_path / "trace").exists()


def test_late_http_conflict_preserves_first_commit_and_stops_remaining(csv_file, http_server, tmp_path):
    def second_fails(capture, index):
        if index == 1:
            return 201, import_report(capture["payload"], capture["requestId"]), {}
        report = import_report(capture["payload"], capture["requestId"], outcome="REJECTED",
                               errors=[{"row": 1, "code": "BUSINESS_CONFLICT", "message": "Server detail"}])
        return 409, failed_envelope(report), {}
    rows = [["demo-invoices", f"INV-{i}", "60", "GBP", "2026-10-01"] for i in range(3)]
    report, code, captures, trace = run(csv_file, http_server, tmp_path, rows=rows, behavior=second_fails, batch_size=1)
    assert code == 3 and report["status"] == "PARTIAL" and len(captures) == 2
    assert report["knownCommittedRows"] == 1 and report["inserted"] == 1
    assert report["unknownRows"] == 0 and report["notSentRows"] == 1
    assert [batch["status"] for batch in report["batches"]] == ["COMMITTED", "REJECTED", "NOT_SENT"]
    error = report["batches"][1]["rowErrors"][0]
    assert error["row"] == error["batchRow"] == 1 and error["csvLineStart"] == 3
    assert error["csvRecord"] == 2 and "message" not in error
    assert json.loads((trace / "report.json").read_text())["knownCommittedRows"] == 1


def test_timeout_outcome_unknown_payload_retained_no_retry_or_later_batch(csv_file, http_server, tmp_path):
    def slow_commit(capture, _):
        time.sleep(0.3)
        return 201, import_report(capture["payload"], capture["requestId"]), {}
    rows = [["demo-invoices", f"INV-{i}", "60", "GBP", "2026-10-01"] for i in range(2)]
    report, code, captures, trace = run(csv_file, http_server, tmp_path, rows=rows, behavior=slow_commit,
                                       batch_size=1, timeout=0.1)
    assert code == 4 and report["status"] == "UNKNOWN" and len(captures) == 1
    assert report["unknownRows"] == 1 and report["notSentRows"] == 1 and report["knownCommittedRows"] == 0
    assert report["batches"][0]["inserted"] is None and report["batches"][0]["notImported"] is None
    assert Path(report["batches"][0]["payloadFile"]).read_bytes() == captures[0]["raw"]


@pytest.mark.parametrize("saved", [True, False])
def test_503_failure_report_persistence_distinguishes_known_and_unknown(csv_file, http_server, tmp_path, saved):
    def database_failure(capture, _):
        report = import_report(capture["payload"], capture["requestId"], outcome="FAILED")
        return 503, failed_envelope(report, saved=saved, unknown=not saved, code="RETRYABLE_DATABASE_ERROR"), {}
    report, code, _, _ = run(csv_file, http_server, tmp_path, behavior=database_failure)
    assert code == (3 if saved else 4)
    assert report["status"] == ("FAILED" if saved else "UNKNOWN")
    assert report["batches"][0]["attemptSaved"] is saved
    assert report["batches"][0]["inserted"] == (0 if saved else None)


def test_preserved_committed_attempt_on_error_is_counted_but_not_auto_retried(csv_file, http_server, tmp_path):
    def preserved(capture, _):
        return 503, failed_envelope(import_report(capture["payload"], capture["requestId"]), code="RETRYABLE_DATABASE_ERROR"), {}
    report, code, captures, _ = run(csv_file, http_server, tmp_path, behavior=preserved)
    assert code == 3 and len(captures) == 1
    assert report["knownCommittedRows"] == report["inserted"] == 1
    assert report["unknownRows"] == 0 and report["batches"][0]["status"] == "COMMITTED"
    assert report["status"] == "COMMITTED_WITH_ERROR"
    assert report["batches"][0]["error"]["code"] == "RETRYABLE_DATABASE_ERROR"


@pytest.mark.parametrize("status,expected", [(413, "REJECTED"), (404, "REJECTED"), (503, "UNKNOWN"), (500, "UNKNOWN"), (408, "UNKNOWN")])
def test_entry_error_or_gateway_response_is_honest(csv_file, http_server, tmp_path, status, expected):
    def error(capture, _):
        return status, {"code": "BODY_TOO_LARGE", "message": "Do not echo private CSV", "request_id": capture["requestId"]}, {}
    report, code, _, _ = run(csv_file, http_server, tmp_path, behavior=error)
    assert report["batches"][0]["status"] == expected
    assert code == (4 if expected == "UNKNOWN" else 3)


def test_post_redirect_is_never_followed_to_other_destination(csv_file, http_server, tmp_path):
    destination, second_captures = http_server()
    def redirect(capture, _):
        return 307, {}, {"Location": destination + "/api/v1/imports/invoices"}
    report, code, captures, _ = run(csv_file, http_server, tmp_path, behavior=redirect)
    assert code == 4 and len(captures) == 1 and second_captures == []
    assert report["batches"][0]["httpStatus"] == 307


def test_ambient_proxy_does_not_receive_localhost_csv(csv_file, http_server, tmp_path, monkeypatch):
    proxy, proxy_captures = http_server()
    for name in ("http_proxy", "HTTP_PROXY", "https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY"):
        monkeypatch.setenv(name, proxy)
    monkeypatch.setenv("no_proxy", "")
    monkeypatch.setenv("NO_PROXY", "")
    report, code, captures, _ = run(csv_file, http_server, tmp_path)
    assert code == 0 and len(captures) == 1 and proxy_captures == []


@pytest.mark.parametrize("mutation", [
    lambda report: report.update(inserted=True), lambda report: report.update(inserted=-1),
    lambda report: report.update(inserted=2), lambda report: report.update(total=2),
    lambda report: report.update(duplicate=1), lambda report: report.update(notImported=1),
    lambda report: report.update(source="other"), lambda report: report.update(requestId="other"),
    lambda report: report.update(contentHash="bad"), lambda report: report.update(attemptId="bad"),
    lambda report: report.update(outcome="RUNNING"), lambda report: report.update(createdAt=None),
    lambda report: report.update(rowErrors=[{"row": 1, "code": "INVALID", "message": "bad"}]),
    lambda report: report.update(extra="unexpected")])
def test_invalid_success_report_never_invents_committed_rows(csv_file, http_server, tmp_path, mutation):
    def invalid(capture, _):
        report = import_report(capture["payload"], capture["requestId"])
        mutation(report)
        return 201, report, {}
    report, code, captures, _ = run(csv_file, http_server, tmp_path, behavior=invalid)
    assert code == 4 and report["knownCommittedRows"] == 0 and report["unknownRows"] == 1
    assert len(captures) == 1


@pytest.mark.parametrize("body,headers", [(b'{', {}), (b'[]', {}), (b'{"x":NaN}', {}),
    (b'{"x":1,"x":2}', {}), (b'\xff', {}), (b'{}', {"Content-Type": "text/html"}),
    (b'{}', {"X-Request-ID": "different"}), (b'{}', {"X-Request-ID": None})])
def test_malformed_json_or_headers_unknown(csv_file, http_server, tmp_path, body, headers):
    report, code, _, _ = run(csv_file, http_server, tmp_path, behavior=lambda *_: (201, body, headers))
    assert code == 4 and report["unknownRows"] == 1


def test_deep_response_is_unknown_and_preserves_previous_real_http_commit(csv_file, http_server, tmp_path):
    def deep_second(capture, index):
        if index == 1:
            return 201, import_report(capture["payload"], capture["requestId"]), {}
        return 201, b'[' * 10_000 + b'0' + b']' * 10_000, {}
    rows = [["demo-invoices", f"INV-{i}", "60", "GBP", "2026-10-01"] for i in range(3)]
    report, code, captures, trace = run(csv_file, http_server, tmp_path, rows=rows, behavior=deep_second, batch_size=1)
    assert code == 4 and report["status"] == "UNKNOWN" and len(captures) == 2
    assert report["knownCommittedRows"] == report["inserted"] == 1
    assert report["unknownRows"] == report["notSentRows"] == 1
    assert [batch["status"] for batch in report["batches"]] == ["COMMITTED", "UNKNOWN", "NOT_SENT"]
    assert json.loads((trace / "report.json").read_text())["knownCommittedRows"] == 1


@pytest.mark.parametrize("mutation", [
    lambda value: value.update(attemptSaved="true"), lambda value: value.update(outcomeUnknown="false"),
    lambda value: value.update(attemptSaved=True, outcomeUnknown=True),
    lambda value: value.update(attemptId="00000000-0000-0000-0000-000000000000"),
    lambda value: value.update(rowErrors=[{"row": 0, "code": "INVALID", "message": "mismatch"}]),
    lambda value: value["report"].update(inserted=1),
    lambda value: value["report"].update(rowErrors=[{"row": 2, "code": "INVALID", "message": "bad row"}])])
def test_inconsistent_failure_envelopes_are_unknown(csv_file, http_server, tmp_path, mutation):
    def invalid(capture, _):
        envelope = failed_envelope(import_report(capture["payload"], capture["requestId"], outcome="FAILED"))
        mutation(envelope)
        return 503, envelope, {}
    report, code, _, _ = run(csv_file, http_server, tmp_path, behavior=invalid)
    assert code == 4 and report["unknownRows"] == 1


@pytest.mark.parametrize("url", ["ftp://localhost", "http://user:secret@localhost", "http://localhost/api/v1", "http://localhost?x=1", "http://localhost#x", "http://localhost:99999", "http://localhost:0", "http://host name", "http://[not-ipv6]"])
def test_origin_validation_rejects_credentials_and_misdirected_endpoints(url):
    with pytest.raises(ValidationError):
        base_url(url)


@pytest.mark.parametrize("timeout", [float("nan"), float("inf"), 0, -1, 121])
def test_timeout_finite_bounded(timeout):
    with pytest.raises(ValidationError):
        timeout_value(timeout)


def test_existing_trace_directory_refuses_overwrite_before_http(csv_file, http_server, tmp_path):
    origin, captures = http_server()
    trace = tmp_path / "trace"
    trace.mkdir()
    marker = trace / "keep"
    marker.write_text("preserve")
    with pytest.raises(ValidationError):
        import_file(kind="invoices", path=csv_file(), origin=origin, trace_dir=trace)
    assert captures == [] and marker.read_text() == "preserve"


def test_final_only_journal_failure_reports_operational_error_without_inventing_unknown(csv_file, http_server, tmp_path, monkeypatch):
    import reconcilekit_importer.client as client
    original = client._journal
    calls = []
    def final_fails(directory, report):
        calls.append(report["status"])
        if report["status"] == "COMMITTED":
            raise OSError("Injected final report write failure")
        original(directory, report)
    monkeypatch.setattr(client, "_journal", final_fails)
    report, code, captures, trace = run(csv_file, http_server, tmp_path)
    assert calls == ["NOT_STARTED", "IN_PROGRESS", "IN_PROGRESS", "COMMITTED"]
    assert code == 3 and report["error"]["code"] == "TRACE_UNAVAILABLE"
    assert report["status"] == "COMMITTED_WITH_ERROR" and report["knownCommittedRows"] == report["inserted"] == 1
    assert report["unknownRows"] == report["notSentRows"] == 0 and len(captures) == 1
    assert report["batches"][0]["status"] == "COMMITTED" and report["batches"][0]["outcomeUnknown"] is False
    saved = json.loads((trace / "report.json").read_text())
    assert saved["status"] == "IN_PROGRESS" and saved["knownCommittedRows"] == 1


def test_public_cli_emits_json_exit2_and_no_request_for_invalid_file(csv_file, http_server, tmp_path):
    origin, captures = http_server()
    file = csv_file(rows=[["demo-invoices", "INV-1", "0.001", "GBP", "2026-10-01"]])
    root = Path(__file__).resolve().parents[2]
    result = subprocess.run([str(root / "scripts/import-csv.sh"), "--kind", "invoices", "--file", str(file),
        "--base-url", origin, "--trace-dir", str(tmp_path / "trace")], capture_output=True, text=True, timeout=10)
    assert result.returncode == 2 and json.loads(result.stdout)["status"] == "INVALID"
    assert "0.001" not in result.stdout and captures == []


def test_public_cli_real_http_stdout_json_and_exit0(csv_file, http_server, tmp_path):
    origin, captures = http_server()
    file = csv_file("payments")
    root = Path(__file__).resolve().parents[2]
    result = subprocess.run([str(root / "scripts/import-csv.sh"), "--kind", "payments", "--file", str(file),
        "--base-url", origin, "--trace-dir", str(tmp_path / "trace")], capture_output=True, text=True, timeout=10)
    report = json.loads(result.stdout)
    assert result.returncode == 0 and report["status"] == "COMMITTED"
    assert report["knownCommittedRows"] == 1 and len(captures) == 1
    assert captures[0]["payload"]["rows"][0]["raw_reference"] == ""
    assert captures[0]["payload"]["rows"][0]["target_invoice_source"] is None
