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

"""Tests for the pieces of the daemon and PDF parser that need no model inference.

The ``docling_*`` conversion modules import the heavy ``docling`` package, so the whole file
skips when it is not installed — the rest of the suite still runs anywhere. What is covered
here is the plumbing around Docling rather than Docling itself: shared-docs path allowlisting,
health reporting, the parse-slot semaphore, and the batch-abandon path that runs when a page
batch fails.

Because this file skips in CI, nothing that can be tested without Docling belongs here. The
request guards were moved to :mod:`daemon_http` for exactly that reason; see
``test_daemon_http.py``. What remains are the tests that genuinely need the daemon.
"""

import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures.process import BrokenProcessPool
from http import HTTPStatus
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlencode

import pytest

pytest.importorskip("docling", reason="docling not installed; conversion plumbing skipped")

import docling_daemon as daemon  # noqa: E402 -- must follow the importorskip guard
import docling_pdf_parser as pdf_parser  # noqa: E402
import parse_callbacks  # noqa: E402
from docling_pdf_parser import _abandon_batches  # noqa: E402


class _FakeHandler:
    """Minimal stand-in for BaseHTTPRequestHandler's body-reading and response surface."""

    def __init__(self, body: bytes, content_length=None, headers=None):
        declared = len(body) if content_length is None else content_length
        self.headers = {"Content-Length": str(declared)}
        if headers:
            self.headers.update(headers)
        self._body = body
        self._offset = 0
        self.sent = []
        self.written = b""
        self.close_connection = False
        self.rfile = SimpleNamespace(read=self._read)
        self.wfile = SimpleNamespace(write=self._write)

    def _read(self, size):
        block = self._body[self._offset:self._offset + size]
        self._offset += len(block)
        return block

    def _write(self, data):
        self.written += data

    @property
    def unread(self):
        return len(self._body) - self._offset

    def send_response(self, status):
        self.sent.append(("status", status))

    def send_header(self, name, value):
        self.sent.append((name.lower(), value))

    def end_headers(self):
        self.sent.append(("end", None))

    def header_value(self, name):
        return next((v for k, v in self.sent if k == name.lower()), None)

    # The real guard, borrowed rather than stubbed: it reads only self.headers and writes
    # through _json_response, both of which this stand-in provides, so the tests exercise
    # the actual Origin and bearer-token checks.
    _refuse_unauthorized = daemon.DoclingDaemonHandler._refuse_unauthorized


class TestAbandonBatches:
    """What happens to sibling page batches when one batch fails."""

    def test_cancels_batches_that_have_not_started(self):
        with ThreadPoolExecutor(max_workers=1) as pool:
            running = pool.submit(time.sleep, 0.2)
            queued = [pool.submit(time.sleep, 5) for _ in range(3)]
            _abandon_batches([running, *queued], log=lambda _message: None)
            assert all(future.cancelled() for future in queued)

    def test_waits_for_a_batch_already_running(self):
        with ThreadPoolExecutor(max_workers=1) as pool:
            running = pool.submit(time.sleep, 0.3)
            start = time.perf_counter()
            _abandon_batches([running], log=lambda _message: None)
            elapsed = time.perf_counter() - start
        assert running.done()
        assert elapsed >= 0.25, f"returned after {elapsed:.3f}s without waiting"

    def test_swallows_a_failing_batch(self):
        def boom():
            raise RuntimeError("batch blew up")

        with ThreadPoolExecutor(max_workers=1) as pool:
            failing = pool.submit(boom)
            _abandon_batches([failing], log=lambda _message: None)

    def test_logs_only_when_something_was_still_running(self):
        messages = []
        with ThreadPoolExecutor(max_workers=1) as pool:
            done = pool.submit(time.sleep, 0)
            done.result()
            _abandon_batches([done], log=messages.append)
        assert messages == []


# The body drain, the bearer-token comparison and the JSON reply helper moved to
# daemon_http.py, and their tests to test_daemon_http.py, so they run in CI too -- this
# module skips itself wherever Docling is absent. The end-to-end guard tests below stay
# here, because they go through do_POST and need the handler.


