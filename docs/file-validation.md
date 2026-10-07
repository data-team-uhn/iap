# File validation

**Module:** `modules/frontend-commons` · **Files:** `fileValidation.ts`,
`pdfjsClient.ts` · **Demo:** `test-data`'s `FileValidationWidget`

Checks a file in the browser before it is uploaded. It answers one question: is anything
wrong with this file? It looks at the size and the type, and it opens the file to make
sure the content really is what the name says.

The point is speed. A person picking a broken or oversized file finds out in a moment,
not after a slow upload and a failed parse. It is **not** a security check. Anyone can
skip the browser, so whatever receives the upload has to check again.

## Using it

```ts
import { validateUpload } from "@iap/frontend-commons/fileValidation";

const problem = await validateUpload(file, accepted, limits);
if (problem !== undefined) {
  showError(problem);   // e.g. "proposal.pdf: It is encrypted with a password."
}
```

| Argument | Type | Default | Meaning |
| --- | --- | --- | --- |
| `file` | `File` | — | The file the person picked |
| `accepted` | `string[]` | `[]` | Types taken, as MIME types (`application/pdf`) or extensions (`.pdf`). Empty means the three formats the document pipeline reads |
| `limits` | `UploadLimits` | `{}` | `maxFileSize` in bytes, `maxPdfPages`, and `maxUnzippedSize` in bytes. Each one left out keeps its default |

It returns a `Promise<string | undefined>`. `undefined` means the file passed every
check. A string is the reason it failed, written for the person to read, and always
starting with the file name. It never rejects in normal use: every failure it knows
about becomes a message.

It stops at the **first** check that fails, so a file with two problems reports one.

The module also exports:

