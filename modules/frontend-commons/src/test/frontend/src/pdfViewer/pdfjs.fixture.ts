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
// other and with PdfViewer. The document gives each page's text, events go on the bus, the link
// service moves the viewer, and the viewer draws each page's text layer as it is shown. Loaded
// through vi.mock("pdfjs-dist", ...) and vi.mock("pdfjs-dist/web/pdf_viewer.mjs", ...).

export const QUOTE = "Volunteers who, on a casual basis, assist from time to time.";

const DEFAULT_PAGES = [ "Cover page of the document.", QUOTE, "The schedule continues for one year." ];

// How far down its page every passage sits, so a test can tell whether it was scrolled into view.
export const MATCH_TOP = 500;

const PAGE_WIDTH = 600;
const PAGE_HEIGHT = 800;

// As PDF.js's viewer exports it
const FindState = { FOUND: 0, NOT_FOUND: 1, WRAPPED: 2, PENDING: 3 };

type Listener = (event: never) => void;

function createDefaults() {
  return {
    pages: [ ...DEFAULT_PAGES ],
    // What opening the document fails with, if anything.
    openFailure: undefined as Error | undefined,
    // Package folders the build did not publish, as asset names.
    unpublished: [] as string[],
    // Set to hold back reading the pages' text until releaseText() is called.
    holdText: false,
    releaseText: undefined as (() => void) | undefined,
    // Set to hold back the first page being shown until releasePages() is called.
    holdPages: false,
    releasePages: undefined as (() => void) | undefined,
    // Pages whose text PDF.js fails to read, by number.
    unreadable: [] as number[],
    // Set to draw only the page shown, as PDF.js does with a long document, rather than every page.
    drawShownPageOnly: false,
    // Every document opened, every worker handed to an open, every page whose text was read, and
    // every place the reader was put back at.
    opened: 0,
    openOptions: [] as object[],
    workers: [] as unknown[],
    textReads: [] as number[],
    // How many pages PDF.js's search has gone through for the last query, every page when undefined.
    pagesSearched: undefined as number | undefined,
    // Set to hold a new search pending until releaseSearch() is called, keeping the last search's matches
    // meanwhile.
    holdSearch: false,
    releaseSearch: undefined as (() => void) | undefined,
    // Every search asked of PDF.js, as its find events.
    finds: [] as { type: string; query: string; findPrevious: boolean }[],
    hashes: [] as string[],
    viewers: [] as FakePDFViewer[],
    // Every zoom the viewer was set to, in order.
    scales: [] as (string | number)[],
    destroyedTasks: 0,
  };
}

export const fakePdf = {
  ...createDefaults(),
  reset(): void {
    Object.assign(fakePdf, createDefaults());
    delete (Range.prototype as Partial<Range>).getClientRects;
  },
};

// jsdom lays nothing out. Here every passage is one line MATCH_TOP down its page. A mark sits where
// its style puts it on its page, so scrolling to a mark scrolls by MATCH_TOP. Anything else is as wide
// as given, which decides whether the viewer has room beside the document. Undone by reset().
export function stubLayout({ elementWidth = 0 } = {}): void {
  Range.prototype.getClientRects = () => [ new DOMRect(0, MATCH_TOP, 100, 20) ] as unknown as DOMRectList;
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    if (this.dataset.markId === undefined) {
      return new DOMRect(0, 0, elementWidth, 0);
    }
    const share = (value: string) => Number.parseFloat(value) / 100;
    const { left, top, width, height } = this.style;
    return new DOMRect(
      share(left) * PAGE_WIDTH,
      share(top) * PAGE_HEIGHT,
      share(width) * PAGE_WIDTH,
      share(height) * PAGE_HEIGHT,
    );
  });
}

// The class names of the marks drawn in the frame, by page number.
export function getDrawnMarks(frame: HTMLElement): Record<number, string[]> {
  const drawn: Record<number, string[]> = {};
  for (const mark of frame.querySelectorAll<HTMLElement>("[data-mark-id]")) {
    const page = Number(mark.closest<HTMLElement>("[data-page-number]")?.dataset.pageNumber);
    drawn[page] = [ ...drawn[page] ?? [], mark.className ];
  }
  return drawn;
}

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

// A document whose pages' text is fakePdf.pages, one text item per line, inside marked content.
class FakeDocument {
  readonly numPages = fakePdf.pages.length;
  #held: Promise<void> | undefined;

