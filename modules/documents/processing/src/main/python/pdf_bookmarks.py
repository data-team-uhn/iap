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

"""
Extract a PDF's embedded bookmark outline.

Uses ``pypdf``, already a parser dependency. ``reader.outline`` is a nested list -- a child
group follows its parent as a sub-list -- flattened here into ordered
``{"title", "level", "page"}`` records with 1-based pages. ``pypdf`` is imported lazily so
importing this module does not need it: tests use a fake reader, and the chunker gets here for
documents with no sibling PDF.
"""

from __future__ import annotations

from pathlib import Path, PurePath


# Deepest outline nesting worth walking. A PDF's outline is caller-supplied and one
# recursion deep per level, so an absurdly nested one would otherwise exhaust the stack.
MAX_OUTLINE_DEPTH = 32


def _get_records(reader) -> list[dict]:
    """Flatten one reader's outline into records."""
    records: list[dict] = []
    _flatten(reader, reader.outline, 1, records)
    return records


def extract_bookmarks(source) -> list[dict]:
    """Flatten a PDF's bookmark outline into ordered ``{title, level, page}`` records.

    ``source`` is a path (opened with pypdf) or an already-constructed reader (duck-typed:
    anything exposing ``outline`` and ``get_destination_page_number``). Returns ``[]`` when
    the PDF has no bookmarks or cannot be read.

    Keep one ``try`` around both paths. With the flattening inside it for a path and outside it
    for a reader, a reader whose ``outline`` was not iterable failed open on one and escaped as
    a 500 on the other. ``RecursionError`` is a ``RuntimeError``, so an outline nested past
    :data:`MAX_OUTLINE_DEPTH` is covered here too.
    """
    try:
        if isinstance(source, (str, Path, PurePath)):
            from shared_docs import open_pdf_reader
            # The outline is flattened into plain dicts, so the handle is only needed here.
            with open(source, "rb") as handle:
                return _get_records(open_pdf_reader(handle))
        return _get_records(source)
    except Exception:  # noqa: BLE001 -- any reader/parse failure means "no usable outline"
        return []


def _flatten(reader, items, level: int, out: list[dict]) -> None:
    """Walk a (nested) outline into flat records, one recursion per nesting level."""
    if level > MAX_OUTLINE_DEPTH:
        # Deeper than any real document's outline, so the rest is not worth a stack frame.
        return
    for item in items:
        if isinstance(item, list):
            _flatten(reader, item, level + 1, out)
            continue
        try:
            title = _get_title(item)
            if not title:
                continue
            out.append({"title": title, "level": level, "page": _get_page(reader, item)})
        except Exception:  # noqa: BLE001 -- skip a single malformed bookmark, keep the rest
            continue


def _get_title(item) -> str:
    title = getattr(item, "title", None)
    return " ".join(str(title).split()).strip() if title is not None else ""


def _get_page(reader, item) -> int | None:
    try:
        index = reader.get_destination_page_number(item)
    except Exception:  # noqa: BLE001
        return None
    return index + 1 if isinstance(index, int) and index >= 0 else None
