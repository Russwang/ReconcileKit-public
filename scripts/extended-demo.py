#!/usr/bin/env python3
"""Real CSV client -> Java imports -> explicit auto/manual ledger demonstration.

Run only against a NEW empty synthetic stack. It intentionally creates two
retained pending payments to prove partial-file failure/retry after the golden
business workflow. Private request trace files stay in the ignored run directory.
"""
import argparse
import csv
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import socket
import threading
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
HEADERS = {
    'invoices': ['source', 'external_invoice_id', 'amount', 'currency', 'issued_on'],
    'payments': ['source', 'external_payment_id', 'amount', 'currency', 'received_on',
                 'raw_reference', 'target_invoice_source', 'target_external_invoice_id'],
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def request(base, method, path, payload=None, key=None, status=200):
    body = None if payload is None else json.dumps(payload).encode('utf-8')
    headers = {'Content-Type': 'application/json', 'X-Request-ID': 'extended-demo'}
    if key:
        headers['Idempotency-Key'] = key
    req = urllib.request.Request(base + '/api/v1' + path, data=body, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=15) as response:
            actual, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        actual, raw = error.code, error.read()
    require(actual == status, f'{method} {path}: expected {status}, received {actual}')
    return json.loads(raw), raw


def write_csv(path, kind, rows):
    with path.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=HEADERS[kind])
        writer.writeheader()
        writer.writerows(rows)
    path.chmod(0o600)



@contextmanager
def dropping_response_proxy(base, captured):
    """Test-only loopback proxy: real Java commit is received, client reply is dropped."""
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            try:
                length = int(self.headers.get('Content-Length', '0'))
                require(0 < length <= 2 * 1024 * 1024 and self.path == '/api/v1/imports/payments',
                        'Unexpected proxy request')
                payload = self.rfile.read(length)
                upstream = urllib.request.Request(base + self.path, data=payload, method='POST',
                    headers={'Content-Type': 'application/json', 'X-Request-ID': self.headers['X-Request-ID']})
                with OPENER.open(upstream, timeout=15) as response:
                    captured['status'] = response.status
                    captured['report'] = json.loads(response.read())
                # HTTP201 is observed only after the Java transaction has actually committed.
                self.connection.shutdown(socket.SHUT_RDWR)
            except Exception:
                captured['proxyFailed'] = True
            finally:
                self.connection.close()
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    worker = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.01}, daemon=True)
    worker.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}'
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=3)
        require(not worker.is_alive(), 'Proxy thread did not terminate')


