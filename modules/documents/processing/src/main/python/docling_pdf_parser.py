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

"""
Convert PDF files to Markdown using Docling.

Splits the document into page-range batches processed in parallel.
Each worker process loads the converter once via _init_worker(); page batches are reused.
"""

import logging
import multiprocessing
import os
from collections.abc import Callable
from concurrent.futures import ProcessPoolExecutor, as_completed
from concurrent.futures.process import BrokenProcessPool
from pathlib import Path
from time import perf_counter

import docling_config  # noqa: F401 — apply shared Docling settings on import
from docling_config import PDF_PIPELINE_OPTIONS

from docling.datamodel.base_models import InputFormat
from docling.document_converter import DocumentConverter, PdfFormatOption

from docling_batch_sizing import (
    calc_active_workers,
    calc_batch_pages,
    calc_chunk_count,
    calc_workers,
    print_parallelism_summary,
)
from shared_docs import open_pdf_reader, refuse_empty_pdf
from docling_error_detection import (
    DOCLING_PIPELINE_LOGGER,
    DoclingLogCollector,
    ensure_conversion_ok,
)
from markdown_cleanup import clean_markdown
from markdown_markers import get_page_marker


def build_pdf_converter() -> DocumentConverter:
    """Create a DocumentConverter for PDF processing."""
    return DocumentConverter(
        format_options={
            InputFormat.PDF: PdfFormatOption(
                pipeline_options=PDF_PIPELINE_OPTIONS
            )
        }
    )


# Per-worker converter. Populated once by _init_worker() so ML models are loaded
# once per process rather than once per page.
_converter: DocumentConverter | None = None


def get_worker_context() -> multiprocessing.context.BaseContext:
    """The start method the PDF pool must use: spawn, never fork.

    Importing this module pulls in Docling, so torch and libgomp are loaded before any pool is
    built, and forking a process that already holds an OpenMP runtime hangs workers. Fork is the
    Linux default, which is where this runs.

    :func:`_init_worker` rebuilds the converter in every worker anyway, so spawn only costs one
    interpreter start each, absorbed by the warm-up pass.
    """
    return multiprocessing.get_context("spawn")


def _init_worker() -> None:
    """Load the DocumentConverter exactly once per worker process."""
    global _converter
    _converter = build_pdf_converter()


def _warm_worker() -> int:
    """Build the PDF pipeline in this worker so model weights are loaded before any request.

    ``build_pdf_converter`` only constructs the converter; Docling loads the weights lazily on
    the first ``convert()``, which is what ``initialize_pipeline`` forces. Just checking that
    the converter exists left every worker cold while ``/health`` reported ready.

    The real work also has to happen here to spread warm-up across processes: a task that
    returns instantly goes straight back to the same idle worker, so a pool sized for N warms
    one or two (see :func:`warm_pdf_workers`).

    @return: this worker's process id, so the caller can count how many were reached;
        ``0`` when the converter was never initialized
    """
    if _converter is None:
        return 0
    _converter.initialize_pipeline(InputFormat.PDF)
    return os.getpid()


LogFn = Callable[[str], None]


def _run_pdf_chunks(
    chunks: list[tuple[str, int, int]],
    executor: ProcessPoolExecutor,
    *,
    log: LogFn,
) -> list[tuple[int, int, str, str, int, float, str | None]]:
    """Submit page batches to executor and collect results in page order."""
    completed_results: list[tuple[int, int, str, str, int, float, str | None]] = []
    # The first batch failure, kept as its message: the daemon's HTTP reply carries only
    # str(exc), so a bare "a batch failed" left the caller nothing to act on.
    had_failure: str | None = None

    future_to_chunk = {
        executor.submit(parse_pdf_chunk, chunk): chunk
        for chunk in chunks
    }

    for future in as_completed(future_to_chunk):
        _, start_page, end_page = future_to_chunk[future]
        try:
            result = future.result()
        except BrokenProcessPool:
            # The whole pool is dead, not just this batch, and it cannot recover in-process.
            # Let the real exception type through so the daemon's handler flags the pool and
            # asks for a restart; a "failed batch" tuple would leave it reporting healthy.
            for pending in future_to_chunk:
                pending.cancel()
            raise
        except Exception as e:
            result = (
                start_page,
                end_page,
                "failed",
                "",
                0,
                0.0,
                f"Executor failure: {e}",
            )

        completed_results.append(result)

        r_start, r_end, status, _md, md_len, elapsed, error = result
        if error:
            had_failure = f"pages {r_start}-{r_end}: {error}"
            log(f"FAILED pages {r_start}-{r_end}: {error}")
            _abandon_batches(future_to_chunk, log=log)
            break
        log(
            f"Completed pages {r_start}-{r_end}: status={status}, "
            f"markdown={md_len:,} chars, time={elapsed:.2f}s"
        )

    if had_failure:
        raise RuntimeError(f"Page batch conversion failed ({had_failure})")

    completed_results.sort(key=lambda item: item[0])
    return completed_results


