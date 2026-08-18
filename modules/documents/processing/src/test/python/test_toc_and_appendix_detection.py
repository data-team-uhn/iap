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

"""Tests for toc_and_appendix_detection: TOC entry recognition, label finding, in-place
TOC cleanup, Reference/Appendix heading detection, the size gate, and derive_outline.

The outline is derived by derive_outline, which writes nothing and returns
``(document, updates, records, line_index)``."""

import time

import toc_and_appendix_detection as tad
from bookmarks import build_line_index, build_lines_catalog


def _derive(md, markdown_path=None, min_structure_tokens=1):
    return tad.derive_outline(md, markdown_path, min_structure_tokens)


def _derive_with_bookmarks(monkeypatch, tmp_path, md, records, min_structure_tokens=1):
    md_path = tmp_path / "doc.md"
    (tmp_path / "doc.pdf").write_bytes(b"%PDF-1.4")
    # Stub PDF is not readable; feed records via extract, and keep verify as identity so
    # tests control the exact outline without page-correction side effects.
    monkeypatch.setattr(tad, "extract_bookmarks", lambda *a, **k: records)
    monkeypatch.setattr(tad, "verify_bookmarks", lambda *a, **k: records)
    return tad.derive_outline(md, md_path, min_structure_tokens)


class TestIsTocEntryLine:
    def test_tab_separated_entry(self):
        assert tad.is_toc_entry_line("Introduction\t3") is True

    def test_numbered_entry_with_tab(self):
        assert tad.is_toc_entry_line("1.0 General Information\t3") is True

    def test_dash_separated_entry(self):
        assert tad.is_toc_entry_line("Summary  -  2") is True

    def test_dot_leader_entry(self):
        assert tad.is_toc_entry_line("Schema.......4") is True

    def test_roman_numeral_page(self):
        assert tad.is_toc_entry_line("Abbreviations - v") is True

    def test_numbered_outline_without_page(self):
        assert tad.is_toc_entry_line("2.0 **Introduction**") is True

    def test_too_many_words_rejected(self):
        line = "This heading has far too many words to be a table entry line indeed\t3"
        assert tad.is_toc_entry_line(line) is False

    def test_plain_sentence_rejected(self):
        assert tad.is_toc_entry_line("This is a normal sentence.") is False

    def test_empty_rejected(self):
        assert tad.is_toc_entry_line("") is False


class TestEntryPatternsStayLinear:
    """CWE-1333. A leading ``\\s*`` in the entry patterns competed with the ``.+?`` title for
    the same spaces, so an indented line cost cubic time: 400 spaces then one word took most
    of a second, and a 2 KB one would have taken about two minutes. The document is caller
    input, so a crafted upload could tie up a worker for hours."""

    def test_a_deeply_indented_line_is_cheap(self):
        for size in (4_000, 40_000):
            line = " " * size + "Introduction"
            start = time.perf_counter()
            tad.is_toc_entry_line(line)
            elapsed = time.perf_counter() - start
            assert elapsed < 0.5, f"{size} spaces took {elapsed:.2f}s"

    def test_the_unguarded_predicate_is_cheap_too(self):
        # is_page_numbered_entry deliberately skips the word limit, so it has no other guard.
        for size in (4_000, 40_000):
            line = " " * size + "Introduction"
            start = time.perf_counter()
            tad.is_page_numbered_entry(line)
            elapsed = time.perf_counter() - start
            assert elapsed < 0.5, f"{size} spaces took {elapsed:.2f}s"

    def test_indented_entries_are_still_recognised(self):
        assert tad.is_toc_entry_line("    Introduction - 3") is True
        assert tad.is_toc_entry_line("\t1.0 Background\t9") is True
        assert tad.is_page_numbered_entry("   Methods  12") is True
        assert tad.is_toc_entry_line("   Not an entry at all") is False


