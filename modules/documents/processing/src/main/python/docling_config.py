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

"""Shared Docling runtime settings and PDF pipeline configuration."""

import logging
import os

from shared_docs import positive_number_from_env

# Before the docling imports below, deliberately. These pull in torch, which loads libgomp,
# and libgomp reads OMP_NUM_THREADS once when it loads -- setting it afterwards is a no-op in
# the process that set it. The container also sets both as ENV, so this only ever mattered
# outside Docker: the CLI and local dev, where every worker then oversubscribed the machine.
# Outer PDF parallelism is the ProcessPoolExecutor's job, so each process stays single-threaded.
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("DOCLING_NUM_THREADS", "1")

from docling.datamodel.accelerator_options import AcceleratorOptions  # noqa: E402
from docling.datamodel.pipeline_options import (  # noqa: E402
    HeadingHierarchyOptions,
    PdfPipelineOptions,
    TableFormerMode,
    TableStructureOptions,
)
from docling.datamodel.settings import settings  # noqa: E402


class _SuppressTorchDtypeDeprecation(logging.Filter):
    """Drop transformers' torch_dtype→dtype rename chatter (Docling still passes the old name)."""

    def filter(self, record: logging.LogRecord) -> bool:
        return "`torch_dtype` is deprecated" not in record.getMessage()


logging.getLogger("transformers").addFilter(_SuppressTorchDtypeDeprecation())

# Docling internal batching/concurrency.
# Keep conservative when also using ProcessPoolExecutor, otherwise memory can spike.
settings.perf.doc_batch_concurrency = 1  # Number of docs processed in parallel
settings.perf.page_batch_concurrency = 1  # Number of page batches processed in parallel
# Pages Docling groups together internally for page-level processing.
settings.perf.page_batch_size = 1
# Extracted elements processed together internally.
settings.perf.elements_batch_size = 16

# Wall-clock ceiling for one Docling conversion, which here means one page batch (at most
# MAX_BATCH_PAGES pages). Docling checks it between batches and stops with PARTIAL_SUCCESS plus
# a TIMEOUT error item, which ensure_conversion_ok already treats as a failure -- so a document
# that runs away fails its batch loudly instead of holding the daemon's only parse slot for as
# long as it likes. Generous on purpose: a false timeout fails a document that would have
# converted, and a table-heavy page on one thread is slow rather than broken. It bounds a slow
# conversion, not a wedged one: a batch that hangs inside a single page never reaches the check.
# 0 disables it, which is Docling's own default.
DOCUMENT_TIMEOUT_VARIABLE = "IAP_DOCLING_DOCUMENT_TIMEOUT_SECONDS"
DEFAULT_DOCUMENT_TIMEOUT_SECONDS = 600.0

PDF_PIPELINE_OPTIONS = PdfPipelineOptions(
    do_ocr=False,
    do_table_structure=True,
    do_code_enrichment=False,
    do_formula_enrichment=False,
    do_picture_classification=False,
    do_picture_description=False,
    do_chart_extraction=False,
    generate_page_images=False,
    generate_picture_images=False,
    generate_table_images=False,
    generate_parsed_pages=False,
    force_backend_text=True,
    accelerator_options=AcceleratorOptions(num_threads=1, device="cpu"),
    layout_batch_size=1,
    table_batch_size=1,
    batch_polling_interval_seconds=0.1,
    table_structure_options=TableStructureOptions(
        mode=TableFormerMode.ACCURATE,
        do_cell_matching=True,
    ),
    document_timeout=positive_number_from_env(
        DOCUMENT_TIMEOUT_VARIABLE, DEFAULT_DOCUMENT_TIMEOUT_SECONDS, float, "a number"
    ),
    # The layout model flags a region as a section header without a level, so every heading
    # the PDF path produces is the same depth and the hierarchy is flat. The chunker cuts at
    # the shallowest heading level and splits an oversized chunk at the level below, so a flat
    # document gives it nothing to work with: every heading becomes a top-level cut and an
    # oversized section falls back to splitting on paragraphs.
    #
    # Bookmarks and numbering are both free. use_style is not: it reads font sizes off
    # parsed_page, which means generate_parsed_pages=True and holding every parsed page in
    # memory, and this config exists to keep a worker inside its RAM budget. Measured on a
    # numbered document it also changed nothing that numbering and bookmarks had not already
    # settled, so it stays off.
    heading_hierarchy_options=HeadingHierarchyOptions(
        enabled=True,
        use_bookmarks=True,
        use_numbering=True,
        use_style=False,
    ),
)
