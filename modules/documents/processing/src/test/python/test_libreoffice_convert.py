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

"""Unit tests for LibreOffice prep — especially soft-fail sibling PDF conversion."""

from pathlib import Path

import pytest

import libreoffice_convert as lo


class TestPrepareOfficeDocument:
    def test_docx_pdf_failure_still_returns_docx(self, tmp_path, monkeypatch):
        docx = tmp_path / "report.docx"
        docx.write_bytes(b"pk")
        logs: list[str] = []

        def boom(input_path, target_format, output_dir=None):
            raise RuntimeError("javaldx: Could not find a Java Runtime Environment!")

        monkeypatch.setattr(lo, "convert", boom)
        result = lo.prepare_office_document(docx, log=logs.append)
        assert result == docx
        assert any("continuing without sibling PDF" in line for line in logs)

    def test_docx_pdf_success_still_returns_docx(self, tmp_path, monkeypatch):
        docx = tmp_path / "report.docx"
        docx.write_bytes(b"pk")
        calls: list[str] = []

        def fake_convert(input_path, target_format, output_dir=None):
            calls.append(target_format)
            out = Path(output_dir or input_path.parent) / f"{input_path.stem}.pdf"
            out.write_bytes(b"%PDF")
            return out

        monkeypatch.setattr(lo, "convert", fake_convert)
        assert lo.prepare_office_document(docx) == docx
        assert calls == [lo._PDF_CONVERT_TO]

    def test_doc_requires_docx_conversion(self, tmp_path, monkeypatch):
        source = tmp_path / "legacy.doc"
        source.write_bytes(b"doc")

        def boom(input_path, target_format, output_dir=None):
            raise RuntimeError("docx convert failed")

        monkeypatch.setattr(lo, "convert", boom)
        with pytest.raises(RuntimeError, match="docx convert failed"):
            lo.prepare_office_document(source)

    def test_the_doc_sibling_pdf_is_rendered_from_the_docx(self, tmp_path, monkeypatch):
        # Which *input* each conversion gets, not just which target format. The sibling PDF
        # supplies the bookmarks and page numbers for the document Docling parses, which is the
        # .docx — rendering it from the original .doc went through a second, independent filter
        # path, so the pagination the bookmarks referred to was not guaranteed to be the
        # pagination verify_bookmarks checks them against. No test distinguished the two.
        source = tmp_path / "legacy.doc"
        source.write_bytes(b"doc")
        docx = tmp_path / "legacy.docx"
        calls: list[tuple[str, str]] = []

        def fake_convert(input_path, target_format, output_dir=None):
            calls.append((Path(input_path).name, target_format))
            if target_format == "docx":
                docx.write_bytes(b"pk")
                return docx
            out = Path(output_dir or Path(input_path).parent) / f"{Path(input_path).stem}.pdf"
            out.write_bytes(b"%PDF")
            return out

        monkeypatch.setattr(lo, "convert", fake_convert)
        assert lo.prepare_office_document(source) == docx
        assert calls == [("legacy.doc", "docx"), ("legacy.docx", lo._PDF_CONVERT_TO)], calls

    def test_the_docx_sibling_pdf_comes_from_the_docx_itself(self, tmp_path, monkeypatch):
        docx = tmp_path / "report.docx"
        docx.write_bytes(b"pk")
        calls: list[tuple[str, str]] = []

        def fake_convert(input_path, target_format, output_dir=None):
            calls.append((Path(input_path).name, target_format))
            out = Path(output_dir or Path(input_path).parent) / f"{Path(input_path).stem}.pdf"
            out.write_bytes(b"%PDF")
            return out

        monkeypatch.setattr(lo, "convert", fake_convert)
        assert lo.prepare_office_document(docx) == docx
        assert calls == [("report.docx", lo._PDF_CONVERT_TO)]

    def test_doc_pdf_failure_still_returns_docx(self, tmp_path, monkeypatch):
        source = tmp_path / "legacy.doc"
        source.write_bytes(b"doc")
        docx = tmp_path / "legacy.docx"
        logs: list[str] = []

        def fake_convert(input_path, target_format, output_dir=None):
            if target_format == "docx":
                docx.write_bytes(b"pk")
                return docx
            raise RuntimeError("pdf export failed")

        monkeypatch.setattr(lo, "convert", fake_convert)
        assert lo.prepare_office_document(source, log=logs.append) == docx
        assert any("continuing without sibling PDF" in line for line in logs)

    def test_pdf_passthrough(self, tmp_path):
        pdf = tmp_path / "native.pdf"
        pdf.write_bytes(b"%PDF")
        assert lo.prepare_office_document(pdf) == pdf


