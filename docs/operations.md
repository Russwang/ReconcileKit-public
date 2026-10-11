# Local operation and recovery

These procedures target isolated synthetic ledgers. Run the core demonstration in the [README](../README.md) first. Docker Engine 28+ with Compose v2 supplies the Java and PostgreSQL runtime. The extended Python helpers require CPython 3.13.16; backup/recovery requires a local Unix Docker context.

## Select the correct stack

Each stack has a distinct `rk-` project name, private environment file, application port and named database volume. Keep credentials in ignored `.runtime/` files with mode `0600`. Never point the backend test suite at a demo or retained database: tests truncate their synthetic tables.

Read health and container state:

```bash
curl --noproxy '*' --fail http://127.0.0.1:18088/actuator/health
scripts/with-docker.sh docker compose --project-name rk-public-demo \
  --env-file .runtime/demo.env --file infra/compose.yaml ps
scripts/check-deployment.sh --project rk-public-demo \
  --env-file "$PWD/.runtime/demo.env"
```

The deployment probe checks the chosen running stack: app UID/GID 10001, read-only root filesystem, zero Linux capabilities, `no-new-privileges`, loopback application binding and no published database port. It is a local probe; it does not establish a remote server boundary.

## Independently check the ledger

```bash
scripts/with-docker.sh docker compose --project-name rk-public-demo \
  --env-file .runtime/demo.env --file infra/compose.yaml exec -T database \
  psql -U reconcilekit -d reconcilekit -v ON_ERROR_STOP=1 \
  < scripts/check-invariants.sql
```

The SQL checks payment and invoice allocation bounds independently of application responses.

## Full core persistence acceptance

Generate another private environment file as shown in the README, using `.runtime/core.env` and a free port. Select an unused project:

```bash
scripts/core-acceptance.sh --project rk-public-core \
  --env-file "$PWD/.runtime/core.env" \
  --baseline-commit "$(git rev-parse HEAD)"
```

This builds a fresh stack, proves all six business tables are empty, runs the core demo, checks SQL invariants, then removes and recreates containers while retaining the database volume. Original successful response bodies, counts and saved hashes must survive. Generated reports stay in `.runtime/core-acceptance/`; the chosen stack remains available for inspection.

## Extended integration

Create a new `.runtime/extended.env` with `APP_PORT=18090` and `IMAGE_TAG=public-extended`, then start an unused stack:

```bash
scripts/start.sh rk-public-extended .runtime/extended.env
scripts/python.sh -I scripts/extended-demo.py \
  --base-url http://127.0.0.1:18090
scripts/with-docker.sh docker compose --project-name rk-public-extended \
  --env-file .runtime/extended.env --file infra/compose.yaml exec -T database \
  psql -U reconcilekit -d reconcilekit -v ON_ERROR_STOP=1 \
  < scripts/check-invariants.sql
scripts/with-docker.sh docker compose --project-name rk-public-extended \
  --env-file .runtime/extended.env --file infra/compose.yaml exec -T database \
  psql -U reconcilekit -d reconcilekit -v ON_ERROR_STOP=1 \
  < scripts/check-extended.sql
```

The extended scenario exercises CSV duplicates, exact AUTO, manual replay, whole-file validation before HTTP, a later batch conflict, and an actual server commit followed by client response loss and an identity-safe retry. Deliberate failure scenarios leave additional synthetic records, including two pending payments of GBP 2.00 and 2.50.

## Container recreation and backup restoration

After extended integration, generate `.runtime/restore.env` for port 18091 with a fresh random password and the same `IMAGE_TAG=public-extended`. Use a distinct, unused restore project and a new report directory:

```bash
scripts/python.sh -I scripts/recovery-check.py \
  --project rk-public-extended --env-file .runtime/extended.env \
  --source-root "$PWD" --image-tag public-extended \
  --restore-project rk-public-restore --restore-env-file .runtime/restore.env \
  --output-dir .runtime/public-recovery \
  --baseline-commit "$(git rev-parse HEAD)" \
  --runtime-baseline-commit "$(git rev-parse HEAD)" \
  --synthetic-extended-ledger
```

The helper checks the selected runtime, captures every column of all six business tables, recreates source containers without deleting the source volume, and restores a custom PostgreSQL backup into a new empty volume. It checks table hashes, saved query bytes, original manual response replay and AUTO replay without new facts. Backups and raw reports remain ignored local files.

Recovered containers use pinned image IDs. Supply the corresponding generated override when checking them:

```bash
scripts/check-deployment.sh --project rk-public-extended \
  --env-file "$PWD/.runtime/extended.env" \
  --compose-override "$PWD/.runtime/public-recovery/source-images.json"
scripts/check-deployment.sh --project rk-public-restore \
  --env-file "$PWD/.runtime/restore.env" \
  --compose-override "$PWD/.runtime/public-recovery/restore-images.json"
```

## Stop a stack

`docker compose down` retains its named database volume. Run it with the same explicit project, environment file and Compose configuration used to start that stack. Adding `--volumes` deletes the ledger; persistence/recovery checks deliberately retain their volumes.

Public deployment would require authentication and authorization, access controls, a reviewed operating boundary, and a separately verified recovery procedure. The repository establishes local demonstration behavior only.
