"""One HTTP request, no redirect or retry; verify reports before counting rows."""
from dataclasses import dataclass
from datetime import datetime
import ipaddress
import http.client
import json
import math
import re
import urllib.error
import urllib.parse
import urllib.request
from uuid import UUID
from .validation import ValidationError

RESPONSE_LIMIT = 4 * 1024 * 1024
REPORT_FIELDS = frozenset(("attemptId", "source", "contentHash", "outcome", "total", "inserted",
                          "duplicate", "notImported", "rowErrors", "errorSummary", "requestId", "createdAt"))


class ProtocolError(ValueError):
    pass


def base_url(value: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port  # Explicitly validate malformed and out-of-range ports.
        host = parsed.hostname
        if (parsed.scheme not in ("http", "https") or not host or parsed.username is not None
                or parsed.password is not None or parsed.path not in ("", "/")
                or parsed.query or parsed.fragment or any(ord(c) <= 32 for c in value)):
            raise ValueError()
        if ":" in host:
            ipaddress.IPv6Address(host)
        elif not re.fullmatch(r"[A-Za-z0-9.-]+", host):
            raise ValueError()
        if port is not None and not 1 <= port <= 65535:
            raise ValueError()
        return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))
    except ValueError:
        raise ValidationError("INVALID_BASE_URL", "Base URL must be an HTTP(S) origin without credentials, path, query or fragment") from None


def timeout_value(value: float) -> float:
    if not math.isfinite(value) or not 0.1 <= value <= 120:
        raise ValidationError("INVALID_TIMEOUT", "Timeout must be finite and between 0.1 and 120 seconds")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, newurl):
        return None


@dataclass(frozen=True)
class Reply:
    status: int | None
    headers: dict
    body: bytes
    transport_error: str | None = None


class HttpTransport:
    def __init__(self, origin: str, timeout: float):
        self.origin, self.timeout = base_url(origin), timeout_value(timeout)
        # Explicit connections: ambient proxy settings cannot send local CSV to a proxy.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def post(self, kind: str, payload: bytes, request_id: str) -> Reply:
        request = urllib.request.Request(self.origin + "/api/v1/imports/" + kind, data=payload,
            method="POST", headers={"Content-Type": "application/json", "Accept": "application/json",
                                    "X-Request-ID": request_id})
        try:
            try:
                response = self.opener.open(request, timeout=self.timeout)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                body = response.read(RESPONSE_LIMIT + 1)
                if len(body) > RESPONSE_LIMIT:
                    return Reply(response.code, {}, b"", "RESPONSE_TOO_LARGE")
                return Reply(response.code, {k.lower(): v for k, v in response.headers.items()}, body)
        except (OSError, urllib.error.URLError, ValueError, http.client.HTTPException):
            # Do not print exception strings: URLs, headers or input could appear there.
            return Reply(None, {}, b"", "NETWORK_ERROR")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProtocolError("Duplicate JSON response field")
        result[key] = value
    return result


def _invalid_constant(value):
    raise ProtocolError("Non-finite JSON response value")


def _uuid(value):
    if not isinstance(value, str):
        raise ProtocolError("Invalid attempt ID")
    try:
        if str(UUID(value)) != value:
            raise ValueError()
    except ValueError:
        raise ProtocolError("Invalid attempt ID") from None
    return value


def _count(value):
    if type(value) is not int or value < 0:
        raise ProtocolError("Invalid count")
    return value


def _code(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,99}", value):
        raise ProtocolError("Invalid error code")
    return value


def _row_errors(value, total):
    if not isinstance(value, list):
        raise ProtocolError("Invalid row errors")
    for item in value:
        if (not isinstance(item, dict) or set(item) != {"row", "code", "message"}
                or _count(item["row"]) > total or not isinstance(item["message"], str)):
            raise ProtocolError("Invalid row error")
        _code(item["code"])
    # Messages are deliberately omitted: a remote service could echo private CSV.
    return [{"row": item["row"], "code": item["code"]} for item in value]


