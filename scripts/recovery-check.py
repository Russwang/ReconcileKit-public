#!/usr/bin/env python3
"""Verify M12 LOCAL persistence and recovery of the retained M10 synthetic ledger.

Both source containers are recreated using their exact running image IDs, retaining
their named database volume. The custom backup is restored only into an unused
different Compose project and newly created empty database volume. Neither project
is removed afterward, including on failure. This does not verify a VPS.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('rk_backup', ROOT / 'scripts/backup.py')
backup_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup_module)
Stack, require, private_json = backup_module.Stack, backup_module.require, backup_module.private_json
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def require_distinct(source, restore):
    require(source.project != restore.project, 'Restore project must differ from source')
    require(source.env_file != restore.env_file, 'Restore environment file must differ from source')
    require(source.volume != restore.volume, 'Restore volume must differ from source')
    require(source.port != restore.port, 'Restore loopback port must differ from source')


def verification_manifest(source):
    """Freeze runtime sources and the exact recovery helpers, excluding env/secret files."""
    candidates = [(f'helpers/{name}', ROOT / name) for name in (
        'scripts/backup.sh', 'scripts/backup.py', 'scripts/recovery-check.py',
        'scripts/test-recovery-guards.py', 'scripts/snapshot-ledger-full.sql',
        'scripts/with-docker.sh', 'scripts/check-invariants.sql',
        'scripts/check-extended.sql', 'scripts/snapshot-ledger.sql', 'examples/expected-core.json')]
    for relative in ('backend/pom.xml', 'backend/Dockerfile', '.dockerignore', 'infra/compose.yaml'):
        candidates.append((f'runtime/{relative}', source.root / relative))
    for relative in ('backend/src/main', 'examples', 'scripts'):
        candidates.extend((f'runtime/{path.relative_to(source.root)}', path)
                          for path in (source.root / relative).rglob('*') if path.is_file())
    manifest = '\n'.join(f'{backup_module.sha256(path)}  {name}' for name, path in sorted(candidates)) + '\n'
    return manifest, hashlib.sha256(manifest.encode('utf-8')).hexdigest()


def request(base, method, path, payload=None, key=None):
    headers = {'Content-Type': 'application/json', 'X-Request-ID': 'm12-recovery-check'}
    if key:
        headers['Idempotency-Key'] = key
    data = None if payload is None else json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def pinned_compose(stack, images, output, label):
    override = output / f'{label}-images.json'
    private_json(override, {'services': {name: {'image': image['imageId']} for name, image in images.items()}})
    return stack.compose + ['--file', str(override)]


def capture_queries(stack):
    paths = ['/api/v1/invoices?size=100', '/api/v1/payments?size=100',
             '/api/v1/reconciliation?size=100', '/api/v1/audit-events?size=100']
    attempts = json.loads(stack.sql('SELECT json_agg(attempt_id ORDER BY attempt_id) FROM import_batches;'))
    paths.extend('/api/v1/import-batches/' + identity for identity in attempts)
    captured = {}
    for path in paths:
        status, raw = request(stack.base, 'GET', path)
        require(status == 200, f'Read-only query failed at {path}')
        captured[path] = {'status': status, 'body': raw.decode('utf-8'),
                          'bodySha256': hashlib.sha256(raw).hexdigest()}
    invoices = json.loads(captured[paths[0]]['body'])['items']
    payments = json.loads(captured[paths[1]]['body'])['items']
    expected = json.loads((ROOT / 'examples/expected-core.json').read_text(encoding='utf-8'))
    invoice_states = sorted([{field: row[field] for field in ('externalInvoiceId', 'status', 'remaining')}
                             for row in invoices], key=lambda row: row['externalInvoiceId'])
    golden_payments = sorted([{field: row[field] for field in ('externalPaymentId', 'status', 'remaining')}
                              for row in payments if row['externalPaymentId'] in ('PAY-001', 'PAY-002')],
                             key=lambda row: row['externalPaymentId'])
    require(invoice_states == expected['invoices'] and golden_payments == expected['payments'],
            'Golden invoice/payment query states changed')
    pending = json.loads(captured[paths[2]]['body'])['items']
    require(sorted((row['externalPaymentId'], row['remaining'], row['reason']) for row in pending) ==
            [('LOST-RESPONSE-M10', '2.50', 'NO_EXPLICIT_TARGET'),
             ('PARTIAL-M10', '2.00', 'NO_EXPLICIT_TARGET')], 'Extended pending query facts changed')
    return captured


def saved_replays(stack):
    rows = json.loads(stack.sql("SELECT json_agg(row_to_json(t) ORDER BY operation_type,key) "
        "FROM (SELECT operation_type,key,response_status,response_body FROM idempotency_records) t;"))
    require(len(rows) == 2, 'Expected the original two successful idempotency records')
    by_type = {row['operation_type']: row for row in rows}
    manual, automatic = by_type['MANUAL_ALLOCATION'], by_type['AUTO_ALLOCATION']
    require(manual['key'] == 'extended-demo:v1:manual' and manual['response_status'] == 201,
            'Expected the saved original M10 manual 201 response')
    ids = json.loads(stack.sql("SELECT json_build_object('payment',"
        "(SELECT id FROM payments WHERE external_payment_id='PAY-001'), 'autoPayment',"
        "(SELECT id FROM payments WHERE external_payment_id='PAY-002'), 'invoice101',"
        "(SELECT id FROM invoices WHERE external_invoice_id='INV-101'), 'invoice102',"
        "(SELECT id FROM invoices WHERE external_invoice_id='INV-102'));"))
    require(all(isinstance(value, int) and value > 0 for value in ids.values()), 'Synthetic identities are missing')
    require(automatic['key'] == f"auto:v1:{ids['autoPayment']}" and automatic['response_status'] == 201,
            'Expected the saved stable AUTO success result')
    manual['request'] = {'payment_id': ids['payment'], 'allocations': [
        {'invoice_id': ids['invoice102'], 'amount': '80.00'},
        {'invoice_id': ids['invoice101'], 'amount': '120.00'}]}
    automatic['request'] = {'payment_ids': [ids['autoPayment']]}
    return {'manual': manual, 'automatic': automatic}


def replay(stack, saved):
    manual = saved['manual']
    status, raw = request(stack.base, 'POST', '/api/v1/allocations', manual['request'], manual['key'])
    require(status == 201 and raw == manual['response_body'].encode('utf-8'),
            'Manual replay differs from the original persisted HTTP201 bytes')
    manual_status = status
    status, raw_auto = request(stack.base, 'POST', '/api/v1/reconciliation/runs', saved['automatic']['request'])
    require(status == 200, 'AUTO replay failed')
    automatic = json.loads(raw_auto)
    require((automatic['applied'], automatic['skipped'], automatic['review'], automatic['failed']) == (0, 1, 0, 0)
            and len(automatic['items']) == 1 and automatic['items'][0]['replayed'] is True,
            'AUTO replay was not one skipped/replayed success with no new effect')
    return {'manualStatus': manual_status, 'manualExactSavedResponseReplay': True,
            'manualResponseSha256': hashlib.sha256(raw).hexdigest(),
            'automaticApplied': 0, 'automaticSkipped': 1, 'automaticReplayed': True,
            'automaticRunResponseSha256': hashlib.sha256(raw_auto).hexdigest()}


def run(args):
    require(args.synthetic_extended_ledger, 'Explicit --synthetic-extended-ledger is required for source recreation')
    require(re.fullmatch('[0-9a-f]{40}', args.baseline_commit) is not None, 'Supply the full baseline commit SHA')
    require(re.fullmatch('[0-9a-f]{40}', args.runtime_baseline_commit) is not None,
            'Supply the full retained runtime baseline commit SHA')
    actual_head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True,
                                 capture_output=True, check=True).stdout.strip()
    require(actual_head == args.baseline_commit, 'Current repository HEAD differs from the selected recovery code baseline')
    source = Stack(args.project, args.env_file, args.source_root, args.image_tag)
    restore = Stack(args.restore_project, args.restore_env_file, args.source_root, args.image_tag)
    require_distinct(source, restore)
    restore.require_unused()
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', restore.port))
    output = args.output_dir.resolve()
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    os.chmod(output, 0o700)
    manifest, manifest_sha = verification_manifest(source)
    with (output / 'source-helper-manifest-before.txt').open('x', encoding='utf-8') as stream:
        os.chmod(stream.name, 0o600)
        stream.write(manifest)
    report = {'result': 'IN_PROGRESS', 'verificationScope': 'LOCAL synthetic M10 Compose persistence and backup recovery',
              'vpsStatus': 'BLOCKED: no supplied remote target or verified remote access',
              'sourceProject': source.project, 'restoreProject': restore.project,
              'sourceVolume': source.volume, 'restoreVolume': restore.volume,
              'baselineCommit': args.baseline_commit, 'runtimeBaselineCommit': args.runtime_baseline_commit,
              'sourceHelperManifestBeforeSha256': manifest_sha,
              'startedAtUtc': datetime.now(timezone.utc).isoformat(),
              'outputDirectory': str(output), 'sourceComposeSha256': backup_module.sha256(source.root / 'infra/compose.yaml'),
              'verifierSha256': backup_module.sha256(Path(__file__)),
              'fullSnapshotSqlSha256': backup_module.sha256(ROOT / 'scripts/snapshot-ledger-full.sql')}

    def save():
        temporary = output / 'report-next.json'
        private_json(temporary, report)
        temporary.replace(output / 'report.json')

    def phase(label):
        report['phase'] = label
        save()

    def checks(stack, label):
        for name in ('check-invariants.sql', 'check-extended.sql'):
            stack.sql((ROOT / 'scripts' / name).read_text(encoding='utf-8'), output / f'{label}-{name}.log')
        full = json.loads(stack.sql((ROOT / 'scripts/snapshot-ledger-full.sql').read_text(encoding='utf-8')))
        summary = json.loads(stack.sql((ROOT / 'scripts/snapshot-ledger.sql').read_text(encoding='utf-8')))
        private_json(output / f'{label}-full-tables.json', full)
        private_json(output / f'{label}-ledger-summary.json', summary)
        queries = capture_queries(stack)
        private_json(output / f'{label}-queries.json', queries)
        return {'fullTables': full, 'summary': summary, 'queries': queries}

    save()
    try:
        phase('capture-original-extended-ledger')
        images = {service: source.identity(service) for service in ('app', 'database')}
        report['sourceRuntimeBefore'] = images
        original = checks(source, 'source-before')
        saved = saved_replays(source)
        private_json(output / 'original-saved-replays.json', saved)
        report['savedResponseBaseline'] = 'Original M10 successful response_body TEXT persisted in idempotency_records; M10 proved exact HTTP retry'
        report['originalSavedResponseSha256'] = {name: hashlib.sha256(
            value['response_body'].encode('utf-8')).hexdigest() for name, value in saved.items()}
        source_pinned = pinned_compose(source, images, output, 'source')
        restore_pinned = pinned_compose(restore, images, output, 'restore')
        phase('recreate-both-source-containers-retain-volume')
        source.execute(source.compose + ['down'], log=output / 'source-down.log')
        source.execute(source_pinned + ['up', '--detach', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180'],
                       log=output / 'source-recreate.log')
        report['sourceRuntimeAfter'] = {service: source.identity(service) for service in images}
        for service, before in images.items():
            after = report['sourceRuntimeAfter'][service]
            require(before['id'] != after['id'], f'{service} was not actually recreated')
            require(before['imageId'] == after['imageId'], f'{service} image changed')
            require(before['volumes'] == after['volumes'], f'{service} volume changed')
        require(checks(source, 'source-after-recreate') == original, 'Source data/query bytes changed on recreation')
        report['sourceReplay'] = replay(source, saved)
        require(checks(source, 'source-after-replay') == original, 'Source replay added or changed persisted facts')
        phase('backup-custom-archive')
        report['backup'] = backup_module.backup(source, output / 'backup')
        require(checks(source, 'source-after-backup') == original, 'Source changed during backup')
        archive = output / 'backup/database.dump'
        require(backup_module.sha256(archive) == report['backup']['archiveSha256'], 'Backup archive SHA mismatch')
        phase('restore-into-new-isolated-empty-volume')
        restore.require_unused()  # Recheck immediately before creating anything.
        restore.execute(restore_pinned + ['up', '--detach', '--no-build', '--pull', 'never', '--wait',
                        '--wait-timeout', '180', 'database'], log=output / 'restore-new-database.log')
        target_database = restore.identity('database')
        require(target_database['imageId'] == images['database']['imageId'], 'Restoration database image changed')
        require(restore.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public';") == '0',
                'Restore target must have a completely empty new public schema')
        with archive.open('rb') as stream:
            result = subprocess.run(restore.compose + ['exec', '-T', 'database', 'pg_restore', '-U', restore.user,
                '-d', restore.database, '--exit-on-error', '--single-transaction', '--no-owner', '--no-acl'],
                cwd=restore.root, env=restore.env, stdin=stream, capture_output=True, timeout=300)
        with (output / 'pg-restore.log').open('xb') as stream:
            os.chmod(stream.name, 0o600)
            stream.write(result.stdout + result.stderr)
        require(result.returncode == 0, f'pg_restore failed with exit {result.returncode}; see pg-restore.log')
        # Before starting Java, require full equality with source (including every stored response and timestamp).
        restored_full = json.loads(restore.sql((ROOT / 'scripts/snapshot-ledger-full.sql').read_text(encoding='utf-8')))
        private_json(output / 'restore-before-app-full-tables.json', restored_full)
        require(restored_full == original['fullTables'], 'Restored SQL rows differ before app startup')
        restore.execute(restore_pinned + ['up', '--detach', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180'],
                        log=output / 'restore-start-app.log')
        report['restoreRuntime'] = {service: restore.identity(service) for service in images}
        require(report['restoreRuntime']['app']['imageId'] == images['app']['imageId'], 'Restored app image changed')
        phase('verify-restored-queries-and-original-replays')
        require(checks(restore, 'restore-before-replay') == original, 'Restored data/query bytes differ from original')
        require(saved_replays(restore) == saved, 'Restored original stored response text changed')
        report['restoreReplay'] = replay(restore, saved)
        require(checks(restore, 'restore-after-replay') == original, 'Restored replay added or changed persisted facts')
        require(checks(source, 'source-final') == original, 'Original source changed during recovery')
        after_manifest, after_manifest_sha = verification_manifest(source)
        with (output / 'source-helper-manifest-after.txt').open('x', encoding='utf-8') as stream:
            os.chmod(stream.name, 0o600)
            stream.write(after_manifest)
        report['sourceHelperManifestAfterSha256'] = after_manifest_sha
        require(after_manifest == manifest, 'Runtime source or recovery helper changed during verification')
        report.update(result='PASS', phase='complete', containersActuallyRecreated=True,
            sourceImagesAndVolumeRetained=True, restoredOnlyIntoNewEmptyVolume=True,
            allSixFullTableHashesEqual=True, exactOriginalManual201Replay=True,
            autoReplayCreatedNoFacts=True, countsAndResponseHashUnchanged=True,
            noNewImportsPerformed=True, sourceVolumeDeleted=False, restoreVolumeDeleted=False,
            finishedAtUtc=datetime.now(timezone.utc).isoformat(), originalLedgerSummary=original['summary'])
        save()
        print(json.dumps(report, indent=2))
    except Exception as error:
        report.update(result='FAIL', error=str(error), finishedAtUtc=datetime.now(timezone.utc).isoformat())
        save()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    backup_module.add_source_arguments(parser)
    parser.add_argument('--restore-project', required=True)
    parser.add_argument('--restore-env-file', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--baseline-commit', required=True)
    parser.add_argument('--runtime-baseline-commit', required=True)
    parser.add_argument('--synthetic-extended-ledger', action='store_true')
    run(parser.parse_args())


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f'Local recovery check failed: {error}', file=sys.stderr)
        sys.exit(1)
