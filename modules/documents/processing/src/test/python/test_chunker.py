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
import time

import pytest

import chunker
import heading_helpers
import markdown_markers


class TestValidHeading:
    def test_ordinary_heading(self):
        assert heading_helpers.is_valid_heading("Introduction") is True

    def test_too_short_rejected(self):
        assert heading_helpers.is_valid_heading("Hi") is False

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

    def test_heading_level_for_valid_atx(self):
        assert heading_helpers._get_heading_level("## Introduction") == 2

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

    def test_catalog_entries_omit_heading_and_length(self, tmp_path):
        path = self._write_large(tmp_path)
        chunker.chunk_file(str(path))
        chunks_dir = path.parent / chunker.CHUNKS_DIRNAME
        catalog = json.loads((chunks_dir / chunker.CATALOG_NAME).read_text(encoding="utf-8"))
        assert catalog
        for entry in catalog:
            assert "heading" not in entry
            assert "length" not in entry
            assert "chunk_id" in entry
            assert "pageStart" in entry
            assert "pageEnd" in entry

    def test_huge_threshold_forces_unchunked(self, tmp_path):
        path = self._write_large(tmp_path)
        summary = chunker.chunk_file(str(path), min_structure_tokens=10_000_000)
        assert summary["chunks"] == 0
        outline_path = path.parent / chunker.CHUNKS_DIRNAME / chunker.OUTLINE_NAME
        outline = json.loads(outline_path.read_text(encoding="utf-8"))
        assert outline["chunked"] is False
        assert not (path.parent / chunker.CHUNKS_DIRNAME / chunker.CATALOG_NAME).exists()


class TestTheStoredDocumentDoesNotDependOnItsLength:
    """A caption is demoted so the splitter does not cut on it, and only for that.

    Demoting in the document that gets persisted made the same content read differently
    depending on its size, because only a document past the size gate is ever chunked: a
    "## Table 3" caption kept its hashes in a short protocol and arrived as body text in a
    long one.
    """

    # Eleven words, so is_valid_heading rejects it: a caption, not a section.
    CAPTION = "## Table 3 the baseline characteristics of every enrolled participant by arm"

    def _document(self, sections):
        paragraph = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 60
        body = [f"# Section {number} Heading\n\n{self.CAPTION}\n\n{paragraph}\n"
                for number in range(1, sections + 1)]
        return "\n".join(body)

    def _stored(self, tmp_path, markdown, **options):
        path = tmp_path / "protocol.md"
        chunker.chunk_file(str(path), markdown=markdown, **options)
        return path.read_text(encoding="utf-8")

    def test_a_caption_keeps_its_hashes_when_the_document_is_chunked(self, tmp_path):
        stored = self._stored(tmp_path, self._document(50))
        assert self.CAPTION in stored

    def test_and_when_it_is_too_short_to_be_chunked(self, tmp_path):
        stored = self._stored(tmp_path, self._document(1))
        assert self.CAPTION in stored

    def test_the_caption_is_still_not_a_chunk_boundary(self, tmp_path):
        # The demotion has to keep happening somewhere, or the splitter cuts on the caption.
        tree = chunker.build_chunk_tree(self._document(50), None, 2000, 1)
        assert tree["chunked"] is True
        assert not any(chunk["text"].lstrip().startswith("##") for chunk in tree["chunks"]), \
            "a chunk opened on the caption, so the splitter cut there"