def _abandon_batches(futures, *, log: LogFn) -> None:
    """Cancel what has not started and wait out what already has, before giving up on a
    conversion.

    ``cancel()`` cannot stop a batch already running, and in daemon mode the executor outlives
    the request. Returning early would leave those batches spending workers and RAM on a result
    nobody wants, starving the next caller.
    """
    still_running = [future for future in futures if not future.cancel() and not future.done()]
    if not still_running:
        return
    log(f"Waiting for {len(still_running)} in-flight page batch(es) to stop")
    for future in still_running:
        try:
            future.result()
        except Exception:  # noqa: BLE001 -- already failing; a batch's outcome is moot now
            pass


def convert_pdf_to_markdown(
    input_path: Path,
    *,
    batch_pages: int | None = None,
    workers: int | None = None,
    executor: ProcessPoolExecutor | None = None,
    log: LogFn | None = None,
) -> str:
    """
    Convert a PDF file to Markdown and return the text.

    @param input_path: path to the source .pdf file
    @param batch_pages: optional override for pages per worker batch
    @param workers: optional override for parallel worker process count
    @param executor: optional persistent ProcessPoolExecutor (daemon mode)
    @param log: optional log sink; defaults to print
    @return: cleaned Markdown text
    """
    log_fn = log if log is not None else print

    # Scoped to the page count, which is all that is needed here: the workers each open the
    # document themselves, and this one would otherwise stay open for the whole conversion.
    with open(input_path, "rb") as handle:
        total_pages = len(open_pdf_reader(handle).pages)
    refuse_empty_pdf(total_pages)

    worker_count = calc_workers(workers)
    batch_page_count = calc_batch_pages(total_pages, worker_count, batch_pages)
    chunk_count = calc_chunk_count(total_pages, batch_page_count)
    active_workers = calc_active_workers(worker_count, chunk_count)

    log_fn(f"Detected {total_pages} pages")
    print_parallelism_summary(
        total_pages=total_pages,
        workers=worker_count,
        batch_pages=batch_page_count,
        chunk_count=chunk_count,
        # None when the caller handed us its pool: active_workers only caps the one we build
        # ourselves below, so reporting it for a shared daemon pool overstates the limit.
        active_workers=active_workers if executor is None else None,
        workers_override=workers is not None,
        batch_pages_override=batch_pages is not None,
        log=log_fn,
    )

    # Split the document into page-range batches for processing
    chunks: list[tuple[str, int, int]] = []
    for start_page in range(1, total_pages + 1, batch_page_count):
        end_page = min(start_page + batch_page_count - 1, total_pages)
        chunks.append((str(input_path), start_page, end_page))

    if len(chunks) != chunk_count:
        raise RuntimeError(
            f"Chunk count mismatch: scheduled {len(chunks)}, expected {chunk_count}"
        )

    t0 = perf_counter()

    if executor is None:
        with ProcessPoolExecutor(
            max_workers=active_workers,
            initializer=_init_worker,
            mp_context=get_worker_context(),
        ) as pool:
            completed_results = _run_pdf_chunks(chunks, pool, log=log_fn)
    else:
        completed_results = _run_pdf_chunks(chunks, executor, log=log_fn)

    all_markdown: list[str] = []
    for _start_page, _end_page, _status, md, _md_len, _elapsed, _error in completed_results:
        all_markdown.append(md)

    parallel_end = perf_counter()
    markdown_content = clean_markdown("".join(all_markdown))
    total_end = perf_counter()

    log_fn("\n=== Timing ===")
    log_fn(f"Parallel processing:  {parallel_end - t0:.2f}s")
    log_fn(f"Cleanup and assembly: {total_end - parallel_end:.2f}s")
    log_fn(f"Total:                {total_end - t0:.2f}s")
    log_fn(f"Chunks attempted:     {chunk_count}")
    log_fn(f"Markdown characters:  {len(markdown_content):,}")

    return markdown_content


