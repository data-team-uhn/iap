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
import heading_helpers
import markdown_markers


class TestValidHeading:
    def test_ordinary_heading(self):
        assert heading_helpers.is_valid_heading("Introduction") is True

    def test_too_short_rejected(self):
        assert heading_helpers.is_valid_heading("Hi") is False

    def test_table_caption_rejected(self):
        assert heading_helpers.is_valid_heading("Table 1: Baseline characteristics") is False

    def test_too_many_words_rejected(self):
        line = "one two three four five six seven eight nine ten eleven"
        assert heading_helpers.is_valid_heading(line) is False

    def test_overlong_word_rejected(self):
        assert heading_helpers.is_valid_heading("word " + "x" * 101) is False

    def test_at_the_word_limit_is_allowed(self):
        line = " ".join(["word"] * markdown_markers.MAX_HEADING_WORDS)
        assert heading_helpers.is_valid_heading(line) is True

    def test_blank_rejected(self):
        assert heading_helpers.is_valid_heading("") is False
        assert heading_helpers.is_valid_heading("   ") is False

    def test_a_numbered_short_heading_is_kept(self):
        # Substance is measured over letters and digits, not the matching key alone.
        for title in ("3.1 Aims", "5.2 Data", "2.0 Bias", "1.4 Team"):
            assert heading_helpers.is_valid_heading(title) is True, title

    def test_digits_only_is_rejected(self):
        assert heading_helpers.is_valid_heading("4.2") is False

    def test_confidential_is_rejected(self):
        assert heading_helpers.is_valid_heading("Confidential") is False

    def test_an_unnumbered_short_title_is_still_rejected(self):
        assert heading_helpers.is_valid_heading("Aims") is False

    def test_a_real_heading_is_kept(self):
        assert heading_helpers.is_valid_heading("3.1 Study Aims") is True


class TestHeadingMatching:
    def test_match_atx_heading_level_and_text(self):
        assert heading_helpers._match_atx_heading("## Foo Bar") == (2, "Foo Bar")

    def test_match_atx_heading_deepest_level(self):
        assert heading_helpers._match_atx_heading("###### Deep Heading") == (6, "Deep Heading")

    def test_match_atx_heading_seven_hashes_is_not_a_heading(self):
        assert heading_helpers._match_atx_heading("####### Seven") is None

    def test_match_atx_heading_plain_line(self):
        assert heading_helpers._match_atx_heading("plain text line") is None

    def test_heading_level_filters_invalid_headings(self):
        assert heading_helpers._get_heading_level("## Introduction") == 2
        # A "Table ..." caption is a heading syntactically but not a chunk boundary.
        assert heading_helpers._get_heading_level("## Table 1: Overview") is None

    def test_min_atx_level(self):
        lines = "# Alpha\n## Bravo\n### Gamma".split("\n")
        assert heading_helpers._get_min_atx_level(lines) == 1
        assert heading_helpers._get_min_atx_level(lines, deeper_than=1) == 2
        assert heading_helpers._get_min_atx_level(["no headings here"]) is None


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
        assert outline["bookmarks"] == []
        assert isinstance(outline["tokens"], int) and outline["tokens"] > 0
        # No catalog for an unchunked document.
        assert not (path.parent / chunker.CHUNKS_DIRNAME / chunker.CATALOG_NAME).exists()

    def test_large_document_chunked(self, tmp_path):
        path = self._write_large(tmp_path)
        summary = chunker.chunk_file(str(path))
        assert summary["chunks"] > 0

        chunks_dir = path.parent / chunker.CHUNKS_DIRNAME
        outline = json.loads((chunks_dir / chunker.OUTLINE_NAME).read_text(encoding="utf-8"))
        assert outline["chunked"] is True
        assert (chunks_dir / chunker.CATALOG_NAME).is_file()
        catalog = json.loads((chunks_dir / chunker.CATALOG_NAME).read_text(encoding="utf-8"))
        assert len(catalog) == summary["chunks"]

    def test_catalog_length_matches_the_chunk_file(self, tmp_path):
        # "length" is the character count of the chunk file on disk, including the trailing
        # newline the writer appends.
        path = self._write_large(tmp_path)
        chunker.chunk_file(str(path))
        chunks_dir = path.parent / chunker.CHUNKS_DIRNAME
        catalog = json.loads((chunks_dir / chunker.CATALOG_NAME).read_text(encoding="utf-8"))
        assert catalog
        # The catalog no longer names its file, so the lengths are compared as a multiset:
        # every entry still has to match some chunk file, and the counts still have to agree.
        written = sorted(
            len(f.read_text(encoding="utf-8")) for f in chunks_dir.glob("Chunk-*.md")
        )
        assert sorted(entry["length"] for entry in catalog) == written

    def test_huge_threshold_forces_unchunked(self, tmp_path):
        path = self._write_large(tmp_path)
        summary = chunker.chunk_file(str(path), min_structure_tokens=10_000_000)
        assert summary["chunks"] == 0
        outline_path = path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME
        outline = json.loads(outline_path.read_text(encoding="utf-8"))
        assert outline["chunked"] is False
        assert not (path.parent / chunker.CHUNKS_DIRNAME / chunker.CATALOG_NAME).exists()


