# File validation

**Module:** `modules/frontend-commons` · **Files:** `fileValidation.ts`, one check per
format in `fileValidation/`, `pdfViewer/pdfjsClient.ts` · **Used by:** `DocumentUpload.tsx` (submissions) · **Demo:**
`test-data`'s `FileValidationWidget`

Checks a file in the browser before it is uploaded: its size, its type, and whether its
content really is what its name says. A person who picks a broken or oversized file
finds out at once, rather than after a slow upload and a failed parse.

It is **not** a security check. Anyone can skip the browser, so whatever receives the
upload checks again — see [After the browser](#after-the-browser).

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

It returns a `Promise<string | undefined>`: `undefined` when the file passes every
check, and otherwise the reason it failed, written for the person to read and starting
with the file name — `"<name>: <reason>"` for a content failure. It stops at the
**first** check that fails, so a file with two problems reports one. It rejects only
when `signal` stops it.

The module also exports:

| Export | Value |
| --- | --- |
| `MAX_FILE_SIZE` | `50 * MEGABYTE` |
| `MAX_PDF_PAGES` | `500` |
| `MAX_UNZIPPED_SIZE` | `512 * MEGABYTE` |
| `MEGABYTE` | `1024 * 1024` |
| `MIME_TYPE_BY_EXTENSION` | `.pdf`, `.docx` and `.doc`, each with its MIME type |
| `PIPELINE_TYPES` | Its values, for a caller taking only what the document pipeline reads |
| `getFileExtension(name)` | The last extension, lower case with the dot (`"v1.2.Final.DOCX"` → `".docx"`), or `undefined` for none (`"README"`, `"notes."`) |
| `UploadLimits` | The type of the third argument |

## The checks

```
validateUpload                               fileValidation.ts
├── 1. empty
├── 2. size                                  before anything is read
├── 3. type                                  against accepted
└── 4. content, by extension
       ├── .pdf   checkPdf                   fileValidation/pdfCheck.ts
       │          loadPdfjs → getDocument → numPages → task.destroy()
       ├── .docx  checkDocx                  fileValidation/docxCheck.ts
       │          readZipDirectory → readZipEntry([Content_Types].xml), from zipReader.ts
       ├── .doc   checkDoc                   fileValidation/docCheck.ts
       │          first 8 bytes against the OLE signature
       └── other                             passes
```

A new format gets a check of its own in `fileValidation/`, returning a `ContentCheck`
(`contentCheck.ts`), and a branch in `checkContent` that calls it.

| # | Check | Fails when | Message |
| --- | --- | --- | --- |
| 1 | Empty | `file.size === 0` | `proposal.pdf is empty.` |
| 2 | Size | `file.size > maxFileSize` | `proposal.pdf is 50.2 MB, and the limit is 50 MB.` |
| 3 | Type | see [Accepted types](#accepted-types) | `proposal.txt is not a file of an accepted type: .pdf, .docx, .doc.` |
| 4 | Content | see [Looking inside the file](#looking-inside-the-file) | `proposal.pdf: The PDF is damaged, or it is not a PDF.` |

The size is checked before anything is read, so an oversized file is refused without
being loaded. Sizes read in KB under a megabyte and otherwise in MB, with at most two
decimals. **The file's size is rounded up and the limit's down**, so a file over a limit
never shows the limit's number: 10,000,001 bytes against a limit of 10,000,000 reads "is
9.54 MB, and the limit is 9.53 MB".

## Accepted types

The rules are the server's (`AttachDocumentHandler#getMimeType` and
`#checkAcceptedType`), so the browser refuses what the server would refuse and nothing
more.

With `accepted` empty, any type passes; a caller taking only what the document pipeline
reads passes `PIPELINE_TYPES`. Otherwise the file's type must equal one of its entries,
ignoring case. That type is the browser's `file.type`, lower case and without parameters
(`; charset=…`). When the browser sent no type, or only `application/octet-stream`, it
is the type the extension names instead:

| Extension | MIME type |
| --- | --- |
| `.pdf` | `application/pdf` |
| `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `.doc` | `application/msword` |

A browser that does not know a format sends no type or the generic one, so the name says
what the file is meant to be, and the content check holds it to that.

With `accepted = ["application/pdf"]`:

| File | `file.type` | Result |
| --- | --- | --- |
| `a.pdf` | `application/pdf` | passes |
| `a.pdf` | `Application/PDF; x=y` | passes: case and parameters are ignored |
| `a.pdf` | `""` or `application/octet-stream` | passes, by the extension |
| `README` | `""` | refused: nothing says what it is |
| `note.png` | `image/png` | refused |

An entry written as an extension (`.pdf`) matches nothing, since the server compares
MIME types only. The refusal names a known type by its extension, so
`application/msword` reads `.doc`, names a type listed twice once, and shows any other
type as given: `notes.txt is not a file of an accepted type: .pdf, image/png.`

## Looking inside the file

Which check runs depends on the **extension**, not on the MIME type, so a file accepted
by type whose extension is not one of the three gets no content check.

### PDF

The whole file is read into memory and opened by [PDF.js](#loading-pdfjs) with no
password, and its pages counted. The PDF.js task is destroyed in a `finally`, pass or
fail: the shared worker holds the whole file until then, and would otherwise keep it as
long as the tab is open.

| What happened | Message |
| --- | --- |
| Opens with 1 to `maxPdfPages` pages | passes |
| Opens with no pages | `It has no pages.` |
| Opens with too many pages | `It has 612 pages, and the limit is 500.` |
| PDF.js throws `PasswordException` | `It is encrypted with a password.` |
| PDF.js could not load, or its worker could not start | passes, unchecked |
| PDF.js throws anything else | `The PDF is damaged, or it is not a PDF.` |

A PDF encrypted with an **empty** password opens without one, so only a file that really
needs a password is refused.

**A file is let through when what checks it cannot load** — a chunk that 404s after a
deploy, a worker script that will not start — since refusing would block every good
upload over a fault in the page. The cause goes to the console as
`Could not load what checks <name>, so it was not checked`. PDF.js does not watch a
worker it is handed, so the check listens for the worker's own `error` event, which is
how that case is told apart from a broken file. Any other failure to open is "damaged",
logged as `Could not open <name>`.

### DOCX

A `.docx` is a zip. The check reads the zip's **central directory** — the index at its
end, naming each entry with its sizes before and after unzipping — adds up the unzipped
sizes, then unzips `[Content_Types].xml` alone and looks in it for
`wordprocessingml.document.main+xml`. It reads the content types rather than looking for
`word/document.xml`, since some programs name the main part differently.

| What happened | Message |
| --- | --- |
| The content types name a Word document | passes |
| Unzips to more than `maxUnzippedSize` | `It unzips to more than 512 MB.` |
| No `[Content_Types].xml`, or it names something else (an Excel file renamed `.docx`) | `It is not a Word document.` |
| Not a zip, an unreadable directory, an entry named twice, or `[Content_Types].xml` unreadable or past 256 KB | `The document is damaged, or it is not a .docx file.` |

**Everything is checked from the directory before anything is unzipped**, because a
small zip can unzip to gigabytes and could hang the tab. **The directory's sizes can
lie**, so the one entry the check does unzip is not trusted either: it is decompressed
with the browser's `DecompressionStream`, stopping once it passes 256 KB, where a real
one is a few KB. Two entries with one name count as damage because which of them is read
is up to the reader, so this check and the parser could see different files.

>  **When working on this:** `readZipDirectory` (`zipReader.ts`) finds the end record (`PK 05 06`) by
> searching back from the end, since a trailing comment of up to 64 KB can follow it,
> then walks the entries (`PK 01 02`). ZIP64 is read wherever a field holds its maximum
> (`FFFF`, `FFFFFFFF`), through the ZIP64 locator and the entries' extra fields — some
> writers use ZIP64 for every archive, however small. The sizes it reads are the ones
> the parser reads (`zipfile`'s `file_size`), so the two agree on what a file unzips to.
> `readZipEntry` reaches an entry's data through its local header, and treats any method
> but stored and deflated as damage.

### DOC

An old-style `.doc` is an OLE compound file, and every OLE file starts with
`D0 CF 11 E0 A1 B1 1A E1`. The check compares the first eight bytes.

| What happened | Message |
| --- | --- |
| Starts with the signature | passes |
| Does not | `It is not a Word document.` |
| The bytes cannot be read | `The document is damaged, or it is not a .doc file.` |

This is a light check: any OLE file passes, so an old `.xls` renamed `.doc` gets through
here and fails in the parser.

## Limits

| Limit | Default | Override | Why this default |
| --- | --- | --- | --- |
| File size | 50 MB (`MAX_FILE_SIZE`) | `limits.maxFileSize`, in bytes | Bigger uploads are slow enough to look broken, and the parser holds the file in memory |
| PDF pages | 500 (`MAX_PDF_PAGES`) | `limits.maxPdfPages` | Longer parses take long enough that a person thinks they failed |
| DOCX unzipped size | 512 MB (`MAX_UNZIPPED_SIZE`) | `limits.maxUnzippedSize`, in bytes | The parser's own limit, `IAP_MAX_EXPANDED_BYTES` |

A megabyte here is `MEGABYTE`, 1024 × 1024 bytes. There is no page limit for `.docx` or
`.doc`, whose page count is unknown until they are converted.

## Loading PDF.js

PDF.js (`pdfjs-dist` 6.4.299, in `aggregated-frontend`'s `package.json`) is imported
with `import()`, so webpack puts it in a chunk of its own that a page downloads only the
first time it checks a PDF. Two webpack settings in `webpack.config-template.js` keep it
that way:

-  **`output.publicPath` must be `/libs/iap/resources/`**, where Sling serves the
  chunks. Anything else and the chunk 404s, so every PDF is let through unchecked.
-  **The shared `vendor` chunk takes `chunks: 'initial'`**, only what pages need up
  front. Without it, every page would download PDF.js.

PDF.js reads PDFs in a web worker, which `loadPdfjs()` in `pdfViewer/pdfjsClient.ts`
starts once and every document shares. Webpack bundles the worker as a chunk of its own:

```ts
new Worker(new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url), { type: "module" })
```

**Use `loadPdfjs()` for any other PDF.js work too**: nothing sets
`GlobalWorkerOptions.workerSrc`, so opening a file through `pdfjs-dist` directly fails
on every PDF.

A `.docx` needs no library, being read with the browser's own `DataView` and
`DecompressionStream`.

## After the browser

This check is the first of several, and the others do not trust it.

**The upload calls it first.** `DocumentUpload.tsx` runs `validateUpload` with the
requirement's `acceptedFileTypes` before sending anything, and shows a refusal in place.
A check still running after `CHECK_TIMEOUT_MS` (30 seconds) is stopped through its
`signal` and the file sent unchecked, since the server decides anyway.

**The server checks again.** `AttachDocumentHandler` (submissions) refuses what this
module refuses on type and size, by the same rules:

| Check | Server | Here |
| --- | --- | --- |
| Size | `MAX_FILE_BYTES`, 50 MB | `MAX_FILE_SIZE`, 50 MB |
| Type | `checkAcceptedType`, after `getMimeType` | the same rules |
| Extension to type | `TYPE_BY_EXTENSION` | `MIME_TYPE_BY_EXTENSION` |
| Content | not looked at | opened |

The two are in different languages and only name each other in comments, so **a change
to one needs the same change to the other.**

**The parser checks again.** The Docling daemon (`modules/documents/processing`) checks
every `/parse` request in `shared_docs.py`:

| Check | Default | Setting | Refusal |
| --- | --- | --- | --- |
| Path is inside the shared volume | `/shared-docs` | `IAP_SHARED_DOCS` | `path must be under …` |
| File size | 64 MiB | `IAP_MAX_INPUT_BYTES` | `document is N bytes, over the …-byte limit` |
| PDF opens, empty password allowed | — | — | `document could not be read as a PDF`, or `PDF is encrypted and did not open with an empty password` |
| PDF has pages | — | — | `document has no pages` |
| PDF pages | 500 | `IAP_MAX_INPUT_PAGES` | `document has N pages, over the …-page limit` |
| DOCX opens as a zip | — | — | `document could not be read as a .docx` |
| DOCX size once unzipped | 512 MiB | `IAP_MAX_EXPANDED_BYTES` | `document expands to N bytes, over the …-byte limit` |
| Type is `.pdf`, `.docx` or `.doc` | — | — | `Unsupported file type: …` |

A numeric setting of 0 or less turns that limit off.

**Any other upload has to check too.** A request that never went through a browser skips
this module entirely, so a new upload endpoint needs its own size and type checks on the
server.

## The demo widget

Started with test data (`./start.sh --test`), the dashboard has a **File check** widget
(`test-data`'s `FileValidationWidget.tsx`, registered by
`Extensions/DashboardWidget/FileValidation.json`). Nothing it checks is uploaded.

| Control | Passes |
| --- | --- |
| **Pick a file** | The file to check |
| **Accepts** | `Anything` (`[]`), `What the pipeline reads` (`PIPELINE_TYPES`), `PDF only`, or `Word only` |
| **Size limit (MB)** | `maxFileSize`, starting at 50 |
| **Page limit** | `maxPdfPages`, starting at 500 |
| **Unzip limit (MB)** | `maxUnzippedSize`, starting at 512 |

Changing a setting checks the same file again, 300 ms after the last change, so one file
can be tried against several settings; an empty limit, or 0, falls back to the default.
Lowering a limit is the easy way to see a refusal without a huge file: a size limit of 1
against a 2 MB PDF, or an unzip limit of 0.01 against any real `.docx`. A check made
stale by a change is stopped through its `signal`, and an answer that still arrives is
ignored.

## Tests

Each source file has its own test in `frontend-commons`: `fileValidation.test.ts` for
the common checks, which replaces the three content checks, and one test per file in
`fileValidation/`. `FileValidationWidget.test.tsx` (`test-data`) covers the widget, which
replaces `validateUpload`.

`pdfCheck.test.ts` uses a fake PDF.js rather than the real one. A file whose first byte
is `0x25` is a PDF whose page count is the next two bytes, low byte first; `0x26` needs
a password; `0x27` meets a worker that will not start; `0x28` never finishes until it is
destroyed; anything else is not a PDF. A switch makes PDF.js fail to load, as a missing
chunk would. A `.docx` is a real zip, written byte by byte by `createZip` in
`zip.fixture.ts`, so a test can shape it exactly — lying sizes, ZIP64 records, a comment,
an entry stored or named twice — or damage a few bytes of a good one.
