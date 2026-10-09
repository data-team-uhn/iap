/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { loadPdfjs } from "./pdfjsClient";

// Checks an upload in the browser so a bad file is refused in a moment, not after a slow upload. It
// only advises: the server checks again, where nobody can skip it.

export const MEGABYTE = 1024 * 1024;

// 50 MB. Above this an upload is slow enough to look broken, and the parser has to hold it in memory.
export const MAX_FILE_SIZE = 50 * MEGABYTE;

// 500 pages. Past this the parse is long enough that a person would think it had failed.
export const MAX_PDF_PAGES = 500;

// 512 MB, the parser's own limit (IAP_MAX_EXPANDED_BYTES). A .docx is a zip, and a small one can
// unzip to gigabytes.
export const MAX_UNZIPPED_SIZE = 512 * MEGABYTE;

// The types the pipeline reads, by the extension that names them
export const MIME_TYPE_BY_EXTENSION = {
  ".pdf": "application/pdf",
  ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  ".doc": "application/msword",
} as const;

// For a caller that takes only what the pipeline reads
export const PIPELINE_TYPES: string[] = Object.values(MIME_TYPE_BY_EXTENSION);

const MIME_TYPES = new Map<string, string>(Object.entries(MIME_TYPE_BY_EXTENSION));

const EXTENSIONS = new Map<string, string>(
  Object.entries(MIME_TYPE_BY_EXTENSION).map(([ extension, type ]) => [ type, extension ]));

// What a browser sends for a file whose type it does not know
const GENERIC_TYPE = "application/octet-stream";

/** Limits a caller can tighten or loosen; each one left out takes the default above. */
export interface UploadLimits {
  /** The largest file taken, in bytes. */
  maxFileSize?: number;
  /** The most pages a PDF may have. */
  maxPdfPages?: number;
  /** The most a .docx may unzip to, in bytes. */
  maxUnzippedSize?: number;
}

// A failed check always says why
type ContentCheck = { valid: true } | { valid: false; error: string };

// The lowercase extension including the dot, or undefined when the name carries none.
export function getFileExtension(fileName: string): string | undefined {
  const dot = fileName.lastIndexOf(".");
  if (dot < 0 || dot === fileName.length - 1) {
    return undefined;
  }
  return fileName.slice(dot).toLowerCase();
}

// A size for a person to read: KB under a megabyte, two decimals at most. A file's size rounds up and
// a limit's down, so a file over a limit never shows the same number as the limit.
function formatSize(bytes: number, rounding: (value: number) => number): string {
  const [ unit, name ] = bytes < MEGABYTE ? [ 1024, "KB" ] : [ MEGABYTE, "MB" ];
  return `${rounding(bytes / unit * 100) / 100} ${name}`;
}

// PDF.js names this exception when a file will not open without a password. An empty password
// opens without throwing, so this is only a file that genuinely needs one.
function isPasswordException(error: unknown): boolean {
  return error instanceof Error && error.name === "PasswordException";
}

// When the library that checks a file cannot load, the file is let through: the server checks again,
// and refusing every good file would block uploads over a fault in this page.
function reportUncheckedFile(file: File, error: unknown): void {
  console.error("Could not load what checks %s, so it was not checked", file.name, error);
}

// The library, or undefined when it could not load
async function loadLibrary<T>(load: () => Promise<T>, file: File): Promise<T | undefined> {
  try {
    return await load();
  } catch (error) {
    reportUncheckedFile(file, error);
    return undefined;
  }
}

// What PDF.js says when its worker script could not load, which is no fault of the file
function isWorkerFailure(error: unknown): boolean {
  return error instanceof Error && error.message.startsWith("Setting up fake worker failed");
}

