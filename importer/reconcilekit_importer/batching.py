"""Size the actual UTF-8 payload, not characters or an estimated row size."""
from dataclasses import dataclass
import hashlib
import json
from .validation import BODY_LIMIT, CsvRow, ValidatedFile, ValidationError


def encode_json(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


@dataclass(frozen=True)
class Batch:
    number: int
    rows: tuple[CsvRow, ...]
    payload: bytes

    @property
    def sha256(self):
        return hashlib.sha256(self.payload).hexdigest()


def make_batches(validated: ValidatedFile, batch_size: int = 1000) -> tuple[Batch, ...]:
    if type(batch_size) is not int or not 1 <= batch_size <= 1000:
        raise ValidationError("INVALID_BATCH_SIZE", "Batch size must be an integer from 1 to 1000")
    prefix = b'{"source":' + encode_json(validated.source) + b',"rows":['
    suffix = b']}'
    overhead, current_bytes = len(prefix) + len(suffix), len(prefix) + len(suffix)
    batches, current_rows, current_payloads = [], [], []
    for row in validated.rows:
        payload = encode_json(row.values)
        if overhead + len(payload) > BODY_LIMIT:
            raise ValidationError("ROW_TOO_LARGE", "One normalized row exceeds the 2 MiB request body limit", line=row.line_start)
        added = len(payload) + (1 if current_rows else 0)
        if current_rows and (len(current_rows) == batch_size or current_bytes + added > BODY_LIMIT):
            batches.append(Batch(len(batches) + 1, tuple(current_rows), prefix + b','.join(current_payloads) + suffix))
            current_rows, current_payloads, current_bytes = [], [], overhead
        current_rows.append(row)
        current_payloads.append(payload)
        current_bytes += len(payload) + (1 if len(current_rows) > 1 else 0)
    if current_rows:
        batches.append(Batch(len(batches) + 1, tuple(current_rows), prefix + b','.join(current_payloads) + suffix))
    return tuple(batches)
