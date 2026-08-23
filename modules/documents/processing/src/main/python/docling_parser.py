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

"""CLI entry point: convert PDF / DOCX / DOC files to Markdown using Docling.

Uses the same :func:`parse_document.parse_document` path as the daemon (LibreOffice prep,
Docling, then :func:`chunker.chunk_file`).
"""

import argparse
import sys
from pathlib import Path

import docling_config  # noqa: F401 — apply shared Docling settings on import

from chunker import DEFAULT_MIN_STRUCTURE_TOKENS
from docling_batch_sizing import MAX_BATCH_PAGES, add_workers_argument, parse_positive_int
from markdown_markers import INPUT_SUFFIXES
from parse_document import parse_document


def parse_args():
    parser = argparse.ArgumentParser(
        description="Convert PDF, DOCX, or DOC files to Markdown using Docling."
    )
    parser.add_argument("input_file", help="Path to a .pdf, .docx, or .doc file")
    add_workers_argument(parser)
    parser.add_argument(
        "--batch-pages",
        type=parse_positive_int,
        default=None,
        metavar="N",
        help=(
            "pages per worker batch (default: auto from page count and workers, "
            f"max {MAX_BATCH_PAGES}). Lower it to cut peak memory on a large PDF"
        ),
    )
    parser.add_argument(
        "--min-structure-tokens",
        type=parse_positive_int,
        default=DEFAULT_MIN_STRUCTURE_TOKENS,
        metavar="N",
        help=(
            "skip chunking when document tokens (len//4) "
            f"are below this (default: {DEFAULT_MIN_STRUCTURE_TOKENS})"
        ),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    input_path = Path(args.input_file)

    if not input_path.exists():
        print(f"File not found: {input_path}", file=sys.stderr)
        sys.exit(1)

    suffix = input_path.suffix.lower()
    if suffix not in INPUT_SUFFIXES:
        print(f"Unsupported file type: {suffix}", file=sys.stderr)
        print(f"Supported: {', '.join(INPUT_SUFFIXES)}", file=sys.stderr)
        sys.exit(1)

    try:
        summary = parse_document(
            input_path,
            min_structure_tokens=args.min_structure_tokens,
            pdf_workers=args.workers,
            pdf_batch_pages=args.batch_pages,
            # Streamed as it happens; the accumulated summary["logs"] is the
            # same text, so printing that too said everything twice.
            log=lambda message: print(message, flush=True),
        )
    except Exception as exc:
        print(f"Parse failed: {exc}", file=sys.stderr)
        sys.exit(1)

    print(f"\nSaved to {summary['markdown_path']}")
    if summary.get("chunked"):
        print(f"Chunks in {summary['chunks_dir']}")
    else:
        print("Document left unchunked (below structure threshold)")


if __name__ == "__main__":
    main()
