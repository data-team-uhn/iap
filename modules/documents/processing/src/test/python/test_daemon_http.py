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

"""The daemon's request guards, tested without Docling.

These used to live in ``docling_daemon`` and so were only covered by
``test_docling_runtime.py``, which skips itself when Docling is absent -- including in CI,
which installs only ``requirements-test.txt``. The checks standing between an unauthenticated
caller and the worker pool were therefore exercised by nothing automated. This module imports
:mod:`daemon_http` alone, so it runs everywhere.
"""

import io
import json
from email.message import Message
from http import HTTPStatus

import pytest

import daemon_http
import shared_docs


class FakeHandler:
    """Enough of a BaseHTTPRequestHandler for the guards, with the replies recorded.

    Header values are ``str`` because ``http.server`` decodes them as latin-1, which is what
    makes a non-ASCII byte reach the token comparison as a non-ASCII ``str``.
    """

    def __init__(self, body: bytes = b"", headers: dict | None = None):
        # An email.message.Message, like http.server builds, not a plain dict: real header
        # lookup is case-insensitive and a dict fake could not tell a casing regression either
        # way. Message.__setitem__ appends, so a None value means "not present" here.
        self.headers = Message()
        self.headers["Content-Length"] = str(len(body))
        for name, value in (headers or {}).items():
            if value is not None:
                self.headers[name] = value
        self.rfile = io.BytesIO(body)
        self.wfile = io.BytesIO()
        self.close_connection = False
        self.sent: list[tuple[str, object]] = []

    # -- the bits of the handler API the guards touch --
    def send_response(self, status):
        self.sent.append(("status", status))

    def send_header(self, key, value):
        self.sent.append((key, value))

    def end_headers(self):
        self.sent.append(("end", None))

    def set_header(self, name, value):
        """Replace a header. ``Message.__setitem__`` appends, which real requests never do."""
        del self.headers[name]
        self.headers[name] = value

    # -- assertions helpers --
    def status(self):
        return next((value for key, value in self.sent if key == "status"), None)

    def body(self):
        return self.wfile.getvalue()


def _log(_message):
    """Swallow the refusal log line."""


class TestDaemonToken:
    def test_unset_is_none(self, monkeypatch):
        monkeypatch.delenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, raising=False)
        assert daemon_http.get_daemon_token() is None

    def test_blank_is_none(self, monkeypatch):
        # The compose file leaves it empty by default, which must mean "unset", not "".
        monkeypatch.setenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, "   ")
        assert daemon_http.get_daemon_token() is None

    def test_surrounding_whitespace_is_stripped(self, monkeypatch):
        monkeypatch.setenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, "  s3cret\n")
        assert daemon_http.get_daemon_token() == "s3cret"


class TestRefuseUnauthorized:
    """The two checks guarding /parse and /shutdown."""

    def test_no_token_configured_lets_a_plain_caller_through(self, monkeypatch):
        monkeypatch.delenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, raising=False)
        handler = FakeHandler()
        assert daemon_http.refuse_unauthorized(handler, "/parse", log=_log) is False
        assert handler.sent == []

    def test_a_browser_origin_is_refused_even_with_no_token(self, monkeypatch):
        # Loopback is no defence: the browser runs on the same host, and a POST with a simple
        # content type needs no preflight.
        monkeypatch.delenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, raising=False)
        handler = FakeHandler(headers={"Origin": "https://evil.example"})
        assert daemon_http.refuse_unauthorized(handler, "/parse", log=_log) is True
        assert handler.status() == HTTPStatus.FORBIDDEN
        assert b"Origin" in handler.body()

    def test_a_browser_origin_is_refused_before_the_token_is_even_read(self, monkeypatch):
        monkeypatch.setenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, "s3cret")
        handler = FakeHandler(
            headers={"Origin": "https://evil.example", "Authorization": "Bearer s3cret"}
        )
        assert daemon_http.refuse_unauthorized(handler, "/parse", log=_log) is True
        assert handler.status() == HTTPStatus.FORBIDDEN

    def test_the_right_token_passes(self, monkeypatch):
        monkeypatch.setenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, "s3cret")
        handler = FakeHandler(headers={"Authorization": "Bearer s3cret"})
        assert daemon_http.refuse_unauthorized(handler, "/parse", log=_log) is False

    @pytest.mark.parametrize("header", [
        None,
        "",
        "Bearer nope",
        "Bearer s3cret ",
        "bearer s3cret",
        "s3cret",
        "Basic czNjcmV0",
    ])
    def test_anything_but_the_right_token_is_401(self, monkeypatch, header):
        monkeypatch.setenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, "s3cret")
        headers = {} if header is None else {"Authorization": header}
        handler = FakeHandler(headers=headers)
        assert daemon_http.refuse_unauthorized(handler, "/parse", log=_log) is True
        assert handler.status() == HTTPStatus.UNAUTHORIZED
        assert daemon_http.TOKEN_ENVIRONMENT_VARIABLE.encode() in handler.body()

    @pytest.mark.parametrize("raw", [b"Bearer \xff", b"Bearer s3cre\xff", b"\xc3\xa9", b"\x80"])
    def test_a_non_ascii_header_is_refused_not_a_crash(self, monkeypatch, raw):
        # Regression: http.server decodes headers as latin-1 and hmac.compare_digest refuses
        # to compare non-ASCII strings, so one 0xFF byte raised TypeError. The guard runs
        # before the handler's try/except, so the request died with no reply at all.
        monkeypatch.setenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, "s3cret")
        handler = FakeHandler(headers={"Authorization": raw.decode("latin-1")})
        assert daemon_http.refuse_unauthorized(handler, "/parse", log=_log) is True
        assert handler.status() == HTTPStatus.UNAUTHORIZED

    def test_the_refusal_is_logged_with_the_endpoint(self, monkeypatch):
        monkeypatch.delenv(daemon_http.TOKEN_ENVIRONMENT_VARIABLE, raising=False)
        lines: list[str] = []
        handler = FakeHandler(headers={"Origin": "https://evil.example"})
        daemon_http.refuse_unauthorized(handler, "/shutdown", log=lines.append)
        assert any("/shutdown" in line for line in lines)


