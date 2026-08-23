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

"""Direct unit tests for the chunker's internal split/pack helpers and heading_helpers.

These carry the fiddliest logic — page-marker migration, standalone-heading look-ahead,
over-budget splitting, small-tail folding — and were previously exercised only
indirectly through chunk_file(). Tokens are len(text) // 4 (see markdown_markers.count_tokens), so
a block of N tokens is a string of length 4*N."""

import json
from pathlib import Path

import pytest

import chunker
import heading_helpers


def _md_path(tmp_path: Path) -> Path:
    return tmp_path / "doc.md"


def _parts(*texts: str) -> list[dict]:
    """Part records for the merge passes."""
    return [{"text": text} for text in texts]


def _texts(parts: list[dict]) -> list[str]:
    """Just the text of each part, for asserting on merge behaviour."""
    return [part["text"] for part in parts]


class TestPageBounds:
    def test_span_from_first_to_last_marker(self):
        text = "<!-- page: 11 -->\nbody\n<!-- page: 12 -->"
        assert chunker._get_page_bounds(text) == (11, 12)

    def test_single_page(self):
        assert chunker._get_page_bounds("<!-- page: 11 -->\nbody") == (11, 11)

    def test_mid_page_start_backs_up_one(self):
        text = "leftover from the previous page\n<!-- page: 12 -->\nmore"
        assert chunker._get_page_bounds(text) == (11, 12)

    def test_leading_blank_then_marker_is_not_mid_page(self):
        assert chunker._get_page_bounds("\n<!-- page: 11 -->\nbody") == (11, 11)

    def test_no_markers(self):
        assert chunker._get_page_bounds("") == (None, None)
        assert chunker._get_page_bounds("plain text") == (None, None)

    def test_unordered_markers(self):
        text = "a\n<!-- page: 3 -->\nb\n<!-- page: 1 -->\n<!-- page: 3 -->"
        assert chunker._get_page_bounds(text) == (1, 3)


class TestSplitTrailingPageMarkers:
    def test_single_trailing_marker(self):
        assert chunker._split_trailing_page_markers("Body text\n\n<!-- page: 5 -->") \
            == ("Body text", "<!-- page: 5 -->")

    def test_multiple_trailing_markers(self):
        result = chunker._split_trailing_page_markers("X\n\n<!-- page: 5 -->\n<!-- page: 6 -->")
        assert result == ("X", "<!-- page: 5 -->\n<!-- page: 6 -->")

    def test_no_trailing_marker(self):
        assert chunker._split_trailing_page_markers("No markers here") is None


class TestMoveTrailingPageMarkers:
    def test_marker_moved_to_next_part(self):
        moved = chunker._move_trailing_page_markers(
            _parts("A\n\n<!-- page: 2 -->", "B body"))
        assert _texts(moved) == ["A", "<!-- page: 2 -->\n\nB body"]

    def test_single_part_unchanged(self):
        assert _texts(chunker._move_trailing_page_markers(_parts("only"))) == ["only"]

    def test_last_part_marker_left_in_place(self):
        # Nowhere to move a marker at the very end.
        texts = ["A", "B\n\n<!-- page: 9 -->"]
        assert _texts(chunker._move_trailing_page_markers(_parts(*texts))) == texts


