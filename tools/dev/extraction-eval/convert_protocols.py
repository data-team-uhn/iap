#!/usr/bin/env python3
#
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

"""Converts PDFs to Markdown in one process, so the Docling models load once for the whole set.

    convert_protocols.py PDF...

Run it with the Python of an environment with Docling installed, with IAP_SHARED_DOCS naming the folder
that holds the PDFs. It prints one JSON line per file: {"pdf", "markdown", "tokens"}, or {"pdf", "error"}.
clinicaltrials.py fetch runs it.
"""

import json
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

PARSER_DIR = Path(__file__).resolve().parents[3] / "modules/documents/processing/src/main/python"
sys.path.insert(0, str(PARSER_DIR))

# The pipeline's own modules, found through the path set above
import docling_config  # noqa: E402,F401 -- applies the shared Docling settings on import
from docling_batch_sizing import calc_workers  # noqa: E402
from docling_pdf_parser import _init_worker, get_worker_context  # noqa: E402
from parse_document import parse_document  # noqa: E402


def main():
    # The same warm pool the daemon keeps, so each worker loads the models once
    with ProcessPoolExecutor(max_workers=calc_workers(None), initializer=_init_worker,
                             mp_context=get_worker_context()) as pool:
        for name in sys.argv[1:]:
            pdf = Path(name)
            try:
                summary = parse_document(pdf, pdf_executor=pool)
                result = {"pdf": str(pdf), "markdown": summary["markdown_path"], "tokens": summary["tokens"]}
            except Exception as error:  # pylint: disable=broad-except -- one bad protocol must not stop the rest
                result = {"pdf": str(pdf), "error": str(error)}
            print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
