# Copyright 2026 DATA @ UHN. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""The daemon's HTTP plumbing and request guards, with no Docling import anywhere.

Kept out of :mod:`docling_daemon` so it stays importable without Docling. The daemon's own
test module skips itself wherever Docling is missing, including CI, which would leave these
checks -- the only thing between an unauthenticated caller and the worker pool -- untested.
``test_daemon_http.py`` runs everywhere.

What is here: the bearer-token secret, the guard refusing browser-originated and
unauthenticated requests to the mutating endpoints, the request-body drain with its size cap,
the JSON reply helper, and the ``/parse`` query parsing.
"""

from __future__ import annotations

import hmac
import json
import os
from http import HTTPStatus
from typing import Any
from urllib.parse import parse_qs, urlsplit

from shared_docs import ParseRequestError

# Optional shared secret for the mutating endpoints. When set, /parse and /shutdown require
# "Authorization: Bearer <token>". Left unset the port is the only boundary, which is fine for
# a loopback-only deployment and not fine for anything else — hence the warning at startup.
TOKEN_ENVIRONMENT_VARIABLE = "IAP_DOCLING_TOKEN"

# Largest request body read and discarded before answering. Path-based /parse sends none, so
# anything here is a caller's mistake; a declared length past this is refused rather than
# streamed, so nobody can hold a worker thread open feeding it bytes.
MAX_DRAINED_BODY_BYTES = 1024 * 1024


def get_daemon_token() -> str | None:
    """The shared secret guarding the mutating endpoints, or ``None`` when unset."""
    token = (os.environ.get(TOKEN_ENVIRONMENT_VARIABLE) or "").strip()
    return token or None


def is_token_ascii() -> bool:
    """Whether the configured token is ASCII, and so unambiguous on the wire.

    ``http.server`` hands headers over latin-1 decoded, while the configured token arrives from
    the environment decoded as UTF-8. Those agree for ASCII. For anything else authentication
    depends on how the client encoded it, so a correct client can quietly fail. Unset is fine.
    """
    token = get_daemon_token()
    if token is None:
        return True
    return token.isascii()


def send_json_response(handler, status: int, payload: dict[str, Any]) -> None:
    """Send ``payload`` as JSON."""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    if handler.close_connection:
        handler.send_header("Connection", "close")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def drain_request_body(handler) -> bool:
    """Read and discard any unread request body (path-based /parse has none).

    @return: whether the body was dealt with. ``False`` means the connection is being closed
        instead — a chunked or oversized body is refused rather than streamed, and the caller
        should answer and stop rather than carry on with a request it could not fully read.
    """
    if handler.headers.get("Transfer-Encoding"):
        handler.close_connection = True
        return False
    declared = handler.headers.get("Content-Length")
    if not declared:
        return True
    try:
        remaining = int(declared)
    except ValueError:
        handler.close_connection = True
        return False
    if remaining < 0 or remaining > MAX_DRAINED_BODY_BYTES:
        handler.close_connection = True
        return False
    while remaining > 0:
        block = handler.rfile.read(min(65536, remaining))
        if not block:
            handler.close_connection = True
            return False
        remaining -= len(block)
    return True


def refuse_unauthorized(handler, endpoint: str, *, log) -> bool:
    """Reject a mutating request that came from a browser, or that lacks the secret.

    Two checks, covering different callers:

    * An ``Origin`` header means a page made this request, and nothing that legitimately drives
      this daemon is a web page. Loopback binding is no defence: the browser runs on the same
      host and a simple-content-type ``POST`` needs no preflight, so any site the operator
      visits could otherwise spend the worker pool or call ``/shutdown``.
    * A bearer token, when :data:`TOKEN_ENVIRONMENT_VARIABLE` is set, so that reaching the
      port is not by itself authority to use it.

    @param handler: the request handler being served
    @param endpoint: the endpoint name, for the log line
    @param log: line logger for the refusal
    @return: whether the request was refused, with a response already written
    """
    if handler.headers.get("Origin") is not None:
        log(f"Refused a browser-originated {endpoint} request")
        send_json_response(
            handler,
            HTTPStatus.FORBIDDEN,
            {"error": "requests carrying an Origin header are not accepted"},
        )
        return True
    token = get_daemon_token()
    if token is None:
        return False
    # Compare bytes, not str. compare_digest raises TypeError on a non-ASCII string, and this
    # runs before the handler's try/except, so one 0xFF byte killed the request with no reply.
    # The header goes back to the latin-1 bytes the client sent; the configured token is UTF-8.
    # Those match only for an ASCII token, which is why is_token_ascii() warns at startup.
    presented = (handler.headers.get("Authorization") or "").encode("latin-1", "replace")
    if not hmac.compare_digest(presented, f"Bearer {token}".encode()):
        send_json_response(
            handler,
            HTTPStatus.UNAUTHORIZED,
            {"error": f"missing or invalid {TOKEN_ENVIRONMENT_VARIABLE} bearer token"},
        )
        return True
    return False


# Query values that mean "no" for a boolean flag; anything else is true.
FALSE_VALUES = ("false", "0", "no")


def parse_query(path: str) -> dict[str, list[str]]:
    """The query string of a request path, decoded.

    Note that ``parse_qs`` decodes ``+`` as a space, which is why a filename containing one has
    to reach ``?path=`` percent-encoded (see :func:`shared_docs.resolve_parse_path`).
    """
    return parse_qs(urlsplit(path).query)


def parse_chunk_flag(query: dict[str, list[str]]) -> bool:
    """Whether ``?chunk=`` asks for chunking. Absent or empty means yes."""
    return (query.get("chunk", ["true"])[0] or "true").lower() not in FALSE_VALUES


def parse_token_options(query: dict[str, list[str]]) -> dict[str, int]:
    """The ``max_tokens`` / ``min_structure_tokens`` overrides a request carries.

    Absent or empty means "use the default", so they are left out of the result rather than
    guessed at. A value that is not a positive integer is the caller's mistake, not a reason to
    fall back silently — a 0 budget would split a document into a chunk per paragraph.

    @param query: the decoded query (see :func:`parse_query`)
    @return: only the options actually supplied, ready to pass as keyword arguments
    @raise ParseRequestError: when a supplied value is not an integer of 1 or more
    """
    options: dict[str, int] = {}
    for name in ("max_tokens", "min_structure_tokens"):
        raw = query.get(name, [None])[0]
        if not raw:
            continue
        try:
            parsed = int(raw)
        except ValueError:
            raise ParseRequestError(f"{name} must be an integer; got {raw!r}") from None
        if parsed < 1:
            raise ParseRequestError(f"{name} must be 1 or greater; got {parsed}")
        options[name] = parsed
    return options
