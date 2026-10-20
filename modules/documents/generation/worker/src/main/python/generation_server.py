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

"""Document generation worker: fills a .docx template and converts it to PDF.

Endpoints:
    GET  /health   -> {"status", "ready", "slots"}
    POST /generate -> body {"template": <base64 .docx>, "context": {...}}
                   -> 200 {"docx": <base64>, "pdf": <base64>}
                   -> 400 malformed request, 413 body over the limit
                   -> 422 {"error"} when the template cannot be rendered with that context
                   -> 503 when every conversion slot is busy
                   -> 500 {"error", "reference"}, the details in the log only

Synchronous, unlike the Docling daemon: a one-page letter renders in milliseconds and converts
in under a second, so a caller waits for the answer instead of hearing back through a callback.
Nothing is written to the shared volume either; the template and both results travel in the
request and the reply.

``/generate`` refuses an ``Origin`` header and, when ``IAP_GENERATION_TOKEN`` is set, requires
it as a bearer token, for the reasons :mod:`daemon_utils` gives. ``GET /health`` stays open.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import shutil
import signal
import sys
import tempfile
import threading
import time
import traceback
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import libreoffice_convert
import render_template
import shared_docs
from daemon_utils import (
    RequestBodyRefused,
    drain_request_body,
    get_daemon_token,
    is_token_ascii,
    read_request_body,
    refuse_unauthorized,
    send_json_response,
)

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18766

# This service's own secret, not the Docling daemon's: the two are separate containers, and
# reaching one should not be authority to use the other
TOKEN_ENVIRONMENT_VARIABLE = "IAP_GENERATION_TOKEN"

# Set by an operator who has confined the port another way, as for the Docling daemon
TRUSTED_NETWORK_VARIABLE = "IAP_GENERATION_TRUSTED_NETWORK"

# How many conversions may run at once. Each is its own soffice process with its own profile,
# so they do not collide, but each costs a few hundred MB while it runs.
SLOTS_VARIABLE = "IAP_GENERATION_MAX_CONCURRENT"
DEFAULT_SLOTS = 2

# The largest request body read. A one-page template with a letterhead image is well under a
# megabyte, and base64 adds a third.
MAX_REQUEST_BYTES_VARIABLE = "IAP_GENERATION_MAX_REQUEST_BYTES"
DEFAULT_MAX_REQUEST_BYTES = 16 * 1024 * 1024

# The name the template is written under for the zip-bomb check and the conversion
STAGED_DOCX = "document.docx"

# How often :func:`main` looks for a shutdown request; see docling_daemon for why it polls
SHUTDOWN_POLL_SECONDS = 0.5


def _log_stderr(message: str) -> None:
    """Log one line to the container log."""
    print(message, file=sys.stderr, flush=True)


def _get_slot_count() -> int:
    configured = shared_docs.read_positive_number_from_env(SLOTS_VARIABLE, DEFAULT_SLOTS)
    return configured if configured is not None else DEFAULT_SLOTS


def _get_max_request_bytes() -> int:
    configured = shared_docs.read_positive_number_from_env(
        MAX_REQUEST_BYTES_VARIABLE, DEFAULT_MAX_REQUEST_BYTES
    )
    return configured if configured is not None else DEFAULT_MAX_REQUEST_BYTES


class BadRequest(ValueError):
    """The request is not one this endpoint can act on, so the reply is a 400."""


class WorkerState:
    """What every request thread shares."""

    def __init__(self, slots: int) -> None:
        self.slot_count = slots
        # Non-blocking: a caller arriving while every slot is busy is told so, and retries
        self.slots = threading.BoundedSemaphore(slots)
        self.shutdown_requested = False


_STATE: WorkerState | None = None
_SHUTDOWN_REQUESTED = False


def _decode_request(body: bytes) -> tuple[bytes, dict[str, Any]]:
    """The template and the context out of a ``/generate`` body.

    @param body: the raw request body
    @return: the template's bytes and the context
    @raise BadRequest: when the body is not the JSON object this endpoint expects
    """
    try:
        request = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BadRequest("the body must be a JSON object") from exc
    if not isinstance(request, dict):
        raise BadRequest("the body must be a JSON object")
    encoded = request.get("template")
    if not isinstance(encoded, str) or not encoded:
        raise BadRequest('"template" must be the base64-encoded .docx')
    context = request.get("context", {})
    if not isinstance(context, dict):
        raise BadRequest('"context" must be a JSON object')
    try:
        template = base64.b64decode(encoded, validate=True)
    except binascii.Error as exc:
        raise BadRequest('"template" is not valid base64') from exc
    return template, context


def generate(template: bytes, context: dict[str, Any]) -> tuple[bytes, bytes]:
    """Render the template and convert the result to PDF.

    @param template: the .docx template's bytes
    @param context: the values its tags refer to
    @return: the rendered .docx and its PDF
    @raise render_template.TemplateError: when the template cannot be rendered
    @raise RuntimeError: when LibreOffice cannot convert the rendered document
    """
    work_dir = Path(tempfile.mkdtemp(prefix="iap-gen-"))
    try:
        # Written out only to be measured: a .docx is a zip, and the request limit bounds it
        # compressed, not what python-docx will hold in memory once it is opened
        staged = work_dir / STAGED_DOCX
        staged.write_bytes(template)
        try:
            shared_docs.refuse_oversized_docx(staged)
        except shared_docs.ParseRequestError as exc:
            raise render_template.TemplateError(str(exc)) from exc

        rendered = render_template.render(template, context)
        staged.write_bytes(rendered)
        pdf = libreoffice_convert.docx_to_pdf(staged, work_dir, log=_log_stderr)
        return rendered, pdf.read_bytes()
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)


class GenerationHandler(BaseHTTPRequestHandler):
    """HTTP request handler for the generation worker."""

    protocol_version = "HTTP/1.1"
    timeout = 120

    def log_message(self, format: str, *args: Any) -> None:
        # Docker's HEALTHCHECK probes /health every 30s; logging each one only adds noise
        if self.path == "/health":
            return
        sys.stderr.write(f"{self.address_string()} - {format % args}\n")

    def do_GET(self) -> None:
        drain_request_body(self)
        if self.path.split("?", 1)[0] != "/health":
            send_json_response(self, HTTPStatus.NOT_FOUND, {"error": "not found"})
            return

        ready = _STATE is not None and not _STATE.shutdown_requested
        send_json_response(
            self,
            HTTPStatus.OK if ready else HTTPStatus.SERVICE_UNAVAILABLE,
            {
                "status": "ok" if ready else "shutting_down",
                "ready": ready,
                "slots": _STATE.slot_count if _STATE is not None else 0,
            },
        )

    def do_POST(self) -> None:
        if self.path.split("?", 1)[0] != "/generate":
            drain_request_body(self)
            send_json_response(self, HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        self._handle_generate()

    def _handle_generate(self) -> None:
        """Fill the template in the body and answer with the .docx and the PDF."""
        # Checked before the body is read, so a refused caller gets no work done for it; the
        # unread body then means the connection has to close
        keep_alive = self.close_connection
        self.close_connection = True
        if refuse_unauthorized(
            self, "/generate", log=_log_stderr, variable=TOKEN_ENVIRONMENT_VARIABLE
        ):
            return
        self.close_connection = keep_alive

        # Every reply is decided inside the try and written once after it, as in the Docling
        # daemon: a write to a client that has gone away raises, and must not be answered twice
        try:
            body = read_request_body(self, _get_max_request_bytes())
            template, context = _decode_request(body)
            if _STATE is None or _STATE.shutdown_requested:
                reply = (HTTPStatus.SERVICE_UNAVAILABLE, {"error": "worker shutting down"})
            elif not _STATE.slots.acquire(blocking=False):
                reply = (
                    HTTPStatus.SERVICE_UNAVAILABLE,
                    {"error": f"worker busy: all {_STATE.slot_count} conversion slots in use"},
                )
            else:
                try:
                    started = time.monotonic()
                    docx, pdf = generate(template, context)
                    _log_stderr(
                        f"generate done ms={int((time.monotonic() - started) * 1000)} "
                        f"docxBytes={len(docx)} pdfBytes={len(pdf)}"
                    )
                    reply = (
                        HTTPStatus.OK,
                        {
                            "docx": base64.b64encode(docx).decode("ascii"),
                            "pdf": base64.b64encode(pdf).decode("ascii"),
                        },
                    )
                finally:
                    _STATE.slots.release()
        except RequestBodyRefused as exc:
            reply = (exc.status, {"error": str(exc)})
        except BadRequest as exc:
            reply = (HTTPStatus.BAD_REQUEST, {"error": str(exc)})
        except render_template.TemplateError as exc:
            # Said back in full: it is about the template, and whoever fixes the template needs it
            _log_stderr(f"generate refused: {exc}")
            reply = (HTTPStatus.UNPROCESSABLE_ENTITY, {"error": str(exc)})
        except Exception:
            # soffice's output and the paths in it stay in the log, under a reference
            reference = uuid.uuid4().hex[:12]
            _log_stderr(f"Generation failure {reference}:")
            traceback.print_exc(file=sys.stderr)
            reply = (
                HTTPStatus.INTERNAL_SERVER_ERROR,
                {
                    "error": "the document could not be generated; the worker log records why, "
                    "against this reference",
                    "reference": reference,
                },
            )
        send_json_response(self, *reply)


class DrainingHTTPServer(ThreadingHTTPServer):
    """``ThreadingHTTPServer`` whose request threads are joined on close.

    So a shutdown lets a conversion that is running finish and answer its caller. See
    ``docling_daemon.DrainingHTTPServer`` for why ``daemon_threads`` must stay off.
    """

    daemon_threads = False


def _request_shutdown() -> None:
    """Ask :func:`main` to stop serving. Called from a signal handler, so it only sets flags."""
    global _SHUTDOWN_REQUESTED
    _SHUTDOWN_REQUESTED = True
    if _STATE is not None:
        _STATE.shutdown_requested = True


def _handle_signal(_signum: int, _frame: Any) -> None:
    _request_shutdown()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the document generation worker: .docx template in, .docx and PDF out."
    )
    parser.add_argument(
        "--host",
        default=DEFAULT_HOST,
        help=(
            f"bind address (default: {DEFAULT_HOST}). With no {TOKEN_ENVIRONMENT_VARIABLE} set "
            "the port is the only boundary; in a container bind 0.0.0.0 but publish the port to "
            f"127.0.0.1 and set {TRUSTED_NETWORK_VARIABLE} to say so"
        ),
    )
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help=f"listen port (default: {DEFAULT_PORT})",
    )
    return parser.parse_args()


def _warn_if_exposed(host: str) -> None:
    """Say so when the bind address is not loopback and nothing authenticates."""
    if host in ("127.0.0.1", "::1", "localhost"):
        return
    if get_daemon_token(TOKEN_ENVIRONMENT_VARIABLE) is not None:
        return
    if (os.environ.get(TRUSTED_NETWORK_VARIABLE) or "").strip():
        return
    _log_stderr(
        f"WARNING: binding {host}, not loopback, with no {TOKEN_ENVIRONMENT_VARIABLE} set. "
        f"Anyone who can reach this port can spend the conversion slots. Set "
        f"{TOKEN_ENVIRONMENT_VARIABLE}, or in Docker publish as "
        f"\"127.0.0.1:<port>:{DEFAULT_PORT}\" so only this host can reach it."
    )


def main() -> None:
    global _STATE

    args = parse_args()
    _warn_if_exposed(args.host)
    if not is_token_ascii(TOKEN_ENVIRONMENT_VARIABLE):
        _log_stderr(
            f"WARNING: {TOKEN_ENVIRONMENT_VARIABLE} contains non-ASCII characters, so whether a "
            "client authenticates depends on the encoding it chose. Use an ASCII secret."
        )
    soffice = libreoffice_convert.resolve_soffice_path()
    if shutil.which(soffice) is None:
        # Every request would fail, so fail here, where an operator looks first
        _log_stderr(f"Generation worker cannot start: LibreOffice not found ({soffice!r})")
        sys.exit(1)
    _STATE = WorkerState(_get_slot_count())

    # The server before the signal handlers, as in the Docling daemon
    server = DrainingHTTPServer((args.host, args.port), GenerationHandler)
    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)
    print(
        f"Generation worker listening on http://{args.host}:{args.port} "
        f"with {_STATE.slot_count} conversion slots",
        flush=True,
    )

    server_thread = threading.Thread(target=server.serve_forever, name="generation-http")
    try:
        if not _SHUTDOWN_REQUESTED:
            server_thread.start()
            while not _SHUTDOWN_REQUESTED and server_thread.is_alive():
                time.sleep(SHUTDOWN_POLL_SECONDS)
    finally:
        if server_thread.is_alive():
            server.shutdown()
            server_thread.join()
        server.server_close()
        print("Generation worker stopped", flush=True)


if __name__ == "__main__":
    main()