class TestResolveParsePath:
    """``POST /parse`` only accepts absolute paths under the shared docs root."""

    def test_accepts_a_file_under_the_shared_root(self, monkeypatch, tmp_path):
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        pdf = tmp_path / "proto.pdf"
        pdf.write_bytes(b"%PDF")
        resolved = daemon.resolve_parse_path(str(pdf))
        assert resolved == pdf.resolve()

    def test_accepts_doc_and_docx(self, monkeypatch, tmp_path):
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        for name in ("a.docx", "b.doc"):
            path = tmp_path / name
            path.write_bytes(b"x")
            assert daemon.resolve_parse_path(str(path)).name == name

    def test_rejects_paths_outside_the_root(self, monkeypatch, tmp_path):
        root = tmp_path / "shared"
        root.mkdir()
        outside = tmp_path / "other" / "proto.pdf"
        outside.parent.mkdir()
        outside.write_bytes(b"%PDF")
        monkeypatch.setenv("IAP_SHARED_DOCS", str(root))
        with pytest.raises(ValueError, match="must be under"):
            daemon.resolve_parse_path(str(outside))

    def test_rejects_missing_files(self, monkeypatch, tmp_path):
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        with pytest.raises(ValueError, match="does not exist"):
            daemon.resolve_parse_path(str(tmp_path / "missing.pdf"))

    def test_rejects_unsupported_suffixes(self, monkeypatch, tmp_path):
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        bad = tmp_path / "notes.txt"
        bad.write_text("hi", encoding="utf-8")
        with pytest.raises(ValueError, match="must end in"):
            daemon.resolve_parse_path(str(bad))

    def test_rejects_empty_path(self, monkeypatch, tmp_path):
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        with pytest.raises(ValueError, match="required"):
            daemon.resolve_parse_path("   ")

    def test_does_not_decode_an_already_decoded_path(self, monkeypatch, tmp_path):
        # parse_qs decodes query values, so a file literally named "report%20final.pdf"
        # arrives here with its percent sign intact. Decoding again would look for
        # "report final.pdf" and report a file that is right there as missing.
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        literal = tmp_path / "report%20final.pdf"
        literal.write_bytes(b"%PDF")
        assert daemon.resolve_parse_path(str(literal)) == literal.resolve()

    def test_rejections_are_request_errors(self, monkeypatch, tmp_path):
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        with pytest.raises(daemon.ParseRequestError):
            daemon.resolve_parse_path(str(tmp_path / "missing.pdf"))
        assert issubclass(daemon.ParseRequestError, ValueError)


class TestHealthReporting:
    """``/health`` must fail its status line when the daemon is unusable."""

    def _state(self, **flags):
        state = SimpleNamespace(
            shutdown_requested=False, pdf_executor_broken=False, worker_count=2,
        )
        for name, value in flags.items():
            setattr(state, name, value)
        state.is_ready = lambda: not state.shutdown_requested and not state.pdf_executor_broken
        return state

    def test_status_is_ok_when_ready(self, monkeypatch):
        monkeypatch.setattr(daemon, "_STATE", self._state())
        assert daemon._get_health_status() == "ok"

    def test_broken_pool_says_so_rather_than_shutting_down(self, monkeypatch):
        monkeypatch.setattr(daemon, "_STATE", self._state(pdf_executor_broken=True))
        assert daemon._get_health_status() == "pdf_pool_broken"

    def test_shutdown_still_says_shutting_down(self, monkeypatch):
        monkeypatch.setattr(daemon, "_STATE", self._state(shutdown_requested=True))
        assert daemon._get_health_status() == "shutting_down"

    def test_broken_pool_wins_over_shutdown(self, monkeypatch):
        monkeypatch.setattr(
            daemon, "_STATE", self._state(pdf_executor_broken=True, shutdown_requested=True)
        )
        assert daemon._get_health_status() == "pdf_pool_broken"

    def test_no_state_yet_says_starting(self, monkeypatch):
        monkeypatch.setattr(daemon, "_STATE", None)
        assert daemon._get_health_status() == "starting"


