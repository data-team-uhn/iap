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
import { findQuote, getSearchText } from "./quoteMatch";

import type { PDFDocumentProxy, PDFWorker } from "pdfjs-dist";
import type { EventBus, PDFFindController, PDFViewer } from "pdfjs-dist/web/pdf_viewer.mjs";

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

let worker: PDFWorker | undefined;

// Loads PDF.js, its viewer, and the one worker every document shares, which webpack bundles as a
// chunk of its own. A worker that fails to start is not replaced, because PDF.js remembers the
// failure and a new one would fail the same way.
export async function loadPdfjs() {
  const pdfjs = await import("pdfjs-dist");
  // The viewer reads the library from here rather than importing it.
  (globalThis as { pdfjsLib?: unknown }).pdfjsLib = pdfjs;
  const [ components, , dataUrls ] = await Promise.all([
    import("pdfjs-dist/web/pdf_viewer.mjs"),
    import("pdfjs-dist/web/pdf_viewer.css"),
    getDataUrls(),
  ]);
  worker ??= pdfjs.PDFWorker.create({
    port: new Worker(new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url), { type: "module" }),
  });
  return { pdfjs, components, worker, dataUrls };
}

export type ViewerComponents = Awaited<ReturnType<typeof loadPdfjs>>["components"];

// Scrolls the container, and only it, so the element sits in the middle of it.
function centreIn(container: HTMLElement, element: HTMLElement): void {
  const outer = container.getBoundingClientRect();
  const inner = element.getBoundingClientRect();
  container.scrollTop += inner.top - outer.top - Math.max(0, (container.clientHeight - inner.height) / 2);
  container.scrollLeft += inner.left - outer.left - Math.max(0, (container.clientWidth - inner.width) / 2);
}

// PDF.js's search, matching the quote the way findQuote does instead of word by word. It moves to
// the match and scrolls it into view only while follow() says so, so a search run again to restore
// the marks leaves the reader where they were.
export function createQuoteFinder(
  components: ViewerComponents,
  { container, viewer, doc, eventBus, follow }: {
    container: HTMLElement;
    viewer: PDFViewer;
    doc: PDFDocumentProxy;
    eventBus: EventBus;
    follow: () => boolean;
  },
): PDFFindController & { readonly hasText: boolean } {
  // The search's own link service: PDF.js moves to a match by setting its page.
  class FollowingLinkService extends components.PDFLinkService {
    override get page(): number {
      return super.page;
    }

    override set page(value: number) {
      if (follow()) {
        super.page = value;
      }
    }
  }

  class QuoteFinder extends components.PDFFindController {
    // Whether any page searched had text, which a scanned document does not.
    hasText = false;

    override match(query: string | string[], pageContent: string) {
      this.hasText ||= pageContent.trim().length > 0;
      const searchText = typeof query === "string" ? getSearchText(query) : undefined;
      return searchText === undefined ? [] : findQuote(pageContent, searchText);
    }

    // PDF.js would call scrollIntoView, which on a phone also pans the page, dialog and all.
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
      if (follow()) {
        centreIn(container, element);
      }
    }
  }

  const linkService = new FollowingLinkService({ eventBus });
  linkService.setViewer(viewer);
  linkService.setDocument(doc);
  return new QuoteFinder({ linkService, eventBus });
}
