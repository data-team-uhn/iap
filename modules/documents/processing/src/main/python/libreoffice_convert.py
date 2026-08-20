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

"""Headless LibreOffice conversions for the parsing pipeline.

Runs before Docling. Converted files are written beside the source immediately:

* ``.doc``  -> ``{stem}.docx`` then ``{stem}.pdf``
* ``.docx`` -> ``{stem}.pdf``

``soffice`` is resolved from ``IAP_LIBREOFFICE_SOFFICE``, else ``soffice`` on PATH.
"""

from __future__ import annotations

import os
import signal
import subprocess
import tempfile
from collections.abc import Callable
from pathlib import Path

import shared_docs

# Name the Writer PDF filter explicitly rather than passing a bare "pdf", so the export
# does not depend on which filter LibreOffice picks for the input it was handed.
_PDF_CONVERT_TO = "pdf:writer_pdf_Export"

# How long one soffice run may take before its process group is killed. Overridable because
# every other budget in the pipeline is (workers, batch pages, the input ceilings, the token
# budgets, the soffice binary itself), and a several-hundred-page DOCX render was the one thing
# that could not be given more time.
TIMEOUT_VARIABLE = "IAP_LIBREOFFICE_TIMEOUT_SECONDS"
# 300s: long enough for a several-hundred-page render (120s was not, and for a .doc the kill is
# a hard RuntimeError, so a document that passed admission was reported unparseable), and short
# enough to sit well inside the container's stop_grace_period — one soffice run must be killable
# long before Docker gives up on the whole container. Moves with DEFAULT_MAX_INPUT_BYTES.
DEFAULT_CONVERSION_TIMEOUT_SECONDS = 300


def conversion_timeout_seconds() -> float:
    """Seconds to allow one soffice run, from the environment or the default.

    Unlike the page and byte ceilings there is no "off" state here: a non-positive timeout
    would mean "kill soffice immediately", so it falls back to the default.
    """
    configured = shared_docs.positive_number_from_env(
        TIMEOUT_VARIABLE, DEFAULT_CONVERSION_TIMEOUT_SECONDS, float, "a number"
    )
    return configured if configured is not None else DEFAULT_CONVERSION_TIMEOUT_SECONDS


LogFn = Callable[[str], None]


def resolve_soffice_path() -> str:
    """Absolute or PATH-relative path to the LibreOffice executable."""
    configured = (os.environ.get("IAP_LIBREOFFICE_SOFFICE") or "").strip()
    return configured or "soffice"


# How long to wait for a SIGKILLed soffice group to be collected before giving up on the
# reap. Short on purpose: this runs holding the daemon's only parse slot.
REAP_TIMEOUT_SECONDS = 10


def _kill_group(process: subprocess.Popen) -> None:
    """Kill the converter and every process it started."""
    try:
        if os.name == "posix":
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            return
    except OSError:
        pass
    process.kill()


