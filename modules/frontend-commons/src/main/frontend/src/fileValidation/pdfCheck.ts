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

import { loadPdfjs } from "../pdfViewer/pdfjsClient";

import type { ContentCheck } from "./contentCheck";
import type { PDFWorker } from "pdfjs-dist";

// PDF.js names this exception when a file will not open without a password
function isPasswordException(error: unknown): boolean {
  return error instanceof Error && error.name === "PasswordException";
}

// When PDF.js cannot load, the file is let through. Refusing would block every good upload over a
// fault in this page, and the server checks again.
function reportUncheckedFile(file: File, error: unknown): void {
  console.error("Could not load what checks %s, so it was not checked", file.name, error);
}

// PDF.js, or undefined when it could not load
async function loadChecker(file: File) {
  try {
    return await loadPdfjs();
  } catch (error) {
    reportUncheckedFile(file, error);
    return undefined;
  }
}

// A worker script that could not load, which is no fault of the file
class WorkerFailure extends Error {}

function isWorkerFailure(error: unknown): boolean {
  return error instanceof WorkerFailure;
}

// PDF.js does not watch a worker it was handed, so a check on one whose script never loaded would
// wait forever. This rejects when the worker reports that failure.
function watchWorker(worker: PDFWorker): { failed: Promise<never>; stop: () => void } {
  let stop: () => void = () => undefined;
  const failed = new Promise<never>((_resolve, reject) => {
    const fail = () => reject(new WorkerFailure("The PDF.js worker did not start"));
    worker.port.addEventListener("error", fail, { once: true });
    stop = () => worker.port.removeEventListener("error", fail);
  });
  return { failed, stop };
}

/**
 * Refuses a PDF that will not open, needs a password, has no pages or has too many.
 *
 * @param file the file, already known to be named .pdf
 * @param maxPages the most pages it may have
 * @param signal stops the check, which then rejects with the signal's reason
 */
export async function checkPdf(file: File, maxPages: number, signal?: AbortSignal): Promise<ContentCheck> {
  const loaded = await loadChecker(file);
  if (loaded === undefined) {
    return { valid: true };
  }
  const { pdfjs, worker } = loaded;
  try {
    const data = await file.arrayBuffer();
    signal?.throwIfAborted();
    // No password is passed. A file whose only password is empty opens; one that needs a password throws.
    const task = pdfjs.getDocument({ data, worker });
    // Destroying the task ends a parse that would otherwise run on, and rejects its promise
    const stop = () => void task.destroy();
    signal?.addEventListener("abort", stop, { once: true });
    const watch = watchWorker(worker);
    try {
      const pdf = await Promise.race([ task.promise, watch.failed ]);
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
      watch.stop();
      signal?.removeEventListener("abort", stop);
      // The shared worker keeps the whole file until the task is destroyed
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
