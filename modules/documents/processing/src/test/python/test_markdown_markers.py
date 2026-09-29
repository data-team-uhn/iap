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

    def test_captures_the_page_number(self):
        assert mm.PAGE_MARKER.search(EMITTED).group(1) == "12"

    def test_rejects_other_spacings(self):
        for text in ("<!--  page:  4  -->", "<!--page:7-->", "<!--\tpage:\t9\t-->",
                     "<!--page: 7 -->", "<!-- page:7 -->", "<!-- page: 7-->"):
            assert mm.PAGE_MARKER.search(text) is None, text

    def test_split_pattern_yields_exactly_one_group(self):
        # re.split returns every group; a second one would break markdown_cleanup's
        # stride-2 walk over the split parts.
        assert mm.PAGE_MARKER_SPLIT.groups == 1

    def test_split_keeps_the_marker_and_its_newlines(self):
        assert mm.PAGE_MARKER_SPLIT.split(f"a\n{EMITTED}\nb") == ["a", f"\n{EMITTED}\n", "b"]

    def test_split_does_not_recognise_non_canonical_spacing(self):
        text = f"a\n{NON_CANONICAL}\nb"
        assert mm.PAGE_MARKER_SPLIT.split(text) == [text]


class TestConsumersAgreeOnTheMarker:
    """Every stage that recognises a page marker must recognise the emitted one."""

    def test_every_stage_reads_the_canonical_marker(self):
        assert markdown_cleanup.PAGE_MARKER_SPLIT.search(f"a\n{EMITTED}\nb") is not None

    def test_every_stage_rejects_non_canonical_spacing(self):
        assert markdown_cleanup.PAGE_MARKER_SPLIT.search(f"a\n{NON_CANONICAL}\nb") is None


class TestDefangPageMarkers:
    """A marker the document itself carries must not survive as a real one: it decides the
    page an extracted answer is cited to."""

    def test_a_submitted_marker_is_escaped(self):
        assert mm.defang_page_markers(f"see {EMITTED}") == "see &lt;!-- page: 12 -->"

    def test_ordinary_text_is_untouched(self):
        assert mm.defang_page_markers("page 12 of the protocol") == "page 12 of the protocol"


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
