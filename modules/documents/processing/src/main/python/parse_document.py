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

"""Shared convert + write path used by the daemon and the CLI.

LibreOffice prep (when needed) runs first and saves converted files beside the source.
Docling then converts to Markdown in memory, the PDF's bookmarks settle the heading levels
across the whole document, and ``{stem}.md`` is written beside the source.
"""

from __future__ import annotations

import contextlib
import threading
from time import perf_counter
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from collections.abc import Callable
from typing import Any

# Before the docling import below, like every other module that pulls it in: importing
# docling loads torch and libgomp, and docling_config sets OMP_NUM_THREADS first.
import docling_config  # noqa: F401 — apply shared Docling settings on import

from docling.document_converter import DocumentConverter

from docling_docx_parser import convert_docx_to_markdown
from docling_pdf_parser import convert_pdf_to_markdown
from heading_levels import correct_heading_levels
from libreoffice_convert import prepare_office_document
from markdown_cleanup import get_source_file_basename
from markdown_markers import INPUT_SUFFIXES, SUPPORTED_SUFFIXES, count_tokens
from shared_docs import write_atomically

LogFn = Callable[[str], None]


def parse_document(
    input_path: Path,
    *,
    pdf_executor: ProcessPoolExecutor | None = None,
    pdf_workers: int | None = None,
    pdf_batch_pages: int | None = None,
    docx_lock: threading.Lock | None = None,
    docx_converter: DocumentConverter | None = None,
    log: LogFn | None = None,
) -> dict[str, Any]:
    """LibreOffice prep, Docling convert, bookmark heading levels, then write ``{stem}.md``.

    @param input_path: absolute path to the staged ``.pdf`` / ``.docx`` / ``.doc``
    @param pdf_executor: warm PDF pool (daemon); ``None`` lets Docling size its own pool
    @param pdf_workers: worker count hint for the PDF pool
    @param pdf_batch_pages: pages per worker batch; ``None`` sizes it automatically
    @param docx_lock: optional lock serialising DOCX Docling conversion (daemon)
    @param docx_converter: optional warm DOCX converter (daemon)
    @param log: optional line logger
    @return: summary ``{ok, markdown_path, tokens, logs, filename}``
    """
    source = Path(input_path)
    if not source.is_file():
        raise FileNotFoundError(f"Document does not exist: {source}")

    suffix = source.suffix.lower()
    if suffix not in INPUT_SUFFIXES:
        raise ValueError(
            f"Unsupported file type: {suffix}; expected one of {', '.join(INPUT_SUFFIXES)}"
        )

    logs: list[str] = []

    def _log(message: str) -> None:
        logs.append(message)
        if log is not None:
            log(message)

    filename = get_source_file_basename(source.name)
    docling_input = prepare_office_document(source, log=_log)
    if docling_input != source:
        _log(f"LibreOffice prepared '{docling_input.name}' from '{source.name}'")

    docling_suffix = docling_input.suffix.lower()
    if docling_suffix not in SUPPORTED_SUFFIXES:
        raise ValueError(f"Docling cannot convert {docling_suffix!r} (from '{source.name}')")

    if docling_suffix == ".pdf":
        markdown = convert_pdf_to_markdown(
            docling_input,
            executor=pdf_executor,
            workers=pdf_workers,
            batch_pages=pdf_batch_pages,
            log=_log,
        )
    else:
        # The lock is optional (the CLI has no concurrent callers), so the two arms differed
        # only in holding it — nullcontext keeps the call itself written once
        with docx_lock if docx_lock is not None else contextlib.nullcontext():
            # Timed inside the lock, so a wait behind another DOCX is not counted as its own time
            t0 = perf_counter()
            markdown = convert_docx_to_markdown(
                docling_input, converter=docx_converter
            )
            elapsed = perf_counter() - t0
        # The same report the PDF path ends with, so both read the same in the logs
        _log("\n=== Timing ===")
        _log(f"Total:                {elapsed:.2f}s")
        _log(f"Markdown characters:  {len(markdown):,}")

    # The Markdown lives beside the staged source (same stem), not a LibreOffice temp.
    output_md = source.with_suffix(".md")
    # Docling only sees one page-range batch at a time, so the whole-document heading levels
    # come from the PDF's bookmarks (the staged PDF, or LibreOffice's rendition of a DOC/DOCX).
    markdown = correct_heading_levels(markdown, output_md, log=_log)
    write_atomically(output_md, markdown)

    tokens = count_tokens(markdown)
    _log(f"Wrote '{output_md.name}' ({tokens} tokens)")
    return {
        "ok": True,
        "markdown_path": str(output_md.resolve()),
        "tokens": tokens,
        "logs": "\n".join(logs),
        "filename": filename,
    }
