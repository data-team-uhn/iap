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


class TestPageRange:
    def test_span_from_first_to_last_marker(self):
        text = "<!-- page: 11 -->\nbody\n<!-- page: 12 -->"
        assert chunker._get_page_range(text) == "11-12"

    def test_single_page(self):
        assert chunker._get_page_range("<!-- page: 11 -->\nbody") == "11"

    def test_mid_page_start_backs_up_one(self):
        text = "leftover from the previous page\n<!-- page: 12 -->\nmore"
        assert chunker._get_page_range(text) == "11-12"

    def test_leading_blank_then_marker_is_not_mid_page(self):
        assert chunker._get_page_range("\n<!-- page: 11 -->\nbody") == "11"

    def test_no_markers(self):
        assert chunker._get_page_range("") == ""
        assert chunker._get_page_range("plain text") == ""

    def test_unordered_markers(self):
        text = "a\n<!-- page: 3 -->\nb\n<!-- page: 1 -->\n<!-- page: 3 -->"
        assert chunker._get_page_range(text) == "1-3"


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
        result = chunker._move_trailing_page_markers(["A\n\n<!-- page: 2 -->", "B body"])
        assert result == ["A", "<!-- page: 2 -->\n\nB body"]

    def test_single_part_unchanged(self):
        assert chunker._move_trailing_page_markers(["only"]) == ["only"]

    def test_last_part_marker_left_in_place(self):
        # Nowhere to move a marker at the very end.
        parts = ["A", "B\n\n<!-- page: 9 -->"]
        assert chunker._move_trailing_page_markers(parts) == parts


class TestSplitToBudget:
    """chunkweaver does the splitting; these pin the behaviour this pipeline needs."""

    def test_all_fits_single_part(self):
        text = "Para one.\n\nPara two.\n\nPara three."
        assert chunker._split_to_budget(text, 10_000) == [text]

    def test_splits_on_paragraph_boundaries(self):
        para = "Sentence one here. " * 6
        parts = chunker._split_to_budget("\n\n".join([para] * 3), 30)
        assert len(parts) == 3
        assert all(chunker.count_tokens(part) <= 30 for part in parts), parts

    def test_an_oversized_paragraph_is_split_rather_than_kept_whole(self):
        # The reason chunkweaver is here. Docling emits a table as one run of "|" lines
        # with no blank line, so the blank-line-only splitter this replaced returned it as
        # a single part far over budget, and nothing downstream re-split it.
        row = "| " + " | ".join(["Procedure with a realistic label"] + ["X"] * 9) + " |"
        parts = chunker._split_to_budget("\n".join([row] * 600),
                                         chunker.DEFAULT_MAX_TOKENS)
        assert len(parts) > 1
        assert all(chunker.count_tokens(part) <= chunker.DEFAULT_MAX_TOKENS for part in parts), \
            [chunker.count_tokens(part) for part in parts]

    def test_nothing_is_lost_or_duplicated(self):
        # A chunk tree is the document of record, so the parts must re-form the input.
        row = "| " + " | ".join(["Procedure"] + ["X"] * 8) + " |"
        text = "\n".join(["Lead-in prose.", ""] + [row] * 300 + ["", "Tail prose."])
        parts = chunker._split_to_budget(text, chunker.DEFAULT_MAX_TOKENS)
        assert " ".join(" ".join(parts).split()) == " ".join(text.split())

    def test_no_part_ends_on_a_page_marker(self):
        para = "Sentence one here. " * 6
        text = "\n\n".join([para, "<!-- page: 7 -->", para])
        parts = chunker._split_to_budget(text, 30)
        assert not any(part.rstrip().endswith("-->") for part in parts), parts