class TestParseErrorStatus:
    """A bad query parameter is the caller's fault; a failed conversion is ours."""

    def _handler(self, query: dict):
        handler = _FakeHandler(b"")
        del handler.headers["Content-Length"]
        handler.path = "/parse?" + urlencode(query)
        return handler

    def _ready(self, monkeypatch, slots=daemon.MAX_CONCURRENT_PARSES):
        state = SimpleNamespace(
            shutdown_requested=False,
            pdf_executor_broken=False,
            parse_slots=threading.BoundedSemaphore(slots),
        )
        state.is_ready = lambda: True
        monkeypatch.setattr(daemon, "_STATE", state)
        return state

    def _staged_pdf(self, monkeypatch, tmp_path):
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        pdf = tmp_path / "doc.pdf"
        pdf.write_bytes(b"%PDF")
        return pdf

    def test_missing_path_is_400(self, monkeypatch, tmp_path):
        self._ready(monkeypatch)
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        handler = self._handler({})
        daemon.DoclingDaemonHandler._handle_parse(handler)
        assert handler.header_value("status") == HTTPStatus.BAD_REQUEST

    def test_unparseable_max_tokens_is_400(self, monkeypatch, tmp_path):
        self._ready(monkeypatch)
        pdf = self._staged_pdf(monkeypatch, tmp_path)
        handler = self._handler({"path": str(pdf), "max_tokens": "abc"})
        daemon.DoclingDaemonHandler._handle_parse(handler)
        assert handler.header_value("status") == HTTPStatus.BAD_REQUEST

    def test_a_value_error_from_conversion_is_500(self, monkeypatch, tmp_path):
        # pypdf, Docling and the chunker all raise plain ValueErrors on a malformed
        # document. Reporting those as 400 tells a caller not to retry a document that
        # may well parse next time.
        self._ready(monkeypatch)
        pdf = self._staged_pdf(monkeypatch, tmp_path)

        def explode(*args, **kwargs):
            raise ValueError("invalid xref table")

        monkeypatch.setattr(daemon, "_run_parse", explode)
        handler = self._handler({"path": str(pdf)})
        daemon.DoclingDaemonHandler._handle_parse(handler)
        assert handler.header_value("status") == HTTPStatus.INTERNAL_SERVER_ERROR


class TestConcurrentParsesAreBounded:
    """Only one conversion at a time; the rest are refused, not queued.

    Regression: ThreadingHTTPServer starts a thread per request, so N simultaneous callers ran
    N conversions, each holding a full assembled document and chunk tree — against a RAM
    budget calculated for exactly one. The cgroup OOM killer took a worker and the
    BrokenProcessPool that followed marked the daemon permanently broken.
    """

    def _handler(self, query: dict):
        handler = _FakeHandler(b"")
        del handler.headers["Content-Length"]
        handler.path = "/parse?" + urlencode(query)
        return handler

    def _staged_pdf(self, monkeypatch, tmp_path):
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        pdf = tmp_path / "doc.pdf"
        pdf.write_bytes(b"%PDF")
        return pdf

    def _ready(self, monkeypatch):
        state = SimpleNamespace(
            shutdown_requested=False,
            pdf_executor_broken=False,
            parse_slots=threading.BoundedSemaphore(daemon.MAX_CONCURRENT_PARSES),
        )
        state.is_ready = lambda: True
        monkeypatch.setattr(daemon, "_STATE", state)
        return state

    def test_a_second_parse_is_refused_while_one_runs(self, monkeypatch, tmp_path):
        state = self._ready(monkeypatch)
        pdf = self._staged_pdf(monkeypatch, tmp_path)
        running = threading.Event()
        release = threading.Event()

        def slow(*args, **kwargs):
            running.set()
            release.wait(5)
            return {"ok": True}

        monkeypatch.setattr(daemon, "_run_parse", slow)
        first = self._handler({"path": str(pdf)})
        worker = threading.Thread(
            target=daemon.DoclingDaemonHandler._handle_parse, args=(first,)
        )
        worker.start()
        try:
            assert running.wait(5), "the first parse never started"
            second = self._handler({"path": str(pdf)})
            daemon.DoclingDaemonHandler._handle_parse(second)
            assert second.header_value("status") == HTTPStatus.SERVICE_UNAVAILABLE
            assert b"busy" in second.written
        finally:
            release.set()
            worker.join(10)
        assert first.header_value("status") == HTTPStatus.OK
        # The slot is handed back, so the daemon is usable again
        assert state.parse_slots.acquire(blocking=False)

    def test_the_slot_is_released_when_a_parse_fails(self, monkeypatch, tmp_path):
        state = self._ready(monkeypatch)
        pdf = self._staged_pdf(monkeypatch, tmp_path)

        def explode(*args, **kwargs):
            raise ValueError("invalid xref table")

        monkeypatch.setattr(daemon, "_run_parse", explode)
        handler = self._handler({"path": str(pdf)})
        daemon.DoclingDaemonHandler._handle_parse(handler)

        assert handler.header_value("status") == HTTPStatus.INTERNAL_SERVER_ERROR
        # A failed conversion must not wedge the daemon shut for good
        assert state.parse_slots.acquire(blocking=False)

    def test_a_rejected_request_never_takes_a_slot(self, monkeypatch, tmp_path):
        state = self._ready(monkeypatch)
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        handler = self._handler({})

        daemon.DoclingDaemonHandler._handle_parse(handler)

        assert handler.header_value("status") == HTTPStatus.BAD_REQUEST
        assert state.parse_slots.acquire(blocking=False)


