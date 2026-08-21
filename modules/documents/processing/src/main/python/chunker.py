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
        "pages": [1, 2],
        "length": 1837
      }, ...
    ]

``chunk_id`` is the chunk's own file name. ``summary`` and ``rubric_tags`` stay empty here, to
be filled in later. ``pages`` are the ``<!-- page: N -->`` numbers inside the chunk, empty for
DOCX. ``length`` is the chunk content's character count.

Token counts come from :func:`markdown_markers.count_tokens`, a character-based heuristic
(``len(text) // 4``). No ML tokenizer is loaded.

Entry points:

* :func:`build_chunk_tree` -- analyse Markdown into a chunk tree, reading a sibling ``.pdf`` for
  bookmarks if there is one. Writes nothing.
* :func:`write_chunk_files` -- the only disk writer of ``{stem}.md`` + ``Chunks/``. Called by
  :func:`parse_document.parse_document` and :func:`chunk_file`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from collections.abc import Callable
from typing import Any, NamedTuple

import shared_docs
from docling_batch_sizing import parse_positive_int
from markdown_markers import (
    HEADING,
    MAX_HEADING_LEVEL,
    MIN_HEADING_CHARS,
    PAGE_MARKER,
    PAGE_MARKER_LINE,
    RULE_LINE,
    count_tokens,
    count_tokens_for_length,
    is_within_word_limits,
)
from pdf_bookmarks import extract_bookmarks

# Default maximum tokens per chunk file. A chunk larger than this is split into parts.
DEFAULT_MAX_TOKENS = 2000

# Documents shorter than this (``len(md) // 4``) are left unchunked and sent whole downstream.
# Known PDF bookmarks bypass the gate: they are already in hand, and even a small document
# needs its ``toc``.
DEFAULT_MIN_STRUCTURE_TOKENS = 20000

# A text-only continuation part smaller than this is folded back into the preceding part
# instead of being cut off into its own file, even when that pushes the preceding part over
# the token budget.
MIN_TAIL_TOKENS = 500

# Heading recorded for the leading chunk (content before the first heading) and any other
# chunk that has no heading of its own.
DEFAULT_HEADING = "General Information"

# Name of the per-document catalog file written into the chunks folder.
CATALOG_NAME = "catalog.json"

# Per-document outline file written inside Chunks/ (TOC / token size / routing).
OUTLINE_NAME = "outline.json"

# Name of the folder, beside a document's .md, holding its chunk files, catalog and outline.
CHUNKS_DIRNAME = "Chunks"

# The two ``unchunkedReason`` values. Either way ``outline.json`` is written with
# ``chunked: false``, so a reader gets one shape plus the reason.
UNCHUNKED_BELOW_THRESHOLD = "below_min_structure_tokens"
UNCHUNKED_NOT_REQUESTED = "chunking_not_requested"

# A line recurring at least this many times across the document is page furniture (a running
# header or footer), never a heading — see :func:`get_repeated_lines`. Three rather than two so a
# heading that genuinely appears twice is not discarded.
MIN_RUNNING_HEADER_PAGES = 3


def is_neutral(stripped: str) -> bool:
    """Lines that neither extend nor break a region: blanks, page markers, rules."""
    return stripped == "" or RULE_LINE.match(stripped) is not None \
        or PAGE_MARKER_LINE.match(stripped) is not None


def is_valid_heading(text: str) -> bool:
    """Whether a heading candidate is usable: longer than 4 characters
    (:data:`markdown_markers.MIN_HEADING_CHARS`), within the shared word limits
    (:func:`markdown_markers.is_within_word_limits`), and not a table caption (text already
    stripped of ``#`` / ``**`` markers must not start with ``Table ``).
    """
    if text.casefold().startswith("table "):
        return False
    if len(text) < MIN_HEADING_CHARS:
        return False
    return is_within_word_limits(text)


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


def _match_heading(line: str) -> tuple[int, str] | None:
    """Match ``line`` against the ATX heading regex once, returning ``(level, text)`` —
    the heading level (number of leading ``#``) and its text with the ``#`` markers
    stripped — or ``None`` if the line is not an ATX heading.

    Does **not** apply :func:`is_valid_heading`; callers that decide chunk cuts or catalog
    labels must filter via :func:`_get_heading_level` or :func:`is_valid_heading` themselves.
    """
    match = HEADING.match(line)
    if match is None:
        return None
    return len(match.group(1)), match.group(2).strip()


def _get_heading_level(line: str) -> int | None:
    """Return the heading level used for chunk cuts, or ``None`` if the line is not an
    ATX heading or its text fails :func:`is_valid_heading` (same rules as catalog/outline
    labels — e.g. ``Table …`` captions do not start or end a chunk).
    """
    matched = _match_heading(line)
    if matched is None or not is_valid_heading(matched[1]):
        return None
    return matched[0]


def _get_min_heading_level(lines: list[str], deeper_than: int = 0) -> int | None:
    """Return the shallowest heading level appearing in ``lines`` that is deeper than
    ``deeper_than``, or ``None`` if no such heading exists.

    @param lines: the already-split lines to scan
    @param deeper_than: only levels strictly deeper than this count
    @return: the shallowest qualifying heading level, or ``None``
    """
    best: int | None = None
    for line in lines:
        level = _get_heading_level(line)
        if level is not None and level > deeper_than and (best is None or level < best):
            best = level
    return best


def _get_pages(text: str) -> list[int]:
    """Return the sorted, de-duplicated ``<!-- page: N -->`` numbers referenced in a string."""
    pages: set[int] = set()
    for match in PAGE_MARKER.finditer(text or ""):
        pages.add(int(match.group(1)))
    return sorted(pages)


def _split_into_top_chunks(lines: list[str], boundary_level: int | None) -> list[dict]:
    """Split the document at its shallowest heading level.

    The boundary is the shallowest heading level in the document: ``#`` if it has any,
    otherwise the topmost level it does have. A document with no headings comes back as one
    ``number == 0`` chunk.

    @param lines: the main-content Markdown already split on newlines
    @param boundary_level: the shallowest ATX heading level, or ``None`` for none at all --
        computed once by :func:`write_chunk_files` via :func:`_get_min_heading_level`
    @return: chunks in document order, each ``{"number", "text"}``. Content before the first
        boundary heading is chunk ``number == 0``, the rest are numbered from 1. Each chunk
        keeps its own heading line at the head of its ``text``; :func:`_get_part_heading` derives
        catalog labels later, per emitted part.
    """
    if boundary_level is None:
        text = "\n".join(lines).strip()
        return [{"number": 0, "text": text}] if text else []

    preamble_lines: list[str] = []
    chunks: list[list[str]] = []
    current: list[str] | None = None

    for line in lines:
        if _get_heading_level(line) == boundary_level:
            if current is not None:
                chunks.append(current)
            current = [line]
        elif current is None:
            preamble_lines.append(line)
        else:
            current.append(line)

    if current is not None:
        chunks.append(current)

    result: list[dict] = []
    preamble_text = "\n".join(preamble_lines).strip()
    if preamble_text:
        result.append({"number": 0, "text": preamble_text})
    for chunk_number, chunk_lines in enumerate(chunks, start=1):
        result.append({"number": chunk_number, "text": "\n".join(chunk_lines).strip()})
    return result


def _subchunk_blocks(chunk_text: str, boundary_level: int) -> list[str]:
    """Split a chunk's text at its shallowest sub-heading level.

    The first block holds the chunk's own boundary heading and any lead-in text before the
    first sub-heading; each subsequent block is one sub-chunk.

    An ATX ``#`` sub-heading is the only boundary. Do not add fallbacks for bold or ALL-CAPS
    lines: they were tried and matched emphasis inside paragraphs far too often. With no
    sub-heading the whole text is one block and :func:`_split_by_paragraphs` takes over.
    """
    lines = chunk_text.split("\n")
    sub_level = _get_min_heading_level(lines, deeper_than=boundary_level)
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


def _is_standalone_heading(block: str) -> bool:
    """Whether ``block`` is only a cut-worthy ATX heading (plus optional blank/neutral lines)."""
    content: list[str] = []
    for line in block.split("\n"):
        if is_neutral(line.strip()):
            continue
        content.append(line)
    return len(content) == 1 and _get_heading_level(content[0]) is not None


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
    chunk_text: str, boundary_level: int, max_tokens: int
) -> list[str]:
    """Split an over-budget chunk into parts.

    Sub-headings (the shallowest level deeper than ``boundary_level``) come first: consecutive
    sub-chunks are joined up to the budget. A chunk with no sub-headings, or a joined part still
    over budget, is split at paragraph boundaries instead.
    """
    blocks = _subchunk_blocks(chunk_text, boundary_level)
    packed = _split_by_paragraphs(chunk_text, max_tokens) if len(blocks) <= 1 \
        else _pack_blocks(blocks, max_tokens)

    parts: list[str] = []
    for part in packed:
        if count_tokens(part) > max_tokens:
            parts.extend(_split_by_paragraphs(part, max_tokens))
        else:
            parts.append(part)
    return parts