# Budget for warming *all* the workers, not each one — see :func:`warm_pdf_workers`. Kept
# under the 180s HEALTHCHECK start_period so a slow start is still a healthy start.
WORKER_WARMUP_TIMEOUT_SECONDS = 120


def warm_pdf_workers(
    executor: ProcessPoolExecutor, worker_count: int, log: LogFn | None = None
) -> None:
    """Load the Docling models in every worker process before the first request.

    Submit every task before awaiting any. Each one is slow, so no worker is idle when the next
    arrives and the pool has to spawn for it. Awaiting one at a time lets a single worker take
    the lot, because ``ProcessPoolExecutor`` only spawns when no idle worker can take the item.

    Coverage is reported, not enforced: a daemon that warmed most of its workers is still worth
    starting, but an operator seeing slow first parses needs to know.

    @param executor: the pool to warm, already created with ``_init_worker``
    @param worker_count: how many worker processes the pool was sized for
    @param log: optional line logger for partial coverage
    @raise RuntimeError: if any warm-up task reports an uninitialized converter
    @raise concurrent.futures.TimeoutError: if the whole warm-up outlasts
        :data:`WORKER_WARMUP_TIMEOUT_SECONDS`
    """
    futures = [executor.submit(_warm_worker) for _ in range(worker_count)]
    warmed: set[int] = set()
    # One deadline for the whole warm-up. Per-future timeouts make the worst case
    # worker_count x the timeout, past the 180s start_period, so the health check would fail a
    # container that was still legitimately starting.
    deadline = perf_counter() + WORKER_WARMUP_TIMEOUT_SECONDS
    for future in futures:
        pid = future.result(timeout=max(0.0, deadline - perf_counter()))
        if not pid:
            raise RuntimeError("PDF worker warm-up failed: converter not initialized")
        warmed.add(pid)
    if log is not None and len(warmed) < worker_count:
        log(
            f"Warmed {len(warmed)} of {worker_count} PDF workers; the rest load their "
            "models on first use"
        )


def parse_pdf_chunk(
    args: tuple[str, int, int],
) -> tuple[int, int, str, str, int, float, str | None]:
    """
    Parse one page-range batch in a separate process.
    Reuses the per-process converter loaded by _init_worker().

    Returns:
        start_page, end_page, status, markdown, markdown_length, elapsed_seconds, error
    """
    input_file, start_page, end_page = args
    chunk_start = perf_counter()

    try:
        if _converter is None:
            raise RuntimeError("Worker converter not initialized; _init_worker missing?")

        pipeline_logs: list[str] = []
        log_collector = DoclingLogCollector(pipeline_logs)
        pipeline_logger = logging.getLogger(DOCLING_PIPELINE_LOGGER)
        pipeline_logger.addHandler(log_collector)

        try:
            result = _converter.convert(
                input_file,
                page_range=(start_page, end_page),
            )
        finally:
            pipeline_logger.removeHandler(log_collector)

        status = str(getattr(result, "status", "unknown"))
        ensure_conversion_ok(result, pipeline_logs=pipeline_logs)

        chunk_parts: list[str] = []
        for page_no in range(start_page, end_page + 1):
            # Add page marker and page markdown to the chunk parts
            chunk_parts.append(f"\n{get_page_marker(page_no)}\n")
            page_md = result.document.export_to_markdown(page_no=page_no)
            chunk_parts.append(page_md)

        md = "".join(chunk_parts)
        elapsed = perf_counter() - chunk_start
        return start_page, end_page, status, md, len(md), elapsed, None

    except Exception as e:
        elapsed = perf_counter() - chunk_start
        return start_page, end_page, "failed", "", 0, elapsed, str(e)