class TestMutatingEndpointsAreGuarded:
    """/parse and /shutdown are not simply open because the port is loopback.

    A page in the operator's browser runs on the same host, and a POST with a simple content
    type needs no preflight, so any site could otherwise spend the worker pool or stop the
    daemon. /health stays open for probes.
    """

    def _handler(self, path, headers=None):
        handler = _FakeHandler(b"", headers=headers)
        del handler.headers["Content-Length"]
        handler.path = path
        return handler

    def _ready(self, monkeypatch):
        state = SimpleNamespace(
            shutdown_requested=False,
            pdf_executor_broken=False,
            worker_count=1,
            parse_slots=threading.BoundedSemaphore(1),
        )
        state.is_ready = lambda: True
        monkeypatch.setattr(daemon, "_STATE", state)

    def test_a_browser_origin_is_refused_on_parse(self, monkeypatch, tmp_path):
        monkeypatch.delenv(daemon.TOKEN_ENVIRONMENT_VARIABLE, raising=False)
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        self._ready(monkeypatch)
        pdf = tmp_path / "doc.pdf"
        pdf.write_bytes(b"%PDF")
        handler = self._handler(
            f"/parse?path={pdf}", headers={"Origin": "https://evil.example"}
        )

        daemon.DoclingDaemonHandler._handle_parse(handler)

        assert handler.header_value("status") == HTTPStatus.FORBIDDEN

    def test_shutdown_is_off_unless_enabled(self, monkeypatch):
        monkeypatch.setattr(daemon, "_SHUTDOWN_ENABLED", False)
        stopped = []
        monkeypatch.setattr(daemon, "_request_shutdown", lambda: stopped.append(True))
        handler = self._handler("/shutdown")

        daemon.DoclingDaemonHandler.do_POST(handler)

        assert handler.header_value("status") == HTTPStatus.NOT_FOUND
        assert stopped == []

    def test_an_enabled_shutdown_still_refuses_a_browser(self, monkeypatch):
        monkeypatch.setattr(daemon, "_SHUTDOWN_ENABLED", True)
        monkeypatch.delenv(daemon.TOKEN_ENVIRONMENT_VARIABLE, raising=False)
        stopped = []
        monkeypatch.setattr(daemon, "_request_shutdown", lambda: stopped.append(True))
        handler = self._handler("/shutdown", headers={"Origin": "https://evil.example"})

        daemon.DoclingDaemonHandler.do_POST(handler)

        assert handler.header_value("status") == HTTPStatus.FORBIDDEN
        assert stopped == []

    def test_an_enabled_shutdown_works_for_a_plain_caller(self, monkeypatch):
        monkeypatch.setattr(daemon, "_SHUTDOWN_ENABLED", True)
        monkeypatch.delenv(daemon.TOKEN_ENVIRONMENT_VARIABLE, raising=False)
        stopped = []
        monkeypatch.setattr(daemon, "_request_shutdown", lambda: stopped.append(True))
        handler = self._handler("/shutdown")

        daemon.DoclingDaemonHandler.do_POST(handler)

        assert handler.header_value("status") == HTTPStatus.OK
        assert stopped == [True]

    def test_the_token_is_required_when_configured(self, monkeypatch):
        monkeypatch.setattr(daemon, "_SHUTDOWN_ENABLED", True)
        monkeypatch.setenv(daemon.TOKEN_ENVIRONMENT_VARIABLE, "s3cret")
        stopped = []
        monkeypatch.setattr(daemon, "_request_shutdown", lambda: stopped.append(True))

        missing = self._handler("/shutdown")
        daemon.DoclingDaemonHandler.do_POST(missing)
        assert missing.header_value("status") == HTTPStatus.UNAUTHORIZED

        wrong = self._handler("/shutdown", headers={"Authorization": "Bearer nope"})
        daemon.DoclingDaemonHandler.do_POST(wrong)
        assert wrong.header_value("status") == HTTPStatus.UNAUTHORIZED

        right = self._handler("/shutdown", headers={"Authorization": "Bearer s3cret"})
        daemon.DoclingDaemonHandler.do_POST(right)
        assert right.header_value("status") == HTTPStatus.OK
        assert stopped == [True]

    def test_a_non_ascii_token_header_is_refused_not_a_crash(self, monkeypatch):
        # Regression: http.server decodes request headers as latin-1, and
        # hmac.compare_digest refuses to compare strings holding non-ASCII characters. One
        # 0xFF byte therefore raised TypeError, and the check runs before the handler's
        # try/except, so the request died with a traceback and no reply at all — reachable by
        # anyone who can open the port, precisely when a token is configured.
        monkeypatch.setattr(daemon, "_SHUTDOWN_ENABLED", True)
        monkeypatch.setenv(daemon.TOKEN_ENVIRONMENT_VARIABLE, "s3cret")
        monkeypatch.setattr(daemon, "_request_shutdown", lambda: None)

        for header in (b"Bearer \xff".decode("latin-1"),
                       b"Bearer s3cre\xff".decode("latin-1"),
                       b"\xc3\xa9".decode("latin-1")):
            handler = self._handler("/shutdown", headers={"Authorization": header})
            daemon.DoclingDaemonHandler.do_POST(handler)
            assert handler.header_value("status") == HTTPStatus.UNAUTHORIZED, repr(header)

    def test_health_needs_no_credential(self, monkeypatch):
        monkeypatch.setenv(daemon.TOKEN_ENVIRONMENT_VARIABLE, "s3cret")
        self._ready(monkeypatch)
        handler = self._handler("/health")

        daemon.DoclingDaemonHandler.do_GET(handler)

        assert handler.header_value("status") == HTTPStatus.OK


