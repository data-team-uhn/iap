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

"""Unit tests for the dependency-free outline-record helpers in ``bookmarks``."""

import bookmarks

MD = ("<!-- page: 1 -->\n## Introduction\n"
      "<!-- page: 2 -->\n## Methods\n"
      "<!-- page: 3 -->\n## Results\n")


def _catalog(md: str):
    return bookmarks.build_lines_catalog(md.split("\n"))


def _verify(records, md: str):
    return bookmarks.verify_bookmarks(records, md, _catalog(md))


def _pages(md: str):
    return bookmarks.pages_from_positions(_catalog(md))


class TestNormalizeTitle:
    def test_strips_markup(self):
        assert bookmarks.normalize_title("## 1.0 Background:") == "10background"

    def test_casefold(self):
        assert bookmarks.normalize_title("**Introduction**") == "introduction"

    def test_empty(self):
        assert bookmarks.normalize_title("  ---  ") == ""


class TestPagesFromPositions:
    def test_groups_by_page(self):
        pages = _pages("<!-- page: 1 -->\n## Intro\ntext\n<!-- page: 2 -->\n## Methods")
        assert pages[1] == {"intro", "text"}
        assert pages[2] == {"methods"}

    def test_exact_marker_spacing_required(self):
        # Only the canonical marker starts a page; anything else is ordinary content, so its
        # text keys onto page 0 instead of opening page 4.
        for marker in ("<!--  page:  4  -->", "<!-- page: 4-->", "<!--page:4-->"):
            pages = _pages(f"{marker}\nFoo")
            assert 4 not in pages, marker
            assert "foo" in pages[0], marker


class TestVerifyBookmarks:
    def test_found_on_page_unchanged(self):
        assert _verify([{"title": "Methods", "level": 1, "page": 2}], MD) \
            == [{"title": "Methods", "level": 1, "page": 2}]

    def test_corrects_to_next_page(self):
        out = _verify([{"title": "Results", "page": 2}], MD)
        assert out[0]["page"] == 3 and "verified" not in out[0]

    def test_corrects_to_prev_page(self):
        out = _verify([{"title": "Introduction", "page": 2}], MD)
        assert out[0]["page"] == 1 and "verified" not in out[0]

    def test_not_found_sets_verified_false(self):
        out = _verify([{"title": "Appendix", "page": 2}], MD)
        assert out[0]["verified"] is False and out[0]["page"] == 2

    def test_pageless_unchanged(self):
        assert _verify([{"title": "Preface", "page": None}], MD) \
            == [{"title": "Preface", "page": None}]

    def test_input_not_mutated(self):
        records = [{"title": "Results", "page": 2}]
        _verify(records, MD)
        assert records == [{"title": "Results", "page": 2}]


class TestUnpagedDocument:
    """No page markers means no verification, and an early exit before the page map is built.

    A record's claimed page cannot be confirmed or contradicted in an unpaged document, so it
    must pass through rather than being flagged ``verified: False`` — which is what would
    happen if the records were run through the lookup against an empty page map.
    """

    UNPAGED = "## Introduction\n\nbody\n\n## Methods\n\nbody\n"

    def test_records_pass_through_untouched(self):
        records = [{"title": "Methods", "level": 1, "page": 7}]
        assert _verify(records, self.UNPAGED) == records

    def test_nothing_is_flagged_unverified(self):
        out = _verify([{"title": "Nowhere At All", "page": 3}], self.UNPAGED)
        assert "verified" not in out[0]

    def test_still_returns_fresh_dicts(self):
        # The early exit must keep the no-aliasing contract the loop had.
        records = [{"title": "Methods", "page": 7}]
        out = _verify(records, self.UNPAGED)
        out[0]["page"] = 999
        assert records[0]["page"] == 7

    def test_marker_not_on_its_own_line_is_not_a_page(self):
        # PAGE_MARKER.search is only the cheap necessary condition: build_lines_catalog requires the
        # marker anchored to its own line, so this document is still unpaged.
        inline = "Some prose <!-- page: 4 --> continues here\n## Methods\n"
        out = _verify([{"title": "Methods", "page": 9}], inline)
        assert out == [{"title": "Methods", "page": 9}]

    def test_empty_records(self):
        assert _verify([], self.UNPAGED) == []


class TestLinePages:
    def test_indices_pages_keys(self):
        md = "<!-- page: 1 -->\n## Intro\n<!-- page: 2 -->\n## Methods"
        assert _catalog(md) == [(1, 1, "intro"), (3, 2, "methods")]


class TestBuildLineIndex:
    def test_groups_positions_by_title_key(self):
        md = "<!-- page: 1 -->\n## Intro\n<!-- page: 2 -->\n## Intro\ntext"
        index = bookmarks.build_line_index(_catalog(md))
        assert index.by_key["intro"] == [(1, 1), (3, 2)]
        assert index.by_key["text"] == [(4, 2)]

    def test_has_pages_true_for_a_paged_document(self):
        index = bookmarks.build_line_index(_catalog(MD))
        assert index.has_pages is True

    def test_has_pages_false_for_an_unpaged_document(self):
        # DOCX output carries no page markers, so every line is page 0.
        index = bookmarks.build_line_index(_catalog("## Intro\nbody text"))
        assert index.has_pages is False

    def test_empty_document(self):
        index = bookmarks.build_line_index(_catalog(""))
        assert index.by_key == {} and index.has_pages is False


