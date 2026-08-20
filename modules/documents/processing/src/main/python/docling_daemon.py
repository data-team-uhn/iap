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
                     With ``&job_id=`` the daemon instead answers
                     {"job_id", "status": "queued"} immediately, converts in the background,
                     and POSTs the summary (minus "logs", plus "job_id"; on failure
                     {"job_id", "ok": false, "error"}) to ``IAP_DOCLING_CALLBACK_URL``,
                     authenticated with the ``IAP_DOCLING_CALLBACK_JWT`` bearer token.
    POST /shutdown -> graceful stop; served only with ``--enable-shutdown``

The daemon and the main app share ``/shared-docs`` (env ``IAP_SHARED_DOCS``).

``/parse`` and ``/shutdown`` change state, so both refuse any request carrying an ``Origin``
header (no legitimate caller here is a web page, and a page on the operator's machine can
reach loopback) and, when ``IAP_DOCLING_TOKEN`` is set, require it as a bearer token.
``GET /health`` stays open so probes need no credential. Where the callback goes is not part
of the request either: it carries the shared token, so it is read from the daemon's own
environment and a caller cannot point it elsewhere.

One conversion runs at a time (:data:`MAX_CONCURRENT_PARSES`), synchronous and background
alike, because the PDF worker pool is sized for a single conversion. A shutdown drains the
background ones: in-flight parses are given :data:`SHUTDOWN_DRAIN_SECONDS` to deliver their
callbacks, and ones that never started are failed to the caller, so no job is left waiting
for a callback that will never come.
"""

from __future__ import annotations

import argparse
import os
import signal
import sys
import threading
import traceback
from concurrent.futures import (
    CancelledError,
    Future,
    ProcessPoolExecutor,
    ThreadPoolExecutor,
    wait,
)
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
import parse_callbacks

from chunker import DEFAULT_MAX_TOKENS
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
from toc_and_appendix_detection import DEFAULT_MIN_STRUCTURE_TOKENS

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18765

# How many conversions may be in flight at once, counting the synchronous path and the
# background one together. One, because the whole RAM budget in docling_batch_sizing —
# worker count, page-batch size, the cgroup limit it reads — is calculated for a single
# conversion spread over the worker pool: a second would not find workers to spread over, it
# would only double the memory. ThreadingHTTPServer starts a thread per request regardless,
# so without this a burst of callers each holds a full assembled document plus its chunk
# tree, the cgroup OOM killer takes a worker, and the BrokenProcessPool that follows marks
# the daemon permanently broken. It bounds the background pool and gates every _run_parse,
# so a synchronous request and a queued job cannot convert at the same time.
MAX_CONCURRENT_PARSES = 1

# How long a shutdown waits for in-flight parses to deliver their callbacks. The container
# has to allow at least this long to stop (stop_grace_period in docker-compose.yml), or the
# drain is cut short by SIGKILL and the point of it is lost.
SHUTDOWN_DRAIN_SECONDS = 60.0

# How long each "this parse never started" callback may take while shutting down.
SHUTDOWN_DELIVERY_TIMEOUT_SECONDS = 5.0


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
        self.parse_executor = ThreadPoolExecutor(
            max_workers=MAX_CONCURRENT_PARSES, thread_name_prefix="parse"
        )
        # The background parses that have not delivered a callback yet, so a shutdown can
        # account for every one of them
        self.pending_parses: dict[Future, tuple[str, str, str]] = {}
        self.pending_lock = threading.Lock()
        self.docx_lock = threading.Lock()
        # The DOCX converter lives in *this* process, not the pool, so whatever it holds sits
        # outside the per-worker budget calc_max_workers_by_ram computes. In practice that is
        # very little: WordFormatOption uses Docling's SimplePipeline, which parses OOXML
        # structurally and loads no model weights (initialize_pipeline for it takes ~0.04s,
        # against seconds and a model stack for the PDF pipeline).
        self.docx_converter = None
        self.shutdown_requested = False
        self.pdf_executor_broken = False
        # Non-blocking: a caller that arrives while the daemon is busy is told so, rather
        # than parked on a socket for however long the conversion ahead of it takes
        self.parse_slots = threading.BoundedSemaphore(MAX_CONCURRENT_PARSES)
        try:
            warm_pdf_workers(self.pdf_executor, self.worker_count, log=_stderr)
            # Built *and* initialized: get_docx_converter only constructs, and Docling builds
            # the pipeline lazily on first convert. It costs ~0.04s here because SimplePipeline
            # has no weights to load, so there is no reason to leave it until a request is
            # holding the parse slot.
            self.docx_converter = get_docx_converter()
            self.docx_converter.initialize_pipeline(InputFormat.DOCX)
        except Exception:
            self.parse_executor.shutdown(wait=False)
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

    def submit_parse(
        self,
        job_id: str,
        callback_url: str,
        token: str,
        input_path: Path,
        **options: Any,
    ) -> None:
        """Queue a background parse, and remember it until its callback is delivered.

        @param job_id: the caller's identifier for this parse
        @param callback_url: where the outcome is POSTed
        @param token: the shared callback JWT
        @param input_path: the already-validated document path
        @param options: the ``chunk`` / ``max_tokens`` / ``min_structure_tokens`` arguments
        @raise RuntimeError: when the daemon has begun shutting down
        """
        with self.pending_lock:
            if self.shutdown_requested:
                raise RuntimeError("the daemon is shutting down")
            future = self.parse_executor.submit(
                _parse_and_call_back, job_id, callback_url, token, input_path, **options
            )
            self.pending_parses[future] = (job_id, callback_url, token)
        future.add_done_callback(self._forget_parse)

    def _forget_parse(self, future: Future) -> None:
        """Drop a parse that has run its course, callback and all."""
        with self.pending_lock:
            self.pending_parses.pop(future, None)

    def drain_parses(self, timeout: float = SHUTDOWN_DRAIN_SECONDS) -> None:
        """Stop taking parses and account for the ones already accepted.

        Every accepted parse owes its caller a callback, and the caller has no timeout of
        its own: a background parse that disappears at shutdown leaves a job stuck forever.
        So queued parses, which never started and never will, are failed to their callers
        right away, and running ones are given ``timeout`` to finish and deliver. A parse
        still running after that keeps its (non-daemon) thread, so it is joined at exit
        rather than killed; the PDF pool closing under it turns into a failure callback,
        which is still an answer.

        @param timeout: seconds to wait for the running parses
        """
        self.shutdown_requested = True
        # Taken before the shutdown: cancelling a queued parse fires its done callback,
        # which forgets it, so a snapshot taken afterwards would not show it at all
        with self.pending_lock:
            pending = dict(self.pending_parses)
        self.parse_executor.shutdown(wait=False, cancel_futures=True)
        running = []
        for future, (job_id, callback_url, token) in pending.items():
            if future.cancelled():
                _stderr(f"Parse job {job_id} never started; failing it to the caller")
                parse_callbacks.deliver(
                    callback_url,
                    parse_callbacks.failure_payload(
                        job_id, "The daemon shut down before this parse started"
                    ),
                    token=token,
                    # One short attempt each: the process is going down, and the usual
                    # retry schedule would spend minutes per job waiting for a caller that
                    # is evidently not answering
                    attempts=1,
                    timeout=SHUTDOWN_DELIVERY_TIMEOUT_SECONDS,
                    log=_stderr,
                )
            else:
                running.append(future)
        if running:
            _stderr(
                f"Waiting up to {timeout:.0f}s for {len(running)} parse(s) to call back"
            )
            unfinished = wait(running, timeout=timeout).not_done
            if unfinished:
                _stderr(
                    f"{len(unfinished)} parse(s) are still running; their callbacks are "
                    "delivered before the process exits"
                )

    def close(self) -> None:
        # Parses first: they use the PDF pool, and they owe their callers a callback
        self.drain_parses()
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
            log=_stderr,
        )
    except BrokenProcessPool as exc:
        _STATE.pdf_executor_broken = True
        _request_shutdown()
        raise RuntimeError("PDF worker pool is broken; restart the daemon") from exc


class DrainingHTTPServer(ThreadingHTTPServer):
    """``ThreadingHTTPServer`` whose request threads are joinable on close.

    The base class sets ``daemon_threads = True``, and ``socketserver._Threads.append``
    *returns early* for a daemon thread — so no request thread is ever tracked and
    ``server_close()``'s ``join()`` joins an empty list. It returned in 0.00s with a request
    still in flight, which made the whole drain below a no-op: the pool was torn down under a
    running conversion, its queued page batches cancelled, and the process exited before the
    caller was answered. That is exactly the "a cancelled batch is indistinguishable from a
    corrupt document" failure the ordering in :func:`main` exists to prevent.

    The cost: an idle keep-alive connection holds its thread in ``readline()`` until
    ``DoclingDaemonHandler.timeout``, so ``server_close()`` can take that long even with
    nothing converting. Well inside the container's stop grace period.
    """

    daemon_threads = False


def _parse_and_call_back(
    job_id: str,
    callback_url: str,
    token: str,
    input_path: Path,
    *,
    chunk: bool,
    max_tokens: int,
    min_structure_tokens: int,
) -> None:
    """Run one background parse and POST its outcome to the caller's callback endpoint.

    Takes a parse slot for the conversion itself, blocking until one is free. The pool
    already runs these one at a time, but the synchronous path converts too, and the RAM
    budget covers one conversion in total — not one of each. Waiting is right here where
    refusing is right there: nobody is holding a socket open for this.
    """
    try:
        with _STATE.parse_slots:
            summary = _run_parse(
                input_path,
                chunk=chunk,
                max_tokens=max_tokens,
                min_structure_tokens=min_structure_tokens,
            )
        payload = parse_callbacks.success_payload(job_id, summary)
    except (Exception, CancelledError) as exc:
        # CancelledError is a BaseException, so "except Exception" would miss it: it is what
        # a shutdown closing the PDF pool mid-parse raises here, and that still owes the
        # caller a failure callback rather than silence.
        # The callback carries only the summary message; the traceback goes to the log
        traceback.print_exc(file=sys.stderr)
        payload = parse_callbacks.failure_payload(job_id, str(exc) or type(exc).__name__)
    parse_callbacks.deliver(callback_url, payload, token=token, log=_stderr)


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
                # Off unless asked for: the container is stopped with a signal, so the
                # endpoint is a liability everywhere except a caller-owned daemon. Drain
                # first: answering with the body still unread leaves those bytes to be
                # parsed as the next request line on a keep-alive connection.
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
        ``&min_structure_tokens=20000``, plus ``&job_id=`` for the asynchronous,
        callback-delivered variant.
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
                job_id = (query.get("job_id", [""])[0] or "").strip()

                if job_id:
                    # Answers immediately; the background task takes the parse slot when it
                    # actually converts, so the acquire below covers the blocking path only
                    reply = self._accept_async_parse(job_id, input_path, chunk, options)
                elif not _STATE.parse_slots.acquire(blocking=False):
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
            # pypdf and the chunker raise on a malformed document. Log the traceback: the
            # reply carries only the message.
            traceback.print_exc(file=sys.stderr)
            broken_pool = _STATE is not None and _STATE.pdf_executor_broken
            if _STATE is not None and _STATE.shutdown_requested and not broken_pool:
                # A conversion interrupted by a shutdown says nothing about the document, so
                # it must not come back as a 500 the caller could read as "unparseable".
                # 503 is the retryable answer, and a fresh daemon will manage it.
                #
                # Excluding a broken pool matters: _run_parse answers BrokenProcessPool by
                # flagging the pool *and* calling _request_shutdown(), so shutdown_requested is
                # set for that case too. Without the guard, an OOM-killed worker came back as
                # "daemon shutting down mid-parse" — the wrong cause, and indistinguishable
                # from a plain SIGTERM, while _health_status() still reported pdf_pool_broken.
                reply = (
                    HTTPStatus.SERVICE_UNAVAILABLE,
                    {"error": f"daemon shutting down mid-parse; retry this document ({exc})"},
                )
            else:
                reply = (HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(exc)})
        json_response(self, *reply)

    def _accept_async_parse(
        self,
        job_id: str,
        input_path: Path,
        chunk: bool,
        options: dict[str, Any],
    ) -> tuple[HTTPStatus, dict[str, Any]]:
        """Queue a background parse and answer immediately; the outcome goes to the callback.

        Where the callback goes is the daemon's own configuration, never the caller's: it
        carries the shared token, and nothing about reaching this port proves the caller is
        the app.

        Returns the reply rather than sending it, so ``_handle_parse`` still writes exactly
        once. Sending from here meant a caller that had already gone away raised inside the
        try, was answered a second time by the handler, and logged a second traceback.

        @param job_id: the caller's identifier for this parse, echoed back in the callback
        @param input_path: the already-validated document path
        @param chunk: whether the document should also be chunked
        @param options: validated ``max_tokens`` / ``min_structure_tokens`` overrides
        @return: the status and body ``_handle_parse`` should send
        @raise ParseRequestError: when the job_id is malformed
        """
        if len(job_id) > 200:
            raise ParseRequestError("job_id is unreasonably long")
        callback_url = parse_callbacks.callback_url()
        token = parse_callbacks.callback_token()
        missing = [
            name
            for name, value in (
                (parse_callbacks.URL_ENVIRONMENT_VARIABLE, callback_url),
                (parse_callbacks.TOKEN_ENVIRONMENT_VARIABLE, token),
            )
            if value is None
        ]
        if missing:
            return (
                HTTPStatus.SERVICE_UNAVAILABLE,
                {
                    "error": "asynchronous parsing is unavailable: "
                    + " and ".join(missing)
                    + " not configured"
                },
            )
        assert _STATE is not None
        try:
            _STATE.submit_parse(
                job_id,
                callback_url,
                token,
                input_path,
                chunk=chunk,
                max_tokens=options.get("max_tokens", DEFAULT_MAX_TOKENS),
                min_structure_tokens=options.get(
                    "min_structure_tokens", DEFAULT_MIN_STRUCTURE_TOKENS
                ),
            )
        except RuntimeError:
            # The shutdown started between the readiness check and here
            return (HTTPStatus.SERVICE_UNAVAILABLE, {"error": "daemon shutting down"})
        return (HTTPStatus.ACCEPTED, {"job_id": job_id, "status": "queued"})


def _request_shutdown() -> None:
    if _STATE is not None:
        _STATE.shutdown_requested = True
    if _SERVER is not None:
        threading.Thread(target=_SERVER.shutdown, daemon=True).start()


def _handle_signal(signum: int, _frame: Any) -> None:
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


# Set by an operator who has confined the port by other means -- published to loopback, or on
# a private Compose network. The daemon cannot see either from in here: the ENTRYPOINT has to
# bind 0.0.0.0 for Docker to forward a published port at all, so without this the warning below
# fired on every boot of the sanctioned configuration, and a warning that is always on is a
# warning nobody reads.
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

    # The server is built before the handlers are installed. Installing them first leaves a
    # window in which a signal finds _SERVER still None: _request_shutdown would set the flag
    # and return with nothing to stop the accept loop, and serve_forever would then run
    # forever answering 503 to everything until the stop grace period ran out and SIGKILL
    # landed — a container that looks hung rather than one that stops.
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
        # A signal delivered between installing the handlers and getting here has already
        # set the flag, so check it rather than entering a loop nothing will leave
        if _STATE.is_ready():
            _SERVER.serve_forever()
    finally:
        pool_broke = _STATE.pdf_executor_broken
        # server_close() first, and only then the pool: it joins the request threads (which
        # takes a non-daemon-thread server, see DrainingHTTPServer), so a conversion already
        # running gets to finish and answer its caller. Tearing the pool down first cancelled
        # that conversion's queued page batches instead, and a cancelled batch comes back
        # through the same path as a corrupt one — so a plain SIGTERM was reported to the
        # caller as a failed document. The container's stop grace period bounds the wait.
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
