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

"""Helpers that identify Markdown headings and match them to PDF bookmarks."""

from __future__ import annotations

import re

from markdown_markers import (
    HEADING,
    MAX_HEADING_LEVEL,
    MAX_HEADING_WORDS,
    MAX_WORD_CHARS,
    MIN_HEADING_CHARS,
    PAGE_MARKER_LINE,
    RULE_LINE,
)

# Letters in any script. Digits and punctuation are dropped so ``"1.0 Background"`` and
# ``"Background"`` share a matching key; do not narrow these to ASCII or CJK/Cyrillic titles vanish.
# A section number at the START of a heading: "3.1 ", "2) ", "10. ". Dropped from the key so a
# printed "3.1 Aims" matches a bookmark called "Aims". Only the leading run: a number anywhere
# else is part of the name, and dropping those merged "Objective 1" with "Objective 2".
_LEADING_NUMBER = re.compile(r"^[\s#*_]*\d+(?:\.\d+)*[.)]?\s+")

# Everything that is not a letter or digit, in any script.
_NON_ALNUM = re.compile(r"[\W_]+", re.UNICODE)

# Words that make an ATX line a caption or stamp, not a section heading.
_REJECTED_HEADING_WORDS = ("table", "confidential")

# Catalog label for Chunk-0 when the preamble has no leading ATX heading.
DEFAULT_HEADING = "General Information"


def is_neutral(stripped: str) -> bool:
    """Lines that neither extend nor break a region: blanks, page markers, rules."""
    return stripped == "" or RULE_LINE.match(stripped) is not None \
        or PAGE_MARKER_LINE.match(stripped) is not None


def is_valid_heading(text: str) -> bool:
    """Whether stripped heading text is usable for cuts, catalog labels, and outline matching.

    Rejects Table/Confidential captions, titles with too little letter+digit substance
    (``3.1 Aims`` passes; bare ``Aims`` or ``4.2`` do not), and headings outside the shared
    word-count limits.
    """
    words = text.split()
    if not words or len(words) > MAX_HEADING_WORDS:
        return False
    if words[0].casefold() in _REJECTED_HEADING_WORDS:
        return False
    if len(_NON_ALNUM.sub("", text.casefold())) < MIN_HEADING_CHARS:
        return False
    return all(len(word) <= MAX_WORD_CHARS for word in words)


def _match_atx_heading(line: str) -> tuple[int, str] | None:
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
    matched = _match_atx_heading(line)
    if matched is None or not is_valid_heading(matched[1]):
        return None
    return matched[0]


def _get_min_atx_level(lines: list[str], deeper_than: int = 0) -> int | None:
    """Return the shallowest cut-worthy ATX heading level in ``lines`` deeper than
    ``deeper_than``, or ``None`` if there is none.
    """
    best: int | None = None
    for line in lines:
        level = _get_heading_level(line)
        if level is not None and level > deeper_than and (best is None or level < best):
            best = level
    return best


def normalize_title(text: str) -> str:
    """A comparison key for a heading: casefolded, leading section number dropped.

    So ``"## 1.0 Background:"`` and ``"Background"`` both key to ``"background"``, which is what
    lets a printed heading match a PDF bookmark that carries no numbering.

    Digits that are not the leading number are kept, because they are part of the name. Dropping
    every digit merged ``"Objective 1"``, ``"Objective 2"`` and ``"Objective 3"`` into one key,
    and the caller treats same-key lines as repeats of one heading: the first kept its markers
    and the rest were demoted to body.
    """
    return _NON_ALNUM.sub("", _LEADING_NUMBER.sub("", text).casefold())


def _get_bookmark_match_text(stripped: str) -> str | None:
    """Heading text from a bookmark-match candidate line, or ``None`` if not usable."""
    atx = _match_atx_heading(stripped)
    text = atx[1] if atx is not None else stripped
    if not is_valid_heading(text):
        return None
    return text


def _get_chunk_heading(part_text: str) -> str:
    """Get heading from the first non-neutral line when it starts with ``#``."""
    for line in part_text.split("\n"):
        stripped = line.strip()
        if is_neutral(stripped):
            continue
        if not stripped.startswith("#"):
            return ""
        atx = _match_atx_heading(stripped)
        return atx[1] if atx is not None else stripped.lstrip("#").strip()
    return ""


def _get_bookmark_level(bookmark: dict) -> int:
    """The outline level used for ATX hashes, clamped to 1..:data:`MAX_HEADING_LEVEL`."""
    level = bookmark.get("level")
    if not isinstance(level, int) or level < 1:
        return 1
    return min(level, MAX_HEADING_LEVEL)


def _get_page_distance(bookmark_page: object, candidate_page: int | None) -> int:
    """How far a candidate's page is from the bookmark's dest page. Missing pages sort last."""
    if not isinstance(bookmark_page, int) or candidate_page is None:
        return 10**9
    return abs(bookmark_page - candidate_page)


def _demote_invalid_atx_headings(lines: list[str]) -> None:
    """Turn invalid ATX heading lines into body text in place."""
    for index, line in enumerate(lines):
        stripped = line.strip()
        if is_neutral(stripped) or stripped.startswith("|"):
            continue
        atx = _match_atx_heading(stripped)
        if atx is not None and not is_valid_heading(atx[1]):
            lines[index] = atx[1]


def _apply_bookmark_heading_levels(
    lines: list[str], pdf_bookmarks: list[dict]
) -> list[str]:
    """Rewrite heading lines to PDF bookmark levels and titles.

    Each bookmark is matched to ATX, bold, or ALL-CAPS lines with the same normalized
    title; the hit closest in page wins, and when two hits share that distance the later
    line in the document wins. That line is promoted, duplicates are demoted to body,
    and ``line`` / ``page`` / ``checked`` are written on the bookmark dict.
    Invalid ATX captions are demoted to body before matching.

    @return: rewritten lines
    """
    out = list(lines)
    _demote_invalid_atx_headings(out)
    if not pdf_bookmarks:
        return out

    # Collect the page number for each line in the document
    current_page: int | None = None
    line_pages: list[int | None] = [None] * len(out)
    for index, line in enumerate(out):
        stripped = line.strip()
        page_match = PAGE_MARKER_LINE.match(stripped)
        if page_match is not None:
            current_page = int(page_match.group(1))
            continue
        line_pages[index] = current_page

    # Match the PDF bookmarks to the lines in the document
    for bookmark in pdf_bookmarks:
        key = normalize_title(bookmark.get("title") or "")
        if not key:
            continue
        matches: list[tuple[int, int | None]] = []
        for index, line in enumerate(out):
            stripped = line.strip()
            if is_neutral(stripped) or stripped.startswith("|"):
                continue
            text = _get_bookmark_match_text(stripped)
            if text is None or normalize_title(text) != key:
                continue
            matches.append((index, line_pages[index]))
        if not matches:
            continue
        chosen_index, chosen_page = min(
            matches,
            key=lambda item: (
                _get_page_distance(bookmark.get("page"), item[1]),
                -item[0], # later lines in the document win
            ),
        )
        if chosen_page is not None:
            bookmark["page"] = chosen_page
        bookmark["line"] = chosen_index + 1
        bookmark["checked"] = True
        # Promote the line in markdown to the bookmark level and title
        out[chosen_index] = (
            f"{'#' * _get_bookmark_level(bookmark)} {bookmark['title']}"
        )
        for index, _page in matches:
            if index != chosen_index:
                out[index] = out[index].replace("#", "").lstrip()
    return out