def _run_soffice(command: list[str], timeout: float) -> subprocess.CompletedProcess:
    """Run ``soffice``, killing the whole process group if it outlives ``timeout``.

    ``soffice`` is a launcher: it starts ``soffice.bin`` and can return before the real work
    is done. ``subprocess.run``'s timeout only kills the launcher, so the converter was left
    orphaned -- harmless for the CLI, but they accumulate in a long-lived daemon. A new
    session makes the whole tree killable at once.

    @param command: the soffice argv
    @param timeout: seconds to allow before killing the group
    @return: the finished process, as ``subprocess.run`` would report it
    @raise subprocess.TimeoutExpired: when the timeout was reached (group already killed)
    """
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        # POSIX only; on Windows there is no group to detach and kill() suffices.
        start_new_session=(os.name == "posix"),
    )
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        _kill_group(process)
        # Reap it, so the killed converter does not linger as a zombie -- but bounded. This runs
        # holding the daemon's only parse slot, so an unbounded wait on a child that is already
        # gone from the group, or wedged in uninterruptible state, would leave /parse answering
        # 503 busy forever while /health still reported ready. Giving up on the reap is the
        # lesser problem: the timeout below is raised either way, and PID 1's reaper
        # (`--init` / compose's `init: true`) collects the corpse.
        try:
            process.communicate(timeout=REAP_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            pass
        raise
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


def convert(input_path: Path, target_format: str, output_dir: Path | None = None) -> Path:
    """Convert ``input_path`` with headless LibreOffice.

    @param input_path: source file
    @param target_format: ``--convert-to`` argument (e.g. ``docx``, ``pdf:writer_pdf_Export``)
    @param output_dir: directory LibreOffice writes into; defaults to the source's parent
    @return: path to the converted file
    @raise FileNotFoundError: when the source is missing
    @raise RuntimeError: when soffice fails, times out, or does not produce the expected file
    """
    source = Path(input_path)
    if not source.is_file():
        raise FileNotFoundError(f"LibreOffice input does not exist: {source}")

    out_dir = Path(output_dir) if output_dir is not None else source.parent
    shared_docs.make_dirs(out_dir)

    extension = target_format.split(":", 1)[0]
    expected = out_dir / f"{source.stem}.{extension}"
    profile_dir = Path(tempfile.mkdtemp(prefix="iap-lo-profile-"))
    try:
        command = [
            resolve_soffice_path(),
            "--headless",
            f"-env:UserInstallation={profile_dir.as_uri()}",
            "--convert-to",
            target_format,
            "--outdir",
            str(out_dir.resolve()),
            str(source.resolve()),
        ]
        try:
            completed = _run_soffice(command, conversion_timeout_seconds())
        except FileNotFoundError as exc:
            raise RuntimeError(
                f"LibreOffice executable not found ({resolve_soffice_path()!r})"
            ) from exc
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(
                f"LibreOffice conversion timed out for '{source.name}'"
            ) from exc

        if completed.returncode != 0:
            detail = (completed.stdout or "") + (completed.stderr or "")
            raise RuntimeError(
                f"LibreOffice conversion failed (exit {completed.returncode}) for "
                f"'{source.name}': {detail.strip() or '(no output)'}"
            )
        if not shared_docs.path_is_file(expected):
            raise RuntimeError(
                "LibreOffice reported success but produced no output for "
                f"'{source.name}' (expected {expected.name})"
            )
        return expected
    finally:
        shared_docs.remove_tree(profile_dir, ignore_errors=True)


def _convert_sibling_pdf(source: Path, log: LogFn | None = None) -> None:
    """Best-effort sibling PDF for later bookmark extraction.

    Docling parses the ``.docx`` either way; a missing PDF only means
    ``toc_source`` falls back to the printed TOC (or none).
    """
    try:
        convert(source, _PDF_CONVERT_TO, source.parent)
    except (RuntimeError, FileNotFoundError, OSError) as exc:
        message = (
            f"LibreOffice PDF conversion failed for '{source.name}'; "
            f"continuing without sibling PDF (bookmarks unavailable): {exc}"
        )
        if log is not None:
            log(message)


def prepare_office_document(input_path: Path, *, log: LogFn | None = None) -> Path:
    """Run the LibreOffice prep step for a ``.doc`` / ``.docx`` and return the Docling input.

    Converted files are saved beside ``input_path`` immediately:

    * ``.doc``  -> ``{stem}.docx``, then a best-effort ``{stem}.pdf`` rendered *from that
      ``.docx``*; Docling receives the ``.docx``
    * ``.docx`` -> best-effort ``{stem}.pdf``; Docling receives the original ``.docx``
    * anything else is returned unchanged (no LibreOffice)

    Sibling PDF conversion is best-effort: failure is logged and ignored so Docling can still
    parse the Word document. ``.doc`` → ``.docx`` remains required.

    @param input_path: staged source file under the shared docs tree
    @param log: optional line logger for soft PDF failures
    @return: path Docling should convert (``.pdf`` or ``.docx``)
    """
    source = Path(input_path)
    suffix = source.suffix.lower()
    if suffix == ".doc":
        docx_path = convert(source, "docx", source.parent)
        # From the .docx, not the .doc: that is the document Docling parses, and the sibling
        # PDF is only there to supply bookmarks and page numbers for it. Rendering it from the
        # original went through a second, independent filter path, so the pagination the
        # bookmarks refer to was not guaranteed to be the pagination verify_bookmarks checks
        # them against.
        _convert_sibling_pdf(docx_path, log)
        return docx_path
    if suffix == ".docx":
        _convert_sibling_pdf(source, log)
        return source
    return source
