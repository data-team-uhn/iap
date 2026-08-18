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

"""Tests for chunker: heading recognition and token helpers, plus the end-to-end
chunk_file() routing decision (small documents left whole, large ones split) and its
outline.json / catalog.json outputs."""

import json

import pytest

import chunker


class TestValidHeading:
    def test_ordinary_heading(self):
        assert chunker.valid_heading("Introduction") is True

    def test_too_short_rejected(self):
        assert chunker.valid_heading("Hi") is False

    def test_table_caption_rejected(self):
        assert chunker.valid_heading("Table 1: Baseline characteristics") is False

    def test_too_many_words_rejected(self):
        assert chunker.valid_heading("one two three four five six seven eight nine ten eleven") is False

    def test_overlong_word_rejected(self):
        assert chunker.valid_heading("word " + "x" * 101) is False


class TestHeadingMatching:
    def test_match_heading_level_and_text(self):
        assert chunker._match_heading("## Foo Bar") == (2, "Foo Bar")

    def test_match_heading_deepest_level(self):
        assert chunker._match_heading("###### Deep Heading") == (6, "Deep Heading")

    def test_match_heading_seven_hashes_is_not_a_heading(self):
        assert chunker._match_heading("####### Seven") is None

    def test_match_heading_plain_line(self):
        assert chunker._match_heading("plain text line") is None

    def test_heading_level_filters_invalid_headings(self):
        assert chunker._heading_level("## Introduction") == 2
        # A "Table ..." caption is a heading syntactically but not a chunk boundary.
        assert chunker._heading_level("## Table 1: Overview") is None

    def test_min_heading_level(self):
        # Every heading text must clear MIN_HEADING_CHARS (5) to count as a boundary.
        lines = "# Alpha\n## Bravo\n### Gamma".split("\n")
        assert chunker._min_heading_level(lines) == 1
        assert chunker._min_heading_level(lines, deeper_than=1) == 2
        assert chunker._min_heading_level(["no headings here"]) is None


class TestStandoutHeading:
    def test_isolated_bold_heading(self):
        lines = ["", "**FUNDING SOURCE**", ""]
        assert chunker._standout_heading(lines, 1) == "FUNDING SOURCE"

    def test_isolated_all_caps_heading(self):
        lines = ["", "REFERENCES", ""]
        assert chunker._standout_heading(lines, 1) == "REFERENCES"

    def test_not_isolated_returns_none(self):
        lines = ["body text", "**FUNDING SOURCE**", "more body"]
        assert chunker._standout_heading(lines, 1) is None


class TestNeutralAndTokens:
    def test_is_neutral(self):
        assert chunker.is_neutral("") is True
        assert chunker.is_neutral("---") is True
        assert chunker.is_neutral("<!-- page: 3 -->") is True
        assert chunker.is_neutral("Real content") is False

    def test_count_tokens_is_quarter_of_length(self):
        assert chunker.count_tokens("a" * 40) == 10
        assert chunker.count_tokens("") == 0


class TestChunkFile:
    def _write_small(self, tmp_path):
        path = tmp_path / "small.md"
        path.write_text("# Tiny protocol\n\nSome short content.\n", encoding="utf-8")
        return path

    def _write_large(self, tmp_path):
        paragraph = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 60
        sections = [f"# Section {i} Heading\n\n{paragraph}\n" for i in range(1, 51)]
        path = tmp_path / "large.md"
        path.write_text("\n".join(sections), encoding="utf-8")
        return path

    def test_missing_file_raises(self, tmp_path):
        with pytest.raises(FileNotFoundError):
            chunker.chunk_file(str(tmp_path / "does-not-exist.md"))

    def test_small_document_not_chunked(self, tmp_path):
        path = self._write_small(tmp_path)
        summary = chunker.chunk_file(str(path))
        assert summary["chunks"] == 0

        outline_path = path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME
        assert outline_path.is_file()
        outline = json.loads(outline_path.read_text(encoding="utf-8"))
        assert outline["chunked"] is False
        assert outline["fileId"] == "small.md"
        assert outline["toc"] == []
        assert isinstance(outline["tokens"], int) and outline["tokens"] > 0
        # No catalog for an unchunked document.
        assert not (path.parent / chunker.CHUNKS_DIRNAME / chunker.CATALOG_NAME).exists()

    def test_small_document_rerun_is_stable(self, tmp_path):
        path = self._write_small(tmp_path)
        assert chunker.chunk_file(str(path))["chunks"] == 0
        assert chunker.chunk_file(str(path))["chunks"] == 0

    def test_large_document_chunked(self, tmp_path):
        path = self._write_large(tmp_path)
        summary = chunker.chunk_file(str(path))
        assert summary["chunks"] > 0

        chunks_dir = path.parent / chunker.CHUNKS_DIRNAME
        outline = json.loads((chunks_dir / chunker.OUTLINE_NAME).read_text(encoding="utf-8"))
        assert outline["chunked"] is True
        assert (chunks_dir / chunker.CATALOG_NAME).is_file()
        catalog = json.loads((chunks_dir / chunker.CATALOG_NAME).read_text(encoding="utf-8"))
        assert len(catalog["chunks"]) == summary["chunks"]

    def test_catalog_length_matches_the_chunk_file(self, tmp_path):
        # "length" is the character count of the chunk file on disk, including the trailing
        # newline the writer appends.
        path = self._write_large(tmp_path)
        chunker.chunk_file(str(path))
        chunks_dir = path.parent / chunker.CHUNKS_DIRNAME
        catalog = json.loads((chunks_dir / chunker.CATALOG_NAME).read_text(encoding="utf-8"))
        assert catalog["chunks"]
        for entry in catalog["chunks"]:
            written = (chunks_dir / entry["file"]).read_text(encoding="utf-8")
            assert entry["length"] == len(written), entry["file"]

    def test_huge_threshold_forces_unchunked(self, tmp_path):
        path = self._write_large(tmp_path)
        summary = chunker.chunk_file(str(path), min_structure_tokens=10_000_000)
        assert summary["chunks"] == 0
        outline = json.loads(
            (path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME).read_text(encoding="utf-8")
        )
        assert outline["chunked"] is False
        assert not (path.parent / chunker.CHUNKS_DIRNAME / chunker.CATALOG_NAME).exists()


