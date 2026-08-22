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

# Heading recorded for the leading chunk (content before the first heading) and any other
# chunk that has no heading of its own.
DEFAULT_HEADING = "General Information"

# Letters in any script. Digits and punctuation are dropped so ``"1.0 Background"`` and
# ``"Background"`` share a key; do not narrow this to ASCII or CJK/Cyrillic titles vanish.
_NON_LETTER = re.compile(r"[\W\d_]+", re.UNICODE)

# A whole-line bold span: ``**Background**`` or ``__Background__``.
_BOLD_LINE = re.compile(r"^(\*\*|__)(.+)\1$")

# Words that make an ATX line a caption or stamp, not a section heading.
_REJECTED_HEADING_WORDS = ("table", "confidential")


def is_neutral(stripped: str) -> bool:
    """Lines that neither extend nor break a region: blanks, page markers, rules."""
    return stripped == "" or RULE_LINE.match(stripped) is not None \
        or PAGE_MARKER_LINE.match(stripped) is not None


def is_valid_heading(text: str) -> bool:
    """Whether a heading candidate is usable: longer than 4 characters
    (:data:`markdown_markers.MIN_HEADING_CHARS`), within the shared word limits
    and not a table caption (text already
    stripped of ``#`` / ``**`` markers must not start with ``Table ``).
    """
    if text.casefold().startswith("table "):
        return False
    if len(text) < MIN_HEADING_CHARS:
        return False
    # ``text`` is short enough to be a real heading
    words = text.split()
    if not words or len(words) > MAX_HEADING_WORDS:
        return False
    return all(len(word) <= MAX_WORD_CHARS for word in words)


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
    """A comparison key for a heading: casefolded letters only, in any script.

    So ``"## 1.0 Background:"`` and ``"1.0 Background"`` both key to ``"background"``.
    """
    return _NON_LETTER.sub("", text.casefold())


def _is_standalone_heading(block: str) -> bool:
    """Whether ``block`` is only a cut-worthy ATX heading (plus optional blank/neutral lines)."""
    content: list[str] = []
    for line in block.split("\n"):
        if is_neutral(line.strip()):
            continue
        content.append(line)
    return len(content) == 1 and _get_heading_level(content[0]) is not None


def _is_heading_only(part: str) -> bool:
    """Whether ``part`` is nothing but cut-worthy ATX headings — no body text at all.

    Broader than :func:`_is_standalone_heading`, which requires exactly one content line: two
    consecutive headings with no prose between them are just as unusable as a chunk.
    """
    content = [line for line in part.split("\n") if not is_neutral(line.strip())]
    return bool(content) and all(_get_heading_level(line) is not None for line in content)


