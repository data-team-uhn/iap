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

"""The shared-docs allowlist: the boundary between a caller-supplied path and the disk.

Kept out of :mod:`docling_daemon` so it stays importable, and testable, without Docling. The
daemon's test module skips itself wherever Docling is missing, including CI -- the wrong place
for the one check standing between ``POST /parse?path=`` and the rest of the filesystem.
``test_shared_docs.py`` runs everywhere.
"""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

from markdown_markers import INPUT_SUFFIXES

DEFAULT_SHARED_DOCS = "/shared-docs"

# Largest PDF the daemon accepts, in pages, plus its override variable. One conversion holds
# the only parse slot, so an enormous document makes every other caller wait on a 503. The
# refusal is a 400, raised after the slot is taken because counting pages means reading the
# document -- so one arriving mid-conversion hears 503 first and 400 on retry. 0 turns it off.
PAGE_LIMIT_VARIABLE = "IAP_MAX_INPUT_PAGES"
DEFAULT_MAX_INPUT_PAGES = 1500

# The same ceiling by size, covering every accepted type. Only a PDF has pages to count, so a
# .doc/.docx walks past the page limit and can still render to an arbitrarily long PDF -- a hole
# a caller opens just by changing the extension. Bytes are the one measure every input has.
BYTE_LIMIT_VARIABLE = "IAP_MAX_INPUT_BYTES"
# 64 MiB, not more: soffice is killed at IAP_LIBREOFFICE_TIMEOUT_SECONDS (300s) and for a .doc
# that kill is a hard failure, so a file bigger than LibreOffice can render in that time gets
# marked unparseable without a fair chance. Raise both limits together, or neither.
DEFAULT_MAX_INPUT_BYTES = 64 * 1024 * 1024


class ParseRequestError(ValueError):
    """The caller's request is wrong, so the reply is a 400.

    Only bad query parameters raise this. Conversion itself raises plenty of ordinary
    ValueErrors from deep inside pypdf, Docling and the chunker; those are server-side
    failures and must stay 500s, or a caller that (correctly) does not retry 4xx would
    permanently mark a re-parseable document as bad. Subclasses ValueError so existing
    callers that catch ValueError still work.
    """


def shared_docs_root() -> Path:
    """Root of the shared volume; paths outside it are refused."""
    configured = (os.environ.get("IAP_SHARED_DOCS") or DEFAULT_SHARED_DOCS).strip()
    return Path(configured).resolve()


def positive_number_from_env(variable, default, cast=int, expected="an integer"):
    """A positive numeric setting from the environment, or ``None`` when it is switched off.

    Shared by every numeric knob so they all warn on an unreadable value instead of silently
    using the default -- a typo like "150O" for "1500" gave the operator no sign it was ignored.

    @param variable: the environment variable to read
    @param default: the value to use when it is unset or unreadable
    @param cast: the parser for the value, ``int`` or ``float``
    @param expected: what the value should have been, for the warning
    @return: the value, or ``None`` when it is 0 or negative
    """
    configured = (os.environ.get(variable) or "").strip()
    if configured:
        try:
            value = cast(configured)
        except ValueError:
            print(
                f"WARNING: {variable}={configured!r} is not {expected}; using {default}",
                file=sys.stderr,
                flush=True,
            )
            value = default
    else:
        value = default
    return value if value > 0 else None


def _limit_from_env(variable: str, default: int) -> int | None:
    """A page/byte ceiling from the environment; ``None`` means the ceiling is off."""
    return positive_number_from_env(variable, default)


def max_input_pages() -> int | None:
    """The configured PDF page ceiling, or ``None`` when the limit is off."""
    return _limit_from_env(PAGE_LIMIT_VARIABLE, DEFAULT_MAX_INPUT_PAGES)


def max_input_bytes() -> int | None:
    """The configured input size ceiling in bytes, or ``None`` when the limit is off."""
    return _limit_from_env(BYTE_LIMIT_VARIABLE, DEFAULT_MAX_INPUT_BYTES)


