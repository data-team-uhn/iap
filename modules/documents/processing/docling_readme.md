# Docling parsing

Docling converts PDF or DOCX documents Markdown format.

Main processor from `.pdf` and `.docx` to Markdown.

- Source : https://github.com/docling-project/docling
- Required version v2.115+

**This module is expected to mostly be run from Docker in deployments,**
**but we provide non-Docker installation instructions below for posterity.**

## Runtime dependencies

| Package                   | Why                                                                                  |
| ------------------------- | ------------------------------------------------------------------------------------ |
| `docling`                 | Main processor: `.pdf` / `.doc` / `.docx` → `.md`; also drives hierarchical chunking |
| `pypdf`                   | Page counting / PDF reading before batching; bookmark extraction                     |
| `psutil`                  | Lets the batch-sizing script self-optimise workers to CPU/RAM                        |
| `LibreOffice` (`soffice`) | DOC→DOCX, DOC→PDF, DOCX→PDF before Docling                                           |

Test-only: `pytest`.

---

## Installation

1. **Pre-req:** Python 3.10+ (the pipeline uses PEP 604 `X | None` annotations in
   runtime-evaluated positions, which 3.9 cannot parse)
2. Use a clean virtual environment so our dependencies stay isolated:

   ```
   python -m venv Docling_env
   ```

3. Activate the virtual environment:

   ```
   # Windows
   Docling_env\Scripts\activate

   # Linux / macOS
   source Docling_env/bin/activate
   ```

4. Install the dependencies:

   ```
   pip install docling pypdf psutil
   ```

5. Set the threading environment variables (keeps per-process threads at 1 so the outer
   `ProcessPoolExecutor` owns the parallelism):

   ```
   OMP_NUM_THREADS=1
   DOCLING_NUM_THREADS=1
   ```

---

## Docling daemon

On start you get:

- **N warm PDF worker processes** (heavy models, parallel page batches)
- **1 warm DOCX converter** in the HTTP server process (lighter, single-threaded via
  `docx_lock`)

### Caller side

- Nothing starts the daemon for you. Start it with Docker, or by hand for local work.
- The caller **stages** the upload once under `/shared-docs/{uuid}/{fileName}`, then calls
  `POST /parse?path=...`. Python writes all derived files.
  The reply is a small summary (`ok`, `markdown_path`, `chunked`, `chunks_dir`, `logs`).

### Manual HTTP daemon start (optional)

```
# Optional: only if the shared root is not /shared-docs
# set IAP_SHARED_DOCS=C:\path\to\shared-docs

python modules/documents/processing/src/main/python/docling_daemon.py --host 127.0.0.1 --port 18765
```

### Test endpoints

- `GET  http://localhost:18765/health` — readiness probe (includes `shared_docs` root).
- `POST http://localhost:18765/parse?path=/shared-docs/.../proto.pdf&chunk=true` —
  path under the shared root → summary JSON. LibreOffice prep + Docling + `write_chunk_files`.
- `POST http://localhost:18765/shutdown` — graceful stop.

Paths outside `IAP_SHARED_DOCS` (default `/shared-docs`) are refused.

The daemon has **no authentication**. Every endpoint, `/shutdown` included, is open to whoever can
reach the port, so nothing that can route to it may be untrusted. Parsing is also slow, which makes
a reachable endpoint a cheap denial-of-service target. Two ways to hold that line:

- **The deployment** (`docker-compose.yml`) publishes the port as `127.0.0.1:18765:18765`, so only
  this host can reach it. A bare `18765:18765` would bind every host interface, and Docker's
  forwarding rules sit ahead of the host firewall, so it would be open to anyone who can route to
  the host. A sibling service on the same Compose network reaches the daemon by service name
  (`http://docling:18765`) without the published port, so drop the `ports:` block entirely if
  nothing on the host needs to call it.
- **A hand-run daemon** stays on loopback: keep the `--host 127.0.0.1` default when starting it
  directly. The daemon prints a warning to stderr if you bind anything else.

In a container the process itself still binds `0.0.0.0`, and that cannot be tightened: Docker
forwards traffic to the container's `eth0`, not its loopback, so a daemon listening only on the
container's loopback refuses every connection.

### Configuration

Environment:

| Variable                                       | Default        | Purpose                                                                                                                        |
| ---------------------------------------------- | -------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `IAP_SHARED_DOCS`                              | `/shared-docs` | Shared root. `?path=` is resolved against it, and paths outside it are refused                                                 |
| `IAP_LIBREOFFICE_SOFFICE` / `LIBREOFFICE_PATH` | `soffice`      | LibreOffice executable                                                                                                         |
| `OMP_NUM_THREADS` / `DOCLING_NUM_THREADS`      | `1`            | Per-process thread caps, so the outer `ProcessPoolExecutor` owns the parallelism (set on import by `docling_config.py`)        |

Daemon flags:

| Flag           | Default                      | Purpose                        |
| -------------- | ---------------------------- | ------------------------------ |
| `--host`       | `127.0.0.1`                  | Bind address                   |
| `--port`       | `18765`                      | Listen port                    |
| `--workers N`  | auto, from cores + RAM budget | Parallel PDF worker processes  |

Per-request options go on the `/parse` query string: `chunk` (default true), `max_tokens`
(2000) and `min_structure_tokens` (20000).

Run the daemon yourself — in Docker for a real deployment, or by hand for local work (see
[Manual daemon start](#manual-http-daemon-start-optional)). Docling is the only processor: if
the daemon cannot be reached, or Docling fails, the parse fails. There is no fallback.
