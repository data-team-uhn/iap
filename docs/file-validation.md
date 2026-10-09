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

const problem = await validateUpload(file, accepted, limits, signal);
if (problem !== undefined) {
  showError(problem);   // e.g. "proposal.pdf: It is encrypted with a password."
}
```

| Argument | Type | Default | Meaning |
| --- | --- | --- | --- |
| `file` | `File` | — | The file the person picked |
| `accepted` | `string[]` | `[]` | The MIME types taken (`application/pdf`), compared as the server compares them. Empty takes any type |
| `limits` | `UploadLimits` | `{}` | `maxFileSize` in bytes, `maxPdfPages`, and `maxUnzippedSize` in bytes. Each one left out keeps its default |
| `signal` | `AbortSignal` | — | Stops the check: it then rejects with the signal's reason, and a PDF still being parsed is dropped |

It returns a `Promise<string | undefined>`. `undefined` means the file passed every
check. A string is the reason it failed, written for the person to read, and always
starting with the file name. It rejects only when `signal` stops it: every failure it
knows about becomes a message.

It stops at the **first** check that fails, so a file with two problems reports one.

The module also exports:

| Export | Value |
| --- | --- |
| `MAX_FILE_SIZE` | `50 * 1024 * 1024` (50 MB) |
| `MAX_PDF_PAGES` | `500` |
| `MAX_UNZIPPED_SIZE` | `512 * 1024 * 1024` (512 MB) |
| `MEGABYTE` | `1024 * 1024` |
| `MIME_TYPE_BY_EXTENSION` | `.pdf`, `.docx` and `.doc`, each with its MIME type (see [Accepted types](#accepted-types)) |
| `PIPELINE_TYPES` | Its values, for a caller that takes only what the document pipeline reads |
| `getFileExtension(name)` | The last extension, lower case with the dot (`"v1.2.Final.DOCX"` → `".docx"`), or `undefined` when there is none (`"README"`, `"notes."`) |
| `UploadLimits` | The type of the third argument |

## Who calls whom

```
validateUpload(file, accepted, limits, signal)
├── 1. file.size === 0?                          → "<name> is empty."
├── 2. file.size > maxFileSize?                  → "<name> is N MB, and the limit is L MB."
├── 3. accepted given?  getMimeType(file, …) in accepted?
│                                                → "<name> is not a file of an accepted type: <types>."
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
       │           ├── readZipEntry(data, …)     (unzips [Content_Types].xml alone, stopping past 256 KB)
       │           │     unreadable or too big?  → "The document is damaged, or it is not a .docx file."
       │           └── look for the Word main document type in it
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

The rules are the server's, so the browser refuses what the server would refuse and
nothing more (`AttachDocumentHandler#getMimeType` and `#checkAcceptedType`).

**When `accepted` is empty**, any type passes, as a requirement that names no type takes
any. A caller that wants only what the document pipeline reads passes `PIPELINE_TYPES`.

**When `accepted` is given**, the file's type has to be one of its entries, compared
without case. The file's type is read like this:

1. The browser's `file.type`, lower case and without parameters (`; charset=…`).
2. When the browser sent no type, or only the generic `application/octet-stream`, the
   type the extension names, if it is one of these:

