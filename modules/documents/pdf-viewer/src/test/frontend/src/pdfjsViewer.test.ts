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
import { afterEach, describe, expect, it, vi } from "vitest";

const core = vi.hoisted(() => ({ pdfjs: { name: "PDF.js" }, worker: { name: "worker" }, failed: new Promise<never>(() => undefined) }));

// Set to make the next import of the viewer fail, as a chunk the network dropped would
const imports = vi.hoisted(() => ({ failing: false }));

const fakeViewer = vi.hoisted(() => () => {
  if (imports.failing) {
    imports.failing = false;
    throw new Error("Failed to fetch dynamically imported module");
  }
  return { PDFViewer: { name: "PDFViewer" } };
});

// What each package folder was published as; an empty name was not published
const published = vi.hoisted(() => new Map([
  [ "asset:pdfjs-dist.standard_fonts", "/libs/iap/resources/pdfjs-dist.standard_fonts.1.data" ],
  [ "asset:pdfjs-dist.wasm", "" ],
  [ "asset:pdfjs-dist.cmaps", "/libs/iap/resources/pdfjs-dist.cmaps.2.data" ],
  [ "asset:pdfjs-dist.iccs", "/libs/iap/resources/pdfjs-dist.iccs.3.data" ],
]));

vi.mock("@iap/frontend-commons/pdfjsClient", () => ({ loadPdfjs: () => Promise.resolve(core) }));
vi.mock("@iap/frontend-commons/assetManager", () => ({
  getAssetURL: (name: string) => Promise.resolve(published.get(name) ?? ""),
}));
vi.mock("pdfjs-dist/web/pdf_viewer.mjs", fakeViewer);
vi.mock("pdfjs-dist/web/pdf_viewer.css", () => ({}));

afterEach(() => {
  delete (globalThis as { pdfjsLib?: unknown }).pdfjsLib;
});

describe("loadPdfViewer", () => {
  it("hands the viewer PDF.js as the library it reads, with PDF.js's own worker", async () => {
    const { loadPdfViewer } = await import("@iap/documents-pdf-viewer/pdfjsViewer");

    const loaded = await loadPdfViewer();

    expect((globalThis as { pdfjsLib?: unknown }).pdfjsLib).toBe(core.pdfjs);
    expect(loaded.components.PDFViewer).toBeDefined();
    expect(loaded.pdfjs).toBe(core.pdfjs);
    expect(loaded.worker).toBe(core.worker);
    expect(loaded.failed).toBe(core.failed);
  });

  it("names where a document's fonts, decoders and character maps come from, leaving out what was not published", async () => {
    const { loadPdfViewer } = await import("@iap/documents-pdf-viewer/pdfjsViewer");

    const { dataUrls } = await loadPdfViewer();

    expect(dataUrls).toEqual({
      standardFontDataUrl: "/libs/iap/resources/pdfjs-dist.standard_fonts.1.data",
      cMapUrl: "/libs/iap/resources/pdfjs-dist.cmaps.2.data",
      iccUrl: "/libs/iap/resources/pdfjs-dist.iccs.3.data",
    });
  });

  it("imports the viewer once for documents opened together, and again after failing to", async () => {
    vi.resetModules();
    imports.failing = true;
    // Registered again, since Vitest keeps what a factory returned until it is
    vi.doMock("pdfjs-dist/web/pdf_viewer.mjs", fakeViewer);
    const { loadPdfViewer } = await import("@iap/documents-pdf-viewer/pdfjsViewer");

    // Vitest wraps what a mock's factory throws in an error of its own
    await expect(loadPdfViewer()).rejects.toThrow();
    const [ one, other ] = await Promise.all([ loadPdfViewer(), loadPdfViewer() ]);

    expect(other.components).toBe(one.components);
  });
});
