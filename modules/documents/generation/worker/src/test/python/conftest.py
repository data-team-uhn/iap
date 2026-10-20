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

"""Shared pytest setup for the generation worker's tests.

The worker's own modules are in this module's ``src/main/python``. The HTTP guards and the
LibreOffice conversion it reuses are in the processing module's, and the Dockerfile copies both
into the image, so both go on ``sys.path`` here, the worker's first.
"""

import sys
from pathlib import Path

# .../generation/worker/src/test/python/conftest.py -> parents[2] is .../generation/worker/src
SOURCE_ROOT = Path(__file__).resolve().parents[2]
WORKER_SOURCES = SOURCE_ROOT / "main" / "python"
SHARED_SOURCES = SOURCE_ROOT.parents[2] / "processing" / "src" / "main" / "python"

for path in (SHARED_SOURCES, WORKER_SOURCES):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))