def refuse_oversized_input(path: Path) -> None:
    """Reject a document bigger than the pipeline is sized for, by size and by page count.

    Both ceilings are needed: bytes cover every type but say little about conversion time,
    pages say a lot but only a PDF has them.

    @param path: the resolved input path
    @raise ParseRequestError: when the document is over either ceiling
    """
    byte_limit = max_input_bytes()
    if byte_limit is not None:
        try:
            size = path.stat().st_size
        except OSError:
            size = None
        if size is not None and size > byte_limit:
            raise ParseRequestError(
                f"document is {size:,} bytes, over the {byte_limit:,}-byte limit "
                f"({BYTE_LIMIT_VARIABLE} raises or disables it)"
            )
    refuse_oversized_pdf(path)


def open_pdf_reader(source):
    """A pypdf reader, unlocking empty-password AES encryption when that is all that is set.

    @param source: an open binary PDF stream (or a path ``PdfReader`` can open)
    @return: a reader whose page tree can be walked
    @raise ParseRequestError: when the PDF needs a non-empty password
    @raise ValueError: when AES support is missing, or pypdf cannot open the file
    """
    from pypdf import PdfReader
    from pypdf.errors import DependencyError

    try:
        reader = PdfReader(source)
        if reader.is_encrypted:
            try:
                # is_encrypted stays True after a successful decrypt; the return value
                # is what tells user/owner password from "still locked" (0).
                unlocked = reader.decrypt("")
            except DependencyError:
                raise
            except Exception as exc:
                raise ParseRequestError(
                    "PDF is encrypted and did not open with an empty password"
                ) from exc
            if not unlocked:
                raise ParseRequestError(
                    "PDF is encrypted and did not open with an empty password"
                )
        return reader
    except DependencyError as exc:
        raise ValueError(
            "PDF uses AES encryption; cryptography>=3.1 is required to read it"
        ) from exc


def refuse_oversized_pdf(path: Path) -> None:
    """Reject a PDF with more pages than :func:`max_input_pages` allows.

    @param path: the resolved input path
    @raise ParseRequestError: when the document is over the ceiling, or password-locked
    """
    limit = max_input_pages()
    if limit is None or path.suffix.lower() != ".pdf":
        return
    try:
        # Opened through a context manager: this runs on every /parse, and PdfReader holds the
        # file until it is collected.
        with open(path, "rb") as handle:
            pages = len(open_pdf_reader(handle).pages)
    except ParseRequestError:
        raise
    except Exception:  # noqa: BLE001 -- unreadable here means "let the converter say why"
        return
    if pages > limit:
        raise ParseRequestError(
            f"document has {pages} pages, over the {limit}-page limit "
            f"({PAGE_LIMIT_VARIABLE} raises or disables it)"
        )


