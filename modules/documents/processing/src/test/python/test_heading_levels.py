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

"""Unit tests for the bookmark-driven heading-level pass. No Docling needed, so these run
in CI -- which is the point of keeping the pass out of ``parse_document``."""

import heading_levels
import markdown_markers


class TestValidHeading:
    def test_ordinary_heading(self):
        assert heading_levels.is_valid_heading("Introduction") is True

    def test_too_short_rejected(self):
        assert heading_levels.is_valid_heading("Hi") is False

    def test_too_many_words_rejected(self):
        line = "one two three four five six seven eight nine ten eleven"
        assert heading_levels.is_valid_heading(line) is False

    def test_overlong_word_rejected(self):
        assert heading_levels.is_valid_heading("word " + "x" * 101) is False

    def test_at_the_word_limit_is_allowed(self):
        line = " ".join(["word"] * markdown_markers.MAX_HEADING_WORDS)
        assert heading_levels.is_valid_heading(line) is True

    def test_blank_rejected(self):
        assert heading_levels.is_valid_heading("") is False
        assert heading_levels.is_valid_heading("   ") is False

    def test_a_numbered_short_heading_is_kept(self):
        # Substance is measured over letters and digits, not the matching key alone.
        for title in ("3.1 Aims", "5.2 Data", "2.0 Bias", "1.4 Team"):
            assert heading_levels.is_valid_heading(title) is True, title

    def test_digits_only_is_rejected(self):
        assert heading_levels.is_valid_heading("4.2") is False

    def test_an_unnumbered_short_title_is_still_rejected(self):
        assert heading_levels.is_valid_heading("Aims") is False

    def test_a_real_heading_is_kept(self):
        assert heading_levels.is_valid_heading("3.1 Study Aims") is True


class TestHeadingMatching:
    def test_match_atx_heading_level_and_text(self):
        assert heading_levels._match_atx_heading("## Foo Bar") == (2, "Foo Bar")

    def test_match_atx_heading_deepest_level(self):
        assert heading_levels._match_atx_heading("###### Deep Heading") == (6, "Deep Heading")

    def test_match_atx_heading_seven_hashes_is_not_a_heading(self):
        assert heading_levels._match_atx_heading("####### Seven") is None

    def test_match_atx_heading_plain_line(self):
        assert heading_levels._match_atx_heading("plain text line") is None


class TestNormalizeTitle:
    """The comparison key for bookmark-to-heading matching."""

    def test_strips_markup_keeps_digits(self):
        assert heading_levels.normalize_title("## 1.0 Background:") == "10background"

    def test_casefold(self):
        assert heading_levels.normalize_title("**Introduction**") == "introduction"

    def test_empty(self):
        assert heading_levels.normalize_title("  ---  ") == ""

    def test_keeps_non_latin_scripts(self):
        # An ASCII-only class erased these entirely, so they could not match a bookmark.
        assert heading_levels.normalize_title("## Введение") == "введение"

    def test_leading_section_numbers_are_kept(self):
        assert heading_levels.normalize_title("3.1 Aims") != \
            heading_levels.normalize_title("Aims")
        assert heading_levels.normalize_title("3.1 Aims") == "31aims"

    def test_numbered_siblings_keep_separate_keys(self):
        keys = [heading_levels.normalize_title(title)
                for title in ("Objective 1", "Objective 2", "Objective 3")]
        assert len(set(keys)) == 3, keys

    def test_section_numbered_siblings_keep_separate_keys(self):
        assert heading_levels.normalize_title("8.1.1.1 DaT-SPECT") == "8111datspect"
        assert heading_levels.normalize_title("9.3.1.1 DaT-SPECT") == "9311datspect"
        assert heading_levels.normalize_title("8.1.1.1 DaT-SPECT") != \
            heading_levels.normalize_title("9.3.1.1 DaT-SPECT")

    def test_a_trailing_number_is_part_of_the_name(self):
        assert heading_levels.normalize_title("Phase 2") == "phase2"
        assert heading_levels.normalize_title("2.0 Site 1") == "20site1"


