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

// The viewer, its stylesheet and the folders it fetches from are loaded alongside, and play no part here
vi.mock("pdfjs-dist/web/pdf_viewer.mjs", () => ({}));
vi.mock("pdfjs-dist/web/pdf_viewer.css", () => ({}));
vi.mock("@iap/frontend-commons/assetManager", () => ({ getAssetURL: () => Promise.resolve("") }));

class FakeWorker extends EventTarget {
  constructor(readonly url: URL, readonly options: WorkerOptions) {
    super();
  }
}
vi.stubGlobal("Worker", FakeWorker);

// A fresh copy of the module, with no worker started yet
async function loadFreshClient() {
  vi.resetModules();
  const client = await import("@iap/frontend-commons/pdfViewer/pdfjsClient");
  const loaded = await client.loadPdfjs();
  return { client, loaded, port: created.ports.at(-1) as FakeWorker };
}

// Whether the promise has settled yet, without waiting for it to
async function isSettled(promise: Promise<unknown>): Promise<boolean> {
  const pending = Symbol("pending");
  return await Promise.race([ promise.then(() => true, () => true), Promise.resolve(pending) ]) !== pending;
}

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

  it("reports a worker whose script never ran, to every later caller", async () => {
    const { client, loaded, port } = await loadFreshClient();

    port.dispatchEvent(new Event("error"));

    await expect(loaded.failed).rejects.toBeInstanceOf(client.PdfWorkerFailure);
    const later = await client.loadPdfjs();
    expect(later.failed).toBe(loaded.failed);
    expect(later.worker).toBe(loaded.worker);
  });

  it("keeps a worker that threw while running, since it still answers", async () => {
    const { loaded, port } = await loadFreshClient();

    port.dispatchEvent(new ErrorEvent("error", { message: "boom" }));

    expect(await isSettled(loaded.failed)).toBe(false);
  });
});