  getPage(pageNumber: number) {
    return Promise.resolve({
      getTextContent: async () => {
        await this.#hold();
        fakePdf.textReads.push(pageNumber);
        if (fakePdf.unreadable.includes(pageNumber)) {
          throw createPdfjsError("UnknownErrorException", "Bad text content");
        }
        const lines = (fakePdf.pages[pageNumber - 1] ?? "").split("\n");
        // Marked content, which says how the text is structured, holds none of it.
        const items = lines.map((str, index) => ({ str, hasEOL: index < lines.length - 1 }));
        return { items: [ { type: "beginMarkedContent" }, ...items, { type: "endMarkedContent" } ] };
      },
    });
  }

  #hold(): Promise<void> {
    if (!fakePdf.holdText) {
      return Promise.resolve();
    }
    this.#held ??= new Promise(resolve => {
      fakePdf.releaseText = resolve;
    });
    return this.#held;
  }
}

class FakeEventBus {
  readonly #listeners = new Map<string, Listener[]>();

  on(name: string, listener: Listener, options?: { signal?: AbortSignal }): void {
    this.#listeners.set(name, [ ...this.#listeners.get(name) ?? [], listener ]);
    options?.signal?.addEventListener("abort", () => {
      this.#listeners.set(name, (this.#listeners.get(name) ?? []).filter(other => other !== listener));
    });
  }

  dispatch(name: string, event: object = {}): void {
    for (const listener of this.#listeners.get(name) ?? []) {
      (listener as (event: object) => void)(event);
    }
  }
}

// A page as the viewer holds it: its text layer, once drawn.
interface FakePageView {
  div: HTMLDivElement;
  textLayer: { div: HTMLDivElement } | null;
}

