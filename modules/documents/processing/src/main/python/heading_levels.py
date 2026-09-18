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

"""Correct the assembled Markdown's heading levels from the PDF's bookmark outline.

A PDF is converted as page-range batches in separate worker processes, so Docling's own
heading-hierarchy pass only ever sees one batch: the same section can come out ``#`` in one
batch and ``##`` in another. The bookmark outline is the one view of the whole document, so
the levels are settled here, after the batches are concatenated.

No Docling import, so this runs in CI.
"""

from __future__ import annotations

import re
from pathlib import Path
from collections.abc import Callable

from markdown_markers import (
    HEADING,
    MAX_HEADING_LEVEL,
    MAX_HEADING_WORDS,
    MAX_WORD_CHARS,
    MIN_HEADING_CHARS,
    PAGE_MARKER_LINE,
    RULE_LINE,
)
from pdf_bookmarks import extract_bookmarks

LogFn = Callable[[str], None]

# Letters in any script. Digits are kept so numbered siblings stay distinct keys.
# Do not narrow these to ASCII or CJK/Cyrillic titles vanish.
# Everything that is not a letter or digit, in any script.
_NON_ALNUM = re.compile(r"[\W_]+", re.UNICODE)


def correct_heading_levels(
    markdown: str, markdown_path: Path | str, *, log: LogFn | None = None
) -> str:
    """Rewrite ``markdown`` heading levels from the bookmarks of the PDF beside it.

    @param markdown: the assembled Markdown, already cleaned
    @param markdown_path: where the ``.md`` will be written; the PDF is looked for beside it
    @param log: optional line logger
    @return: the Markdown, with matched heading lines set to their bookmark level
    """
    pdf_file = find_sibling_pdf(Path(markdown_path))
    if pdf_file is None:
        if log is not None:
            log(f"No PDF beside '{Path(markdown_path).name}'; heading levels left as parsed")
        return markdown

    pdf_bookmarks = [
        bookmark
        for bookmark in extract_bookmarks(pdf_file)
        if bookmark.get("title") and bookmark["level"] <= MAX_HEADING_LEVEL
    ]
    if not pdf_bookmarks:
        # extract_bookmarks answers an unreadable PDF and an unbookmarked one the same way.
        if log is not None:
            log(f"'{pdf_file.name}' yielded no usable bookmarks; heading levels left as parsed")
        return markdown

    corrected = apply_bookmark_heading_levels(markdown.split("\n"), pdf_bookmarks)
    if log is not None:
        matched = sum(1 for bookmark in pdf_bookmarks if bookmark.get("checked"))
        log(f"Set heading levels from {matched}/{len(pdf_bookmarks)} '{pdf_file.name}' bookmarks")
    return "\n".join(corrected)


def find_sibling_pdf(markdown_path: Path) -> Path | None:
    """The document's own PDF beside its ``.md``, matched without regard to the suffix's case.

    An upload named ``PROTOCOL.PDF`` is routine from Windows, and only the suffix's case can
    differ: the ``.md`` is named from the source's stem, and LibreOffice writes the sibling
    under that same stem.

    @param markdown_path: path of the ``.md``
    @return: the sibling PDF, or ``None`` when there is none
    """
    exact = markdown_path.with_suffix(".pdf")
    if exact.is_file():
        return exact
    try:
        entries = sorted(markdown_path.parent.iterdir())
    except OSError:
        return None
    return next(
        (entry for entry in entries
         if entry.stem == markdown_path.stem and entry.suffix.lower() == ".pdf"
         and entry.is_file()),
        None,
    )