class TestDrainRequestBody:
    """Path-based /parse carries no body, so anything present is drained, within a cap."""

    def test_the_whole_body_is_read(self):
        handler = FakeHandler(b"x" * 4096)
        daemon_http.drain_request_body(handler)
        assert handler.rfile.read() == b""
        assert handler.close_connection is False

    def test_no_content_length_is_a_noop(self):
        handler = FakeHandler()
        del handler.headers["Content-Length"]
        daemon_http.drain_request_body(handler)
        assert handler.close_connection is False

    def test_a_chunked_body_closes_the_connection(self):
        handler = FakeHandler(b"abc", headers={"Transfer-Encoding": "chunked"})
        daemon_http.drain_request_body(handler)
        assert handler.close_connection is True

    @pytest.mark.parametrize("declared", ["abc", "-1", ""])
    def test_a_bad_content_length_is_not_streamed(self, declared):
        handler = FakeHandler(b"abc")
        handler.set_header("Content-Length", declared)
        daemon_http.drain_request_body(handler)
        # An empty value is falsy and means "no body"; the others close the connection.
        assert handler.close_connection is (declared != "")

    def test_an_oversized_declared_length_is_refused_rather_than_read(self):
        # Nobody gets to hold a worker thread open feeding the daemon bytes.
        handler = FakeHandler(b"abc")
        handler.set_header("Content-Length", str(daemon_http.MAX_DRAINED_BODY_BYTES + 1))
        daemon_http.drain_request_body(handler)
        assert handler.close_connection is True
        assert handler.rfile.read() == b"abc"

    def test_a_length_at_the_cap_is_still_drained(self):
        handler = FakeHandler(b"x" * 16)
        handler.set_header("Content-Length", str(daemon_http.MAX_DRAINED_BODY_BYTES))
        daemon_http.drain_request_body(handler)
        # Short read: the body ended early, so the connection cannot be reused.
        assert handler.close_connection is True

    def test_a_short_body_closes_the_connection(self):
        handler = FakeHandler(b"abc")
        handler.set_header("Content-Length", "100")
        daemon_http.drain_request_body(handler)
        assert handler.close_connection is True


class TestJsonResponse:
    def test_it_writes_json_with_a_length_and_content_type(self):
        handler = FakeHandler()
        daemon_http.send_json_response(handler, HTTPStatus.OK, {"status": "ok"})
        assert handler.status() == HTTPStatus.OK
        assert json.loads(handler.body()) == {"status": "ok"}
        sent = dict(handler.sent)
        assert sent["Content-Type"] == "application/json; charset=utf-8"
        assert sent["Content-Length"] == str(len(handler.body()))

    def test_non_ascii_survives_the_reply(self):
        handler = FakeHandler()
        daemon_http.send_json_response(handler, HTTPStatus.BAD_REQUEST, {"error": "café — 研究"})
        assert json.loads(handler.body())["error"] == "café — 研究"

    def test_a_closing_connection_says_so(self):
        handler = FakeHandler()
        handler.close_connection = True
        daemon_http.send_json_response(handler, HTTPStatus.OK, {})
        assert dict(handler.sent)["Connection"] == "close"


