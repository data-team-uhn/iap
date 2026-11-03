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

import { checkPdf } from "@iap/frontend-commons/fileValidation/pdfCheck";

// The tests stand in for PDF.js. A stand-in PDF opens when its first byte is 0x25, and its next two
// bytes carry the page count, low byte first. A first byte of 0x26 needs a password, 0x27 meets a
// worker that did not load, and 0x28 never finishes until it is destroyed.
const destroyed = vi.hoisted(() => ({ count: 0 }));

// What the last check asked PDF.js for
const opened = vi.hoisted((): { options: Record<string, unknown> } => ({ options: {} }));

// A switch that makes PDF.js fail to load, as a chunk that 404s after a deploy would
const missing = vi.hoisted(() => ({ pdfjs: false }));

// The one worker the loader shares between documents. Its port is the web worker PDF.js talks to.
const sharedWorker = vi.hoisted(() => ({ port: new EventTarget() }));

const pdfjs = vi.hoisted(() => ({
  getDocument: (args: { data: ArrayBuffer }) => {
    opened.options = args;
    const bytes = new Uint8Array(args.data);
    const destroy = () => {
      destroyed.count++;
      return Promise.resolve();
    };
    if (bytes[0] === 0x26) {
      const locked = new Error("No password given");
      locked.name = "PasswordException";
      return { promise: Promise.reject(locked), destroy };
    }
    if (bytes[0] === 0x28) {
      let fail: (error: Error) => void = () => undefined;
      const promise = new Promise((_resolve, reject: (error: Error) => void) => {
        fail = reject;
      });
      return {
        promise,
        destroy: () => {
          destroyed.count++;
          fail(new Error("Worker was destroyed"));
          return Promise.resolve();
        },
      };
    }
    if (bytes[0] === 0x27) {
      setTimeout(() => sharedWorker.port.dispatchEvent(new Event("error")), 0);
      return { promise: new Promise(() => undefined), destroy };
    }
    return {
      promise: bytes[0] === 0x25
        ? Promise.resolve({ numPages: bytes[1] + (bytes[2] ?? 0) * 256 })
        : Promise.reject(new Error("not a pdf")),
      destroy,
    };
  },
}));

vi.mock("@iap/frontend-commons/pdfViewer/pdfjsClient", () => ({
  loadPdfjs: () => missing.pdfjs
    ? Promise.reject(new Error("Loading chunk pdfjs failed"))
    : Promise.resolve({ pdfjs, worker: sharedWorker }),
}));

const MAX_PAGES = 500;

afterEach(() => {
  vi.restoreAllMocks();
  missing.pdfjs = false;
});

function createUpload(bytes: number[]): File {
  return new File([ new Uint8Array(bytes) ], "proposal.pdf");
}

/** A stand-in PDF of a given length. */
function createPdf(pages: number): File {
  return createUpload([ 0x25, pages % 256, Math.floor(pages / 256) ]);
}

async function getError(file: File, maxPages = MAX_PAGES): Promise<string | undefined> {
  const result = await checkPdf(file, maxPages);
  return result.valid ? undefined : result.error;
}

function spyOnErrors() {
  return vi.spyOn(console, "error").mockImplementation(() => undefined);
}

describe("checkPdf", () => {
  // A worker of its own per check would fetch and compile the PDF.js worker script again
  it("opens a PDF on the shared PDF.js worker", async () => {
    await checkPdf(createPdf(3), MAX_PAGES);

    expect(opened.options.worker).toBe(sharedWorker);
  });

  it("refuses a .pdf that is not a PDF", async () => {
    spyOnErrors();

    expect(await getError(createUpload([ 0x00, 1, 0 ]))).toBe("The PDF is damaged, or it is not a PDF.");
  });

  it("refuses a PDF encrypted with a password", async () => {
    expect(await getError(createUpload([ 0x26 ]))).toBe("It is encrypted with a password.");
  });

  it("refuses a PDF with no pages", async () => {
    expect(await getError(createPdf(0))).toBe("It has no pages.");
  });

  it("accepts a PDF within the page limit", async () => {
    expect(await getError(createPdf(10))).toBeUndefined();
  });

  it("refuses a PDF over the page limit, and says how long it is", async () => {
    expect(await getError(createPdf(11), 10)).toBe("It has 11 pages, and the limit is 10.");
  });

  it("accepts one exactly at the page limit", async () => {
    expect(await getError(createPdf(MAX_PAGES))).toBeUndefined();
  });

  it("lets go of the PDF it opened", async () => {
    const before = destroyed.count;
    await checkPdf(createPdf(3), MAX_PAGES);
    await checkPdf(createUpload([ 0x26 ]), MAX_PAGES);

    expect(destroyed.count).toBe(before + 2);
  });
});

describe("stopping a PDF check", () => {
  it("does not open a PDF the caller has already given up on", async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(checkPdf(createPdf(3), MAX_PAGES, controller.signal)).rejects.toMatchObject({ name: "AbortError" });
  });

  it("ends a check that would never finish, and lets go of the PDF", async () => {
    const controller = new AbortController();
    const before = destroyed.count;
    const pending = checkPdf(createUpload([ 0x28 ]), MAX_PAGES, controller.signal);
    await new Promise(resolve => setTimeout(resolve, 10));

    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(destroyed.count).toBeGreaterThan(before);
  });
});

describe("when PDF.js cannot load", () => {
  it("lets a PDF through when PDF.js does not load, and says so in the console", async () => {
    const logged = spyOnErrors();
    missing.pdfjs = true;

    expect(await getError(createPdf(3))).toBeUndefined();
    expect(logged).toHaveBeenCalledWith(expect.stringContaining("not checked"), "proposal.pdf", expect.any(Error));
  });

  it("lets a PDF through when the PDF.js worker does not load", async () => {
    const logged = spyOnErrors();

    expect(await getError(createUpload([ 0x27 ]))).toBeUndefined();
    expect(logged).toHaveBeenCalledWith(expect.stringContaining("not checked"), "proposal.pdf", expect.any(Error));
  });
});