class TestOutlineBookmarks:
    """``Chunks/outline.json`` ``bookmarks`` come from a sibling PDF and from nothing else.

    Markdown headings are not harvested into them. A document with no sibling PDF reports an
    empty list, and the caller decides what to send in its place; the document's own headings
    are still in ``catalog.json`` per chunk.
    """

    PARAGRAPH = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 60

    def _proposal_with_a_printed_toc(self, path):
        toc = ["## TABLE OF CONTENTS", "1.0 Background", "2.0 Objectives", ""]
        body = [f"# Section {i} Heading{chr(10)}{chr(10)}{self.PARAGRAPH}{chr(10)}"
                for i in range(1, 51)]
        path.write_text(chr(10).join(toc + body), encoding="utf-8")

    def test_no_sibling_pdf_means_no_bookmarks(self, tmp_path):
        path = tmp_path / "proto.md"
        self._proposal_with_a_printed_toc(path)
        chunker.chunk_file(str(path))
        outline_path = path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME
        outline = json.loads(outline_path.read_text(encoding="utf-8"))
        assert outline["bookmarks"] == []
        assert "bookmark_source" not in outline

    def test_a_printed_toc_is_not_a_chunk_heading(self, tmp_path):
        # The reason the outline no longer harvests Markdown: a printed TOC line looks exactly
        # like a heading. It still must not end up labelling a chunk.
        path = tmp_path / "proto.md"
        self._proposal_with_a_printed_toc(path)
        chunker.chunk_file(str(path))
        catalog_path = path.parent / chunker.CHUNKS_DIRNAME / chunker.CATALOG_NAME
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        headings = [entry["heading"] for entry in catalog]
        assert "TABLE OF CONTENTS" not in headings
        assert any(h.startswith("Section ") and h.endswith(" Heading") for h in headings)

    def test_pdf_bookmarks_on_the_unchunked_path(self, tmp_path, monkeypatch):
        pdf_bookmarks = [{"title": "Alpha Section", "level": 1, "page": 1}]
        monkeypatch.setattr(
            "chunker.extract_bookmarks", lambda *a, **k: pdf_bookmarks
        )
        path = tmp_path / "small.md"
        path.write_text("# Tiny\n\nshort body\n", encoding="utf-8")
        (tmp_path / "small.pdf").write_bytes(b"%PDF-1.4")
        assert chunker.chunk_file(str(path), min_structure_tokens=10 ** 9)["chunks"] == 0
        outline_path = path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME
        outline = json.loads(outline_path.read_text(encoding="utf-8"))
        assert outline["bookmarks"] == [
            {"title": "Alpha Section", "level": 1, "page": 1}
        ]

    def test_bookmarks_keep_headings_up_to_the_level_ceiling(self, tmp_path, monkeypatch):
        pdf_bookmarks = [
            {"title": "Aims", "level": 1, "page": 1},
            {"title": "Specific", "level": chunker.MAX_HEADING_LEVEL, "page": 2},
            {"title": "Too deep", "level": chunker.MAX_HEADING_LEVEL + 1, "page": 3},
        ]
        monkeypatch.setattr(
            "chunker.extract_bookmarks", lambda *a, **k: pdf_bookmarks
        )
        path = tmp_path / "small.md"
        path.write_text("# Tiny\n\nshort body\n", encoding="utf-8")
        (tmp_path / "small.pdf").write_bytes(b"%PDF-1.4")
        chunker.chunk_file(str(path), min_structure_tokens=10 ** 9)
        outline_path = path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME
        outline = json.loads(outline_path.read_text(encoding="utf-8"))
        assert outline["bookmarks"] == [
            {"title": "Aims", "level": 1, "page": 1},
            {"title": "Specific", "level": chunker.MAX_HEADING_LEVEL, "page": 2},
        ]


class TestUnchunkedOutline:
    """``?chunk=false`` leaves the same shape on disk as the size gate does.

    Regression: that path used to leave no outline at all while the size gate wrote
    ``Chunks/outline.json`` with ``chunked: false``, so a reader had two shapes to handle and
    no way to tell "not asked for" from "too small to bother".
    """

    def _md(self, tmp_path):
        path = tmp_path / "doc.md"
        path.write_text("# Doc\n\nbody\n", encoding="utf-8")
        return path

    def _outline(self, tmp_path):
        return json.loads(
            (tmp_path / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME).read_text(encoding="utf-8")
        )

    def test_it_writes_an_outline_recording_the_reason(self, tmp_path):
        path = self._md(tmp_path)
        chunker.write_unchunked_outline(path, path.read_text(encoding="utf-8"))
        outline = self._outline(tmp_path)
        assert outline["chunked"] is False
        assert outline["unchunkedReason"] == chunker.UNCHUNKED_NOT_REQUESTED

    def test_the_keys_match_the_size_gate_path(self, tmp_path):
        # One shape for downstream, whichever way the document ended up unchunked.
        gated = tmp_path / "gated"
        gated.mkdir()
        small = gated / "small.md"
        small.write_text("# Tiny\n\nShort body.\n", encoding="utf-8")
        chunker.chunk_file(str(small))
        gate_keys = set(self._outline(gated))

        path = self._md(tmp_path)
        chunker.write_unchunked_outline(path, path.read_text(encoding="utf-8"))
        assert set(self._outline(tmp_path)) == gate_keys

    def test_write_atomically_leaves_no_scratch_behind(self, tmp_path):
        path = tmp_path / "out.md"
        chunker.write_atomically(path, "content\n")
        assert path.read_text(encoding="utf-8") == "content\n"
        assert [p.name for p in tmp_path.iterdir()] == ["out.md"]