class TestDrainReportsRefusal:
    """A body that could not be read must not be treated as read.

    The drain refuses a chunked or oversized body rather than streaming it, but it used to say
    so only by setting ``close_connection`` — the handler carried on and converted anyway, so
    the connection state and the work disagreed.
    """

    def test_it_returns_true_when_the_body_is_dealt_with(self):
        assert daemon_http.drain_request_body(FakeHandler(b"x" * 100)) is True

    def test_no_body_at_all_is_still_true(self):
        handler = FakeHandler()
        del handler.headers["Content-Length"]
        assert daemon_http.drain_request_body(handler) is True

    def test_a_chunked_body_returns_false(self):
        handler = FakeHandler(b"abc", headers={"Transfer-Encoding": "chunked"})
        assert daemon_http.drain_request_body(handler) is False

    def test_an_oversized_body_returns_false(self):
        handler = FakeHandler(b"abc")
        handler.set_header("Content-Length", str(daemon_http.MAX_DRAINED_BODY_BYTES + 1))
        assert daemon_http.drain_request_body(handler) is False

    def test_a_bad_length_returns_false(self):
        handler = FakeHandler(b"abc")
        handler.set_header("Content-Length", "abc")
        assert daemon_http.drain_request_body(handler) is False

    def test_a_short_body_returns_false(self):
        handler = FakeHandler(b"abc")
        handler.set_header("Content-Length", "100")
        assert daemon_http.drain_request_body(handler) is False


class TestParseQuery:
    """``/parse``'s query parsing, which the daemon module cannot cover in CI."""

    def test_the_query_is_decoded(self):
        query = daemon_http.parse_query("/parse?path=/shared-docs/a%20b.pdf&chunk=false")
        assert query["path"] == ["/shared-docs/a b.pdf"]
        assert query["chunk"] == ["false"]

    def test_no_query_is_empty(self):
        assert daemon_http.parse_query("/parse") == {}

    def test_a_plus_decodes_to_a_space(self):
        # Which is why a filename holding one has to arrive percent-encoded; the contract is
        # documented on shared_docs.resolve_parse_path.
        assert daemon_http.parse_query("/parse?path=a+b.pdf")["path"] == ["a b.pdf"]
        assert daemon_http.parse_query("/parse?path=a%2Bb.pdf")["path"] == ["a+b.pdf"]


class TestParseChunkFlag:
    @pytest.mark.parametrize("value,expected", [
        ("true", True), ("TRUE", True), ("1", True), ("yes", True), ("anything", True),
        ("false", False), ("FALSE", False), ("0", False), ("no", False), ("No", False),
    ])
    def test_the_recognised_values(self, value, expected):
        assert daemon_http.parse_chunk_flag({"chunk": [value]}) is expected

    def test_absent_means_chunk(self):
        assert daemon_http.parse_chunk_flag({}) is True

    def test_empty_means_chunk(self):
        assert daemon_http.parse_chunk_flag({"chunk": [""]}) is True


class TestParseTokenOptions:
    """A bad budget must be the caller's error, not a silent fallback.

    ``--max-tokens 0`` produced a chunk per paragraph rather than an error when the CLI skipped
    this validation; the daemon has always rejected it, and now that check is tested in CI too.
    """

    def test_absent_options_are_left_out(self):
        # Left out rather than defaulted, so the caller's defaults stay in one place.
        assert daemon_http.parse_token_options({}) == {}

    def test_empty_values_are_left_out(self):
        assert daemon_http.parse_token_options({"max_tokens": [""]}) == {}

    def test_both_are_read(self):
        query = {"max_tokens": ["500"], "min_structure_tokens": ["9000"]}
        assert daemon_http.parse_token_options(query) == {
            "max_tokens": 500, "min_structure_tokens": 9000,
        }

    @pytest.mark.parametrize("name", ["max_tokens", "min_structure_tokens"])
    def test_a_non_integer_is_refused(self, name):
        with pytest.raises(shared_docs.ParseRequestError, match="must be an integer"):
            daemon_http.parse_token_options({name: ["abc"]})

    @pytest.mark.parametrize("name", ["max_tokens", "min_structure_tokens"])
    @pytest.mark.parametrize("value", ["0", "-1", "-9999"])
    def test_less_than_one_is_refused(self, name, value):
        with pytest.raises(shared_docs.ParseRequestError, match="1 or greater"):
            daemon_http.parse_token_options({name: [value]})

    def test_one_is_allowed(self):
        assert daemon_http.parse_token_options({"max_tokens": ["1"]}) == {"max_tokens": 1}