class TestOutlineToc:
    """Printed-TOC and PDF-bookmark titles land in ``Chunks/outline.json``'s ``toc`` list."""

    PARAGRAPH = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 60

    def _protocol(self, path):
        # Four entries, not two: the no-table path needs MIN_DENSITY_MATCHES (3) of the lines
        # after the label to look like entries before it accepts the block as a TOC.
        toc = ["## TABLE OF CONTENTS"] + [
            "1.0 Background\t2", "2.0 Objectives\t3", "3.0 Design\t4", "4.0 Analysis\t5", "",
        ]
        body = [f"# Section {i} Heading\n\n{self.PARAGRAPH}\n" for i in range(1, 51)]
        path.write_text("\n".join(toc + body), encoding="utf-8")

    def test_printed_toc_titles_land_in_outline(self, tmp_path):
        path = tmp_path / "proto.md"
        self._protocol(path)
        chunker.chunk_file(str(path))
        outline = json.loads(
            (path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME).read_text(encoding="utf-8")
        )
        assert "1.0 Background" in outline["toc"]
        assert "4.0 Analysis" in outline["toc"]

    def test_pdf_bookmarks_on_the_unchunked_path(self, tmp_path, monkeypatch):
        records = [{"title": "Alpha Section", "level": 1, "page": 1}]
        monkeypatch.setattr(
            "toc_and_appendix_detection.extract_bookmarks", lambda *a, **k: records
        )
        monkeypatch.setattr(
            "toc_and_appendix_detection.verify_bookmarks", lambda *a, **k: records
        )
        path = tmp_path / "small.md"
        path.write_text("# Tiny\n\nshort body\n", encoding="utf-8")
        (tmp_path / "small.pdf").write_bytes(b"%PDF-1.4")
        assert chunker.chunk_file(str(path), min_structure_tokens=10 ** 9)["chunks"] == 0
        outline = json.loads(
            (path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME).read_text(encoding="utf-8")
        )
        assert outline["toc"] == ["Alpha Section"]


class TestRechunk:
    """A second ``chunk_file`` on the same path rebuilds the outline from the current document."""

    PARAGRAPH = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 60

    def _write(self, path, toc_titles):
        toc = ["## TABLE OF CONTENTS"] + [f"{t}\t{i + 2}" for i, t in enumerate(toc_titles)] + [""]
        body = [f"# Section {i} Heading\n\n{self.PARAGRAPH}\n" for i in range(1, 51)]
        path.write_text("\n".join(toc + body), encoding="utf-8")

    def _outline(self, path):
        return json.loads(
            (path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME).read_text(encoding="utf-8")
        )

    def test_rerun_on_new_content_does_not_reuse_the_old_outline(self, tmp_path):
        path = tmp_path / "proto.md"
        self._write(path, ["1.0 Background", "2.0 Objectives", "3.0 Design", "4.0 Analysis"])
        chunker.chunk_file(str(path))
        assert self._outline(path)["toc"] == [
            "1.0 Background", "2.0 Objectives", "3.0 Design", "4.0 Analysis",
        ]

        self._write(path, ["9.0 Completely New", "8.0 Other Topic", "7.0 Third Thing"])
        chunker.chunk_file(str(path))
        outline = self._outline(path)
        assert outline["toc_source"] == "md-toc"
        assert outline["toc"] == ["9.0 Completely New", "8.0 Other Topic", "7.0 Third Thing"]


class TestClearPriorOutputs:
    def test_removes_chunks_dir(self, tmp_path):
        path = tmp_path / "doc.md"
        path.write_text("# Doc\n\nbody\n", encoding="utf-8")
        (tmp_path / chunker.CHUNKS_DIRNAME).mkdir()
        (tmp_path / chunker.CHUNKS_DIRNAME / "Chunk-1.md").write_text("stale", encoding="utf-8")
        chunker.clear_prior_outputs(path)
        assert not (tmp_path / chunker.CHUNKS_DIRNAME).exists()
