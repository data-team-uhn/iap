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
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import PdfViewer from "@iap/submissions/pdfViewer";
import type { ProvenancePassage } from "@iap/submissions/provenance";

const PDF = "/Submissions/demo-1/proposal/1/file/file.pdf";
const QUOTE = "Participants receive pembrolizumab 200 mg IV every three weeks.";

function passage(overrides: Partial<ProvenancePassage> = {}): ProvenancePassage {
  return {
    quote: QUOTE,
    cite: "p. 2 · Study treatment",
    source: `${PDF}#page=2`,
    ...overrides,
  };
}

function textItem(str: string, page: number) {
  return {
    str,
    transform: [ 12, 0, 0, 12, 72, 700 ],
    width: 400,
    height: 12,
    hasEOL: true,
    page,
  };
}

const pages = new Map<number, string>([
  [ 1, "Cover page of the protocol." ],
  [ 2, QUOTE ],
  [ 3, "The schedule continues for one year." ],
]);

function resetPages() {
  pages.set(1, "Cover page of the protocol.");
  pages.set(2, QUOTE);
  pages.set(3, "The schedule continues for one year.");
}

function viewport(scale: number) {
  return {
    width: 600 * scale,
    height: 800 * scale,
    convertToViewportRectangle: (rect: number[]) => {
      const [ x1 = 0, y1 = 0, x2 = 0, y2 = 0 ] = rect;
      return [ x1 * scale, (800 - y2) * scale, x2 * scale, (800 - y1) * scale ];
    },
  };
}

function fakePage(pageNumber: number) {
  const text = pages.get(pageNumber) ?? "";
  return {
    getViewport: ({ scale }: { scale: number }) => viewport(scale),
    getTextContent: () => Promise.resolve({ items: text.length === 0 ? [] : [ textItem(text, pageNumber) ] }),
    render: () => ({ promise: Promise.resolve(), cancel: () => undefined }),
  };
}

let failOpen = false;

vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: () => ({
    destroy: () => undefined,
    promise: failOpen
      ? Promise.reject(new Error("not a pdf"))
      : Promise.resolve({
        numPages: 3,
        getPage: (pageNumber: number) => Promise.resolve(fakePage(pageNumber)),
        destroy: () => Promise.resolve(),
      }),
  }),
}));

function show(overrides: Partial<ProvenancePassage> = {}) {
  const onClose = vi.fn();
  render(<PdfViewer passage={passage(overrides)} onClose={onClose} />);
  return onClose;
}

describe("PdfViewer", () => {
  afterEach(() => {
    failOpen = false;
    resetPages();
  });

  it("opens the cited page and highlights the quote", async () => {
    show();

    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(await screen.findByTestId("citation-highlight")).toBeInTheDocument();
    expect(screen.queryByText(/not in the PDF text/)).not.toBeInTheDocument();
  });

  it("looks through the other pages when the cited one does not have the quote", async () => {
    pages.set(2, "A table of contents.");
    pages.set(3, QUOTE);
    show();

    expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
    expect(await screen.findByTestId("citation-highlight")).toBeInTheDocument();
  });

  it("says so when the quote is not in the PDF text", async () => {
    show({ quote: "This sentence is not anywhere in the document at all." });

    expect(await screen.findByText(/not in the PDF text/)).toBeInTheDocument();
    expect(screen.queryByTestId("citation-highlight")).not.toBeInTheDocument();
  });

  it("moves between pages and says when the quote is not on the one shown", async () => {
    show();
    await screen.findByTestId("citation-highlight");

    await userEvent.click(screen.getByRole("button", { name: "Next page" }));

    expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
    expect(await screen.findByText(/not on this page/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "Previous page" }));

    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(await screen.findByTestId("citation-highlight")).toBeInTheDocument();
  });

  it("says when the protocol cannot be opened", async () => {
    failOpen = true;
    show();

    expect(await screen.findByText(/could not be opened/)).toBeInTheDocument();
  });

  it("closes when asked", async () => {
    const onClose = show({ cite: undefined, source: PDF });

    await userEvent.click(screen.getByRole("button", { name: "close" }));

    expect(onClose).toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: "Protocol" })).toBeInTheDocument();
  });
});