class TestCliBatchPagesFlag:
    """--batch-pages was parsed and validated, then dropped before the converter saw it."""

    def _run(self, monkeypatch, tmp_path, *flags):
        import docling_parser

        pdf = tmp_path / "doc.pdf"
        pdf.write_bytes(b"%PDF")
        seen: dict = {}

        def capture(_input_path, **kwargs):
            seen.update(kwargs)
            return {"markdown_path": str(tmp_path / "doc.md"), "chunked": False, "logs": ""}

        monkeypatch.setattr(docling_parser, "parse_document", capture)
        monkeypatch.setattr(sys, "argv", ["docling_parser.py", str(pdf), *flags])
        docling_parser.main()
        return seen

    def test_batch_pages_reaches_the_converter(self, monkeypatch, tmp_path):
        assert self._run(monkeypatch, tmp_path, "--batch-pages", "1")["pdf_batch_pages"] == 1

    def test_omitting_it_leaves_the_size_automatic(self, monkeypatch, tmp_path):
        assert self._run(monkeypatch, tmp_path)["pdf_batch_pages"] is None


class TestRunPdfChunksBrokenPool:
    """A dead pool must surface as BrokenProcessPool so the daemon can act on it."""

    def test_broken_pool_propagates(self, monkeypatch):
        def explode(_chunk):
            raise BrokenProcessPool("worker was killed")

        monkeypatch.setattr(pdf_parser, "parse_pdf_chunk", explode)
        with ThreadPoolExecutor(max_workers=2) as pool:
            with pytest.raises(BrokenProcessPool):
                pdf_parser._run_pdf_chunks(
                    [("doc.pdf", 1, 2), ("doc.pdf", 3, 4)], pool, log=lambda _m: None
                )

    def test_an_ordinary_batch_failure_still_becomes_a_runtime_error(self, monkeypatch):
        def explode(_chunk):
            raise RuntimeError("page 3 is broken")

        monkeypatch.setattr(pdf_parser, "parse_pdf_chunk", explode)
        with ThreadPoolExecutor(max_workers=1) as pool:
            with pytest.raises(RuntimeError, match="Page batch conversion failed"):
                pdf_parser._run_pdf_chunks([("doc.pdf", 1, 2)], pool, log=lambda _m: None)

    def test_the_failure_carries_the_reason_and_the_pages(self, monkeypatch):
        # The daemon's reply is only str(exc), so a bare "a batch failed" left whoever polls
        # the job nothing to act on and no choice but to read the container log.
        def explode(_chunk):
            raise RuntimeError("page 3 is broken")

        monkeypatch.setattr(pdf_parser, "parse_pdf_chunk", explode)
        with ThreadPoolExecutor(max_workers=1) as pool:
            with pytest.raises(RuntimeError) as raised:
                pdf_parser._run_pdf_chunks([("doc.pdf", 1, 2)], pool, log=lambda _m: None)
        assert "page 3 is broken" in str(raised.value)
        assert "pages 1-2" in str(raised.value)


