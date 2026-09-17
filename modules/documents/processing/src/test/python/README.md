# Processing Python tests

Unit tests for the document-processing Python code in `../main/python`.

Most of the suite runs anywhere, with no heavy dependencies. `test_docling_runtime.py`
needs `docling` itself and skips via `importorskip` when it is absent, so a plain
`python -m pytest` is always safe to run.

Production shape (what these tests exercise pieces of): Java stages an upload under
`IAP_SHARED_DOCS` (default `/shared-docs`); the Docling daemon runs
`parse_document` → LibreOffice prep → Docling → bookmark heading levels → `{stem}.md`,
which is the only file the parse itself writes.

| Test module | Covers |
|-------------|--------|
| `test_markdown_cleanup.py` | garbage-line stripping, blank-line collapsing, leading line-number removal, source-file headers |
| `test_markdown_markers.py` | shared `<!-- page: N -->` format (one accepted spelling), marker defanging, the token estimate, accepted suffixes |
| `test_heading_levels.py` | the bookmark-driven heading-level pass: heading validity, the matching key, bookmark-to-line matching, the sibling-PDF lookup |
| `test_pdf_bookmarks.py` | pypdf outline flattening via a fake reader: levels, pages, the depth and count caps |
| `test_daemon_utils.py` | the daemon's request guards with no Docling import, so they run in CI: the bearer token, the `Origin` refusal, the body drain and its 1 MiB cap, `/parse` query parsing |
| `test_shared_docs.py` | the shared-docs allowlist, the input size ceilings, and the guarded write helpers |
| `test_libreoffice_convert.py` | `soffice` invocation, its timeout and process-group kill, the best-effort sibling PDF |
| `test_docling_batch_sizing.py` | worker/RAM/batch-page arithmetic, including cgroup v1 and v2 quota reading |
| `test_docling_config.py` | the shared Docling pipeline options: heading hierarchy, table structure, timeouts |
| `test_docling_runtime.py` | daemon plumbing with no model inference: `IAP_SHARED_DOCS` path allowlisting for `POST /parse`, body draining, health reporting, broken PDF-pool shutdown, batch-abandon path, and that `parse_document` writes the Markdown atomically |

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

Requires `pytest`, `psutil` and `pypdf` on the interpreter used. `docling` is optional;
without it `test_docling_runtime.py` skips.

## From the Maven build

The tests run in the `test` phase of `modules/documents/processing`, gated by the shared `skipTests`
flag (`-Pquick` skips them, matching the Java tests):

```
mvn test -Ptests -pl modules/documents/processing
```
