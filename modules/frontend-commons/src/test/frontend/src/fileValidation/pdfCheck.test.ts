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
import { PdfWorkerFailure } from "@iap/frontend-commons/pdfjsClient";

// What the fake PDF.js does with the next file it is asked to open. Like the real one, destroying a
// task never settles its promise, and a worker that failed to start stays failed.
type Opening = { pages: number } | "locked" | "damaged" | "endless";

interface FakeState {
  opening: Opening;
  loadFails: boolean;
  workerFailed: Promise<never>;
  destroyed: number;
  // What the last check asked PDF.js for
  options: Record<string, unknown>;
}

const fake = vi.hoisted((): FakeState => ({
  opening: { pages: 3 },
  loadFails: false,
  workerFailed: new Promise<never>(() => undefined),
  destroyed: 0,
  options: {},
}));

// The one worker the loader shares between documents
const sharedWorker = vi.hoisted(() => ({ port: new EventTarget() }));

const pdfjs = vi.hoisted(() => ({
  getDocument: (options: Record<string, unknown>) => {
    fake.options = options;
    const destroy = () => {
      fake.destroyed++;
      return Promise.resolve();
    };
    const opening = fake.opening;
    if (opening === "locked") {
      const locked = new Error("No password given");
      locked.name = "PasswordException";
      return { promise: Promise.reject(locked), destroy };
    }
    if (opening === "damaged") {
      return { promise: Promise.reject(new Error("Invalid PDF structure")), destroy };
    }
    if (opening === "endless") {
      return { promise: new Promise(() => undefined), destroy };
    }
    return { promise: Promise.resolve({ numPages: opening.pages }), destroy };
  },
}));

vi.mock("@iap/frontend-commons/pdfjsClient", async importOriginal => ({
  ...await importOriginal<typeof import("@iap/frontend-commons/pdfjsClient")>(),
  loadPdfjs: () => fake.loadFails
    ? Promise.reject(new Error("Loading chunk pdfjs failed"))
    : Promise.resolve({ pdfjs, worker: sharedWorker, failed: fake.workerFailed }),
}));

const MAX_PAGES = 500;

afterEach(() => {
  vi.restoreAllMocks();
  fake.opening = { pages: 3 };
  fake.loadFails = false;
  fake.workerFailed = new Promise<never>(() => undefined);
  fake.options = {};
});

function openNextAs(opening: Opening): File {
  fake.opening = opening;
  return new File([ new Uint8Array([ 0x25, 0x50, 0x44, 0x46 ]) ], "proposal.pdf");
}

function failWorker(): void {
  fake.workerFailed = Promise.reject(new PdfWorkerFailure("The PDF.js worker did not start"));
  fake.workerFailed.catch(() => undefined);
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
    await checkPdf(openNextAs({ pages: 3 }), MAX_PAGES);

    expect(fake.options.worker).toBe(sharedWorker);
  });

  it("refuses a .pdf that is not a PDF", async () => {
    spyOnErrors();

    expect(await getError(openNextAs("damaged"))).toBe("The PDF is damaged, or it is not a PDF.");
  });

  it("refuses a PDF encrypted with a password", async () => {
    expect(await getError(openNextAs("locked"))).toBe("It is encrypted with a password.");
  });

  it("refuses a PDF with no pages", async () => {
    expect(await getError(openNextAs({ pages: 0 }))).toBe("It has no pages.");
  });

  it("accepts a PDF within the page limit", async () => {
    expect(await getError(openNextAs({ pages: 10 }))).toBeUndefined();
  });

  it("refuses a PDF over the page limit, and says how long it is", async () => {
    expect(await getError(openNextAs({ pages: 11 }), 10)).toBe("It has 11 pages, and the limit is 10.");
  });

  it("accepts one exactly at the page limit", async () => {
    expect(await getError(openNextAs({ pages: MAX_PAGES }))).toBeUndefined();
  });

  it("lets go of the PDF it opened", async () => {
    const before = fake.destroyed;
    await checkPdf(openNextAs({ pages: 3 }), MAX_PAGES);
    await checkPdf(openNextAs("locked"), MAX_PAGES);

    expect(fake.destroyed).toBe(before + 2);
  });
});

describe("stopping a PDF check", () => {
  it("does not open a PDF the caller has already given up on", async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(checkPdf(openNextAs({ pages: 3 }), MAX_PAGES, controller.signal))
      .rejects.toMatchObject({ name: "AbortError" });
  });

  it("ends a parse that would never finish, and lets go of the PDF", async () => {
    const controller = new AbortController();
    const before = fake.destroyed;
    const pending = checkPdf(openNextAs("endless"), MAX_PAGES, controller.signal);
    await vi.waitFor(() => expect(fake.options.worker).toBe(sharedWorker));

    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(fake.destroyed).toBe(before + 1);
  });
});

describe("when PDF.js cannot load", () => {
  it("lets a PDF through when PDF.js does not load, and says so in the console", async () => {
    const logged = spyOnErrors();
    fake.loadFails = true;

    expect(await getError(openNextAs({ pages: 3 }))).toBeUndefined();
    expect(logged).toHaveBeenCalledWith(expect.stringContaining("not checked"), "proposal.pdf", expect.any(Error));
  });

  it("lets a PDF through when the PDF.js worker never started", async () => {
    const logged = spyOnErrors();
    failWorker();

    expect(await getError(openNextAs("endless"))).toBeUndefined();
    expect(logged).toHaveBeenCalledWith(expect.stringContaining("not checked"), "proposal.pdf", expect.any(PdfWorkerFailure));
  });

  it("lets every later PDF through too, without waiting on the dead worker", async () => {
    spyOnErrors();
    failWorker();
    await checkPdf(openNextAs("endless"), MAX_PAGES);

    expect(await getError(openNextAs("endless"))).toBeUndefined();
  });
});
