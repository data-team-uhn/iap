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
Split an already-produced Markdown document into per-chunk files by top level headings.

Takes the final Markdown that :mod:`docling_pdf_parser` / :mod:`docling_docx_parser` writes and
lays out a chunk folder beside that ``.md``::

    protocol.md
    Chunks/
        catalog.json
        outline.json
        Chunk-0.md        (everything before the first level-1 heading, if any)
        Chunk-1.md
        Chunk-2.1.md      (a chunk over the token budget, split into parts)
        Chunk-2.2.md

Consecutive top-level sections are joined in document order until they fill
:data:`DEFAULT_MAX_TOKENS`, then written as one chunk file. A chunk still over budget is split
into ``Chunk-<n>.<k>`` parts by sub-headings, then by blank lines. A text-only tail part under
:data:`MIN_TAIL_TOKENS` is folded back into the part before it rather than cut off, even if that
pushes it over budget.

Every chunk is listed in ``catalog.json``::

    [
      {
        "chunk_id": "Chunk-1.md",
        "headings": ["Introduction"],
        "summary": "",
        "rubric_tags": [],
        "pages": "1-2",
        "length": 1837
      }, ...
    ]

``chunk_id`` is the chunk's own file name. ``summary`` and ``rubric_tags`` stay empty here, to
be filled in later. ``pages`` is the page range covered by the chunk (``"11-12"``, or ``"11"``
for one page), empty for DOCX. ``length`` is the chunk content's character count. When a
sibling PDF supplies
bookmarks, ``headings`` are those bookmark titles (not every ATX line): a printed TOC styled
as ``#`` is dropped, and a section that never became ``#`` is still labelled. When bookmarks
exist they also rewrite heading levels in the Markdown used for splitting, so chunk cuts
follow the outline rather than Docling's ``#`` counts.

