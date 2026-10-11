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

// A stand-in for pdfjs-dist and its viewer, keeping to the protocol the real ones follow with each
// other and with PdfViewer: events on the bus, the link service moving the viewer, and the find
// controller calling match() on each page's text, setting the link service's page to the match,
// and asking for the match to be scrolled into view once its page's text is drawn. Loaded through
// vi.mock("pdfjs-dist", ...) and vi.mock("pdfjs-dist/web/pdf_viewer.mjs", ...).

export const QUOTE = "Volunteers who, on a casual basis, assist from time to time.";

const DEFAULT_PAGES = [ "Cover page of the document.", QUOTE, "The schedule continues for one year." ];

// How far down the frame a match sits, so a test can tell whether it was scrolled into view.
export const MATCH_TOP = 500;

// As PDF.js's viewer exports it
const FindState = { FOUND: 0, NOT_FOUND: 1, WRAPPED: 2, PENDING: 3 };

type Listener = (event: never) => void;

interface FakeDocument {
  numPages: number;
}

function createDefaults() {
  return {
    pages: [ ...DEFAULT_PAGES ],
    // What opening the document fails with, if anything.
    openFailure: undefined as Error | undefined,
    // Package folders the build did not publish, as asset names.
    unpublished: [] as string[],
    // Set to hold every search back until releaseSearch() is called.
    holdSearch: false,
    releaseSearch: undefined as (() => void) | undefined,
    // Set to hold back the first page being shown until releasePages() is called.
    holdPages: false,
    releasePages: undefined as (() => void) | undefined,
    // Every document opened, every worker handed to an open, every search, and every place the
    // reader was put back at.
    opened: 0,
    openOptions: [] as object[],
    workers: [] as unknown[],
    searches: [] as string[],
    hashes: [] as string[],
    viewers: [] as FakePDFViewer[],
    destroyedTasks: 0,
  };
}

export const fakePdf = {
  ...createDefaults(),
  reset(): void {
    Object.assign(fakePdf, createDefaults());
  },
};

// A failure as PDF.js reports it: an Error named after the kind of failure.
export function createPdfjsError(name: string, message: string): Error {
  const error = new Error(message);
  error.name = name;
  return error;
}

// Where the build published a package folder, or nothing for one it did not publish, as assetManager
// answers. Loaded through vi.mock of assetManager, whose assets.json is fetched once per test file.
export function getAssetURL(url: string): Promise<string> {
  const name = url.replace(/^asset:/, "");
  if (fakePdf.unpublished.includes(name)) {
    return Promise.resolve("");
  }
  return Promise.resolve(`/libs/iap/resources/${name.replace(".", "/")}.0123456789abcdef.data/`);
}

// A browser worker, which jsdom does not have.
export class FakeWorker {
  constructor(readonly url: URL, readonly options: WorkerOptions) {}
}

// What the server answers when the viewer fetches the file. A new response per call, since a body
// can only be read once.
export function stubFetch(status = 200): void {
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(new ArrayBuffer(8), { status }))));
}

class FakeEventBus {
  readonly #listeners = new Map<string, Listener[]>();

  on(name: string, listener: Listener): void {
    this.#listeners.set(name, [ ...this.#listeners.get(name) ?? [], listener ]);
  }

  dispatch(name: string, event: object = {}): void {
    for (const listener of this.#listeners.get(name) ?? []) {
      (listener as (event: object) => void)(event);
    }
  }
}

class FakePDFViewer {
  readonly eventBus: FakeEventBus;
  readonly container: HTMLElement;
  readonly removePageBorders: boolean;
  readonly abortSignal: AbortSignal | undefined;
  findController: FakePDFFindController | null = null;
  doc: FakeDocument | null = null;
  #page = 1;
  #scale: string | number = "auto";

  constructor({ container, eventBus, removePageBorders = false, abortSignal }: {
    container: HTMLElement;
    eventBus: FakeEventBus;
    removePageBorders?: boolean;
    abortSignal?: AbortSignal;
  }) {
    this.container = container;
    this.eventBus = eventBus;
    this.removePageBorders = removePageBorders;
    this.abortSignal = abortSignal;
    fakePdf.viewers.push(this);
  }

  setDocument(doc: FakeDocument | null): void {
    this.doc = doc;
    this.findController?.setDocument(doc);
    if (doc === null) {
      return;
    }
    const init = () => this.eventBus.dispatch("pagesinit");
    if (fakePdf.holdPages) {
      fakePdf.releasePages = init;
    } else {
      queueMicrotask(init);
    }
  }

  get currentPageNumber(): number {
    return this.#page;
  }

  set currentPageNumber(page: number) {
    if (page === this.#page) {
      return;
    }
    this.#page = page;
    this.eventBus.dispatch("pagechanging", { pageNumber: page });
    this.#updateViewArea();
  }

  get currentScaleValue(): string {
    return String(this.#scale);
  }

  set currentScaleValue(scale: string | number) {
    this.#scale = scale;
    this.#updateViewArea();
    // The pages' text is drawn again at the new size, marks included.
    this.findController?.redrawMatch();
  }

  increaseScale(): void {
    this.currentScaleValue = this.#numericScale() * 2;
  }

  decreaseScale(): void {
    this.currentScaleValue = this.#numericScale() / 2;
  }

  #numericScale(): number {
    return typeof this.#scale === "number" ? this.#scale : 1;
  }