class TestResolveRecordLine:
    # "Methods" appears on both page 2 (line 3) and page 3 (line 5).
    MD = "<!-- page: 1 -->\n## Intro\n<!-- page: 2 -->\n## Methods\n<!-- page: 3 -->\n## Methods"

    def _index(self):
        return bookmarks.build_line_index(_catalog(self.MD))

    def test_page_guard_disambiguates(self):
        assert bookmarks.resolve_record_line(self._index(), {"title": "Methods", "page": 2}) == 3

    def test_ambiguous_without_page_guard_is_none(self):
        record = {"title": "Methods", "page": 2, "verified": False}
        assert bookmarks.resolve_record_line(self._index(), record) is None

    def test_unique_match(self):
        assert bookmarks.resolve_record_line(self._index(), {"title": "Intro", "page": 1}) == 1

    def test_absent_returns_none(self):
        assert bookmarks.resolve_record_line(self._index(), {"title": "Ghost", "page": 1}) is None

    def test_exclude_drops_the_only_match(self):
        record = {"title": "Intro", "page": 1}
        assert bookmarks.resolve_record_line(self._index(), record, exclude={1}) is None

    def test_exclude_disambiguates_two_matches(self):
        # Both "Methods" lines match without a trusted page; excluding one leaves exactly
        # one candidate. This is how the printed-TOC range rescues backmatter detection.
        record = {"title": "Methods", "page": 2, "verified": False}
        assert bookmarks.resolve_record_line(self._index(), record, exclude={3}) == 5

    def test_empty_title_returns_none(self):
        assert bookmarks.resolve_record_line(self._index(), {"title": "  ---  "}) is None
        assert bookmarks.resolve_record_line(self._index(), {}) is None

    def test_unpaged_document_ignores_the_claimed_page(self):
        index = bookmarks.build_line_index(_catalog("## Intro\nbody"))
        assert bookmarks.resolve_record_line(index, {"title": "Intro", "page": 99}) == 0


class TestTheTocIsNotItsOwnEvidence:
    """A record's page cannot be confirmed by the printed TOC entry that names it.

    Regression: every record's title appears in the TOC by definition, so counting those
    lines let the TOC confirm a page it says nothing about. A record whose page landed within
    the off-by-one window of the TOC's own page was "found" there and rewritten to it, then
    left trusted -- and :func:`resolve_record_line` honours a trusted page while excluding the
    TOC range, so the record could never resolve to anything. Marking it unverified instead
    lets it search the whole document and find its real heading. Printed-TOC records make this
    routine: their page is the number printed in the entry, offset from the PDF page index by
    whatever unnumbered front matter comes first.
    """

    # Printed TOC on PDF page 2 says "Background ... 3"; the real heading is on PDF page 5.
    MD = "\n".join([
        "<!-- page: 1 -->", "Title Page", "",
        "<!-- page: 2 -->", "TABLE OF CONTENTS", "Background", "Methods", "",
        "<!-- page: 3 -->", "Sponsor and funding details.", "",
        "<!-- page: 4 -->", "Signature page.", "",
        "<!-- page: 5 -->", "Background", "Body text about the study.", "",
    ])
    TOC_RANGE = (4, 6)
    REAL_HEADING_LINE = 15

    def _positions(self):
        return bookmarks.build_lines_catalog(self.MD.split("\n"))

    def test_the_page_is_not_rewritten_to_the_tocs_own_page(self):
        record = {"title": "Background", "page": 3}
        out = bookmarks.verify_bookmarks([record], self.MD, self._positions(), self.TOC_RANGE)[0]
        assert out["page"] == 3
        assert out["verified"] is False

    def test_the_record_then_resolves_to_the_real_heading(self):
        record = {"title": "Background", "page": 3}
        positions = self._positions()
        out = bookmarks.verify_bookmarks([record], self.MD, positions, self.TOC_RANGE)[0]
        index = bookmarks.build_line_index(positions)
        resolved = bookmarks.resolve_record_line(
            index, out, exclude=frozenset(range(self.TOC_RANGE[0], self.TOC_RANGE[1] + 1))
        )
        assert resolved == self.REAL_HEADING_LINE

    def test_a_page_confirmed_outside_the_toc_is_still_trusted(self):
        # The off-by-one correction must keep working on real body evidence.
        record = {"title": "Background", "page": 4}
        out = bookmarks.verify_bookmarks([record], self.MD, self._positions(), self.TOC_RANGE)[0]
        assert out["page"] == 5
        assert "verified" not in out

    def test_no_toc_range_keeps_the_previous_behaviour(self):
        # Callers without a detected TOC pass nothing and are unaffected.
        record = {"title": "Background", "page": 3}
        out = bookmarks.verify_bookmarks([record], self.MD, self._positions())[0]
        assert out["page"] == 2