class TestSplitterReturnedNothing:
    """A document that clears the size gate and comes back uncut is a failure, not a routing
    decision. Writing an empty catalog instead reads downstream as "chunked, no content",
    which is indistinguishable from a document that genuinely has none.
    """

    # Whitespace only, but long enough to clear a min_structure_tokens of 1.
    BLANK = " " * 200

    def test_it_is_recorded_as_unchunked_with_a_reason(self):
        tree = chunker.build_chunk_tree(self.BLANK, None, chunker.DEFAULT_MAX_TOKENS, 1)
        assert tree["chunked"] is False
        assert tree["outline"]["unchunkedReason"] == chunker.UNCHUNKED_NO_PARTS

    def test_the_outline_does_not_also_claim_it_was_chunked(self):
        # chunked is set True by the size gate before the split runs, so it has to be put back.
        tree = chunker.build_chunk_tree(self.BLANK, None, chunker.DEFAULT_MAX_TOKENS, 1)
        assert tree["outline"]["chunked"] is False

    def test_no_chunks_and_no_catalog_are_produced(self):
        tree = chunker.build_chunk_tree(self.BLANK, None, chunker.DEFAULT_MAX_TOKENS, 1)
        assert tree["chunks"] == []
        assert tree["catalog"] is None

    def test_the_markdown_still_comes_back(self):
        # The .md is still written: the parse succeeded, only the chunking did not.
        tree = chunker.build_chunk_tree(self.BLANK, None, chunker.DEFAULT_MAX_TOKENS, 1)
        assert tree["markdown"] == self.BLANK

    def test_the_log_says_it_failed_rather_than_was_skipped(self, tmp_path):
        # The size-gate log would claim the document was too small, which it was not.
        md = tmp_path / "doc.md"
        md.write_text(self.BLANK, encoding="utf-8")
        summary = chunker.chunk_file(str(md), min_structure_tokens=1)
        assert summary["chunked"] is False
        assert summary["chunks"] == 0
        assert "FAILED" in summary["logs"]
        assert chunker.UNCHUNKED_NO_PARTS in summary["logs"]

    def test_a_real_document_is_unaffected(self):
        real = "# 1.0 Alpha Section\n\n" + "Alpha prose. " * 300
        tree = chunker.build_chunk_tree(real, None, chunker.DEFAULT_MAX_TOKENS, 1)
        assert tree["chunked"] is True
        assert "unchunkedReason" not in tree["outline"]


class TestOutlineSizeGate:
    """The gate that decides whether the outline pass runs at all.

    It lives in ``derive_outline``: when there are no PDF bookmarks and the document is below
    ``min_structure_tokens``, derivation is skipped. PDF bookmarks beat the gate, because a
    bookmarked document's outline is already in hand and even a small one needs its
    ``bookmarks`` downstream.
    """

    SMALL = "# Tiny\n\n## Table of Contents\n\nAlpha\t1\nBeta\t2\nGamma\t3\n\n## Alpha\n\nbody\n"

    def _tree(self, tmp_path, monkeypatch=None, pdf_bookmarks=None):
        md_path = _md_path(tmp_path)
        if pdf_bookmarks is not None:
            (tmp_path / "doc.pdf").write_bytes(b"%PDF-1.4")
            monkeypatch.setattr(
                "chunker.extract_bookmarks",
                lambda *a, **k: pdf_bookmarks,
            )
        return chunker.build_chunk_tree(
            self.SMALL,
            md_path,
            chunker.DEFAULT_MAX_TOKENS,
            10 ** 9,
        )

    def test_small_document_without_pdf_bookmarks_is_left_alone(self, tmp_path):
        tree = self._tree(tmp_path)
        # Not even the bookmarks are rewritten: the document is sent whole, so there is
        # nothing to route.
        assert tree["markdown"] == self.SMALL
        assert tree["outline"]["bookmarks"] == []
        assert "bookmark_source" not in tree["outline"]
        assert "pdf_bookmarks" not in tree

    def test_small_document_with_pdf_bookmarks_still_gets_its_outline(self, tmp_path, monkeypatch):
        tree = self._tree(tmp_path, monkeypatch, [{"title": "Alpha", "level": 1, "page": 1}])
        assert tree["outline"]["bookmarks"] == ["Alpha"]
        assert "bookmark_source" not in tree["outline"]

    def test_both_are_still_unchunked(self, tmp_path, monkeypatch):
        # The gate above is about the outline; the chunking decision is separate and unaffected.
        assert self._tree(tmp_path)["chunked"] is False
        assert self._tree(
            tmp_path, monkeypatch, [{"title": "Alpha", "level": 1, "page": 1}]
        )["chunked"] is False

    def test_a_large_document_without_pdf_bookmarks_reports_none(self, tmp_path):
        big = self.SMALL + ("Body sentence that carries it along. " * 200)
        tree = chunker.build_chunk_tree(
            big, _md_path(tmp_path), chunker.DEFAULT_MAX_TOKENS, 1
        )
        # No sibling PDF, so no bookmarks: they come from a PDF and from nothing else. The
        # document is still chunked -- the empty outline is not a routing decision.
        assert tree["outline"]["bookmarks"] == []
        assert "bookmark_source" not in tree["outline"]
        assert tree["chunked"] is True


