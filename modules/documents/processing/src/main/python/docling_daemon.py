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

"""
Long-running Docling worker daemon.

Keeps a warm ProcessPoolExecutor (with loaded PDF models) alive across requests.
Java calls this over HTTP instead of spawning docling_parser.py per file.

Endpoints:
    GET  /health   -> {"status": "ok", "workers": N, "ready": true}
    POST /parse    -> ?path=/shared-docs/.../file.pdf[&chunk=true][&max_tokens=]
                      [&min_structure_tokens=]
                     -> {"ok", "markdown_path", "chunked", "chunks_dir", "logs", "filename"}
    POST /shutdown -> graceful stop; served only with ``--enable-shutdown``

The daemon and the main app share ``/shared-docs`` (env ``IAP_SHARED_DOCS``).

``/parse`` and ``/shutdown`` change state, so both refuse any request carrying an ``Origin``
header (no legitimate caller here is a web page, and a page on the operator's machine can
reach loopback) and, when ``IAP_DOCLING_TOKEN`` is set, require it as a bearer token.
``GET /health`` stays open so probes need no credential.
"""

from __future__ import annotations

import argparse
import os
import signal
import sys
import threading
import traceback
from concurrent.futures import ProcessPoolExecutor
from concurrent.futures.process import BrokenProcessPool
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import docling_config  # noqa: F401 — apply shared Docling settings on import

from daemon_http import (
    TOKEN_ENVIRONMENT_VARIABLE,
    daemon_token,
    token_is_ascii,
    drain_request_body,
    json_response,
    parse_chunk_flag,
    parse_query,
    parse_token_options,
    refuse_unauthorized,
)

from chunker import DEFAULT_MAX_TOKENS, DEFAULT_MIN_STRUCTURE_TOKENS
from docling_batch_sizing import add_workers_argument, calc_workers
from docling.datamodel.base_models import InputFormat

from docling_docx_parser import get_docx_converter
from docling_pdf_parser import warm_pdf_workers, worker_context, _init_worker
from shared_docs import (
    ParseRequestError,
    refuse_oversized_input,
    resolve_parse_path,
    shared_docs_root,
)
from parse_document import parse_document

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18765

# One conversion at a time: the whole RAM budget in docling_batch_sizing is calculated for a
# single conversion spread over the pool. ThreadingHTTPServer starts a thread per request
# anyway, so without this a burst of callers gets a worker OOM-killed, and the BrokenProcessPool
# that follows marks the daemon permanently broken.
MAX_CONCURRENT_PARSES = 1


def _stderr(message: str) -> None:
    """Log one line to the container log."""
    print(message, file=sys.stderr, flush=True)


class DaemonState:
    """Shared daemon resources."""

    def __init__(self, workers: int | None) -> None:
        # Refresh free-RAM budget at daemon start
        self.worker_count = calc_workers(workers)
        self.pdf_executor = ProcessPoolExecutor(
            max_workers=self.worker_count,
            initializer=_init_worker,
            # Spawn, not fork: torch is already loaded here. See worker_context().
            mp_context=worker_context(),
        )
        self.docx_lock = threading.Lock()
        # The DOCX converter lives in this process, not the pool, so it sits outside the
        # per-worker RAM budget. That is fine: SimplePipeline parses OOXML and loads no models.
        self.docx_converter = None
        self.shutdown_requested = False
        self.pdf_executor_broken = False
        # Non-blocking: a caller that arrives while the daemon is busy is told so, rather
        # than parked on a socket for however long the conversion ahead of it takes
        self.parse_slots = threading.BoundedSemaphore(MAX_CONCURRENT_PARSES)
        try:
            warm_pdf_workers(self.pdf_executor, self.worker_count, log=_stderr)
            # Built and initialized here: get_docx_converter only constructs, and Docling would
            # otherwise build the pipeline on the first convert, while it holds the parse slot.
            self.docx_converter = get_docx_converter()
            self.docx_converter.initialize_pipeline(InputFormat.DOCX)
        except Exception:
            self.pdf_executor.shutdown(wait=False, cancel_futures=True)
            raise

    def is_ready(self) -> bool:
        """Return True if the daemon can accept conversion requests.

        A broken PDF pool fails the whole daemon (not just PDF requests) on purpose:
        it signals callers to fall back and operators to restart, since the pool
        cannot recover in-process. DOCX conversion would still work, but reporting
        unready keeps behaviour predictable.
        """
        return not self.shutdown_requested and not self.pdf_executor_broken

    def close(self) -> None:
        self.pdf_executor.shutdown(wait=True, cancel_futures=True)


