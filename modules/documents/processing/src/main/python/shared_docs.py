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

Split out of :mod:`docling_daemon` so it can be imported, and tested, without Docling. The
daemon pulls in the whole model stack, so its test module skips itself wherever Docling is
not installed — including CI. That is the wrong place for the one check standing between
``POST /parse?path=`` and the rest of the filesystem, so it lives here instead and
``test_shared_docs.py`` runs everywhere.
"""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

from markdown_markers import INPUT_SUFFIXES

DEFAULT_SHARED_DOCS = "/shared-docs"

# Largest PDF the daemon will accept, in pages, and the variable that overrides it. One
# conversion holds the only parse slot (MAX_CONCURRENT_PARSES), so an enormous document does
# not just take a long time -- every other caller gets 503 until it finishes. The refusal is a
# 400 the caller can act on, raised *after* the parse slot is taken (see
# :func:`refuse_oversized_input`): counting pages means reading the document, so one that
# arrives mid-conversion hears 503 busy first and 400 on retry. 0 or negative turns it off.
PAGE_LIMIT_VARIABLE = "IAP_MAX_INPUT_PAGES"
DEFAULT_MAX_INPUT_PAGES = 1500

# The same ceiling by size, for every accepted type rather than PDFs only. A page count needs
# pages to count, so a .doc/.docx walks straight past the page limit and can still render to an
# arbitrarily long PDF in LibreOffice prep — the hole a caller could step through by changing
# the extension. Bytes are the one measure every input has. 0 or negative turns the limit off.
BYTE_LIMIT_VARIABLE = "IAP_MAX_INPUT_BYTES"
# 64 MiB, not something larger: the ceilings have to describe a document the rest of the
# pipeline can actually finish. One soffice run is killed at IAP_LIBREOFFICE_TIMEOUT_SECONDS
# (300s), and for a .doc that kill is a hard failure — so admitting a file far bigger than
# LibreOffice can render in that time would mark a document unparseable that was never given a
# fair chance. Raise both together, or neither.
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

    Shared by every numeric knob the pipeline reads, so the "an unreadable value warns instead
    of silently becoming the default" behaviour cannot drift between them: "150O" for "1500"
    otherwise left the operator no way to tell their setting had been ignored.

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

    Both ceilings exist because neither covers everything: bytes apply to every accepted type
    but say little about how long a conversion will take, and pages say a great deal but only a
    PDF has them to count.

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


def refuse_oversized_pdf(path: Path) -> None:
    """Reject a PDF with more pages than :func:`max_input_pages` allows.

    Reads only the page tree, which is cheap next to the conversion it is protecting.
    Deliberately fails open: an unreadable or non-PDF file is left to the converter, which
    reports a real error for it. The ceiling is a resource guard, not a security boundary --
    :func:`resolve_parse_path` is what keeps a caller inside the shared volume.

    @param path: the resolved input path
    @raise ParseRequestError: when the document is over the ceiling
    """
    limit = max_input_pages()
    if limit is None or path.suffix.lower() != ".pdf":
        return
    try:
        from pypdf import PdfReader
        # Opened through a context manager: this runs on every /parse, and PdfReader holds the
        # file until it is collected.
        with open(path, "rb") as handle:
            pages = len(PdfReader(handle).pages)
    except Exception:  # noqa: BLE001 -- unreadable here means "let the converter say why"
        return
    if pages > limit:
        raise ParseRequestError(
            f"document has {pages} pages, over the {limit}-page limit "
            f"({PAGE_LIMIT_VARIABLE} raises or disables it)"
        )


def resolve_parse_path(raw_path: str) -> Path:
    """Resolve and allowlist a caller-supplied document path under the shared docs root.

    ``raw_path`` is already URL-decoded: ``parse_qs`` decodes query values, so decoding
    again here would turn a correctly-encoded ``report%2520final.pdf`` into
    ``report final.pdf`` -- a different file -- and would also let ``%252e%252e`` collapse
    into ``..`` after the caller believed it had escaped it.

    ``os.path.realpath`` before the containment check is what makes it hold: it collapses
    ``..`` and follows symlinks first, so a link planted inside the root that points out of
    it is compared at its real location. CodeQL's ``py/path-injection`` query then requires
    ``str.startswith`` on that normalized value (it does not model ``commonpath`` or
    ``Path.relative_to``). ``commonpath`` is the real jail: it rejects a sibling whose name
    merely starts with the root ("/shared-docs-evil"), which ``startswith`` would accept.
    ``relative_to`` is kept as a third closed check.

    ``parse_qs`` also decodes ``+`` as a space, so a file whose name really contains one has to
    arrive percent-encoded (``report%2Bfinal.pdf``); sent literally it is looked for as
    ``report final.pdf`` and reported missing.

    Resolve-then-use, so there is a window between the checks here and the converter opening
    the file: a symlink swapped in after this returns would be followed. Accepted rather than
    closed, because exploiting it needs write access to the shared volume, and anyone who has
    that can simply stage the file they want parsed.

    Deliberately cheap — stat and string work only, no reading of the document. The size
    ceilings are :func:`refuse_oversized_input`, which the caller applies once it holds the
    parse slot: walking a PDF's page tree is real work, and doing it here let every concurrent
    request do it at once on a container sized for one conversion.

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
        # A NUL byte in the path. On Linux ``realpath`` calls ``os.lstat``, which raises a bare
        # ValueError for one, and ``posixpath.realpath`` catches only OSError — so it escaped
        # before any ParseRequestError could be raised, and the handler reported a malformed
        # request as a 500. A caller reading 5xx as "retry" would then retry it forever.
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

    This check is not a containment check and rejects nothing in practice -- the jail is
    :func:`resolve_parse_path`. It exists only so the query sees a guard at the syscall.

    ``realpath`` + ``startswith`` must sit in this function, not a helper: CodeQL's
    ``SafeAccessCheck`` is a barrier-guard and only sanitizes the true branch in the
    same CFG as the ``open``. After ``realpath`` the path is absolute, so it starts
    with the drive (Windows) or ``os.sep`` (POSIX). The shared-docs jail stays in
    :func:`resolve_parse_path`; this only satisfies the query at the syscall so the
    CLI can still re-chunk a file outside ``IAP_SHARED_DOCS``.
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
