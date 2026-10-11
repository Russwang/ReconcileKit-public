#!/usr/bin/env python3
"""Record a real 150-second synthetic-ledger demonstration as a timed JSON transcript.

Requires a NEW empty local stack; this imports and allocates its synthetic data.
Rendering the captured transcript is a separate operation, never a fake API result.
"""
import argparse
import json
import hashlib
import re
import urllib.parse
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone
import urllib.request
import urllib.error

def source_manifest(root):
    files = []
    for base in ['backend/src', 'backend/.mvn', 'infra', 'scripts', 'importer', 'examples']:
        for path in (root / base).rglob('*'):
            if not path.is_file() or any(part in {'__pycache__', '.pytest_cache', '.venv', 'target'} for part in path.parts) or path.suffix == '.pyc':
                continue
            files.append(path)
    files += [root / name for name in ['backend/pom.xml', 'backend/Dockerfile', 'backend/mvnw', '.dockerignore']]
    rows = [{'path': str(path.relative_to(root)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
             'executable': bool(path.stat().st_mode & 0o111)} for path in sorted(set(files))]
    encoded = (json.dumps(rows, indent=2) + '\n').encode()
    return rows, encoded, hashlib.sha256(encoded).hexdigest()

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def selected_stack(config, inspected, project, port):
    """Bind all HTTP requests and independent SQL to the same selected stack."""
    services = config['services']
    database = services['database']['environment']
    db_name, db_user = database['POSTGRES_DB'], database['POSTGRES_USER']
    assert isinstance(db_name, str) and db_name and isinstance(db_user, str) and db_user
    selected = {}
    for item in inspected:
        labels = item['Config']['Labels']
        assert labels['com.docker.compose.project'] == project
        service = labels['com.docker.compose.service']
        assert service in ('app', 'database') and service not in selected
        selected[service] = item
    assert set(selected) == {'app', 'database'}
    configured = services['app']['ports']
    assert len(configured) == 1 and configured[0]['host_ip'] == '127.0.0.1'
    assert int(configured[0]['target']) == 8080 and int(configured[0]['published']) == port
    actual = selected['app']['NetworkSettings']['Ports']
    assert actual == {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(port)}]}
    db_env = dict(value.split('=', 1) for value in selected['database']['Config']['Env'] if '=' in value)
    app_env = dict(value.split('=', 1) for value in selected['app']['Config']['Env'] if '=' in value)
    assert db_env['POSTGRES_DB'] == db_name and db_env['POSTGRES_USER'] == db_user
    assert app_env['DB_URL'] == 'jdbc:postgresql://database:5432/' + db_name
    assert app_env['DB_USER'] == db_user
    return db_name, db_user


