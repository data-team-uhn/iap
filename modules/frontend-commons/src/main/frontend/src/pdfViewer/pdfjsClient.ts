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
import type { PDFWorker } from "pdfjs-dist";

let worker: PDFWorker | undefined;

// Loads PDF.js and the one worker every document shares. A worker that fails to start
// is not replaced, because PDF.js remembers the failure and a new one would fail the same way.
export async function loadPdfjs() {
  const pdfjs = await import("pdfjs-dist");
  worker ??= pdfjs.PDFWorker.create({
    // The worker is bundled as a plain module rather than as the project's modern-module library:
    // webpack 5.112 cannot render that for an entry none of whose exports are used, as a worker's are not
    port: new Worker(/* webpackEntryOptions: { library: { type: "module" } } */
      new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url), { type: "module" }),
  });
  return { pdfjs, worker };
}