Token counts come from :func:`markdown_markers.count_tokens`, a character-based heuristic
(``len(text) // 4``). No ML tokenizer is loaded. Heading recognition lives in
:mod:`heading_helpers`.

Entry points:

* :func:`build_chunk_tree` -- analyse Markdown into a chunk tree, reading a sibling ``.pdf`` for
  bookmarks if there is one. Writes nothing.
* :func:`chunk_file` -- the only disk writer of ``{stem}.md`` + ``Chunks/``. Called by
  :func:`parse_document.parse_document` (with Markdown already in hand) and the CLI (reads the
  ``.md`` from disk).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from collections.abc import Callable
from typing import Any

import shared_docs
from docling_batch_sizing import parse_positive_int
from heading_helpers import (
    _apply_bookmark_heading_levels,
    _get_heading_level,
    _get_min_atx_level,
    _get_part_heading,
    _get_preamble_heading,
    _is_heading_only,
    _is_standalone_heading,
    _verify_catalog_headings,
)
from markdown_markers import (
    MAX_HEADING_LEVEL,
    PAGE_MARKER,
    PAGE_MARKER_LINE,
    count_tokens,
    count_tokens_for_length,
)
from pdf_bookmarks import extract_bookmarks

# Default maximum tokens per chunk file. A chunk larger than this is split into parts.
DEFAULT_MAX_TOKENS = 2000

# Documents shorter than this (``len(md) // 4``) are left unchunked and sent whole downstream.
# Known PDF bookmarks bypass the gate: they are already in hand, and even a small document
# needs its bookmarks.
DEFAULT_MIN_STRUCTURE_TOKENS = 20000

# A text-only continuation part smaller than this is folded back into the preceding part
# instead of being cut off into its own file, even when that pushes the preceding part over
# the token budget.
MIN_TAIL_TOKENS = 500

# Name of the per-document catalog file written into the chunks folder.
CATALOG_NAME = "catalog.json"

# Per-document outline file written inside Chunks/ (bookmarks / token size / routing).
OUTLINE_NAME = "outline.json"

# Name of the folder, beside a document's .md, holding its chunk files, catalog and outline.
CHUNKS_DIRNAME = "Chunks"

# The two ``unchunkedReason`` values. Either way ``outline.json`` is written with
# ``chunked: false``, so a reader gets one shape plus the reason.
UNCHUNKED_BELOW_THRESHOLD = "below_min_structure_tokens"
UNCHUNKED_NOT_REQUESTED = "chunking_not_requested"


def _split_lines_at(lines: list[str], is_boundary: Callable[[int], bool]) -> list[str]:
    """Group ``lines`` into blocks, starting a new block at each boundary line. A boundary
    at the very start (nothing accumulated yet) does not create an empty leading block.
    """
    blocks: list[str] = []
    current: list[str] = []
    for index, line in enumerate(lines):
        if is_boundary(index) and current:
            blocks.append("\n".join(current).strip())
            current = [line]
        else:
            current.append(line)
    if current:
        blocks.append("\n".join(current).strip())
    return [block for block in blocks if block]


def _get_page_range(text: str) -> str:
    """The page range this chunk covers, as ``"11-12"`` or ``"11"``.

    Markers inside the text set the end (and usually the start). If the first non-blank line
    is not a page marker, the chunk began mid-page, so the start is one before the first
    marker. No markers means no range (DOCX, or a tail with no later page break).
    """
    pages: set[int] = set()
    for match in PAGE_MARKER.finditer(text or ""):
        pages.add(int(match.group(1)))
    if not pages:
        return ""
    start = min(pages)
    end = max(pages)
    if not _starts_with_page_marker(text):
        start = max(1, start - 1)
    if start == end:
        return str(start)
    return f"{start}-{end}"


def _starts_with_page_marker(text: str) -> bool:
    """Whether the first non-blank line of ``text`` is a ``<!-- page: N -->`` marker."""
    for line in (text or "").split("\n"):
        stripped = line.strip()
        if stripped == "":
            continue
        return PAGE_MARKER_LINE.match(stripped) is not None
    return False


def _get_header_line_index(bookmark: dict) -> int | None:
    """0-based array index from a bookmark's 1-based ``line``, or ``None`` if missing."""
    line = bookmark.get("line")
    if isinstance(line, int) and line >= 1:
        return line - 1
    return None


def _split_into_top_chunks(
    lines: list[str],
    header_bookmarks: list[dict],
    min_header_level: int | None,
) -> list[dict]:
    """Split the document at top-level header lines already listed in ``header_bookmarks``.

    @param lines: the main-content Markdown already split on newlines
    @param header_bookmarks: heading candidates / matched bookmarks with ``level`` and line
    @param min_header_level: the shallowest heading level to cut on, or ``None``
    @return: chunks in document order, each ``{"number", "text"}``
    """
    if min_header_level is None:
        text = "\n".join(lines).strip()
        return [{"number": 0, "text": text}] if text else []

    split_indices = sorted({
        index
        for bookmark in header_bookmarks
        if bookmark.get("level") == min_header_level
        and (index := _get_header_line_index(bookmark)) is not None
    })
    # If none of pdf bookmarks matched to md lines so no records have any line numbers from md
    if not split_indices:
        # PDF levels with no matched line, or no positioned top-level header.
        text = "\n".join(lines).strip()
        return [{"number": 0, "text": text}] if text else []

    result: list[dict] = []
    first_cut = split_indices[0]
    # Content before the first such heading is chunk ``number == 0``
    if first_cut > 0:
        preamble = "\n".join(lines[:first_cut]).strip()
        if preamble:
            result.append({"number": 0, "text": preamble})

    for chunk_number, start in enumerate(split_indices, start=1):
        end = split_indices[chunk_number] if chunk_number < len(split_indices) else len(lines)
        text = "\n".join(lines[start:end]).strip()
        if text:
            result.append({"number": chunk_number, "text": text})
    return result


def _subchunk_blocks(chunk_text: str, min_header_level: int) -> list[str]:
    """Split a chunk's text at its shallowest sub-heading level.

    The first block holds the chunk's own boundary heading and any lead-in text before the
    first sub-heading; each subsequent block is one sub-chunk.

    An ATX ``#`` sub-heading is the only boundary. Do not add fallbacks for bold or ALL-CAPS
    lines: they were tried and matched emphasis inside paragraphs far too often. With no
    sub-heading the whole text is one block and :func:`_split_by_paragraphs` takes over.
    """
    lines = chunk_text.split("\n")
    sub_level = _get_min_atx_level(lines, deeper_than=min_header_level)
    if sub_level is not None:
        return _split_lines_at(lines, lambda index: _get_heading_level(lines[index]) == sub_level)

    stripped = chunk_text.strip()
    return [stripped] if stripped else []


def _split_trailing_page_markers(text: str) -> tuple[str, str] | None:
    """If ``text`` ends with one or more ``<!-- page: N -->`` lines (and blank lines around
    them), return ``(body, markers_block)``. ``None`` when it does not end that way.
    """
    lines = text.split("\n")
    end = len(lines) - 1
    while end >= 0 and lines[end].strip() == "":
        end -= 1
    if end < 0 or not PAGE_MARKER_LINE.match(lines[end].strip()):
        return None
    start = end
    while start > 0:
        prev = lines[start - 1].strip()
        if prev == "" or PAGE_MARKER_LINE.match(prev):
            start -= 1
            continue
        break
    while start <= end and lines[start].strip() == "":
        start += 1
    body = "\n".join(lines[:start]).rstrip()
    markers = "\n".join(lines[start:]).strip()
    if not markers:
        return None
    return body, markers


def _flush_without_trailing_page_markers(current: str, nxt: str) -> tuple[str | None, str]:
    """Close ``current`` before ``nxt``: move a trailing page-marker run onto ``nxt``.

    Returns ``(body_to_emit_or_None, next_current)``.
    """
    extracted = _split_trailing_page_markers(current)
    if extracted is None:
        return current, nxt
    body, markers = extracted
    new_next = (markers + "\n\n" + nxt.lstrip()).strip()
    body = body.strip()
    return (body if body else None), new_next


def _move_trailing_page_markers(parts: list[str]) -> list[str]:
    """Never leave a ``<!-- page: N -->`` marker at the end of a part: move trailing marker
    runs to the start of the next part. Empty parts left behind are dropped. The last
    part is unchanged (nowhere to move markers).
    """
    if len(parts) < 2:
        return parts
    result = list(parts)
    for index in range(len(result) - 1):
        extracted = _split_trailing_page_markers(result[index])
        if extracted is None:
            continue
        body, markers = extracted
        result[index] = body
        result[index + 1] = (markers + "\n\n" + result[index + 1].lstrip()).strip()
    return [part for part in result if part.strip()]


def _split_by_paragraphs(text: str, max_tokens: int) -> list[str]:
    """Split text into parts no larger than the budget at blank-line (paragraph) boundaries.

    A single paragraph larger than the budget is kept whole (nothing is split mid-paragraph).
    When a part is closed, a trailing ``<!-- page: N -->`` run is moved onto the next paragraph.
    """
    parts: list[str] = []
    # The part under construction is kept unjoined: measuring a candidate by length instead
    # of building it keeps this linear in the size of the text (see :func:`_pack_blocks`).
    pieces: list[str] = []
    length = 0
    for paragraph in text.split("\n\n"):
        if paragraph.strip() == "":
            continue
        if not pieces:
            pieces, length = [paragraph], len(paragraph)
            continue
        if count_tokens_for_length(length + 2 + len(paragraph)) <= max_tokens:
            pieces.append(paragraph)
            length += 2 + len(paragraph)
            continue
        body, current = _flush_without_trailing_page_markers("\n\n".join(pieces), paragraph)
        if body is not None:
            parts.append(body)
        pieces, length = [current], len(current)
    if pieces:
        parts.append("\n\n".join(pieces))
    return _move_trailing_page_markers(parts)


def _pack_blocks(blocks: list[str], max_tokens: int) -> list[str]:
    """Unite consecutive blocks into parts as large as the budget allows.

    When the next block is a stand-alone heading (heading line, no body), look ahead one more
    block and only keep merging if ``current + heading + following`` still fits. If not, flush
    and start a new merge from that heading. A part that is still only a heading never flushes
    alone -- it always takes the following block, even over budget, and the oversized splitter
    deals with it later.

    A flushed part never ends on a ``<!-- page: N -->`` marker; trailing markers move to the
    start of the next part.
    """
    parts: list[str] = []
    # Keep the part being built as unjoined pieces plus the length it would have once joined.
    # Actually joining it just to measure copies the whole part on every block and every
    # discarded lookahead, which makes packing quadratic. count_tokens_for_length gives the same
    # answer without building the string.
    pieces: list[str] = []
    length = 0
    index = 0
    n = len(blocks)

    def take(block: str) -> None:
        nonlocal length
        length += (2 if pieces else 0) + len(block)
        pieces.append(block)

    def restart(text: str) -> None:
        nonlocal pieces, length
        pieces, length = [text], len(text)

    while index < n:
        block = blocks[index]
        if not pieces:
            restart(block)
            index += 1
            continue

        if _is_standalone_heading(block) and index + 1 < n:
            following = blocks[index + 1]
            lookahead = length + 2 + len(block) + 2 + len(following)
            if count_tokens_for_length(lookahead) <= max_tokens:
                take(block)
                index += 1
                continue
            body, current = _flush_without_trailing_page_markers("\n\n".join(pieces), block)
            if body is not None:
                parts.append(body)
            restart(current)
            index += 1
            continue

        if count_tokens_for_length(length + 2 + len(block)) <= max_tokens:
            take(block)
            index += 1
            continue
        # Joined once: this is the flush path, and the comment above explains why building
        # the accumulated part twice to answer one predicate is what made packing quadratic.
        joined = "\n\n".join(pieces)
        if _is_standalone_heading(joined):
            # Do not emit a heading-only part — pull the next block in even over budget.
            take(block)
            index += 1
            continue
        body, current = _flush_without_trailing_page_markers(joined, block)
        if body is not None:
            parts.append(body)
        restart(current)
        index += 1

    if pieces:
        parts.append("\n\n".join(pieces))
    return _move_trailing_page_markers(parts)


def _split_oversized(
    chunk_text: str, min_header_level: int, max_tokens: int
) -> list[str]:
    """Split an over-budget chunk into parts.

    Sub-headings (the shallowest level deeper than ``min_header_level``) come first: consecutive
    sub-chunks are joined up to the budget. A chunk with no sub-headings, or a joined part still
    over budget, is split at paragraph boundaries instead.
    """
    blocks = _subchunk_blocks(chunk_text, min_header_level)
    packed = _split_by_paragraphs(chunk_text, max_tokens) if len(blocks) <= 1 \
        else _pack_blocks(blocks, max_tokens)

    parts: list[str] = []
    for part in packed:
        if count_tokens(part) > max_tokens:
            parts.extend(_split_by_paragraphs(part, max_tokens))
        else:
            parts.append(part)
    return parts


def _merge_heading_only_parts(parts: list[str]) -> list[str]:
    """Fold a part that is only a heading into a neighbour, so no chunk file is a bare title.

    Two paths produce one: :func:`_split_by_paragraphs` flushes a heading alone when the body
    is one over-budget paragraph (common for a big table, which Docling emits as ``|`` lines
    with no blank line), and :func:`_pack_blocks` leaves a trailing bare heading as the last
    part because its lookahead stops at ``index + 1 < n``.

    The direction has to vary -- a heading takes the part after it, but a trailing heading has
    nothing to take and folds backwards. :func:`_merge_small_text_tails` cannot cover this: it
    only merges backwards and refuses any part with a heading.

    Merging can push a part over ``max_tokens``, on purpose. A bare-title chunk gives the
    summarizer nothing, and a table split from its heading is left with an inherited label.
    """
    merged: list[str] = []
    for part in parts:
        if merged and _is_heading_only(merged[-1]):
            merged[-1] = merged[-1].rstrip() + "\n\n" + part.lstrip()
            continue
        merged.append(part)
    if len(merged) > 1 and _is_heading_only(merged[-1]):
        trailing = merged.pop()
        merged[-1] = merged[-1].rstrip() + "\n\n" + trailing.lstrip()
    return merged


def _merge_small_text_tails(parts: list[str], min_tokens: int) -> list[str]:
    """Fold a text-only continuation part smaller than ``min_tokens`` back into the part
    before it, so a small tail cut off from the previous part is not emitted as its own file.
    A part that carries any *cut-worthy* ATX heading (see :func:`_get_heading_level` /
    :func:`is_valid_heading`) is never merged; lines that look like headings but fail
    validation (e.g. ``## Table …``) do not block the merge. The merge is applied even
    when it pushes the preceding part over the token budget.
    """
    merged: list[str] = []
    for part in parts:
        if merged and _get_min_atx_level(part.split("\n")) is None \
                and count_tokens(part) < min_tokens:
            merged[-1] = merged[-1].rstrip() + "\n\n" + part.lstrip()
        else:
            merged.append(part)
    return merged


def _write_json(path: Path, data: object) -> None:
    """Write ``data`` as pretty-printed UTF-8 JSON with a trailing newline."""
    shared_docs.write_text(
        path, json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    )


def write_unchunked_outline(output_file: Path, markdown: str) -> Path:
    """Write ``Chunks/outline.json`` for a document that was never offered to the chunker.

    ``?chunk=false`` skips detection on purpose, so there are no bookmarks to record. The
    outline is still written so both unchunked paths leave the same shape on disk -- same
    keys as :func:`build_chunk_tree` writes when the size gate skips chunking, only the
    reason differs.

    @param output_file: path of the ``.md`` the outline sits beside
    @param markdown: the document, for its token count
    @return: the ``Chunks/`` directory
    """
    chunks_dir = output_file.parent / CHUNKS_DIRNAME
    shared_docs.make_dirs(chunks_dir)
    _write_json(chunks_dir / OUTLINE_NAME, {
        "bookmarks": [],
        "tokens": count_tokens(markdown),
        "chunked": False,
        "unchunkedReason": UNCHUNKED_NOT_REQUESTED,
    })
    return chunks_dir


def write_atomically(path: Path, text: str) -> None:
    """Write ``text`` to ``path`` via a temporary file and a rename.

    A direct ``write_text`` truncates first, so an interrupted write leaves a half-written
    document that still looks like a finished one.

    @param path: the file to write
    @param text: its complete new content
    """
    scratch = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        shared_docs.write_text(scratch, text)
        shared_docs.replace_file(scratch, path)
    finally:
        shared_docs.remove_file(scratch)


def _stage_chunks(chunks_dir: Path, tree: dict[str, Any]) -> Path:
    """Write the whole chunk tree into a staging directory beside its final home.

    Building in place would delete the old tree first, so a failure part way through leaves a
    half-written set that looks complete. Write everything here, then move it in one step.

    @param chunks_dir: where the tree will end up
    @param tree: the built chunk tree
    @return: the staging directory, ready to be swapped in
    """
    staging = chunks_dir.with_name(f"{chunks_dir.name}.new-{os.getpid()}")
    shared_docs.make_dirs(staging)
    try:
        _write_json(staging / OUTLINE_NAME, tree["outline"])
        if tree["chunked"]:
            for chunk in tree["chunks"]:
                shared_docs.write_text(
                    staging / chunk["file"], chunk["text"] + "\n"
                )
            # Last inside the staging directory as well, so even a torn move leaves the
            # catalog as the marker that the set beside it is complete
            _write_json(staging / CATALOG_NAME, tree["catalog"])
    except BaseException:
        # A half-written staging directory has no further use, and leaving it behind would
        # litter the shared volume with one per failed parse
        shared_docs.remove_tree(staging, ignore_errors=True)
        raise
    return staging


def _swap_into_place(staging: Path, target: Path) -> None:
    """Replace ``target`` with ``staging`` using renames, keeping the old tree until it is.

    @param staging: the fully written new tree
    @param target: the directory it replaces
    """
    previous = target.with_name(f"{target.name}.old-{os.getpid()}")
    shared_docs.remove_tree(previous, ignore_errors=True)
    try:
        if shared_docs.path_exists(target):
            shared_docs.replace_file(target, previous)
        shared_docs.replace_file(staging, target)
    except OSError:
        # Put the previous tree back rather than leaving the document with none at all
        if shared_docs.path_exists(previous) and not shared_docs.path_exists(target):
            shared_docs.replace_file(previous, target)
        shared_docs.remove_tree(staging, ignore_errors=True)
        raise
    shared_docs.remove_tree(previous, ignore_errors=True)


def build_chunk_tree(
    markdown_content: str,
    markdown_path: Path | None,
    max_tokens: int,
    min_structure_tokens: int,
) -> dict[str, Any]:
    """Analyse and split an already-cleaned document into its chunk tree.

    Writes nothing. Callers that need the tree on disk go through :func:`chunk_file`.

    @param markdown_content: the full Markdown document, already cleaned
    @param markdown_path: path of the ``.md``; a sibling ``.pdf`` supplies PDF bookmarks.
        ``None`` means there is no sibling to look for, so the document has no bookmarks
    @param max_tokens: target maximum tokens per chunk
    @param min_structure_tokens: leave the document unchunked below this size
    @return: ``{"markdown", "chunked", "outline", "catalog", "chunks"}``, where ``chunks``
        is a list of ``{"file", "text"}`` in document order, ``catalog`` is ``None`` when
        the document was left unchunked, and ``outline["bookmarks"]`` is the sibling PDF's
        bookmarks (empty when there is no sibling PDF, or it carries none)
    """
    md_file = markdown_content

    # Get bookmarks from PDF if avaialble, levels not deeper than MAX_HEADING_LEVEL
    pdf_bookmarks: list[dict] = []
    if markdown_path is not None:
        pdf_file = Path(markdown_path).with_suffix(".pdf")
        if pdf_file.is_file():
            pdf_bookmarks = extract_bookmarks(pdf_file)
    pdf_bookmarks = [
        bookmark
        for bookmark in pdf_bookmarks
        if bookmark.get("title") and bookmark["level"] <= MAX_HEADING_LEVEL
    ]

    # The size gate : is file too small to chunk?
    tokens = count_tokens(md_file)
    to_be_chunked = tokens >= min_structure_tokens
    outline = {
        "tokens": tokens,
        "bookmarks": pdf_bookmarks,
        "chunked": to_be_chunked,
    }

    if not to_be_chunked:
        # Below the size gate
        outline["unchunkedReason"] = UNCHUNKED_BELOW_THRESHOLD
        return {
            "markdown": md_file,
            "chunked": False,
            "outline": outline,
            "catalog": None,
            "chunks": [],
        }

    # Match heading candidates to bookmarks by title, keeping the hit closest in page.
    md_lines = md_file.split("\n")
    md_lines, header_bookmarks = _apply_bookmark_heading_levels(md_lines, pdf_bookmarks)
    md_file = "\n".join(md_lines)
    outline["bookmarks"] = header_bookmarks

    min_header_level = min(
        (
            bookmark["level"]
            for bookmark in header_bookmarks
            if isinstance(bookmark.get("level"), int) and bookmark["level"] > 0
        ),
        default=None,
    )

    catalog_chunks: list[dict] = []
    chunks: list[dict] = []

    # Returns array [{"number": chunk_number, "text": text}]
    top_chunks = _split_into_top_chunks(md_lines, header_bookmarks, min_header_level)
    top_texts = [chunk["text"] for chunk in top_chunks if chunk["text"]]
    # _pack_blocks already ends with _move_trailing_page_markers; a second pass finds nothing.
    packed = _pack_blocks(top_texts, max_tokens) if top_texts else []
    # Fold heading-only chunks into a sibling before anything is numbered. Doing it per packed
    # chunk lower down misses a section with a heading and no body: it arrives alone, so there
    # is no neighbour in that call and it ends up a chunk file holding only a title.
    packed = _merge_heading_only_parts(packed)
    # Prefer Chunk-0 when the document has a leading preamble; otherwise start at 1.
    first_number = 0 if (top_chunks and top_chunks[0]["number"] == 0) else 1
    preamble_text = top_chunks[0]["text"] if first_number == 0 else ""
    split_level = min_header_level if min_header_level is not None else 0

    def add(name: str, text: str, heading: list[str]) -> None:
        chunks.append({"file": name, "text": text})
        catalog_chunks.append({
            "chunk_id": name,
            "headings": heading,
            "summary": "",
            "rubric_tags": [],
            "pages": _get_page_range(text),
            "length": len(text) + 1,
        })

    for offset, packed_text in enumerate(packed):
        number = first_number + offset
        if count_tokens(packed_text) > max_tokens:
            parts = _split_oversized(packed_text, split_level, max_tokens)
        else:
            parts = [packed_text]
        parts = _merge_heading_only_parts(parts)
        # _split_oversized re-splits individual packed parts, and the last piece of each split
        # keeps its trailing markers — which lands in the middle of the list once the next
        # packed part follows it. Merging parts can leave one mid-list too.
        parts = _move_trailing_page_markers(_merge_small_text_tails(parts, MIN_TAIL_TOKENS))

        single_part = len(parts) == 1
        for part_index, part_text in enumerate(parts, start=1):
            name = f"Chunk-{number}.md" if single_part else f"Chunk-{number}.{part_index}.md"
            previous_heading = catalog_chunks[-1]["headings"] if catalog_chunks else None
            is_preamble = first_number == 0 and not catalog_chunks
            if is_preamble:
                heading = _get_preamble_heading(part_text, preamble_text)
            else:
                # Everything else gets its real heading
                heading = _get_part_heading(part_text, previous_heading)
            heading = _verify_catalog_headings(
                heading, part_text, pdf_bookmarks, previous_heading, is_preamble=is_preamble
            )
            add(name, part_text, heading)

    return {
        "markdown": md_file,
        "chunked": True,
        "outline": outline,
        "catalog": catalog_chunks,
        "chunks": chunks,
    }


def chunk_file(
    file_path: str | Path,
    max_tokens: int = DEFAULT_MAX_TOKENS,
    *,
    min_structure_tokens: int = DEFAULT_MIN_STRUCTURE_TOKENS,
    markdown: str | None = None,
) -> dict[str, Any]:
    """Write ``file_path`` (``.md``) and a sibling ``Chunks/`` tree.

    Sole disk writer for parse outputs (:func:`parse_document.parse_document` and the CLI).
    Markdown must already be cleaned.

    When ``markdown`` is omitted, the file is read from disk. Pass ``markdown`` when the
    text is already in hand and the ``.md`` may not exist yet.

    Always writes ``Chunks/outline.json``. A document below ``min_structure_tokens`` gets the
    outline only. Otherwise the body is split by headings up to ``max_tokens``.

    @param file_path: path of the ``.md`` to write (and to read, when ``markdown`` is omitted)
    @param max_tokens: max tokens per chunk before further splitting
    @param min_structure_tokens: below this, leave the document unchunked
    @param markdown: cleaned Markdown; ``None`` reads ``file_path``
    @return: summary dict ``{"chunks", "logs", "chunked", "chunks_dir"}``
    @raise FileNotFoundError: when ``markdown`` is omitted and the file does not exist
    """
    path = Path(file_path)
    if markdown is None:
        if not path.is_file():
            raise FileNotFoundError(f"File does not exist: {path}")
        markdown = path.read_text(encoding="utf-8", errors="replace")

    tree = build_chunk_tree(
        markdown,
        path,
        max_tokens,
        min_structure_tokens,
    )
    shared_docs.make_dirs(path.parent)
    chunks_dir = path.parent / CHUNKS_DIRNAME
    chunk_count = len(tree["chunks"])

    staging = _stage_chunks(chunks_dir, tree)
    # Chunks first, Markdown last. The two renames cannot be one atomic step, so the .md is the
    # commit marker: if it is the new one, the chunks beside it are the new set too. The other
    # order would pair new Markdown with chunks from a different revision.
    _swap_into_place(staging, chunks_dir)
    write_atomically(path, tree["markdown"])

    if not tree["chunked"]:
        tokens = count_tokens(tree["markdown"])
        return {
            "chunks": 0,
            "chunked": False,
            "chunks_dir": None,
            "logs": (
                f"Skipped chunking '{path.name}' "
                f"({tokens} tokens < {min_structure_tokens} min_structure_tokens); "
                f"recorded chunked=false in {CHUNKS_DIRNAME}/{OUTLINE_NAME}"
            ),
        }
    return {
        "chunks": chunk_count,
        "chunked": True,
        "chunks_dir": chunks_dir,
        "logs": (
            f"Split '{path.name}' into {chunk_count} chunk file(s) in "
            f"'{CHUNKS_DIRNAME}/'"
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Light chunker for a single parsed Markdown file."
    )
    parser.add_argument(
        "file_path",
        help="Path to a parsed .md file to chunk",
    )
    parser.add_argument(
        "--max-tokens",
        type=parse_positive_int,
        default=DEFAULT_MAX_TOKENS,
        help=f"Maximum tokens per chunk file (default: {DEFAULT_MAX_TOKENS})",
    )
    parser.add_argument(
        "--min-structure-tokens",
        type=parse_positive_int,
        default=DEFAULT_MIN_STRUCTURE_TOKENS,
        help=(
            "Skip chunking when document tokens (len//4) are below this "
            f"(default: {DEFAULT_MIN_STRUCTURE_TOKENS})"
        ),
    )
    args = parser.parse_args()

    try:
        summary = chunk_file(
            file_path=args.file_path,
            max_tokens=args.max_tokens,
            min_structure_tokens=args.min_structure_tokens,
        )
    except FileNotFoundError as exc:
        print(str(exc), file=sys.stderr, flush=True)
        sys.exit(1)

    if summary["logs"]:
        print(summary["logs"], flush=True)
    print(f"Created {summary['chunks']} chunk file(s).", flush=True)


if __name__ == "__main__":
    main()