class TestMergeSmallTextTails:
    def test_small_text_tail_folded_into_previous(self):
        assert _texts(chunker._merge_small_text_tails(_parts("First part body.", "tiny"), 500)) == \
            ["First part body.\n\ntiny"]

    def test_tail_with_heading_never_merged(self):
        parts = ["First", "## Second Heading"]
        assert _texts(chunker._merge_small_text_tails(_parts(*parts), 500)) == parts

    def test_large_tail_not_merged(self):
        big = "w" * 4000  # 1000 tokens
        assert _texts(chunker._merge_small_text_tails(_parts("First", big), 500)) == ["First", big]


class TestSplitIntoChunks:
    def test_preamble_and_sections(self):
        lines = ["preamble text", "# Section One", "body one", "# Section Two", "body two"]
        chunks = chunker._split_into_chunks(lines, chunker.DEFAULT_MAX_TOKENS)
        assert [c["number"] for c in chunks] == [0, 1, 2]
        assert chunks[0]["text"] == "preamble text"
        # Each section keeps its own heading line at the head of its text.
        assert chunks[1]["text"] == "# Section One\nbody one"
        assert chunks[2]["text"] == "# Section Two\nbody two"

    def test_leading_page_marker_before_first_heading_is_not_preamble(self):
        lines = ["<!-- page: 1 -->", "", "# Section One", "body one"]
        chunks = chunker._split_into_chunks(lines, chunker.DEFAULT_MAX_TOKENS)
        assert [c["number"] for c in chunks] == [1]

    def test_no_headings_single_chunk(self):
        chunks = chunker._split_into_chunks(["just", "text"], chunker.DEFAULT_MAX_TOKENS)
        assert [(c["number"], c["text"]) for c in chunks] == [(0, "just\ntext")]

    def test_empty(self):
        assert chunker._split_into_chunks([], chunker.DEFAULT_MAX_TOKENS) == []

    def test_an_oversized_paragraph_is_split_rather_than_kept_whole(self):
        # Docling emits a table as one run of `|` lines with no blank line, so the old
        # blank-line-only splitter returned it as a single part far over budget.
        row = "| " + " | ".join(["Procedure with a realistic label"] + ["X"] * 9) + " |"
        chunks = chunker._split_into_chunks(["# 2.0 Schedule", ""] + [row] * 600,
                                            chunker.DEFAULT_MAX_TOKENS)
        assert len(chunks) > 1
        assert all(chunker.count_tokens(c["text"]) <= chunker.DEFAULT_MAX_TOKENS
                   for c in chunks), [chunker.count_tokens(c["text"]) for c in chunks]

    def test_nothing_is_lost_or_duplicated(self):
        # A chunk tree is the document of record, so the parts must re-form the input.
        row = "| " + " | ".join(["Procedure"] + ["X"] * 8) + " |"
        lines = ["# 1.0 Schedule", "", "Lead-in prose."] + [row] * 300 + ["", "Tail prose."]
        chunks = chunker._split_into_chunks(lines, chunker.DEFAULT_MAX_TOKENS)
        rejoined = " ".join(" ".join(c["text"] for c in chunks).split())
        assert rejoined == " ".join("\n".join(lines).split())

    def test_no_chunk_ends_on_a_page_marker(self):
        para = "Sentence one here. " * 6
        lines = ["# 1.0 Alpha", "", para, "", "<!-- page: 7 -->", "", para]
        chunks = chunker._split_into_chunks(lines, 30)
        assert not any(c["text"].rstrip().endswith("-->") for c in chunks), chunks

    def test_a_heading_with_no_bookmark_still_starts_a_chunk(self):
        # The cut follows the heading lines, not the bookmark list. Cutting only where a
        # bookmark had matched glued a real section onto its neighbour whenever the PDF
        # outline was incomplete.
        lines = ["# One Section", "body one", "# Two Section", "body two"]
        chunks = chunker._split_into_chunks(lines, chunker.DEFAULT_MAX_TOKENS)
        assert [chunk["number"] for chunk in chunks] == [1, 2]


