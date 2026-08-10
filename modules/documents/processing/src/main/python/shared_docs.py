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

    ``resolve()`` before the containment check is what makes it hold: it collapses ``..``
    and follows symlinks first, so a link planted inside the root that points out of it is
    compared at its real location. ``relative_to`` then rejects anything outside, including
    a sibling directory whose name merely starts with the root's ("/shared-docs-evil").

    @param raw_path: absolute, already-decoded path from the ``path`` query parameter
    @return: resolved existing file path
    @raise ParseRequestError: when the path is empty, outside the shared root, or not a file
    """
    text = (raw_path or "").strip()
    if not text:
        raise ParseRequestError("path query parameter is required")
    candidate = Path(text).resolve()
    root = shared_docs_root()
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
