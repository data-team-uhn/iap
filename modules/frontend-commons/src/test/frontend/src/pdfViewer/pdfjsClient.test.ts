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
import { describe, expect, it, vi } from "vitest";

import { loadPdfjs } from "@iap/frontend-commons/pdfViewer/pdfjsClient";

const created = vi.hoisted(() => ({ ports: [] as unknown[] }));

vi.mock("pdfjs-dist", () => ({
  PDFWorker: {
    create: ({ port }: { port: unknown }) => {
      created.ports.push(port);
      return { port };
    },
  },
}));

class FakeWorker {
  constructor(readonly url: URL, readonly options: WorkerOptions) {}
}
vi.stubGlobal("Worker", FakeWorker);

describe("loadPdfjs", () => {
  it("starts PDF.js's worker as a module, and gives it with the library", async () => {
    const { pdfjs, worker } = await loadPdfjs();

    expect(pdfjs.PDFWorker).toBeDefined();
    const port = created.ports[0] as FakeWorker;
    expect(worker).toEqual({ port });
    expect(port.url.pathname).toMatch(/pdfjs-dist\/build\/pdf\.worker\.min\.mjs$/);
    expect(port.options).toEqual({ type: "module" });
  });

  it("starts the worker once, for every document to share", async () => {
    const first = await loadPdfjs();
    const second = await loadPdfjs();

    expect(second.worker).toBe(first.worker);
    expect(created.ports).toHaveLength(1);
  });
});
