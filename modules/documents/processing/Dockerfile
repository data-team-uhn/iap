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

# Docling processing daemon.
#
# Serves POST /parse with a path under the shared /shared-docs volume. LibreOffice prep,
# Docling, and write_chunk_files all run in this container and write beside the staged file.
#
# Build, from the repo root:
#   mvn install -Pdocker -pl modules/documents/processing
# or, without Maven:
#   docker build -t iap/docling:latest modules/documents/processing
#
# Run (shared docs mounted; publish only to loopback). On Linux, --user has to name whoever
# owns the host directory behind /shared-docs ("id -u:id -g"): the image's own user is 10001,
# and a bind mount arrives with the host's ownership on top of the image layer, so without it
# the daemon cannot write {stem}.md and Chunks/ back. It does not matter on Docker Desktop.
# An overridden uid has no /etc/passwd entry, so Docker sets HOME=/, which is not writable --
# hence -e HOME=/tmp, the same thing docker-compose.yml does. --init gives the container a
# reaper at PID 1: without one, a soffice.bin that outlives its launcher reparents to the
# Python daemon, which never wait()s, and the zombies accumulate. --stop-timeout matches
# docker-compose.yml's stop_grace_period: the daemon closes its listener and lets an
# in-flight conversion finish, and a PDF near IAP_MAX_INPUT_PAGES can still be converting
# well past five minutes — SIGKILL in that window is the case the drain exists to avoid.
#   docker run --rm -p 127.0.0.1:18765:18765 \
#     --init --user "$(id -u):$(id -g)" -e HOME=/tmp --stop-timeout 900 \
#     -v /path/to/shared-docs:/shared-docs \
#     --cpus=4 --memory=8g iap/docling:latest

# Pinned by digest, for the reason requirements.txt gives for pinning the Python packages:
# the layer below bakes model weights, CI never builds this image, and a floating tag lets the
# same commit produce materially different images. The tag moved with every CPython patch
# release and every rebuild of the base layer. Re-pin deliberately; the tag stays for
# readability.
#
# What this does *not* freeze: the apt packages installed below. `apt-get install` resolves
# against Debian's archive at build time, so LibreOffice, the JRE and the imaging libraries
# still float even with the base image fixed. Freezing those means pinning versions in the
# install list, or a snapshot.debian.org source.
FROM python:3.13-slim@sha256:ffb752e139c0a19692a43af8d8523b274222dd68eebad5d583b45c2201c6e30a AS base

# Docling's PDF pipeline needs native imaging/OpenMP; LibreOffice handles DOC/DOCX→PDF/DOCX.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
        libgomp1 \
        curl \
        libreoffice-writer \
        libreoffice-java-common \
        default-jre-headless \
        fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    # One thread per process: the daemon's own ProcessPoolExecutor owns the parallelism, and
    # letting each worker also thread out oversubscribes the container's CPU quota.
    OMP_NUM_THREADS=1 \
    DOCLING_NUM_THREADS=1 \
    # The layout model triggers torch.compile, whose TorchInductor backend needs a C++
    # compiler at runtime; this slim image has none, so every conversion would fail with
    # InvalidCxxCompiler. Eager mode loses little on CPU with single-threaded workers.
    TORCHDYNAMO_DISABLE=1 \
    # Where the baked weights live. download_models() saves into DOCLING_ARTIFACTS_PATH,
    # and Docling reads them back from that same layout at runtime.
    DOCLING_ARTIFACTS_PATH=/opt/models/docling \
    HF_HOME=/opt/models/hf \
    IAP_SHARED_DOCS=/shared-docs

WORKDIR /app

# Pinned in requirements.txt rather than inline: the layer below bakes model weights whose
# contents depend on the docling version, and CI never builds this image, so an unbounded
# range let the same commit produce materially different images with nothing to catch it.
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# The user comes before the weights, and the weights are downloaded as that user. A later
# "chown -R /opt/models" would rewrite the ownership of every model file, and a metadata change
# is enough to copy a file into the new layer, so the multi-GB weights ended up stored twice in
# the image. Owning them from the start costs nothing: these are empty directories here.
RUN mkdir -p /opt/models /shared-docs \
    && useradd --create-home --uid 10001 docling \
    && chown docling:docling /app /opt/models /shared-docs
USER docling

# Bake the model weights into the image. Without this the first conversion tries to reach
# huggingface.co and fails on any host with no egress or with TLS interception — the container
# would start healthy and then fail every real request. Downloading at build time also makes
# startup deterministic and lets the runtime stay fully offline (see the ENV below).
RUN python -c "import os; from pathlib import Path; from docling.utils.model_downloader import download_models; download_models(output_dir=Path(os.environ['DOCLING_ARTIFACTS_PATH']), with_tableformer_v2=True)" \
    && find /opt/models -type d -name '.locks' -prune -exec rm -rf {} +

# Now that we have the models downloaded, we can turn HF offline.
#
# HF_HOME moves somewhere writable for the rest of the container's life. /opt/models is
# owned by uid 10001 at mode 0755, so an overridden --user (which is how a bind mount is
# made writable — see the run example above) cannot write under it, and the HF stack does
# create lock files there, which is why the download step prunes .locks. Nothing needs the
# baked cache at runtime: the weights come from DOCLING_ARTIFACTS_PATH, and both the PDF
# and DOCX paths were verified to convert with HF_HOME moved.
ENV TRANSFORMERS_OFFLINE=1 \
    HF_HUB_OFFLINE=1 \
    HF_HOME=/tmp/hf

COPY --chown=docling:docling src/main/python/ /app/

EXPOSE 18765

HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD curl -fsS http://127.0.0.1:18765/health || exit 1

# 0.0.0.0 is required here and cannot be tightened to 127.0.0.1: Docker forwards a published port
# to the container's eth0 address. Confine it from the host side by publishing to 127.0.0.1.
# Mount the same /shared-docs volume the main app uses so path-based /parse can read and write.
ENTRYPOINT ["python", "docling_daemon.py", "--host", "0.0.0.0", "--port", "18765"]