class TestPackBlocks:
    def test_all_fits_merged(self):
        blocks = ["Block A text", "Block B text", "Block C text"]
        assert chunker._pack_blocks(blocks, 10_000) == \
            ["Block A text\n\nBlock B text\n\nBlock C text"]

    def test_budget_forces_flush(self):
        block = "x" * 40  # 10 tokens; two fit in 25, three do not
        assert chunker._pack_blocks([block, block, block], 25) == \
            [block + "\n\n" + block, block]

    def test_standalone_heading_never_emitted_alone(self):
        heading = "## Section Heading"
        body = "z" * 200  # 50 tokens — over budget, but the heading must not flush alone
        assert chunker._pack_blocks([heading, body], 25) == [heading + "\n\n" + body]

    def test_middle_heading_flushed_with_following_when_lookahead_over_budget(self):
        a = "a" * 80   # 20 tokens
        heading = "## Middle Heading"
        c = "c" * 80   # 20 tokens
        # a + heading + c is over 25, so a flushes and the heading attaches to c.
        assert chunker._pack_blocks([a, heading, c], 25) == [a, heading + "\n\n" + c]


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
        assert tree["outline"]["bookmarks"] == [
            {"title": "Alpha", "level": 1, "page": 1}
        ]
        assert "bookmark_source" not in tree["outline"]

    def test_both_are_still_unchunked(self, tmp_path, monkeypatch):
        # The gate above is about the outline; the chunking decision is separate and unaffected.
        assert self._tree(tmp_path)["chunked"] is False
        assert self._tree(
            tmp_path, monkeypatch, [{"title": "Alpha", "level": 1, "page": 1}]
        )["chunked"] is False

    def test_a_large_document_runs_the_outline_pass_without_pdf_bookmarks(self, tmp_path):
        big = self.SMALL + ("Body sentence that carries it along. " * 200)
        tree = chunker.build_chunk_tree(
            big, _md_path(tmp_path), chunker.DEFAULT_MAX_TOKENS, 1
        )
        # No sibling PDF: heading candidates from the Markdown fill the bookmarks list.
        # A printed TOC line is not one of them.
        titles = [entry["title"] for entry in tree["outline"]["bookmarks"]]
        assert "Table of Contents" not in titles
        assert "Alpha" in titles
        assert "bookmark_source" not in tree["outline"]


class TestIsHeadingOnly:
    def test_single_heading(self):
        assert heading_helpers._is_heading_only("# 6.0 Schedule of Assessments") is True

    def test_two_headings_with_no_prose(self):
        # Broader than _is_standalone_heading, which requires exactly one content line: two
        # headings back to back are just as unusable as a chunk file.
        assert heading_helpers._is_heading_only("# 7.0 Analysis\n\n## 7.1 Primary") is True

    def test_heading_with_body_is_not(self):
        assert heading_helpers._is_heading_only("# 6.0 Schedule\n\nSome prose.") is False

    def test_blank_and_neutral_lines_ignored(self):
        part = "\n<!-- page: 4 -->\n# 6.0 Schedule\n\n---\n"
        assert heading_helpers._is_heading_only(part) is True

    def test_body_only_is_not(self):
        assert heading_helpers._is_heading_only("Just prose, no heading.") is False

    def test_empty_is_not(self):
        assert heading_helpers._is_heading_only("") is False


