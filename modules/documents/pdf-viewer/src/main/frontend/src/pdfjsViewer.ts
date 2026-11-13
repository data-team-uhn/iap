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
import { getAssetURL } from "@iap/frontend-commons/assetManager";
import { loadPdfjs } from "@iap/frontend-commons/pdfjsClient";

import { centreIn } from "./markLayer";

import type { EventBus, PDFLinkService } from "pdfjs-dist/web/pdf_viewer.mjs";

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

type Pdfjs = Awaited<ReturnType<typeof loadPdfjs>>["pdfjs"];

async function importViewer(pdfjs: Pdfjs) {
  // The viewer reads the library from here rather than importing it.
  (globalThis as { pdfjsLib?: unknown }).pdfjsLib = pdfjs;
  const [ components ] = await Promise.all([
    import("pdfjs-dist/web/pdf_viewer.mjs"),
    import("pdfjs-dist/web/pdf_viewer.css"),
  ]);
  return components;
}

// PDF.js's viewer, imported once for all the documents opened together, and again after failing.
let imported: ReturnType<typeof importViewer> | undefined;

// Loads PDF.js with its shared worker, the viewer that shows what it reads, and where a document's
// fonts, decoders and character maps are fetched from.
export async function loadPdfViewer() {
  const [ core, dataUrls ] = await Promise.all([ loadPdfjs(), getDataUrls() ]);
  imported ??= importViewer(core.pdfjs).catch((error: unknown) => {
    imported = undefined;
    throw error;
  });
  return { ...core, components: await imported, dataUrls };
}

export type ViewerComponents = Awaited<ReturnType<typeof loadPdfViewer>>["components"];

// PDF.js's search, except that a match is brought into view by scrolling the frame alone. PDF.js calls
// scrollIntoView, which on a phone also pans the page, dialog and all.
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