class TestGetChunkHeading:
    def test_skips_page_markers(self):
        part = "<!-- page: 1 -->\n\n# Section"
        assert heading_helpers._get_chunk_heading(part) == "Section"

    def test_first_atx_line(self):
        part = "## First Heading\n\ntext\n\n### Sub Heading Here"
        assert heading_helpers._get_chunk_heading(part) == "First Heading"

    def test_skips_neutral_lines(self):
        part = "<!-- page: 1 -->\n# Section Title\nbody"
        assert heading_helpers._get_chunk_heading(part) == "Section Title"

    def test_empty_when_first_content_is_not_atx(self):
        assert heading_helpers._get_chunk_heading("front matter only") == ""
        assert heading_helpers._get_chunk_heading("**Bold**\n\n# Later") == ""

    def test_empty_when_there_is_no_content(self):
        assert heading_helpers._get_chunk_heading("") == ""


class TestFirstChunkNumbering:
    """Chunk-0 is reserved for a leading preamble; otherwise numbering starts at 1."""

    PARAGRAPH = "Body sentence that carries the section text along. " * 40

    def _tree(self, markdown, tmp_path, max_tokens=chunker.DEFAULT_MAX_TOKENS):
        return chunker.build_chunk_tree(
            markdown, _md_path(tmp_path), max_tokens, 1
        )

    def test_no_preamble_starts_at_one(self, tmp_path):
        md = f"# 1.0 Introduction{chr(10)}{chr(10)}{self.PARAGRAPH}"
        catalog = self._tree(md, tmp_path)["catalog"]
        assert catalog[0]["chunk_id"] == "Chunk-1.md"

    def test_preamble_gets_chunk_zero(self, tmp_path):
        preamble = "Loose front matter carrying no heading whatsoever. " * 40
        md = (f"{preamble}{chr(10)}{chr(10)}"
              f"# 1.0 Introduction{chr(10)}{chr(10)}{self.PARAGRAPH}")
        chunks = self._tree(md, tmp_path, max_tokens=300)["chunks"]
        assert chunks[0]["file"] == "Chunk-0.md"
        assert any(
            c["file"] != "Chunk-0.md" and c["text"].lstrip().startswith("# 1.0 Introduction")
            for c in chunks
        ), [c["file"] for c in chunks]

    def test_packed_preamble_starts_at_zero(self, tmp_path):
        md = (f"One short front-matter line.{chr(10)}{chr(10)}"
              f"# 1.0 Introduction{chr(10)}{chr(10)}{self.PARAGRAPH}{chr(10)}{chr(10)}"
              f"# 2.0 Methods{chr(10)}{chr(10)}{self.PARAGRAPH}")
        assert self._tree(md, tmp_path)["catalog"][0]["chunk_id"] == "Chunk-0.md"

    def test_preamble_alone_is_chunk_zero(self, tmp_path):
        md = f"One short front-matter line.{chr(10)}"
        assert self._tree(md, tmp_path)["catalog"][0]["chunk_id"] == "Chunk-0.md"

    def test_later_chunks_keep_atx_headings_in_text(self, tmp_path):
        md = "".join(
            f"# {i}.0 Section Heading{chr(10)}{chr(10)}{self.PARAGRAPH}{chr(10)}{chr(10)}"
            for i in range(1, 6)
        )
        texts = [c["text"] for c in self._tree(md, tmp_path)["chunks"]]
        assert all(t.lstrip().startswith("#") for t in texts), texts