class TestMergeHeadingOnlyParts:
    """No chunk file may be a bare title.

    Regression, reproduced at default settings: a section whose body is one over-budget
    paragraph made _split_to_budget flush the heading alone, giving a 29-byte
    'Chunk-1.1.md' holding '# 6.0 Schedule of Assessments' and nothing else, with the table
    next to it labelled only by inheritance. Docling emits a table as consecutive '|' lines
    with no blank line, so the whole table is one paragraph and this is not an edge case.
    """

    def test_heading_takes_the_following_part(self):
        assert chunker._merge_heading_only_parts(["# 6.0 Schedule", "| a | b |"]) == \
            ["# 6.0 Schedule\n\n| a | b |"]

    def test_trailing_heading_folds_backwards(self):
        # _pack_blocks guards its lookahead with `index + 1 < n`, so a document ending on a bare
        # heading leaves it last — where there is nothing after it to take.
        assert chunker._merge_heading_only_parts(["Body text.", "## 5.2 Deferred"]) == \
            ["Body text.\n\n## 5.2 Deferred"]

    def test_consecutive_headings_take_the_body(self):
        parts = ["# 7.0 Analysis", "## 7.1 Primary", "Prose body."]
        assert chunker._merge_heading_only_parts(parts) == \
            ["# 7.0 Analysis\n\n## 7.1 Primary\n\nProse body."]

    def test_middle_heading_merges_forwards_not_backwards(self):
        parts = ["First body.", "## 5.2 Second", "Second body."]
        assert chunker._merge_heading_only_parts(parts) == \
            ["First body.", "## 5.2 Second\n\nSecond body."]

    def test_a_lone_heading_part_is_left_alone(self):
        # Nothing to merge with; a document that is only a heading has no better answer.
        assert chunker._merge_heading_only_parts(["# 6.0 Schedule"]) == ["# 6.0 Schedule"]

    def test_parts_without_bare_headings_are_untouched(self):
        parts = ["# 1.0 Intro\n\nbody", "## 1.1 More\n\nbody"]
        assert chunker._merge_heading_only_parts(parts) == parts

    def test_empty(self):
        assert chunker._merge_heading_only_parts([]) == []


class TestNoHeadingOnlyChunkFiles:
    """The same two cases end to end, at default max_tokens."""

    def _files(self, md, tmp_path):
        tree = chunker.build_chunk_tree(
            md, _md_path(tmp_path), chunker.DEFAULT_MAX_TOKENS, 1
        )
        return [(chunk["file"], len(chunk["text"])) for chunk in tree["chunks"]]

    def test_heading_stays_with_an_over_budget_table(self, tmp_path):
        # One row of ten cells is ~20 tokens, so 130 rows clears the 2000-token budget.
        row = "| " + " | ".join(["Procedure with a realistic label"] + ["X"] * 9) + " |"
        table = "\n".join(["| A | B | C | D | E | F | G | H | I | J |"] + [row] * 130)
        files = self._files(f"# 6.0 Schedule of Assessments\n\n{table}", tmp_path)
        assert len(files) == 1, files
        assert files[0][1] > 1000, files

    def test_trailing_bare_heading_is_not_its_own_file(self, tmp_path):
        md = ("# 5.0 Methods\n\n## 5.1 First Subsection\n\n"
              + ("Body sentence that carries it along. " * 220)
              + "\n\n## 5.2 Deferred Subsection\n")
        files = self._files(md, tmp_path)
        assert all(size > 100 for _, size in files), files

    def test_an_empty_top_level_section_is_not_its_own_file(self, tmp_path):
        # Regression: a top-level section with a heading and no body reaches the packing loop
        # as a single part, so the per-packed-chunk merge had no neighbour to fold it into and
        # wrote it out as a chunk holding nothing but the title. Merging across packed chunks
        # before numbering catches it.
        body = "Prose paragraph about the study protocol. " * 200
        md = "\n".join([
            "# 1.0 Section One", body, "",
            "# 2.0 Section Two", body, "",
            "# 3.0 Appendix Section Title",
        ])
        files = self._files(md, tmp_path)
        assert all(size > 100 for _, size in files), files
        assert not any(size < 60 for _, size in files), files

    def test_the_empty_section_title_is_still_in_the_output(self, tmp_path):
        body = "Prose paragraph about the study protocol. " * 200
        md = "\n".join([
            "# 1.0 Section One", body, "",
            "# 2.0 Section Two", body, "",
            "# 3.0 Appendix Section Title",
        ])
        tree = chunker.build_chunk_tree(
            md, _md_path(tmp_path), chunker.DEFAULT_MAX_TOKENS, 1
        )
        # Folded into a sibling, never dropped
        assert any("Appendix Section Title" in chunk["text"] for chunk in tree["chunks"])