_STATE: DaemonState | None = None
_SERVER: DrainingHTTPServer | None = None
_SHUTDOWN_ENABLED = False


def _health_status() -> str:
    """The reason ``/health`` is refusing, for an operator reading the body."""
    if _STATE is None:
        return "starting"
    if _STATE.pdf_executor_broken:
        return "pdf_pool_broken"
    if _STATE.shutdown_requested:
        return "shutting_down"
    return "ok"


def _run_parse(
    input_path: Path,
    *,
    chunk: bool,
    max_tokens: int,
    min_structure_tokens: int,
) -> dict[str, Any]:
    """LibreOffice + Docling + chunk_file on a shared-docs path."""
    assert _STATE is not None
    try:
        return parse_document(
            input_path,
            chunk=chunk,
            max_tokens=max_tokens,
            min_structure_tokens=min_structure_tokens,
            pdf_executor=_STATE.pdf_executor,
            pdf_workers=_STATE.worker_count,
            docx_lock=_STATE.docx_lock,
            docx_converter=_STATE.docx_converter,
            # Echo progress to stderr as well: on failure the HTTP reply carries only the
            # summary message, so the container log is the only place the per-batch
            # diagnostics (e.g. "FAILED pages 4-6: ...") survive.
            log=lambda message: print(message, file=sys.stderr, flush=True),
        )
    except BrokenProcessPool as exc:
        _STATE.pdf_executor_broken = True
        _request_shutdown()
        raise RuntimeError("PDF worker pool is broken; restart the daemon") from exc


class DrainingHTTPServer(ThreadingHTTPServer):
    """``ThreadingHTTPServer`` whose request threads are joinable on close.

    Do not restore ``daemon_threads = True``. ``socketserver._Threads.append`` returns early
    for a daemon thread, so no request thread is tracked and ``server_close()`` joins an empty
    list -- the drain in :func:`main` becomes a no-op and the pool is torn down under a running
    conversion.

    The cost is that an idle keep-alive connection holds its thread until
    ``DoclingDaemonHandler.timeout``, so ``server_close()`` can take that long with nothing
    converting. Still well inside the container's stop grace period.
    """

    daemon_threads = False