class TestNormalizeTitle:
    """The comparison key for bookmark-to-heading matching."""

    def test_strips_markup_keeps_digits(self):
        assert heading_helpers.normalize_title("## 1.0 Background:") == "10background"

    def test_casefold(self):
        assert heading_helpers.normalize_title("**Introduction**") == "introduction"

    def test_empty(self):
        assert heading_helpers.normalize_title("  ---  ") == ""

    def test_keeps_non_latin_scripts(self):
        # An ASCII-only class erased these entirely, so they could not match a bookmark.
        assert heading_helpers.normalize_title("## Введение") == "введение"

    def test_leading_section_numbers_are_kept(self):
        assert heading_helpers.normalize_title("3.1 Aims") != \
            heading_helpers.normalize_title("Aims")
        assert heading_helpers.normalize_title("3.1 Aims") == "31aims"

    def test_numbered_siblings_keep_separate_keys(self):
        keys = [heading_helpers.normalize_title(title)
                for title in ("Objective 1", "Objective 2", "Objective 3")]
        assert len(set(keys)) == 3, keys

    def test_section_numbered_siblings_keep_separate_keys(self):
        assert heading_helpers.normalize_title("8.1.1.1 DaT-SPECT") == "8111datspect"
        assert heading_helpers.normalize_title("9.3.1.1 DaT-SPECT") == "9311datspect"
        assert heading_helpers.normalize_title("8.1.1.1 DaT-SPECT") != \
            heading_helpers.normalize_title("9.3.1.1 DaT-SPECT")

    def test_a_trailing_number_is_part_of_the_name(self):
        assert heading_helpers.normalize_title("Phase 2") == "phase2"
        assert heading_helpers.normalize_title("2.0 Site 1") == "20site1"