  #updateViewArea(): void {
    this.eventBus.dispatch("updateviewarea", {
      location: { pdfOpenParams: `#page=${this.#page}&zoom=${this.#scale},0,0` },
    });
  }
}

class FakePDFLinkService {
  pdfViewer: FakePDFViewer | null = null;
  pdfDocument: FakeDocument | null = null;

  setViewer(viewer: FakePDFViewer): void {
    this.pdfViewer = viewer;
  }

  setDocument(doc: FakeDocument | null): void {
    this.pdfDocument = doc;
  }

  get page(): number {
    return this.pdfDocument !== null && this.pdfViewer !== null ? this.pdfViewer.currentPageNumber : 1;
  }

  set page(page: number) {
    if (this.pdfDocument !== null && this.pdfViewer !== null) {
      this.pdfViewer.currentPageNumber = page;
    }
  }

  setHash(hash: string): void {
    fakePdf.hashes.push(hash);
    const params = new URLSearchParams(hash);
    const zoom = params.get("zoom")?.split(",")[0] ?? "auto";
    if (this.pdfViewer !== null) {
      this.pdfViewer.currentScaleValue = Number.isNaN(Number(zoom)) ? zoom : Number(zoom);
      this.pdfViewer.currentPageNumber = Number(params.get("page"));
    }
  }
}

class FakePDFFindController {
  _linkService: FakePDFLinkService;
  _eventBus: FakeEventBus;
  _selected = { pageIdx: -1, matchIdx: -1 };
  _scrollMatches = false;
  #doc: FakeDocument | null = null;

  constructor({ linkService, eventBus }: { linkService: FakePDFLinkService; eventBus: FakeEventBus }) {
    this._linkService = linkService;
    this._eventBus = eventBus;
    eventBus.on("find", ({ query }: { query: string }) => {
      void this.#find(query);
    });
    eventBus.on("updatetextlayermatches", () => {
      this.redrawMatch();
    });
  }

  get selected() {
    return this._selected;
  }

  setDocument(doc: FakeDocument | null): void {
    this.#doc = doc;
  }

  // Overridden by PdfViewer's search.
  match(_query: string, _pageContent: string, _pageIndex: number): { index: number; length: number }[] {
    return [];
  }

  // Overridden by PdfViewer's search.
  scrollMatchIntoView(_params: { element: HTMLElement; pageIndex: number; matchIndex: number }): void {
    // Nothing to scroll in the stand-in
  }

  // Drawing the text of the match's page asks for the match to be scrolled into view, each time.
  redrawMatch(): void {
    const { pageIdx, matchIdx } = this._selected;
    if (pageIdx < 0) {
      return;
    }
    const element = document.createElement("span");
    element.getBoundingClientRect = () => new DOMRect(0, MATCH_TOP, 100, 20);
    this.scrollMatchIntoView({ element, pageIndex: pageIdx, matchIndex: matchIdx });
  }

  // From the page shown onwards, wrapping round, as PDF.js does.
  async #find(query: string): Promise<void> {
    fakePdf.searches.push(query);
    this._eventBus.dispatch("updatefindcontrolstate", { state: FindState.PENDING });
    if (fakePdf.holdSearch) {
      await new Promise<void>(resolve => {
        fakePdf.releaseSearch = resolve;
      });
    }
    await Promise.resolve();
    const pageCount = this.#doc?.numPages ?? 0;
    const start = this._linkService.page - 1;
    for (let step = 0; step < pageCount; step++) {
      const pageIdx = (start + step) % pageCount;
      if (this.match(query, fakePdf.pages[pageIdx] ?? "", pageIdx).length > 0) {
        this._selected = { pageIdx, matchIdx: 0 };
        const state = pageIdx < start ? FindState.WRAPPED : FindState.FOUND;
        this._eventBus.dispatch("updatefindcontrolstate", { state });
        this._scrollMatches = true;
        this._linkService.page = pageIdx + 1;
        this.redrawMatch();
        return;
      }
    }
    this._eventBus.dispatch("updatefindcontrolstate", { state: FindState.NOT_FOUND });
  }
}

export class FakePDFWorker {
  readonly port: unknown;

  constructor({ port }: { port?: unknown } = {}) {
    this.port = port;
  }

  static create(params: { port?: unknown }): FakePDFWorker {
    return new FakePDFWorker(params);
  }
}

// What vi.mock("pdfjs-dist", ...) hands the viewer in place of the real module.
export function createPdfjsModule() {
  return {
    GlobalWorkerOptions: { workerSrc: "" },
    PDFWorker: FakePDFWorker,
    getDocument: (options: { data: ArrayBuffer; worker?: unknown }) => {
      fakePdf.openOptions.push(options);
      fakePdf.workers.push(options.worker);
      return {
        promise: fakePdf.openFailure !== undefined
          ? Promise.reject(fakePdf.openFailure)
          : Promise.resolve().then((): FakeDocument => {
            fakePdf.opened++;
            return { numPages: fakePdf.pages.length };
          }),
        destroy: () => {
          fakePdf.destroyedTasks++;
          return Promise.resolve();
        },
      };
    },
  };
}

// What vi.mock("pdfjs-dist/web/pdf_viewer.mjs", ...) hands the viewer in place of the real module.
export function createViewerModule() {
  return {
    EventBus: FakeEventBus,
    FindState,
    PDFLinkService: FakePDFLinkService,
    PDFViewer: FakePDFViewer,
    PDFFindController: FakePDFFindController,
  };
}