def _report(value, source, total, request_id, saved):
    if not isinstance(value, dict) or set(value) != REPORT_FIELDS:
        raise ProtocolError("Invalid import report fields")
    _uuid(value["attemptId"])
    if value["source"] != source or value["requestId"] != request_id or _count(value["total"]) != total:
        raise ProtocolError("Import report correlation mismatch")
    if not isinstance(value["contentHash"], str) or not re.fullmatch(r"[0-9a-f]{64}", value["contentHash"]):
        raise ProtocolError("Invalid content hash")
    counts = [_count(value[key]) for key in ("inserted", "duplicate", "notImported")]
    if value["outcome"] == "COMMITTED":
        if counts[0] + counts[1] != total or counts[2] != 0 or value["rowErrors"] != []:
            raise ProtocolError("Inconsistent committed counts")
    elif value["outcome"] in ("REJECTED", "FAILED"):
        if counts != [0, 0, total]:
            raise ProtocolError("Inconsistent failure counts")
    else:
        raise ProtocolError("Invalid report outcome")
    row_errors = _row_errors(value["rowErrors"], total)
    if value["errorSummary"] is not None and not isinstance(value["errorSummary"], str):
        raise ProtocolError("Invalid error summary")
    if saved:
        try:
            if not isinstance(value["createdAt"], str) or datetime.fromisoformat(value["createdAt"]).tzinfo is None:
                raise ValueError()
        except ValueError:
            raise ProtocolError("Invalid persisted timestamp") from None
    elif value["createdAt"] is not None:
        raise ProtocolError("Unsaved report has a persisted timestamp")
    return {"status": value["outcome"], "attemptId": value["attemptId"],
            "serverContentHash": value["contentHash"], "inserted": counts[0], "duplicate": counts[1],
            "notImported": counts[2], "rowErrors": row_errors}


def verify_reply(reply: Reply, *, source: str, total: int, request_id: str) -> dict:
    unknown = {"status": "UNKNOWN", "inserted": None, "duplicate": None, "notImported": None,
               "attemptSaved": None, "outcomeUnknown": True, "rowErrors": [], "attemptId": None,
               "httpStatus": reply.status, "error": {"code": reply.transport_error or "INVALID_SERVER_RESPONSE",
               "message": "Import outcome is unverified; retry only with the same source identities and content"}}
    if reply.transport_error:
        return unknown
    try:
        if (reply.headers.get("x-request-id") != request_id
                or reply.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json"):
            raise ProtocolError("Invalid response headers")
        body = json.loads(reply.body.decode("utf-8", errors="strict"),
                          object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
        if not isinstance(body, dict):
            raise ProtocolError("Invalid response object")
        if reply.status == 201:
            result = _report(body, source, total, request_id, True)
            if result["status"] != "COMMITTED":
                raise ProtocolError("Success without committed report")
            return result | {"httpStatus": 201, "attemptSaved": True, "outcomeUnknown": False, "error": None}
        if not isinstance(reply.status, int) or not 400 <= reply.status <= 599:
            raise ProtocolError("Unexpected HTTP status")
        if body.get("request_id") != request_id or not isinstance(body.get("message"), str):
            raise ProtocolError("Error correlation mismatch")
        code = _code(body.get("code"))
        error = {"code": code, "message": "Server did not return a normal successful import response"}
        if "report" not in body:
            if reply.status >= 500 or reply.status in (408, 429):
                return unknown | {"error": error}
            if set(body) != {"code", "message", "request_id"}:
                raise ProtocolError("Invalid entry error fields")
            return {"status": "REJECTED", "inserted": 0, "duplicate": 0, "notImported": total,
                    "attemptSaved": False, "outcomeUnknown": False, "rowErrors": [], "attemptId": None,
                    "httpStatus": reply.status, "error": error}
        if set(body) != {"code", "message", "request_id", "attemptId", "rowErrors", "report", "attemptSaved", "outcomeUnknown"}:
            raise ProtocolError("Invalid failure envelope fields")
        saved, outcome_unknown = body["attemptSaved"], body["outcomeUnknown"]
        if type(saved) is not bool or type(outcome_unknown) is not bool or (saved and outcome_unknown):
            raise ProtocolError("Invalid failure persistence flags")
        result = _report(body["report"], source, total, request_id, saved)
        if body["attemptId"] != result["attemptId"] or body["rowErrors"] != body["report"]["rowErrors"]:
            raise ProtocolError("Failure report mismatch")
        if outcome_unknown:
            if reply.status < 500 or result["status"] == "COMMITTED":
                raise ProtocolError("Inconsistent unknown report")
            return unknown | {"attemptId": result["attemptId"], "attemptSaved": saved,
                              "serverContentHash": result["serverContentHash"], "error": error}
        if not saved and (reply.status >= 500 or result["status"] == "COMMITTED"):
            raise ProtocolError("Unproven commit outcome")
        return result | {"httpStatus": reply.status, "attemptSaved": saved, "outcomeUnknown": False, "error": error}
    except (ProtocolError, UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError, RecursionError):
        return unknown