def _is_heading_only(part: str) -> bool:
    """Whether ``part`` is nothing but cut-worthy ATX headings — no body text at all.

    Broader than :func:`_is_standalone_heading`, which requires exactly one content line: two
    consecutive headings with no prose between them are just as unusable as a chunk.
    """
    content = [line for line in part.split("\n") if not is_neutral(line.strip())]
    return bool(content) and all(_get_heading_level(line) is not None for line in content)


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
        if merged and _get_min_heading_level(part.split("\n")) is None \
                and count_tokens(part) < min_tokens:
            merged[-1] = merged[-1].rstrip() + "\n\n" + part.lstrip()
        else:
            merged.append(part)
    return merged


# Everything that is not a letter or digit in any script. Do not narrow this to ``[^a-z0-9]+``:
# that normalized a CJK or Cyrillic heading to "", hiding it from running-header detection.
_NON_ALNUM = re.compile(r"[\W_]+", re.UNICODE)


def normalize_title(text: str) -> str:
    """A comparison key for a heading: casefolded, letters and digits only, in any script.

    So ``"## 1.0 Background:"`` and ``"1.0 Background"`` both key to ``"10background"``.
    """
    return _NON_ALNUM.sub("", text.casefold())


def get_repeated_lines(
    lines: list[str], min_occurrences: int = MIN_RUNNING_HEADER_PAGES
) -> frozenset:
    """Normalized keys of lines that recur at least ``min_occurrences`` times in the document —
    running headers and footers.

    Recurrence is the only signal that works here. A running header like ``CONFIDENTIAL`` sits
    right after a ``<!-- page: N -->`` marker, exactly as isolated as a real heading at the top
    of a page, so position cannot tell them apart. Count can: a real heading appears once.

    @param lines: the document's lines
    @param min_occurrences: how many times a line must recur to count as furniture
    @return: normalized keys to refuse as headings
    """
    counts: dict[str, int] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or PAGE_MARKER_LINE.match(stripped):
            continue
        key = normalize_title(stripped)
        if key:
            counts[key] = counts.get(key, 0) + 1
    return frozenset(key for key, count in counts.items() if count >= min_occurrences)


