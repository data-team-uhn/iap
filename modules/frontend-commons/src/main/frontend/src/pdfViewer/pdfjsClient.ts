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
import { centreIn } from "./markLayer";

import type { PDFWorker } from "pdfjs-dist";
import type { EventBus, PDFLinkService } from "pdfjs-dist/web/pdf_viewer.mjs";

// The options naming the folders PDF.js fetches what a document needs from, and does not carry,
// as published from package-folders.config.
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

async function importPdfjs() {
  const pdfjs = await import("pdfjs-dist");
  // The viewer reads the library from here rather than importing it.
  (globalThis as { pdfjsLib?: unknown }).pdfjsLib = pdfjs;
  const [ components ] = await Promise.all([
    import("pdfjs-dist/web/pdf_viewer.mjs"),
    import("pdfjs-dist/web/pdf_viewer.css"),
  ]);
  return { pdfjs, components };
}

// PDF.js and its viewer, imported once for all the documents opened together, and again after failing.
let imported: ReturnType<typeof importPdfjs> | undefined;

let worker: PDFWorker | undefined;

// Loads PDF.js, its viewer, and the one worker every document shares, which webpack bundles as a
// chunk of its own. A worker that fails to start is not replaced, because PDF.js remembers the
// failure and a new one would fail the same way.
export async function loadPdfjs() {
  imported ??= importPdfjs().catch((error: unknown) => {
    imported = undefined;
    throw error;
  });
  const [ { pdfjs, components }, dataUrls ] = await Promise.all([ imported, getDataUrls() ]);
  worker ??= pdfjs.PDFWorker.create({
    port: new Worker(new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url), { type: "module" }),
  });
  return { pdfjs, components, worker, dataUrls };
}

export type ViewerComponents = Awaited<ReturnType<typeof loadPdfjs>>["components"];

// PDF.js's search as it comes, except for bringing a match into view by scrolling the frame alone: PDF.js
// would call scrollIntoView, which on a phone also pans the page, dialog and all.
export function createFindController(
  components: ViewerComponents,
  { container, linkService, eventBus }: { container: HTMLElement; linkService: PDFLinkService; eventBus: EventBus },
) {
  class FrameFindController extends components.PDFFindController {
    override scrollMatchIntoView({ element, pageIndex = -1, matchIndex = -1 }: {
      element?: HTMLElement | null;
      pageIndex?: number;
      matchIndex?: number;
    }) {
      const selected = this._selected;
      if (!this._scrollMatches || !element || pageIndex !== selected?.pageIdx || matchIndex !== selected.matchIdx) {
        return;
      }
      this._scrollMatches = false;
      centreIn(container, element);
    }
  }
  return new FrameFindController({ linkService, eventBus });
}
