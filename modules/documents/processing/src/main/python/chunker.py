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
        Chunk-0.md        (everything before the first heading, if any)
        Chunk-1.md
        Chunk-2.md

One :func:`_split_into_chunks` pass cuts the whole document to :data:`DEFAULT_MAX_TOKENS`.

Every chunk is listed in ``catalog.json``::

    [
      {
        "chunk_id": "Chunk-1.md",
        "summary": "",
        "rubric_tags": [],
        "pageStart": 1,
        "pageEnd": 2
      }, ...
    ]
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

import shared_docs
from chunkweaver import Chunker
from chunkweaver.presets import MARKDOWN_LEVELED
from docling_batch_sizing import parse_positive_int
from heading_helpers import (
    _apply_bookmark_heading_levels,
    _get_chunk_heading,
    _get_min_atx_level,
)
from markdown_markers import (
    MAX_HEADING_LEVEL,
    PAGE_MARKER,
    PAGE_MARKER_LINE,
    count_tokens,
)
from pdf_bookmarks import extract_bookmarks

# Default maximum tokens per chunk file. A chunk larger than this is split into parts.
DEFAULT_MAX_TOKENS = 2000

# chunkweaver sizes chunks in characters, this pipeline in tokens. The same 4:1 ratio
# count_tokens uses (see markdown_markers.count_tokens_for_length), so a budget converted
# here and a chunk measured there agree.
CHARS_PER_TOKEN = 4

# chunkweaver's Markdown heading boundaries, with its horizontal-rule spec dropped.
HEADING_BOUNDARIES = [
    spec for spec in MARKDOWN_LEVELED if not spec[0].startswith("^---")
]

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

# The ``unchunkedReason`` values. Whichever it is, ``outline.json`` is written with
# ``chunked: false``, so a reader gets one shape plus the reason.
UNCHUNKED_BELOW_THRESHOLD = "below_min_structure_tokens"
UNCHUNKED_NOT_REQUESTED = "chunking_not_requested"
# the document was big enough to chunk and the splitter came back with nothing.
# Recorded rather than silently written out as an empty catalog
UNCHUNKED_NO_PARTS = "splitter_returned_no_parts"


def _get_page_bounds(text: str) -> tuple[int | None, int | None]:
    """The first and last page this chunk covers, or ``(None, None)`` when unmarked.

    Markers inside the text set the end (and usually the start). If the first non-blank line
    is not a page marker, the chunk began mid-page, so the start is one before the first
    marker. No markers means no bounds (DOCX, or a tail with no later page break).
    """
    pages: set[int] = set()
    for match in PAGE_MARKER.finditer(text or ""):
        pages.add(int(match.group(1)))
    if not pages:
        return None, None
    start = min(pages)
    end = max(pages)
    if not _starts_with_page_marker(text):
        start = max(1, start - 1)
    return start, end


def _starts_with_page_marker(text: str) -> bool:
    """Whether the first non-blank line of ``text`` is a ``<!-- page: N -->`` marker."""
    for line in (text or "").split("\n"):
        stripped = line.strip()
        if stripped == "":
            continue
        return PAGE_MARKER_LINE.match(stripped) is not None
    return False


