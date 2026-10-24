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
import { renderHook, waitFor } from "@testing-library/react";

import { usePdfDocument } from "@iap/frontend-commons/pdfViewer/usePdfDocument";

import { fakePdf, FakeWorker, stubFetch } from "./pdfjs.fixture";

vi.mock("pdfjs-dist", async () => (await import("./pdfjs.fixture")).createPdfjsModule());
vi.mock("pdfjs-dist/web/pdf_viewer.mjs", async () => (await import("./pdfjs.fixture")).createViewerModule());
vi.mock("pdfjs-dist/web/pdf_viewer.css", () => ({}));
vi.mock("@iap/frontend-commons/assetManager", async () => ({
  getAssetURL: (await import("./pdfjs.fixture")).getAssetURL,
}));

const URL = "/content/pdfs/memo.pdf";

describe("usePdfDocument", () => {
  beforeEach(() => {
    stubFetch();
    vi.stubGlobal("Worker", FakeWorker);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fakePdf.reset();
  });

  it("opens nothing without a URL", () => {
    const { result } = renderHook(() => usePdfDocument(undefined));

    expect(result.current).toMatchObject({ pdf: undefined, openError: undefined });
    expect(fetch).not.toHaveBeenCalled();
  });

  it("gives no document while one dropped is opened again, rather than the one dropped", async () => {
    const initialProps: { url: string | undefined } = { url: URL };
    const { result, rerender } = renderHook(({ url }) => usePdfDocument(url), { initialProps });
    await waitFor(() => expect(result.current.pdf).toBeDefined());
    const dropped = result.current.pdf;

    rerender({ url: undefined });
    rerender({ url: URL });

    expect(result.current.pdf).toBeUndefined();
    await waitFor(() => expect(result.current.pdf).toBeDefined());
    expect(result.current.pdf).not.toBe(dropped);
  });
});