def run(args):
    base = args.base_url.rstrip('/')
    output = ROOT / '.runtime/extended-demo' / uuid.uuid4().hex
    output.mkdir(parents=True)
    report = {'result': 'IN_PROGRESS', 'baseUrl': base, 'outputDirectory': str(output),
              'startedAtUtc': datetime.now(timezone.utc).isoformat(),
              'scope': 'real CSV/Java HTTP integration; one later file failure retains its first committed row',
              'checks': {}}

    def save():
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')

    def cli(label, kind, path, expected_exit=0, batch_size=1000, origin=None):
        command = [str(ROOT / 'scripts/import-csv.sh'), '--kind', kind, '--file', str(path),
                   '--base-url', origin or base, '--batch-size', str(batch_size),
                   '--trace-dir', str(output / ('trace-' + label))]
        completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=90)
        (output / (label + '.json')).write_text(completed.stdout, encoding='utf-8')
        (output / (label + '.stderr.log')).write_text(completed.stderr, encoding='utf-8')
        require(completed.returncode == expected_exit,
                f'{label}: expected CLI exit {expected_exit}, received {completed.returncode}')
        result = json.loads(completed.stdout)
        require(result['fileAtomic'] is False, 'CLI falsely claims whole-file atomicity')
        return result

    save()
    try:
        for kind in HEADERS:
            page, _ = request(base, 'GET', '/' + kind)
            require(page['items'] == [], 'Use a new isolated empty ledger')
        for kind in HEADERS:
            rows = json.loads((ROOT / 'examples' / (kind + '.json')).read_text(encoding='utf-8'))['rows']
            path = output / (kind + '.csv')
            write_csv(path, kind, rows)
            result = cli('initial-' + kind, kind, path)
            require(result['status'] == 'COMMITTED' and result['knownCommittedRows'] == len(rows)
                    and result['inserted'] == len(rows) and result['duplicate'] == 0,
                    'Initial CSV import report mismatch')
            duplicate = cli('duplicate-' + kind, kind, path)
            require(duplicate['status'] == 'COMMITTED' and duplicate['inserted'] == 0
                    and duplicate['duplicate'] == len(rows), 'Repeated source IDs were not duplicates')
        report['checks']['csvImportsAndDuplicates'] = True
        invoices, _ = request(base, 'GET', '/invoices?size=100')
        payments, _ = request(base, 'GET', '/payments?size=100')
        invoice_ids = {row['externalInvoiceId']: row['id'] for row in invoices['items']}
        payment_ids = {row['externalPaymentId']: row['id'] for row in payments['items']}
        ids = [payment_ids['PAY-001'], payment_ids['PAY-002']]
        automatic, _ = request(base, 'POST', '/reconciliation/runs', {'payment_ids': ids})
        (output / 'automatic.json').write_text(json.dumps(automatic, indent=2) + '\n')
        require(automatic['applied'] == 1 and automatic['review'] == 1 and automatic['failed'] == 0,
                'Expected one exact auto allocation and one free-reference review')
        items = {row['paymentId']: row for row in automatic['items']}
        require(items[payment_ids['PAY-001']]['reason'] == 'NO_EXPLICIT_TARGET', 'Free reference was guessed')
        repeated, _ = request(base, 'POST', '/reconciliation/runs', {'payment_ids': ids})
        require(repeated['applied'] == 0 and repeated['review'] == 1 and repeated['skipped'] == 1
                and repeated['failed'] == 0, 'Repeated run made an extra allocation')
        auto_replay = next(row for row in repeated['items'] if row['paymentId'] == payment_ids['PAY-002'])
        require(auto_replay['replayed'] is True, 'Existing AUTO result was not reported as replayed skipped')
        report['checks']['explicitAutoAndRepeat'] = True
        manual = {'payment_id': payment_ids['PAY-001'], 'allocations': [
            {'invoice_id': invoice_ids['INV-102'], 'amount': '80.00'},
            {'invoice_id': invoice_ids['INV-101'], 'amount': '120.00'}]}
        _, original = request(base, 'POST', '/allocations', manual, 'extended-demo:v1:manual', 201)
        _, replayed = request(base, 'POST', '/allocations', manual, 'extended-demo:v1:manual', 201)
        require(original == replayed, 'Manual retry changed the saved original response')
        final_run, _ = request(base, 'POST', '/reconciliation/runs', {'payment_ids': ids})
        require(final_run['applied'] == 0 and final_run['skipped'] == 2 and final_run['review'] == 0
                and final_run['failed'] == 0, 'Fully allocated payments were not skipped')
        states = {}
        for kind, identity in [('invoices', 'externalInvoiceId'), ('payments', 'externalPaymentId')]:
            page, _ = request(base, 'GET', '/' + kind + '?size=100')
            states[kind] = sorted([{field: row[field] for field in [identity, 'status', 'remaining']}
                                   for row in page['items']], key=lambda row: row[identity])
        expected = json.loads((ROOT / 'examples/expected-core.json').read_text(encoding='utf-8'))
        require(states == expected, 'Extended workflow changed the fixed golden answer')
        pending, _ = request(base, 'GET', '/reconciliation')
        require(pending['items'] == [], 'Fixed golden workflow still has pending payments')
        report['goldenStatesBeforeFailureScenario'] = states
        report['checks']['manualExactReplayAndGoldenAnswer'] = True

        # Full-file validation rejects the second row before sending the valid first.
        original_rows = json.loads((ROOT / 'examples/payments.json').read_text())['rows']
        new_row = {**original_rows[0], 'external_payment_id': 'LOCAL-INVALID-PREVENTED', 'amount': '2.00'}
        invalid_row = {**original_rows[1], 'external_payment_id': 'LOCAL-INVALID-DECIMAL', 'amount': '0.001'}
        invalid_path = output / 'invalid-payments.csv'
        write_csv(invalid_path, 'payments', [new_row, invalid_row])
        invalid = cli('local-invalid', 'payments', invalid_path, 2, 1)
        require(invalid['status'] == 'INVALID' and invalid['knownCommittedRows'] == 0,
                'Local invalid file was reported committed')
        after_invalid, _ = request(base, 'GET', '/payments?size=100')
        require(len(after_invalid['items']) == 2, 'Valid prefix was sent before full-file validation')
        report['checks']['fullFileValidationBeforeHttp'] = True

        # Both rows are locally valid; conflict in batch2 must preserve batch1.
        new_row = {**original_rows[0], 'external_payment_id': 'PARTIAL-M10', 'amount': '2.00'}
        conflict_row = {**original_rows[0], 'amount': '201.00'}
        partial_path = output / 'partial-payments.csv'
        write_csv(partial_path, 'payments', [new_row, conflict_row])
        partial = cli('later-batch-conflict', 'payments', partial_path, 3, 1)
        require(partial['status'] == 'PARTIAL' and partial['knownCommittedRows'] == 1
                and partial['inserted'] == 1 and partial['duplicate'] == 0 and partial['unknownRows'] == 0,
                'Known earlier batch commit hidden by later conflict')
        partial_again = cli('later-batch-safe-retry', 'payments', partial_path, 3, 1)
        require(partial_again['knownCommittedRows'] == 1 and partial_again['inserted'] == 0
                and partial_again['duplicate'] == 1 and partial_again['unknownRows'] == 0,
                'Retry of partially committed file duplicated the first identity')
        after_partial, _ = request(base, 'GET', '/payments?size=100')
        require(len(after_partial['items']) == 3, 'Partial file/retry produced unexpected ledger rows')
        original_payment = next(row for row in after_partial['items'] if row['externalPaymentId'] == 'PAY-001')
        require(original_payment['amount'] == '200.00', 'Conflict silently overwrote original payment')
        pending, _ = request(base, 'GET', '/reconciliation')
        require(len(pending['items']) == 1 and pending['items'][0]['externalPaymentId'] == 'PARTIAL-M10'
                and pending['items'][0]['remaining'] == '2.00', 'Failed file scenario lost its real committed prefix')
        report['checks']['laterBatchFailureAndSafeIdentityRetry'] = True
        report['retainedPendingPayment'] = {'externalPaymentId': 'PARTIAL-M10', 'remaining': '2.00'}
        # Actual Java commits through the proxy, which loses the client response afterward.
        lost_row = {**original_rows[0], 'external_payment_id': 'LOST-RESPONSE-M10', 'amount': '2.50'}
        lost_path = output / 'lost-response-payments.csv'
        write_csv(lost_path, 'payments', [lost_row])
        captured = {}
        with dropping_response_proxy(base, captured) as proxy_origin:
            unknown = cli('actual-committed-response-lost', 'payments', lost_path, 4, 1, proxy_origin)
        require(captured.get('status') == 201 and captured['report']['outcome'] == 'COMMITTED'
                and captured['report']['inserted'] == 1 and not captured.get('proxyFailed'),
                'Proxy did not observe a real Java commit before dropping the response')
        require(unknown['status'] == 'UNKNOWN' and unknown['knownCommittedRows'] == 0
                and unknown['unknownRows'] == 1, 'Client invented a known result after losing its response')
        persisted, _ = request(base, 'GET', '/import-batches/' + captured['report']['attemptId'])
        require(persisted == captured['report'], 'Observed Java commit is not independently retrievable')
        safe_retry = cli('actual-lost-response-safe-retry', 'payments', lost_path)
        require(safe_retry['status'] == 'COMMITTED' and safe_retry['inserted'] == 0
                and safe_retry['duplicate'] == 1, 'Unknown-outcome retry duplicated the real committed identity')
        final_payments, _ = request(base, 'GET', '/payments?size=100')
        require(len(final_payments['items']) == 4, 'Lost-response retry duplicated a business row')
        final_pending, _ = request(base, 'GET', '/reconciliation?size=100')
        observed_pending = sorted([(row['externalPaymentId'], row['remaining'], row['reason'])
                                   for row in final_pending['items']])
        require(observed_pending == [('LOST-RESPONSE-M10', '2.50', 'NO_EXPLICIT_TARGET'),
                                     ('PARTIAL-M10', '2.00', 'NO_EXPLICIT_TARGET')],
                'Final pending payments or reasons differ from the retained committed rows')
        original_states = sorted([{field: row[field] for field in
                                  ['externalPaymentId', 'status', 'remaining']}
                                 for row in final_payments['items']
                                 if row['externalPaymentId'] in payment_ids],
                                 key=lambda row: row['externalPaymentId'])
        require(original_states == expected['payments'],
                'Failure/retry scenarios changed the fully allocated golden payments')
        report['checks']['actualJavaCommitThenClientResponseLossAndSafeRetry'] = True
        report['responseLossProof'] = {'realCommittedAttemptId': captured['report']['attemptId'],
                'clientObservedStatus': unknown['status'], 'clientUnknownRows': unknown['unknownRows'],
                'retryInserted': safe_retry['inserted'], 'retryDuplicate': safe_retry['duplicate']}
        report['retainedPendingPayments'] = final_pending['items']
        report.update(result='PASS', finishedAtUtc=datetime.now(timezone.utc).isoformat())
        save()
        print(json.dumps(report, indent=2))
    except Exception as error:
        report.update(result='FAIL', error=str(error), finishedAtUtc=datetime.now(timezone.utc).isoformat())
        save()
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    args = parser.parse_args()
    try:
        run(args)
    except (AssertionError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f'Extended demo failed: {error}', file=sys.stderr)
        sys.exit(1)