def _split_into_chunks(lines: list[str], max_tokens: int) -> list[dict]:
    """Split the whole document into parts that fit the budget via chunkweaver Chunker.

    @param lines: the main-content Markdown, heading levels already resolved
    @param max_tokens: the token budget per part
    @return: parts in document order, each ``{"number", "text"}``, none ending on a
        ``<!-- page: N -->`` run. Content before the first heading is part ``number == 0``,
        the rest are numbered from 1.
    """
    budget = max_tokens * CHARS_PER_TOKEN
    cut = Chunker(
        target_size=budget,
        overlap=0,
        min_size=min(MIN_TAIL_TOKENS * CHARS_PER_TOKEN, budget // 2),
        boundaries=HEADING_BOUNDARIES,
        fallback="paragraph",
    ).chunk("\n".join(lines))
    parts = _move_trailing_page_markers([
        {"text": text.strip()} for text in cut if text.strip()
    ])
    if not parts:
        return []
    # A first part that does not open on a heading is the preamble.
    first_number = 0 if not _get_chunk_heading(parts[0]["text"]) else 1
    return [
        {**part, "number": first_number + offset, "text": part["text"].strip()}
        for offset, part in enumerate(parts)
    ]


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


def _move_trailing_page_markers(parts: list[dict]) -> list[dict]:
    """Never leave a ``<!-- page: N -->`` marker at the end of a part: move trailing marker
    runs to the start of the next part. Empty parts left behind are dropped. The last
    part is unchanged (nowhere to move markers).
    """
    if len(parts) < 2:
        return parts
    result = [dict(part) for part in parts]
    for index in range(len(result) - 1):
        extracted = _split_trailing_page_markers(result[index]["text"])
        if extracted is None:
            continue
        body, markers = extracted
        result[index]["text"] = body
        result[index + 1]["text"] = (
            markers + "\n\n" + result[index + 1]["text"].lstrip()
        ).strip()
    return [part for part in result if part["text"].strip()]


def _merge_small_chunks(parts: list[dict], min_tokens: int) -> list[dict]:
    """Fold a part smaller than ``min_tokens`` into a neighbour, without changing branch.

    A part's level is its shallowest cut-worthy ATX heading (see :func:`_get_heading_level`
    / :func:`is_valid_heading`); lines that look like headings but fail validation, e.g.
    ``## Table …``, do not count. A part with no heading has no level.

    Which way a small part folds is decided by that level, because dissolving the wrong
    boundary files a heading under a section it does not belong to -- a ``### 3.5.7`` merged
    into the ``## 3.6`` that follows it is labelled, and summarised, as part of 3.6:

    * **forward**, into the next part, when the next level is the same or deeper (a sibling or
      a child), or the next part has no heading at all -- that part is the body this heading
      introduces, and a heading must not be separated from it.
    * **backward**, into the previous part, when the previous level is the same or shallower: a
      sibling shares this part's parent, and anything shallower is that parent. Not when the
      previous part has no heading, because then there is no way to tell which branch it is
      the tail of.
    * a part with **no heading** always folds backward: it is a continuation, so it belongs to
      whatever it continues.

    Forward is tried first, so a small parent goes with the children under it rather than back
    into the section before. Repeated to a fixpoint, so a run of small siblings coalesces
    instead of merging one at a time. The merge is applied even when it pushes the result over
    the token budget: a 62-token chunk holding one sub-heading is worse than an over-budget one.
    """
    def joined(left: dict, right: dict) -> dict:
        return {"text": left["text"].rstrip() + "\n\n" + right["text"].lstrip()}

    def level_of(part: dict) -> int | None:
        return _get_min_atx_level(part["text"].split("\n"))

    result = [dict(part) for part in parts]
    merging = True
    while merging:
        merging = False
        for index, part in enumerate(result):
            if count_tokens(part["text"]) >= min_tokens:
                continue
            level = level_of(part)
            if level is None:
                if index > 0:
                    result[index - 1:index + 1] = [joined(result[index - 1], part)]
                    merging = True
                    break
                continue
            if index + 1 < len(result):
                following = level_of(result[index + 1])
                if following is None or following >= level:
                    result[index:index + 2] = [joined(part, result[index + 1])]
                    merging = True
                    break
            if index > 0:
                previous = level_of(result[index - 1])
                if previous is not None and previous <= level:
                    result[index - 1:index + 1] = [joined(result[index - 1], part)]
                    merging = True
                    break
    return result


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
        bookmark titles as strings (empty when there is no sibling PDF, or it carries none)
    """
    md_file = markdown_content

    # Get bookmarks from PDF if available, levels not deeper than MAX_HEADING_LEVEL
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
        "bookmarks": [bookmark["title"] for bookmark in pdf_bookmarks],
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

    # Correct the markdown header levels according to the PDF bookmarks levels
    md_lines = md_file.split("\n")
    md_lines = _apply_bookmark_heading_levels(md_lines, pdf_bookmarks)
    md_file = "\n".join(md_lines)

    catalog_chunks: list[dict] = []
    chunks: list[dict] = []

    # Returns array [{"number": chunk_number, "text": text}]
    top_chunks = _split_into_chunks(md_lines, max_tokens)
    top_texts = [chunk for chunk in top_chunks if chunk["text"]]
    if not top_texts:
        # Past the size gate, so there was a document to cut. Nothing back means the splitter
        # failed on it -- stop here and say so, rather than write an empty catalog.
        # ``chunked`` was set True by the gate above and has to be corrected, or outline.json
        # goes out claiming both that the document was chunked and why it was not.
        outline["chunked"] = False
        outline["unchunkedReason"] = UNCHUNKED_NO_PARTS
        return {
            "markdown": md_file,
            "chunked": False,
            "outline": outline,
            "catalog": None,
            "chunks": [],
        }

    packed = _merge_small_chunks(top_texts, MIN_TAIL_TOKENS)

    def add(name: str, part: dict) -> None:
        text = part["text"]
        page_start, page_end = _get_page_bounds(text)
        chunks.append({"file": name, "text": text})
        catalog_chunks.append({
            "chunk_id": name,
            "summary": "",
            "rubric_tags": [],
            "pageStart": page_start,
            "pageEnd": page_end,
        })

    # Prefer Chunk-0 when the document has a leading preamble; otherwise start at 1.
    first_number = 0 if (top_chunks and top_chunks[0]["number"] == 0) else 1
    for offset, part in enumerate(packed):
        add(f"Chunk-{first_number + offset}.md", part)

    # Fill missing page bounds from surrounding chunks: chunks without explicit page markers
    # inherit the page number from the previous chunk's end, or the next chunk's start.
    for i in range(len(catalog_chunks)):
        chunk = catalog_chunks[i]
        if chunk["pageStart"] is None and chunk["pageEnd"] is None:
            # Try to infer from previous chunk
            if i > 0 and catalog_chunks[i - 1]["pageEnd"] is not None:
                inferred_page = catalog_chunks[i - 1]["pageEnd"]
                chunk["pageStart"] = inferred_page
                chunk["pageEnd"] = inferred_page
            # Fall back to next chunk's start if previous had nothing
            elif i < len(catalog_chunks) - 1 and catalog_chunks[i + 1]["pageStart"] is not None:
                inferred_page = catalog_chunks[i + 1]["pageStart"]
                chunk["pageStart"] = inferred_page
                chunk["pageEnd"] = inferred_page

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
        reason = tree["outline"].get("unchunkedReason")
        if reason == UNCHUNKED_NO_PARTS:
            # A failure, not a routing decision: the document cleared the size gate and the
            # splitter still produced nothing.
            return {
                "chunks": 0,
                "chunked": False,
                "chunks_dir": None,
                "logs": (
                    f"FAILED to chunk '{path.name}': {tokens} tokens went in and the splitter "
                    f"returned no parts; recorded {reason} in "
                    f"{CHUNKS_DIRNAME}/{OUTLINE_NAME}"
                ),
            }
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