| Export | Value |
| --- | --- |
| `MAX_FILE_SIZE` | `50 * 1024 * 1024` (50 MB) |
| `MAX_PDF_PAGES` | `500` |
| `MAX_UNZIPPED_SIZE` | `512 * 1024 * 1024` (512 MB) |
| `MEGABYTE` | `1024 * 1024` |
| `MIME_TYPE_BY_EXTENSION` | `.pdf`, `.docx` and `.doc`, each with its MIME type (see [Accepted types](#accepted-types)) |
| `ACCEPTED_EXTENSIONS` | Its keys: `[".pdf", ".docx", ".doc"]` |
| `getFileExtension(name)` | The last extension, lower case with the dot (`"v1.2.Final.DOCX"` → `".docx"`), or `undefined` when there is none (`"README"`, `"notes."`) |
| `UploadLimits` | The type of the third argument |

## Who calls whom

```
validateUpload(file, accepted, limits)
├── 1. file.size === 0?                          → "<name> is empty."
├── 2. file.size > maxFileSize?                  → "<name> is N MB, and the limit is L MB."
├── 3. accepted given?  isAccepted(file, …)      → "<name> is not a file of an accepted type: <types>."
│      accepted empty?  extension in ACCEPTED_EXTENSIONS
│                                                → "<name> is not a file of an accepted type: .pdf, .docx, .doc."
└── 4. checkContent(file, extension, limits), picked by extension:
       ├── .pdf  → checkPdf
       │           ├── loadPdfjs()               (pdfjsClient.ts: import("pdfjs-dist"), set worker URL)
       │           ├── file.arrayBuffer()
       │           ├── pdfjs.getDocument({ data }).promise
       │           ├── numPages === 0?           → "It has no pages."
       │           ├── numPages > maxPdfPages?   → "It has N pages, and the limit is L."
       │           └── task.destroy()            (always, in a finally)
       ├── .docx → checkDocx
       │           ├── file.arrayBuffer()
       │           ├── readZipDirectory(data)    (reads the zip's central directory, unzips nothing)
       │           │     unreadable?             → "The document is damaged, or it is not a .docx file."
       │           │     sizes > maxUnzippedSize? → "It unzips to more than L MB."
       │           │     no [Content_Types].xml? → "It is not a Word document."
       │           │     it stores over 16 KB?   → "The document is damaged, or it is not a .docx file."
       │           ├── import("jszip")
       │           ├── JSZip.loadAsync(data)
       │           └── read [Content_Types].xml, look for the Word main document type
       ├── .doc  → checkDoc
       │           └── read the first 8 bytes, compare to the OLE signature
       └── other → passes, nothing to look inside
```

A content failure is reported as `"<name>: <reason>"`.

## The checks, in order

| # | Check | Fails when | Message |
| --- | --- | --- | --- |
| 1 | Empty | `file.size === 0` | `proposal.pdf is empty.` |
| 2 | Size | `file.size > maxFileSize` | `proposal.pdf is 51 MB, and the limit is 50 MB.` |
| 3 | Type | see [Accepted types](#accepted-types) | `proposal.txt is not a file of an accepted type: .pdf, .docx, .doc.` |
| 4 | Content | see [Looking inside the file](#looking-inside-the-file) | `proposal.pdf: The PDF is damaged, or it is not a PDF.` |

The size in the message is **rounded up**. A 50.2 MB file says "51 MB". Rounding to the
nearest made it say "is 50 MB, and the limit is 50 MB", which reads as a bug. The limit
itself is printed as given, so a 1.5 MB limit prints "1.5 MB".

The size is checked before anything is read, so an oversized file is refused without
loading it.

## Accepted types

**When `accepted` is empty**, the file needs one of the three extensions the document
pipeline reads: `.pdf`, `.docx`, `.doc`. The browser's MIME type is ignored.

**When `accepted` is given**, a file passes if any one of these holds:

1. An entry starts with `.` and equals the file's extension. Both sides are lower-cased.
2. An entry is a MIME type and equals the browser's `file.type`. Case does not matter.
3. The browser gave **no** type at all (`file.type === ""`), and the extension names a
   type that is in the list. Only the three pipeline formats are known here:

| Extension | MIME type it stands for |
| --- | --- |
| `.pdf` | `application/pdf` |
| `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `.doc` | `application/msword` |

Rule 3 exists because a browser that does not recognise a format reports an empty type.
The name then says what the file is meant to be, and step 4 checks the content against
that.

Examples, with `accepted = ["application/pdf"]`:

| File | `file.type` | Result |
| --- | --- | --- |
| `a.pdf` | `application/pdf` | passes, by rule 2 |
| `a.pdf` | `""` | passes, by rule 3 |
| `a.pdf` | `application/octet-stream` | **refused**: a type was given and it is not in the list |
| `README` | `""` | refused: no extension, so nothing says what it is |
| `note.png` | `image/png` | refused |

With `accepted = [".docx"]`, a `.pdf` is refused even though the pipeline could read it.
The caller's list wins over the pipeline's list.

The refusal names the accepted types the way a person knows them. A MIME type the module
knows is shown as its extension, so `application/msword` reads `.doc`, and a type listed
both ways is named once. Any other type is shown as given: `notes.txt is not a file of
an accepted type: .pdf, image/png.`

## Looking inside the file

Which check runs depends on the **extension**, not on the MIME type. A file accepted by
type whose name has no known extension gets no content check at all.

### PDF

1. PDF.js is loaded (see [Loading PDF.js and JSZip](#loading-pdfjs-and-jszip)).
2. The whole file is read into memory and handed to PDF.js. No password is passed.
3. PDF.js opens it and counts the pages.
4. The PDF.js task is destroyed in a `finally`, pass or fail. Each check starts its own
   worker holding the whole file, and without this it would live as long as the tab.

| What happened | Message |
| --- | --- |
| Opens, `numPages` from 1 to `maxPdfPages` | passes |
| Opens, 0 pages | `It has no pages.` |
| Opens, too many pages | `It has 612 pages, and the limit is 500.` |
| PDF.js throws an error named `PasswordException` | `It is encrypted with a password.` |
| PDF.js could not load, or its worker could not start | passes, unchecked |
| PDF.js throws anything else | `The PDF is damaged, or it is not a PDF.` |

A PDF encrypted with an **empty** password opens without a password, so it passes. Only
a file that really needs a password is refused.

**A file is let through when what checks it cannot load.** A chunk that 404s after a
deploy, or a worker script that will not start, is a fault in the page, not in the file.
Refusing would block every good upload, and the server checks again anyway. The cause
goes to the browser console as `Could not load what checks <name>, so it was not
checked`. PDF.js reports a worker that would not start as `Setting up fake worker
failed`, which is how that case is told apart from a broken file. Any other failure to
open a file is still "damaged", logged as `Could not open <name>`.

Counting pages is the only way to know how long a PDF is before it is uploaded. 500
pages is where the parse gets long enough that a person would think it had failed.

### DOCX

A `.docx` is a zip file. The check:

1. Reads the whole file into memory.
2. Reads the zip's list of entries, without unzipping anything.
3. Adds up how big the entries are once unzipped.
4. Finds `[Content_Types].xml` in the list, and checks it stores no more than 16 KB.
5. Loads JSZip, opens the zip and reads `[Content_Types].xml`.
6. Looks for the text `wordprocessingml.document.main+xml` in it.

It looks at the content types and not at a fixed file name like `word/document.xml`,
because some programs name the main part differently (`word/document2.xml`).

| What happened | Message |
| --- | --- |
| Zip opens, content types name a Word document | passes |
| Unzips to more than `maxUnzippedSize` | `It unzips to more than 512 MB.` |
| No `[Content_Types].xml`, or it names something else (an Excel file renamed `.docx`) | `It is not a Word document.` |
| Not a zip, its directory does not add up, or `[Content_Types].xml` stores over 16 KB | `The document is damaged, or it is not a .docx file.` |
| JSZip could not load | passes, unchecked (see [PDF](#pdf)) |

**Everything is checked from the list before anything is unzipped.** A small zip can
unzip to gigabytes: 50 MB of the same byte repeated squeezes down to almost nothing.
Unzipping such a file, even just to read the content types, could hang the tab.

The list is the zip's **central directory**, the index at the end of every zip. It names
each entry with its size before and after zipping. `readZipDirectory`:

1. Searches back from the end for the end record (signature `PK 05 06`). It is the last
   22 bytes, unless the zip ends with a comment, which can be up to 64 KB.
2. Reads from it how many entries there are and where the directory starts. When those
   fields hold their largest value (`FFFF`, `FFFFFFFF`), the real ones are in a ZIP64
   record, found through the ZIP64 locator just before the end record.
3. Walks the entries (signature `PK 01 02`), reading each name and both sizes. A size of
   `FFFFFFFF` means the real one is in the entry's ZIP64 extra field.

Some writers use ZIP64 for every archive, however small, so a ZIP64 `.docx` is read like
any other. The parser reads the same numbers (`zipfile`'s `file_size`), so the two agree
on what a file unzips to.

The list is treated as damaged when there is no end record, an entry lacks its
signature, an entry runs past the end of the directory, or a size is marked as ZIP64
with no ZIP64 field to read it from.

**The sizes in the list can lie**, and JSZip does not stop at the stated size. It unzips
everything and only then sees the lengths differ: a 200 KB file claiming 1 KB was
unzipped to 200 MB. So the stored size of `[Content_Types].xml`, the one part JSZip
unzips, is capped too. A real one stores about 1 KB. Deflate unzips at most about a
thousand times what it stores, so 16 KB stored is at most about 16 MB unzipped.

### DOC

An old-style `.doc` is an OLE compound file, and every OLE file starts with the same
eight bytes: `D0 CF 11 E0 A1 B1 1A E1`. The check reads those eight bytes and compares.

| What happened | Message |
| --- | --- |
| Starts with the signature | passes |
| Does not | `It is not a Word document.` |
| The bytes cannot be read | `The document is damaged, or it is not a .doc file.` |

This is a light check. Any OLE file passes, so an old `.xls` renamed to `.doc` gets
through here and fails later in the parser.

### Anything else

No content check. A file of another type that passed step 3 passes.

## Limits

| Limit | Default | Override | Why this default |
| --- | --- | --- | --- |
| File size | 50 MB (`MAX_FILE_SIZE`) | `limits.maxFileSize`, in bytes | Bigger uploads are slow enough to look broken, and the parser holds the file in memory |
| PDF pages | 500 (`MAX_PDF_PAGES`) | `limits.maxPdfPages` | Longer parses take long enough that a person thinks they failed |
| DOCX unzipped size | 512 MB (`MAX_UNZIPPED_SIZE`) | `limits.maxUnzippedSize`, in bytes | The parser's own limit (`IAP_MAX_EXPANDED_BYTES`) |

There is no page limit for `.docx` or `.doc`. Their page count is not known until they
are converted.

## Loading PDF.js and JSZip

Both libraries are imported with `import()`, not at the top of the file. Webpack puts
each in its own chunk, so a page only downloads PDF.js the first time a PDF is checked,
and JSZip the first time a `.docx` is. That holds because the shared `vendor` chunk
takes only libraries pages need up front (`chunks: 'initial'` in
`webpack.config-template.js`). Without that, both would join it and every page would
download them.

| Library | Version | Added in |
| --- | --- | --- |
| `pdfjs-dist` | 4.10.38 | `aggregated-frontend/src/main/frontend/package.json` |
| `jszip` | 3.10.1 | the same file |

PDF.js reads PDFs in a web worker, a separate script file. It will not open anything
until it is told where that file is. Two pieces set that up:

- `pdfjsClient.ts`: `loadPdfjs()` imports PDF.js and sets
  `GlobalWorkerOptions.workerSrc` to `new URL("pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url)`.
- `webpack.config-template.js`: a rule matching `pdf.worker.min.mjs` emits it as its own
  file, `pdf.worker.min.js`, under `/libs/iap/resources/`, and rewrites the URL above to
  point there.

The file is renamed from `.mjs` to `.js` because Sling serves `.mjs` as
`application/octet-stream`, and the browser refuses to load a module with that type.

Use `loadPdfjs()` for any other PDF.js work too, such as showing a PDF. Importing
`pdfjs-dist` directly skips the worker setup, and every PDF then fails to open.

## What happens after the browser

This check is the first of several. The others do not trust it.

**The parser checks again.** The Docling daemon (`modules/documents/processing`) runs
its own checks on every `/parse` request, in `shared_docs.py`:

| Check | Default | Setting | Refusal |
| --- | --- | --- | --- |
| Path is inside the shared volume | `/shared-docs` | `IAP_SHARED_DOCS` | `path must be under …` |
| File size | 64 MiB | `IAP_MAX_INPUT_BYTES` | `document is N bytes, over the …-byte limit` |
| PDF opens (empty password allowed) | — | — | `document could not be read as a PDF`, or `PDF is encrypted and did not open with an empty password` |
| PDF has pages | — | — | `document has no pages` |
| PDF pages | 500 | `IAP_MAX_INPUT_PAGES` | `document has N pages, over the …-page limit` |
| DOCX opens as a zip | — | — | `document could not be read as a .docx` |
| DOCX size once unzipped | 512 MiB | `IAP_MAX_EXPANDED_BYTES` | `document expands to N bytes, over the …-byte limit` |
| Type is `.pdf`, `.docx` or `.doc` | — | — | `Unsupported file type: …` |

Setting a limit to 0 turns it off.

**Whatever receives the upload has to check too.** There is no upload endpoint on `main`
yet. Whoever adds one has to check at least the size and the type on the server, because
a request that never went through a browser skips this module completely.

## The demo widget

Start the app with test data (`./start.sh --test`). The dashboard then has a **File
check** widget (`test-data/src/main/frontend/src/FileValidationWidget.tsx`, registered
by `Extensions/DashboardWidget/FileValidation.json`).

| Control | What it does |
| --- | --- |
| **Pick a file** | Picks the file to check. Nothing is uploaded |
| **Accepts** | `Anything the pipeline reads` (`[]`), `PDF only` (`["application/pdf"]`), or `Word only` (`[".docx", ".doc"]`, written as extensions) |
| **Size limit (MB)** | Passed as `maxFileSize`. Starts at 50 |
| **Page limit** | Passed as `maxPdfPages`. Starts at 500 |
| **Unzip limit (MB)** | Passed as `maxUnzippedSize`. Starts at 512 |

The result shows as "Checking …" while it runs, then a green "<name> passes every
check." or a red box with the reason.

Changing any setting checks the same file again, 300 ms after the last change, so one
file can be tried against several settings. Lowering the limits is the easy way to see
the refusals without a huge file: set the size limit to 1 and pick a 2 MB PDF, or set
the unzip limit to 0.01 (about 10 KB) and pick any real `.docx`.

An empty limit, or 0, falls back to the default rather than meaning "nothing allowed".

Each answer is kept with the file and settings it was checked under. If the settings
change while a check is still running, that check's answer is ignored when it arrives,
so the screen never shows a result for settings that are no longer selected.

## Tests

| File | Tests | What it covers |
| --- | --- | --- |
| `modules/frontend-commons/src/test/frontend/src/fileValidation.test.ts` | 43 | Every check and message, the limits at and past the edge, MIME and extension matching, caller limits, the zip directory reader, a library that does not load, the worker URL, and that every PDF.js task is destroyed |
| `test-data/src/test/frontend/src/FileValidationWidget.test.tsx` | 9 | What the widget asks `validateUpload` for, what it shows, the fall-back limits, that typing runs one check, and that a stale answer is ignored |

The validation test does not load real PDF.js. It uses a small fake: a file starting
with byte `0x25` is a PDF whose page count is in the next two bytes, low byte first, so
501 pages fits. A file starting with `0x26` needs a password, and one starting with
`0x27` meets a worker that would not start. Anything else is not a PDF. A switch makes
PDF.js or JSZip fail to load, the way a missing chunk would.

It does use real JSZip, to build real `.docx` zips, because the checks read the zip's
own bytes. Bad zips are made by changing a few bytes of a good one: pointing the
directory at the wrong place, or writing the ZIP64 marker into an entry's size. JSZip
cannot write ZIP64, so `createZip64` builds those archives by hand.

The widget test replaces `validateUpload` itself, since the rules have their own tests.

## Files

| File | What it does |
| --- | --- |
| `modules/frontend-commons/src/main/frontend/src/fileValidation.ts` | `validateUpload` and every check |
| `modules/frontend-commons/src/main/frontend/src/pdfjsClient.ts` | Loads PDF.js and tells it where its worker is |
| `aggregated-frontend/src/main/frontend/webpack.config-template.js` | Emits the PDF.js worker as `/libs/iap/resources/pdf.worker.min.js` |
| `aggregated-frontend/src/main/frontend/package.json` | The `pdfjs-dist` and `jszip` dependencies |
| `test-data/src/main/frontend/src/FileValidationWidget.tsx` | The demo widget |
| `test-data/src/main/resources/SLING-INF/content/Extensions/DashboardWidget/FileValidation.json` | Puts the widget on the dashboard |

## What is still missing

- **No real caller on `main`.** The demo widget is the only code that calls
  `validateUpload`. The submission upload that uses it lives on another branch.
- **The browser and the parser disagree on size.** The browser stops at 50 MB and the
  parser at 64 MiB. That is safe, since the stricter one runs first, but nothing keeps
  the two in step. They are in different languages and are changed by hand.