class TestBrokenPoolShutsDown:
    """A broken PDF pool has to end the process, not just flip a flag."""

    def test_broken_pool_requests_shutdown(self, monkeypatch, tmp_path):
        state = SimpleNamespace(
            shutdown_requested=False, pdf_executor_broken=False,
            pdf_executor=None, worker_count=1, docx_lock=None, docx_converter=None,
        )
        monkeypatch.setattr(daemon, "_STATE", state)
        monkeypatch.setattr(daemon, "_SERVER", None)

        def explode(*args, **kwargs):
            raise BrokenProcessPool("forced")

        monkeypatch.setattr(daemon, "parse_document", explode)
        pdf = tmp_path / "doc.pdf"
        pdf.write_bytes(b"%PDF-1.4")

        with pytest.raises(RuntimeError, match="PDF worker pool is broken"):
            daemon._run_parse(pdf, chunk=True, max_tokens=2000, min_structure_tokens=20000)

        assert state.pdf_executor_broken is True
        assert state.shutdown_requested is True


class TestPathBasedParseContract:
    """The daemon accepts shared-docs paths; legacy byte-upload helpers stay gone."""

    def test_resolve_parse_path_exists(self):
        assert hasattr(daemon, "resolve_parse_path")
        assert hasattr(daemon, "get_shared_docs_root")

    def test_legacy_byte_upload_helpers_are_gone(self):
        for name in ("_spool_upload", "_safe_suffix", "MAX_UPLOAD_BYTES", "MIN_GZIP_BYTES"):
            assert not hasattr(daemon, name), name

    def test_chunk_handler_is_gone(self):
        assert not hasattr(daemon.DoclingDaemonHandler, "_handle_chunk")

    def test_no_parse_output_dir_argument(self):
        parser_source = daemon.parse_args.__code__.co_consts
        assert not any(isinstance(c, str) and "parse-output-dir" in c for c in parser_source)


