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

# Document generation worker.
#
# Serves POST /generate: a .docx template and its values in, the filled .docx and its PDF out.
# Its own image rather than a second process in the Docling one: that image carries GBs of
# model weights and a RAM budget sized for a single parse, and generation needs neither. Nothing
# here touches /shared-docs, so no volume is mounted.
#
# The build context is modules/documents, not this directory: the worker reuses the HTTP guards
# and the LibreOffice conversion from utils/src/main/python, which the Docling image shares too.
# modules/documents/.dockerignore keeps the rest of the tree out.
#
# Build, from the repo root:
#   mvn install -Pdocker -pl modules/documents/generation/worker
# or, without Maven:
#   docker build -t iap/generation -f modules/documents/generation/worker/Dockerfile \
#     modules/documents
#
# Run (publish only to loopback):
#   docker run --rm -p 127.0.0.1:18766:18766 --memory=1g iap/generation

# The same base as the Docling image, pinned by digest for the reason given there
FROM python:3.14-slim@sha256:51dafde81dbdb6ebde285137a295cf18a47ca95234fe388a343719cb97305b3d

# Writer is all a DOCX-to-PDF conversion needs; unlike the Docling image there is no JRE.
# The fonts are what keeps a Word template's layout: Carlito and Caladea have Calibri's and
# Cambria's metrics, Liberation has Arial's, Times New Roman's and Courier New's, so lines
# wrap and pages break where they did in Word. tini is PID 1, so a soffice.bin that outlives
# its launcher is reaped whether or not the container was started with --init.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        tini \
        libreoffice-writer \
        fonts-dejavu-core \
        fonts-liberation \
        fonts-crosextra-carlito \
        fonts-crosextra-caladea \
    && rm -rf /var/lib/apt/lists/*

# A caller waits on each request, so one soffice run gets 10s rather than the Docling image's
# 300s. A conversion is three runs at worst (direct, then ODT and back), inside the caller's 30s.
ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    IAP_LIBREOFFICE_TIMEOUT_SECONDS=10

WORKDIR /app

COPY generation/worker/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

RUN useradd --create-home --uid 10001 generation \
    && chown generation:generation /app
USER generation

COPY --chown=generation:generation utils/src/main/python/ /app/
COPY --chown=generation:generation generation/worker/src/main/python/ /app/

# Fails the build, rather than the first deploy, if the worker imports something neither tree has
RUN python -c "import generation_server"

EXPOSE 18766

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:18766/health || exit 1

# 0.0.0.0 for the reason the Docling Dockerfile gives: Docker forwards a published port to
# eth0. Confine it from the host side by publishing to 127.0.0.1.
ENTRYPOINT ["tini", "--", "python", "generation_server.py", "--host", "0.0.0.0", "--port", "18766"]