class DoclingDaemonHandler(BaseHTTPRequestHandler):
    """HTTP request handler for the Docling worker daemon."""

    protocol_version = "HTTP/1.1"
    timeout = 120

    def log_message(self, format: str, *args: Any) -> None:
        # Docker's HEALTHCHECK probes /health every 30s; logging each one only adds noise.
        if self.path == "/health":
            return
        # ``format % args`` stays printf: that is BaseHTTPRequestHandler's contract with its
        # callers. Only the line assembled around it is ours to write as an f-string.
        sys.stderr.write(f"{self.address_string()} - {format % args}\n")

    def do_GET(self) -> None:
        # Drained like every POST route, and for the same reason: answering with the body still
        # unread leaves those bytes to be parsed as the next request line on a keep-alive
        # connection. A GET with a body is unusual, not impossible.
        drain_request_body(self)
        # Split on "?" like the POST routes do: a probe that acquires a query string
        # ("/health?ready=1") would otherwise start getting 404s.
        if self.path.split("?", 1)[0] != "/health":
            json_response(self, HTTPStatus.NOT_FOUND, {"error": "not found"})
            return

        ready = _STATE is not None and _STATE.is_ready()
        json_response(
            self,
            HTTPStatus.OK if ready else HTTPStatus.SERVICE_UNAVAILABLE,
            # No filesystem paths here: /health is deliberately open so container probes need
            # no credential, which makes it the wrong place to hand out the shared-docs root.
            # It is logged at startup instead, where only an operator sees it.
            {
                "status": _health_status(),
                "workers": _STATE.worker_count if _STATE is not None else 0,
                "ready": ready,
            },
        )

    def _refuse_unauthorized(self, endpoint: str) -> bool:
        """Refuse a browser-originated or unauthenticated request (see :mod:`daemon_http`).

        Called before the body is drained, so a caller that is about to be refused gets no work
        done on its behalf. Nothing has been read at that point, and an unread body would
        corrupt the next request on a keep-alive connection, so a refusal closes the connection.
        """
        keep_alive = self.close_connection
        self.close_connection = True
        if refuse_unauthorized(self, endpoint, log=_stderr):
            return True
        # Restored, not cleared: an HTTP/1.0 caller or one sending "Connection: close" already
        # had this set, and overwriting it held the socket open for the handler's whole timeout.
        self.close_connection = keep_alive
        return False

    def do_POST(self) -> None:
        if self.path.split("?", 1)[0] == "/shutdown":
            if not _SHUTDOWN_ENABLED:
                # Off unless asked for: the container is stopped with a signal instead. Drain
                # first -- an unread body would be parsed as the next request line on a
                # keep-alive connection.
                drain_request_body(self)
                json_response(
                    self,
                    HTTPStatus.NOT_FOUND,
                    {"error": "/shutdown is disabled; start with --enable-shutdown"},
                )
                return
            if self._refuse_unauthorized("/shutdown"):
                return
            drain_request_body(self)
            _request_shutdown()
            json_response(self, HTTPStatus.OK, {"status": "shutting_down"})
            return

        if self.path.split("?", 1)[0] == "/parse":
            self._handle_parse()
            return

        drain_request_body(self)
        json_response(self, HTTPStatus.NOT_FOUND, {"error": "not found"})

    def _handle_parse(self) -> None:
        """Parse a document already on the shared volume.

        Query: ``?path=/shared-docs/.../file.pdf&chunk=true&max_tokens=2000``
        ``&min_structure_tokens=20000``.
        """
        if self._refuse_unauthorized("/parse"):
            return
        if not drain_request_body(self):
            # Chunked or oversized: the request was refused rather than read, so the
            # connection is closing and there is nothing to act on.
            json_response(
                self,
                HTTPStatus.BAD_REQUEST,
                {"error": "request body must be absent, or declared and under 1 MiB"},
            )
            return
        # Every reply is decided inside the try and written once after it. Writing to a client
        # that has already gone away raises, and a write attempted inside the try was caught by
        # the handler below, which then attempted a second reply and logged a second traceback.
        try:
            if _STATE is None or not _STATE.is_ready():
                reply = (HTTPStatus.SERVICE_UNAVAILABLE, {"error": "daemon shutting down"})
            else:
                query = parse_query(self.path)
                input_path = resolve_parse_path(query.get("path", [""])[0] or "")
                chunk = parse_chunk_flag(query)
                options = parse_token_options(query)

                if not _STATE.parse_slots.acquire(blocking=False):
                    # Refused rather than queued: a conversion takes minutes, and holding the
                    # socket open for one that has not started yet only invites client timeouts
                    reply = (
                        HTTPStatus.SERVICE_UNAVAILABLE,
                        {
                            "error": "daemon busy: a conversion is already running "
                            f"(limit {MAX_CONCURRENT_PARSES})"
                        },
                    )
                else:
                    try:
                        # Inside the slot: reading the document to measure it is real work, and
                        # the RAM budget covers one conversion, not one per concurrent caller.
                        refuse_oversized_input(input_path)
                        reply = (
                            HTTPStatus.OK,
                            _run_parse(
                                input_path,
                                chunk=chunk,
                                max_tokens=options.get("max_tokens", DEFAULT_MAX_TOKENS),
                                min_structure_tokens=options.get(
                                    "min_structure_tokens", DEFAULT_MIN_STRUCTURE_TOKENS
                                ),
                            ),
                        )
                    finally:
                        _STATE.parse_slots.release()
        except ParseRequestError as exc:
            reply = (HTTPStatus.BAD_REQUEST, {"error": str(exc)})
        except Exception as exc:
            # Everything else is a server-side failure, including the ValueErrors Docling,
            # pypdf and the chunker raise on a malformed document. The reply carries only the
            # message, so log the traceback.
            traceback.print_exc(file=sys.stderr)
            broken_pool = _STATE is not None and _STATE.pdf_executor_broken
            if _STATE is not None and _STATE.shutdown_requested and not broken_pool:
                # A shutdown mid-conversion says nothing about the document, so answer 503
                # (retryable) rather than a 500 the caller could read as "unparseable".
                #
                # The broken-pool guard is needed because _run_parse also calls
                # _request_shutdown() for a BrokenProcessPool, so shutdown_requested is set
                # there too -- without it an OOM-killed worker reports the wrong cause.
                reply = (
                    HTTPStatus.SERVICE_UNAVAILABLE,
                    {"error": f"daemon shutting down mid-parse; retry this document ({exc})"},
                )
            else:
                reply = (HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(exc)})
        json_response(self, *reply)


def _request_shutdown() -> None:
    if _STATE is not None:
        _STATE.shutdown_requested = True
    if _SERVER is not None:
        threading.Thread(target=_SERVER.shutdown, daemon=True).start()


