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

"""Attacks on the shared-docs allowlist, the boundary guarding ``POST /parse?path=``.

CodeQL's ``py/path-injection`` query models ``os.path.realpath`` (normalization) then
``str.startswith`` (SafeAccessCheck on the true branch). It does not model
``os.path.commonpath`` or ``Path.relative_to``. ``resolve_parse_path`` uses all three:
``startswith`` so the query sees a check, ``commonpath`` so a sibling whose name merely
starts with the root is still refused. Derived writes (``{stem}.md``, ``Chunks/``) are
new path expressions and stay tainted; they go through the I/O helpers that repeat
``realpath`` + ``startswith`` in the same function as the syscall.

Deliberately not in ``test_docling_runtime.py``: that module skips itself wherever
Docling is absent, which includes CI, and a security check that does not run in CI is
not a check. :mod:`shared_docs` has no Docling dependency so this runs everywhere.
"""

import os

import pytest

import shared_docs


@pytest.fixture
def root(monkeypatch, tmp_path):
    shared = (tmp_path / "shared").resolve()
    shared.mkdir()
    monkeypatch.setenv("IAP_SHARED_DOCS", str(shared))
    return shared


@pytest.fixture
def outside(tmp_path):
    other = (tmp_path / "outside").resolve()
    other.mkdir()
    (other / "secret.pdf").write_bytes(b"%PDF")
    return other


class TestEscapesAreRefused:
    def test_traversal_out_of_the_root(self, root, outside):
        with pytest.raises(ValueError, match="must be under"):
            shared_docs.resolve_parse_path(str(root / ".." / "outside" / "secret.pdf"))

    def test_deep_traversal(self, root):
        with pytest.raises(ValueError, match="must be under"):
            shared_docs.resolve_parse_path(str(root) + "/../" * 12 + "etc/passwd")

    def test_absolute_path_outside(self, root, outside):
        with pytest.raises(ValueError, match="must be under"):
            shared_docs.resolve_parse_path(str(outside / "secret.pdf"))

    def test_percent_encoded_dots_are_not_decoded(self, root, outside):
        # parse_qs already decoded the value, so these are literal characters, not "..".
        for encoded in ("%2e%2e", "%252e%252e"):
            with pytest.raises(ValueError):
                shared_docs.resolve_parse_path(str(root / encoded / "outside" / "secret.pdf"))

    def test_null_byte(self, root):
        (root / "good.pdf").write_bytes(b"%PDF")
        with pytest.raises(ValueError):
            shared_docs.resolve_parse_path(str(root / "good.pdf") + "\x00.txt")

    def test_a_sibling_whose_name_starts_with_the_root(self, root, tmp_path):
        # "/shared-evil" must not pass as being under "/shared". startswith would
        # accept it; commonpath is the check that does not.
        evil = tmp_path / "shared-evil"
        evil.mkdir()
        secret = evil / "secret.pdf"
        secret.write_bytes(b"%PDF")
        root_s = os.path.realpath(root)
        evil_s = os.path.realpath(secret)
        assert os.path.commonpath([root_s, evil_s]) != root_s
        assert evil_s.startswith(root_s)
        with pytest.raises(ValueError, match="must be under"):
            shared_docs.resolve_parse_path(str(secret))

    def test_commonpath_on_different_volumes_is_a_refusal(self, root, monkeypatch):
        (root / "good.pdf").write_bytes(b"%PDF")

        def boom(_paths):
            raise ValueError("Can't mix path types")

        monkeypatch.setattr(os.path, "commonpath", boom)
        with pytest.raises(shared_docs.ParseRequestError, match="must be under"):
            shared_docs.resolve_parse_path(str(root / "good.pdf"))

    def test_a_symlink_pointing_out_of_the_root(self, root, outside):
        try:
            (root / "link.pdf").symlink_to(outside / "secret.pdf")
        except OSError:
            pytest.skip("symlinks not creatable in this environment")
        with pytest.raises(ValueError, match="must be under"):
            shared_docs.resolve_parse_path(str(root / "link.pdf"))

    def test_a_relative_argument_that_looks_like_an_option(self, root):
        # An argument starting with "-" would be read as an option by LibreOffice
        # (py/command-line-injection, alert 10). Only absolute paths under the root get
        # through, so the value handed to soffice can never start with a dash.
        with pytest.raises(ValueError):
            shared_docs.resolve_parse_path("-oEvil.pdf")

    def test_a_directory_is_not_a_document(self, root):
        (root / "sub").mkdir()
        with pytest.raises(ValueError, match="does not exist"):
            shared_docs.resolve_parse_path(str(root / "sub"))

    def test_an_unsupported_suffix(self, root):
        (root / "notes.txt").write_text("hi", encoding="utf-8")
        with pytest.raises(ValueError, match="must end in"):
            shared_docs.resolve_parse_path(str(root / "notes.txt"))

    def test_an_empty_path(self, root):
        with pytest.raises(ValueError, match="required"):
            shared_docs.resolve_parse_path("   ")


