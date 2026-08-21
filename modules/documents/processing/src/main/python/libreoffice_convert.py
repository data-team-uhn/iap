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
_ODT_CONVERT_TO = "odt"

# What soffice may be handed
STAGEABLE_SUFFIXES = (*INPUT_SUFFIXES, ".odt")

# How long one soffice run may take before its process group is killed
TIMEOUT_VARIABLE = "IAP_LIBREOFFICE_TIMEOUT_SECONDS"
DEFAULT_CONVERSION_TIMEOUT_SECONDS = 300


def get_conversion_timeout_seconds() -> float:
    """Seconds to allow one soffice run, from the environment or the default.

    Unlike the page and byte ceilings there is no "off" state here: a non-positive timeout
    would mean "kill soffice immediately", so it falls back to the default.
    """
    configured = shared_docs.read_positive_number_from_env(
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

# The only option-looking arguments soffice may be handed. Checked in :func:`_run_soffice` so
# nothing derived from a caller's filename can arrive as a switch. Match FIXED_SWITCHES
# exactly, never by prefix: "--convert-to=pdf:evil" passes a prefix test.
FIXED_SWITCHES = frozenset({"--headless", "--convert-to", "--outdir"})
PROFILE_SWITCH_PREFIX = "-env:UserInstallation=file://"


def _kill_group(process: subprocess.Popen) -> None:
    """Kill the converter and every process it started.

    The group is the point: soffice is a launcher, so killing only the process it started
    leaves the real converter running (see :func:`_run_soffice`).
    """
    try:
        if os.name == "posix":
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            return
    except OSError:
        # No group to signal: getpgid raises once the launcher is reaped, and killpg raises
        # if the group is gone. Either way the single-process kill below is the fallback, and
        # it tolerates an already-dead child -- Popen.kill suppresses ProcessLookupError.
        pass
    process.kill()


def _run_soffice(command: list[str], timeout: float) -> subprocess.CompletedProcess:
    """Run ``soffice``, killing the whole process group if it outlives ``timeout``.

    @param command: the soffice argv
    @param timeout: seconds to allow before killing the group
    @return: the finished process, as ``subprocess.run`` would report it
    @raise subprocess.TimeoutExpired: when the timeout was reached (group already killed)
    """
    for argument in command[1:]:
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
        try:
            process.communicate(timeout=REAP_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            pass
        raise
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


# The name the document is staged under before soffice is called
STAGED_INPUT_STEM = "document"


def _move_into_place(produced: Path, expected: Path) -> None:
    """Move the converted file out of the temp work directory to where the caller expects it.

    ``shutil.move`` rather than ``os.replace``: the work directory is under the system temp
    directory and the destination is on the shared docs volume, which are different
    filesystems, and ``os.replace`` cannot cross devices.

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
    taken from :data:`STAGEABLE_SUFFIXES` rather than copied off the caller's filename, so the
    staged name is a constant either way.

    @param source: the document to stage, already resolved inside the shared docs volume
    @param work_dir: the temp directory to stage it into
    @return: the staged path, under ``work_dir``
    @raise RuntimeError: when the extension is not one this pipeline accepts
    """
    lowered = source.suffix.lower()
    suffix = next((allowed for allowed in STAGEABLE_SUFFIXES if allowed == lowered), None)
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
    # One directory for the whole run, created before the try so finally always has something
    # to remove, and holding both subdirectories so a second mkdtemp cannot orphan the first.
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
            completed = _run_soffice(command, get_conversion_timeout_seconds())
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


def _convert_sibling_pdf_via_odt(source: Path) -> None:
    """Fallback: Render the sibling PDF through LibreOffice's own format ODT instead of directly.

    @param source: the document to render, the same one the direct attempt used
    @raise RuntimeError: when the ODT conversion fails, as :func:`convert` reports it
    """
    staging = Path(tempfile.mkdtemp(prefix="iap-lo-odt-"))
    try:
        intermediate = convert(source, _ODT_CONVERT_TO, staging)
        convert(intermediate, _PDF_CONVERT_TO, source.parent)
    finally:
        _discard_staging(staging)


def _convert_sibling_pdf(source: Path, log: LogFn | None = None) -> None:
    """Convert DOCX to PDF

    Two attempts: direct PDF, if failed, try through ODT (see :func:`_convert_sibling_pdf_via_odt`).
    """
    try:
        convert(source, _PDF_CONVERT_TO, source.parent)
        return
    except (RuntimeError, FileNotFoundError, OSError) as direct_failure:
        if log is not None:
            log(
                f"LibreOffice PDF conversion failed for '{source.name}' ({direct_failure}); "
                "retrying through ODT"
            )
        first = direct_failure

    try:
        _convert_sibling_pdf_via_odt(source)
    except (RuntimeError, FileNotFoundError, OSError) as odt_failure:
        if log is not None:
            log(
                f"LibreOffice PDF conversion failed for '{source.name}' both directly and "
                f"through ODT; continuing without sibling PDF (bookmarks unavailable): "
                f"direct: {first}; via ODT: {odt_failure}"
            )


def prepare_office_document(input_path: Path, *, log: LogFn | None = None) -> Path:
    """Convert DOC/DOCX to PDF and return the Docling input path.

    @param input_path: staged source file under the shared docs tree
    @param log: optional line logger for soft PDF failures
    @return: path Docling should convert (``.pdf`` or ``.docx``)
    """
    source = Path(input_path)
    suffix = source.suffix.lower()
    if suffix == ".doc":
        docx_path = convert(source, "docx", source.parent)
        # From the .docx, not the .doc: that is the document Docling parses to .md
        _convert_sibling_pdf(docx_path, log)
        return docx_path
    if suffix == ".docx":
        _convert_sibling_pdf(source, log)
        return source
    return source
