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

// A stand-in for pdfjs-dist: a three-page document whose text the tests set, with switches that
// make the open, a page's text or its drawing fail, or hold a page's text back until the test lets
// it go. Loaded through `vi.mock("pdfjs-dist", ...)` by every test that renders the viewer.

export const QUOTE = "Participants receive pembrolizumab 200 mg IV every three weeks.";

const DEFAULT_PAGES: [number, string][] = [
  [ 1, "Cover page of the document." ],
  [ 2, QUOTE ],
  [ 3, "The schedule continues for one year." ],
];

export interface RenderCall {
  viewport: { width: number; height: number };
  transform?: number[];
}

function createDefaults() {
  return {
    pages: new Map(DEFAULT_PAGES),
    failOpen: false,
    failPage: undefined as number | undefined,
    failRender: false,
    heldPage: undefined as number | undefined,
    releaseHeldPage: undefined as (() => void) | undefined,
    // How often each page's text was read, by page number.
    textReads: new Map<number, number>(),
    renders: [] as RenderCall[],
    // The worker each open was handed, in order.
    workers: [] as unknown[],
    // The pages PDF.js was asked to free.
    cleanedPages: [] as number[],
  };
}

export const fakePdf = {
  ...createDefaults(),
  reset(): void {
    Object.assign(fakePdf, createDefaults());
  },
};

function createTextItem(str: string) {
  return { str, transform: [ 12, 0, 0, 12, 72, 700 ], width: 400, height: 12 };
}

function createViewport(scale: number) {
  return {
    width: 600 * scale,
    height: 800 * scale,
    convertToViewportRectangle: (rect: number[]) => {
      const [ x1 = 0, y1 = 0, x2 = 0, y2 = 0 ] = rect;
      return [ x1 * scale, (800 - y2) * scale, x2 * scale, (800 - y1) * scale ];
    },
  };
}

function readText(pageNumber: number) {
  fakePdf.textReads.set(pageNumber, (fakePdf.textReads.get(pageNumber) ?? 0) + 1);
  const text = fakePdf.pages.get(pageNumber) ?? "";
  const content = { items: text.length === 0 ? [] : [ createTextItem(text) ] };
  if (pageNumber === fakePdf.failPage) {
    return Promise.reject(new Error("broken page"));
  }
  if (pageNumber === fakePdf.heldPage) {
    return new Promise<typeof content>(resolve => {
      fakePdf.releaseHeldPage = () => resolve(content);
    });
  }
  return Promise.resolve(content);
}

function createPage(pageNumber: number) {
  return {
    getViewport: ({ scale }: { scale: number }) => createViewport(scale),
    getTextContent: () => readText(pageNumber),
    render: (call: RenderCall) => {
      fakePdf.renders.push(call);
      return {
        promise: fakePdf.failRender ? Promise.reject(new Error("broken drawing")) : Promise.resolve(),
        cancel: () => undefined,
      };
    },
    cleanup: () => {
      fakePdf.cleanedPages.push(pageNumber);
      return true;
    },
  };
}

// jsdom has no 2D context; this stand-in lets the drawing reach the fake page's render and be
// copied onto the shown canvas.
export function stubCanvasContext(): void {
  vi.spyOn(HTMLCanvasElement.prototype, "getContext")
    .mockImplementation(() => ({ drawImage: () => undefined }) as unknown as CanvasRenderingContext2D);
}

// What the server answers when the viewer fetches the file. A new response per call, since a
// body can only be read once.
export function stubFetch(status = 200): void {
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(new ArrayBuffer(8), { status }))));
}

class FakePdfWorker {
  readonly promise = Promise.resolve();
}

// What `vi.mock("pdfjs-dist", ...)` hands the viewer in place of the real module.
export function createPdfjsModule() {
  return {
    GlobalWorkerOptions: { workerSrc: "" },
    PDFWorker: FakePdfWorker,
    getDocument: ({ worker }: { worker?: unknown }) => {
      fakePdf.workers.push(worker);
      return {
        destroy: () => Promise.resolve(),
        promise: fakePdf.failOpen
          ? Promise.reject(new Error("not a pdf"))
          : Promise.resolve({
            numPages: 3,
            getPage: (pageNumber: number) => Promise.resolve(createPage(pageNumber)),
            destroy: () => Promise.resolve(),
          }),
      };
    },
  };
}
