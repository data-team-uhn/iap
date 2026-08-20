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


def _fake_soffice_argv(tmp_path, body: str) -> list[str]:
    """A stand-in for the soffice argv: the interpreter plus an absolute script path.

    Deliberately not ``python -c``: :func:`_run_soffice` refuses an argument that reads as an
    option, which is the check that keeps a caller-derived filename from becoming a switch, so
    a fake converter has to be invoked the way soffice itself is.
    """
    import sys

    script = tmp_path / "fake_soffice.py"
    script.write_text(body, encoding="utf-8")
    return [sys.executable, str(script)]


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

    def test_a_timeout_kills_the_child_and_raises(self, tmp_path):
        import subprocess
        import time

        started = time.perf_counter()
        with pytest.raises(subprocess.TimeoutExpired):
            lo._run_soffice(_fake_soffice_argv(tmp_path, "import time; time.sleep(30)"), 1.0)
        # The child was killed and reaped rather than waited out.
        assert time.perf_counter() - started < 10

    def test_a_normal_run_reports_output_and_status(self, tmp_path):
        done = lo._run_soffice(
            _fake_soffice_argv(tmp_path, "print('hello'); raise SystemExit(3)"), 30
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


class TestArgumentsCannotBecomeSwitches:
    """A value handed to soffice must not be readable as an option (CWE-88).

    The document path reaches the argv from the caller's ``?path=`` query. It is absolute
    because ``convert`` resolves it, and an absolute path can never be parsed as a switch --
    but nothing said so where the process is started, and a relative name like "--convert-to"
    would be swallowed as an option instead of converted.
    """

    def test_the_real_argv_is_accepted(self, tmp_path, monkeypatch):
        # Held before the stub replaces the module attribute, so the guard below is the real
        # one; calling lo._run_soffice here would just call the stub again.
        real_run_soffice = lo._run_soffice
        source = tmp_path / "report.docx"
        source.write_bytes(b"pk")
        seen = {}

        def record(command, timeout):
            import subprocess

            seen["command"] = command
            # Where soffice would write: into --outdir, named after the staged input.
            staged = Path(command[-1])
            (staged.parent / f"{lo.STAGED_INPUT_STEM}.pdf").write_bytes(b"%PDF")
            return subprocess.CompletedProcess(command, 0, "", "")

        monkeypatch.setattr(lo, "_run_soffice", record)
        lo.convert(source, "pdf", tmp_path)
        # What convert() builds passes the guard the real _run_soffice applies.
        real_run_soffice(
            _fake_soffice_argv(tmp_path, "raise SystemExit(0)") + seen["command"][1:], 30
        )

    @pytest.mark.parametrize("injected", [
        "--convert-to=pdf:evil",          # the prefix form an exact match has to refuse
        "-outdir=/etc",
        "--headlessly",
        "--nolockcheck",
        "-h",
    ])
    def test_an_option_like_argument_is_refused(self, tmp_path, injected):
        argv = _fake_soffice_argv(tmp_path, "raise SystemExit(0)")
        with pytest.raises(ValueError, match="reads as an option"):
            lo._run_soffice([*argv, injected], 30)

    def test_the_switches_convert_builds_are_allowed(self, tmp_path):
        argv = _fake_soffice_argv(tmp_path, "raise SystemExit(0)")
        done = lo._run_soffice(
            [
                *argv,
                "--headless",
                f"-env:UserInstallation={tmp_path.as_uri()}",
                "--convert-to",
                "pdf:writer_pdf_Export",
                "--outdir",
                str(tmp_path),
            ],
            30,
        )
        assert done.returncode == 0

    def test_a_plain_value_is_allowed(self, tmp_path):
        # Conversion targets and paths carry no leading dash, so they are values, not options.
        argv = _fake_soffice_argv(tmp_path, "raise SystemExit(0)")
        assert lo._run_soffice([*argv, "docx", str(tmp_path / "a.docx")], 30).returncode == 0


class TestReapIsBounded:
    """The wait after the kill cannot hang the daemon.

    The kill is followed by a communicate() to collect the corpse, and that used to be
    unbounded. It runs holding the daemon's only parse slot, so a child already gone from the
    group — or wedged uninterruptible — left /parse answering 503 busy forever while /health
    still reported ready.
    """

    def test_it_gives_up_on_a_child_that_survives_the_kill(self, monkeypatch, tmp_path):
        import subprocess
        import time

        # Neuter the kill so the child outlives it, standing in for a wedged soffice.bin.
        monkeypatch.setattr(lo, "_kill_group", lambda process: None)
        monkeypatch.setattr(lo, "REAP_TIMEOUT_SECONDS", 2)

        started = time.perf_counter()
        with pytest.raises(subprocess.TimeoutExpired):
            lo._run_soffice(_fake_soffice_argv(tmp_path, "import time; time.sleep(60)"), 1.0)
        elapsed = time.perf_counter() - started
        # The timeout still surfaces, and the reap does not wait out the child's 60s.
        assert elapsed < 15, f"post-kill wait took {elapsed:.1f}s"

    def test_a_killable_child_is_still_reaped(self, tmp_path):
        import subprocess

        with pytest.raises(subprocess.TimeoutExpired):
            lo._run_soffice(_fake_soffice_argv(tmp_path, "import time; time.sleep(60)"), 1.0)


class TestNothingCallerDerivedReachesTheArgv:
    """soffice is handed constants and temp paths, never the caller's filename.

    The document path arrives from the caller's ``?path=`` query, and CodeQL's
    py/command-line-injection follows it to the ``Popen``. A check on the argv did not satisfy
    it -- and a check is weaker anyway. The document is staged under a fixed name instead, so
    there is no caller-derived string on the command line to reason about.
    """

    def _run(self, tmp_path, monkeypatch, name, target="pdf"):
        source = tmp_path / name
        source.write_bytes(b"pk")
        seen = {}

        def record(command, timeout):
            import subprocess

            seen["command"] = command
            staged = Path(command[-1])
            extension = target.split(":", 1)[0]
            (staged.parent / f"{lo.STAGED_INPUT_STEM}.{extension}").write_bytes(b"out")
            return subprocess.CompletedProcess(command, 0, "", "")

        monkeypatch.setattr(lo, "_run_soffice", record)
        produced = lo.convert(source, target, tmp_path)
        return seen["command"], produced

    def test_the_document_stem_is_absent_from_the_argv(self, tmp_path, monkeypatch):
        command, _ = self._run(tmp_path, monkeypatch, "Secret Board Minutes 2026.docx")
        joined = " ".join(command)
        assert "Secret Board Minutes 2026" not in joined
        assert lo.STAGED_INPUT_STEM in joined

    def test_the_shared_docs_directory_is_absent_from_the_argv(self, tmp_path, monkeypatch):
        # --outdir used to be the document's own directory, which is caller-derived too.
        command, _ = self._run(tmp_path, monkeypatch, "report.docx")
        assert str(tmp_path) not in " ".join(command)

    def test_the_output_still_lands_under_the_callers_name(self, tmp_path, monkeypatch):
        _, produced = self._run(tmp_path, monkeypatch, "report.docx")
        assert produced == tmp_path / "report.pdf"
        assert produced.is_file()

    def test_the_extension_comes_from_the_allowlist(self, tmp_path, monkeypatch):
        command, _ = self._run(tmp_path, monkeypatch, "report.DOCX")
        # Normalised to the literal from INPUT_SUFFIXES, not the caller's spelling.
        assert command[-1].endswith(f"{lo.STAGED_INPUT_STEM}.docx")

    def test_an_unsupported_extension_is_refused(self, tmp_path):
        source = tmp_path / "notes.rtf"
        source.write_bytes(b"rtf")
        with pytest.raises(RuntimeError, match="unsupported extension"):
            lo.convert(source, "pdf", tmp_path)

    def test_staging_is_always_a_copy(self, tmp_path):
        # Never a symlink, even where the platform would allow one: otherwise the container
        # takes the link path and every test takes the copy path, so the branch that runs in
        # production is the one nothing exercises.
        source = tmp_path / "report.docx"
        source.write_bytes(b"pk-content")
        work = tmp_path / "work"
        work.mkdir()

        staged = lo._stage_for_soffice(source, work)
        assert staged.read_bytes() == b"pk-content"
        assert not staged.is_symlink()
        assert staged.parent == work

    def test_the_caller_document_is_left_alone(self, tmp_path, monkeypatch):
        # soffice is handed the copy, so nothing it does can reach the original.
        source = tmp_path / "report.docx"
        source.write_bytes(b"pk-content")
        work = tmp_path / "work"
        work.mkdir()

        staged = lo._stage_for_soffice(source, work)
        staged.write_bytes(b"soffice scribbled here")
        assert source.read_bytes() == b"pk-content"

    def test_the_work_directory_goes_even_when_the_conversion_fails(self, tmp_path, monkeypatch):
        # The finally in convert(): a crashed soffice leaves its lock and temp files in the
        # work directory, and the work directory does not outlive the call.
        source = tmp_path / "report.docx"
        source.write_bytes(b"pk")
        seen = {}

        def crash(command, timeout):
            import subprocess

            seen["work_dir"] = Path(command[-1]).parent
            (seen["work_dir"] / ".~lock.document.docx#").write_bytes(b"lock")
            return subprocess.CompletedProcess(command, 77, "", "boom")

        monkeypatch.setattr(lo, "_run_soffice", crash)
        with pytest.raises(RuntimeError, match="exit 77"):
            lo.convert(source, "pdf", tmp_path)

        assert not seen["work_dir"].exists()
        assert list(tmp_path.glob(".~lock*")) == []

    def test_the_work_directory_is_cleaned_up(self, tmp_path, monkeypatch):
        command, _ = self._run(tmp_path, monkeypatch, "report.docx")
        work_dir = Path(command[-1]).parent
        assert not work_dir.exists()


class TestNothingIsLeftInTemp:
    """However a conversion ends, no staging directory outlives the call.

    Everything soffice touches lives under one ``iap-lo-*`` directory in the system temp
    directory, including the copy of the caller's document. A failed conversion must not leave
    that copy behind, so this watches the real temp directory rather than the staged paths.
    """

    def _temp_entries(self):
        import tempfile

        return set(Path(tempfile.gettempdir()).glob("iap-lo-*"))

    def _convert_expecting_failure(self, tmp_path, monkeypatch, run_soffice, match):
        source = tmp_path / "report.docx"
        source.write_bytes(b"pk")
        monkeypatch.setattr(lo, "_run_soffice", run_soffice)
        before = self._temp_entries()
        with pytest.raises(RuntimeError, match=match):
            lo.convert(source, "pdf", tmp_path)
        assert self._temp_entries() == before, "a staging directory survived the failure"

    def test_nothing_survives_a_nonzero_exit(self, tmp_path, monkeypatch):
        def failing(command, timeout):
            import subprocess

            # soffice leaves its own droppings behind before dying.
            work = Path(command[-1]).parent
            (work / ".~lock.document.docx#").write_bytes(b"lock")
            (work / "half-written.pdf").write_bytes(b"partial")
            return subprocess.CompletedProcess(command, 77, "", "boom")

        self._convert_expecting_failure(tmp_path, monkeypatch, failing, "exit 77")

    def test_nothing_survives_a_timeout(self, tmp_path, monkeypatch):
        def timing_out(command, timeout):
            import subprocess

            (Path(command[-1]).parent / "wedged.tmp").write_bytes(b"x")
            raise subprocess.TimeoutExpired(command, timeout)

        self._convert_expecting_failure(tmp_path, monkeypatch, timing_out, "timed out")

    def test_nothing_survives_a_missing_soffice(self, tmp_path, monkeypatch):
        def missing(command, timeout):
            raise FileNotFoundError(command[0])

        self._convert_expecting_failure(tmp_path, monkeypatch, missing, "not found")

    def test_nothing_survives_a_silent_failure(self, tmp_path, monkeypatch):
        def produces_nothing(command, timeout):
            import subprocess

            return subprocess.CompletedProcess(command, 0, "", "")

        self._convert_expecting_failure(
            tmp_path, monkeypatch, produces_nothing, "produced no output"
        )

    def test_nothing_survives_an_unsupported_extension(self, tmp_path):
        source = tmp_path / "notes.rtf"
        source.write_bytes(b"rtf")
        before = self._temp_entries()
        with pytest.raises(RuntimeError, match="unsupported extension"):
            lo.convert(source, "pdf", tmp_path)
        assert self._temp_entries() == before

    def test_nothing_survives_a_successful_conversion_either(self, tmp_path, monkeypatch):
        def works(command, timeout):
            import subprocess

            (Path(command[-1]).parent / f"{lo.STAGED_INPUT_STEM}.pdf").write_bytes(b"%PDF")
            return subprocess.CompletedProcess(command, 0, "", "")

        source = tmp_path / "report.docx"
        source.write_bytes(b"pk")
        monkeypatch.setattr(lo, "_run_soffice", works)
        before = self._temp_entries()
        lo.convert(source, "pdf", tmp_path)
        assert self._temp_entries() == before

    def test_a_cleanup_that_cannot_finish_is_reported(self, tmp_path, monkeypatch, capsys):
        # Silence here would leave a copy of the document in temp with nothing said about it.
        # lo.shutil is the shutil module itself, so this neuters rmtree everywhere; the real
        # one is held first, for this test to clean up with once it is done.
        real_rmtree = lo.shutil.rmtree
        monkeypatch.setattr(lo.shutil, "rmtree", lambda *a, **k: None)

        def works(command, timeout):
            import subprocess

            (Path(command[-1]).parent / f"{lo.STAGED_INPUT_STEM}.pdf").write_bytes(b"%PDF")
            return subprocess.CompletedProcess(command, 0, "", "")

        source = tmp_path / "report.docx"
        source.write_bytes(b"pk")
        monkeypatch.setattr(lo, "_run_soffice", works)
        lo.convert(source, "pdf", tmp_path)

        warning = capsys.readouterr().err
        assert "could not remove the LibreOffice staging directory" in warning
        assert "still holds a copy of the document" in warning
        # Left behind by the stubbed rmtree, so clean it up with the real one rather than
        # leaking a copy of the document out of the test.
        import tempfile

        for leftover in Path(tempfile.gettempdir()).glob("iap-lo-*"):
            real_rmtree(leftover, ignore_errors=True)


class TestSiblingPdfFallsBackThroughOdt:
    """When the direct Word-to-PDF export fails, try again through ODT.

    The sibling PDF is what supplies bookmarks and page numbers, so losing it costs
    ``toc_source``. Some documents the import filter reads fine cannot be exported straight to
    PDF, and re-writing them as ODT in between is often enough for the export to work.
    """

    def _soffice(self, tmp_path, monkeypatch, fails):
        """Stub soffice, failing the conversions named in ``fails`` by target format."""
        attempts = []

        def run(command, timeout):
            import subprocess

            target = command[command.index("--convert-to") + 1]
            attempts.append(target)
            if target in fails:
                return subprocess.CompletedProcess(command, 1, "", f"cannot export {target}")
            staged = Path(command[-1])
            extension = target.split(":", 1)[0]
            (staged.parent / f"{lo.STAGED_INPUT_STEM}.{extension}").write_bytes(b"out")
            return subprocess.CompletedProcess(command, 0, "", "")

        monkeypatch.setattr(lo, "_run_soffice", run)
        source = tmp_path / "report.docx"
        source.write_bytes(b"pk")
        return source, attempts

    def _temp_entries(self):
        import tempfile

        return set(Path(tempfile.gettempdir()).glob("iap-lo-*"))

    def test_a_working_direct_export_does_not_use_the_fallback(self, tmp_path, monkeypatch):
        source, attempts = self._soffice(tmp_path, monkeypatch, fails=())
        lo._convert_sibling_pdf(source)
        assert attempts == [lo._PDF_CONVERT_TO]
        assert (tmp_path / "report.pdf").is_file()

    def test_a_failed_direct_export_retries_through_odt(self, tmp_path, monkeypatch):
        source, attempts = self._soffice(tmp_path, monkeypatch, fails=(lo._PDF_CONVERT_TO,))
        logged = []

        # The PDF leg fails only when asked for it directly; from the ODT it succeeds.
        def run(command, timeout):
            import subprocess

            target = command[command.index("--convert-to") + 1]
            attempts.append(target)
            staged = Path(command[-1])
            if target == lo._PDF_CONVERT_TO and staged.suffix == ".docx":
                return subprocess.CompletedProcess(command, 1, "", "cannot export")
            extension = target.split(":", 1)[0]
            (staged.parent / f"{lo.STAGED_INPUT_STEM}.{extension}").write_bytes(b"out")
            return subprocess.CompletedProcess(command, 0, "", "")

        monkeypatch.setattr(lo, "_run_soffice", run)
        attempts.clear()
        lo._convert_sibling_pdf(source, logged.append)

        assert attempts == [lo._PDF_CONVERT_TO, lo._ODT_CONVERT_TO, lo._PDF_CONVERT_TO]
        assert (tmp_path / "report.pdf").is_file(), "the fallback did not produce the PDF"
        assert any("retrying through ODT" in line for line in logged)

    def test_the_intermediate_odt_is_not_left_beside_the_document(self, tmp_path, monkeypatch):
        source, attempts = self._soffice(tmp_path, monkeypatch, fails=())

        def run(command, timeout):
            import subprocess

            target = command[command.index("--convert-to") + 1]
            staged = Path(command[-1])
            if target == lo._PDF_CONVERT_TO and staged.suffix == ".docx":
                return subprocess.CompletedProcess(command, 1, "", "cannot export")
            extension = target.split(":", 1)[0]
            (staged.parent / f"{lo.STAGED_INPUT_STEM}.{extension}").write_bytes(b"out")
            return subprocess.CompletedProcess(command, 0, "", "")

        monkeypatch.setattr(lo, "_run_soffice", run)
        before = self._temp_entries()
        lo._convert_sibling_pdf(source)

        assert list(tmp_path.glob("*.odt")) == [], "the ODT was left in the shared docs tree"
        assert self._temp_entries() == before, "the ODT staging directory survived"

    def test_both_routes_failing_stays_soft(self, tmp_path, monkeypatch):
        source, attempts = self._soffice(
            tmp_path, monkeypatch, fails=(lo._PDF_CONVERT_TO, lo._ODT_CONVERT_TO)
        )
        logged = []
        before = self._temp_entries()

        # No exception: Docling still parses the Word document, just without bookmarks.
        lo._convert_sibling_pdf(source, logged.append)

        assert not (tmp_path / "report.pdf").exists()
        assert any("both directly and through ODT" in line for line in logged)
        assert any("bookmarks unavailable" in line for line in logged)
        assert self._temp_entries() == before

    def test_a_doc_still_renders_its_pdf_from_the_docx(self, tmp_path, monkeypatch):
        # The pagination the bookmarks refer to has to be the .docx's, so both attempts start
        # from the converted .docx rather than the original .doc.
        staged_inputs = []

        def run(command, timeout):
            import subprocess

            target = command[command.index("--convert-to") + 1]
            staged = Path(command[-1])
            staged_inputs.append((target, staged.suffix))
            if target == lo._PDF_CONVERT_TO and staged.suffix == ".docx":
                return subprocess.CompletedProcess(command, 1, "", "cannot export")
            extension = target.split(":", 1)[0]
            (staged.parent / f"{lo.STAGED_INPUT_STEM}.{extension}").write_bytes(b"out")
            return subprocess.CompletedProcess(command, 0, "", "")

        monkeypatch.setattr(lo, "_run_soffice", run)
        source = tmp_path / "legacy.doc"
        source.write_bytes(b"doc")
        assert lo.prepare_office_document(source) == tmp_path / "legacy.docx"
        # .doc -> .docx, then .docx -> pdf (fails), then .docx -> odt, then .odt -> pdf.
        assert staged_inputs == [
            ("docx", ".doc"),
            (lo._PDF_CONVERT_TO, ".docx"),
            (lo._ODT_CONVERT_TO, ".docx"),
            (lo._PDF_CONVERT_TO, ".odt"),
        ]