| Extension | MIME type it stands for |
| --- | --- |
| `.pdf` | `application/pdf` |
| `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `.doc` | `application/msword` |

Rule 2 exists because a browser that does not know a format sends no type, or the
generic one. The name then says what the file is meant to be, and step 4 checks the
content against that.

Examples, with `accepted = ["application/pdf"]`:

| File | `file.type` | Result |
| --- | --- | --- |
| `a.pdf` | `application/pdf` | passes |
| `a.pdf` | `Application/PDF; x=y` | passes: case and parameters are ignored |
| `a.pdf` | `""` or `application/octet-stream` | passes, by the extension |
| `README` | `""` | refused: no extension, so nothing says what it is |
| `note.png` | `image/png` | refused |

An entry written as an extension (`.pdf`) matches nothing, as on the server, which only
compares MIME types.

The refusal names the accepted types the way a person knows them. A MIME type the module
knows is shown as its extension, so `application/msword` reads `.doc`, and a type listed
twice is named once. Any other type is shown as given: `notes.txt is not a file of an
accepted type: .pdf, image/png.`

## Looking inside the file

Which check runs depends on the **extension**, not on the MIME type. A file accepted by
type whose name has no known extension gets no content check at all.

### PDF

1. PDF.js is loaded (see [Loading PDF.js](#loading-pdfjs)).
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
4. Finds `[Content_Types].xml` in the list and unzips that one entry, stopping if it
   grows past 256 KB.
5. Looks for the text `wordprocessingml.document.main+xml` in it.

It looks at the content types and not at a fixed file name like `word/document.xml`,
because some programs name the main part differently (`word/document2.xml`).

| What happened | Message |
| --- | --- |
| Zip opens, content types name a Word document | passes |
| Unzips to more than `maxUnzippedSize` | `It unzips to more than 512 MB.` |
| No `[Content_Types].xml`, or it names something else (an Excel file renamed `.docx`) | `It is not a Word document.` |
| Not a zip, its directory does not add up, it names an entry twice, or `[Content_Types].xml` cannot be read or unzips past 256 KB | `The document is damaged, or it is not a .docx file.` |

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
3. Walks the entries (signature `PK 01 02`), reading each name, both sizes, how the
   entry is compressed and where it starts. A field of `FFFFFFFF` means the real value
   is in the entry's ZIP64 extra field.

Some writers use ZIP64 for every archive, however small, so a ZIP64 `.docx` is read like
any other. The parser reads the same numbers (`zipfile`'s `file_size`), so the two agree
on what a file unzips to.

The list is treated as damaged when there is no end record, an entry lacks its
signature, an entry runs past the end of the directory, a field is marked as ZIP64 with
no ZIP64 field to read it from, or two entries have the same name. With two of one name,
which one counts is up to the reader, so this check and the parser could read different
ones.

**The sizes in the list can lie.** A zip can say an entry unzips to 1 KB and hold
gigabytes. So the one entry the check needs, `[Content_Types].xml`, is not trusted:
`readZipEntry` finds its data through the entry's local header and unzips it with the
browser's own `DecompressionStream`, stopping once it passes 256 KB. A real one is a few
KB. An entry stored without compression is read as it is; any other method is treated as
damaged.

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

## Loading PDF.js

PDF.js (`pdfjs-dist` 4.10.38) is imported with `import()`, not at the top of the file.
Webpack puts it in its own chunk, so a page only downloads it the first time a PDF is
checked. That holds because the shared `vendor` chunk takes only libraries pages need up
front (`chunks: 'initial'` in `webpack.config-template.js`). Without that, it would join
that chunk and every page would download it.

A `.docx` needs no library: the zip is read with the browser's own `DataView` and
`DecompressionStream`.

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
| **Accepts** | `Anything` (`[]`), `What the pipeline reads` (`PIPELINE_TYPES`), `PDF only`, or `Word only` (the `.docx` and `.doc` MIME types) |
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
change while a check is still running, that check is stopped through its `signal`, and
an answer that still arrives is ignored, so the screen never shows a result for settings
that are no longer selected.

## Tests

| File | Tests | What it covers |
| --- | --- | --- |
| `modules/frontend-commons/src/test/frontend/src/fileValidation.test.ts` | 48 | Every check and message, the limits at and past the edge, MIME and extension matching, caller limits, the zip directory reader, a library that does not load, stopping a check, the worker URL, and that every PDF.js task is destroyed |
| `test-data/src/test/frontend/src/FileValidationWidget.test.tsx` | 10 | What the widget asks `validateUpload` for, what it shows, the fall-back limits, that typing runs one check, and that a stale check is stopped and its answer ignored |

The validation test does not load real PDF.js. It uses a small fake: a file starting
with byte `0x25` is a PDF whose page count is in the next two bytes, low byte first, so
501 pages fits. A file starting with `0x26` needs a password, one starting with `0x27`
meets a worker that would not start, and one starting with `0x28` never finishes until
it is destroyed. Anything else is not a PDF. A switch makes PDF.js fail to load, the way
a missing chunk would.

A `.docx` is a real zip, written byte by byte by `createZip`, so a test can shape it
exactly: lying sizes, ZIP64 records, a comment, an entry stored or named twice. Bad zips
are also made by changing a few bytes of a good one.

The widget test replaces `validateUpload` itself, since the rules have their own tests.

## Files

| File | What it does |
| --- | --- |
| `modules/frontend-commons/src/main/frontend/src/fileValidation.ts` | `validateUpload` and every check |
| `modules/frontend-commons/src/main/frontend/src/pdfjsClient.ts` | Loads PDF.js and tells it where its worker is |
| `aggregated-frontend/src/main/frontend/webpack.config-template.js` | Emits the PDF.js worker as `/libs/iap/resources/pdf.worker.min.js` |
| `aggregated-frontend/src/main/frontend/package.json` | The `pdfjs-dist` dependency |
| `test-data/src/main/frontend/src/FileValidationWidget.tsx` | The demo widget |
| `test-data/src/main/resources/SLING-INF/content/Extensions/DashboardWidget/FileValidation.json` | Puts the widget on the dashboard |

## What is still missing

- **No real caller on `main`.** The demo widget is the only code that calls
  `validateUpload`. The submission upload that uses it lives on another branch.
- **The browser and the parser disagree on size.** The browser stops at 50 MB and the
  parser at 64 MiB. That is safe, since the stricter one runs first, but nothing keeps
  the two in step. They are in different languages and are changed by hand.
