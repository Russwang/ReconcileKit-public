import pytest
from reconcilekit_importer.validation import (HEADERS, ValidationError, calendar_date,
                                               identity, money, read_csv, storage_text)


@pytest.mark.parametrize("value, expected", [("60", "60.00"), ("60.0", "60.00"), ("60.000", "60.00"),
    ("00000.0100000000", "0.01"), ("999999999999.99", "999999999999.99")])
def test_exact_money_is_string_normalized_without_rounding(value, expected):
    assert money(value) == expected


@pytest.mark.parametrize("value", ["0", "-1", "+1", "0.001", "1000000000000", "999999999999.991",
    "1e2", "NaN", "Infinity", "1.", ".1", "£1", " 60", "60 ", "١", "", "1,000"])
def test_invalid_money_rejects_instead_of_rounding(value):
    with pytest.raises(ValidationError):
        money(value)


@pytest.mark.parametrize("value", ["0001-01-01", "1582-10-10", "2000-02-29", "9999-12-31"])
def test_dates_round_trip_proleptic_calendar(value):
    assert calendar_date(value, "issued_on") == value


@pytest.mark.parametrize("value", ["0000-01-01", "2026-02-29", "2026-1-01", "+2026-01-01", "２０２６-01-01", "2026-13-01"])
def test_invalid_dates_rejected(value):
    with pytest.raises(ValidationError):
        calendar_date(value, "issued_on")


def test_java_compatible_stripping_preserves_nonbreaking_space_and_codepoints():
    assert identity("\t\u2000 INV \u3000", "external_id") == "INV"
    assert identity("\u00a0INV\u00a0", "external_id") == "\u00a0INV\u00a0"
    assert identity("\u2007INV\u202f", "external_id") == "\u2007INV\u202f"
    assert identity("😀" * 100, "external_id") == "😀" * 100
    with pytest.raises(ValidationError):
        identity("😀" * 101, "external_id")
    with pytest.raises(ValidationError):
        identity(" \t\u3000", "source")


@pytest.mark.parametrize("value", ["nul\0", "\ud800", "\udfff"])
def test_postgres_unsupported_text_is_rejected(value):
    with pytest.raises(ValidationError):
        storage_text(value, "raw_reference")


@pytest.mark.parametrize("header", [
    ["source", "source", "amount", "currency", "issued_on"],
    ["source", "external_invoice_id", "amount", "currency"],
    [*HEADERS["invoices"], "extra"], list(reversed(HEADERS["invoices"]))])
def test_missing_duplicate_unknown_and_reordered_headers_rejected(csv_file, header):
    with pytest.raises(ValidationError):
        read_csv(csv_file(header=header), "invoices")


@pytest.mark.parametrize("raw", [b"\xff", b"\xef\xbb\xbfsource,external_invoice_id,amount,currency,issued_on\n",
    b"source,external_invoice_id,amount,currency,issued_on\ns,inv,1,GBP,2026-10-01,extra\n",
    b"source,external_invoice_id,amount,currency,issued_on\ns,inv,1,GBP\n",
    b"source,external_invoice_id,amount,currency,issued_on\n\n",
    b"source,external_invoice_id,amount,currency,issued_on\n\"unclosed,inv,1,GBP,2026-10-01\n",
    b"source,external_invoice_id,amount,currency,issued_on\n"])
def test_encoding_csv_structure_blank_rows_and_empty_file_rejected(csv_file, raw):
    with pytest.raises(ValidationError):
        read_csv(csv_file(raw=raw), "invoices")


def test_duplicate_identity_equal_normalized_contents_allowed_and_source_strip(csv_file):
    file = csv_file(rows=[[" demo-invoices ", "INV-1", "60", "GBP", "2026-10-01"],
                          ["demo-invoices", " INV-1\t", "60.000", "GBP", "2026-10-01"]])
    validated = read_csv(file, "invoices")
    assert validated.rows[0].values == validated.rows[1].values
    assert len(validated.rows) == 2


def test_conflicting_identity_late_in_file_is_rejected(csv_file):
    file = csv_file(rows=[["demo-invoices", "INV-1", "60", "GBP", "2026-10-01"],
                          ["demo-invoices", "INV-1", "61", "GBP", "2026-10-01"]])
    with pytest.raises(ValidationError, match="different business content") as error:
        read_csv(file, "invoices")
    assert error.value.line == 3


def test_empty_and_whitespace_raw_reference_are_distinct_business_content(csv_file):
    file = csv_file("payments", rows=[
        ["demo-payments", "P1", "60", "GBP", "2026-10-02", "", "", ""],
        ["demo-payments", "P1", "60.000", "GBP", "2026-10-02", " ", "", ""]])
    with pytest.raises(ValidationError) as error:
        read_csv(file, "payments")
    assert error.value.code == "CONFLICTING_IDENTITY" and error.value.line == 3


def test_mixed_source_is_rejected(csv_file):
    file = csv_file(rows=[["demo-invoices", "INV-1", "60", "GBP", "2026-10-01"],
                          ["other", "INV-2", "60", "GBP", "2026-10-01"]])
    with pytest.raises(ValidationError) as error:
        read_csv(file, "invoices")
    assert error.value.code == "MIXED_SOURCE"


def test_raw_reference_blank_and_multiline_are_preserved_with_physical_line_mapping(csv_file):
    file = csv_file("payments", rows=[
        ["demo-payments", "P1", "60", "GBP", "2026-10-02", "", "", ""],
        ["demo-payments", "P2", "60", "GBP", "2026-10-02", " line1\r\nline2 ", " demo-invoices ", " INV-1 "]])
    validated = read_csv(file, "payments")
    assert validated.rows[0].values["raw_reference"] == ""
    assert validated.rows[0].values["target_invoice_source"] is None
    assert validated.rows[1].values["raw_reference"] == " line1\r\nline2 "
    assert validated.rows[1].values["target_external_invoice_id"] == "INV-1"
    assert validated.rows[1].trace(2) == {"batchRow": 2, "csvRecord": 2, "csvLineStart": 3, "csvLineEnd": 4}


@pytest.mark.parametrize("source,external", [("", "INV-1"), ("demo-invoices", ""), (" ", " "), ("demo-invoices", "\t")])
def test_partial_or_whitespace_targets_rejected(csv_file, source, external):
    file = csv_file("payments", rows=[["demo-payments", "P1", "60", "GBP", "2026-10-02", "", source, external]])
    with pytest.raises(ValidationError):
        read_csv(file, "payments")


@pytest.mark.parametrize("field,value", [("currency", "gbp"), ("source", ""), ("external_invoice_id", "x" * 101), ("external_invoice_id", "bad\0")])
def test_semantic_csv_validation_is_not_deferred_to_server(csv_file, field, value):
    cells = ["demo-invoices", "INV-1", "60", "GBP", "2026-10-01"]
    cells[HEADERS["invoices"].index(field)] = value
    with pytest.raises(ValidationError):
        read_csv(csv_file(rows=[cells]), "invoices")
