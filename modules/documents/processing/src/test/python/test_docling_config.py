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

"""Every name docling_config sets has to still exist in the installed Docling.

``PdfPipelineOptions`` is a pydantic model that **accepts and drops unknown keyword
arguments** -- ``PdfPipelineOptions(layuot_batch_size=1)`` constructs happily and the option
simply is not there. So a rename upstream turns any of these settings into a silent no-op, and
the ones that keep memory bounded (``layout_batch_size``, ``table_batch_size``,
``settings.perf.*``) are exactly the ones whose loss would show up as an OOM-killed worker
rather than an error.

Nothing else catches that: CI installs no Docling on purpose, CI never builds the image, and
the requirements pin is bumped by hand. These assertions are the check that makes
requirements.txt's "bump deliberately, then rebuild to confirm" workflow mean something.

Needs the Docling *package* but none of its weights, so it is fast wherever Docling is present.
"""

import importlib.util

import pytest

pytest.importorskip("docling", reason="docling not installed; option names unverifiable")

import docling_config  # noqa: E402
from docling.datamodel.pipeline_options import (  # noqa: E402
    HeadingHierarchyOptions,
    PdfPipelineOptions,
    TableStructureOptions,
)
from docling.datamodel.settings import settings  # noqa: E402


class TestPipelineOptionNames:
    """Each option we set is a real field, so none of them is silently dropped."""

    # Exactly the keyword arguments docling_config passes; kept as a literal so adding one
    # there without adding it here is visible in review.
    EXPECTED = (
        "do_ocr",
        "do_table_structure",
        "do_code_enrichment",
        "do_formula_enrichment",
        "do_picture_classification",
        "do_picture_description",
        "do_chart_extraction",
        "generate_page_images",
        "generate_picture_images",
        "generate_table_images",
        "generate_parsed_pages",
        "force_backend_text",
        "accelerator_options",
        "layout_batch_size",
        "table_batch_size",
        "batch_polling_interval_seconds",
        "table_structure_options",
        "document_timeout",
        "heading_hierarchy_options",
    )

    def test_the_model_silently_drops_unknown_options(self):
        # The premise. If this ever fails, Docling started validating and these tests are
        # belt to its braces rather than the only check.
        options = PdfPipelineOptions(layuot_batch_size=1)
        assert not hasattr(options, "layuot_batch_size")

    @pytest.mark.parametrize("name", EXPECTED)
    def test_each_option_is_a_real_field(self, name):
        assert name in PdfPipelineOptions.model_fields, (
            f"{name} is no longer a PdfPipelineOptions field; the setting is being dropped"
        )

    def test_every_option_we_set_is_covered_here(self):
        # Guards the list above against drifting from docling_config.
        set_fields = set(docling_config.PDF_PIPELINE_OPTIONS.model_fields_set)
        assert set_fields == set(self.EXPECTED), (
            f"docling_config sets {sorted(set_fields ^ set(self.EXPECTED))} "
            "which this test does not know about"
        )

    def test_the_memory_bounding_options_took_effect(self):
        # Not just present as names — actually carrying the values that keep peak RAM down.
        options = docling_config.PDF_PIPELINE_OPTIONS
        assert options.layout_batch_size == 1
        assert options.table_batch_size == 1
        assert options.accelerator_options.num_threads == 1

    def test_the_table_structure_sub_options_are_real_fields(self):
        for name in ("mode", "do_cell_matching"):
            assert name in TableStructureOptions.model_fields, name

    def test_the_heading_hierarchy_sub_options_are_real_fields(self):
        for name in ("enabled", "use_bookmarks", "use_numbering", "use_style"):
            assert name in HeadingHierarchyOptions.model_fields, name

    def test_heading_hierarchy_is_on_without_the_style_pass(self):
        # The layout model gives every PDF heading the same level, which leaves the chunker
        # nothing to cut on below the top. Bookmarks and numbering fix that for free; the style
        # pass would need generate_parsed_pages=True and every parsed page held in memory.
        options = docling_config.PDF_PIPELINE_OPTIONS.heading_hierarchy_options
        assert options.enabled is True
        assert options.use_bookmarks is True
        assert options.use_numbering is True
        assert options.use_style is False
        assert docling_config.PDF_PIPELINE_OPTIONS.generate_parsed_pages is False

    def test_the_heading_level_ceiling_matches_the_markdown_one(self):
        # Anything deeper than 6 would come back as markdown our HEADING regex rejects.
        from markdown_markers import HEADING

        assert HEADING.match("#" * 6 + " Deep") is not None
        assert HEADING.match("#" * 7 + " Deeper") is None
        assert docling_config.PDF_PIPELINE_OPTIONS.heading_hierarchy_options.max_level == 6

    def test_a_conversion_is_time_bounded(self):
        # Unbounded, one runaway document holds the daemon's only parse slot for as long as it
        # likes while /health still reports ready.
        timeout = docling_config.PDF_PIPELINE_OPTIONS.document_timeout
        assert timeout is not None and timeout > 0

    def test_the_timeout_reads_its_environment_variable(self, monkeypatch):
        import importlib

        monkeypatch.setenv(docling_config.DOCUMENT_TIMEOUT_VARIABLE, "45.5")
        reloaded = importlib.reload(docling_config)
        try:
            assert reloaded.PDF_PIPELINE_OPTIONS.document_timeout == 45.5
            # 0 means "no ceiling", which is Docling's own default.
            monkeypatch.setenv(reloaded.DOCUMENT_TIMEOUT_VARIABLE, "0")
            assert importlib.reload(reloaded).PDF_PIPELINE_OPTIONS.document_timeout is None
        finally:
            monkeypatch.delenv(docling_config.DOCUMENT_TIMEOUT_VARIABLE, raising=False)
            importlib.reload(docling_config)