class TestLegitimatePathsStillWork:
    def test_a_file_at_the_root(self, root):
        (root / "proto.pdf").write_bytes(b"%PDF")
        assert shared_docs.resolve_parse_path(str(root / "proto.pdf")) == root / "proto.pdf"

    def test_a_nested_file(self, root):
        nested = root / "a" / "b"
        nested.mkdir(parents=True)
        (nested / "proto.pdf").write_bytes(b"%PDF")
        assert shared_docs.resolve_parse_path(str(nested / "proto.pdf")) == nested / "proto.pdf"

    def test_every_accepted_suffix(self, root):
        for name in ("a.pdf", "b.docx", "c.doc"):
            (root / name).write_bytes(b"x")
            assert shared_docs.resolve_parse_path(str(root / name)).name == name

    def test_a_percent_sign_in_the_filename_is_taken_literally(self, root):
        literal = root / "report%20final.pdf"
        literal.write_bytes(b"%PDF")
        assert shared_docs.resolve_parse_path(str(literal)) == literal

    def test_an_accepted_path_satisfies_the_commonpath_sanitizer(self, root):
        (root / "proto.pdf").write_bytes(b"%PDF")
        accepted = shared_docs.resolve_parse_path(str(root / "proto.pdf"))
        root_s = os.path.realpath(root)
        resolved = os.path.realpath(accepted)
        assert resolved.startswith(root_s)
        assert os.path.commonpath([root_s, resolved]) == root_s


class TestIoHelpersWorkOutsideTheJail:
    def test_write_text_and_replace_on_tmp_path(self, tmp_path):
        # The CLI and pytest write beside files that are not under IAP_SHARED_DOCS.
        dest = tmp_path / "out.md"
        scratch = tmp_path / "out.md.tmp"
        shared_docs.write_text(scratch, "hello")
        shared_docs.replace_file(scratch, dest)
        assert dest.read_text(encoding="utf-8") == "hello"
        assert not shared_docs.path_exists(scratch)

    def test_make_dirs_exists_and_remove_tree(self, tmp_path):
        nested = tmp_path / "Chunks" / "inner"
        shared_docs.make_dirs(nested)
        shared_docs.write_text(nested / "Chunk-1.md", "body\n")
        assert shared_docs.path_is_file(nested / "Chunk-1.md")
        shared_docs.remove_tree(tmp_path / "Chunks")
        assert not shared_docs.path_exists(tmp_path / "Chunks")

    def test_remove_file_missing_is_ok(self, tmp_path):
        shared_docs.remove_file(tmp_path / "absent.txt")


class TestDerivedOutputsStayContained:
    def test_markdown_and_chunks_land_under_the_root(self, root):
        # The daemon writes {stem}.md and Chunks/ beside the source, so containing the
        # source is what contains the writes.
        (root / "sub").mkdir()
        (root / "sub" / "proto.pdf").write_bytes(b"%PDF")
        source = shared_docs.resolve_parse_path(str(root / "sub" / "proto.pdf"))
        assert source.with_suffix(".md").is_relative_to(root)
        assert (source.parent / "Chunks").is_relative_to(root)


class TestRequestErrorType:
    def test_refusals_are_parse_request_errors(self, root):
        with pytest.raises(shared_docs.ParseRequestError):
            shared_docs.resolve_parse_path(str(root / "missing.pdf"))

    def test_it_is_still_a_value_error(self):
        assert issubclass(shared_docs.ParseRequestError, ValueError)