class TestTocLabelLine:
    def test_decorated_atx_label(self):
        lines = ["# Protocol", "", "## Table of Contents", "", "Intro\t1"]
        assert tad.toc_label_line(lines) == 2

    def test_bold_contents_label(self):
        lines = ["**Contents**", "Intro\t1"]
        assert tad.toc_label_line(lines) == 0

    def test_bare_label_requires_isolation(self):
        isolated = ["", "table of contents", ""]
        assert tad.toc_label_line(isolated) == 1
        not_isolated = ["preamble text", "table of contents", "more text"]
        assert tad.toc_label_line(not_isolated) is None

    def test_absent_label(self):
        assert tad.toc_label_line(["# Title", "", "Body"]) is None


class TestMarkAndCleanupToc:
    def _doc(self):
        return (
            "# Study Protocol\n"
            "\n"
            "## Table of Contents\n"
            "\n"
            "Introduction\t1\n"
            "Background\t2\n"
            "Methods\t3\n"
            "Results\t4\n"
            "\n"
            "## Introduction\n"
            "\n"
            "The study begins here.\n"
        )

    def test_no_label_returns_unchanged(self):
        md = "# Title\n\nJust body text, no contents label.\n"
        result, fields = tad._detect_toc(md)
        assert result == md
        assert fields == {}

    def test_reports_range_and_entries(self):
        result, fields = tad._detect_toc(self._doc())
        # The label survives cleanup with its markers stripped.
        assert "Table of Contents" in result
        assert fields["tocStartLine"] == 2
        assert fields["tocEndLine"] >= fields["tocStartLine"]
        # Tab separators are normalized to a space in the reported entries.
        assert "Introduction 1" in fields["toc"]
        assert "Results 4" in fields["toc"]

    def test_empty_input(self):
        assert tad._detect_toc("") == ("", {})


class TestMarkTocAndAppendix:
    def test_size_gate_skips_small_docs_without_bookmarks(self):
        # No bookmarks and tokens below the default threshold → empty updates, no line index.
        md = "# Small\n\nToo short for structure detection.\n"
        result, updates, records, line_index = tad.derive_outline(md)
        assert result == md
        assert updates == {}
        assert records == []
        assert line_index is None

    def test_default_threshold_constant(self):
        assert tad.DEFAULT_MIN_STRUCTURE_TOKENS == 20000

    def test_records_tokens_when_not_gated(self):
        md = "# Title\n\n" + ("Some content paragraph. " * 40)
        _, updates, _, _ = _derive(md)
        assert updates["tokens"] == len(md) // 4

    def test_bookmark_path_records_outline_even_when_small(self, monkeypatch, tmp_path):
        # Bookmarks beat the size gate, so a small document still carries a toc.
        known = [{"title": "Alpha", "level": 1, "page": 1}]
        _, updates, _, _ = _derive_with_bookmarks(
            monkeypatch, tmp_path, "# Tiny\n\nbody\n", known
        )
        assert updates["toc_source"] == "pdf-bookmarks"
        assert updates["toc"] == ["Alpha"]