class TestScratchDirectories:
    """Everything a parse writes goes into a directory of its own, and is cleared up.

    The key used to be ``os.getpid()``. Python is PID 1 in this container, so the same two
    names came back after every restart rather than merely sometimes: a daemon killed part way
    through staging left ten chunk files, the container restarted as PID 1, the caller retried,
    the new document yielded five, and the published tree held ten under a catalog naming five.
    """

    def _large(self):
        paragraph = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 60
        return "\n".join(f"# Section {n} Heading\n\n{paragraph}\n" for n in range(1, 51))

    def test_a_leftover_staging_directory_is_not_written_into(self, tmp_path):
        path = tmp_path / "protocol.md"
        stale = tmp_path / f"{chunker.CHUNKS_DIRNAME}{chunker.SCRATCH_NEW}1"
        stale.mkdir()
        (stale / "Chunk-99.md").write_text("from a killed run\n", encoding="utf-8")

        chunker.chunk_file(str(path), markdown=self._large())
        published = tmp_path / chunker.CHUNKS_DIRNAME
        assert not (published / "Chunk-99.md").exists()
        catalog = json.loads((published / chunker.CATALOG_NAME).read_text(encoding="utf-8"))
        assert {entry["chunk_id"] for entry in catalog} == {
            chunk.name for chunk in published.glob("Chunk-*.md")
        }

    def test_two_parses_do_not_share_a_staging_name(self, tmp_path, monkeypatch):
        seen = []
        original = chunker.shared_docs.make_dirs

        def watch(path, **options):
            seen.append(str(path))
            return original(path, **options)

        monkeypatch.setattr(chunker.shared_docs, "make_dirs", watch)
        for name in ("first.md", "second.md"):
            chunker.chunk_file(str(tmp_path / name), markdown=self._large())
        staging = [entry for entry in seen if chunker.SCRATCH_NEW in entry]
        assert len(staging) == 2
        assert staging[0] != staging[1]

    def test_a_staging_name_that_came_back_is_a_loud_failure(self, tmp_path, monkeypatch):
        # The key is a uuid, so this cannot happen -- and if it ever did, the old behaviour was
        # to write into whatever was there and publish the union.
        import uuid as uuid_module

        monkeypatch.setattr(uuid_module, "uuid4",
                            lambda: type("Fixed", (), {"hex": "fixed"})())
        tree = chunker.build_chunk_tree(self._large(), None, 2000, 1)
        chunks_dir = tmp_path / chunker.CHUNKS_DIRNAME
        chunker._stage_chunks(chunks_dir, tree)
        with pytest.raises(FileExistsError):
            chunker._stage_chunks(chunks_dir, tree)

    def test_the_sweep_removes_what_a_killed_run_left(self, tmp_path):
        import os

        document = tmp_path / "study" / "protocol.md"
        document.parent.mkdir()
        old_run = [
            document.parent / f"{chunker.CHUNKS_DIRNAME}{chunker.SCRATCH_NEW}abc",
            document.parent / f"{chunker.CHUNKS_DIRNAME}{chunker.SCRATCH_OLD}abc",
        ]
        for scratch in old_run:
            scratch.mkdir()
            (scratch / "Chunk-1.md").write_text("orphan\n", encoding="utf-8")
            stale = time.time() - chunker.SCRATCH_GRACE_SECONDS - 60
            os.utime(scratch, (stale, stale))

        assert chunker.sweep_scratch_directories(tmp_path) == 2
        assert not any(scratch.exists() for scratch in old_run)

    def test_the_sweep_leaves_a_published_tree_alone(self, tmp_path):
        published = tmp_path / chunker.CHUNKS_DIRNAME
        published.mkdir()
        (published / chunker.OUTLINE_NAME).write_text("{}", encoding="utf-8")
        assert chunker.sweep_scratch_directories(tmp_path) == 0
        assert (published / chunker.OUTLINE_NAME).is_file()

    def test_the_sweep_leaves_a_recent_one_alone(self, tmp_path):
        # It cannot tell this daemon's staging directory from another's, and two sharing a
        # volume is possible. At startup this one has no parse running, so anything recent
        # belongs to somebody else.
        fresh = tmp_path / f"{chunker.CHUNKS_DIRNAME}{chunker.SCRATCH_NEW}xyz"
        fresh.mkdir()
        assert chunker.sweep_scratch_directories(tmp_path) == 0
        assert fresh.is_dir()


class TestPageBoundsAreFilledFromBothSides:
    """An unmarked chunk takes the page its marked neighbour is on.

    Walking forward alone reads the next entry's ``pageStart`` before that entry has been
    filled, so a run of unmarked chunks at the head of the document kept its nulls while
    everything after it had pages -- a citation UI could find the whole document except its
    opening.
    """

    def _filled(self, pages):
        catalog = [{"chunk_id": f"Chunk-{n}.md", "pageStart": start, "pageEnd": end}
                   for n, (start, end) in enumerate(pages)]
        chunker._fill_missing_page_bounds(catalog)
        return [(entry["pageStart"], entry["pageEnd"]) for entry in catalog]

    def test_a_leading_run_takes_the_first_page_it_can_find(self):
        assert self._filled([(None, None), (None, None), (4, 5)]) == [(4, 4), (4, 4), (4, 5)]

    def test_a_trailing_run_takes_the_last_page_before_it(self):
        assert self._filled([(1, 2), (None, None), (None, None)]) == [(1, 2), (2, 2), (2, 2)]

    def test_a_gap_in_the_middle_prefers_the_page_before_it(self):
        assert self._filled([(1, 2), (None, None), (7, 8)]) == [(1, 2), (2, 2), (7, 8)]

    def test_a_document_with_no_pages_at_all_is_left_alone(self):
        assert self._filled([(None, None), (None, None)]) == [(None, None), (None, None)]