def _handle_signal(_signum: int, _frame: Any) -> None:
    _request_shutdown()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a long-lived Docling HTTP worker with a warm process pool."
    )
    parser.add_argument(
        "--host",
        default=DEFAULT_HOST,
        help=(
            f"bind address (default: {DEFAULT_HOST}). With no {TOKEN_ENVIRONMENT_VARIABLE} set "
            "the port is the only boundary, so binding anywhere but loopback lets the network "
            "parse documents; in a container bind 0.0.0.0 but publish the port to 127.0.0.1 and "
            f"set {TRUSTED_NETWORK_VARIABLE} to say so"
        ),
    )
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help=f"listen port (default: {DEFAULT_PORT})",
    )
    add_workers_argument(parser)
    parser.add_argument(
        "--enable-shutdown",
        action="store_true",
        help=(
            "serve POST /shutdown. Off by default: a container is stopped with a signal, so "
            "the endpoint is only useful when the caller owns the daemon process, and it is "
            "a denial-of-service handle everywhere else"
        ),
    )
    return parser.parse_args()


# Set by an operator who has confined the port another way -- published to loopback, or on a
# private Compose network. The daemon cannot see either from in here, because the ENTRYPOINT has
# to bind 0.0.0.0 for Docker to forward a published port at all, so without this the warning
# below fires on every boot of the sanctioned setup.
TRUSTED_NETWORK_VARIABLE = "IAP_DOCLING_TRUSTED_NETWORK"


def _warn_if_exposed(host: str) -> None:
    """Say so, loudly, when the bind address is not loopback and nothing authenticates."""
    if host in ("127.0.0.1", "::1", "localhost") or daemon_token() is not None:
        return
    if (os.environ.get(TRUSTED_NETWORK_VARIABLE) or "").strip():
        return
    print(
        f"WARNING: binding {host}, not loopback, with no {TOKEN_ENVIRONMENT_VARIABLE} set. "
        f"Anyone who can reach this port can parse any document on the shared volume and "
        f"spend the worker pool. Set {TOKEN_ENVIRONMENT_VARIABLE}, or in Docker publish as "
        f"\"127.0.0.1:<port>:{DEFAULT_PORT}\" so only this host can reach it.",
        file=sys.stderr,
        flush=True,
    )


def main() -> None:
    global _STATE, _SERVER, _SHUTDOWN_ENABLED

    args = parse_args()
    _SHUTDOWN_ENABLED = args.enable_shutdown
    _warn_if_exposed(args.host)
    if not token_is_ascii():
        # Said at startup rather than left to fail per request: a non-ASCII secret is compared
        # against whatever bytes the client encoded it to, so authentication becomes
        # encoding-dependent and a correct client can be refused with no clue why.
        _stderr(
            f"WARNING: {TOKEN_ENVIRONMENT_VARIABLE} contains non-ASCII characters. An HTTP "
            "header carries bytes, so whether a client authenticates then depends on the "
            "encoding it chose. Use an ASCII secret."
        )
    try:
        _STATE = DaemonState(args.workers)
    except Exception as e:
        print(f"Docling daemon initialization failed: {e}", file=sys.stderr, flush=True)
        sys.exit(1)

    # Build the server before installing the handlers. The other order leaves a window where a
    # signal finds _SERVER still None, so nothing stops the accept loop and serve_forever runs
    # on answering 503 until SIGKILL -- a container that looks hung instead of stopping.
    _SERVER = DrainingHTTPServer((args.host, args.port), DoclingDaemonHandler)
    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)

    print(
        f"Docling daemon listening on http://{args.host}:{args.port} "
        f"with {_STATE.worker_count} warm PDF workers "
        f"(shared docs root: {shared_docs_root()})",
        flush=True,
    )

    try:
        # A signal delivered between installing the handlers and here already set the flag, so
        # check it rather than entering a loop nothing will leave.
        if _STATE.is_ready():
            _SERVER.serve_forever()
    finally:
        pool_broke = _STATE.pdf_executor_broken
        # server_close() first, pool second. It joins the request threads (which needs
        # DrainingHTTPServer), so a running conversion finishes and answers its caller. The
        # other order cancels its queued page batches, and a cancelled batch is reported the
        # same way as a corrupt document -- so a plain SIGTERM looked like a parse failure.
        _SERVER.server_close()
        _STATE.close()
        print("Docling daemon stopped", flush=True)

    if pool_broke:
        print(
            "PDF worker pool broke and cannot be rebuilt in-process; exiting so a fresh "
            "daemon is started",
            file=sys.stderr,
            flush=True,
        )
        sys.exit(1)


if __name__ == "__main__":
    main()