class TestApplyBookmarkHeadingLevels:
    """Bookmark titles are matched to lines; the closest page wins."""

    PDF_BOOKMARKS = [
        {"title": "Background", "level": 1, "page": 5},
        {"title": "Design", "level": 2, "page": 6},
        {"title": "Methods", "level": 1, "page": 8},
    ]

    def test_no_pdf_bookmarks_leaves_lines_unchanged(self):
        lines = ["# Alpha", "body"]
        assert heading_levels.apply_bookmark_heading_levels(lines, []) == [
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
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
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

    def test_a_section_split_across_batches_ends_up_at_one_level(self):
        # Why this pass exists: each page-range batch runs its own heading-hierarchy pass, so
        # the same section comes back '#' in one batch and '###' in another.
        lines = [
            "<!-- page: 5 -->",
            "# Study Design",
            "body",
            "<!-- page: 40 -->",
            "### Statistical Methods",
            "body",
        ]
        pdf_bookmarks = [
            {"title": "Study Design", "level": 2, "page": 5},
            {"title": "Statistical Methods", "level": 2, "page": 40},
        ]
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "## Study Design"
        assert out[4] == "## Statistical Methods"

    def test_same_page_distance_picks_the_later_line(self):
        lines = [
            "<!-- page: 5 -->",
            "# Background",
            "toc body",
            "## Background",
            "section body",
        ]
        pdf_bookmarks = [{"title": "Background", "level": 1, "page": 5}]
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
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
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "##### 6.5.1.1.1 DaT-SPECT"
        assert out[4] == "#### 8.1.1.1 DaT-SPECT"
        assert out[7] == "#### 9.3.1.1 DaT-SPECT"
        assert [bookmark["line"] for bookmark in pdf_bookmarks] == [5, 8]

    def test_unmatched_atx_is_left_as_heading(self):
        lines = ["# Methods", "body", "# Random Caption Here"]
        pdf_bookmarks = [{"title": "Methods", "level": 2, "page": None}]
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[0] == "## Methods"
        assert out[2] == "# Random Caption Here"

    def test_bold_title_becomes_an_atx_heading(self):
        lines = ["<!-- page: 5 -->", "**BACKGROUND**", "prose"]
        pdf_bookmarks = [{**bookmark} for bookmark in self.PDF_BOOKMARKS]
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "# **BACKGROUND**"
        assert pdf_bookmarks[0]["checked"] is True
        assert pdf_bookmarks[0]["page"] == 5

    def test_all_caps_title_becomes_an_atx_heading(self):
        lines = ["<!-- page: 5 -->", "BACKGROUND", "prose"]
        pdf_bookmarks = [{**bookmark} for bookmark in self.PDF_BOOKMARKS]
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "# BACKGROUND"
        assert pdf_bookmarks[0]["line"] == 2

    def test_keeps_original_heading_text_when_fixing_level(self):
        lines = ["<!-- page: 5 -->", "### 1.0 Background:", "prose"]
        pdf_bookmarks = [{"title": "1.0 Background", "level": 1, "page": 5}]
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "# 1.0 Background:"

    def test_a_table_row_is_never_matched(self):
        lines = ["<!-- page: 5 -->", "| Background | 12 |", "prose"]
        pdf_bookmarks = [{"title": "Background", "level": 1, "page": 5}]
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[1] == "| Background | 12 |"
        assert "checked" not in pdf_bookmarks[0]

    def test_a_level_past_the_ceiling_is_clamped(self):
        lines = ["# Background Section"]
        pdf_bookmarks = [{"title": "Background Section", "level": 99, "page": None}]
        out = heading_levels.apply_bookmark_heading_levels(lines, pdf_bookmarks)
        assert out[0] == "#" * markdown_markers.MAX_HEADING_LEVEL + " Background Section"


class TestTheSiblingPdfIsFoundWhateverItsCase:
    """``PROTOCOL.PDF`` is routine from Windows, and the lookup rebuilt the name in lowercase.

    On a case-sensitive filesystem that missed: bookmarks came back empty and heading levels
    were never corrected, with ``ok: true``.
    """

    def test_an_uppercase_suffix_is_matched(self, tmp_path):
        (tmp_path / "PROTOCOL.PDF").write_bytes(b"%PDF")
        assert heading_levels.find_sibling_pdf(tmp_path / "PROTOCOL.md") \
            == tmp_path / "PROTOCOL.PDF"

    def test_the_exact_name_still_wins(self, tmp_path):
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        assert heading_levels.find_sibling_pdf(tmp_path / "protocol.md") \
            == tmp_path / "protocol.pdf"

    def test_another_document_is_not_mistaken_for_it(self, tmp_path):
        (tmp_path / "appendix.pdf").write_bytes(b"%PDF")
        assert heading_levels.find_sibling_pdf(tmp_path / "protocol.md") is None

    def test_a_missing_directory_is_reported(self, tmp_path):
        assert heading_levels.find_sibling_pdf(tmp_path / "gone" / "protocol.md") is None


class TestCorrectHeadingLevels:
    """The whole pass: find the PDF beside the ``.md``, read its outline, rewrite the levels."""

    MARKDOWN = "<!-- page: 5 -->\n### Background Section\n\nbody\n"

    def test_no_sibling_pdf_leaves_the_markdown_alone(self, tmp_path):
        logs = []
        out = heading_levels.correct_heading_levels(
            self.MARKDOWN, tmp_path / "protocol.md", log=logs.append
        )
        assert out == self.MARKDOWN
        assert any("No PDF beside" in line for line in logs)

    def test_a_sibling_with_no_bookmarks_is_reported(self, tmp_path, monkeypatch):
        # extract_bookmarks answers an unreadable PDF and an unbookmarked one the same way,
        # so from outside the two are indistinguishable.
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        monkeypatch.setattr(heading_levels, "extract_bookmarks", lambda source: [])
        logs = []
        out = heading_levels.correct_heading_levels(
            self.MARKDOWN, tmp_path / "protocol.md", log=logs.append
        )
        assert out == self.MARKDOWN
        assert any("no usable bookmarks" in line for line in logs)

    def test_bookmarks_set_the_level(self, tmp_path, monkeypatch):
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        monkeypatch.setattr(
            heading_levels, "extract_bookmarks",
            lambda source: [{"title": "Background Section", "level": 1, "page": 5}],
        )
        logs = []
        out = heading_levels.correct_heading_levels(
            self.MARKDOWN, tmp_path / "protocol.md", log=logs.append
        )
        assert out == "<!-- page: 5 -->\n# Background Section\n\nbody\n"
        assert any("1/1" in line for line in logs)

    def test_an_unmatched_bookmark_is_counted_but_changes_nothing(self, tmp_path, monkeypatch):
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        monkeypatch.setattr(
            heading_levels, "extract_bookmarks",
            lambda source: [
                {"title": "Background Section", "level": 1, "page": 5},
                {"title": "Never In The Document", "level": 1, "page": 9},
            ],
        )
        logs = []
        out = heading_levels.correct_heading_levels(
            self.MARKDOWN, tmp_path / "protocol.md", log=logs.append
        )
        assert out == "<!-- page: 5 -->\n# Background Section\n\nbody\n"
        assert any("1/2" in line for line in logs)

    def test_bookmarks_past_the_level_ceiling_are_dropped(self, tmp_path, monkeypatch):
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        monkeypatch.setattr(
            heading_levels, "extract_bookmarks",
            lambda source: [{"title": "Background Section",
                             "level": markdown_markers.MAX_HEADING_LEVEL + 1, "page": 5}],
        )
        out = heading_levels.correct_heading_levels(self.MARKDOWN, tmp_path / "protocol.md")
        assert out == self.MARKDOWN

    def test_a_titleless_bookmark_is_dropped(self, tmp_path, monkeypatch):
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        monkeypatch.setattr(
            heading_levels, "extract_bookmarks",
            lambda source: [{"title": "", "level": 1, "page": 5}],
        )
        out = heading_levels.correct_heading_levels(self.MARKDOWN, tmp_path / "protocol.md")
        assert out == self.MARKDOWN

    def test_the_uppercase_sibling_is_used(self, tmp_path, monkeypatch):
        (tmp_path / "PROTOCOL.PDF").write_bytes(b"%PDF")
        seen = []

        def fake_extract(source):
            seen.append(source)
            return [{"title": "Background Section", "level": 2, "page": 5}]

        monkeypatch.setattr(heading_levels, "extract_bookmarks", fake_extract)
        out = heading_levels.correct_heading_levels(self.MARKDOWN, tmp_path / "PROTOCOL.md")
        assert seen == [tmp_path / "PROTOCOL.PDF"]
        assert out == "<!-- page: 5 -->\n## Background Section\n\nbody\n"

    def test_a_docx_without_page_markers_still_gets_its_levels(self, tmp_path, monkeypatch):
        # A DOCX has no physical pages, so no marker ever sets a candidate page: every match
        # is at the same (missing-page) distance and the later line wins.
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        monkeypatch.setattr(
            heading_levels, "extract_bookmarks",
            lambda source: [{"title": "Background Section", "level": 3, "page": 5}],
        )
        out = heading_levels.correct_heading_levels(
            "# Background Section\n\nbody\n", tmp_path / "protocol.md"
        )
        assert out == "### Background Section\n\nbody\n"

    def test_the_markdown_is_returned_unchanged_line_for_line(self, tmp_path, monkeypatch):
        # The split/join round trip must not add or drop a trailing newline.
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        monkeypatch.setattr(
            heading_levels, "extract_bookmarks",
            lambda source: [{"title": "Background Section", "level": 1, "page": 5}],
        )
        markdown = "# Background Section\n\nbody\n\n"
        out = heading_levels.correct_heading_levels(markdown, tmp_path / "protocol.md")
        assert out.count("\n") == markdown.count("\n")
        assert out.endswith("body\n\n")