class TestEntryToRecord:
    def test_dash_page_and_level(self):
        assert tad._entry_to_record("1.0 Background - 9") == {"title": "1.0 Background", "level": 1, "page": 9}

    def test_tab_collapsed_single_space(self):
        assert tad._entry_to_record("Introduction 1") == {"title": "Introduction", "level": None, "page": 1}

    def test_roman_page(self):
        assert tad._entry_to_record("Abbreviations - v") == {"title": "Abbreviations", "level": None, "page": 5}

    def test_no_page_keeps_level(self):
        assert tad._entry_to_record("2.0 Introduction") == {"title": "2.0 Introduction", "level": 1, "page": None}

    def test_section_letters_are_not_roman_pages(self):
        # A trailing section letter is not a roman page: "Appendix C" is the whole title,
        # not title "Appendix" page 100 (which would never match the body heading).
        for entry in ("Appendix C", "Appendix D", "Appendix I", "Annex X"):
            assert tad._entry_to_record(entry) == {"title": entry, "level": None, "page": None}

    def test_trailing_initials_are_not_pages(self):
        assert tad._entry_to_record("Participant ID") == {
            "title": "Participant ID", "level": None, "page": None,
        }

    def test_uppercase_roman_still_reads_after_a_strong_separator(self):
        # A dash or dot leaders make it unambiguously a TOC line, so case does not matter.
        assert tad._entry_to_record("Preface - IV")["page"] == 4
        assert tad._entry_to_record("Preface .... IV")["page"] == 4

    def test_lowercase_roman_reads_after_a_bare_space(self):
        assert tad._entry_to_record("List of Abbreviations vii")["page"] == 7

    def test_malformed_roman_is_not_a_page(self):
        assert tad._entry_to_record("Section iiii")["page"] is None

    def test_a_very_long_entry_stays_cheap(self):
        # CWE-1333. Every _ENTRY_PAGE separator starts with a whitespace run, so searching the
        # whole string retried from each position: 4k characters cost 0.7s. _confirm_table_toc
        # passes flattened table rows, which have no length limit.
        for size in (40_000, 400_000):
            entry = " " * size + "Intro"
            start = time.perf_counter()
            tad._entry_to_record(entry)
            elapsed = time.perf_counter() - start
            assert elapsed < 0.5, f"{size} chars took {elapsed:.2f}s"

    def test_the_page_is_still_found_past_a_long_separator(self):
        # The window bounds where the search starts, not what counts as the end of the entry.
        assert tad._entry_to_record("Intro" + " " * 300 + "7") == {
            "title": "Intro", "level": None, "page": 7,
        }
        assert tad._entry_to_record("Intro" + "." * 300 + "7")["page"] == 7


class TestTableTocConfirmation:
    """A table under a "Contents" label is only flattened when it really is a TOC."""

    def _doc(self, rows):
        return "**Contents**\n\n" + rows + "\nBody text follows here.\n"

    def test_a_real_table_toc_is_still_flattened(self):
        rows = (
            "| Section | Title | Page |\n"
            "| --- | --- | --- |\n"
            "| 1.0 | Introduction | 3 |\n"
            "| 2.0 | Objectives | 5 |\n"
            "| 3.0 | Study Design | 8 |\n"
            "| 4.0 | Statistics | 12 |\n"
        )
        result, fields = tad._detect_toc(self._doc(rows))
        assert "1.0 Introduction 3" in fields["toc"]
        assert "| 1.0 |" not in result

    def test_a_data_table_under_a_contents_label_is_left_alone(self):
        # Kit/shipment "Contents" sections are data tables. Flattening one destroys it:
        # separator rows dropped, cells merged, duplicate cells removed.
        rows = (
            "| Item | Quantity |\n"
            "| --- | --- |\n"
            "| Syringe 10 mL | 4 |\n"
            "| Needle 21G | 12 |\n"
            "| Alcohol swab | 2 |\n"
            "| Label sheet | 1 |\n"
        )
        doc = self._doc(rows)
        result, fields = tad._detect_toc(doc)
        assert fields == {}
        assert result == doc

    def test_too_few_rows_is_not_a_toc(self):
        rows = "| Item | Page |\n| --- | --- |\n| Only one | 3 |\n"
        doc = self._doc(rows)
        assert tad._detect_toc(doc) == (doc, {})