def _get_part_heading(
    part_text: str, previous_heading: list[str] | None, repeated: frozenset = frozenset()
) -> list[str]:
    """Derive the heading array for one chunk file part in the order they appear.

    Collects ATX headings within one level of the part's first one, so a sub-sub-section does
    not clutter the label. Each must pass :func:`is_valid_heading`. A part with no heading of its
    own copies the previous chunk's array; :data:`DEFAULT_HEADING` is the last resort, when
    there is no previous entry either.

    @param part_text: the emitted chunk part
    @param previous_heading: the preceding catalog entry's heading array, when there is one
    @param repeated: normalized keys of document-wide recurring lines to refuse
        (see :func:`get_repeated_lines`) — page furniture, not headings
    @return: the heading array for this part
    """
    lines = part_text.split("\n")
    beginning_level: int | None = None
    headings: list[str] = []
    for line in lines:
        atx = _match_heading(line)
        if atx is not None:
            level, text = atx
            if beginning_level is None:
                beginning_level = level
            if not (beginning_level <= level <= beginning_level + 1):
                continue
        else:
            continue
        if is_valid_heading(text) and normalize_title(text) not in repeated:
            headings.append(text)
    if headings:
        return headings
    return previous_heading or [DEFAULT_HEADING]


def _get_preamble_heading(
    part_text: str, preamble_text: str, repeated: frozenset = frozenset()
) -> list[str]:
    """Heading array for the part that carries the document preamble.

    The preamble sits before the first boundary heading, so it has none of its own and gets
    :data:`DEFAULT_HEADING`. But :func:`_pack_blocks` merges following sections into it, and
    labelling the whole merged part ``DEFAULT_HEADING`` dropped their headings from the catalog
    -- a short document came out as one untitled blob. So keep the label and append the real
    headings merged in after it.

    @param part_text: the emitted chunk part
    @param preamble_text: the preamble chunk's text, to tell "preamble only" from "merged"
    @param repeated: recurring lines to refuse (see :func:`get_repeated_lines`)
    @return: the heading array for this part
    """
    if part_text.strip() == preamble_text.strip():
        return [DEFAULT_HEADING]
    headings = _get_part_heading(part_text, None, repeated)
    return headings if headings == [DEFAULT_HEADING] else [DEFAULT_HEADING] + headings