// Opens the PDF. A file PDF.js refuses is one the parser would refuse too, and counting its pages is
// the only way to know the length before the upload.
async function checkPdf(file: File, maxPages: number, signal?: AbortSignal): Promise<ContentCheck> {
  const pdfjs = await loadLibrary(loadPdfjs, file);
  if (pdfjs === undefined) {
    return { valid: true };
  }
  try {
    const data = await file.arrayBuffer();
    signal?.throwIfAborted();
    // No password is passed. A file whose only password is empty opens; one that needs a password throws.
    // isEvalSupported off: PDF.js then never runs code it builds from a file's fonts
    const task = pdfjs.getDocument({ data, isEvalSupported: false });
    // Destroying the task ends a parse that would otherwise run on, and rejects its promise
    const stop = () => void task.destroy();
    signal?.addEventListener("abort", stop, { once: true });
    try {
      const pdf = await task.promise;
      // The parser refuses these too, and there is nothing in one to read
      if (pdf.numPages === 0) {
        return { valid: false, error: "It has no pages." };
      }
      if (pdf.numPages > maxPages) {
        return {
          valid: false,
          error: `It has ${pdf.numPages} pages, and the limit is ${maxPages}.`,
        };
      }
      return { valid: true };
    } finally {
      signal?.removeEventListener("abort", stop);
      // Each check has its own worker holding the whole file; left alone it lives as long as the tab
      void task.destroy();
    }
  } catch (error) {
    signal?.throwIfAborted();
    if (isPasswordException(error)) {
      return { valid: false, error: "It is encrypted with a password." };
    }
    if (isWorkerFailure(error)) {
      reportUncheckedFile(file, error);
      return { valid: true };
    }
    console.error("Could not open %s", file.name, error);
    return { valid: false, error: "The PDF is damaged, or it is not a PDF." };
  }
}

// A DOCX is a zip whose content types name a Word main document. The part's name is not fixed:
// some producers call it word/document2.xml.
const WORD_MAIN_DOCUMENT = "wordprocessingml.document.main+xml";

const DOCX_DAMAGED = "The document is damaged, or it is not a .docx file.";

const CONTENT_TYPES = "[Content_Types].xml";

// A real one is a few KB. Reading stops past this, whatever size the zip states.
const MAX_CONTENT_TYPES_BYTES = 256 * 1024;

// Zip record layouts, from the zip format's APPNOTE
const END_RECORD = 0x06054B50;
const END_RECORD_SIZE = 22;
const ZIP64_LOCATOR = 0x07064B50;
const ZIP64_LOCATOR_SIZE = 20;
const ZIP64_END_RECORD = 0x06064B50;
const ZIP64_END_RECORD_SIZE = 56;
const ZIP64_EXTRA_FIELD = 0x0001;
const DIRECTORY_ENTRY = 0x02014B50;
const DIRECTORY_ENTRY_SIZE = 46;
const LOCAL_HEADER = 0x04034B50;
const LOCAL_HEADER_SIZE = 30;
const MAX_ZIP_COMMENT = 0xFFFF;
const STORED = 0;
const DEFLATED = 8;
// A field holding its largest value has the real one in a ZIP64 record
const ZIP64_COUNT = 0xFFFF;
const ZIP64_MARKER = 0xFFFFFFFF;

interface ZipEntry {
  name: string;
  method: number;
  // As the directory states them
  storedSize: number;
  unzippedSize: number;
  // Where the entry's local header starts
  offset: number;
}

function readUint64(view: DataView, at: number): number {
  return Number(view.getBigUint64(at, true));
}

// The end record sits last, after a comment of up to 64 KB, so it is searched for backwards.
function findEndRecord(view: DataView): number | undefined {
  const last = view.byteLength - END_RECORD_SIZE;
  for (let at = last; at >= Math.max(0, last - MAX_ZIP_COMMENT); at--) {
    if (view.getUint32(at, true) === END_RECORD) {
      return at;
    }
  }
  return undefined;
}

// Where the directory starts, how many entries it has, and where it has to end
function findDirectory(view: DataView, end: number): { start: number; count: number; limit: number } | undefined {
  const count = view.getUint16(end + 10, true);
  const start = view.getUint32(end + 16, true);
  if (count !== ZIP64_COUNT && start !== ZIP64_MARKER) {
    return { start, count, limit: end };
  }
  const locator = end - ZIP64_LOCATOR_SIZE;
  if (locator < 0 || view.getUint32(locator, true) !== ZIP64_LOCATOR) {
    return undefined;
  }
  const record = readUint64(view, locator + 8);
  if (record + ZIP64_END_RECORD_SIZE > locator || view.getUint32(record, true) !== ZIP64_END_RECORD) {
    return undefined;
  }
  return { start: readUint64(view, record + 48), count: readUint64(view, record + 32), limit: record };
}

// The real values of the fields that hold the marker. The ZIP64 extra field lists only those, in
// this order.
function readZip64Fields(view: DataView, entry: ZipEntry, from: number, to: number): ZipEntry | undefined {
  for (let at = from; at + 4 <= to; at += 4 + view.getUint16(at + 2, true)) {
    if (view.getUint16(at, true) !== ZIP64_EXTRA_FIELD) {
      continue;
    }
    const fieldEnd = Math.min(to, at + 4 + view.getUint16(at + 2, true));
    let next = at + 4;
    const fields = { ...entry };
    for (const key of [ "unzippedSize", "storedSize", "offset" ] as const) {
      if (fields[key] === ZIP64_MARKER) {
        if (next + 8 > fieldEnd) {
          return undefined;
        }
        fields[key] = readUint64(view, next);
        next += 8;
      }
    }
    return fields;
  }
  return undefined;
}

