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
body draining, health reporting, and the batch-abandon path that runs when a page batch fails.
"""

import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures.process import BrokenProcessPool
from http import HTTPStatus
from types import SimpleNamespace
from urllib.parse import urlencode

import pytest

pytest.importorskip("docling", reason="docling not installed; conversion plumbing skipped")

import docling_daemon as daemon  # noqa: E402 -- must follow the importorskip guard
import docling_pdf_parser as pdf_parser  # noqa: E402
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


class TestDrainRequestBody:
    """Leftover request bodies must be drained or the connection closed."""

    def test_drains_the_whole_body(self):
        handler = _FakeHandler(b"x" * 5000)
        daemon._drain_request_body(handler)
        assert handler.unread == 0
        assert handler.close_connection is False

    def test_no_content_length_is_a_noop(self):
        handler = _FakeHandler(b"abc")
        del handler.headers["Content-Length"]
        daemon._drain_request_body(handler)
        assert handler.unread == 3
        assert handler.close_connection is False

    def test_bad_content_length_closes_the_connection(self):
        handler = _FakeHandler(b"abc", headers={"Content-Length": "not-a-number"})
        daemon._drain_request_body(handler)
        assert handler.close_connection is True

    def test_negative_content_length_closes_the_connection(self):
        handler = _FakeHandler(b"abc", headers={"Content-Length": "-5"})
        daemon._drain_request_body(handler)
        assert handler.unread == 3
        assert handler.close_connection is True

    def test_chunked_body_closes_the_connection(self):
        handler = _FakeHandler(b"3\r\nabc\r\n0\r\n\r\n")
        handler.headers["Transfer-Encoding"] = "chunked"
        daemon._drain_request_body(handler)
        assert handler.close_connection is True

    def test_stops_at_a_short_body(self):
        handler = _FakeHandler(b"abc", content_length=10_000)
        daemon._drain_request_body(handler)
        assert handler.unread == 0
        assert handler.close_connection is True

    def test_oversized_declared_length_closes_the_connection(self):
        handler = _FakeHandler(b"x" * 100, content_length=2 * 1024 * 1024)
        daemon._drain_request_body(handler)
        assert handler.unread == 100
        assert handler.close_connection is True


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
        assert daemon._health_status() == "ok"

    def test_broken_pool_says_so_rather_than_shutting_down(self, monkeypatch):
        monkeypatch.setattr(daemon, "_STATE", self._state(pdf_executor_broken=True))
        assert daemon._health_status() == "pdf_pool_broken"

    def test_shutdown_still_says_shutting_down(self, monkeypatch):
        monkeypatch.setattr(daemon, "_STATE", self._state(shutdown_requested=True))
        assert daemon._health_status() == "shutting_down"

    def test_broken_pool_wins_over_shutdown(self, monkeypatch):
        monkeypatch.setattr(
            daemon, "_STATE", self._state(pdf_executor_broken=True, shutdown_requested=True)
        )
        assert daemon._health_status() == "pdf_pool_broken"

    def test_no_state_yet_says_starting(self, monkeypatch):
        monkeypatch.setattr(daemon, "_STATE", None)
        assert daemon._health_status() == "starting"


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
            with pytest.raises(RuntimeError, match="One or more page batches failed"):
                pdf_parser._run_pdf_chunks([("doc.pdf", 1, 2)], pool, log=lambda _m: None)


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
        assert hasattr(daemon, "shared_docs_root")

    def test_legacy_byte_upload_helpers_are_gone(self):
        for name in ("_spool_upload", "_safe_suffix", "MAX_UPLOAD_BYTES", "MIN_GZIP_BYTES"):
            assert not hasattr(daemon, name), name

    def test_chunk_handler_is_gone(self):
        assert not hasattr(daemon.DoclingDaemonHandler, "_handle_chunk")

    def test_no_parse_output_dir_argument(self):
        parser_source = daemon.parse_args.__code__.co_consts
        assert not any(isinstance(c, str) and "parse-output-dir" in c for c in parser_source)