class TestApplyBookmarkHeadingLevels:
    """Bookmark titles are matched to lines; the closest page wins."""

    PDF_BOOKMARKS = [
        {"title": "Background", "level": 1, "page": 5},
        {"title": "Design", "level": 2, "page": 6},
        {"title": "Methods", "level": 1, "page": 8},
    ]

    def test_no_pdf_bookmarks_leaves_lines_unchanged(self):
        lines = ["# Alpha", "body"]
        assert heading_helpers._apply_bookmark_heading_levels(lines, []) == [
            "# Alpha", "body"
        ]

    def test_closest_page_is_rewritten_other_hits_keep_hashes(self):
        lines = [
            "<!-- page: 1 -->",
            "# Background",
            "# Methods",
            "<!-- page: 5 -->",
            "### Background",
            "body",
            "<!-- page: 6 -->",
            "# Design",
            "body",
            "<!-- page: 8 -->",
            "### Methods",
            "body",
        ]
        pdf_bookmarks = [{**bookmark} for bookmark in self.PDF_BOOKMARKS]
        out = heading_helpers._apply_bookmark_heading_levels(lines, pdf_bookmarks)
        # Earlier same-title hits keep their hashes; only the chosen line gets the bookmark level.
        assert out[1] == "# Background"
        assert out[2] == "# Methods"
        assert out[4] == "# Background"
        assert out[7] == "## Design"
        assert out[10] == "# Methods"
        assert pdf_bookmarks[0]["checked"] is True
        assert pdf_bookmarks[0]["page"] == 5
        assert pdf_bookmarks[0]["line"] == 5
        assert pdf_bookmarks[1]["page"] == 6
        assert pdf_bookmarks[1]["line"] == 8
        assert pdf_bookmarks[2]["page"] == 8
        assert pdf_bookmarks[2]["line"] == 11

    def test_same_page_distance_picks_the_later_line(self):
        lines = [
            "<!-- page: 5 -->",
            "# Background",
            "toc body",
            "## Background",
            "section body",
        ]
        pdf_bookmarks = [{"title": "Background", "level": 1, "page": 5}]
        out = heading_helpers._apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "# Background"
        assert out[3] == "# Background"
        assert pdf_bookmarks[0]["line"] == 4

    def test_numbered_titles_match_only_the_same_section_number(self):
        lines = [
            "<!-- page: 49 -->",
            "##### 6.5.1.1.1 DaT-SPECT",
            "body",
            "<!-- page: 58 -->",
            "##### 8.1.1.1 DaT-SPECT",
            "body",
            "<!-- page: 81 -->",
            "##### 9.3.1.1 DaT-SPECT",
            "body",
        ]
        pdf_bookmarks = [
            {"title": "8.1.1.1 DaT-SPECT", "level": 4, "page": 58},
            {"title": "9.3.1.1 DaT-SPECT", "level": 4, "page": 81},
        ]
        out = heading_helpers._apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "##### 6.5.1.1.1 DaT-SPECT"
        assert out[4] == "#### 8.1.1.1 DaT-SPECT"
        assert out[7] == "#### 9.3.1.1 DaT-SPECT"
        assert [bookmark["line"] for bookmark in pdf_bookmarks] == [5, 8]

    def test_unmatched_atx_is_left_as_heading(self):
        lines = ["# Methods", "body", "# Random Caption Here"]
        pdf_bookmarks = [{"title": "Methods", "level": 2, "page": None}]
        out = heading_helpers._apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[0] == "## Methods"
        assert out[2] == "# Random Caption Here"

    def test_bold_title_becomes_an_atx_heading(self):
        lines = ["<!-- page: 5 -->", "**BACKGROUND**", "prose"]
        pdf_bookmarks = [{**bookmark} for bookmark in self.PDF_BOOKMARKS]
        out = heading_helpers._apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "# **BACKGROUND**"
        assert pdf_bookmarks[0]["checked"] is True
        assert pdf_bookmarks[0]["page"] == 5

    def test_all_caps_title_becomes_an_atx_heading(self):
        lines = ["<!-- page: 5 -->", "BACKGROUND", "prose"]
        pdf_bookmarks = [{**bookmark} for bookmark in self.PDF_BOOKMARKS]
        out = heading_helpers._apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "# BACKGROUND"
        assert pdf_bookmarks[0]["line"] == 2

    def test_keeps_original_heading_text_when_fixing_level(self):
        lines = ["<!-- page: 5 -->", "### 1.0 Background:", "prose"]
        pdf_bookmarks = [{"title": "1.0 Background", "level": 1, "page": 5}]
        out = heading_helpers._apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "# 1.0 Background:"

    def test_rewritten_lines_split_at_bookmark_level_one(self):
        body = "Section prose that carries the text along. " * 20
        lines = [
            "<!-- page: 1 -->",
            "# Background",
            "<!-- page: 5 -->",
            "### Background",
            body,
            "<!-- page: 8 -->",
            "### Methods",
            body,
        ]
        pdf_bookmarks = [{**bookmark} for bookmark in self.PDF_BOOKMARKS]
        out = heading_helpers._apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert min(
            bookmark["level"] for bookmark in pdf_bookmarks
            if isinstance(bookmark.get("level"), int) and bookmark["level"] > 0
        ) == 1
        chunks = chunker._split_into_chunks(out, chunker.DEFAULT_MAX_TOKENS)
        # Both Background hits keep ATX hashes, so each starts its own chunk (no preamble).
        assert [chunk["number"] for chunk in chunks] == [1, 2, 3]
        assert chunks[0]["text"].startswith("<!-- page: 1 -->")
        assert "\n# Background" in chunks[0]["text"]
        # The page marker that ended the part before is carried onto the head of this one, so
        # the heading follows it rather than opening the chunk (see _move_trailing_page_markers).
        assert chunks[1]["text"].startswith("<!-- page: 5 -->")
        assert "\n# Background" in chunks[1]["text"]
        assert "\n# Methods" in chunks[2]["text"]