class TestConvertRequiresOutput:
    """soffice exits 0 having converted nothing often enough to be worth checking for.

    Nothing tries to tell a fresh output file from an older one at that path, because there
    is never an older one: each parse is staged in a directory of its own.
    """

    def _soffice(self, monkeypatch, *, writes):
        import subprocess

        def fake_run(command, timeout):
            if writes:
                outdir = Path(command[command.index("--outdir") + 1])
                source = Path(command[-1])
                (outdir / f"{source.stem}.docx").write_bytes(b"FRESH")
            return subprocess.CompletedProcess(command, 0, "", "")

        monkeypatch.setattr(lo, "_run_soffice", fake_run)

    def test_a_silent_failure_is_still_an_error(self, monkeypatch, tmp_path):
        source = tmp_path / "legacy.doc"
        source.write_bytes(b"doc")
        self._soffice(monkeypatch, writes=False)

        with pytest.raises(RuntimeError, match="produced no output"):
            lo.convert(source, "docx", tmp_path)

    def test_a_real_conversion_returns_its_output(self, monkeypatch, tmp_path):
        source = tmp_path / "legacy.doc"
        source.write_bytes(b"doc")
        self._soffice(monkeypatch, writes=True)

        produced = lo.convert(source, "docx", tmp_path)
        assert produced == tmp_path / "legacy.docx"
        assert produced.read_bytes() == b"FRESH"

    def test_the_profile_directory_is_always_cleaned_up(self, monkeypatch, tmp_path):
        source = tmp_path / "legacy.doc"
        source.write_bytes(b"doc")
        self._soffice(monkeypatch, writes=False)

        with pytest.raises(RuntimeError):
            lo.convert(source, "docx", tmp_path)

        assert [p.name for p in tmp_path.iterdir() if p.name.startswith("iap-lo-")] == []


class TestSofficeTimeout:
    """A conversion that outstays its welcome takes its children with it.

    ``soffice`` is a launcher that starts ``soffice.bin``, and ``subprocess.run``'s timeout
    only kills the launcher, so the real converter was orphaned. Harmless for the CLI; in a
    long-lived daemon they accumulate.
    """

    def test_a_timeout_kills_the_child_and_raises(self):
        import subprocess
        import sys
        import time

        started = time.perf_counter()
        with pytest.raises(subprocess.TimeoutExpired):
            lo._run_soffice([sys.executable, "-c", "import time; time.sleep(30)"], 1.0)
        # The child was killed and reaped rather than waited out.
        assert time.perf_counter() - started < 10

    def test_a_normal_run_reports_output_and_status(self):
        import sys

        done = lo._run_soffice(
            [sys.executable, "-c", "print('hello'); raise SystemExit(3)"], 30
        )
        assert done.returncode == 3
        assert "hello" in done.stdout

    def test_the_timeout_becomes_a_runtime_error(self, monkeypatch, tmp_path):
        import subprocess

        source = tmp_path / "legacy.doc"
        source.write_bytes(b"doc")

        def slow(command, timeout):
            raise subprocess.TimeoutExpired(command, timeout)

        monkeypatch.setattr(lo, "_run_soffice", slow)
        with pytest.raises(RuntimeError, match="timed out"):
            lo.convert(source, "docx", tmp_path)


class TestReapIsBounded:
    """The wait after the kill cannot hang the daemon.

    The kill is followed by a communicate() to collect the corpse, and that used to be
    unbounded. It runs holding the daemon's only parse slot, so a child already gone from the
    group — or wedged uninterruptible — left /parse answering 503 busy forever while /health
    still reported ready.
    """

    def test_it_gives_up_on_a_child_that_survives_the_kill(self, monkeypatch):
        import subprocess
        import sys
        import time

        # Neuter the kill so the child outlives it, standing in for a wedged soffice.bin.
        monkeypatch.setattr(lo, "_kill_group", lambda process: None)
        monkeypatch.setattr(lo, "REAP_TIMEOUT_SECONDS", 2)

        started = time.perf_counter()
        with pytest.raises(subprocess.TimeoutExpired):
            lo._run_soffice([sys.executable, "-c", "import time; time.sleep(60)"], 1.0)
        elapsed = time.perf_counter() - started
        # The timeout still surfaces, and the reap does not wait out the child's 60s.
        assert elapsed < 15, f"post-kill wait took {elapsed:.1f}s"

    def test_a_killable_child_is_still_reaped(self):
        import subprocess
        import sys

        with pytest.raises(subprocess.TimeoutExpired):
            lo._run_soffice([sys.executable, "-c", "import time; time.sleep(60)"], 1.0)