class TestMarkTocAndAppendixFork:
    def _doc_with_toc(self):
        return (
            "# Study Protocol\n\n## Table of Contents\n\n"
            "1.0 Introduction\t1\n2.0 Methods\t2\nReferences\t3\n\n"
            "## 1.0 Introduction\n\nbody\n\n## 2.0 Methods\n\nbody\n\n## References\n\ncites\n"
        )

    def test_manual_path_records_toc_and_backmatter(self):
        _, updates, records, _ = _derive(self._doc_with_toc())
        assert updates["toc_source"] == "md-toc"
        assert updates["toc"] == ["1.0 Introduction", "2.0 Methods", "References"]
        assert "backmatterLine" in updates
        # The harvested records come back as the third return value; they are not written to disk.
        assert {"title": "1.0 Introduction", "level": 1, "page": 1} in records

    def test_bookmark_path_discards_the_printed_toc_entries(self, monkeypatch, tmp_path):
        # The printed TOC is still cleaned; its *entries* are what the PDF bookmark records
        # displace — ``toc`` comes from the records, not from the page.
        known = [{"title": "Alpha", "level": 1, "page": 1}]
        result, updates, _, _ = _derive_with_bookmarks(
            monkeypatch, tmp_path, self._doc_with_toc(), known
        )
        assert updates["toc_source"] == "pdf-bookmarks"
        assert updates["toc"] == ["Alpha"]
        assert "## Table of Contents" in result
        assert isinstance(updates["tocStartLine"], int)
        assert updates["tocEndLine"] >= updates["tocStartLine"]

    def test_bookmark_path_reports_toc_range(self, monkeypatch, tmp_path):
        # Without a range, a page-less "References" entry collides with the body heading it
        # points at, and both backmatter_from_records and chunker._record_cut_keys fail open.
        pageless = [{"title": "References", "level": None, "page": None}]
        _, updates, _, _ = _derive_with_bookmarks(
            monkeypatch, tmp_path, self._doc_with_toc(), pageless
        )
        assert "tocStartLine" in updates
        assert "backmatterLine" in updates

    def test_toc_source_none_when_nothing_found(self):
        _, updates, _, _ = _derive(
            "# Title\n\n" + ("body paragraph. " * 40)
        )
        assert updates["toc_source"] == "none"


# A running header of the kind Docling leaves at the top of every protocol page. Well over
# MAX_LEADING_WORDS_FOR_CONTINUATION on its own, which is the point: the in-block prose
# tolerance cannot absorb it, so a TOC split across pages needs the page-break probe.
RUNNING_HEADER = "REB Protocol 24-5450 Version 3.0 dated 12 March 2026 Confidential Page 2 of 48"


class TestTocAcrossPageBreaks:
    """A printed TOC that continues on the next PDF page."""

    def _doc(self, separator):
        return "\n".join([
            "<!-- page: 1 -->",
            "## TABLE OF CONTENTS",
            "1.0 Background and Rationale\t3",
            "2.0 Study Objectives\t5",
            "3.0 Eligibility Criteria\t7",
            "4.0 Study Design\t9",
            "<!-- page: 2 -->",
            *separator,
            "5.0 Statistical Analysis\t12",
            "6.0 Data Management\t15",
            "7.0 Ethical Considerations\t18",
            "8.0 References and Appendices\t22",
            "",
            "<!-- page: 3 -->",
            "## 1.0 Background and Rationale",
            "Body text of the background section.",
        ])

    def _entries(self, separator):
        return tad._detect_toc(self._doc(separator))[1]["toc"]

    def test_continues_straight_across_a_page_marker(self):
        entries = self._entries([])
        assert len(entries) == 8
        assert any("Statistical Analysis" in entry for entry in entries)

    def test_continues_past_a_running_header(self):
        entries = self._entries([RUNNING_HEADER])
        assert len(entries) == 8, entries
        assert any("References and Appendices" in entry for entry in entries)

    def test_running_header_is_not_kept_as_an_entry(self):
        entries = self._entries([RUNNING_HEADER])
        assert not any("Confidential" in entry for entry in entries)

    def test_stops_when_the_next_page_is_body_text(self):
        body = ["## Introduction Section", "Real body prose starts here."]
        entries = self._entries(body)
        # A real heading after the break is a hard boundary: only page 1's entries.
        assert len(entries) == 4

    def test_gives_up_after_too_much_header_noise(self):
        noise = [f"{RUNNING_HEADER} line {i}" for i in range(6)]
        entries = self._entries(noise)
        assert len(entries) == 4


