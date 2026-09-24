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
import { GlobalWorkerOptions } from "pdfjs-dist";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  ACCEPTED_EXTENSIONS,
  MAX_FILE_SIZE,
  MAX_PDF_PAGES,
  fileExtension,
  validateUpload,
} from "@iap/submissions/fileValidation";

// PDF.js and JSZip are loaded on demand, so the tests stand in for them rather than shipping real
// files. What is under test is the rules, not those two libraries.
// A stand-in PDF starts with 0x25 and carries its page count in the next two bytes, low byte first,
// because one byte cannot say 501.
vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: (args: { data: ArrayBuffer }) => {
    const bytes = new Uint8Array(args.data);
    return {
      promise: bytes[0] === 0x25
        ? Promise.resolve({ numPages: bytes[1] + (bytes[2] ?? 0) * 256 })
        : Promise.reject(new Error("not a pdf")),
    };
  },
}));

vi.mock("jszip", () => ({
  default: {
    loadAsync: (file: File) => file.name.includes("broken")
      ? Promise.reject(new Error("not a zip"))
      : Promise.resolve({
        file: (name: string) => file.name.includes("bare") ? null : { name },
      }),
  },
}));

afterEach(() => {
  vi.restoreAllMocks();
});

function upload(name: string, bytes: number[] = [ 0x25, 1, 0 ], size?: number): File {
  const file = new File([ new Uint8Array(bytes) ], name);
  Object.defineProperty(file, "size", { value: size ?? bytes.length });
  return file;
}

/** An upload the browser recognised, so it carries a MIME type as a real one would. */
function typed(name: string, type: string): File {
  const file = new File([ new Uint8Array([ 0x25, 1, 0 ]) ], name, { type });
  Object.defineProperty(file, "size", { value: 3 });
  return file;
}

/** A stand-in PDF of a given length. */
function pdf(pages: number, size?: number): File {
  return upload("proposal.pdf", [ 0x25, pages % 256, Math.floor(pages / 256) ], size);
}

describe("fileExtension", () => {
  it("reads the extension in lower case", () => {
    expect(fileExtension("Proposal.PDF")).toBe(".pdf");
  });

  it("has none for a name that carries none", () => {
    expect(fileExtension("proposal")).toBeUndefined();
    expect(fileExtension("proposal.")).toBeUndefined();
  });

  it("reads the last one, not the first", () => {
    expect(fileExtension("v1.2.final.docx")).toBe(".docx");
  });
});

describe("what an upload is refused for", () => {
  it("refuses an empty file", async () => {
    await expect(validateUpload(upload("proposal.pdf", [], 0))).resolves.toMatch(/is empty/);
  });

  it("refuses one over the size limit, and says the limit", async () => {
    const big = pdf(1, MAX_FILE_SIZE + 1);

    await expect(validateUpload(big)).resolves.toMatch(/50 MB/);
  });

  it("allows one exactly at the limit", async () => {
    const edge = pdf(1, MAX_FILE_SIZE);

    await expect(validateUpload(edge)).resolves.toBeUndefined();
  });

  it("refuses a format the pipeline cannot read", async () => {
    await expect(validateUpload(upload("proposal.txt"))).resolves.toMatch(/not a .pdf/);
  });

  it("refuses a name with no extension at all", async () => {
    await expect(validateUpload(upload("proposal"))).resolves.toMatch(/not a/);
  });

  // The requirement says what it takes; the pipeline's own list is only the fallback
  it("honours the formats the requirement asks for", async () => {
    await expect(validateUpload(upload("proposal.pdf"), [ ".docx" ])).resolves.toMatch(/not a .docx/);
  });

  // The requirement states MIME types, the way the server checks them, and that is what a browser
  // reports for a file it knows. Reading the list as extensions emptied it and let anything .pdf through.
  it("honours the MIME types the requirement asks for", async () => {
    await expect(validateUpload(typed("note.png", "image/png"), [ "image/png" ])).resolves.toBeUndefined();
    await expect(validateUpload(typed("note.png", "image/png"), [ "application/pdf" ]))
      .resolves.toMatch(/not a application\/pdf/);
  });

  // A browser that does not know the format reports no type; the server checks again, so let it decide
  it("does not refuse a file the browser could not type", async () => {
    await expect(validateUpload(upload("proposal.pdf"), [ "application/pdf" ])).resolves.toBeUndefined();
  });
});

describe("what is inside the file", () => {
  // PDF.js refuses to open anything until told where its worker script is, and it reports that the
  // same way as a broken file, so the setting is checked on its own
  it("tells PDF.js where its worker is before opening anything", async () => {
    await validateUpload(pdf(3));

    expect(GlobalWorkerOptions.workerSrc).toMatch(/pdf\.worker\.min\.mjs$/);
  });

  it("refuses a .pdf that is not a PDF", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    await expect(validateUpload(upload("proposal.pdf", [ 0x00, 1, 0 ])))
      .resolves.toMatch(/damaged, or it is not a PDF/);
  });

  it("accepts a PDF within the page limit", async () => {
    await expect(validateUpload(pdf(10))).resolves.toBeUndefined();
  });

  it("refuses a PDF over the page limit, and says how long it is", async () => {
    const long = pdf(MAX_PDF_PAGES + 1);

    await expect(validateUpload(long)).resolves.toMatch(new RegExp(`${MAX_PDF_PAGES + 1} pages`));
  });

  it("accepts one exactly at the page limit", async () => {
    await expect(validateUpload(pdf(MAX_PDF_PAGES))).resolves.toBeUndefined();
  });

  it("accepts a .docx that holds what a Word document holds", async () => {
    await expect(validateUpload(upload("proposal.docx"))).resolves.toBeUndefined();
  });

  // A zip without these parts is some other zip that was renamed
  it("refuses a .docx missing the parts that make it one", async () => {
    await expect(validateUpload(upload("bare.docx"))).resolves.toMatch(/not a Word document/);
  });

  it("refuses a .docx that is not a zip", async () => {
    await expect(validateUpload(upload("broken.docx"))).resolves.toMatch(/damaged/);
  });

  it("accepts a .doc that starts the way one does", async () => {
    const doc = upload("proposal.doc", [ 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 ]);

    await expect(validateUpload(doc)).resolves.toBeUndefined();
  });

  it("refuses a .doc that does not", async () => {
    await expect(validateUpload(upload("proposal.doc", [ 0x50, 0x4B, 3, 4, 0, 0, 0, 0 ])))
      .resolves.toMatch(/not a Word document/);
  });
});

describe("the limits themselves", () => {
  it("are the ones the team agreed", () => {
    expect(MAX_FILE_SIZE).toBe(50 * 1024 * 1024);
    expect(MAX_PDF_PAGES).toBe(500);
    expect(ACCEPTED_EXTENSIONS).toEqual([ ".pdf", ".docx", ".doc" ]);
  });
});