class TestMergeSmallTextTails:
    def test_small_text_tail_folded_into_previous(self):
        assert chunker._merge_small_text_tails(["First part body.", "tiny"], 500) == \
            ["First part body.\n\ntiny"]

    def test_tail_with_heading_never_merged(self):
        parts = ["First", "## Second Heading"]
        assert chunker._merge_small_text_tails(parts, 500) == parts

    def test_large_tail_not_merged(self):
        big = "w" * 4000  # 1000 tokens
        assert chunker._merge_small_text_tails(["First", big], 500) == ["First", big]


class TestSplitIntoTopChunks:
    def test_preamble_and_sections(self):
        lines = ["preamble text", "# Section One", "body one", "# Section Two", "body two"]
        chunks = chunker._split_into_top_chunks(lines)
        assert [c["number"] for c in chunks] == [0, 1, 2]
        assert chunks[0]["text"] == "preamble text"
        # Each section keeps its own heading line at the head of its text; catalog labels
        # are derived per emitted part later, by _get_part_heading.
        assert chunks[1]["text"] == "# Section One\nbody one"
        assert chunks[2]["text"] == "# Section Two\nbody two"

    def test_no_headings_single_chunk(self):
        assert chunker._split_into_top_chunks(["just", "text"]) == \
            [{"number": 0, "text": "just\ntext"}]

    def test_empty(self):
        assert chunker._split_into_top_chunks([]) == []

    def test_a_heading_with_no_bookmark_still_starts_a_chunk(self):
        # The cut follows the heading lines, not the bookmark list. Cutting only where a
        # bookmark had matched glued a real section onto its neighbour whenever the PDF
        # outline was incomplete.
        lines = ["# One Section", "body one", "# Two Section", "body two"]
        chunks = chunker._split_into_top_chunks(lines)
        assert [chunk["number"] for chunk in chunks] == [1, 2]


class TestPartHeading:
    def test_collects_atx_within_two_levels(self):
        part = "## First Heading\n\ntext\n\n### Sub Heading Here"
        assert heading_helpers._get_part_heading(part, None) == [
            "First Heading", "Sub Heading Here"]

    def test_excludes_headings_deeper_than_beginning_plus_one(self):
        part = "## Alpha Heading\n\n### Beta Heading\n\n#### Gamma Heading"
        assert heading_helpers._get_part_heading(part, None) == ["Alpha Heading", "Beta Heading"]

    def test_no_heading_copies_previous(self):
        assert heading_helpers._get_part_heading(
            "no heading text", ["Prev Heading"]) == ["Prev Heading"]

    def test_no_heading_no_previous_uses_default(self):
        assert heading_helpers._get_part_heading(
            "no heading text", None) == [heading_helpers.DEFAULT_HEADING]