def resolve_parse_path(raw_path: str) -> Path:
    """Resolve and allowlist a caller-supplied document path under the shared docs root.

    Do not decode ``raw_path`` again -- ``parse_qs`` already did. A second pass would turn a
    correctly-encoded ``report%2520final.pdf`` into a different file, and would let
    ``%252e%252e`` collapse into ``..`` after the caller thought it had escaped it.

    All three containment checks are load-bearing. ``realpath`` runs first so ``..`` and
    symlinks are resolved before anything is compared. ``commonpath`` is the actual jail: it
    rejects a sibling that merely starts with the root name ("/shared-docs-evil"), which
    ``startswith`` accepts. ``startswith`` stays because CodeQL's ``py/path-injection`` only
    recognises that form, and ``relative_to`` is a third closed check.

    ``parse_qs`` also decodes ``+`` as a space, so a name really containing one must arrive
    percent-encoded (``report%2Bfinal.pdf``) or it is looked for as ``report final.pdf``.

    @param raw_path: absolute, already-decoded path from the ``path`` query parameter
    @return: resolved existing file path
    @raise ParseRequestError: when the path is empty, outside the shared root, or not a file
    """
    text = (raw_path or "").strip()
    if not text:
        raise ParseRequestError("path query parameter is required")
    root = shared_docs_root()
    try:
        resolved = os.path.realpath(text)
    except ValueError as exc:
        # A NUL byte. ``posixpath.realpath`` catches only OSError, and ``os.lstat`` raises a
        # bare ValueError for one, so without this a malformed request came back as a 500 and a
        # caller treating 5xx as retryable would retry it forever.
        raise ParseRequestError(f"path is not a usable filename: {exc}") from None
    root_s = os.path.realpath(root)
    # realpath (PathNormalization) then startswith (SafeAccessCheck) is the pair
    # py/path-injection models. commonpath is not modeled; it rejects a sibling
    # whose name merely starts with the root.
    if not resolved.startswith(root_s):
        raise ParseRequestError(f"path must be under {root}; got {resolved}")
    try:
        contained = os.path.commonpath([root_s, resolved]) == root_s
    except ValueError:
        # Different drives (Windows) have no common path; that is outside the root.
        contained = False
    if not contained:
        raise ParseRequestError(f"path must be under {root}; got {resolved}")
    candidate = Path(resolved)
    try:
        candidate.relative_to(root)
    except ValueError as exc:
        raise ParseRequestError(
            f"path must be under {root}; got {candidate}"
        ) from exc
    if not candidate.is_file():
        raise ParseRequestError(f"document does not exist: {candidate}")
    suffix = candidate.suffix.lower()
    if suffix not in INPUT_SUFFIXES:
        raise ParseRequestError(
            f"path must end in one of {', '.join(INPUT_SUFFIXES)}; got {candidate.name!r}"
        )
    return candidate


def write_text(path: Path | str, text: str) -> None:
    """Write UTF-8 ``text`` to ``path`` after the CodeQL-visible path check.

    The real containment check is :func:`resolve_parse_path`; this one is here so CodeQL's
    ``py/path-injection`` sees a barrier guard. Do not pull it out into a shared helper: CodeQL
    only honours a guard in the same function as the sink, so a helper turns every writer in
    this module back into an alert.
    """
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    with open(resolved, "w", encoding="utf-8") as handle:
        handle.write(text)


def replace_file(source: Path | str, dest: Path | str) -> None:
    """``os.replace`` after the CodeQL-visible path check on both sides.

    See :func:`write_text` for why the check is inline and not a shared helper.
    """
    src = os.path.realpath(source)
    dst = os.path.realpath(dest)
    src_root = os.path.splitdrive(src)[0] or os.sep
    dst_root = os.path.splitdrive(dst)[0] or os.sep
    if not src.startswith(src_root):
        raise ParseRequestError(f"invalid path: {source}")
    if not dst.startswith(dst_root):
        raise ParseRequestError(f"invalid path: {dest}")
    os.replace(src, dst)


def remove_file(path: Path | str, *, missing_ok: bool = True) -> None:
    """Unlink ``path`` after the CodeQL-visible path check (see :func:`write_text`)."""
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    try:
        os.unlink(resolved)
    except FileNotFoundError:
        if not missing_ok:
            raise


def remove_tree(path: Path | str, *, ignore_errors: bool = False) -> None:
    """``shutil.rmtree`` after the CodeQL-visible path check (see :func:`write_text`)."""
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    shutil.rmtree(resolved, ignore_errors=ignore_errors)


def make_dirs(path: Path | str, *, exist_ok: bool = True) -> None:
    """``os.makedirs`` after the CodeQL-visible path check (see :func:`write_text`)."""
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    os.makedirs(resolved, exist_ok=exist_ok)


def path_exists(path: Path | str) -> bool:
    """``os.path.exists`` after the CodeQL-visible path check (see :func:`write_text`)."""
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    return os.path.exists(resolved)


def path_is_file(path: Path | str) -> bool:
    """``os.path.isfile`` after the CodeQL-visible path check (see :func:`write_text`)."""
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    return os.path.isfile(resolved)
