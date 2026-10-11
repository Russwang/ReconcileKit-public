import json
import pytest
from reconcilekit_importer.batching import make_batches
from reconcilekit_importer.validation import BODY_LIMIT, ValidationError, read_csv


def test_1001_rows_split_and_preserve_order_original_metadata(csv_file):
    file = csv_file(rows=[["demo-invoices", f"I{i}", "1", "GBP", "2026-10-01"] for i in range(1001)])
    batches = make_batches(read_csv(file, "invoices"))
    assert [len(batch.rows) for batch in batches] == [1000, 1]
    assert json.loads(batches[1].payload)["rows"][0]["external_invoice_id"] == "I1000"
    assert batches[1].rows[0].line_start == 1002
    assert all(len(batch.payload) <= BODY_LIMIT for batch in batches)


def test_utf8_actual_bytes_split_before_2mib_even_under_1000_rows(csv_file):
    raw = "😀" * 260_000
    file = csv_file("payments", rows=[["demo-payments", f"P{i}", "1", "GBP", "2026-10-01", raw, "", ""] for i in range(3)])
    batches = make_batches(read_csv(file, "payments"))
    assert [len(batch.rows) for batch in batches] == [2, 1]
    assert len(batches[0].payload) <= BODY_LIMIT
    assert len(batches[0].payload) > len(batches[0].payload.decode()) * 3


def test_single_valid_csv_row_too_large_for_json_rejected_before_http(csv_file):
    file = csv_file("payments", rows=[["demo-payments", "P1", "1", "GBP", "2026-10-01", "😀" * 524_288, "", ""]])
    with pytest.raises(ValidationError) as error:
        make_batches(read_csv(file, "payments"))
    assert error.value.code == "ROW_TOO_LARGE"


@pytest.mark.parametrize("size", [0, 1001, -1, True, 1.0])
def test_batch_size_is_bounded_integer(csv_file, size):
    with pytest.raises(ValidationError):
        make_batches(read_csv(csv_file(), "invoices"), size)
