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
import { getAssetURL } from "../assetManager";

import type { PDFWorker } from "pdfjs-dist";

// The PDF.js options naming where to fetch what a document needs but does not carry, such as fonts,
// and the published package folder for each.
const DATA_FOLDERS = {
  standardFontDataUrl: "pdfjs-dist.standard_fonts",
  wasmUrl: "pdfjs-dist.wasm",
  cMapUrl: "pdfjs-dist.cmaps",
  iccUrl: "pdfjs-dist.iccs",
};

type DataUrls = Partial<Record<keyof typeof DATA_FOLDERS, string>>;

// A folder that was not published is left out, for PDF.js to do without, since it refuses an empty URL.
async function getDataUrls(): Promise<DataUrls> {
  const urls = await Promise.all(Object.entries(DATA_FOLDERS).map(async ([ option, folder ]) =>
    [ option, await getAssetURL(`asset:${folder}`) ] as const));
  return Object.fromEntries(urls.filter(([ , url ]) => url.length > 0));
}

/** The PDF.js worker's script never ran, so nothing sent to the worker gets an answer. */
export class PdfWorkerFailure extends Error {}

let shared: { worker: PDFWorker; failed: Promise<never> } | undefined;

// A promise that rejects if the worker's script never ran. PDF.js does not watch a worker it is
// handed. The error fires only once, so this listens from the start and keeps the failure for every
// later caller.
function watchStart(port: Worker): Promise<never> {
  const failed = new Promise<never>((_resolve, reject) => {
    port.addEventListener("error", event => {
      // A script that never ran fires a plain Event. An error thrown later is an ErrorEvent, and the
      // worker keeps running.
      if (!(event instanceof ErrorEvent)) {
        reject(new PdfWorkerFailure("The PDF.js worker did not start"));
      }
    });
  });
  // Keeps a failure that nobody is waiting for from being reported as an unhandled rejection
  failed.catch(() => undefined);
  return failed;
}

// Loads PDF.js, its viewer, and the one worker every document shares. `failed` rejects if that worker
// never started. Such a worker is not replaced, since a new one would load the same missing script.
export async function loadPdfjs() {
  const pdfjs = await import("pdfjs-dist");
  // The viewer reads the library from here rather than importing it.
  (globalThis as { pdfjsLib?: unknown }).pdfjsLib = pdfjs;
  const [ components, , dataUrls ] = await Promise.all([
    import("pdfjs-dist/web/pdf_viewer.mjs"),
    import("pdfjs-dist/web/pdf_viewer.css"),
    getDataUrls(),
  ]);
  if (shared === undefined) {
    // The magic comment bundles the worker as a plain module, not as the project's modern-module
    // library. Webpack 5.112 cannot render that library for an entry whose exports nobody uses, and
    // nobody uses a worker's.
    const port = new Worker(/* webpackEntryOptions: { library: { type: "module" } } */
      new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url), { type: "module" });
    shared = { failed: watchStart(port), worker: pdfjs.PDFWorker.create({ port }) };
  }
  return { pdfjs, components, dataUrls, ...shared };
}

export type ViewerComponents = Awaited<ReturnType<typeof loadPdfjs>>["components"];
