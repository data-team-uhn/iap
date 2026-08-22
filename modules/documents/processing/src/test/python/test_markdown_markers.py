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

"""Tests for the shared markers module.

Pin the canonical ``<!-- page: N -->`` form, assert every consumer agrees on it, and
assert that every other spacing is rejected."""

import time

import chunker
import heading_helpers
import markdown_cleanup
import markdown_markers as mm

# The canonical form, exactly as markdown_markers.get_page_marker builds it and as the PDF
# parser emits it.
EMITTED = "<!-- page: 12 -->"
NON_CANONICAL = "<!-- page: 12-->"


class TestCanonicalPageMarker:
    def test_page_marker_builds_the_canonical_form(self):
        assert mm.get_page_marker(12) == EMITTED

    def test_page_marker_has_a_space_before_the_close(self):
        assert mm.get_page_marker(7) == "<!-- page: 7 -->"


class TestPageMarkerFormat:
    def test_matches_the_canonical_emitted_form(self):
        assert mm.PAGE_MARKER.search(EMITTED) is not None
        assert mm.PAGE_MARKER_LINE.match(EMITTED) is not None

    def test_captures_the_page_number(self):
        assert mm.PAGE_MARKER.search(EMITTED).group(1) == "12"
        assert mm.PAGE_MARKER_LINE.match(EMITTED).group(1) == "12"

    def test_rejects_other_spacings(self):
        for text in ("<!--  page:  4  -->", "<!--page:7-->", "<!--\tpage:\t9\t-->",
                     "<!--page: 7 -->", "<!-- page:7 -->", "<!-- page: 7-->"):
            assert mm.PAGE_MARKER_LINE.match(text) is None, text

    def test_line_pattern_tolerates_surrounding_whitespace(self):
        assert mm.PAGE_MARKER_LINE.match(f"  {EMITTED}  ") is not None

    def test_line_pattern_rejects_a_marker_with_trailing_content(self):
        assert mm.PAGE_MARKER_LINE.match(f"{EMITTED} and more text") is None

    def test_split_pattern_yields_exactly_one_group(self):
        # re.split returns every group; a second one would break markdown_cleanup's
        # stride-2 walk over the split parts.
        assert mm.PAGE_MARKER_SPLIT.groups == 1

    def test_split_keeps_the_marker_and_its_newlines(self):
        assert mm.PAGE_MARKER_SPLIT.split(f"a\n{EMITTED}\nb") == ["a", f"\n{EMITTED}\n", "b"]

    def test_split_does_not_recognise_non_canonical_spacing(self):
        text = f"a\n{NON_CANONICAL}\nb"
        assert mm.PAGE_MARKER_SPLIT.split(text) == [text]

    def test_line_pattern_allows_only_line_level_whitespace_slack(self):
        # Indentation and a stray carriage return are tolerated; the marker's own internal
        # spacing is not.
        assert mm.PAGE_MARKER_LINE.match(f"   {EMITTED}\r") is not None
        assert mm.PAGE_MARKER_LINE.match("   <!-- page: 12-->  ") is None


class TestConsumersAgreeOnTheMarker:
    """Every stage that recognises a page marker must recognise the emitted one.

    The outline module used to be one of them. It no longer reads page markers itself -- it
    hands the document to :func:`bookmarks.build_lines_catalog`, which is covered here through
    the ``_get_page_range`` helper.
    """

    def test_every_stage_reads_the_canonical_marker(self):
        assert heading_helpers.is_neutral(EMITTED) is True
        assert chunker._get_page_range(f"body\n{EMITTED}\nmore") == "11-12"
        assert markdown_cleanup.PAGE_MARKER_SPLIT.search(f"a\n{EMITTED}\nb") is not None

    def test_every_stage_rejects_non_canonical_spacing(self):
        assert heading_helpers.is_neutral(NON_CANONICAL) is False
        assert chunker._get_page_range(f"body\n{NON_CANONICAL}\nmore") == ""
        assert markdown_cleanup.PAGE_MARKER_SPLIT.search(f"a\n{NON_CANONICAL}\nb") is None


class TestHeadingPattern:
    def test_captures_level_and_text(self):
        match = mm.HEADING.match("### Study Design")
        assert match.group(1) == "###" and match.group(2) == "Study Design"

    def test_rejects_hashes_past_the_heading_ceiling(self):
        assert mm.HEADING.match("#" * mm.MAX_HEADING_LEVEL + " Deep") is not None
        assert mm.HEADING.match("#" * (mm.MAX_HEADING_LEVEL + 1) + " Deeper") is None

    def test_rejects_hash_without_space(self):
        assert mm.HEADING.match("#NoSpace") is None

    def test_rejects_empty_heading(self):
        assert mm.HEADING.match("## ") is None

    def test_a_hash_line_of_only_whitespace_stays_linear(self):
        # CWE-1333. When the tail was written as (.*\S)\s*$ those two repetitions competed
        # for the same whitespace and the engine retried once per space: a 16k-space line
        # took 1.4s, and the cost is quadratic. _match_heading takes raw document lines, and
        # the document comes from the caller, so this is reachable from a crafted upload.
        for size in (200_000, 800_000):
            line = "# " + " " * size
            start = time.perf_counter()
            assert mm.HEADING.match(line) is None
            elapsed = time.perf_counter() - start
            assert elapsed < 1.0, f"{size} spaces took {elapsed:.2f}s"

    def test_long_real_headings_stay_linear_too(self):
        line = "## " + "Study Design " * 20_000
        start = time.perf_counter()
        assert mm.HEADING.match(line) is not None
        assert time.perf_counter() - start < 1.0

    def test_the_heading_text_group_may_carry_trailing_whitespace(self):
        # _match_heading strips trailing whitespace; every other caller uses HEADING as a
        # predicate and does not care about the captured tail.
        assert mm.HEADING.match("## Study Design   ").group(2) == "Study Design   "
        assert mm.HEADING.match("## Study Design").group(2) == "Study Design"


class TestRuleLine:
    def test_matches_three_or_more_dashes(self):
        assert mm.RULE_LINE.match("---") is not None
        assert mm.RULE_LINE.match("-------") is not None

    def test_rejects_two_dashes_and_mixed_content(self):
        assert mm.RULE_LINE.match("--") is None
        assert mm.RULE_LINE.match("--- text") is None


class TestCountTokens:
    def test_quarter_of_length(self):
        assert mm.count_tokens("a" * 40) == 10

    def test_empty(self):
        assert mm.count_tokens("") == 0

    def test_floors_rather_than_rounds(self):
        assert mm.count_tokens("abc") == 0


class TestSupportedSuffixes:
    def test_docling_suffixes(self):
        assert mm.SUPPORTED_SUFFIXES == (".pdf", ".docx")

    def test_input_suffixes_include_doc(self):
        assert mm.INPUT_SUFFIXES == (".pdf", ".docx", ".doc")
