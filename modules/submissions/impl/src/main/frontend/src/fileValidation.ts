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
// The server checks again. This is here so a person finds out in a moment rather than after a slow
// upload and a parse, and so a corrupt file never reaches the document pipeline at all.
//
// It looks inside the file rather than trusting its name. A .pdf that PDF.js cannot open, or a .docx
// that is not a zip with the parts a Word document has, is refused here.

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

function megabytes(bytes: number): number {
  return Math.round(bytes / (1024 * 1024));
}

// PDF.js parses in a web worker and refuses to open anything until told where the worker script is.
// webpack emits that script under a fixed name beside the rest of the frontend (see the rule in
// webpack.config.js); the URL below is what it rewrites to that location.
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

async function checkContent(file: File, extension: string): Promise<ContentCheck> {
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
 * What is wrong with an upload, or undefined when nothing is.
 *
 * @param file the file the person picked
 * @param accepted the extensions the requirement takes, empty when it takes anything
 */
export async function validateUpload(file: File, accepted: string[] = []): Promise<string | undefined> {
  if (file.size === 0) {
    return `${file.name} is empty.`;
  }
  if (file.size > MAX_FILE_SIZE) {
    return `${file.name} is ${megabytes(file.size)} MB, and the limit is ${megabytes(MAX_FILE_SIZE)} MB.`;
  }
  const extension = fileExtension(file.name);
  // What the requirement takes, when it says; otherwise the formats the pipeline can read at all
  const allowed = accepted.length > 0
    ? accepted.filter(type => type.startsWith("."))
    : ACCEPTED_EXTENSIONS;
  const checkedAgainst = allowed.length > 0 ? allowed : ACCEPTED_EXTENSIONS;
  if (extension === undefined || !checkedAgainst.includes(extension)) {
    return `${file.name} is not a ${checkedAgainst.join(", ")} file.`;
  }
  const content = await checkContent(file, extension);
  return content.valid ? undefined : `${file.name}: ${content.error ?? "It cannot be read."}`;
}
