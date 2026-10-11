# Verification

This public snapshot packages the 0.1.1 ledger and client implementation with a concise reviewer guide. It contains synthetic inputs and an archived demonstration video. Local environment files, database backups, personal learning/CV material and original development logs are excluded.

## Public snapshot checks — 11 October 2026

The exported source was tested before publication:

| Check | Result | What it exercises |
| --- | --- | --- |
| Backend `clean verify` | 138 passed; 0 failures/errors/skips | Real PostgreSQL constraints, allocation bounds, lock contention, rollback, HTTP contracts and saved-response replay |
| CSV client | 134 passed | Whole-file validation, batching, HTTP failures, partial/unknown outcomes and private retry traces |
| Deployment guards | 11 passed | Selected-stack checks and rejection of unsafe/mismatched configuration |
| Recovery guards | 5 passed | Isolated restore target and private artifact protections |
| Recording target guards | 3 passed | Bind recorded HTTP and SQL checks to the same selected stack |
| Source/document review | Completed | Implemented claims, command arguments, relative links, public export boundaries and omitted private artifacts |

Total automated tests and guards: **291**. Backend tests used a new disposable PostgreSQL Testcontainer with `DB_URL` unset. A local nested runtime symlink initially exceeded the Unix socket path limit; the final successful run used its canonical shorter Docker socket path. This was a test-environment correction, without business source changes.

The checks run on this public snapshot are distinct from historical development evidence. Full container integration and backup recovery are also defined in [the workflow](../.github/workflows/ci.yml); see this repository's [Actions](https://github.com/Russwang/ReconcileKit-public/actions) for the result at a particular public commit. A workflow definition alone does not establish a successful run.

## Scenarios to inspect

- [AllocationTransactionsTest](../backend/src/test/java/dev/reconcilekit/AllocationTransactionsTest.java): competing payment/invoice allocations, fresh sums after locks, actual lock timeout, and rollback.
- [IdempotencyAuditTest](../backend/src/test/java/dev/reconcilekit/IdempotencyAuditTest.java): same-key contention, exact successful-response replay, changed-content conflict, audit atomicity and connection failure.
- [ImportTransactionsTest](../backend/src/test/java/dev/reconcilekit/ImportTransactionsTest.java): source-identity conflicts, late failure rollback, concurrent duplicates and failed-attempt reporting.
- [ReconciliationTransactionsTest](../backend/src/test/java/dev/reconcilekit/ReconciliationTransactionsTest.java): manual/AUTO and AUTO/AUTO races through the shared allocator.
- [CSV client tests](../importer/tests/test_client.py): committed-prefix preservation, no automatic retry, response validation and unknown transport outcomes.
- [Extended demo](../scripts/extended-demo.py): real CSV → Java → PostgreSQL, server commit followed by client response loss, then a safe identity/content retry.
- [SQL invariants](../scripts/check-invariants.sql): independent invoice and payment balance bounds.
- [Recovery checker](../scripts/recovery-check.py): source container recreation, restoration into a new empty volume, six-table hashes and saved response replay.

## Scope of the results

These checks demonstrate the tested scenarios with synthetic data. They do not establish commercial performance, regulatory compliance, tamper-evident auditing, protection against arbitrary external SQL, verified public-server deployment, or correctness under every possible fault. Client response loss is tested; a lost PostgreSQL commit acknowledgment is a separate fault scenario.

Source and reachable original history were screened for common token/private-key patterns and credential files. No genuine secret or customer data was detected within that scope. Publication uses a new repository history and a reviewed file allowlist; local credentials, dumps and original machine-specific logs are excluded. Such screening does not prove the absence of every possible form of sensitive information.

For reproduction commands and runtime requirements, see the [README](../README.md) and [operations](operations.md).
