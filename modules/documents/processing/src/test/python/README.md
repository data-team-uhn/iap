# Processing Python tests

Unit tests for the document-processing Python code in `../main/python`.

Most of the suite runs anywhere, with no heavy dependencies. `test_docling_runtime.py`
needs `docling` itself and skips via `importorskip` when it is absent, so a plain
`python -m pytest` is always safe to run.

Production shape (what these tests exercise pieces of): Java stages an upload under
`IAP_SHARED_DOCS` (default `/shared-docs`); the Docling daemon runs
`parse_document` → LibreOffice prep → Docling → `chunk_file`, which is the
**sole** writer of `{stem}.md` + `Chunks/`. `build_chunk_tree` is in-memory only.

| Test module | Covers |
|-------------|--------|
| `test_markdown_cleanup.py` | garbage-line stripping, blank-line collapsing, leading line-number removal, source-file headers |
| `test_markdown_markers.py` | shared `<!-- page: N -->` format (one accepted spelling), token helpers, supported suffixes |
| `test_chunker.py` | heading helpers (`heading_helpers`); `chunk_file()` routing (small docs left whole, large ones split into `Chunks/`); `outline.json` / `catalog.json`; the unchunked-outline shape |
| `test_daemon_http.py` | the daemon's request guards with no Docling import, so they run in CI: the bearer token, the `Origin` refusal, the body drain and its 1 MiB cap, `/parse` query parsing |
| `test_chunker_internals.py` | splitting internals via `build_chunk_tree`; heading helpers in `heading_helpers`; block packing, oversized splitting, paragraph fallback, heading resolution, merge passes that avoid bare-heading or tiny orphan chunks |
| `test_pdf_bookmarks.py` | flattening a PDF's embedded bookmark tree (pypdf; fake reader, no real PDF needed) |
| `test_docling_batch_sizing.py` | worker/RAM/batch-page arithmetic, including cgroup v1 and v2 quota reading |
| `test_docling_runtime.py` | daemon plumbing with no model inference: `IAP_SHARED_DOCS` path allowlisting for `POST /parse`, body draining, health reporting, broken PDF-pool shutdown, batch-abandon path |
| `test_cli_end_to_end.py` | `chunker.py` run as a subprocess (re-chunk an existing `.md`); does **not** call `docling_parser.py` / Docling |

`build_chunk_tree` writes nothing — it returns the whole tree, and
`chunker.chunk_file` is what puts the outline (and markdown / chunks) on disk. Tests
assert on that return value rather than on a file wherever they can. The size gate
(`min_structure_tokens`) lives in `build_chunk_tree`; a sibling PDF's bookmarks bypass it.

## Running

From the `modules/documents/processing` directory (where `pytest.ini` lives):

```
python -m pytest
```

Or from this directory:

```
python -m pytest .
```

`conftest.py` puts `src/main/python` on `sys.path`, so the tests import modules by bare
name regardless of where pytest is launched. `pytest.ini` limits collection to
`src/test/python`.

Requires `pytest` and `psutil` on the interpreter used. `pypdf` is needed for
`test_pdf_bookmarks.py`. `docling` is optional; without it `test_docling_runtime.py`
skips.

## From the Maven build

The tests run in the `test` phase of `modules/documents/processing`, gated by the shared `skipTests`
flag (`-Pquick` skips them, matching the Java tests):

```
mvn test -Ptests -pl modules/documents/processing
```