def chunk_file_content(text: str) -> str:
    """The exact content written for a chunk file: the chunk text plus a trailing newline.

    :func:`build_chunk_tree` records this length in ``catalog.json`` and
    :func:`write_chunk_files` writes it, so the two have to agree.
    """
    return text + "\n"


def _write_json(path: Path, data: object) -> None:
    """Write ``data`` as pretty-printed UTF-8 JSON with a trailing newline."""
    shared_docs.write_text(
        path, json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    )


def write_unchunked_outline(output_file: Path, markdown: str) -> Path:
    """Write ``Chunks/outline.json`` for a document that was never offered to the chunker.

    ``?chunk=false`` skips detection on purpose, so there is no TOC to record. The outline is
    still written so both unchunked paths leave the same shape on disk -- same keys as
    :func:`build_chunk_tree` writes when the size gate skips chunking, only the reason differs.

    @param output_file: path of the ``.md`` the outline sits beside
    @param markdown: the document, for its token count
    @return: the ``Chunks/`` directory
    """
    chunks_dir = output_file.parent / CHUNKS_DIRNAME
    shared_docs.make_dirs(chunks_dir)
    _write_json(chunks_dir / OUTLINE_NAME, {
        "toc_source": "none",
        "toc": [],
        "tokens": count_tokens(markdown),
        "chunked": False,
        "unchunkedReason": UNCHUNKED_NOT_REQUESTED,
    })
    return chunks_dir


class ChunkingSummary(NamedTuple):
    """Summary from :func:`write_chunk_files`."""

    chunks_dir: Path | None
    chunked: bool
    chunk_count: int
    toc_source: str
    logs: str


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
                    staging / chunk["file"], chunk_file_content(chunk["text"])
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