class TestFirstChunkHeading:
    """The first catalog entry must use its real heading when it has one.

    Regression: the first entry was forced to DEFAULT_HEADING regardless of content, so every
    document without a preamble lost a perfectly good label like "1.0 Introduction" — and it
    contradicted _get_part_heading's own documented contract, which already falls back to
    DEFAULT_HEADING exactly when a part has no heading and there is no previous entry to copy.
    """

    PARAGRAPH = "Body sentence that carries the section text along. " * 40

    def _tree(self, markdown, tmp_path, max_tokens=chunker.DEFAULT_MAX_TOKENS):
        return chunker.build_chunk_tree(
            markdown, _md_path(tmp_path), max_tokens, 1
        )

    def test_no_preamble_uses_the_real_heading(self, tmp_path):
        md = f"# 1.0 Introduction{chr(10)}{chr(10)}{self.PARAGRAPH}"
        first = self._tree(md, tmp_path)["catalog"][0]
        assert first["headings"] == ["1.0 Introduction"]

    def test_preamble_with_no_headings_uses_the_default(self, tmp_path):
        # The preamble has to be over budget to stand alone: a short one is packed together with
        # the following section, and that combined chunk really does contain "1.0 Introduction",
        # so labelling it with the heading is right.
        preamble = "Loose front matter carrying no heading whatsoever. " * 40
        md = (f"{preamble}{chr(10)}{chr(10)}"
              f"# 1.0 Introduction{chr(10)}{chr(10)}{self.PARAGRAPH}")
        chunks = self._tree(md, tmp_path, max_tokens=300)["catalog"]
        assert chunks[0]["headings"] == [heading_helpers.DEFAULT_HEADING]
        # And the section that follows keeps its own heading.
        assert any(c["headings"] == ["1.0 Introduction"] for c in chunks[1:]), \
            [c["headings"] for c in chunks]

    def test_packed_preamble_keeps_the_default_and_the_packed_headings(self, tmp_path):
        # A short preamble is packed together with the following sections. Chunk-0 is still
        # front matter and keeps the default label, but the sections packed in with it keep
        # their headings too — dropping them left the whole chunk untitled in the catalog.
        md = (f"One short front-matter line.{chr(10)}{chr(10)}"
              f"# 1.0 Introduction{chr(10)}{chr(10)}{self.PARAGRAPH}{chr(10)}{chr(10)}"
              f"# 2.0 Methods{chr(10)}{chr(10)}{self.PARAGRAPH}")
        first = self._tree(md, tmp_path)["catalog"][0]
        assert first["headings"][0] == heading_helpers.DEFAULT_HEADING
        assert "1.0 Introduction" in first["headings"]
        assert "2.0 Methods" in first["headings"]

    def test_preamble_alone_is_only_the_default(self, tmp_path):
        # Nothing packed in with it, so there is no real heading to add.
        md = f"One short front-matter line.{chr(10)}"
        first = self._tree(md, tmp_path)["catalog"][0]
        assert first["headings"] == [heading_helpers.DEFAULT_HEADING]

    def test_preamble_stand_out_lines_do_not_become_the_label(self, tmp_path):
        # A title block's bold/ALL-CAPS lines are field labels, not section titles.
        preamble = (f"**PRINCIPAL INVESTIGATOR:**{chr(10)}{chr(10)}Dr Somebody{chr(10)}{chr(10)}"
                    + "Front matter prose that runs on for a while. " * 40)
        md = (f"{preamble}{chr(10)}{chr(10)}# 1.0 Introduction{chr(10)}{chr(10)}{self.PARAGRAPH}")
        chunks = self._tree(md, tmp_path, max_tokens=300)["catalog"]
        assert chunks[0]["headings"] == [heading_helpers.DEFAULT_HEADING]

    def test_later_chunks_unaffected(self, tmp_path):
        md = "".join(
            f"# {i}.0 Section Heading{chr(10)}{chr(10)}{self.PARAGRAPH}{chr(10)}{chr(10)}"
            for i in range(1, 6)
        )
        headings = [c["headings"] for c in self._tree(md, tmp_path)["catalog"]]
        assert all(h != [heading_helpers.DEFAULT_HEADING] for h in headings), headings


class TestNormalizeTitle:
    """The comparison key for bookmark-to-heading matching."""

    def test_strips_markup(self):
        assert heading_helpers.normalize_title("## 1.0 Background:") == "background"

    def test_casefold(self):
        assert heading_helpers.normalize_title("**Introduction**") == "introduction"

    def test_empty(self):
        assert heading_helpers.normalize_title("  ---  ") == ""

    def test_keeps_non_latin_scripts(self):
        # An ASCII-only class erased these entirely, so they could not match a bookmark.
        assert heading_helpers.normalize_title("## Введение") == "введение"

    def test_the_leading_number_is_dropped_so_a_bookmark_matches(self):
        # The point of dropping it: a PDF bookmark carries no numbering.
        assert heading_helpers.normalize_title("3.1 Aims") == \
            heading_helpers.normalize_title("Aims")

    def test_numbered_siblings_keep_separate_keys(self):
        # Regression: dropping every digit keyed these all to "objective", and the caller
        # treats same-key lines as repeats of one heading -- the first kept its markers and
        # the rest were demoted to body, losing their # and their chunk boundary.
        keys = [heading_helpers.normalize_title(title)
                for title in ("Objective 1", "Objective 2", "Objective 3")]
        assert len(set(keys)) == 3, keys

    def test_a_trailing_number_is_part_of_the_name(self):
        assert heading_helpers.normalize_title("Phase 2") == "phase2"
        assert heading_helpers.normalize_title("2.0 Site 1") == "site1"


