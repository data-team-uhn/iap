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

"""Tests for the callback delivery used by the daemon's asynchronous ``/parse`` mode.

``parse_callbacks`` is deliberately free of Docling imports, so unlike the daemon plumbing
tests these run everywhere. The callback receiver is a real local HTTP server, since the
whole point of the module is what goes over the wire: the bearer token, the JSON body, and
the retry behaviour when the receiver misbehaves.
"""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import parse_callbacks


class _Receiver:
    """A local stand-in for the Java callback endpoint, answering scripted statuses."""

    def __init__(self, statuses):
        self.statuses = list(statuses)
        self.requests = []
        receiver = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length)
                receiver.requests.append((dict(self.headers), body))
                status = receiver.statuses.pop(0) if receiver.statuses else 200
                self.send_response(status)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/callback"

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def receiver_factory():
    receivers = []

    def build(statuses=()):
        receiver = _Receiver(statuses)
        receivers.append(receiver)
        return receiver

    yield build
    for receiver in receivers:
        receiver.stop()


class TestDeliver:
    """What goes over the wire, and what happens when the receiver misbehaves."""

    def test_delivers_the_payload_with_the_bearer_token(self, receiver_factory):
        receiver = receiver_factory([200])
        payload = parse_callbacks.success_payload(
            "86a4c102", {"ok": True, "markdown_path": "/shared-docs/a.md", "logs": "noisy"}
        )

        delivered = parse_callbacks.deliver(
            receiver.url, payload, token="secret-jwt", retry_delay=0, log=lambda _line: None
        )

        assert delivered is True
        assert len(receiver.requests) == 1
        headers, body = receiver.requests[0]
        assert headers.get("Authorization") == "Bearer secret-jwt"
        assert headers.get("Content-Type") == "application/json; charset=utf-8"
        sent = json.loads(body)
        assert sent["job_id"] == "86a4c102"
        assert sent["markdown_path"] == "/shared-docs/a.md"
        assert "logs" not in sent

    def test_retries_until_the_receiver_accepts(self, receiver_factory):
        receiver = receiver_factory([500, 200])
        messages = []

        delivered = parse_callbacks.deliver(
            receiver.url,
            parse_callbacks.failure_payload("86a4c102", "boom"),
            token="secret-jwt",
            retry_delay=0,
            log=messages.append,
        )

        assert delivered is True
        assert len(receiver.requests) == 2
        assert any("refused with HTTP 500" in line for line in messages)

    def test_gives_up_after_the_configured_attempts(self, receiver_factory):
        receiver = receiver_factory([500, 500, 500])
        messages = []

        delivered = parse_callbacks.deliver(
            receiver.url,
            parse_callbacks.failure_payload("86a4c102", "boom"),
            token="secret-jwt",
            attempts=2,
            retry_delay=0,
            log=messages.append,
        )

        assert delivered is False
        assert len(receiver.requests) == 2
        assert any("abandoned after 2 attempts" in line for line in messages)

    def test_an_unreachable_receiver_is_a_failed_delivery(self, receiver_factory):
        receiver = receiver_factory()
        receiver.stop()
        messages = []

        delivered = parse_callbacks.deliver(
            receiver.url,
            parse_callbacks.failure_payload("86a4c102", "boom"),
            token="secret-jwt",
            attempts=1,
            retry_delay=0,
            log=messages.append,
        )

        assert delivered is False
        assert any("failed:" in line for line in messages)


class TestPayloads:
    """The exact bodies the Java callback endpoint receives."""

    def test_success_payload_echoes_the_summary_without_logs(self):
        summary = {
            "ok": True,
            "markdown_path": "/shared-docs/a.md",
            "chunked": True,
            "chunks_dir": "/shared-docs/Chunks",
            "logs": "page 1... page 2...",
            "filename": "a.pdf",
        }

        payload = parse_callbacks.success_payload("86a4c102", summary)

        assert payload["job_id"] == "86a4c102"
        assert payload["ok"] is True
        assert payload["chunks_dir"] == "/shared-docs/Chunks"
        assert "logs" not in payload
        # The original summary is not mutated
        assert "logs" in summary

    def test_failure_payload_carries_the_error(self):
        payload = parse_callbacks.failure_payload("86a4c102", "cannot parse")

        assert payload == {"job_id": "86a4c102", "ok": False, "error": "cannot parse"}


class TestCallbackToken:
    """The shared JWT comes from the environment, or asynchronous parsing stays off."""

    def test_reads_the_token_from_the_environment(self, monkeypatch):
        monkeypatch.setenv(parse_callbacks.TOKEN_ENVIRONMENT_VARIABLE, "  the-jwt  ")
        assert parse_callbacks.callback_token() == "the-jwt"

    def test_a_missing_token_is_none(self, monkeypatch):
        monkeypatch.delenv(parse_callbacks.TOKEN_ENVIRONMENT_VARIABLE, raising=False)
        assert parse_callbacks.callback_token() is None

    def test_a_blank_token_is_none(self, monkeypatch):
        monkeypatch.setenv(parse_callbacks.TOKEN_ENVIRONMENT_VARIABLE, "   ")
        assert parse_callbacks.callback_token() is None
