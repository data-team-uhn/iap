# File validation

**Module:** `modules/frontend-commons` · **Files:** `fileValidation.ts`, one check per
format in `fileValidation/`, `pdfjsClient.ts` · **Used by:**
`DocumentUpload.tsx` (submissions) · **Demo:** `test-data`'s `FileValidationWidget`

Checks a file in the browser before it is uploaded: its size, its type, and whether its
content really is what its name says. A person who picks a broken or oversized file
finds out at once, rather than after a slow upload and a failed parse.

It is **not** a security check. Anyone can skip the browser, so whatever receives the
upload checks again. See [After the browser](#after-the-browser).

## Using it

```ts
import { validateUpload } from "@iap/frontend-commons/fileValidation";

const problem = await validateUpload(file, { accepted, timeoutMs: 30_000 });
if (problem !== undefined) {
  showError(problem);   // e.g. "proposal.pdf: It is encrypted with a password."
}
```

The second argument is optional, and so is each of its fields:

| Option | Type | Default | Meaning |
| --- | --- | --- | --- |
| `accepted` | `string[]` | `[]` | The MIME types accepted, such as `application/pdf`, compared ignoring case. Empty accepts any type |
| `limits` | `UploadLimits` | `{}` | `maxFileSize` in bytes, `maxPdfPages`, and `maxUnzippedSize` in bytes. Each one left out keeps its default |
| `signal` | `AbortSignal` | — | Stops the check: it then rejects with the signal's reason, and a PDF still being parsed is dropped |
| `timeoutMs` | `number` | — | How long the check may take, in milliseconds. Past it, the check stops and the file passes unchecked |

It returns a `Promise<string | undefined>`: `undefined` when the file passes every
check, and otherwise the reason it failed, written for the person to read. The reason
starts with the file name, as `"<name>: <reason>"` for a content failure. It stops at
the **first** check that fails, so a file with two problems reports one. It rejects only
when `signal` stops it.

The module also exports:

| Export | Value |
| --- | --- |
| `MAX_FILE_SIZE` | `50 * MEGABYTE` |
| `MAX_PDF_PAGES` | `500` |
| `MAX_UNZIPPED_SIZE` | `512 * MEGABYTE` |
| `MEGABYTE` | `1024 * 1024` |
| `MIME_TYPE_BY_EXTENSION` | `.pdf`, `.docx` and `.doc`, each with its MIME type |
| `PIPELINE_TYPES` | The MIME types of `MIME_TYPE_BY_EXTENSION`, for a caller accepting only what the document pipeline reads |
| `getFileExtension(name)` | The last extension, lower case with the dot (`"v1.2.Final.DOCX"` → `".docx"`), or `undefined` for none (`"README"`, `"notes."`) |
| `UploadCheckOptions`, `UploadLimits` | The types of the second argument and of its `limits` |

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
       │          first bytes against the OLE and RTF signatures
       └── other                             passes
```

A new format gets a check of its own in `fileValidation/`, returning a `ContentCheck`
(`contentCheck.ts`), and an entry in `CONTENT_CHECKS`. That table is typed by the keys
of `MIME_TYPE_BY_EXTENSION`, so a format added to the map does not compile without a
check.

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
9.54 MB, and the limit is 9.53 MB". `formatSize` in `size.ts` does this.

Every step runs under one signal, made of the caller's `signal` and the `timeoutMs`
clock. `stopOnAbort` ends the wait for a step that does not watch that signal itself,
such as the first download of PDF.js.

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
`application/msword` reads `.doc`. A type listed twice shows once, and any other type
shows in lower case: `notes.txt is not a file of an accepted type: .pdf, image/png.`

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
| PDF.js could not load, or its worker never started | passes, unchecked |
| PDF.js throws anything else | `The PDF is damaged, or it is not a PDF.` |

A PDF encrypted with an **empty** password opens without one, so only a file that really
needs a password is refused.

**A file is let through when what checks it cannot load**, such as a chunk that 404s
after a deploy. Refusing would block every good upload over a fault in the page. The
cause goes to the console as `Could not load what checks <name>, so it was not checked`.
Any other failure to open is "damaged", logged as `Could not open <name>`.

Destroying a PDF.js task does **not** settle its promise once the worker has the file,
so the check stops waiting on the signal itself and destroys the task after. A worker
that never started never answers either. See [Loading PDF.js](#loading-pdfjs) for how
that is caught.

### DOCX

A `.docx` is a zip. The check reads the zip's **central directory**, the index at its
end that names each entry with its sizes before and after unzipping. It adds up the
unzipped sizes, then unzips `[Content_Types].xml` alone and looks in it for
`wordprocessingml.document.main+xml`. It reads the content types rather than looking for
`word/document.xml`, since some programs name the main part differently. The content
types may be UTF-8 or UTF-16, told apart by the byte order mark.

| What happened | Message |
| --- | --- |
| The content types name a Word document | passes |
| Unzips to more than `maxUnzippedSize` | `It unzips to more than 512 MB.` |
| No `[Content_Types].xml`, or it names something else (an Excel file renamed `.docx`) | `It is not a Word document.` |
| Not a zip, an unreadable directory, an entry named twice, or `[Content_Types].xml` unreadable or past 256 KB | `The document is damaged, or it is not a .docx file.` |

**The sizes are checked from the directory before anything is unzipped**, because a
small zip can unzip to gigabytes and could hang the tab. **The directory's sizes can
lie**, so the one entry the check unzips is capped too. It is decompressed with the
browser's `DecompressionStream`, which stops once it passes 256 KB. A real one is a few
KB. Two entries with one name count as damage. Which of them is read is up to the
reader, so this check and the parser could see different files.

> **When working on this:** `readZipDirectory` (`zipReader.ts`) finds the end record
> (`PK 05 06`) by searching back from the end, since a trailing comment of up to 64 KB
> can follow it, then walks the entries (`PK 01 02`). ZIP64 is read wherever a field
> holds its maximum (`FFFF`, `FFFFFFFF`), through the ZIP64 locator and the entries'
> extra fields. Some writers use ZIP64 for every archive, however small. The sizes it
> reads are the ones the parser reads (`zipfile`'s `file_size`), so the two agree on
> what a file unzips to. `readZipEntry` reaches an entry's data through its local
> header. It answers `undefined` for any method but stored and deflated, and for data
> that is not valid deflate.

### DOC

An old-style `.doc` is an OLE compound file, and every OLE file starts with
`D0 CF 11 E0 A1 B1 1A E1`. Word also saves RTF under `.doc`, which starts with `{\rtf`
and which the parser converts too. The check compares the first bytes with both.

| What happened | Message |
| --- | --- |
| Starts with either signature | passes |
| Does not | `It is not a Word document.` |
| The bytes cannot be read | `The document is damaged, or it is not a .doc file.` |

This is a light check. Any OLE file passes, so an old `.xls` renamed `.doc` gets through
here and is left to the parser.

## Limits

| Limit | Default | Override | Why this default |
| --- | --- | --- | --- |
| File size | 50 MB (`MAX_FILE_SIZE`) | `limits.maxFileSize`, in bytes | The server's limit, `MAX_FILE_BYTES`. The parser holds the whole file in memory |
| PDF pages | 500 (`MAX_PDF_PAGES`) | `limits.maxPdfPages` | The parser's default for `IAP_MAX_INPUT_PAGES` |
| DOCX unzipped size | 512 MB (`MAX_UNZIPPED_SIZE`) | `limits.maxUnzippedSize`, in bytes | The parser's default for `IAP_MAX_EXPANDED_BYTES` |

A megabyte here is `MEGABYTE`, 1024 × 1024 bytes. There is no page limit for `.docx` or
`.doc`, whose page count is unknown until they are converted.

## Loading PDF.js

PDF.js (`pdfjs-dist` 6.4.299, in `aggregated-frontend`'s `package.json`) is imported
with `import()`, so webpack puts it in a chunk of its own that a page downloads only the
first time it checks a PDF. Two webpack settings in `webpack.config-template.js` keep it
that way:

- **`output.publicPath` must be `/libs/iap/resources/`**, where Sling serves the
  chunks. Anything else and the chunk 404s, so every PDF is let through unchecked.
- **The shared `vendor` chunk takes `chunks: 'initial'`**, only what pages need up
  front. Without it, every page would download PDF.js.

PDF.js reads PDFs in a web worker, which `loadPdfjs()` in `pdfjsClient.ts`
starts once and every document shares. Webpack bundles the worker as a chunk of its own:

```ts
new Worker(new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url), { type: "module" })
```

PDF.js does not watch a worker it is handed, and a worker whose script never loaded
fires `error` only once. `loadPdfjs()` listens from the moment it creates the worker. It
hands every caller a `failed` promise that rejects with `PdfWorkerFailure` if the script
never ran. Such a worker is kept rather than replaced, since a new one would load the
same missing script. An error thrown later by a running worker is an `ErrorEvent`, and
does not count: the worker still answers.

**Use `loadPdfjs()` for any other PDF.js work too**: nothing sets
`GlobalWorkerOptions.workerSrc`, so opening a file through `pdfjs-dist` directly fails
on every PDF.

A `.docx` needs no library. It is read with the browser's own `DataView` and
`DecompressionStream`.

## After the browser

This check is the first of several, and the others do not trust it.

**The upload calls it first.** `DocumentUpload.tsx` runs `validateUpload` with the
requirement's `acceptedFileTypes` before sending anything, and shows a refusal in place.
It passes `CHECK_TIMEOUT_MS` (30 seconds) as `timeoutMs`, so a check still running then
is stopped and the file sent unchecked.

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
| Name ends in `.pdf`, `.docx` or `.doc` | — | — | `path must end in one of .pdf, .docx, .doc; got …` |
| File size | 64 MiB | `IAP_MAX_INPUT_BYTES` | `document is N bytes, over the …-byte limit` |
| PDF opens, empty password allowed | — | — | `document could not be read as a PDF`, or `PDF is encrypted and did not open with an empty password` |
| PDF has pages | — | — | `document has no pages` |
| PDF pages | 500 | `IAP_MAX_INPUT_PAGES` | `document has N pages, over the …-page limit` |
| DOCX opens as a zip | — | — | `document could not be read as a .docx` |
| DOCX size once unzipped | 512 MiB | `IAP_MAX_EXPANDED_BYTES` | `document expands to N bytes, over the …-byte limit` |

A numeric setting of 0 or less turns that limit off. With `IAP_MAX_EXPANDED_BYTES` off,
a `.docx` is not opened as a zip here either.

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
can be tried against several settings. An empty limit, or one of 0 or less, falls back
to the default. Lowering a limit shows a refusal without a huge file: a size limit of 1
against a 2 MB PDF, or an unzip limit of 0.01 against any real `.docx`. A check made
stale by a change is stopped through its `signal`, and an answer that still arrives is
ignored.

## Tests

The tests are in `frontend-commons`. `fileValidation.test.ts` covers the common checks,
the time limit and `formatSize`, and replaces the three content checks. Each content
check, the zip reader and `pdfjsClient.ts` has a test file of its own.
`FileValidationWidget.test.tsx` (`test-data`) covers the widget, which replaces
`validateUpload`.

`pdfCheck.test.ts` uses a fake PDF.js rather than the real one. A test tells it what to
do with the next file it opens: report a page count, or act `"locked"`, `"damaged"` or
`"endless"`. Like the real one, destroying a task never settles its promise, and a
worker that failed stays failed. A switch makes PDF.js fail to load, as a missing chunk
would. A `.docx` is a real zip, written byte by byte by `createZip` in `zip.fixture.ts`.
A test can shape it exactly (lying sizes, ZIP64 records, a comment, an entry stored or
named twice, UTF-16 content types) or damage a few bytes of a good one.