class TestLocalBookmarkTitles:
    """Assign bookmark titles to a chunk when the title appears as a line."""

    PDF_BOOKMARKS = [
        {"title": "Background", "level": 1, "page": 5},
        {"title": "Methods", "level": 1, "page": 8},
    ]

    def test_a_page_without_the_title_is_not_a_match(self):
        part = "<!-- page: 5 -->\nbody"
        assert heading_helpers._get_local_bookmark_titles(self.PDF_BOOKMARKS, part) == []

    def test_a_title_line_matches_regardless_of_page(self):
        part = "<!-- page: 1 -->\n# Background\n# Methods"
        assert heading_helpers._get_local_bookmark_titles(self.PDF_BOOKMARKS, part) == [
            "Background", "Methods"]

    def test_a_bold_title_matches(self):
        part = "<!-- page: 5 -->\n**BACKGROUND**\nprose"
        assert heading_helpers._get_local_bookmark_titles(
            self.PDF_BOOKMARKS, part) == ["Background"]

    def test_unpaged_matches_a_title_line(self):
        part = "**Background**\nprose"
        assert heading_helpers._get_local_bookmark_titles(
            self.PDF_BOOKMARKS, part) == ["Background"]

    def test_unpaged_ignores_unrelated_text(self):
        part = "Just prose about something else."
        assert heading_helpers._get_local_bookmark_titles(self.PDF_BOOKMARKS, part) == []

    def test_empty_pdf_bookmarks(self):
        assert heading_helpers._get_local_bookmark_titles(
            [], "<!-- page: 5 -->\n# Background") == []


class TestCollectHeadingCandidates:
    def test_atx_record_has_level_title_page_and_key(self):
        lines = ["<!-- page: 5 -->", "### 1.0 Background", "body"]
        found = heading_helpers._collect_heading_candidates(lines, list(lines))
        assert found == [{
            "title": "1.0 Background",
            "level": 3,
            "page": 5,
            "key": "background",
            "line": 2,
        }]

    def test_bold_record_has_no_level(self):
        lines = ["<!-- page: 2 -->", "**METHODS**", "prose"]
        found = heading_helpers._collect_heading_candidates(lines, list(lines))
        assert found == [{
            "title": "METHODS",
            "page": 2,
            "key": "methods",
            "line": 2,
        }]
        assert "level" not in found[0]