class TestTolerated_LinesBeforeAnEndingPageMarker:
    """Content between the last TOC entry and a page marker must survive.

    Regression: ``_scan_block`` rolled the boundary back past its trailing tolerated run on
    the body-text and end-of-document exits but not on the page-marker one. A short line
    sitting there was therefore left out of the collected block *and* inside the range the
    cleaned block replaced, so it was deleted from the document and from every chunk built
    from it.
    """

    def _doc(self, tail):
        return "\n".join([
            "## TABLE OF CONTENTS",
            "",
            "1.0 Introduction\t3",
            "2.0 Background\t5",
            "3.0 Objectives\t7",
            "4.0 Study Design\t9",
            *tail,
            "",
            "<!-- page: 2 -->",
            "",
            "Body prose starts here and runs on for a good while afterwards.",
        ])

    def test_a_short_line_before_the_marker_is_not_deleted(self):
        document = tad._detect_toc(self._doc(["IMPORTANT SAFETY TEXT HERE"]))[0]
        assert "IMPORTANT SAFETY TEXT HERE" in document

    def test_it_is_not_harvested_as_an_entry_either(self):
        entries = tad._detect_toc(self._doc(["IMPORTANT SAFETY TEXT HERE"]))[1]["toc"]
        assert not any("SAFETY" in entry for entry in entries)

    def test_the_toc_itself_is_still_cleaned(self):
        entries = tad._detect_toc(self._doc(["IMPORTANT SAFETY TEXT HERE"]))[1]["toc"]
        assert len(entries) == 4

    def test_nothing_is_lost_when_there_is_no_tolerated_run(self):
        document = tad._detect_toc(self._doc([]))[0]
        assert "Body prose starts here" in document


class TestTableTocEntryHarvest:
    """A confirmed table TOC must yield its entries, not just get flattened.

    Regression: harvesting re-tested the already-flattened rows with ``is_toc_entry_line``,
    whose separators never match the single space ``_row_to_line`` joins cells with. The
    document was rewritten destructively and ``toc`` came back empty, so ``toc_source`` fell
    to "none" and ``backmatterLine`` was never set.
    """

    def _fields(self, rows):
        document = "\n".join([
            "## Table of Contents",
            "",
            "| Section | Page |",
            "| --- | --- |",
            *rows,
            "",
            "## Introduction",
            "Body prose.",
        ])
        return tad._detect_toc(document)[1]

    ROWS = [
        "| Protocol Summary | 3 |",
        "| Study Schema | 5 |",
        "| Background Rationale | 7 |",
        "| Study Objectives | 9 |",
    ]

    def test_unnumbered_rows_are_harvested(self):
        entries = self._fields(self.ROWS)["toc"]
        assert len(entries) == 4
        assert any("Protocol Summary" in entry for entry in entries)

    def test_the_page_numbers_survive_flattening(self):
        entries = self._fields(self.ROWS)["toc"]
        assert entries[0].endswith("3")

    def test_numbered_rows_still_work(self):
        rows = [
            "| 1.0 Introduction | 3 |",
            "| 2.0 Background | 5 |",
            "| 3.0 Objectives | 7 |",
            "| 4.0 Design | 9 |",
        ]
        assert len(self._fields(rows)["toc"]) == 4


class TestResumeAfterPageBreak:
    LINES = ["<!-- page: 2 -->", RUNNING_HEADER, "5.0 Statistical Analysis\t12"]

    def test_finds_the_resumption_line_past_noise(self):
        assert tad._resume_after_page_break(self.LINES, 1, tad.is_toc_entry_line) == 2

    def test_none_at_a_real_heading(self):
        lines = ["<!-- page: 2 -->", "## Real Heading Here", "1.0 Entry\t3"]
        assert tad._resume_after_page_break(lines, 1, tad.is_toc_entry_line) is None

    def test_none_when_the_label_reappears(self):
        lines = ["<!-- page: 2 -->", "## Table of Contents", "1.0 Entry\t3"]
        assert tad._resume_after_page_break(lines, 1, tad.is_toc_entry_line) is None

    def test_none_past_end_of_document(self):
        assert tad._resume_after_page_break(["<!-- page: 2 -->"], 1, tad.is_toc_entry_line) is None

    def test_none_when_noise_exceeds_the_allowance(self):
        noise = [f"noise line {i} with several words in it" for i in range(6)]
        lines = ["<!-- page: 2 -->", *noise, "1.0 Entry\t3"]
        assert tad._resume_after_page_break(lines, 1, tad.is_toc_entry_line) is None


