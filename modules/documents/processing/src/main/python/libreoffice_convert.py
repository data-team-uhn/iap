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
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Callable
from pathlib import Path

import shared_docs
from markdown_markers import INPUT_SUFFIXES

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

# The only options this module builds. Matched exactly, not by prefix: "--convert-to" as a
# prefix would also admit "--convert-to=pdf:evil", which is the shape an injected argument
# takes. The profile switch is the one exception, since its value is a generated temp URI.
FIXED_SWITCHES = frozenset({"--headless", "--convert-to", "--outdir"})
PROFILE_SWITCH_PREFIX = "-env:UserInstallation=file://"


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
    for argument in command[1:]:
        # No caller-derived value may reach soffice looking like an option. The document path
        # is absolute (``resolve()``), so it cannot today, and this is the check that keeps it
        # that way: a relative name such as "--convert-to" would otherwise be swallowed as a
        # switch rather than converted (CWE-88). The executable itself is operator
        # configuration, not request data, so it is not covered here.
        #
        # Inline rather than a helper on purpose: a barrier guard only sanitizes the true
        # branch in the same control-flow graph as the call it protects, the same reason the
        # path checks sit at their syscalls in shared_docs.
        if not argument.startswith("-"):
            # A value: a document path, an output directory, or a conversion target.
            continue
        if argument in FIXED_SWITCHES or argument.startswith(PROFILE_SWITCH_PREFIX):
            continue
        raise ValueError(f"refusing a soffice argument that reads as an option: {argument!r}")
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


# The name the document is staged under before soffice is called. soffice is only ever handed
# this constant plus its own switches, so nothing derived from the caller's ?path= reaches the
# command line at all -- which is what CodeQL's py/command-line-injection asks for, and what a
# check on the argv could not give it.
STAGED_INPUT_STEM = "document"


def _move_into_place(produced: Path, expected: Path) -> None:
    """Move the converted file out of the temp work directory to where the caller expects it.

    ``shutil.move`` rather than ``os.replace``: the work directory is under the system temp
    directory and the destination is on the shared docs volume, which are different
    filesystems, and ``os.replace`` cannot cross devices.

    The ``realpath`` check is inline here for the same reason the ones in ``shared_docs`` sit
    at their syscalls -- a barrier guard only sanitizes the true branch in the same control
    flow graph as the call it protects.

    @param produced: the file soffice wrote, inside the work directory
    @param expected: where it belongs, named after the caller's document
    """
    source = os.path.realpath(produced)
    destination = os.path.realpath(expected)
    if not source.startswith(os.path.splitdrive(source)[0] or os.sep):
        raise ValueError(f"invalid path: {produced}")
    if not destination.startswith(os.path.splitdrive(destination)[0] or os.sep):
        raise ValueError(f"invalid path: {expected}")
    shutil.move(source, destination)


def _discard_staging(staging_root: Path) -> None:
    """Remove the staging directory, and say so if any of it survives.

    Everything soffice is given or writes lives in here, including the copy of the caller's
    document, so nothing may outlive the call -- least of all when the conversion failed.
    ``ignore_errors`` on its own would leave that copy in the system temp directory and report
    nothing, so what is left is checked for and named. It is not raised: that would replace
    whatever went wrong in the conversion with a cleanup error.

    @param staging_root: the directory to remove
    """
    shutil.rmtree(staging_root, ignore_errors=True)
    if staging_root.exists():
        print(
            f"WARNING: could not remove the LibreOffice staging directory {staging_root}; "
            "it still holds a copy of the document",
            file=sys.stderr,
            flush=True,
        )


def _stage_for_soffice(source: Path, work_dir: Path) -> Path:
    """Put ``source`` inside ``work_dir`` under a name built only from literals.

    soffice picks its import filter from the extension, so the extension has to survive. It is
    taken from :data:`INPUT_SUFFIXES` rather than copied off the caller's filename, so the
    staged name is a constant either way.

    Always a copy, never a symlink. A symlink would be free, but ``os.symlink`` only works
    where the platform allows one: the container would take the link and every test would take
    the copy, leaving the branch that actually runs in production the one nothing exercises.

    It buys nothing else. soffice was measured not to touch the input in ``--convert-to``
    mode -- the source directory can be read-only and the file comes back with the same hash,
    mtime and inode -- so the copy is not protecting the caller's document from anything
    soffice does today.

    The cost is one copy per conversion, bounded by the input ceiling
    (:data:`shared_docs.BYTE_LIMIT_VARIABLE`, 64 MiB by default) and immaterial next to
    LibreOffice starting up. A ``.doc`` pays it twice, once per conversion.

    @param source: the document to stage, already resolved inside the shared docs volume
    @param work_dir: the temp directory to stage it into
    @return: the staged path, under ``work_dir``
    @raise RuntimeError: when the extension is not one this pipeline accepts
    """
    lowered = source.suffix.lower()
    suffix = next((allowed for allowed in INPUT_SUFFIXES if allowed == lowered), None)
    if suffix is None:
        raise RuntimeError(f"LibreOffice cannot convert '{source.name}': unsupported extension")
    staged = work_dir / (STAGED_INPUT_STEM + suffix)
    shutil.copyfile(source, staged)
    return staged


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
    # One directory for the whole run, created before the try so the finally always has
    # something to remove, and holding both subdirectories so there is no window in which a
    # second mkdtemp could fail and orphan the first.
    staging_root = Path(tempfile.mkdtemp(prefix="iap-lo-"))
    try:
        work_dir = staging_root / "work"
        profile_dir = staging_root / "profile"
        shared_docs.make_dirs(work_dir)
        staged = _stage_for_soffice(source, work_dir)
        command = [
            resolve_soffice_path(),
            "--headless",
            f"-env:UserInstallation={profile_dir.as_uri()}",
            "--convert-to",
            target_format,
            "--outdir",
            str(work_dir),
            str(staged),
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
        # soffice names its output after the staged input, so the result carries the staged
        # stem and is moved to the name the caller expects.
        produced = work_dir / f"{STAGED_INPUT_STEM}.{extension}"
        if not shared_docs.path_is_file(produced):
            raise RuntimeError(
                "LibreOffice reported success but produced no output for "
                f"'{source.name}' (expected {expected.name})"
            )
        _move_into_place(produced, expected)
        return expected
    finally:
        _discard_staging(staging_root)


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