class TestApplyBookmarkHeadingLevels:
    """Heading candidates are matched to bookmarks by title; the closest page wins."""

    PDF_BOOKMARKS = [
        {"title": "Background", "level": 1, "page": 5},
        {"title": "Design", "level": 2, "page": 6},
        {"title": "Methods", "level": 1, "page": 8},
    ]

    def test_no_pdf_bookmarks_returns_candidates_as_bookmarks(self):
        lines = ["# Alpha", "body"]
        out, bookmarks = heading_helpers._apply_bookmark_heading_levels(lines, [])
        assert out == ["# Alpha", "body"]
        assert bookmarks == [{
            "title": "Alpha",
            "level": 1,
            "page": None,
            "key": "alpha",
            "line": 1,
        }]

    def test_closest_page_is_kept_and_the_rest_demoted(self):
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
        out, bookmarks = heading_helpers._apply_bookmark_heading_levels(
            lines, self.PDF_BOOKMARKS
        )
        assert out[1] == "Background"
        assert out[2] == "Methods"
        assert out[4] == "# Background"
        assert out[7] == "## Design"
        assert out[10] == "# Methods"
        assert bookmarks[0]["checked"] is True
        assert bookmarks[0]["page"] == 5
        assert bookmarks[0]["line"] == 5
        assert bookmarks[1]["page"] == 6
        assert bookmarks[1]["line"] == 8
        assert bookmarks[2]["page"] == 8
        assert bookmarks[2]["line"] == 11

    def test_noisy_atx_is_demoted_during_collection(self):
        lines = [
            "# Table 1: Overview Here",
            "# Confidential",
            "# 12",
            "# Methods",
            "body",
        ]
        pdf_bookmarks = [{"title": "Methods", "level": 2, "page": None}]
        out, bookmarks = heading_helpers._apply_bookmark_heading_levels(
            lines, pdf_bookmarks
        )
        assert out[0] == "Table 1: Overview Here"
        assert out[1] == "Confidential"
        assert out[2] == "12"
        assert out[3] == "## Methods"
        assert bookmarks[0]["checked"] is True
        assert bookmarks[0]["line"] == 4

    def test_unmatched_atx_is_left_as_heading(self):
        lines = ["# Methods", "body", "# Random Caption Here"]
        pdf_bookmarks = [{"title": "Methods", "level": 2, "page": None}]
        out, _bookmarks = heading_helpers._apply_bookmark_heading_levels(
            lines, pdf_bookmarks
        )
        assert out[0] == "## Methods"
        assert out[2] == "# Random Caption Here"

    def test_bold_title_becomes_an_atx_heading(self):
        lines = ["<!-- page: 5 -->", "**BACKGROUND**", "prose"]
        out, bookmarks = heading_helpers._apply_bookmark_heading_levels(
            lines, self.PDF_BOOKMARKS
        )
        assert out[1] == "# Background"
        assert bookmarks[0]["checked"] is True
        assert bookmarks[0]["page"] == 5

    def test_all_caps_title_becomes_an_atx_heading(self):
        lines = ["<!-- page: 5 -->", "BACKGROUND", "prose"]
        out, bookmarks = heading_helpers._apply_bookmark_heading_levels(
            lines, self.PDF_BOOKMARKS
        )
        assert out[1] == "# Background"
        assert bookmarks[0]["line"] == 2

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
        out, bookmarks = heading_helpers._apply_bookmark_heading_levels(
            lines, self.PDF_BOOKMARKS
        )
        assert min(
            bookmark["level"] for bookmark in bookmarks
            if isinstance(bookmark.get("level"), int) and bookmark["level"] > 0
        ) == 1
        chunks = chunker._split_into_top_chunks(out)
        assert [chunk["number"] for chunk in chunks] == [0, 1, 2]
        assert "Background" in chunks[0]["text"]
        assert chunks[1]["text"].startswith("# Background")
        assert chunks[2]["text"].startswith("# Methods")