class TestOverlongTocEntry:
    """One TOC entry longer than the word limit must not end the block.

    Regression: such an entry fails ``is_toc_entry_line`` on word count, was then charged
    against the prose tolerance as if it were body text, and ended the TOC — silently
    dropping every entry after it, References and Appendices included, which in turn left
    ``backmatterLine`` unset.
    """

    def _doc(self):
        return "\n".join([
            "## TABLE OF CONTENTS",
            "1.0 Background\t3",
            "2.0 Objectives\t5",
            # 15 words: over MAX_HEADING_WORDS, but structurally a page-numbered entry.
            "6.11 Adaptations for outcome assessment for patients who cannot attend "
            "facility based outcome assessment\t15",
            "7.0 Ethics\t16",
            "19.0 References\t20",
            "20.0 Appendices\t27",
            "",
            "## 1.0 Background",
            "body",
        ])

    def _entries(self):
        return tad._detect_toc(self._doc())[1]["toc"]

    def test_entries_after_the_long_one_survive(self):
        entries = self._entries()
        assert any("References" in entry for entry in entries), entries
        assert any("Appendices" in entry for entry in entries), entries

    def test_the_long_entry_itself_is_still_not_recorded(self):
        assert not any("Adaptations" in entry for entry in self._entries())

    def test_page_numbered_entry_predicate_ignores_length(self):
        long_entry = ("6.11 Adaptations for outcome assessment for patients who cannot "
                      "attend facility based outcome assessment\t15")
        assert tad.is_toc_entry_line(long_entry) is False
        assert tad.is_page_numbered_entry(long_entry) is True

    def test_numbered_body_prose_is_not_page_numbered_entry(self):
        # The trailing-page-number requirement is what keeps this safe: ordinary numbered
        # prose must not look like an entry, or the scan would run on into a numbered list.
        for prose in ("1. The patient will be assessed for eligibility at baseline.",
                      "2) Participants receive standard of care prehabilitation therapy.",
                      "3.1 Data will be collected using the validated instrument above."):
            assert tad.is_page_numbered_entry(prose) is False, prose


class TestBackmatterTocExclusion:
    """The printed TOC stays in the document, so a page-less TOC entry is textually
    identical to the body heading it points at. Without the TOC range excluded both lines
    match the record, ``resolve_record_line`` fails open, and the backmatter split is lost.
    """

    MD = "\n".join([
        "TABLE OF CONTENTS",
        "1.0 Background",
        "2.0 Study Design",
        "8.0 References",
        "",
        "## 1.0 Background",
        "body",
        "## 8.0 References",
        "Smith J et al. Lancet. 2020.",
    ])
    RECORDS = [{"title": "8.0 References", "level": 1, "page": None}]

    def _index(self):
        return build_line_index(build_lines_catalog(self.MD.split("\n")))

    def test_ambiguous_without_the_toc_range(self):
        assert tad.backmatter_from_records(self.RECORDS, self._index()) is None

    def test_resolves_to_the_body_heading_with_the_toc_range(self):
        assert tad.backmatter_from_records(self.RECORDS, self._index(), (0, 3)) == 7

    def test_no_backmatter_record_short_circuits(self):
        records = [{"title": "1.0 Background", "page": None}]
        assert tad.backmatter_from_records(records, self._index(), (0, 3)) is None

    def test_end_to_end_records_backmatter_for_a_pageless_toc(self):
        doc = "\n".join([
            "## Table of Contents",
            "1.0 Background",
            "2.0 Study Design",
            "8.0 References",
            "",
            "## 1.0 Background",
            "body paragraph. " * 20,
            "## 2.0 Study Design",
            "body paragraph. " * 20,
            "## 8.0 References",
            "Smith J et al. Lancet. 2020.",
        ])
        _, updates, _, _ = _derive(doc)
        assert updates["toc_source"] == "md-toc"
        assert isinstance(updates["backmatterLine"], int)
        # It must point at the body heading, not the TOC entry.
        assert updates["backmatterLine"] > updates["tocEndLine"]