def write_chunk_files(
    markdown_content: str,
    output_file: Path,
    max_tokens: int = DEFAULT_MAX_TOKENS,
    *,
    min_structure_tokens: int = DEFAULT_MIN_STRUCTURE_TOKENS,
) -> ChunkingSummary:
    """Write ``output_file`` (``.md``) and a sibling ``Chunks/`` tree.

    Sole disk writer for parse outputs (:func:`parse_document.parse_document` and
    :func:`chunk_file`). ``markdown_content`` must already be cleaned.

    Always writes ``Chunks/outline.json``. A document below ``min_structure_tokens`` gets the
    outline only. Otherwise the body is split by headings up to ``max_tokens``.

    @param markdown_content: cleaned Markdown
    @param output_file: path of the ``.md`` to write
    @param max_tokens: max tokens per chunk before further splitting
    @param min_structure_tokens: below this, leave the document unchunked
    @return: :class:`ChunkingSummary`
    """
    tree = build_chunk_tree(
        markdown_content,
        output_file,
        max_tokens,
        min_structure_tokens,
    )
    shared_docs.make_dirs(output_file.parent)
    chunks_dir = output_file.parent / CHUNKS_DIRNAME
    toc_source = tree["outline"].get("toc_source")
    chunk_count = len(tree["chunks"])

    staging = _stage_chunks(chunks_dir, tree)
    # Chunks first, Markdown last. The two renames cannot be one atomic step, so the .md is the
    # commit marker: if it is the new one, the chunks beside it are the new set too. The other
    # order would pair new Markdown with chunks from a different revision.
    _swap_into_place(staging, chunks_dir)
    write_atomically(output_file, tree["markdown"])

    if not tree["chunked"]:
        tokens = count_tokens(tree["markdown"])
        return ChunkingSummary(
            chunks_dir=None,
            chunked=False,
            chunk_count=0,
            toc_source=toc_source,
            logs=(
                f"Skipped chunking '{output_file.name}' "
                f"({tokens} tokens < {min_structure_tokens} min_structure_tokens); "
                f"recorded chunked=false, toc_source={toc_source} in "
                f"{CHUNKS_DIRNAME}/{OUTLINE_NAME}"
            ),
        )
    return ChunkingSummary(
        chunks_dir=chunks_dir,
        chunked=True,
        chunk_count=chunk_count,
        toc_source=toc_source,
        logs=(
            f"Split '{output_file.name}' into {chunk_count} chunk file(s) in "
            f"'{CHUNKS_DIRNAME}/' (toc_source={toc_source})"
        ),
    )


