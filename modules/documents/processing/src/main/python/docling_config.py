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

from shared_docs import read_positive_number_from_env

# Must stay above the docling imports: they pull in torch, which loads libgomp, and libgomp
# reads OMP_NUM_THREADS once at load time. Setting it later does nothing. Outer parallelism is
# the ProcessPoolExecutor's job, so each process stays single-threaded.
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("DOCLING_NUM_THREADS", "1")

from docling.datamodel.accelerator_options import AcceleratorOptions  # noqa: E402
from docling.datamodel.pipeline_options import (  # noqa: E402
    HeadingHierarchyOptions,
    PdfPipelineOptions,
    TableStructureV2Options,
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

# Wall-clock ceiling for one page batch, so a runaway document cannot hold the daemon's only
# parse slot forever. Docling checks it between batches and returns PARTIAL_SUCCESS, which
# ensure_conversion_ok fails. So it bounds a slow conversion, not one wedged inside a single
# page. Generous on purpose -- a false timeout fails a document that would have converted.
# 0 disables it.
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
    # TableFormer V2. V1 with cell matching smeared one cell's text across a whole column:
    # on a 4-page sample it repeated the same paragraph over 7 table rows, which inflates the
    # chunk and hands the summarizer the same text seven times. V2 got the same table right in
    # 3 rows, and table structure is the pipeline's dominant cost -- it dropped from 13.7s to
    # 4.9s on those pages. The weights need `with_tableformer_v2=True` in the Dockerfile's
    # download_models call, or the offline container fails on the first table.
    table_structure_options=TableStructureV2Options(do_cell_matching=True),
    document_timeout=read_positive_number_from_env(
        DOCUMENT_TIMEOUT_VARIABLE, DEFAULT_DOCUMENT_TIMEOUT_SECONDS, float, "a number"
    ),
    # Without this the layout model gives every heading the same depth, and the chunker needs
    # levels to decide where to cut. Bookmarks and numbering are free; use_style needs
    # generate_parsed_pages=True, which blows the worker's RAM budget, and measured no better.
    heading_hierarchy_options=HeadingHierarchyOptions(
        enabled=True,
        use_bookmarks=True,
        use_numbering=True,
        use_style=False,
    ),
)