def apply_bookmark_heading_levels(
    lines: list[str], pdf_bookmarks: list[dict]
) -> list[str]:
    """Rewrite matched heading lines to PDF bookmark ATX levels.

    Each bookmark is matched to ATX, bold, or ALL-CAPS lines with the same normalized
    title (letters and digits only; section numbers are kept). The hit closest in page
    wins, and when two hits share that distance the later line in the document wins.
    That line keeps its text and only its ``#`` count is set to the bookmark level;
    ``line`` / ``page`` / ``checked`` are written on the bookmark dict.

    @return: rewritten lines
    """
    out = list(lines)
    if not pdf_bookmarks:
        return out

    # One pass over the document, indexed by normalized title, rather than a pass per bookmark.
    # Both factors are the submitter's: 200 bookmarks over a 61,500-line document took 17.6s
    # here, strictly linear in their product, and it is spent holding the daemon's only parse
    # slot after the conversion itself has finished.
    candidates: dict[str, list[tuple[int, int | None]]] = {}
    current_page: int | None = None
    for index, line in enumerate(out):
        stripped = line.strip()
        page_match = PAGE_MARKER_LINE.match(stripped)
        if page_match is not None:
            current_page = int(page_match.group(1))
            continue
        if _is_neutral(stripped) or stripped.startswith("|"):
            continue
        text = _get_bookmark_match_text(stripped)
        if text is None:
            continue
        candidates.setdefault(normalize_title(text), []).append((index, current_page))

    # Match the PDF bookmarks to the lines in the document
    for bookmark in pdf_bookmarks:
        key = normalize_title(bookmark.get("title") or "")
        matches = candidates.get(key) if key else None
        if not matches:
            continue
        chosen_index, chosen_page = min(
            matches,
            key=lambda item: (
                _get_page_distance(bookmark.get("page"), item[1]),
                -item[0],  # later lines in the document win
            ),
        )
        if chosen_page is not None:
            bookmark["page"] = chosen_page
        bookmark["line"] = chosen_index + 1
        bookmark["checked"] = True
        # Keep the line text; only set the ATX level from the bookmark.
        level = _get_bookmark_level(bookmark)
        stripped = out[chosen_index].strip()
        atx = _match_atx_heading(stripped)
        text = atx[1] if atx is not None else stripped
        out[chosen_index] = f"{'#' * level} {text}"
    return out


def is_valid_heading(text: str) -> bool:
    """Whether stripped heading text is usable for bookmark matching.

    Rejects Table/Confidential captions, titles with too little letter+digit substance
    (``3.1 Aims`` passes; bare ``Aims`` or ``4.2`` do not), and headings outside the shared
    word-count limits.
    """
    words = text.split()
    if not words or len(words) > MAX_HEADING_WORDS:
        return False
    if len(_NON_ALNUM.sub("", text.casefold())) < MIN_HEADING_CHARS:
        return False
    return all(len(word) <= MAX_WORD_CHARS for word in words)


def normalize_title(text: str) -> str:
    """A comparison key for a heading: casefolded letters and digits only.

    Digits stay in the key on both the bookmark and the Markdown side, so
    ``"8.1.1.1 DaT-SPECT"`` and ``"9.3.1.1 DaT-SPECT"`` do not collide.
    """
    return _NON_ALNUM.sub("", text.casefold())


def _is_neutral(stripped: str) -> bool:
    """Lines that can never be a heading: blanks, page markers, rules."""
    return stripped == "" or RULE_LINE.match(stripped) is not None \
        or PAGE_MARKER_LINE.match(stripped) is not None


def _match_atx_heading(line: str) -> tuple[int, str] | None:
    """Match ``line`` against the ATX heading regex once, returning ``(level, text)`` --
    the heading level (number of leading ``#``) and its text with the ``#`` markers
    stripped -- or ``None`` if the line is not an ATX heading.

    Does **not** apply :func:`is_valid_heading`; callers that decide what a bookmark may
    match must filter through :func:`is_valid_heading` themselves.
    """
    match = HEADING.match(line)
    if match is None:
        return None
    return len(match.group(1)), match.group(2).strip()


def _get_bookmark_match_text(stripped: str) -> str | None:
    """Heading text from a bookmark-match candidate line, or ``None`` if not usable."""
    atx = _match_atx_heading(stripped)
    text = atx[1] if atx is not None else stripped
    if not is_valid_heading(text):
        return None
    return text


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
