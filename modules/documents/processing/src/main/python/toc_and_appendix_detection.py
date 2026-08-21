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
TOC detection: find a "table of contents" / "contents" label, flatten table-shaped
TOCs, clean leaders/tabs, and record the cleaned block's line range plus entry lines
in ``outline.json`` (``tocStartLine`` / ``tocEndLine`` / ``toc``). Entries over 10
words or with a word over 100 characters are discarded.

Appendix detection (:func:`derive_outline`): the first Reference/Appendix section
title among the document's outline records (see :data:`REFERENCE_HEADINGS` /
:data:`APPENDIX_HEADINGS`) is resolved to its body line and recorded as ``backmatterLine``
in ``outline.json``.
"""

from __future__ import annotations

import re
from pathlib import Path

from bookmarks import (
    LineIndex,
    build_line_index,
    build_lines_catalog,
    resolve_record_line,
    verify_bookmarks,
)
from heading_numbering import numbering_depth, roman_numbering
from markdown_markers import (
    HEADING,
    PAGE_MARKER_LINE,
    RULE_LINE,
    count_tokens,
    within_word_limits,
)
from pdf_bookmarks import extract_bookmarks

# Density check for the no-table (plain-line) path: at least this many of the next
# DENSITY_WINDOW non-empty lines after the label must look like TOC entries.
MIN_DENSITY_MATCHES = 3
DENSITY_WINDOW = 8

# Tolerance, in words, for a short interstitial or leading run of non-matching text — a
# brief divider between multiple tables/entry-groups in one TOC block — before concluding
# the block has genuinely ended. Running page headers are NOT covered by this: they sit
# behind a page marker and are handled by :func:`_resume_after_page_break`, which does not
# have to fit them into a word budget.
MAX_LEADING_WORDS_FOR_CONTINUATION = 12

# After a page break inside a TOC, this many substantive lines may be running
# header/footer noise before the entries resume. A real protocol running header ("REB
# Protocol 24-5450 Version 3.0 dated 12 March 2026 Confidential Page 2 of 48") routinely
# exceeds MAX_LEADING_WORDS_FOR_CONTINUATION on its own, so the in-block word tolerance
# cannot absorb it and the page-break probe must skip it outright.
MAX_HEADER_LINES_AFTER_PAGE_BREAK = 3

# Documents shorter than this (``len(md) // 4``) skip TOC harvesting in :func:`derive_outline`
# — Stage 0.5 can send them whole. Known PDF bookmarks bypass the gate, since those are
# already in hand and even a small document needs its ``toc`` downstream.
DEFAULT_MIN_STRUCTURE_TOKENS = 20000

# Reference-section heading words/phrases recognised in outline record titles.
REFERENCE_HEADINGS = [
    "References",
    "Reference",
    "Reference List",
    "List of References",
    "Bibliography",
    "Selected Bibliography",
    "Works Cited",
    "Literature Cited",
    "Cited Literature",
    "References Cited",
    "Sources Cited",
    "Reference Materials",
    "References and Notes",
    "Notes and References",
    "References and Bibliography",
    "Bibliography and References",
]

# Appendix-section heading words/phrases recognised in outline record titles.
APPENDIX_HEADINGS = [
    "Appendix",
    "Appendices",
    "Annex",
    "Annexes",
    "Attachment",
    "Attachments",
    "Supplement",
    "Supplements",
    "Supplementary Material",
    "Supplementary Materials",
    "Supplemental Material",
    "Supplemental Materials",
    "Supporting Information",
    "Additional Materials",
]

# Leading section numbering to strip before matching a Reference/Appendix keyword, e.g.
# "18.0 ", "13 ", "10.1. ", "17  ".
# A section number at the start of a heading, with the label some documents put in front
# of it ("CHAPTER 11: REFERENCES") and the punctuation that can follow it.
_APPENDIX_NUMBERING_PREFIX = re.compile(
    r"^(?:(?:chapter|section|part|clause)\s+)?\(?\d+(?:\.\d+)*[.):]?\s+",
    re.IGNORECASE,
)


def _keyword_start_pattern(phrases: list[str]) -> re.Pattern:
    """A case-insensitive pattern matching any of ``phrases`` as a whole-word prefix."""
    alternation = "|".join(re.escape(phrase) for phrase in phrases)
    return re.compile(rf"^(?:{alternation})\b", re.IGNORECASE)


_REFERENCE_HEADING_START = _keyword_start_pattern(REFERENCE_HEADINGS)
_APPENDIX_HEADING_START = _keyword_start_pattern(APPENDIX_HEADINGS)

# A "table of contents" / "contents" label decorated with '#' and/or '*' on either side,
# e.g. "## TABLE OF CONTENTS", "**Contents**", "### Contents:".
_TOC_LABEL_DECORATED = re.compile(
    r"^[#*]+\s*(?:table\s+of\s+contents|contents)\s*[#*:]*$",
    re.IGNORECASE,
)

# The bare phrase alone, undecorated — only accepted by toc_label_lines when isolated
# by blank-line neighbors (see the module docstring's step 1).
_TOC_LABEL_BARE = re.compile(r"^table\s+of\s+contents$", re.IGNORECASE)

# A TOC entry with a page reference: title, then a tab/dash/dot-leader/multi-space
# separator, then an arabic or Roman page number. E.g. "Protocol Summary\t3",
# "1.0 General Information\t3", "Summary  -  2", "Schema.......4",
# "ABBREVIATIONS AND DEFINITIONS OF TERMS - V".
#
# Match this against an already-stripped line: :func:`is_toc_entry_line` and
# :func:`is_page_numbered_entry` do that for you. A leading ``\s*`` here used to compete
# with the ``.+?`` title for the same run of spaces, so an indented line cost cubic time —
# 400 spaces then one word took most of a second, and the document is caller input.
#
# The ``(?<![\s.…·])`` is the same hazard one level in: the title and the leader run competed
# for the same dots, so leaders with no page number after them cost quadratic time — 4k of
# them took 1.5s. Stripping does not help here, because dot leaders survive a strip.
#
# The ``(?=\S)`` on the section number's gap is the third and last of these. That ``\s+`` and
# the title and the ``\s{2,}`` separator could all claim one interior run of spaces, so a line
# like "a" + 8000 spaces + "b" cost about a second, and 20 of them in one document cost ten.
# The word limit does not help — that is two words — and nothing collapses intra-line runs
# before the scan, so a PDF text layer can carry them straight in. Requiring the gap to end on
# a non-space leaves it one place to stop.
TOC_ENTRY_PATTERN = re.compile(
    r"""
    ^
    (?!\|)                              # Do not allow a Markdown table row
    (?:[-*+]\s+)?                       # Optional bullet marker
    (?:\*{1,3})?                        # Optional Markdown emphasis
    (?:                                 # Optional section number
        \(?
        (?:\d+|[A-Za-z])
        (?:\.\d+)*
        [.)]?
        \s+(?=\S)                       # ...whose gap ends at the title, see below
    )?
    .+?                                 # Section title
    (?<![\s.…·])                        # ...which cannot end on a separator character
    (?:
        \t+                             # Tab before page number
        |
        \s+[-–—]\s+                     # Hyphen/dash separator
        |
        [.…·]{2,}\s*                    # Dot leaders
        |
        \s{2,}                          # Multiple spaces
    )
    (?:page\s*)?                        # Optional word "Page"
    (?:\d{1,4}|[ivxlcdm]{1,8})          # Arabic or Roman page number
    \s*
    \*{0,3}                             # Optional closing Markdown emphasis
    \s*$
    """,
    re.IGNORECASE | re.VERBOSE,
)

# A numbered/lettered outline entry with no page reference at all, e.g.
# "1.0 **Study Team, Disclosures, and Patient Partners**", "2.0 **Introduction**".
# Also expects an already-stripped line, for the reason on TOC_ENTRY_PATTERN above.
TOC_OUTLINE_ENTRY_PATTERN = re.compile(
    r"""
    ^
    (?!\|)
    (?:[-*+]\s+)?
    \*{0,3}
    (?:
        \d+(?:\.\d+)*\.?
        |
        [A-Za-z][.)]
    )
    \s+
    \S.+?
    \*{0,3}
    \s*$
    """,
    re.IGNORECASE | re.VERBOSE,
)


def is_toc_entry_line(line: str) -> bool:
    """Whether ``line`` looks like a TOC entry on its own: short enough to be a real
    entry rather than parsing garbage (:func:`markdown_markers.within_word_limits`), and
    matching one of the two entry patterns.

    An over-long line that is nonetheless structurally an entry is rejected here but still
    recognised by :func:`is_page_numbered_entry`, so it does not end the surrounding block.

    Leading and trailing whitespace is removed first; the entry patterns no longer absorb it
    themselves (see :data:`TOC_ENTRY_PATTERN`).
    """
    if not within_word_limits(line):
        return False
    stripped = line.strip()
    return (TOC_ENTRY_PATTERN.match(stripped) is not None
            or TOC_OUTLINE_ENTRY_PATTERN.match(stripped) is not None)


def is_page_numbered_entry(line: str) -> bool:
    """Whether ``line`` has the *structure* of a TOC entry with a page reference — section
    numbering, a separator, then a page number — regardless of how long it is.

    Deliberately ignores the word limit that :func:`is_toc_entry_line` applies, and
    deliberately ignores the page-less :data:`TOC_OUTLINE_ENTRY_PATTERN`. Requiring the
    trailing page number is what makes this safe to trust on a long line: ordinary numbered
    body prose ("1. The patient will be assessed at baseline.") does not match it, so this
    cannot drag the scan out of a TOC and into a numbered list.

    Used only by :func:`_scan_block`, to tell an over-long TOC entry apart from prose.

    @param line: the raw line
    @return: ``True`` when the line is shaped like a page-numbered TOC entry
    """
    return TOC_ENTRY_PATTERN.match(line.strip()) is not None


def _is_toc_header_line(stripped: str) -> bool:
    """Whether an already-stripped line is a decorated "table of contents"/"contents" label.
    """
    return _TOC_LABEL_DECORATED.match(stripped) is not None


def toc_label_lines(lines: list[str]):
    """Yield every "table of contents" / "contents" label line, in document order: decorated
    (with ``#`` and/or ``*``), or the bare phrase "table of contents" alone, isolated by
    blank-line neighbors (or start/end of document).

    Every one of them, not just the first, because "Contents" is not a reserved word. A
    protocol that lists what is in a shipment or kit under that heading (the case
    :func:`_confirm_table_toc` exists to reject) puts a decoy ahead of the real TOC, and
    stopping at the first candidate meant the decoy failed confirmation and the real TOC below
    it was never looked at -- losing ``toc``, ``backmatterLine`` and every record cut-key.
    :func:`_detect_toc` tries each in turn and keeps the first that confirms.
    """
    n = len(lines)
    for index, line in enumerate(lines):
        stripped = line.strip()
        if _is_toc_header_line(stripped):
            yield index
            continue
        if _TOC_LABEL_BARE.match(stripped):
            prev_blank = index == 0 or lines[index - 1].strip() == ""
            next_blank = index + 1 >= n or lines[index + 1].strip() == ""
            if prev_blank and next_blank:
                yield index


def _is_markdown_table_line(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("|") and stripped.endswith("|")


def _parse_table_row(line: str) -> list[str]:
    inner = line.strip()
    if inner.startswith("|"):
        inner = inner[1:]
    if inner.endswith("|"):
        inner = inner[:-1]
    return [cell.strip() for cell in inner.split("|")]


def _is_table_separator_row(cells: list[str]) -> bool:
    return bool(cells) and all(re.fullmatch(r"[\s\-:]+", cell or "") for cell in cells)


def _row_to_line(cells: list[str]) -> str:
    """Merge one table row's cells into a single line, dropping duplicate cell content
    (comparing cell values within the row) and empty cells."""
    seen: set[str] = set()
    parts: list[str] = []
    for cell in cells:
        value = cell.strip()
        if not value or value in seen:
            continue
        seen.add(value)
        parts.append(value)
    return " ".join(parts)


def _flatten_table_block(block_lines: list[str]) -> list[str]:
    """Flatten every Markdown table row in ``block_lines`` into one merged,
    duplicate-free line per row (separator rows dropped) across pages."""
    flattened: list[str] = []
    for line in block_lines:
        if not _is_markdown_table_line(line):
            flattened.append(line)
            continue
        cells = _parse_table_row(line)
        if _is_table_separator_row(cells):
            continue
        merged = _row_to_line(cells)
        if merged:
            flattened.append(merged)
    return flattened


# Blank lines allowed between a TOC label and the table under it before the table is taken to
# be something else entirely.
MAX_BLANK_LINES_BEFORE_TABLE = 4


def _find_table_start_near(lines: list[str], label_index: int) -> int | None:
    """The first line of a Markdown table under the label, or ``None``.

    Skips blank lines up to :data:`MAX_BLANK_LINES_BEFORE_TABLE` rather than looking at exactly
    two offsets: two blank lines between the label and the table used to end the search, and
    falling through to the plain-line path could not recover it — ``is_toc_entry_line`` rejects
    anything starting with ``|``, so a table row never confirms there. The label was quietly
    dropped and a real table TOC lost with it.
    """
    index = label_index + 1
    blanks = 0
    while index < len(lines) and blanks <= MAX_BLANK_LINES_BEFORE_TABLE:
        stripped = lines[index].strip()
        if stripped == "":
            blanks += 1
            index += 1
            continue
        return index if _is_markdown_table_line(lines[index]) else None
    return None


def _confirm_plain_toc(lines: list[str], label_index: int) -> bool:
    """Whether at least MIN_DENSITY_MATCHES of the next DENSITY_WINDOW non-empty lines after
    the label
    look like TOC entries."""
    checked = 0
    matches = 0
    index = label_index + 1
    n = len(lines)
    while index < n and checked < DENSITY_WINDOW:
        stripped = lines[index].strip()
        if stripped != "" and not PAGE_MARKER_LINE.match(stripped):
            checked += 1
            if is_toc_entry_line(lines[index]):
                matches += 1
        index += 1
    return matches >= MIN_DENSITY_MATCHES


# Header-row words that name a page column, and ones that name a quantity/dose column. The
# flattened header survives as the block's first line ("Section Page", "Item Qty"), and it is a
# far better discriminator than the row values: a kit table whose Qty column happens to run
# 1, 2, 3 is indistinguishable from a four-entry TOC of pages 1-4 by the numbers alone.
_PAGE_COLUMN_WORDS = frozenset({"page", "pages", "pg"})
_QUANTITY_COLUMN_WORDS = frozenset({
    "qty", "quantity", "quantities", "dose", "doses", "dosage",
    "amount", "count", "unit", "units", "volume", "lot", "strength",
})


def _names_a_quantity_column(header: str) -> bool:
    """Whether a flattened header row names a quantity column and no page column."""
    words = {word.strip(":|").casefold() for word in header.split()}
    return bool(words & _QUANTITY_COLUMN_WORDS) and not (words & _PAGE_COLUMN_WORDS)


def _confirm_table_toc(flattened: list[str]) -> bool:
    """Whether a flattened table under a "Contents" label really is a table of contents.

    The plain-line path has :func:`_confirm_plain_toc`; without the same check here any table
    within two lines of a "Contents"-shaped label was flattened into the document. That is
    destructive — separator rows dropped, every row collapsed to one space-joined line with
    duplicate cells removed — and protocols do have "Contents" sections listing what is in a
    shipment or kit, which are data tables, not outlines.

    Two checks, because neither is enough alone. The header row settles it when it names its
    columns: a page column says TOC, a quantity/dose column with no page column says data
    table. Failing that, a TOC row ends in a page number and a TOC's pages do not run
    backwards, while a quantity column often does.

    ``is_toc_entry_line`` alone is not enough here: flattening joins cells with a single space,
    which its patterns do not accept as a separator.

    The residual case is a data table with no header *and* a non-decreasing quantity column —
    ``1, 2, 3`` reads exactly like pages 1 to 3, and nothing in the numbers can separate them.
    """
    pages: list[int] = []
    rows = 0
    header_checked = False
    for line in flattened:
        stripped = line.strip()
        if stripped == "" or PAGE_MARKER_LINE.match(stripped):
            continue
        if not header_checked:
            # The first substantive line is the header, if the table has one. It still counts
            # as a row and still goes through the checks below -- "Section Page" yields no page
            # and is not an entry, so it contributes nothing but its share of DENSITY_WINDOW.
            header_checked = True
            if _names_a_quantity_column(stripped):
                return False
        rows += 1
        page = _entry_to_record(stripped)["page"]
        if page is not None:
            pages.append(page)
        elif is_toc_entry_line(stripped):  # see _is_flattened_toc_entry
            # A page-less outline entry ("2.0 Introduction") is still TOC structure; carry
            # the running page so it neither breaks nor fakes the ordering.
            pages.append(pages[-1] if pages else 0)
        if rows >= DENSITY_WINDOW:
            break
    if len(pages) < MIN_DENSITY_MATCHES:
        return False
    # Ragged on purpose: pairing each page with its successor leaves the last one out.
    return all(earlier <= later for earlier, later in zip(pages, pages[1:], strict=False))


def _is_flattened_toc_entry(text: str) -> bool:
    """Whether a flattened table row reads as a TOC entry.

    ``_flatten_table_block`` joins cells with a single space, and none of
    ``is_toc_entry_line``'s separators (tab, dash, 2+ dot leaders, 2+ spaces) accept that, so
    a row like "Protocol Summary 3" fails it however TOC-shaped it is. Trailing page numbers
    are what survive flattening, which is why :func:`_confirm_table_toc` reads them with
    :func:`_entry_to_record`; harvesting has to use the same union or it finds nothing in a
    table it has just confirmed.

    @param text: one already-flattened, already-cleaned row
    @return: whether it counts as an entry
    """
    return is_toc_entry_line(text) or _entry_to_record(text)["page"] is not None


def _skip_to_next_content(lines: list[str], index: int) -> int:
    """Skip forward over blank/rule lines only (not page markers) to the first
    substantive line at or after ``index``."""
    n = len(lines)
    while index < n and (lines[index].strip() == "" or RULE_LINE.match(lines[index].strip())):
        index += 1
    return index


def _marker_past_run(lines: list[str], start: int) -> int | None:
    """The index of a page marker reachable from ``start`` across nothing but page furniture.

    The mirror of :func:`_resume_after_page_break`: a running footer sits before the marker and
    routinely overruns :data:`MAX_LEADING_WORDS_FOR_CONTINUATION`. Gives up at a real heading,
    so body text still ends the block.

    @param lines: the document's lines
    @param start: the first index to look at
    @return: the page marker's index, or ``None`` when none is reachable
    """
    index = start
    for _ in range(MAX_HEADER_LINES_AFTER_PAGE_BREAK + 1):
        index = _skip_to_next_content(lines, index)
        if index >= len(lines):
            return None
        stripped = lines[index].strip()
        if PAGE_MARKER_LINE.match(stripped):
            return index
        if HEADING.match(stripped):
            return None
        index += 1
    return None


def _scan_block(lines: list[str], start: int, is_of_type) -> tuple[list[str], int, int | None]:
    """Within one page (or the whole document, if unpaged), collect contiguous
    ``is_of_type``-matching lines from ``start``, tolerating blank lines and a short
    (<= MAX_LEADING_WORDS_FOR_CONTINUATION words) non-matching interlude as a
    separator between matches. Stops at a page marker, a real heading line, a longer
    non-matching run, or end of document.

    Tolerated lines are only ever kept when a later match absorbs them; a trailing run of
    them belongs to whatever follows the block, so every exit rolls the boundary back past
    it. Doing that on some exits but not others deletes those lines outright: they sit outside
    ``collected`` yet inside the replaced range. So ``collected`` always equals
    ``lines[start:boundary]``, which is what makes the block safe to splice over that span.

    Absorbing a tolerated run takes a *page-numbered* match; a page-less one is equally a bold
    body heading, so it ends the block instead (see :func:`_trim_trailing_pageless`).

    @param is_of_type: predicate classifying a raw line as part of the block
        (e.g. _is_markdown_table_line or is_toc_entry_line)
    @return: (collected_lines, boundary, marker); ``boundary`` is the position in lines at
        which the block ends (exclusive), never counting a trailing tolerated run;
        ``marker`` is the index of the page marker that ended the block, or ``None`` when it
        ended at body text or end of document. A run that overruns the word tolerance still
        reports a marker sitting just past it (:func:`_marker_past_run`), so a TOC page whose
        footer blows the budget still gets its continuation probe
    """
    collected: list[str] = []
    pending: list[str] = []
    pending_words = 0
    pending_prose = False
    index = start
    n = len(lines)
    while index < n:
        line = lines[index]
        stripped = line.strip()
        if PAGE_MARKER_LINE.match(stripped):
            return collected, index - len(pending), index
        if is_of_type(line):
            if pending_prose and not is_page_numbered_entry(line):
                # Only a page-numbered entry may reach back over prose. A page-less one is
                # equally a bold body heading, and letting it absorb prose made the block hop
                # a paragraph and swallow the next heading too.
                return collected, index - len(pending), None
            collected.extend(pending)
            collected.append(line)
            pending = []
            pending_words = 0
            pending_prose = False
            index += 1
            continue
        if stripped == "":
            pending.append(line)
            index += 1
            continue
        if HEADING.match(stripped):
            # A real heading is always a hard content boundary — never tolerated as
            # page-header noise, however short (e.g. "## Glossary of Abbreviations").
            return collected, index - len(pending), None
        if is_page_numbered_entry(line):
            # Structurally a page-numbered TOC entry, but is_of_type rejected it — in
            # practice an entry longer than the word limit. It is not prose, so charging it
            # against the prose tolerance would end the block on a single long entry and
            # silently drop every entry after it (References/Appendices included).
            pending.append(line)
            index += 1
            continue
        pending_words += len(stripped.split())
        if pending_words > MAX_LEADING_WORDS_FOR_CONTINUATION:
            # Out of tolerance, but a marker may sit just past the run. A running footer lands
            # in this budget and overruns it on its own, and without reporting the marker the
            # continuation probe never ran: a TOC with a footer was truncated at page one,
            # losing References/Appendix and ``backmatterLine`` with it.
            return collected, index - len(pending), _marker_past_run(lines, index)
        pending.append(line)
        pending_prose = True
        index += 1
    return collected, index - len(pending), None


def _resume_after_page_break(lines: list[str], start: int, is_of_type) -> int | None:
    """The index at which an ``is_of_type`` region resumes after a page break, or ``None``
    when it does not resume.

    Looks at up to :data:`MAX_HEADER_LINES_AFTER_PAGE_BREAK` substantive lines past the
    page marker, skipping running header/footer noise outright rather than charging it
    against :data:`MAX_LEADING_WORDS_FOR_CONTINUATION` — a real running header is routinely
    longer than that budget, which is exactly why a TOC split across pages needs its own
    probe. Gives up at a real heading (a hard content boundary) or a re-appearing TOC label.

    @param lines: the document's lines
    @param start: the first index after the page marker
    @param is_of_type: the region predicate (see :func:`_scan_block`)
    @return: the index of the line where the region resumes, or ``None``
    """
    index = _skip_to_next_content(lines, start)
    for _ in range(MAX_HEADER_LINES_AFTER_PAGE_BREAK + 1):
        if index >= len(lines):
            return None
        stripped = lines[index].strip()
        if HEADING.match(stripped) or _is_toc_header_line(stripped):
            return None
        if is_of_type(lines[index]):
            return index
        index = _skip_to_next_content(lines, index + 1)
    return None


def _trim_trailing_pageless(
    collected: list[str], boundary: int, is_of_type
) -> tuple[list[str], int]:
    """Give a trailing run of page-less entries back to the body.

    A page-less numbered entry (:data:`TOC_OUTLINE_ENTRY_PATTERN`) is also exactly what a bold
    body heading looks like, so position decides: one trailing a block that otherwise carries
    page numbers is the first body heading. Left in, it lost the ``**``
    :func:`chunker._standout_heading` needs, and ``tocEndLine`` covered real body lines.

    A block with no page numbers anywhere is a real page-less TOC, so it is kept; one *between*
    numbered entries is not trailing, so it stays too. ``boundary`` moves back with the trim so
    the lines return to the document rather than being deleted.

    @param collected: the region's lines, equal to ``lines[start:boundary]``
    @param boundary: the region's exclusive end in the original document
    @param is_of_type: the region predicate (see :func:`_scan_block`)
    @return: ``(collected, boundary)``, trimmed when a trailing page-less run was found
    """
    if not any(is_page_numbered_entry(line) for line in collected):
        return collected, boundary

    end = len(collected)
    saw_pageless_entry = False
    while end > 0:
        line = collected[end - 1]
        stripped = line.strip()
        if is_page_numbered_entry(line):
            break
        if stripped == "" or PAGE_MARKER_LINE.match(stripped):
            # Blank lines and the page marker ride along with the run they trail
            end -= 1
            continue
        if is_of_type(line):
            saw_pageless_entry = True
            end -= 1
            continue
        break

    if not saw_pageless_entry:
        return collected, boundary
    return collected[:end], boundary - (len(collected) - end)


def _scan_region(lines: list[str], start: int, is_of_type) -> tuple[list[str], int]:
    """Collect an ``is_of_type`` region, continuing across ``<!-- page: N -->`` boundaries for
    as long as the region resumes on the next page (see :func:`_resume_after_page_break`)
    and no label re-appears first. Everything between one page's block and the line the next
    resumes on is kept — tolerated run, marker, and furniture — because the block is spliced
    over that whole span and anything left out is deleted. Furniture is filtered at the harvest
    instead. A page marker with no continuation stays outside the block, along with any
    tolerated lines leading up to it.
    Degrades to a single :func:`_scan_block` call when the document has no page markers
    (DOCX), since no marker is then ever reported — one implementation covers both the table
    path and the plain-line path, and both paged and unpaged documents.

    @param lines: the document's lines
    @param start: the first index to scan from
    @param is_of_type: the region predicate (see :func:`_scan_block`)
    @return: ``(collected_lines, boundary_index)`` — ``boundary_index`` is the original
        document's line index at which the block ends (exclusive); a page marker with no
        TOC continuation, real body text, a re-appearing label, or end of document all
        stay untouched from there on
    """
    all_collected: list[str] = []
    index = start
    while True:
        block, boundary, marker = _scan_block(lines, index, is_of_type)
        all_collected.extend(block)
        if marker is None:
            return _trim_trailing_pageless(all_collected, boundary, is_of_type)
        resume = _resume_after_page_break(lines, marker + 1, is_of_type)
        if resume is None:
            # Nothing continues the region, so the block ends before the tolerated run that
            # leads up to the marker; those lines are ordinary content again
            return _trim_trailing_pageless(all_collected, boundary, is_of_type)
        # Everything up to the resuming line is now interior to the block: the tolerated run,
        # the marker, and the furniture after it. Collecting only the marker deleted the rest,
        # since :func:`_detect_toc` replaces the whole span. They are kept as lines, not
        # entries — the harvest re-tests each one. ``resume > index``, so this terminates.
        all_collected.extend(lines[boundary:resume])
        index = resume


def _block_cleanup(text: str) -> str:
    """Whole-block cleanup: normalize long dot/dash/ellipsis-leader runs (5 or more) to
    `` - ``, remove tabs and escaped underscores (``\\_``), and collapse runs of 3 or more
    consecutive whitespace characters within a line to one space."""

    text = text.replace("\t", " ")
    text = text.replace("\\_", "")
    text = re.sub(r"\.{5,}", " - ", text)
    text = re.sub(r"-{5,}", " - ", text)
    text = re.sub(r"…{5,}", " - ", text)
    text = re.sub(r"[^\S\n]{3,}", " ", text)
    return text


def _clean_toc_line(line: str) -> str:
    """Per-line cleanup: strip ``*`` and ``#`` (emphasis/heading markers, wherever they
    appear in the line), then strip leading/trailing whitespace."""
    return line.replace("*", "").replace("#", "").strip()


def _confirm_labelled_block(lines: list[str], label_index: int):
    """Scan and confirm the TOC block under one candidate label.

    @param lines: the document's lines
    @param label_index: the candidate label's line
    @return: ``(block_lines, boundary, is_entry)``, or ``None`` when this label does not head
        a real TOC -- not fatal, because :func:`_detect_toc` moves on to the next candidate
    """
    table_start = _find_table_start_near(lines, label_index)
    if table_start is not None:
        raw_block, boundary = _scan_region(lines, table_start, _is_markdown_table_line)
        block_lines = _flatten_table_block(raw_block)
        if not _confirm_table_toc(block_lines):
            return None
        # Flattening joins cells with a single space, which none of ``is_toc_entry_line``'s
        # separators accept, so it alone would harvest nothing from a table TOC that was
        # nevertheless just confirmed as one — and the document has already been rewritten
        # by then. Recognise a row the same way :func:`_confirm_table_toc` does.
        is_entry = _is_flattened_toc_entry
    else:
        if not _confirm_plain_toc(lines, label_index):
            return None
        block_lines, boundary = _scan_region(lines, label_index + 1, is_toc_entry_line)
        is_entry = is_toc_entry_line

    if not block_lines:
        return None
    return block_lines, boundary, is_entry


def _detect_toc(md: str) -> tuple[str, dict]:
    """Detect the document's TOC and clean it in place, returning the outline fields it
    yields rather than writing them anywhere.

    :func:`derive_outline` takes the entries straight from memory.

    ``tocStartLine`` / ``tocEndLine`` describe the *returned* document, which is the only one
    any caller sees now that cleanup is unconditional. They are not interchangeable with a range
    in the input: flattening a table TOC collapses its header and separator rows, so the cleaned
    block can be shorter than the block it replaced and everything after it shifts up.

    @param md: the full assembled Markdown document
    @return: ``(document, fields)`` — the document with its TOC (if any) cleaned, and
        ``{"tocStartLine", "tocEndLine", "toc"}``; ``fields`` is empty when no TOC is found
        and the document is then returned unchanged
    """
    if not md:
        return "", {}

    lines = md.split("\n")
    # Every candidate label, not just the first: one that fails confirmation is a decoy (a
    # "Contents" section listing a kit's contents, say) and the real TOC may be below it.
    for label_index in toc_label_lines(lines):
        confirmed = _confirm_labelled_block(lines, label_index)
        if confirmed is not None:
            block_lines, boundary, is_entry = confirmed
            break
    else:
        return md, {}

    # Normalized to a level-2 ATX heading rather than stripped bare, which tidies what
    # Docling emits — often an invalid run of '#' (11 of them on one real protocol). It
    # never becomes a chunk boundary: :func:`chunker.valid_heading` rejects any heading
    # starting with "Table ".
    label_line = f"## {_clean_toc_line(lines[label_index])}".rstrip()
    body_text = _block_cleanup("\n".join(block_lines))
    body_lines: list[str] = []
    for line in body_text.split("\n"):
        stripped = line.strip()
        if stripped == "":
            # Keep blank lines inside the TOC block; do not collapse them here.
            body_lines.append("")
            continue
        if PAGE_MARKER_LINE.match(stripped):
            body_lines.append(stripped)
            continue
        cleaned_line = _clean_toc_line(line)
        if cleaned_line:
            body_lines.append(cleaned_line)
    if not any(line for line in body_lines):
        return md, {}
    cleaned = ([label_line] if label_line else []) + body_lines
    # Replace the original TOC span with the cleaned block; leave surrounding lines as-is.
    rebuilt = lines[:label_index] + cleaned + lines[boundary:]
    # No trailing newline is appended. The join already restores whatever the input had (a
    # document ending in one splits to a final ""), and adding one only when a TOC was found
    # made ``tokens`` differ by a character between the two paths for the same document.
    result = "\n".join(rebuilt)

    # Entry lines only — label / page markers / interstitial noise stay out of ``toc``.
    # Match against pre-block-cleanup text so tab/leader separators still satisfy
    # :func:`is_toc_entry_line`, then store the same cleaned form as in the body.
    toc_entries: list[str] = []
    for line in block_lines:
        stripped = line.strip()
        if not stripped or PAGE_MARKER_LINE.match(stripped):
            continue
        candidate = _clean_toc_line(line)
        if candidate and is_entry(candidate):
            stored = _block_cleanup(candidate).strip()
            if stored:
                toc_entries.append(stored)
    fields = {
        "tocStartLine": label_index,
        "tocEndLine": label_index + len(cleaned) - 1,
        "toc": toc_entries,
    }
    return result, fields


# Trailing "<separator><page number>" of a cleaned TOC entry, capturing the separator and the
# page (arabic or roman). The separator is a dash, dot-leaders, or whitespace (a tab collapses
# to one space); which one it is decides how much the roman alternative can be trusted, see
# :func:`_page_number`.
#
# Searched over the tail only, see :data:`_ENTRY_PAGE_TAIL`.
_ENTRY_PAGE = re.compile(
    r"""
    (?P<sep>
        \s+[-–—]\s+
        | \s*[.…·]{2,}\s*
        | \s+
    )
    (?:page\s*)?
    (?P<page>\d{1,4}|[ivxlcdm]{1,8})
    \s*$
    """,
    re.IGNORECASE | re.VERBOSE,
)

# A dash or dot-leader separator is a strong TOC signal; a bare space is not.
_STRONG_SEPARATOR = re.compile(r"[-–—.…·]")

# How much of the end of an entry :data:`_ENTRY_PAGE` is searched over. Every separator
# alternative starts with a whitespace run, so scanning the whole string made ``search`` retry
# from each position in turn -- quadratic, and 4k characters already cost 0.7s.
# :func:`_confirm_table_toc` hands this flattened table rows, which have no length limit at
# all. A page number lives at the end of the entry by definition, so the tail is the only part
# worth looking at.
#
# The page number itself is never affected: a separator run longer than the window simply gets
# matched from inside the run rather than from its head. The title can differ in one case --
# a dot-leader run longer than the window stays on the title, where whitespace is stripped off
# either way. No caller sees it: cleaned TOC entries reach here with dot runs already
# collapsed to " - " by :func:`_block_cleanup`, and the one caller that passes raw text,
# :func:`_confirm_table_toc`, reads only ``page``.
_ENTRY_PAGE_TAIL = 200

# A well-formed roman numeral, so "Appendix D" is not read as page 500. The lookahead keeps
# the empty string out, which every group being optional would otherwise allow.
_ROMAN_PAGE = re.compile(
    r"(?=[mdclxvi])m{0,4}(?:cm|cd|d?c{0,3})(?:xc|xl|l?x{0,3})(?:ix|iv|v?i{0,3})$",
    re.IGNORECASE,
)


def _page_number(token: str, *, strong_separator: bool) -> int | None:
    """Parse a TOC page token (arabic ``"12"`` or roman ``"iv"``) to an int, or ``None``.

    Rejects a roman-looking token that is really a section letter. ``"Appendix C"`` and
    ``"Participant ID"`` both end in letters drawn from the roman alphabet, and reading them
    as pages truncated the title to ``"Appendix"`` / ``"Participant"`` — which then never
    matched its body heading, so the appendix was never marked and got summarized.

    Two things separate a page from a label: the token has to be a valid numeral at all, and
    after a bare space it also has to be lowercase. Printed front matter is numbered in
    lowercase roman ("iv"), while section labels are uppercase ("Appendix C"). After a dash
    or dot leaders the entry is unambiguously a TOC line, so either case is fine there.
    """
    if token.isdigit():
        return int(token)
    if not _ROMAN_PAGE.match(token):
        return None
    if not strong_separator and token != token.lower():
        return None
    vector = roman_numbering(token)
    return vector[0] if vector else None


def _entry_to_record(entry: str) -> dict:
    """Convert a cleaned TOC entry string into an outline record: title (trailing page
    stripped), ``page`` (its page number, or ``None``), ``level`` (numbering depth, or ``None``).

    The title only loses its tail when that tail really is a page number; otherwise the whole
    entry is the title.
    """
    match = _ENTRY_PAGE.search(entry, max(0, len(entry) - _ENTRY_PAGE_TAIL))
    page = None
    if match:
        page = _page_number(
            match.group("page"),
            strong_separator=bool(_STRONG_SEPARATOR.search(match.group("sep"))),
        )
    title = entry[: match.start()].strip() if page is not None else entry.strip()
    return {"title": title, "level": numbering_depth(title) or None, "page": page}


def _records_from_toc_strings(toc_strings: list) -> list[dict]:
    """Build outline records from the entry strings recorded by :func:`_detect_toc`."""
    records: list[dict] = []
    for entry in toc_strings:
        record = _entry_to_record(entry)
        if record["title"]:
            records.append(record)
    return records


def _is_backmatter_title(title: str) -> bool:
    """Whether ``title`` names a Reference- or Appendix-section (numbering prefix ignored)."""
    text = _APPENDIX_NUMBERING_PREFIX.sub("", title).strip()
    return bool(_REFERENCE_HEADING_START.match(text) or _APPENDIX_HEADING_START.match(text))


def _names_backmatter_exactly(text: str) -> bool:
    """Whether a heading's own text names a backmatter section, numbering and punctuation aside.

    Stricter than :func:`_is_backmatter_title` on the Reference side, and deliberately so. That
    one takes titles from PDF bookmarks or a printed TOC, which are curated; this one is applied
    to every heading in the body, where "Reference Documents" and "Reference Safety
    Information" are ordinary front-matter section names in a protocol. Splitting there would
    send most of the document into the chunk that is never summarized, so a Reference heading
    has to *be* one of :data:`REFERENCE_HEADINGS` rather than merely start with one.

    Appendix headings keep prefix matching: they carry an identifier ("Appendix A: Schedule",
    "Annex 2"), and an Appendix heading early in a document is not a thing.

    @param text: the heading's text, without its leading ``#`` marks
    @return: whether it names a Reference or Appendix section
    """
    stripped = _APPENDIX_NUMBERING_PREFIX.sub("", text).strip()
    stripped = stripped.strip("*_ 	:.-").strip()
    if any(stripped.lower() == phrase.lower() for phrase in REFERENCE_HEADINGS):
        return True
    return bool(_APPENDIX_HEADING_START.match(stripped))


def _has_body_after(lines: list[str], index: int) -> bool:
    """Whether anything but blank lines and page markers follows ``index``.

    A backmatter split needs something to split off. A document ending on a bare
    "Appendix Section Title" would otherwise put a chunk holding nothing but that title into
    the outline, which is the one thing the chunker's heading-only merge exists to prevent --
    and the backmatter chunk is exempt from that merge.

    @param lines: the document's lines
    @param index: the heading's index
    @return: whether any substantive line follows
    """
    for line in lines[index + 1:]:
        stripped = line.strip()
        if stripped == "" or PAGE_MARKER_LINE.match(stripped):
            continue
        return True
    return False


def backmatter_from_headings(
    lines: list[str],
    toc_range: tuple[int, int] | None = None,
) -> int | None:
    """The line of the first heading in the body that names a backmatter section.

    The fallback for :func:`backmatter_from_records`. A document with no PDF bookmarks and no
    printed TOC has no records at all, so nothing was ever offered to the record lookup -- and
    its ``## 11 References`` is still sitting in the body, which is the only thing marking where
    the references begin.

    @param lines: the document's lines, split the way :func:`derive_outline` splits them
    @param toc_range: inclusive ``(start, end)`` line range of the printed TOC, when known
    @return: the heading's line index, or ``None``
    """
    for index, line in enumerate(lines):
        if toc_range is not None and toc_range[0] <= index <= toc_range[1]:
            continue
        heading = HEADING.match(line.strip())
        if (
            heading is not None
            and _names_backmatter_exactly(heading.group(2))
            and _has_body_after(lines, index)
        ):
            return index
    return None


def backmatter_from_records(
    records: list[dict],
    index: LineIndex,
    toc_range: tuple[int, int] | None = None,
) -> int | None:
    """Get the body line index of the first Reference/Appendix record that resolves to a unique line,
    or ``None`` -- the outline-based replacement for the heuristic body scan.

    ``toc_range`` must be passed whenever it is known to avoid, because it can be styled as headings.

    @param records: the document's outline records, in order
    @param index: precomputed :func:`bookmarks.build_line_index` for the document
    @param toc_range: inclusive ``(start, end)`` line range of the printed TOC, when known
    @return: the backmatter body line index, or ``None``
    """
    candidates = [record for record in records if _is_backmatter_title(record.get("title") or "")]
    if not candidates:
        return None
    exclude = (
        frozenset(range(toc_range[0], toc_range[1] + 1)) if toc_range is not None else frozenset()
    )
    for record in candidates:
        line = resolve_record_line(index, record, exclude=exclude)
        if line is not None:
            return line
    return None


def derive_outline(
    md: str,
    markdown_path: Path | None = None,
    min_structure_tokens: int = DEFAULT_MIN_STRUCTURE_TOKENS,
) -> tuple[str, dict, list[dict], LineIndex | None]:
    """Derive a document's outline .

    @param md: the full assembled Markdown document
    @param markdown_path: path of the ``.md``; a sibling ``.pdf`` supplies embedded bookmarks
    @param min_structure_tokens: skip printed-TOC harvesting below this size when there are
        no PDF bookmarks (small docs are sent whole downstream)
    @return: ``(document, outline_fields, records, line_index)`` — the document with its printed
        TOC cleaned in place, the outline fields, the records the outline was built from, and a
        line index shared with later chunk cut-key resolution. When the size gate skips the
        pass, returns ``(md, {}, [], None)``.
    """
    # A sibling <stem>.pdf is the only disk source of real bookmarks: native PDFs and the
    # DOC/DOCX→PDF renditions LibreOffice writes beside the source before Docling runs.
    # Page verification waits until the shared catalog is built below, so the PDF and
    # printed-TOC paths share one scan.
    bookmarks: list[dict] = []
    if markdown_path is not None:
        pdf_file = markdown_path.with_suffix(".pdf")
        if pdf_file.is_file():
            bookmarks = extract_bookmarks(pdf_file)

    # skip TOC harvesting for small documents with no bookmarks
    if not bookmarks and count_tokens(md) < min_structure_tokens:
        return md, {}, [], None

    # detect TOC regardsless if we have bookmarks from pdf
    md_file, toc_summary = _detect_toc(md)
    toc_range = (
        (toc_summary["tocStartLine"], toc_summary["tocEndLine"])
        if "tocStartLine" in toc_summary
        else None
    )

    # Split and index once: verification, backmatter lookup, and later cut-key resolution
    # all need the same line walk.
    md_lines = md_file.split("\n")
    positions = build_lines_catalog(md_lines)
    line_index = build_line_index(positions)

    if bookmarks:
        toc_records = verify_bookmarks(bookmarks, md_file, positions, toc_range)
        toc_source = "pdf-bookmarks"
    else:
        # Only this path harvests a printed TOC; the source it ends up reporting depends on
        # whether any harvested entry could be found in the body.
        toc_records = verify_bookmarks(
            _records_from_toc_strings(toc_summary.get("toc", [])),
            md_file,
            positions,
            toc_range,
        )
        toc_source = "md-toc" if toc_records else "none"

    # One dict for the whole outline.
    toc_summary["tokens"] = count_tokens(md_file)
    toc_summary["toc_source"] = toc_source
    toc_summary["toc"] = [record["title"] for record in toc_records if record.get("title")]
    backmatter_line = backmatter_from_records(toc_records, line_index, toc_range)
    if backmatter_line is None:
        # No record resolved to one: either the outline has no Reference/Appendix entry, or
        # there is no outline at all. The heading in the body still marks the split.
        backmatter_line = backmatter_from_headings(md_lines, toc_range)
    if backmatter_line is not None:
        toc_summary["backmatterLine"] = backmatter_line
    return md_file, toc_summary, toc_records, line_index
