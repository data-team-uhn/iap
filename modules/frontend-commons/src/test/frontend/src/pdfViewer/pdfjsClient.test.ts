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
import { loadPdfjs } from "@iap/frontend-commons/pdfViewer/pdfjsClient";

import { FakeWorker } from "./pdfjs.fixture";

// The first import of PDF.js fails, as a chunk the network dropped would.
const imports = vi.hoisted(() => ({ failing: true }));

vi.mock("pdfjs-dist", async () => {
  if (imports.failing) {
    imports.failing = false;
    throw new Error("Failed to fetch dynamically imported module");
  }
  return (await import("./pdfjs.fixture")).createPdfjsModule();
});
vi.mock("pdfjs-dist/web/pdf_viewer.mjs", async () => (await import("./pdfjs.fixture")).createViewerModule());
vi.mock("pdfjs-dist/web/pdf_viewer.css", () => ({}));
vi.mock("@iap/frontend-commons/assetManager", async () => ({
  getAssetURL: (await import("./pdfjs.fixture")).getAssetURL,
}));

describe("loadPdfjs", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("imports PDF.js once for documents opened together, and again after failing to", async () => {
    vi.stubGlobal("Worker", FakeWorker);

    // Vitest wraps what a mock's factory throws in an error of its own.
    await expect(loadPdfjs()).rejects.toThrow();
    const [ one, other ] = await Promise.all([ loadPdfjs(), loadPdfjs() ]);

    expect(other.pdfjs).toBe(one.pdfjs);
    expect(other.components).toBe(one.components);
  });
});
