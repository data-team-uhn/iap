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

"""Tests for markdown_cleanup: garbage-line stripping, blank collapsing, the
cleaned marker, leading line-number removal, and source-file header helpers."""

from pathlib import Path

import markdown_cleanup as mc


class TestCleanMarkdown:
    def test_empty_input_returns_empty(self):
        assert mc.clean_markdown("") == ""
        assert mc.clean_markdown(None) == ""

    def test_content_is_kept(self):
        result = mc.clean_markdown("# Title\n\nBody text.")
        assert result == "# Title\n\nBody text."

    def test_running_it_again_changes_nothing(self):
        once = mc.clean_markdown("# Title\n\n\n\n***\n\n<!-- image -->\n\nBody text.")
        assert mc.clean_markdown(once) == once

    def test_symbol_only_garbage_lines_removed(self):
        # Lines with no alphanumerics and no '|' or '-' are decorative garbage.
        result = mc.clean_markdown("Keep me\n\n***\n\n===\n\nKeep me too")
        assert "***" not in result
        assert "===" not in result
        assert "Keep me" in result
        assert "Keep me too" in result

    def test_rule_line_with_dash_is_kept(self):
        # A '---' line contains '-', so it is not treated as garbage.
        result = mc.clean_markdown("Above\n\n---\n\nBelow")
        assert "---" in result

    def test_image_placeholder_removed(self):
        result = mc.clean_markdown("Before\n\n<!-- image -->\n\nAfter")
        assert "<!-- image -->" not in result
        assert "Before" in result
        assert "After" in result

    def test_multiple_blank_lines_collapsed(self):
        assert mc.clean_markdown("A\n\n\n\n\nB") == "A\n\nB"


class TestLeadingLineNumbers:
    def test_long_consecutive_run_stripped(self):
        numbers = "\n".join(str(n) for n in range(1, 31))
        page = numbers + "\nReal content starts here.\n"
        cleaned = mc.cleanup_page_leading_line_numbers(page)
        assert cleaned.startswith("Real content starts here.")
        assert "\n1\n" not in ("\n" + cleaned)

    def test_short_run_kept(self):
        # Fewer than MIN_RUN_LENGTH numbers: not a line-number block, left untouched.
        numbers = "\n".join(str(n) for n in range(1, 6))
        page = numbers + "\nContent"
        assert mc.cleanup_page_leading_line_numbers(page) == page

    def test_non_consecutive_run_kept(self):
        values = [1] + list(range(3, 32))  # 30 values but a gap after the first
        page = "\n".join(str(n) for n in values) + "\nContent"
        assert mc.cleanup_page_leading_line_numbers(page) == page

    def test_empty_input(self):
        assert mc.cleanup_page_leading_line_numbers("") == ""
        assert mc.cleanup_leading_line_numbers("") == ""

    def test_paged_document_cleans_each_page(self):
        numbers = "\n".join(str(n) for n in range(1, 31))
        md = (
            "Intro\n"
            "<!-- page: 1 -->\n"
            + numbers
            + "\nPage one body.\n"
        )
        cleaned = mc.cleanup_leading_line_numbers(md)
        assert "Page one body." in cleaned
        assert "\n1\n2\n3\n" not in cleaned


class TestHelpers:
    def test_is_consecutive(self):
        assert mc._is_consecutive([1, 2, 3, 4]) is True
        assert mc._is_consecutive([1]) is True
        assert mc._is_consecutive([]) is True
        assert mc._is_consecutive([1, 3]) is False

    def test_leading_line_number_run(self):
        lines = ["1", "", "2", "", "3", "Body"]
        run, end_index = mc._leading_line_number_run(lines)
        assert run == [1, 2, 3]
        assert lines[end_index] == "Body"


class TestSourceFileHeader:
    def test_resolve_prefers_explicit_name_basename(self):
        assert mc.resolve_source_file_name(Path("/tmp/on-disk.pdf"), "Original Upload.pdf") \
            == "Original Upload.pdf"

    def test_resolve_strips_directories_from_explicit_name(self):
        assert mc.resolve_source_file_name(Path("x.pdf"), "/some/dir/report.docx") == "report.docx"

    def test_resolve_falls_back_to_path_name(self):
        assert mc.resolve_source_file_name(Path("/tmp/on-disk.pdf"), None) == "on-disk.pdf"
        assert mc.resolve_source_file_name(Path("/tmp/on-disk.pdf"), "   ") == "on-disk.pdf"

    def test_header_escapes_double_dash(self):
        # '--' cannot appear inside an HTML comment; it becomes an em dash.
        header = mc.source_file_header("weird--name.pdf")
        assert "--" not in header.replace("<!--", "").replace("-->", "")
        assert header.startswith("<!-- source_file: ")
        assert header.endswith(" -->")