class TestShutdownDrain:
    """server_close() has to actually wait for an in-flight request.

    Regression: ``ThreadingHTTPServer`` sets ``daemon_threads = True``, and
    ``socketserver._Threads.append`` returns early for a daemon thread — so no request thread
    was ever tracked and ``server_close()``'s join joined an empty list. It returned in 0.00s
    with a conversion still running, which made the whole shutdown ordering in ``main()`` a
    no-op: the pool was torn down under the conversion, its queued page batches cancelled, and
    the process exited before the caller heard anything. Nothing covered this.
    """

    def _slow_handler(self, seconds, started, finished):
        class Slow(daemon.DoclingDaemonHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def do_GET(self):
                started.set()
                time.sleep(seconds)
                finished.set()
                body = b'{"ok":true}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        return Slow

    def test_the_server_does_not_use_daemon_threads(self):
        # The one attribute the whole drain depends on.
        assert daemon.DrainingHTTPServer.daemon_threads is False

    def test_server_close_waits_for_a_request_in_flight(self):
        import socket

        started = threading.Event()
        finished = threading.Event()
        server = daemon.DrainingHTTPServer(
            ("127.0.0.1", 0), self._slow_handler(2.0, started, finished)
        )
        threading.Thread(target=server.serve_forever, daemon=True).start()
        port = server.server_address[1]

        replies = []

        def call():
            sock = socket.create_connection(("127.0.0.1", port))
            sock.settimeout(15)
            sock.sendall(b"GET /health HTTP/1.1\r\nHost: x\r\n\r\n")
            try:
                replies.append(sock.recv(64))
            finally:
                sock.close()

        caller = threading.Thread(target=call)
        caller.start()
        # Wait for the handler to *start*, not merely to not have finished: without this the
        # shutdown could land before the request thread existed, leaving nothing to join and
        # making the assertion below pass or fail on timing rather than on behaviour.
        assert started.wait(10), "the request never reached the handler"
        assert not finished.is_set(), "the handler finished before the drain was tested"

        server.shutdown()
        started = time.perf_counter()
        server.server_close()
        waited = time.perf_counter() - started
        caller.join(15)

        assert finished.is_set(), "server_close returned before the handler finished"
        assert waited > 0.5, f"server_close returned in {waited:.2f}s without draining"
        assert replies and replies[0].startswith(b"HTTP/1.1 200"), replies


class TestParseFailureStatuses:
    """The three ways a parse can fail have to stay distinguishable to the caller.

    ``_run_parse`` answers ``BrokenProcessPool`` by flagging the pool *and* calling
    ``_request_shutdown()``, so ``shutdown_requested`` is set for a dead pool too. A
    shutdown-only check therefore reported an OOM-killed worker as "daemon shutting down
    mid-parse" — the wrong cause, and indistinguishable from a plain SIGTERM, while
    ``_get_health_status()`` still said ``pdf_pool_broken``. Nothing covered ``_handle_parse`` for
    any of these; ``TestBrokenPoolShutsDown`` calls ``_run_parse`` directly.
    """

    def _handler(self, path):
        handler = _FakeHandler(b"")
        del handler.headers["Content-Length"]
        handler.path = path
        return handler

    def _state(self, monkeypatch):
        state = SimpleNamespace(
            shutdown_requested=False,
            pdf_executor_broken=False,
            worker_count=1,
            parse_slots=threading.BoundedSemaphore(1),
        )
        state.is_ready = lambda: True
        monkeypatch.setattr(daemon, "_STATE", state)
        monkeypatch.setattr(daemon, "_SERVER", None)
        return state

    def _staged(self, monkeypatch, tmp_path):
        monkeypatch.setenv("IAP_SHARED_DOCS", str(tmp_path))
        monkeypatch.delenv(daemon.TOKEN_ENVIRONMENT_VARIABLE, raising=False)
        pdf = tmp_path / "doc.pdf"
        pdf.write_bytes(b"%PDF")
        return pdf

    def _run(self, monkeypatch, tmp_path, failure):
        state = self._state(monkeypatch)
        pdf = self._staged(monkeypatch, tmp_path)
        monkeypatch.setattr(daemon, "_run_parse", lambda *a, **k: failure(state))
        handler = self._handler(f"/parse?path={pdf}")
        daemon.DoclingDaemonHandler._handle_parse(handler)
        return handler

    def _broken_pool(self, state):
        # Exactly what _run_parse does for a BrokenProcessPool.
        state.pdf_executor_broken = True
        state.shutdown_requested = True
        raise RuntimeError("PDF worker pool is broken; restart the daemon")

    def _shutdown(self, state):
        state.shutdown_requested = True
        raise RuntimeError("batch cancelled")

    def _bad_document(self, _state):
        raise ValueError("invalid xref table")

    def test_a_broken_pool_is_a_500_naming_the_pool(self, monkeypatch, tmp_path):
        handler = self._run(monkeypatch, tmp_path, self._broken_pool)
        assert handler.header_value("status") == HTTPStatus.INTERNAL_SERVER_ERROR
        assert b"worker pool is broken" in handler.written
        assert b"shutting down mid-parse" not in handler.written

    def test_a_graceful_shutdown_is_a_retryable_503(self, monkeypatch, tmp_path):
        handler = self._run(monkeypatch, tmp_path, self._shutdown)
        assert handler.header_value("status") == HTTPStatus.SERVICE_UNAVAILABLE
        assert b"retry this document" in handler.written

    def test_a_bad_document_is_a_500_naming_the_document_error(self, monkeypatch, tmp_path):
        handler = self._run(monkeypatch, tmp_path, self._bad_document)
        assert handler.header_value("status") == HTTPStatus.INTERNAL_SERVER_ERROR
        assert b"invalid xref table" in handler.written

    def test_the_three_outcomes_are_not_conflated(self, monkeypatch, tmp_path):
        # The property that regressed: a dead pool and a SIGTERM must not look the same.
        broken = self._run(monkeypatch, tmp_path, self._broken_pool)
        shutdown = self._run(monkeypatch, tmp_path, self._shutdown)
        assert broken.header_value("status") != shutdown.header_value("status")


class TestAsyncParseAcceptance:
    """The callback-delivered /parse variant: validation, token gating, and the 202 answer."""

    def test_job_id_without_callback_is_rejected(self):
        with pytest.raises(daemon.ParseRequestError, match="sent together"):
            daemon.DoclingDaemonHandler._accept_async_parse(
                _FakeHandler(b""), "86a4c102", "", Path("doc.pdf"), True, {})

    def test_callback_without_job_id_is_rejected(self):
        with pytest.raises(daemon.ParseRequestError, match="sent together"):
            daemon.DoclingDaemonHandler._accept_async_parse(
                _FakeHandler(b""), "", "http://caller/cb", Path("doc.pdf"), True, {})

    def test_non_http_callback_is_rejected(self):
        with pytest.raises(daemon.ParseRequestError, match="http"):
            daemon.DoclingDaemonHandler._accept_async_parse(
                _FakeHandler(b""), "86a4c102", "ftp://caller/cb", Path("doc.pdf"), True, {})

    def test_overlong_job_id_is_rejected(self):
        with pytest.raises(daemon.ParseRequestError, match="long"):
            daemon.DoclingDaemonHandler._accept_async_parse(
                _FakeHandler(b""), "x" * 201, "http://caller/cb", Path("doc.pdf"), True, {})

    def test_missing_token_refuses_asynchronous_parsing(self, monkeypatch):
        monkeypatch.delenv(parse_callbacks.TOKEN_ENVIRONMENT_VARIABLE, raising=False)

        status, body = daemon.DoclingDaemonHandler._accept_async_parse(
            _FakeHandler(b""), "86a4c102", "http://caller/cb", Path("doc.pdf"), True, {})

        assert status == HTTPStatus.SERVICE_UNAVAILABLE
        assert parse_callbacks.TOKEN_ENVIRONMENT_VARIABLE in body["error"]

    def test_accepted_parse_answers_queued_and_runs_in_background(self, monkeypatch):
        monkeypatch.setenv(parse_callbacks.TOKEN_ENVIRONMENT_VARIABLE, "the-jwt")
        calls = []
        started = threading.Event()

        def record(job_id, callback_url, token, input_path, *, chunk, max_tokens,
                   min_structure_tokens):
            calls.append((job_id, callback_url, token, input_path, chunk, max_tokens,
                          min_structure_tokens))
            started.set()

        monkeypatch.setattr(daemon, "_parse_and_call_back", record)
        handler = _FakeHandler(b"")

        reply = daemon.DoclingDaemonHandler._accept_async_parse(
            handler, "86a4c102", "http://caller/cb", Path("doc.pdf"), False,
            {"max_tokens": 500})

        assert reply == (HTTPStatus.ACCEPTED, {"job_id": "86a4c102", "status": "queued"})
        assert started.wait(5), "the background parse never started"
        assert calls == [("86a4c102", "http://caller/cb", "the-jwt", Path("doc.pdf"), False,
                          500, daemon.DEFAULT_MIN_STRUCTURE_TOKENS)]
