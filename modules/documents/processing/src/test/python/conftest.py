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

"""Shared pytest setup for the parsing tests.

The modules under test (``markdown_cleanup``, ``markdown_markers``,
``docling_batch_sizing``) live in ``src/main/python`` and are imported by
their bare module name. This file sits in ``src/test/python``. Put the source directory
on ``sys.path`` so the tests run the same way whether launched by Maven or by ``pytest``
from anywhere else. The helpers this module shares with the generation worker
(``shared_docs``, ``libreoffice_convert``, ``daemon_utils``) are in the utils module, and the
Dockerfile copies both trees into the image, so both go on the path.

``docling`` is a multi-GB install that CI leaves out. Without it, a stand-in takes its place
so the daemon and the PDF loop can still be imported and their own logic tested. A test that
needs the real package is marked ``requires_docling`` and skips.
"""

import importlib.util
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest

# .../src/test/python/conftest.py -> parents[2] is .../src
PYTHON_SOURCE_ROOT = Path(__file__).resolve().parents[2] / "main" / "python"
# .../processing/src/main/python -> parents[3] is modules/documents
UTILS_SOURCE_ROOT = PYTHON_SOURCE_ROOT.parents[3] / "utils" / "src" / "main" / "python"

for path in (UTILS_SOURCE_ROOT, PYTHON_SOURCE_ROOT):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

HAS_DOCLING = importlib.util.find_spec("docling") is not None

# Every docling module the code under src/main/python imports from
DOCLING_MODULES = (
    "docling",
    "docling.datamodel",
    "docling.datamodel.accelerator_options",
    "docling.datamodel.base_models",
    "docling.datamodel.document",
    "docling.datamodel.pipeline_options",
    "docling.datamodel.settings",
    "docling.document_converter",
)

if not HAS_DOCLING:
    for name in DOCLING_MODULES:
        sys.modules[name] = MagicMock(name=name)


def pytest_configure(config):
    config.addinivalue_line("markers", "requires_docling: needs the real docling package")


def pytest_collection_modifyitems(config, items):
    if HAS_DOCLING:
        return
    skip = pytest.mark.skip(reason="docling not installed")
    for item in items:
        if "requires_docling" in item.keywords:
            item.add_marker(skip)
