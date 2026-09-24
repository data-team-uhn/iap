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

// Checks an upload in the browser, before it is sent.
//
// This is here so a person finds out in a moment rather than after a slow upload and a parse, and so
// a corrupt file never reaches the document pipeline at all. It is not what enforces anything: the
// server checks the size and the stated type again on arrival, where nobody can skip it
// (AttachDocumentHandler).
//
// What only this side does is look inside the file rather than trusting its name. A .pdf that PDF.js
// cannot open, or a .docx that is not a zip with the parts a Word document has, is refused here.
// Keep MAX_FILE_SIZE in step with the server's own limit by hand: one is TypeScript, the other Java.

// 50 MB. Above this an upload is slow enough to look broken, and the parser has to hold it in memory.
export const MAX_FILE_SIZE = 50 * 1024 * 1024;

// 500 pages. Past this the parse is long enough that a person would think it had failed.
export const MAX_PDF_PAGES = 500;

export const ACCEPTED_EXTENSIONS = [ ".pdf", ".docx", ".doc" ];

interface ContentCheck {
  valid: boolean;
  error?: string;
}

// The lowercase extension including the dot, or undefined when the name carries none.
export function fileExtension(fileName: string): string | undefined {
  const dot = fileName.lastIndexOf(".");
  if (dot < 0 || dot === fileName.length - 1) {
    return undefined;
  }
  return fileName.slice(dot).toLowerCase();
}

// Rounded up, so a file a little over the limit never reports the limit back as its own size:
// Math.round turned 50.2 MB into "is 50 MB, and the limit is 50 MB".
function megabytes(bytes: number): number {
  return Math.ceil(bytes / (1024 * 1024));
}

// PDF.js parses in a web worker and refuses to open anything until told where the worker script is.
// webpack emits that script under a fixed name beside the rest of the frontend (see the rule in
// webpack.config-template.js); the URL below is what it rewrites to that location.
async function loadPdfjs() {
  const pdfjs = await import("pdfjs-dist");
  pdfjs.GlobalWorkerOptions.workerSrc = new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url).href;
  return pdfjs;
}

// Opens the PDF. A file PDF.js refuses is one the parser would refuse too, and counting its pages is
// the only way to know the length before the upload.
async function checkPdf(file: File): Promise<ContentCheck> {
  try {
    const pdfjs = await loadPdfjs();
    const data = await file.arrayBuffer();
    const pdf = await pdfjs.getDocument({ data }).promise;
    if (pdf.numPages > MAX_PDF_PAGES) {
      return {
        valid: false,
        error: `It has ${pdf.numPages} pages, and the limit is ${MAX_PDF_PAGES}.`,
      };
    }
    return { valid: true };
  } catch (error) {
    // The same message covers a broken file and a PDF.js that could not start, so the cause goes to
    // the console where it can be told apart
    console.error("Could not check %s", file.name, error);
    return { valid: false, error: "The PDF is damaged, or it is not a PDF." };
  }
}

// A DOCX is a zip. These two parts are what makes it a Word document rather than any other zip.
async function checkDocx(file: File): Promise<ContentCheck> {
  try {
    const { default: JSZip } = await import("jszip");
    const zip = await JSZip.loadAsync(file);
    if (!zip.file("[Content_Types].xml") || !zip.file("word/document.xml")) {
      return { valid: false, error: "It is not a Word document." };
    }
    return { valid: true };
  } catch {
    return { valid: false, error: "The document is damaged, or it is not a .docx file." };
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

async function checkContent(file: File, extension: string | undefined): Promise<ContentCheck> {
  if (extension === ".pdf") {
    return checkPdf(file);
  }
  if (extension === ".docx") {
    return checkDocx(file);
  }
  if (extension === ".doc") {
    return checkDoc(file);
  }
  return { valid: true };
}

/**
 * Whether a requirement that takes these types takes this file.
 *
 * A requirement states its types the way the server checks them, as MIME types, and that is what a
 * browser reports for a file it recognises. Entries written as extensions are honoured too, since a
 * schema may be written either way.
 */
function isAccepted(file: File, accepted: string[], extension: string | undefined): boolean {
  const byExtension = accepted.some(type => type.startsWith(".") && type.toLowerCase() === extension);
  const byType = file.type !== ""
    && accepted.some(type => !type.startsWith(".") && type.toLowerCase() === file.type.toLowerCase());
  if (byExtension || byType) {
    return true;
  }
  // A browser that does not know the format reports no type at all, and refusing then would be refusing
  // for lack of information. The server checks the type again on arrival, so leave that call to it.
  return file.type === "" && accepted.some(type => !type.startsWith("."));
}

/**
 * What is wrong with an upload, or undefined when nothing is.
 *
 * @param file the file the person picked
 * @param accepted the types the requirement takes, as MIME types or extensions; empty takes anything
 */
export async function validateUpload(file: File, accepted: string[] = []): Promise<string | undefined> {
  if (file.size === 0) {
    return `${file.name} is empty.`;
  }
  if (file.size > MAX_FILE_SIZE) {
    const limit = MAX_FILE_SIZE / (1024 * 1024);
    return `${file.name} is ${megabytes(file.size)} MB, and the limit is ${limit} MB.`;
  }
  const extension = fileExtension(file.name);
  if (accepted.length > 0) {
    if (!isAccepted(file, accepted, extension)) {
      return `${file.name} is not a ${accepted.join(", ")} file.`;
    }
  } else if (extension === undefined || !ACCEPTED_EXTENSIONS.includes(extension)) {
    // Nothing stated, so the formats the pipeline can read at all
    return `${file.name} is not a ${ACCEPTED_EXTENSIONS.join(", ")} file.`;
  }
  const content = await checkContent(file, extension);
  return content.valid ? undefined : `${file.name}: ${content.error ?? "It cannot be read."}`;
}