/**
 * A zip's entries as its central directory lists them, read without unzipping anything.
 *
 * @returns the entries, or undefined when the directory cannot be read or names an entry twice
 */
function readZipDirectory(data: ArrayBuffer): ZipEntry[] | undefined {
  const view = new DataView(data);
  const end = findEndRecord(view);
  const directory = end === undefined ? undefined : findDirectory(view, end);
  if (directory === undefined) {
    return undefined;
  }
  const decoder = new TextDecoder();
  const entries: ZipEntry[] = [];
  const names = new Set<string>();
  let at = directory.start;
  for (let index = 0; index < directory.count; index++) {
    if (at + DIRECTORY_ENTRY_SIZE > directory.limit || view.getUint32(at, true) !== DIRECTORY_ENTRY) {
      return undefined;
    }
    const nameLength = view.getUint16(at + 28, true);
    const extraStart = at + DIRECTORY_ENTRY_SIZE + nameLength;
    const extraEnd = extraStart + view.getUint16(at + 30, true);
    if (extraEnd > directory.limit) {
      return undefined;
    }
    let entry: ZipEntry | undefined = {
      name: decoder.decode(new Uint8Array(data, at + DIRECTORY_ENTRY_SIZE, nameLength)),
      method: view.getUint16(at + 10, true),
      storedSize: view.getUint32(at + 20, true),
      unzippedSize: view.getUint32(at + 24, true),
      offset: view.getUint32(at + 42, true),
    };
    if ([ entry.storedSize, entry.unzippedSize, entry.offset ].includes(ZIP64_MARKER)) {
      entry = readZip64Fields(view, entry, extraStart, extraEnd);
    }
    // With two entries of one name, which one counts is up to the reader, so this check and the
    // parser could read different ones
    if (entry === undefined || names.has(entry.name)) {
      return undefined;
    }
    names.add(entry.name);
    entries.push(entry);
    at = extraEnd + view.getUint16(at + 32, true);
  }
  return entries;
}

// Unzips a deflated entry, giving up once it grows past the limit
async function inflate(
  stored: Uint8Array<ArrayBuffer>,
  maxBytes: number,
  signal?: AbortSignal,
): Promise<Uint8Array | undefined> {
  const source = new ReadableStream<Uint8Array<ArrayBuffer>>({
    start(controller) {
      controller.enqueue(stored);
      controller.close();
    },
  });
  const reader = source.pipeThrough(new DecompressionStream("deflate-raw")).getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
    total += chunk.value.length;
    if (total > maxBytes || signal?.aborted) {
      await reader.cancel();
      signal?.throwIfAborted();
      return undefined;
    }
    chunks.push(chunk.value);
  }
  const bytes = new Uint8Array(total);
  chunks.reduce((at, part) => {
    bytes.set(part, at);
    return at + part.length;
  }, 0);
  return bytes;
}

/**
 * One entry's content, read straight from the zip and capped, whatever size the zip states.
 *
 * @returns the content, or undefined when it cannot be read or grows past the limit
 */
async function readZipEntry(
  data: ArrayBuffer,
  entry: ZipEntry,
  maxBytes: number,
  signal?: AbortSignal,
): Promise<Uint8Array | undefined> {
  const view = new DataView(data);
  if (entry.offset + LOCAL_HEADER_SIZE > data.byteLength || view.getUint32(entry.offset, true) !== LOCAL_HEADER) {
    return undefined;
  }
  // The local header carries its own name and extra field, whose lengths can differ from the directory's
  const start = entry.offset + LOCAL_HEADER_SIZE + view.getUint16(entry.offset + 26, true)
    + view.getUint16(entry.offset + 28, true);
  if (start + entry.storedSize > data.byteLength) {
    return undefined;
  }
  const stored = new Uint8Array(data, start, entry.storedSize);
  if (entry.method === STORED) {
    return stored.length <= maxBytes ? stored : undefined;
  }
  return entry.method === DEFLATED ? inflate(stored, maxBytes, signal) : undefined;
}