def build_chunk_tree(
    markdown_content: str,
    markdown_path: Path | None,
    max_tokens: int,
    min_structure_tokens: int,
) -> dict[str, Any]:
    """Analyse and split an already-cleaned document into its chunk tree.

    Writes nothing. Callers that need the tree on disk go through :func:`write_chunk_files`.

    @param markdown_content: the full Markdown document, already cleaned
    @param markdown_path: path of the ``.md``; a sibling ``.pdf`` supplies the outline.
        ``None`` means there is no sibling to look for, so the document has no outline
    @param max_tokens: target maximum tokens per chunk
    @param min_structure_tokens: leave the document unchunked below this size
    @return: ``{"markdown", "chunked", "outline", "catalog", "chunks", "records"}``, where
        ``chunks`` is a list of ``{"file", "text"}`` in document order, ``catalog`` is ``None``
        when the document was left unchunked, and ``records`` is the sibling PDF's bookmarks
        (empty when there is no sibling PDF, or it carries none)
    """
    md_file = markdown_content

    # The outline is a sibling <stem>.pdf's own bookmarks and nothing else: either the native
    # upload staged beside the .md, or the DOC/DOCX->PDF rendition LibreOffice wrote before
    # Docling ran. The titles are taken as the PDF gives them -- no page verification against
    # the <!-- page: N --> markers, since nothing downstream reads a record's page.
    toc_records: list[dict] = []
    if markdown_path is not None:
        pdf_file = Path(markdown_path).with_suffix(".pdf")
        if pdf_file.is_file():
            toc_records = extract_bookmarks(pdf_file)

    # The size gate is the one routing decision, recorded as ``chunked``. The outline is written
    # either way so downstream always has the same shape to read.
    tokens = count_tokens(md_file)
    to_be_chunked = tokens >= min_structure_tokens
    outline = {
        "tokens": tokens,
        "toc_source": "pdf-bookmarks" if toc_records else "none",
        "toc": [
            record["title"]
            for record in toc_records
            if record.get("title") and record["level"] <= MAX_HEADING_LEVEL
        ],
        "chunked": to_be_chunked,
    }

    if not to_be_chunked:
        # Below the size gate, so it is sent whole on purpose.
        outline["unchunkedReason"] = UNCHUNKED_BELOW_THRESHOLD
        return {
            "markdown": md_file,
            "chunked": False,
            "outline": outline,
            "catalog": None,
            "chunks": [],
            "records": toc_records,
        }

    # Split once and share it; every pass below would otherwise re-split the whole document.
    md_lines = md_file.split("\n")
    # Page furniture, identified once for the whole document: a per-part view cannot tell a
    # running header from a heading, because within one page each appears exactly once.
    repeated = get_repeated_lines(md_lines)

    boundary_level = _get_min_heading_level(md_lines)

    catalog_chunks: list[dict] = []
    chunks: list[dict] = []

    # Unite consecutive top-level sections up to the token budget, then split only
    # those united parts that are still over budget (a single oversized section).
    top_chunks = _split_into_top_chunks(md_lines, boundary_level)
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
    split_level = boundary_level if boundary_level is not None else 0

    def add(name: str, text: str, heading: list[str]) -> None:
        chunks.append({"file": name, "text": text})
        catalog_chunks.append({
            "chunk_id": name,
            "headings": heading,
            "summary": "",
            "rubric_tags": [],
            "pages": _get_pages(text),
            "length": len(chunk_file_content(text)),
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
            if first_number == 0 and not catalog_chunks:
                heading = _get_preamble_heading(part_text, preamble_text, repeated)
            else:
                # Everything else gets its real heading
                heading = _get_part_heading(part_text, previous_heading, repeated)
            add(name, part_text, heading)

    return {
        "markdown": md_file,
        "chunked": True,
        "outline": outline,
        "catalog": catalog_chunks,
        "chunks": chunks,
        "records": toc_records,
    }


def chunk_file(
    file_path: str,
    max_tokens: int = DEFAULT_MAX_TOKENS,
    *,
    min_structure_tokens: int = DEFAULT_MIN_STRUCTURE_TOKENS,
) -> dict[str, Any]:
    """Split one already-parsed Markdown file into its chunk tree.

    "Already-parsed" means written by one of the converters, which is what makes the file
    already cleaned — nothing in the chunker cleans. Pointed at a hand-written ``.md``, this
    chunks it as-is.

    Documents under ``min_structure_tokens`` are not chunked (``chunks`` is 0),
    but the decision is still recorded in ``Chunks/outline.json`` (``chunked: false``).

    @param file_path: path to the parsed ``.md``
    @param max_tokens: target maximum tokens per chunk file
    @param min_structure_tokens: skip chunking when the document is smaller than this
    @return: summary dict ``{"chunks", "logs"}``
    @raise FileNotFoundError: when the file does not exist
    """
    path = Path(file_path)
    if not path.is_file():
        raise FileNotFoundError(f"File does not exist: {path}")

    markdown = path.read_text(encoding="utf-8", errors="replace")
    summary = write_chunk_files(
        markdown,
        path,
        max_tokens=max_tokens,
        min_structure_tokens=min_structure_tokens,
    )
    return {
        "chunks": summary.chunk_count,
        "logs": summary.logs,
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
