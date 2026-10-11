"""Validate an entire CSV before any HTTP request or batch trace is prepared."""
import csv
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, Inexact, InvalidOperation, localcontext
import hashlib
import io
from pathlib import Path
import re

BODY_LIMIT = 2 * 1024 * 1024
HEADERS = {
    "invoices": ("source", "external_invoice_id", "amount", "currency", "issued_on"),
    "payments": ("source", "external_payment_id", "amount", "currency", "received_on",
                 "raw_reference", "target_invoice_source", "target_external_invoice_id"),
}
# Java 21 Character.isWhitespace, used by String.strip(). Nonbreaking spaces
# U+00A0/U+2007/U+202F are intentionally preserved rather than Python-stripped.
JAVA_WHITESPACE = frozenset(chr(n) for n in (
    *range(0x09, 0x0E), *range(0x1C, 0x21), 0x1680,
    *range(0x2000, 0x2007), *range(0x2008, 0x200B),
    0x2028, 0x2029, 0x205F, 0x3000,
))


class ValidationError(ValueError):
    def __init__(self, code: str, message: str, *, line: int | None = None, field: str | None = None):
        super().__init__(message)
        self.code, self.message, self.line, self.field = code, message, line, field

    def report(self):
        return {"code": self.code, "message": self.message, "csvLine": self.line, "field": self.field}


def storage_text(value: str, field: str) -> str:
    if "\x00" in value or any(0xD800 <= ord(c) <= 0xDFFF for c in value):
        raise ValidationError("INVALID_TEXT", "Text contains NUL or invalid Unicode", field=field)
    return value


def identity(value: str, field: str) -> str:
    storage_text(value, field)
    first, last = 0, len(value)
    while first < last and value[first] in JAVA_WHITESPACE:
        first += 1
    while last > first and value[last - 1] in JAVA_WHITESPACE:
        last -= 1
    normalized = value[first:last]
    if not 1 <= len(normalized) <= 100:
        raise ValidationError("INVALID_IDENTITY", "Identity must contain 1–100 characters after Java-compatible stripping", field=field)
    return normalized


def money(value: str) -> str:
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", value):
        raise ValidationError("INVALID_AMOUNT", "Amount must be a plain decimal string", field="amount")
    # Reject oversized integer parts before constructing a very large Decimal.
    if len(value.partition(".")[0].lstrip("0")) > 12:
        raise ValidationError("INVALID_AMOUNT", "Amount outside supported range", field="amount")
    try:
        with localcontext() as context:
            context.prec = max(16, len(value) + 2)
            context.traps[Inexact] = True
            amount = Decimal(value).quantize(Decimal("0.01"))
            if not Decimal("0") < amount <= Decimal("999999999999.99"):
                raise ValidationError("INVALID_AMOUNT", "Amount outside supported range", field="amount")
            return format(amount, ".2f")
    except (Inexact, InvalidOperation):
        raise ValidationError("INVALID_AMOUNT", "Amount must contain exact pennies without rounding", field="amount") from None


def calendar_date(value: str, field: str) -> str:
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value):
        raise ValidationError("INVALID_DATE", "Date must be YYYY-MM-DD with year 0001–9999", field=field)
    try:
        return date.fromisoformat(value).isoformat()
    except ValueError:
        raise ValidationError("INVALID_DATE", "Date must be a valid calendar date with year 0001–9999", field=field) from None


@dataclass(frozen=True)
class CsvRow:
    record: int
    line_start: int
    line_end: int
    values: dict

    def trace(self, batch_row: int) -> dict:
        return {"batchRow": batch_row, "csvRecord": self.record,
                "csvLineStart": self.line_start, "csvLineEnd": self.line_end}


@dataclass(frozen=True)
class ValidatedFile:
    kind: str
    source: str
    rows: tuple[CsvRow, ...]
    sha256: str


def normalize_row(kind: str, values: dict) -> dict:
    for field, value in values.items():
        storage_text(value, field)
    external = "external_invoice_id" if kind == "invoices" else "external_payment_id"
    date_field = "issued_on" if kind == "invoices" else "received_on"
    row = {"source": identity(values["source"], "source"),
           external: identity(values[external], external), "amount": money(values["amount"]),
           "currency": values["currency"], date_field: calendar_date(values[date_field], date_field)}
    if row["currency"] != "GBP":
        raise ValidationError("INVALID_CURRENCY", "Only GBP is supported", field="currency")
    if kind == "payments":
        # Empty CSV raw reference is a real empty string, not a nullable/missing value.
        row["raw_reference"] = values["raw_reference"]
        source, external_id = values["target_invoice_source"], values["target_external_invoice_id"]
        if source == "" and external_id == "":
            row["target_invoice_source"] = row["target_external_invoice_id"] = None
        elif source == "" or external_id == "":
            raise ValidationError("INVALID_TARGET", "Target invoice fields must both be empty or both contain identities", field="target_invoice_source")
        else:
            row["target_invoice_source"] = identity(source, "target_invoice_source")
            row["target_external_invoice_id"] = identity(external_id, "target_external_invoice_id")
    return row


def read_csv(path: Path, kind: str) -> ValidatedFile:
    if kind not in HEADERS:
        raise ValidationError("INVALID_KIND", "Import kind must be invoices or payments")
    try:
        original = path.read_bytes()
        text = original.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        raise ValidationError("INVALID_UTF8", "CSV must use strict UTF-8 without replacement") from None
    except OSError:
        raise ValidationError("FILE_UNREADABLE", "CSV file could not be read") from None
    rows, seen, source = [], {}, None
    previous_limit = csv.field_size_limit(BODY_LIMIT + 1)
    reader = csv.reader(io.StringIO(text, newline=""), strict=True)
    try:
        header = next(reader, None)
        if header is None:
            raise ValidationError("INVALID_HEADERS", "CSV must contain the fixed template header", line=1)
        if len(header) != len(set(header)):
            raise ValidationError("DUPLICATE_HEADERS", "CSV contains duplicate header names", line=1)
        if tuple(header) != HEADERS[kind]:
            raise ValidationError("INVALID_HEADERS", "CSV headers must exactly match the selected template, including order", line=1)
        previous_line = reader.line_num
        for record, cells in enumerate(reader, 1):
            start, end = previous_line + 1, reader.line_num
            previous_line = end
            if len(cells) != len(header):
                raise ValidationError("INVALID_FIELD_COUNT", "CSV row has the wrong number of fields", line=start)
            try:
                values = normalize_row(kind, dict(zip(header, cells, strict=True)))
                if source is None:
                    source = values["source"]
                if values["source"] != source:
                    raise ValidationError("MIXED_SOURCE", "Every row in a file must have the same normalized source", field="source")
                external = "external_invoice_id" if kind == "invoices" else "external_payment_id"
                prior = seen.setdefault(values[external], values)
                if prior != values:
                    raise ValidationError("CONFLICTING_IDENTITY", "Same source identity has different business content within the file", field=external)
            except ValidationError as error:
                error.line = start
                raise
            rows.append(CsvRow(record, start, end, values))
        if not rows:
            raise ValidationError("EMPTY_FILE", "CSV must contain at least one data row", line=reader.line_num + 1)
    except csv.Error:
        raise ValidationError("INVALID_CSV", "CSV quoting or field size is invalid", line=max(1, reader.line_num)) from None
    finally:
        csv.field_size_limit(previous_limit)
    return ValidatedFile(kind, source, tuple(rows), hashlib.sha256(original).hexdigest())