class TestBackmatterOnTheBookmarksPath:
    """The printed TOC has to be excluded from the backmatter lookup on *both* paths.

    Regression: authoritative bookmarks leave the printed TOC in the document on purpose, but
    ``toc_range`` was only known on the path that ran TOC detection. Without it, a page-less
    "19.0 References" entry is textually identical to the body heading it points at, both lines
    match the record, ``resolve_record_line`` fails open, and ``backmatterLine`` is dropped —
    leaving References and Appendices buried in the main chunks for every bookmarked document.
    """

    def _doc(self):
        return "\n".join([
            "# Study Protocol", "", "## Table of Contents", "",
            "1.0 Background", "2.0 Methods", "19.0 References", "",
            "## 1.0 Background", "body paragraph. " * 30,
            "## 2.0 Methods", "body paragraph. " * 30,
            "## 19.0 References", "Smith J et al. Lancet. 2020.",
        ])

    RECORDS = [{"title": "1.0 Background", "level": 1, "page": 3},
               {"title": "2.0 Methods", "level": 1, "page": 5},
               {"title": "19.0 References", "level": 1, "page": 20}]

    def _backmatter(self, monkeypatch, tmp_path, records):
        doc = self._doc()
        if records is None:
            _, updates, _, _ = _derive(doc)
        else:
            _, updates, _, _ = _derive_with_bookmarks(
                monkeypatch, tmp_path, doc, records
            )
        line = updates.get("backmatterLine")
        return line, (doc.split("\n")[line] if isinstance(line, int) else None)

    def test_resolves_with_paged_bookmarks(self, monkeypatch, tmp_path):
        line, text = self._backmatter(monkeypatch, tmp_path, self.RECORDS)
        assert text == "## 19.0 References", (line, text)

    def test_resolves_with_page_less_bookmarks(self, monkeypatch, tmp_path):
        # The case that failed: no page to disambiguate with, so the TOC line had to be excluded.
        pageless = [dict(record, page=None) for record in self.RECORDS]
        line, text = self._backmatter(monkeypatch, tmp_path, pageless)
        assert text == "## 19.0 References", (line, text)

    def test_points_at_the_body_not_the_toc_entry(self, monkeypatch, tmp_path):
        line, _ = self._backmatter(monkeypatch, tmp_path, self.RECORDS)
        assert line > self._doc().split("\n").index("19.0 References")

    def test_matches_the_printed_toc_path(self, monkeypatch, tmp_path):
        # Printed path first so an extract/verify monkeypatch cannot leak into it.
        without, _ = self._backmatter(monkeypatch, tmp_path, None)
        with_bookmarks, _ = self._backmatter(monkeypatch, tmp_path, self.RECORDS)
        assert with_bookmarks == without


class TestOutlineIsOneObject:
    def test_all_fields_arrive_in_a_single_dict(self):
        doc = "\n".join([
            "## Table of Contents",
            "1.0 Background\t3",
            "2.0 Objectives\t5",
            "3.0 Design\t7",
            "",
            "## 1.0 Background",
            "body paragraph. " * 60,
        ])
        _, updates, _, _ = _derive(doc)
        assert {"tocStartLine", "tocEndLine", "toc", "tokens", "toc_source"} <= set(updates)

    def test_tokens_reflect_the_returned_document(self):
        doc = "# Title\n\n" + ("Some content paragraph. " * 40)
        result, updates, _, _ = _derive(doc)
        assert updates["tokens"] == len(result) // 4