class TestVerifyCatalogHeadings:
    PDF_BOOKMARKS = [{"title": "Background", "level": 1, "page": 5}]

    def test_without_pdf_bookmarks_keeps_atx(self):
        assert heading_helpers._verify_catalog_headings(
            ["# noise"], "<!-- page: 1 -->\n# noise", [], None, is_preamble=False
        ) == ["# noise"]

    def test_a_title_line_uses_the_bookmark_title(self):
        assert heading_helpers._verify_catalog_headings(
            ["Background"],
            "<!-- page: 1 -->\n# Background",
            self.PDF_BOOKMARKS,
            None,
            is_preamble=False,
        ) == ["Background"]

    def test_real_page_uses_the_bookmark_title(self):
        assert heading_helpers._verify_catalog_headings(
            ["1.0 BACKGROUND"],
            "<!-- page: 5 -->\n# Background",
            self.PDF_BOOKMARKS,
            None,
            is_preamble=False,
        ) == ["Background"]

    def test_preamble_with_no_atx_uses_only_the_bookmark_titles(self):
        assert heading_helpers._verify_catalog_headings(
            [heading_helpers.DEFAULT_HEADING],
            "<!-- page: 5 -->\n**Background**\nprose",
            self.PDF_BOOKMARKS,
            None,
            is_preamble=True,
        ) == ["Background"]

    def test_preamble_keeps_the_default_and_appends_local_titles(self):
        assert heading_helpers._verify_catalog_headings(
            [heading_helpers.DEFAULT_HEADING, "Background"],
            "<!-- page: 5 -->\nfront\n# Background",
            self.PDF_BOOKMARKS,
            None,
            is_preamble=True,
        ) == [heading_helpers.DEFAULT_HEADING, "Background"]

    def test_empty_local_inherits_previous(self):
        assert heading_helpers._verify_catalog_headings(
            ["Methods"],
            "<!-- page: 20 -->\ncontinuation",
            self.PDF_BOOKMARKS,
            ["Background"],
            is_preamble=False,
        ) == ["Background"]


class TestCatalogHeadingsFromBookmarks:
    """End-to-end: catalog.json headings come from bookmarks when a sibling PDF has them."""

    PARAGRAPH = "Body sentence that carries the section text along. " * 40

    def _tree(self, tmp_path, monkeypatch, md, pdf_bookmarks):
        (tmp_path / "doc.pdf").write_bytes(b"%PDF-1.4")
        monkeypatch.setattr("chunker.extract_bookmarks", lambda *a, **k: pdf_bookmarks)
        return chunker.build_chunk_tree(md, _md_path(tmp_path), chunker.DEFAULT_MAX_TOKENS, 1)

    def test_bookmark_titles_label_every_chunk_that_carries_them(self, tmp_path, monkeypatch):
        md = (
            f"<!-- page: 1 -->\n# Background\n# Methods\n\n"
            f"<!-- page: 5 -->\n# Background\n\n{self.PARAGRAPH}\n\n"
            f"<!-- page: 8 -->\n# Methods\n\n{self.PARAGRAPH}\n"
        )
        pdf_bookmarks = [
            {"title": "Background", "level": 1, "page": 5},
            {"title": "Methods", "level": 1, "page": 8},
        ]
        catalog = self._tree(tmp_path, monkeypatch, md, pdf_bookmarks)["catalog"]
        flattened = [heading for entry in catalog for heading in entry["headings"]]
        assert "Background" in flattened
        assert "Methods" in flattened

    def test_a_bold_section_still_gets_the_bookmark_label(self, tmp_path, monkeypatch):
        md = f"<!-- page: 5 -->\n**Background**\n\n{self.PARAGRAPH}\n"
        pdf_bookmarks = [{"title": "Background", "level": 1, "page": 5}]
        catalog = self._tree(tmp_path, monkeypatch, md, pdf_bookmarks)["catalog"]
        assert "Background" in catalog[0]["headings"]


class TestSplitOversized:
    def test_no_subheadings_paragraph_split(self):
        chunk_text = "\n\n".join(["p" * 40] * 4)  # four 10-token paragraphs
        parts = chunker._split_oversized(chunk_text, 15)
        assert len(parts) == 4
        assert all(chunker.count_tokens(p) <= 15 for p in parts)

    def test_with_subheadings_packs_then_stays_within_budget(self):
        chunk_text = "# Top Heading\n\n" + "\n\n".join(
            f"## Sub {i} Heading\n\n" + "q" * 40 for i in range(1, 5)
        )
        parts = chunker._split_oversized(chunk_text, 25)
        assert len(parts) >= 2
        assert all(chunker.count_tokens(p) <= 25 for p in parts)


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
