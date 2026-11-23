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

// Tests loading PDF.js and its shared worker, against a fake pdfjs-dist and a fake browser Worker. The
// worker lives as long as the module, so a test needing a new one loads a fresh copy of the module.

import { describe, expect, it, vi } from "vitest";

import { loadPdfjs } from "@iap/frontend-commons/pdfjsClient";

// Every port a PDF.js worker was created with.
const created = vi.hoisted(() => ({ ports: [] as unknown[] }));

// Set to make the next import of PDF.js throw, as when the network drops its chunk.
const imports = vi.hoisted(() => ({ failing: false }));

const fakePdfjs = vi.hoisted(() => () => {
  if (imports.failing) {
    imports.failing = false;
    throw new Error("Failed to fetch dynamically imported module");
  }
  return {
    PDFWorker: {
      create: ({ port }: { port: unknown }) => {
        created.ports.push(port);
        return { port };
      },
    },
  };
});

vi.mock("pdfjs-dist", fakePdfjs);

class FakeWorker extends EventTarget {
  constructor(readonly url: URL, readonly options: WorkerOptions) {
    super();
  }
}
vi.stubGlobal("Worker", FakeWorker);

// Loads PDF.js through a fresh copy of the module, which starts a worker of its own.
async function loadFreshClient() {
  vi.resetModules();
  const client = await import("@iap/frontend-commons/pdfjsClient");
  const loaded = await client.loadPdfjs();
  return { client, loaded, port: created.ports.at(-1) as FakeWorker };
}

// Whether the promise has settled yet, without waiting for it to.
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

  it("imports PDF.js once for documents opened together, and again after failing to", async () => {
    vi.resetModules();
    imports.failing = true;
    // vi.resetModules() keeps what a vi.mock factory returned, so the factory is registered again to run.
    vi.doMock("pdfjs-dist", fakePdfjs);
    const client = await import("@iap/frontend-commons/pdfjsClient");

    // Vitest wraps what a mock's factory throws in an error of its own.
    await expect(client.loadPdfjs()).rejects.toThrow();
    const [ one, other ] = await Promise.all([ client.loadPdfjs(), client.loadPdfjs() ]);

    expect(other.pdfjs).toBe(one.pdfjs);
  });

  it("keeps a worker that threw while running, since it still answers", async () => {
    const { loaded, port } = await loadFreshClient();

    port.dispatchEvent(new ErrorEvent("error", { message: "boom" }));

    expect(await isSettled(loaded.failed)).toBe(false);
  });
});