class TestTheSiblingPdfIsFoundWhateverItsCase:
    """``PROTOCOL.PDF`` is routine from Windows, and the lookup rebuilt the name in lowercase.

    On a case-sensitive filesystem that missed: bookmarks came back empty, heading levels were
    never corrected, and the document was cut at the wrong boundaries with ``ok: true``.
    """

    def test_an_uppercase_suffix_is_matched(self, tmp_path):
        (tmp_path / "PROTOCOL.PDF").write_bytes(b"%PDF")
        assert chunker._find_sibling_pdf(tmp_path / "PROTOCOL.md") == tmp_path / "PROTOCOL.PDF"

    def test_the_exact_name_still_wins(self, tmp_path):
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        assert chunker._find_sibling_pdf(tmp_path / "protocol.md") == tmp_path / "protocol.pdf"

    def test_another_document_is_not_mistaken_for_it(self, tmp_path):
        (tmp_path / "appendix.pdf").write_bytes(b"%PDF")
        assert chunker._find_sibling_pdf(tmp_path / "protocol.md") is None

    def test_a_missing_sibling_is_reported(self, tmp_path):
        tree = chunker.build_chunk_tree("# Doc\n\nbody\n", tmp_path / "protocol.md", 2000,
                                        10_000_000)
        assert any("No PDF beside" in note for note in tree["notes"])

    def test_a_sibling_with_no_bookmarks_is_reported(self, tmp_path, monkeypatch):
        # extract_bookmarks answers an unreadable PDF and an unbookmarked one the same way,
        # so from outside the two were indistinguishable.
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        monkeypatch.setattr(chunker, "extract_bookmarks", lambda source: [])
        tree = chunker.build_chunk_tree("# Doc\n\nbody\n", tmp_path / "protocol.md", 2000,
                                        10_000_000)
        assert any("no usable bookmarks" in note for note in tree["notes"])

    def test_the_notes_reach_the_summary(self, tmp_path):
        summary = chunker.chunk_file(str(tmp_path / "protocol.md"), markdown="# Doc\n\nbody\n")
        assert "No PDF beside" in summary["logs"]
        assert set(summary) == {"chunks", "chunked", "chunks_dir", "logs"}


class TestOutlineBookmarks:
    """``Chunks/outline.json`` ``bookmarks`` come from a sibling PDF and from nothing else.

    Markdown headings are not harvested into them. A document with no sibling PDF reports an
    empty list, and the caller decides what to send in its place.
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
        assert outline["bookmarks"] == ["Alpha Section"]

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
        assert outline["bookmarks"] == ["Aims", "Specific"]


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
        chunker.chunk_file(str(path), chunk=False)
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
        chunker.chunk_file(str(path), chunk=False)
        assert set(self._outline(tmp_path)) == gate_keys

    def test_a_reparse_without_chunking_takes_the_old_chunks_with_it(self, tmp_path):
        # This path used to create the directory and overwrite outline.json, leaving everything
        # else. catalog.json is documented as the marker that the set beside it is complete, so
        # a consumer reading it got the previous revision's chunks under an outline saying the
        # document was never chunked.
        path = tmp_path / "protocol.md"
        paragraph = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 60
        sections = "\n".join(f"# Section {n} Heading\n\n{paragraph}\n" for n in range(1, 51))
        chunker.chunk_file(str(path), markdown=sections)
        chunks_dir = tmp_path / chunker.CHUNKS_DIRNAME
        assert (chunks_dir / chunker.CATALOG_NAME).is_file()
        assert list(chunks_dir.glob("Chunk-*.md"))

        chunker.chunk_file(str(path), markdown="# Short\n\nbody\n", chunk=False)
        assert not (chunks_dir / chunker.CATALOG_NAME).exists()
        assert list(chunks_dir.glob("Chunk-*.md")) == []
        assert self._outline(tmp_path)["unchunkedReason"] == chunker.UNCHUNKED_NOT_REQUESTED

    def test_nothing_is_detected_when_chunking_was_not_asked_for(self, monkeypatch, tmp_path):
        # Skipping detection is the point of ?chunk=false: no sibling PDF is opened for it.
        path = tmp_path / "protocol.md"
        path.write_text("# Doc\n\nbody\n", encoding="utf-8")
        (tmp_path / "protocol.pdf").write_bytes(b"%PDF")
        opened = []
        monkeypatch.setattr(chunker, "extract_bookmarks",
                            lambda source: opened.append(source) or [])
        chunker.chunk_file(str(path), chunk=False)
        assert opened == []
        assert self._outline(tmp_path)["bookmarks"] == []

    def test_write_atomically_leaves_no_scratch_behind(self, tmp_path):
        path = tmp_path / "out.md"
        chunker.write_atomically(path, "content\n")
        assert path.read_text(encoding="utf-8") == "content\n"
        assert [p.name for p in tmp_path.iterdir()] == ["out.md"]