def run(args):
    root = args.source_root.resolve()
    assert re.fullmatch(r'rk-[a-z0-9][a-z0-9_-]{0,59}', args.project)
    assert re.fullmatch(r'[0-9a-f]{40}', args.baseline_commit)
    parts = urllib.parse.urlsplit(args.base_url)
    assert parts.scheme == 'http' and parts.hostname == '127.0.0.1' and parts.port
    assert not parts.username and not parts.password and parts.path in ('', '/') and not parts.query and not parts.fragment
    args.base_url = args.base_url.rstrip('/')
    actual_head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, text=True, capture_output=True, check=True).stdout.strip()
    assert actual_head == args.baseline_commit
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    rows, manifest_bytes, source_hash = source_manifest(root)
    (output/'candidate-source-manifest.json').write_bytes(manifest_bytes)
    start = time.monotonic()
    events = []
    report = {'result': 'IN_PROGRESS', 'startedAtUtc': datetime.now(timezone.utc).isoformat(),
              'baselineCommit': args.baseline_commit, 'project': args.project, 'baseUrl': args.base_url,
              'scope': 'actual local HTTP/SQL timed recording; synthetic GBP ledger; no VPS claim',
              'candidateSourceManifestSha256': source_hash, 'candidateFiles': len(rows),
              'recorderSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'responses': []}

    def save():
        (output / 'transcript.json').write_text(json.dumps({'report': report, 'events': events}, indent=2) + '\n')

    def event(title, lines):
        item = {'elapsedSeconds': round(time.monotonic() - start, 4), 'title': title, 'lines': lines}
        events.append(item)
        save()
        print(title, flush=True)

    def hold(until):
        remaining = until - (time.monotonic() - start)
        if remaining > 0:
            time.sleep(remaining)

    def request(method, path, payload=None, key=None, expected=200):
        data = None if payload is None else json.dumps(payload).encode()
        headers = {'Content-Type': 'application/json', 'X-Request-ID': 'recording-demo'}
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(args.base_url + path, data=data, headers=headers, method=method)
        try:
            with OPENER.open(req, timeout=15) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        response_name = f'response-{len(report["responses"])+1:03d}.json'
        (output / response_name).write_bytes(raw)
        report['responses'].append({'method': method, 'path': path, 'status': status,
                                   'bodyFile': response_name, 'bodySha256': hashlib.sha256(raw).hexdigest(),
                                   'observedAtSeconds': round(time.monotonic()-start,4)})
        assert status == expected, (method, path, status)
        return json.loads(raw), raw, status

    def summary(title, method, path, payload=None, key=None, expected=200, fields=None):
        body, raw, status = request(method, path, payload, key, expected)
        displayed = body if fields is None else {name: body[name] for name in fields}
        event(title, [f'{method} {path}', f'HTTP {status}', *json.dumps(displayed, indent=2).splitlines()])
        return body, raw

    try:
        # Guard all six tables before starting; SQL is independent of application methods.
        docker = [str(root / 'scripts/with-docker.sh'), 'docker', 'compose', '--project-name', args.project,
                  '--env-file', str(args.env_file.resolve()), '--file', str(root / 'infra/compose.yaml')]
        ids = subprocess.run(docker + ['ps', '-q'], text=True, capture_output=True, check=True).stdout.split()
        assert len(ids)==2
        inspected = json.loads(subprocess.run([str(root/'scripts/with-docker.sh'), 'docker', 'inspect', *ids], text=True, capture_output=True, check=True).stdout)
        config = json.loads(subprocess.run(docker + ['config', '--format', 'json'], text=True, capture_output=True, check=True).stdout)
        db_name, db_user = selected_stack(config, inspected, args.project, parts.port)
        report['httpSqlTargetBound'] = True
        report['selectedAppPublishedPort'] = parts.port
        report['containers'] = [{'id': item['Id'], 'imageId': item['Image'], 'creationReference': item['Config']['Image'],
            'service': item['Config']['Labels']['com.docker.compose.service']} for item in inspected]
        empty = "DO $$ BEGIN IF (SELECT count(*) FROM invoices)+(SELECT count(*) FROM payments)+(SELECT count(*) FROM allocations)+(SELECT count(*) FROM audit_events)+(SELECT count(*) FROM idempotency_records)+(SELECT count(*) FROM import_batches)<>0 THEN RAISE EXCEPTION 'Expected empty synthetic ledger'; END IF; END $$;"
        sql = docker + ['exec', '-T', 'database', 'psql', '-X', '-U', db_user, '-d', db_name, '-v', 'ON_ERROR_STOP=1']
        completed = subprocess.run(sql + ['-c', empty], text=True, capture_output=True, timeout=30, check=True)
        (output / 'empty-ledger.log').write_text(completed.stdout)
        health, _, _ = request('GET', '/actuator/health')
        assert health['status'] == 'UP'
        event('ReconcileKit | actual local demonstration', [
            'One customer / GBP / immutable source identities',
            'New isolated ledger: all six business tables are empty',
            'Health observed: UP; no detailed components',
            'Timed HTTP/SQL recording, synthetic data only',
            'VPS deployment and personal learning remain pending'])
        hold(12)
        invoices = json.loads((root / 'examples/invoices.json').read_text())
        payments = json.loads((root / 'examples/payments.json').read_text())
        event('Fixed input and business scope', [
            'INV-101: 120.00 / INV-102: 80.00 / INV-103: 100.00',
            'PAY-001: 200.00, free reference BATCH-01',
            'PAY-002: 60.00, exact explicit target INV-103',
            'Money is a decimal STRING; same amount is not an identity',
            'No guessed matching, no multi-currency or production-finance claim'])
        hold(24)
        fields=['outcome','total','inserted','duplicate','notImported']
        first=[]
        for kind, payload in [('invoices', invoices), ('payments', payments)]:
            body, _, _ = request('POST', '/api/v1/imports/' + kind, payload, expected=201)
            assert body['inserted']==len(payload['rows']) and body['duplicate']==0
            first.append(kind+': '+json.dumps({key:body[key] for key in fields}))
        event('JSON imports commit each atomic batch', ['Actual HTTP 201 for invoices and payments', *first])
        hold(36)
        duplicate=[]
        for kind, payload in [('invoices', invoices), ('payments', payments)]:
            body, _, _ = request('POST', '/api/v1/imports/' + kind, payload, expected=201)
            assert body['inserted']==0 and body['duplicate']==len(payload['rows'])
            duplicate.append(kind+': '+json.dumps({key:body[key] for key in fields}))
        event('Repeat import: immutable source identities', ['Actual HTTP 201', *duplicate,
              'Same identity + same complete business content = duplicate',
              'Business row counts remain 3 invoices / 2 payments'])
        hold(48)
        inv, _, _=request('GET','/api/v1/invoices?size=100')
        pay, _, _=request('GET','/api/v1/payments?size=100')
        invoice_ids={row['externalInvoiceId']:row['id'] for row in inv['items']}
        payment_ids={row['externalPaymentId']:row['id'] for row in pay['items']}
        ids=[payment_ids['PAY-001'],payment_ids['PAY-002']]
        auto,_,status=request('POST','/api/v1/reconciliation/runs',{'payment_ids':ids})
        event('Explicit automatic run: one applied, one review',[
              'POST /api/v1/reconciliation/runs -> HTTP'+str(status),
              'Actual totals: '+json.dumps({key:auto[key] for key in ['total','applied','skipped','review','failed']}),
              *[f'paymentId {item["paymentId"]}: {item["status"]}, reason={item["reason"]}' for item in auto['items']],
              'Free reference BATCH-01 was not guessed',
              'PAY-002 allocated all60.00 to exact INV-103'])
        assert auto['applied']==1 and auto['review']==1 and auto['failed']==0
        hold(62)
        manual={'payment_id':payment_ids['PAY-001'],'allocations':[
            {'invoice_id':invoice_ids['INV-102'],'amount':'80.00'},
            {'invoice_id':invoice_ids['INV-101'],'amount':'120.00'}]}
        original,raw,status=request('POST','/api/v1/allocations',manual,'recording-demo:v1:manual',201)
        event('Manual allocation: both invoices in one transaction',[
              'POST /api/v1/allocations -> HTTP'+str(status),
              'Actual operationId: '+original['operationId'],
              'Actual allocationIds: '+json.dumps(original['allocationIds']),
              'Observed payment state: '+original['payment']['status']+', remaining='+original['payment']['remaining'],
              *[row['externalInvoiceId']+': '+row['status']+', remaining='+row['remaining'] for row in original['invoices']],
              'Shared engine: claim/replay -> payment lock -> sorted invoice locks',
              'Fresh sums -> one transaction for allocations/audit/original response'])
        (output/'manual-original.txt').write_bytes(raw)
        hold(76)
        replay,replayed,_=request('POST','/api/v1/allocations',manual,'recording-demo:v1:manual',201)
        assert raw==replayed
        changed={**manual,'allocations':[{**manual['allocations'][0],'amount':'1.00'}]}
        conflict,_,status=request('POST','/api/v1/allocations',changed,'recording-demo:v1:manual',409)
        event('Persistent idempotency: exact replay and changed-key conflict',[
            'Same request + same key -> HTTP201, exact original response bytes',
            'Observed operation ID: '+original['operationId'],
            'Changed business content + same key -> HTTP'+str(status),
            'Observed error code: '+conflict['code'],
            'Replay happens before exhausted payment balance checks'])
        hold(90)
        states={}
        for kind,identity in [('invoices','externalInvoiceId'),('payments','externalPaymentId')]:
            page,_,_=request('GET','/api/v1/'+kind+'?size=100')
            states[kind]=sorted([{name:row[name] for name in [identity,'status','remaining']} for row in page['items']],key=lambda x:x[identity])
        assert states==json.loads((root/'examples/expected-core.json').read_text())
        pending,_,_=request('GET','/api/v1/reconciliation')
        assert pending['items']==[]
        event('Observed golden answer: the two balances are independent',[
            'INV-101 PAID 0.00 / INV-102 PAID 0.00',
            'INV-103 PARTIALLY_PAID 40.00',
            'PAY-001 FULLY_ALLOCATED 0.00 / PAY-002 FULLY_ALLOCATED 0.00',
            'Actual pending payment count: 0',
            'Exact response fields match examples/expected-core.json'])
        hold(104)
        repeated,_,status=request('POST','/api/v1/reconciliation/runs',{'payment_ids':ids})
        event('Repeat automatic run: no extra effects',[
              'POST /api/v1/reconciliation/runs -> HTTP'+str(status),
              'Actual totals: '+json.dumps({key:repeated[key] for key in ['total','applied','skipped','review','failed']}),
              *[f'paymentId {item["paymentId"]}: {item["status"]}, replayed={item["replayed"]}' for item in repeated['items']],
              'Existing successful AUTO result replays; manual-full payment skips',
              'No new allocation or success audit'])
        assert repeated['applied']==0 and repeated['skipped']==2 and repeated['failed']==0
        hold(116)
        completed=subprocess.run(sql,text=True,input=(root/'scripts/check-invariants.sql').read_text(),capture_output=True,timeout=30,check=True)
        (output/'independent-invariants.log').write_text(completed.stdout)
        assert 'PASS: payment/invoice bounds' in completed.stdout
        audit,_,_=request('GET','/api/v1/audit-events?size=100')
        assert len(audit['items'])==6
        event('Independent PostgreSQL and real success audits',[
              *completed.stdout.strip().splitlines(),
              'Observed audit events: 6 (4 import operations + 2 allocation operations)',
              'AUTO and MANUAL use the same safe allocation engine'])
        hold(132)
        meta=json.loads(args.ci_metadata.read_text())
        assert meta['status']=='completed' and meta['conclusion']=='success'
        event('Verification evidence and limits',[
            'Previously completed actual CI: '+meta['url'],
            'Exact CI commit: '+meta['headSha'],
            'Jobs: '+', '.join(job['name']+':'+job['conclusion'] for job in meta['jobs']),
            'Real PG contention/fault tests; CSV134; actual extended integration',
            'Source, tests, evidence and guides trace through stage commits',
            'Controlled VPS/remote recovery pending; release stays v0.1.x',
            'Personal replication and understanding remain separate'])
        hold(150)
        assert source_manifest(root)[2] == source_hash, 'Source changed during recording'
        report['candidateSourceUnchanged'] = True
        report.update(result='PASS',durationSeconds=round(time.monotonic()-start,4),finishedAtUtc=datetime.now(timezone.utc).isoformat(),goldenStates=states,auditCount=6)
        save()
        print('Recorded actual timed demonstration: PASS',flush=True)
    except BaseException as error:
        report.update(result='FAIL',durationSeconds=round(time.monotonic()-start,4),error=type(error).__name__)
        save()
        raise


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root',type=Path,required=True)
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--project',required=True)
    parser.add_argument('--env-file',type=Path,required=True)
    parser.add_argument('--baseline-commit',required=True)
    parser.add_argument('--ci-metadata',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    run(parser.parse_args())
