# API reference

Business endpoints use `/api/v1`. POST requests require `Content-Type: application/json`. Swagger UI is available at `/swagger-ui/index.html`, and the generated OpenAPI document at `/v3/api-docs` on a running instance. The application has no authentication or authorization layer; the supplied Compose configuration exposes it on loopback for local demonstrations.

## Endpoints

| Method | Path | Input / result |
|---|---|---|
| POST | `/api/v1/imports/invoices` | `source`, `rows`; 201 import report. |
| POST | `/api/v1/imports/payments` | `source`, `rows`; 201 import report. |
| GET | `/api/v1/import-batches/{attemptId}` | UUID attempt ID; saved report or 404. |
| POST | `/api/v1/allocations` | `payment_id`, `allocations`; required `Idempotency-Key`; 201 allocation result. |
| POST | `/api/v1/reconciliation/runs` | `payment_ids`; 200 report with individual payment outcomes. |
| GET | `/api/v1/invoices` | Paged invoice balances and statuses. |
| GET | `/api/v1/payments` | Paged payment balances and statuses. |
| GET | `/api/v1/reconciliation` | Paged payments with remaining balance and AUTO review reasons. |
| GET | `/api/v1/audit-events` | Paged audit events. |
| GET | `/actuator/health` | Runtime health without detailed component information. |

## Input rules

| Field / boundary | Contract |
|---|---|
| POST body | At most 2 MiB, including requests without `Content-Length`. |
| Import rows | 1–1000 per request; every row source must match the envelope source. |
| Source / external identity | Strings, trimmed, case-sensitive, 1–100 Unicode code points after trimming. |
| Invoice source | Must equal the deployment-configured authoritative source; default `demo-invoices`. |
| Money | Positive decimal **strings**, at most `999999999999.99`, with no rounding. `"60"` and `"60.000"` normalize to `"60.00"`; `"0.011"`, JSON numbers, signs, and scientific notation are rejected. |
| Currency | Exactly `GBP`. |
| Dates | Valid `YYYY-MM-DD` calendar dates, years 0001–9999. |
| Payment target | Both target fields must contain valid identities, or both be absent/null. The target invoice may be imported later. |
| Manual targets | 1–20 distinct positive invoice IDs, each with a positive money amount. |
| `payment_ids` | 1–100 distinct positive integer IDs. |
| `Idempotency-Key` | Required for manual allocations; trimmed length 1–200 Unicode code points. |
| Query paging | `page` ≥ 0, `size` 1–100; defaults 0 / 50; ascending internal ID. |

Unknown JSON fields are rejected. Input keys use snake_case as shown below; response fields use camelCase, with the error correlation field `request_id` as an exception. Text containing NUL or invalid Unicode is rejected.

## Import examples

POST `/api/v1/imports/invoices`:

```json
{
  "source": "demo-invoices",
  "rows": [
    {
      "source": "demo-invoices",
      "external_invoice_id": "INV-101",
      "amount": "120.00",
      "currency": "GBP",
      "issued_on": "2026-10-01"
    }
  ]
}
```

POST `/api/v1/imports/payments`:

```json
{
  "source": "demo-payments",
  "rows": [
    {
      "source": "demo-payments",
      "external_payment_id": "PAY-001",
      "amount": "60.00",
      "currency": "GBP",
      "received_on": "2026-10-02",
      "raw_reference": "INV-101",
      "target_invoice_source": "demo-invoices",
      "target_external_invoice_id": "INV-101"
    }
  ]
}
```

`raw_reference` is optional and is preserved as business content. It does not create an automatic target. Omitted/null references are equivalent; empty and whitespace references remain distinct content.

The 201 report contains `attemptId`, `source`, `contentHash`, `outcome`, `total`, `inserted`, `duplicate`, `notImported`, `rowErrors`, `errorSummary`, `requestId`, and `createdAt`. A committed request has `total = inserted + duplicate` and `notImported = 0`. Reimporting the same source identities and normalized business content counts duplicates; changing existing content returns 409. Each retry creates a new import attempt.

## Manual allocation and replay

Get actual internal IDs from the invoice/payment queries before sending a command; the numeric IDs below are illustrative. POST `/api/v1/allocations` with `Idempotency-Key: sample-manual-001`:

```json
{
  "payment_id": 1,
  "allocations": [
    {"invoice_id": 1, "amount": "60.00"}
  ]
}
```

The 201 result contains `operationId`, `allocationIds`, the resulting `payment`, and affected `invoices`. Payment and invoice views expose original amount, allocated amount, remaining amount, and status as derived values.

Resending the same key and normalized command returns the original 201 status and response text, with `Idempotency-Replayed: true`. Reusing that key for different content returns 409. Allocation targets are canonicalized by invoice ID. Exceeding either remaining balance rejects the complete operation.

## Automatic reconciliation

As an alternative to manual allocation, POST `/api/v1/reconciliation/runs` using actual payment IDs:

```json
{"payment_ids": [1, 2]}
```

The report contains `runId`, `total`, `applied`, `skipped`, `review`, `failed`, and `items`. Each item has `paymentId`, `status`, `reason`, `replayed`, `operationId`, `allocationIds`, `code`, `message`, and `retryable`.

| Item status | Meaning |
|---|---|
| `APPLIED` | Full payment allocated to its explicit eligible target. |
| `SKIPPED` | Payment already fully allocated, or a prior successful AUTO result replayed. |
| `REVIEW` | No allocation; reason is `NO_EXPLICIT_TARGET`, `NO_TARGET`, `MANUAL_REQUIRED`, `TARGET_ALREADY_PAID`, or `OVERPAYMENT`. |
| `FAILED` | Operation failed; inspect `code`, `message`, and `retryable`. |

AUTO does not split payments, infer targets from references, or top up a partially allocated payment. Each payment commits independently. HTTP 200 means a run report was returned, so inspect every item for failures. Payments requiring review have no successful AUTO key and can be rerun after a later target import.

## Queries and errors

Paged responses have `items`, `page`, and `size`. Invoice statuses are `OPEN`, `PARTIALLY_PAID`, and `PAID`; payment statuses are `UNALLOCATED`, `PARTIALLY_ALLOCATED`, and `FULLY_ALLOCATED`. `/reconciliation` lists remaining payments rather than unpaid invoices.

An optional `X-Request-ID` matching `[A-Za-z0-9_.:-]{1,100}` is accepted; otherwise a UUID is generated. The response returns the correlation header. Ordinary errors use this shape:

```json
{
  "code": "BUSINESS_CONFLICT",
  "message": "Allocation exceeds payment remaining amount",
  "request_id": "sample-request-001"
}
```

Principal statuses are 400 for malformed structure, 404 for missing resources, 409 for business conflicts, 413 for oversized bodies, 422 for invalid values, and 503 for retryable database errors. Other internal failures return 500.

Import failure envelopes additionally contain `attemptId`, `rowErrors`, `report`, `attemptSaved`, and `outcomeUnknown`. Row numbers are one-based; row 0 identifies a batch-level issue. Check these fields before deciding whether to retry. A failed report save or a transport interruption can leave the outcome uncertain; a failure count alone is not proof that a database commit never occurred.

Source definitions: [controllers and query views](../backend/src/main/java/dev/reconcilekit/query/QueryController.java), [import parser](../backend/src/main/java/dev/reconcilekit/importbatch/ImportParser.java), [allocation command](../backend/src/main/java/dev/reconcilekit/allocation/AllocationCommand.java), and [run command](../backend/src/main/java/dev/reconcilekit/reconciliation/ReconciliationRunCommand.java). See [architecture](architecture.md) for transaction boundaries.