class TestPerfSettingNames:
    """``settings.perf.*`` is assigned by attribute, so a rename would create a new attribute
    that nothing reads instead of failing."""

    @pytest.mark.parametrize("name", [
        "doc_batch_concurrency",
        "page_batch_concurrency",
        "page_batch_size",
        "elements_batch_size",
    ])
    def test_each_perf_setting_exists(self, name):
        assert name in type(settings.perf).model_fields, (
            f"settings.perf.{name} no longer exists; the assignment is a no-op"
        )

    def test_the_values_we_set_are_in_place(self):
        assert settings.perf.doc_batch_concurrency == 1
        assert settings.perf.page_batch_concurrency == 1
        assert settings.perf.page_batch_size == 1
        assert settings.perf.elements_batch_size == 16


class TestPipelineLoggerName:
    """The logger whose ERROR lines catch Docling returning SUCCESS with empty pages.

    ``docling_error_detection`` watches this logger for "bad_alloc" / "preprocess failed". A
    rename would disable that detection silently — the parse would report success and hand back
    an empty document.
    """

    def test_the_pipeline_module_still_exists(self):
        from docling_error_detection import DOCLING_PIPELINE_LOGGER

        assert importlib.util.find_spec(DOCLING_PIPELINE_LOGGER) is not None, (
            f"{DOCLING_PIPELINE_LOGGER} is gone; bad_alloc detection is watching nothing"
        )


class TestDocumentTimeoutBecomesAFailure:
    """A Docling timeout has to fail the batch, not hand back a shortened document.

    ``document_timeout`` does not raise. Docling stops between page batches, appends a
    ``TIMEOUT`` error item and sets ``PARTIAL_SUCCESS``, then returns the pages it managed --
    so a caller that only looks at ``result.document`` gets a truncated document that looks
    fine. What makes the option safe to enable here is that ``ensure_conversion_ok`` already
    treats ``PARTIAL_SUCCESS`` as a failure.
    """

    def _timed_out_result(self):
        from types import SimpleNamespace

        from docling.datamodel.base_models import (
            ConversionStatus,
            DoclingComponentType,
            FailureCategory,
        )
        from docling.datamodel.document import ErrorItem

        error = ErrorItem(
            component_type=DoclingComponentType.PIPELINE,
            module_name="base_pipeline",
            error_message=(
                "Document processing timeout: exceeded 600.000s limit after 601.2s. "
                "Processed 3/12 pages."
            ),
            category=FailureCategory.TIMEOUT,
        )
        # document is present and non-empty, which is exactly the trap: the pages it did
        # manage are there, so only the status and the error item say anything is wrong.
        return SimpleNamespace(
            status=ConversionStatus.PARTIAL_SUCCESS, errors=[error], document=object()
        )

    def test_a_timeout_raises_rather_than_returning_a_short_document(self):
        from docling_error_detection import ensure_conversion_ok

        with pytest.raises(RuntimeError) as raised:
            ensure_conversion_ok(self._timed_out_result())
        assert "timeout" in str(raised.value).lower()

    def test_the_message_says_how_far_it_got(self):
        # The operator needs to know whether to raise the ceiling or fix the document.
        from docling_error_detection import ensure_conversion_ok

        with pytest.raises(RuntimeError) as raised:
            ensure_conversion_ok(self._timed_out_result())
        message = str(raised.value)
        assert "partial_success" in message
        assert "Processed 3/12 pages" in message
