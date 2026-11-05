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

import { stopOnAbort } from "./stopOnAbort";
import { PdfWorkerFailure, loadPdfjs } from "../pdfjsClient";

import type { ContentCheck } from "./contentCheck";
import type { PDFDocumentProxy } from "pdfjs-dist";

type Pdfjs = Awaited<ReturnType<typeof loadPdfjs>>;

// PDF.js names this exception when a file will not open without a password
function isPasswordException(error: unknown): boolean {
  return error instanceof Error && error.name === "PasswordException";
}

// When PDF.js cannot load or its worker never started, the file is let through. Refusing would block
// every good upload over a fault in this page, and the server checks again.
function reportUncheckedFile(file: File, error: unknown): void {
  console.error("Could not load what checks %s, so it was not checked", file.name, error);
}

// PDF.js, or undefined when it could not load
async function loadChecker(file: File): Promise<Pdfjs | undefined> {
  try {
    return await loadPdfjs();
  } catch (error) {
    reportUncheckedFile(file, error);
    return undefined;
  }
}

// Opens a PDF on the shared worker. Destroying a task does not settle its promise once the worker has
// the file, and a worker that never started never answers, so both end the wait here.
async function openPdf({ pdfjs, worker, failed }: Pdfjs, data: ArrayBuffer, signal?: AbortSignal)
  : Promise<PDFDocumentProxy> {
  // No password is passed. A file whose only password is empty opens; one that needs a password throws.
  const task = pdfjs.getDocument({ data, worker });
  try {
    return await stopOnAbort(Promise.race([ task.promise, failed ]), signal);
  } finally {
    // Ends a parse still running, and the shared worker keeps the whole file until then
    void task.destroy();
  }
}

function checkPageCount(pages: number, maxPages: number): ContentCheck {
  if (pages === 0) {
    return { valid: false, error: "It has no pages." };
  }
  if (pages > maxPages) {
    return { valid: false, error: `It has ${pages} pages, and the limit is ${maxPages}.` };
  }
  return { valid: true };
}

/**
 * Refuses a PDF that will not open, needs a password, has no pages or has too many.
 *
 * @param file the file, already known to be named .pdf
 * @param maxPages the most pages it may have
 * @param signal stops the check, which then rejects with the signal's reason
 */
export async function checkPdf(file: File, maxPages: number, signal?: AbortSignal): Promise<ContentCheck> {
  const pdfjs = await loadChecker(file);
  if (pdfjs === undefined) {
    return { valid: true };
  }
  try {
    const data = await file.arrayBuffer();
    signal?.throwIfAborted();
    const pdf = await openPdf(pdfjs, data, signal);
    return checkPageCount(pdf.numPages, maxPages);
  } catch (error) {
    signal?.throwIfAborted();
    if (isPasswordException(error)) {
      return { valid: false, error: "It is encrypted with a password." };
    }
    if (error instanceof PdfWorkerFailure) {
      reportUncheckedFile(file, error);
      return { valid: true };
    }
    console.error("Could not open %s", file.name, error);
    return { valid: false, error: "The PDF is damaged, or it is not a PDF." };
  }
}