def _get_part_heading(
    part_text: str, previous_heading: list[str] | None
) -> list[str]:
    """Derive the heading array for one chunk file part in the order they appear.

    Collects ATX headings within one level of the part's first one, so a sub-sub-section does
    not clutter the label. Each must pass :func:`is_valid_heading`. A part with no heading of its
    own copies the previous chunk's array; :data:`DEFAULT_HEADING` is the last resort, when
    there is no previous entry either.

    @param part_text: the emitted chunk part
    @param previous_heading: the preceding catalog entry's heading array, when there is one
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
        if is_valid_heading(text):
            headings.append(text)
    if headings:
        return headings
    return previous_heading or [DEFAULT_HEADING]


def _get_preamble_heading(part_text: str, preamble_text: str) -> list[str]:
    """Heading array for the part that carries the document preamble.

    The preamble sits before the first boundary heading, so it has none of its own and gets
    :data:`DEFAULT_HEADING`. But packing may merge following sections into it, and labelling
    the whole merged part ``DEFAULT_HEADING`` dropped their headings from the catalog -- a
    short document came out as one untitled blob. So keep the label and append the real
    headings merged in after it.

    @param part_text: the emitted chunk part
    @param preamble_text: the preamble chunk's text, to tell "preamble only" from "merged"
    @return: the heading array for this part
    """
    if part_text.strip() == preamble_text.strip():
        return [DEFAULT_HEADING]
    headings = _get_part_heading(part_text, None)
    return headings if headings == [DEFAULT_HEADING] else [DEFAULT_HEADING] + headings


def _has_title_line(part_text: str, title: str) -> bool:
    """Whether ``part_text`` has a content line whose normalized key equals ``title``'s.

    Used when deciding whether a bookmark title belongs to a chunk: the title still has to
    appear as a line — ATX, bold, or plain.
    """
    key = normalize_title(title)
    if not key:
        return False
    for line in part_text.split("\n"):
        stripped = line.strip()
        if is_neutral(stripped):
            continue
        atx = _match_heading(stripped)
        text = atx[1] if atx is not None else stripped
        if normalize_title(text) == key:
            return True
    return False


def _get_bookmark_level(bookmark: dict) -> int:
    """The outline level used for ATX hashes, clamped to 1..:data:`MAX_HEADING_LEVEL`."""
    level = bookmark.get("level")
    if not isinstance(level, int) or level < 1:
        return 1
    return min(level, MAX_HEADING_LEVEL)


def _is_rejected_heading(text: str) -> bool:
    """ATX noise: too short once normalized, digits-only, or a Table/Confidential caption."""
    words = text.split()
    first = words[0].casefold() if words else ""
    if first in _REJECTED_HEADING_WORDS:
        return True
    return len(normalize_title(text)) < MIN_HEADING_CHARS


def _get_bold_text(stripped: str) -> str | None:
    """Inner text of a whole-line bold span, or ``None`` if ``stripped`` is not one."""
    match = _BOLD_LINE.match(stripped)
    if match is None:
        return None
    inner = match.group(2).strip()
    return inner or None


def _is_all_caps_heading(stripped: str) -> bool:
    """Whether ``stripped`` is a standalone ALL-CAPS heading line (letters only, all upper)."""
    letters = [char for char in stripped if char.isalpha()]
    return bool(letters) and all(char.isupper() for char in letters)


def _to_body_text(line: str) -> str:
    """Strip ATX hashes or wrapping bold so the line is ordinary body text."""
    stripped = line.strip()
    atx = _match_heading(stripped)
    if atx is not None:
        return atx[1]
    bold = _get_bold_text(stripped)
    if bold is not None:
        return bold
    return stripped


def _get_page_distance(bookmark_page: object, candidate_page: int | None) -> int:
    """How far a candidate's page is from the bookmark's dest page. Missing pages sort last."""
    if not isinstance(bookmark_page, int) or candidate_page is None:
        return 10**9
    return abs(bookmark_page - candidate_page)


def _collect_heading_candidates(
    lines: list[str], out: list[str]
) -> list[dict]:
    """Standalone heading lines in ``lines``: ATX, whole-line bold, or ALL-CAPS.

    Each record is ``{title, page, key, line}``, plus ``level`` when the line is ATX.
    ``line`` is 1-based. An ATX line that is too short once normalized, digits-only, or
    starts with Table/Confidential is demoted to body in ``out`` and skipped.
    """
    candidates: list[dict] = []
    current_page: int | None = None
    for index, line in enumerate(lines):
        stripped = line.strip()
        page_match = PAGE_MARKER_LINE.match(stripped)
        if page_match is not None:
            current_page = int(page_match.group(1))
            continue
        if is_neutral(stripped) or stripped.startswith("|"):
            continue
        atx = _match_heading(stripped)
        if atx is not None:
            level, title = atx
            if _is_rejected_heading(title):
                out[index] = title
                continue
            candidates.append({
                "title": title,
                "level": level,
                "page": current_page,
                "key": normalize_title(title),
                "line": index + 1,
            })
            continue
        bold = _get_bold_text(stripped)
        if bold is not None:
            title = bold
        elif _is_all_caps_heading(stripped):
            title = stripped
        else:
            continue
        if _is_rejected_heading(title) or not is_valid_heading(title):
            continue
        key = normalize_title(title)
        if not key:
            continue
        candidates.append({
            "title": title,
            "page": current_page,
            "key": key,
            "line": index + 1,
        })
    return candidates


def _apply_bookmark_heading_levels(
    lines: list[str], pdf_bookmarks: list[dict]
) -> tuple[list[str], list[dict]]:
    """Cross-reference heading candidates with PDF bookmarks.

    Collects standalone ATX / bold / ALL-CAPS lines first. With no bookmarks those
    candidates are returned as the bookmark list. With bookmarks, each bookmark keeps the
    candidate whose page is closest; that line is promoted to the bookmark's level and extra
    title hits are demoted to body. The chosen line and page are written onto the bookmark
    and ``checked`` is set.

    @return: rewritten lines, and bookmarks (or heading candidates when there were none)
    """
    out = list(lines)
    candidates = _collect_heading_candidates(lines, out)
    if not pdf_bookmarks:
        return out, candidates
    bookmarks = [{**bookmark} for bookmark in pdf_bookmarks]
    for bookmark in bookmarks:
        key = normalize_title(bookmark.get("title") or "")
        if not key:
            continue
        matches = [candidate for candidate in candidates if candidate["key"] == key]
        if not matches:
            continue
        chosen = min(
            matches,
            key=lambda candidate: (
                _get_page_distance(bookmark.get("page"), candidate["page"]),
                candidate["line"],
            ),
        )
        if chosen["page"] is not None:
            bookmark["page"] = chosen["page"]
        bookmark["line"] = chosen["line"]
        bookmark["checked"] = True
        line_index = chosen["line"] - 1
        out[line_index] = (
            f"{'#' * _get_bookmark_level(bookmark)} {bookmark['title']}"
        )
        for extra in matches:
            if extra["line"] != chosen["line"]:
                extra_index = extra["line"] - 1
                out[extra_index] = _to_body_text(out[extra_index])
            candidates.remove(extra)
    return out, bookmarks


def _get_local_bookmark_titles(pdf_bookmarks: list[dict], part_text: str) -> list[str]:
    """Bookmark titles that belong to this chunk, in outline order, unique by key.

    A bookmark counts when its title appears as a content line in the part (ATX, bold, or
    plain). Dest page is not used.
    """
    titles: list[str] = []
    seen: set[str] = set()
    for bookmark in pdf_bookmarks:
        title = bookmark.get("title") or ""
        key = normalize_title(title)
        if not key or key in seen:
            continue
        if not _has_title_line(part_text, title):
            continue
        titles.append(title)
        seen.add(key)
    return titles


def _verify_catalog_headings(
    headings: list[str],
    part_text: str,
    pdf_bookmarks: list[dict],
    previous_heading: list[str] | None,
    *,
    is_preamble: bool,
) -> list[str]:
    """Replace ATX catalog labels with bookmark titles when PDF bookmarks exist.

    ATX in the Markdown is often wrong in both directions: a TOC or styled line becomes
    ``#``, and a real section that was only bold or ALL-CAPS never does. Bookmark titles
    on this part win. No local bookmark means inherit the previous chunk (or the default),
    not the noisy ATX list.
    """
    if not pdf_bookmarks:
        return headings
    local = _get_local_bookmark_titles(pdf_bookmarks, part_text)
    if is_preamble:
        if not local:
            return [DEFAULT_HEADING]
        # No ATX in the document at all: this "preamble" *is* the body, so the bookmarks
        # are the labels. Packed front matter still keeps the default and appends them.
        if headings == [DEFAULT_HEADING]:
            return local
        return [DEFAULT_HEADING] + local
    return local or previous_heading or [DEFAULT_HEADING]