// Everything is checked from the directory before anything is unzipped, and only the content types
// are unzipped: a small file can unzip to gigabytes, which would hang the tab.
async function checkDocx(file: File, maxUnzippedSize: number, signal?: AbortSignal): Promise<ContentCheck> {
  try {
    const data = await file.arrayBuffer();
    signal?.throwIfAborted();
    const entries = readZipDirectory(data);
    if (entries === undefined) {
      return { valid: false, error: DOCX_DAMAGED };
    }
    if (entries.reduce((total, entry) => total + entry.unzippedSize, 0) > maxUnzippedSize) {
      return { valid: false, error: `It unzips to more than ${formatSize(maxUnzippedSize, Math.floor)}.` };
    }
    const listed = entries.find(entry => entry.name === CONTENT_TYPES);
    if (listed === undefined) {
      return { valid: false, error: "It is not a Word document." };
    }
    const types = await readZipEntry(data, listed, MAX_CONTENT_TYPES_BYTES, signal);
    if (types === undefined) {
      return { valid: false, error: DOCX_DAMAGED };
    }
    if (!new TextDecoder().decode(types).includes(WORD_MAIN_DOCUMENT)) {
      return { valid: false, error: "It is not a Word document." };
    }
    return { valid: true };
  } catch {
    signal?.throwIfAborted();
    return { valid: false, error: DOCX_DAMAGED };
  }
}

// A legacy .doc is an OLE compound file, which always starts with these eight bytes.
const OLE_MAGIC = [ 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 ];

async function checkDoc(file: File): Promise<ContentCheck> {
  try {
    const bytes = new Uint8Array(await file.slice(0, OLE_MAGIC.length).arrayBuffer());
    if (!OLE_MAGIC.every((value, index) => bytes[index] === value)) {
      return { valid: false, error: "It is not a Word document." };
    }
    return { valid: true };
  } catch {
    return { valid: false, error: "The document is damaged, or it is not a .doc file." };
  }
}

async function checkContent(
  file: File,
  extension: string | undefined,
  limits: Required<Pick<UploadLimits, "maxPdfPages" | "maxUnzippedSize">>,
  signal?: AbortSignal,
): Promise<ContentCheck> {
  if (extension === ".pdf") {
    return checkPdf(file, limits.maxPdfPages, signal);
  }
  if (extension === ".docx") {
    return checkDocx(file, limits.maxUnzippedSize, signal);
  }
  if (extension === ".doc") {
    return checkDoc(file);
  }
  return { valid: true };
}

// The file's type read as the server reads it (AttachDocumentHandler#getMimeType): lower case, without
// parameters, and from the extension when the browser sent no type or only the generic one.
function getMimeType(file: File, extension: string | undefined): string | undefined {
  const declared = file.type.split(";", 1)[0].trim().toLowerCase();
  if (declared !== "" && declared !== GENERIC_TYPE) {
    return declared;
  }
  const fromName = extension === undefined ? undefined : MIME_TYPES.get(extension);
  return fromName ?? (declared === "" ? undefined : declared);
}

// A known MIME type reads as its extension, so a person sees ".docx" rather than the long type name
function describeWrongType(file: File, accepted: string[]): string {
  const names = new Set(accepted.map(type => EXTENSIONS.get(type.toLowerCase()) ?? type.toLowerCase()));
  return `${file.name} is not a file of an accepted type: ${[ ...names ].join(", ")}.`;
}

/**
 * What is wrong with an upload, or undefined when nothing is.
 *
 * @param file the file the person picked
 * @param accepted the MIME types taken, as the server compares them; empty takes any type
 * @param limits the size, length and unzipped-size limits, where the defaults do not fit
 * @param signal stops the check, which then rejects with the signal's reason
 */
export async function validateUpload(
  file: File,
  accepted: string[] = [],
  limits: UploadLimits = {},
  signal?: AbortSignal,
): Promise<string | undefined> {
  signal?.throwIfAborted();
  const maxFileSize = limits.maxFileSize ?? MAX_FILE_SIZE;
  if (file.size === 0) {
    return `${file.name} is empty.`;
  }
  if (file.size > maxFileSize) {
    const size = formatSize(file.size, Math.ceil);
    return `${file.name} is ${size}, and the limit is ${formatSize(maxFileSize, Math.floor)}.`;
  }
  const extension = getFileExtension(file.name);
  const type = getMimeType(file, extension);
  if (accepted.length > 0 && !accepted.some(entry => entry.toLowerCase() === type)) {
    return describeWrongType(file, accepted);
  }
  const content = await checkContent(file, extension, {
    maxPdfPages: limits.maxPdfPages ?? MAX_PDF_PAGES,
    maxUnzippedSize: limits.maxUnzippedSize ?? MAX_UNZIPPED_SIZE,
  }, signal);
  return content.valid ? undefined : `${file.name}: ${content.error}`;
}