class FakePDFViewer {
  readonly eventBus: FakeEventBus;
  readonly container: HTMLElement;
  readonly removePageBorders: boolean;
  readonly abortSignal: AbortSignal | undefined;
  doc: FakeDocument | null = null;
  findController: FakePDFFindController | null = null;
  #pageViews: FakePageView[] = [];
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
    this.#pageViews.forEach(pageView => pageView.div.remove());
    this.#pageViews = [];
    if (doc === null) {
      return;
    }
    const holder = this.container.firstElementChild ?? this.container;
    this.#pageViews = Array.from({ length: doc.numPages }, (_, index) => {
      const div = document.createElement("div");
      div.dataset.pageNumber = String(index + 1);
      holder.append(div);
      return { div, textLayer: null };
    });
    const init = () => {
      this.eventBus.dispatch("pagesinit");
      this.#drawShown();
    };
    if (fakePdf.holdPages) {
      fakePdf.releasePages = init;
    } else {
      queueMicrotask(init);
    }
  }

  get pagesCount(): number {
    return this.#pageViews.length;
  }

  getPageView(index: number): FakePageView | undefined {
    return this.#pageViews[index];
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
    this.#drawShown();
  }

  get currentScaleValue(): string {
    return String(this.#scale);
  }

  set currentScaleValue(scale: string | number) {
    fakePdf.scales.push(scale);
    this.#scale = scale;
    this.eventBus.dispatch("scalechanging", { scale: this.#numericScale() });
    this.#updateViewArea();
    this.findController?.redrawMatch();
    // PDF.js keeps a page's text layer when zooming, and says it is drawn again.
    this.#pageViews.forEach((pageView, index) => {
      if (pageView.textLayer !== null) {
        this.eventBus.dispatch("textlayerrendered", { source: pageView, pageNumber: index + 1 });
      }
    });
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

  // Every page not yet drawn, or only the one shown, after the change that shows it, as PDF.js does.
  #drawShown(): void {
    queueMicrotask(() => {
      this.#pageViews.forEach((pageView, index) => {
        if (pageView.textLayer === null && (!fakePdf.drawShownPageOnly || index + 1 === this.#page)) {
          this.#drawText(pageView, index + 1);
        }
      });
    });
  }

  #drawText(pageView: FakePageView, pageNumber: number): void {
    const div = document.createElement("div");
    div.className = "textLayer";
    for (const line of (fakePdf.pages[pageNumber - 1] ?? "").split("\n")) {
      const span = document.createElement("span");
      span.textContent = line;
      div.append(span, document.createElement("br"));
    }
    div.getBoundingClientRect = () => new DOMRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
    pageView.div.append(div);
    pageView.textLayer = { div };
    this.eventBus.dispatch("textlayerrendered", { source: pageView, pageNumber });
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

  get page(): number {
    return this.pdfViewer?.currentPageNumber ?? 1;
  }

  set page(page: number) {
    if (this.pdfViewer !== null) {
      this.pdfViewer.currentPageNumber = page;
    }
  }

  setViewer(viewer: FakePDFViewer): void {
    this.pdfViewer = viewer;
  }

  setDocument(doc: FakeDocument | null): void {
    this.pdfDocument = doc;
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

interface FindEvent {
  type: string;
  query: string;
  findPrevious: boolean;
}

// PDF.js's search: every match of the query in the pages' text, ignoring case, the first from the page
// shown, then the next or previous one each time it is asked again, wrapping round. It says it is
// pending, then how it went, sets the link service's page to the match, and asks for it to be
// scrolled into view.
class FakePDFFindController {
  _linkService: FakePDFLinkService;
  _eventBus: FakeEventBus;
  _selected = { pageIdx: -1, matchIdx: -1 };
  _scrollMatches = false;
  _dirtyMatch = false;
  #matches: { pageIdx: number; matchIdx: number }[] = [];
  #position = -1;

  constructor({ linkService, eventBus }: { linkService: FakePDFLinkService; eventBus: FakeEventBus }) {
    this._linkService = linkService;
    this._eventBus = eventBus;
    eventBus.on("find", (event: FindEvent) => {
      fakePdf.finds.push({ type: event.type, query: event.query, findPrevious: event.findPrevious });
      this.#find(event);
    });
  }

  // The matches on each page searched so far, by page: none before the search has searched any.
  get pageMatches(): number[][] | undefined {
    const searched = fakePdf.pagesSearched ?? fakePdf.pages.length;
    if (searched === 0) {
      return undefined;
    }
    return fakePdf.pages.slice(0, searched)
      .map((_, pageIdx) => this.#matches.filter(match => match.pageIdx === pageIdx).map(({ matchIdx }) => matchIdx));
  }

  // Overridden by PdfViewer's search.
  scrollMatchIntoView(_params: { element: HTMLElement; pageIndex: number; matchIndex: number }): void {
    // Nothing to scroll in the stand-in
  }

  #find(event: FindEvent): void {
    const { type, query, findPrevious } = event;
    const count = (current: number) => ({ matchesCount: { current, total: this.#matches.length } });
    this._eventBus.dispatch("updatefindcontrolstate", { state: FindState.PENDING, ...count(0) });
    if (type === "" && query !== "" && fakePdf.holdSearch) {
      this._dirtyMatch = true;
      fakePdf.releaseSearch = () => {
        fakePdf.holdSearch = false;
        this._dirtyMatch = false;
        this.#find(event);
      };
      return;
    }
    if (type === "") {
      this.#matches = query === ""
        ? []
        : fakePdf.pages.flatMap((text, pageIdx) => text.toLowerCase().split(query.toLowerCase())
          .slice(1)
          .map((_, matchIdx) => ({ pageIdx, matchIdx })));
      if (query === "") {
        return;
      }
      const from = this._linkService.page - 1;
      const next = this.#matches.findIndex(match => match.pageIdx >= from);
      this.#position = next < 0 ? 0 : next;
    } else {
      this.#position += findPrevious ? -1 : 1;
    }
    if (this.#matches.length === 0) {
      this._eventBus.dispatch("updatefindcontrolstate", { state: FindState.NOT_FOUND, ...count(0) });
      return;
    }
    const wrapped = this.#position < 0 || this.#position >= this.#matches.length;
    this.#position = (this.#position + this.#matches.length) % this.#matches.length;
    this._selected = this.#matches[this.#position];
    this._eventBus.dispatch("updatefindmatchescount", count(this.#position + 1));
    const state = wrapped ? FindState.WRAPPED : FindState.FOUND;
    this._eventBus.dispatch("updatefindcontrolstate", { state, ...count(this.#position + 1) });
    this._scrollMatches = true;
    this._linkService.page = this._selected.pageIdx + 1;
    this.redrawMatch();
  }

  // Drawing the text of the match's page, as zooming does, asks for the match to be scrolled into view,
  // each time.
  redrawMatch(): void {
    const { pageIdx, matchIdx } = this._selected;
    if (pageIdx < 0) {
      return;
    }
    const element = document.createElement("span");
    element.getBoundingClientRect = () => new DOMRect(0, MATCH_TOP, 100, 20);
    this.scrollMatchIntoView({ element, pageIndex: pageIdx, matchIndex: matchIdx });
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
          : Promise.resolve().then(() => {
            fakePdf.opened++;
            return new FakeDocument();
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
    PDFFindController: FakePDFFindController,
    PDFLinkService: FakePDFLinkService,
    PDFViewer: FakePDFViewer,
  };
}
