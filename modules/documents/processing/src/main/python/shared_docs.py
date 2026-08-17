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
from pathlib import Path

from markdown_markers import INPUT_SUFFIXES

DEFAULT_SHARED_DOCS = "/shared-docs"


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

    @param raw_path: absolute, already-decoded path from the ``path`` query parameter
    @return: resolved existing file path
    @raise ParseRequestError: when the path is empty, outside the shared root, or not a file
    """
    text = (raw_path or "").strip()
    if not text:
        raise ParseRequestError("path query parameter is required")
    root = shared_docs_root()
    resolved = os.path.realpath(text)
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
    """``os.replace`` after the CodeQL-visible path check on both sides."""
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
    """Unlink ``path`` after the CodeQL-visible path check."""
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
    """``shutil.rmtree`` after the CodeQL-visible path check."""
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    shutil.rmtree(resolved, ignore_errors=ignore_errors)


def make_dirs(path: Path | str, *, exist_ok: bool = True) -> None:
    """``os.makedirs`` after the CodeQL-visible path check."""
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    os.makedirs(resolved, exist_ok=exist_ok)


def path_exists(path: Path | str) -> bool:
    """``os.path.exists`` after the CodeQL-visible path check."""
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    return os.path.exists(resolved)


def path_is_file(path: Path | str) -> bool:
    """``os.path.isfile`` after the CodeQL-visible path check."""
    resolved = os.path.realpath(path)
    root_marker = os.path.splitdrive(resolved)[0] or os.sep
    if not resolved.startswith(root_marker):
        raise ParseRequestError(f"invalid path: {path}")
    return os.path.isfile(resolved)