class TestCatalogFromBookmarks:
    """End-to-end: bookmark promotion puts ATX headings into chunk text."""

    PARAGRAPH = "Body sentence that carries the section text along. " * 40

    def _tree(
        self, tmp_path, monkeypatch, md, pdf_bookmarks, max_tokens=chunker.DEFAULT_MAX_TOKENS
    ):
        (tmp_path / "doc.pdf").write_bytes(b"%PDF-1.4")
        monkeypatch.setattr("chunker.extract_bookmarks", lambda *a, **k: pdf_bookmarks)
        return chunker.build_chunk_tree(md, _md_path(tmp_path), max_tokens, 1)

    def test_bookmark_titles_promote_each_section(self, tmp_path, monkeypatch):
        md = (
            f"<!-- page: 5 -->\n# Background\n\n{self.PARAGRAPH}\n\n"
            f"<!-- page: 8 -->\n# Methods\n\n{self.PARAGRAPH}\n"
        )
        pdf_bookmarks = [
            {"title": "Background", "level": 1, "page": 5},
            {"title": "Methods", "level": 1, "page": 8},
        ]
        chunks = self._tree(
            tmp_path, monkeypatch, md, pdf_bookmarks, max_tokens=300
        )["chunks"]
        assert len(chunks) >= 2
        assert any("# Background" in c["text"] for c in chunks)
        assert any("# Methods" in c["text"] for c in chunks)

    def test_a_bold_section_is_promoted_to_atx(self, tmp_path, monkeypatch):
        md = f"<!-- page: 5 -->\n**Background**\n\n{self.PARAGRAPH}\n"
        pdf_bookmarks = [{"title": "Background", "level": 1, "page": 5}]
        chunks = self._tree(tmp_path, monkeypatch, md, pdf_bookmarks)["chunks"]
        assert any(c["text"].lstrip().startswith("# **Background**")
                   or "\n# **Background**" in c["text"] for c in chunks)


class TestBookmarksStorage:
    def _outline(self, tmp_path):
        return json.loads((tmp_path / "Chunks" / "outline.json").read_text(encoding="utf-8"))

    def test_no_sibling_pdf_no_bookmarks(self, tmp_path):
        md = tmp_path / "doc.md"
        md.write_text("# Title\n\nshort body\n", encoding="utf-8")
        chunker.chunk_file(str(md), min_structure_tokens=10 ** 9)
        outline = self._outline(tmp_path)
        assert outline["bookmarks"] == []
        assert "bookmark_source" not in outline


class TestAtomicPublication:
    """The .md and Chunks/ are swapped into place, never built in place.

    Regression: the Markdown was overwritten, the old Chunks/ deleted, and the new files then
    created one by one. Any failure in the middle left new Markdown beside missing or
    half-written chunks, indistinguishable from a finished parse.
    """

    def _document(self, tmp_path):
        body = "Prose paragraph about the study protocol. " * 1200
        md = tmp_path / "alpha.md"
        md.write_text(f"# 1.0 Alpha\n\n{body}\n\n# 2.0 Methods\n\n{body}\n", encoding="utf-8")
        return md

    def _leftovers(self, tmp_path):
        return [p.name for p in tmp_path.iterdir()
                if ".new-" in p.name or ".old-" in p.name or p.name.endswith(".tmp")]

    def test_a_clean_run_leaves_no_scratch_behind(self, tmp_path):
        chunker.chunk_file(str(self._document(tmp_path)))
        assert self._leftovers(tmp_path) == []

    def test_a_failure_mid_publish_keeps_the_previous_tree(self, tmp_path, monkeypatch):
        md = self._document(tmp_path)
        chunker.chunk_file(str(md))
        before = sorted(p.name for p in (tmp_path / "Chunks").iterdir())
        markdown_before = md.read_text(encoding="utf-8")

        original = chunker._write_json

        def fail_on_catalog(path, data):
            if path.name == chunker.CATALOG_NAME:
                raise OSError("disk full")
            original(path, data)

        monkeypatch.setattr(chunker, "_write_json", fail_on_catalog)
        with pytest.raises(OSError):
            chunker.chunk_file(str(md))

        assert sorted(p.name for p in (tmp_path / "Chunks").iterdir()) == before
        assert md.read_text(encoding="utf-8") == markdown_before
        assert self._leftovers(tmp_path) == []
