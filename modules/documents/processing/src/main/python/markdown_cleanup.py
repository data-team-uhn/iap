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

"""Post-processing cleanup for generated markdown output."""

import html
import re
from pathlib import Path

from markdown_markers import PAGE_MARKER_SPLIT, defang_page_markers

#
# Matches empty Markdown-like headings, decorative lines, symbol-only lines, box-drawing lines.
#
_GARBAGE_LINE = re.compile(
    r"^(?!\s*$)(?!.*[^\W_])(?!.*[|-]).+$",
    re.UNICODE,
)

MIN_RUN_LENGTH = 25

# Bounded, because the run is read with int(): CPython refuses an integer literal over 4300
# digits, so an unbounded pattern let a page whose first non-blank line was a long digit run
# fail a document that had converted correctly. No line number is nine digits long.
_LINE_NUMBER = re.compile(r"^\d{1,9}$")
_IMAGE_PLACEHOLDER = re.compile(r"^\s*<!--\s*image\s*-->\s*$")


def _is_consecutive(values: list[int]) -> bool:
    """Return True when values form a +1 sequence."""
    if len(values) < 2:
        return True
    return all(values[index + 1] - values[index] == 1 for index in range(len(values) - 1))


def _get_leading_line_number_run(lines: list[str]) -> tuple[list[int], int]:
    """
    Scan lines from the top and return a leading digit-only run.

    @return: (run values, index of first line after the run)
    """
    index = 0
    run: list[int] = []

    while index < len(lines):
        stripped = lines[index].strip()
        if stripped == "":
            index += 1
            continue
        if _LINE_NUMBER.fullmatch(stripped):
            run.append(int(stripped))
            index += 1
            while index < len(lines) and lines[index].strip() == "":
                index += 1
            continue
        break

    return run, index


def cleanup_page_leading_line_numbers(page_md: str) -> str:
    """
    Remove a leading leading line-number block from one page body.

    @param page_md: markdown for a single page body (no page header)
    @return: page markdown with leading line numbers removed when detected
    """
    if not page_md:
        return page_md

    lines = page_md.split("\n")
    run, end_index = _get_leading_line_number_run(lines)
    if len(run) < MIN_RUN_LENGTH or not _is_consecutive(run):
        return page_md

    # An index rather than repeated re-slicing: for a DOCX there are no page markers, so the
    # whole document is one page and this run is its entire leading blank block.
    start = end_index
    while start < len(lines) and lines[start].strip() == "":
        start += 1
    return "\n".join(lines[start:])


def get_source_file_basename(source_file: str) -> str:
    """Return the final component of a client-supplied file name.

    Upload names can come from another operating system, so normalize Windows separators before
    :class:`Path` sees them -- otherwise a Windows path reaching the Linux daemon leaks its
    directory components into the metadata. Whitespace is collapsed in the result.
    """
    name = Path(source_file.replace("\\", "/")).name
    return " ".join(name.split())


def _clean_page(page_md: str) -> str:
    """Unescape and tidy one page body, which holds none of the parser's own page markers.

    @param page_md: the text between two markers, or the whole document when there are none
    @return: the cleaned body
    """
    # Named and numeric entities (&amp;, &#38;, &#x26;, …) before other cleanup.
    unescaped = defang_page_markers(html.unescape(page_md))
    without_line_numbers = cleanup_page_leading_line_numbers(unescaped)
    kept_lines = [
        line
        for line in without_line_numbers.split("\n")
        if not _GARBAGE_LINE.match(line) and not _IMAGE_PLACEHOLDER.match(line)
    ]
    return "\n".join(kept_lines)


def clean_markdown(md: str) -> str:
    """
    Unescape HTML entities, collapse blank lines, remove empty headings / image
    placeholders, and strip decorative garbage lines.

    Docling often emits ``&amp;``, ``&lt;``, and numeric entities; turning those
    back into real characters keeps bookmark matching and chunk text readable.

    The document is cut on the parser's page markers first, so unescaping only ever runs on
    the text between them. Unescaping first turned a submitted ``&lt;!-- page: 9 --&gt;`` into
    a real marker, and markers are what a chunk's recorded pages are worked out from.

    @param md: Markdown as exported by Docling, or an empty value
    @return: the cleaned text; ``""`` for empty input
    """
    if not md:
        return ""

    # Odd indices are the markers themselves, kept exactly as the parser wrote them.
    parts = PAGE_MARKER_SPLIT.split(md)
    cleaned = [
        part if index % 2 else _clean_page(part)
        for index, part in enumerate(parts)
    ]
    return re.sub(r"\n{3,}", "\n\n", "".join(cleaned)).strip()
