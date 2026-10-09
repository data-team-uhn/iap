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
import JSZip from "jszip";
import { GlobalWorkerOptions } from "pdfjs-dist";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  ACCEPTED_EXTENSIONS,
  MAX_FILE_SIZE,
  MAX_PDF_PAGES,
  MAX_UNZIPPED_SIZE,
  getFileExtension,
  validateUpload,
} from "@iap/frontend-commons/fileValidation";

// PDF.js is loaded on demand, so the tests stand in for it rather than shipping real PDFs.
// A stand-in PDF starts with 0x25 and carries its page count in the next two bytes, low byte first,
// because one byte cannot say 501. One that starts with 0x26 is encrypted and needs a password, and
// one that starts with 0x27 meets a worker that did not load.
// A .docx is a real zip made with JSZip, since the check reads the zip's own bytes.
const destroyed = vi.hoisted(() => ({ count: 0 }));

// Switches that make a library fail to load, as a chunk that 404s after a deploy would
const missing = vi.hoisted(() => ({ pdfjs: false, jszip: false }));

// The [Content_Types].xml of an Office zip whose main part is of this kind
function getContentTypes(kind: string): string {
  return `<Types><Override ContentType="application/vnd.openxmlformats-officedocument.${kind}.main+xml"/></Types>`;
}

/** The bytes of a zip shaped like a .docx; `kind` null leaves out the content types. */
async function createZipBytes(
  kind: string | null = "wordprocessingml.document",
  body = "<w:document/>",
  comment?: string,
): Promise<Uint8Array<ArrayBuffer>> {
  const zip = new JSZip();
  if (kind !== null) {
    zip.file("[Content_Types].xml", getContentTypes(kind));
  }
  zip.file("word/document.xml", body);
  // Copied so the bytes own their buffer from offset 0, which the tests below write into
  return new Uint8Array(await zip.generateAsync({ type: "uint8array", compression: "DEFLATE", comment }));
}

async function createDocx(kind?: string | null, body?: string, comment?: string): Promise<File> {
  return new File([ await createZipBytes(kind, body, comment) ], "proposal.docx");
}

// Where the first central directory entry starts: its signature, low byte first
function findDirectoryEntry(bytes: Uint8Array): number {
  return bytes.findIndex((value, at) =>
    value === 0x50 && bytes[at + 1] === 0x4B && bytes[at + 2] === 0x01 && bytes[at + 3] === 0x02);
}

/**
 * A stored (not compressed) zip written in the ZIP64 format, which JSZip cannot write. Every size
 * and offset sits in a ZIP64 record, with the marker in the plain fields, the way a writer that
 * always uses ZIP64 leaves them. `claimed` is the unzipped size the directory states, when it lies.
 */
function createZip64(entries: { name: string; text: string; claimed?: number }[]): File {
  const encoder = new TextEncoder();
  const parts = entries.map(entry =>
    ({ ...entry, name: encoder.encode(entry.name), data: encoder.encode(entry.text) }));
  const localSize = parts.reduce((sum, part) => sum + 30 + part.name.length + 20 + part.data.length, 0);
  const directorySize = parts.reduce((sum, part) => sum + 46 + part.name.length + 28, 0);
  const bytes = new Uint8Array(localSize + directorySize + 56 + 20 + 22);
  const view = new DataView(bytes.buffer);
  let at = 0;
  const offsets: number[] = [];
  for (const part of parts) {
    offsets.push(at);
    view.setUint32(at, 0x04034B50, true);
    view.setUint16(at + 4, 45, true);
    view.setUint32(at + 18, 0xFFFFFFFF, true);
    view.setUint32(at + 22, 0xFFFFFFFF, true);
    view.setUint16(at + 26, part.name.length, true);
    view.setUint16(at + 28, 20, true);
    bytes.set(part.name, at + 30);
    const extra = at + 30 + part.name.length;
    view.setUint16(extra, 1, true);
    view.setUint16(extra + 2, 16, true);
    view.setBigUint64(extra + 4, BigInt(part.data.length), true);
    view.setBigUint64(extra + 12, BigInt(part.data.length), true);
    bytes.set(part.data, extra + 20);
    at = extra + 20 + part.data.length;
  }
  const directoryStart = at;
  parts.forEach((part, index) => {
    view.setUint32(at, 0x02014B50, true);
    view.setUint16(at + 4, 45, true);
    view.setUint16(at + 6, 45, true);
    view.setUint32(at + 20, 0xFFFFFFFF, true);
    view.setUint32(at + 24, 0xFFFFFFFF, true);
    view.setUint16(at + 28, part.name.length, true);
    view.setUint16(at + 30, 28, true);
    view.setUint32(at + 42, 0xFFFFFFFF, true);
    bytes.set(part.name, at + 46);
    const extra = at + 46 + part.name.length;
    view.setUint16(extra, 1, true);
    view.setUint16(extra + 2, 24, true);
    view.setBigUint64(extra + 4, BigInt(part.claimed ?? part.data.length), true);
    view.setBigUint64(extra + 12, BigInt(part.data.length), true);
    view.setBigUint64(extra + 20, BigInt(offsets[index]), true);
    at = extra + 28;
  });
  const zip64End = at;
  view.setUint32(at, 0x06064B50, true);
  view.setBigUint64(at + 4, BigInt(44), true);
  view.setUint16(at + 12, 45, true);
  view.setUint16(at + 14, 45, true);
  view.setBigUint64(at + 24, BigInt(parts.length), true);
  view.setBigUint64(at + 32, BigInt(parts.length), true);
  view.setBigUint64(at + 40, BigInt(zip64End - directoryStart), true);
  view.setBigUint64(at + 48, BigInt(directoryStart), true);
  at += 56;
  view.setUint32(at, 0x07064B50, true);
  view.setBigUint64(at + 8, BigInt(zip64End), true);
  view.setUint32(at + 16, 1, true);
  at += 20;
  view.setUint32(at, 0x06054B50, true);
  view.setUint16(at + 8, 0xFFFF, true);
  view.setUint16(at + 10, 0xFFFF, true);
  view.setUint32(at + 12, 0xFFFFFFFF, true);
  view.setUint32(at + 16, 0xFFFFFFFF, true);
  return new File([ bytes ], "proposal.docx");
}

const WORD_PARTS = [
  { name: "[Content_Types].xml", text: getContentTypes("wordprocessingml.document") },
  { name: "word/document.xml", text: "<w:document/>" },
];

vi.mock("jszip", async importOriginal => {
  const actual = await importOriginal<{ default: unknown }>();
  return {
    get default() {
      if (missing.jszip) {
        throw new Error("Loading chunk jszip failed");
      }
      return actual.default;
    },
  };
});

const workerOptions = vi.hoisted(() => ({ workerSrc: "" }));

vi.mock("pdfjs-dist", () => ({
  get GlobalWorkerOptions() {
    if (missing.pdfjs) {
      throw new Error("Loading chunk pdfjs failed");
    }
    return workerOptions;
  },
  getDocument: (args: { data: ArrayBuffer }) => {
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
    if (bytes[0] === 0x27) {
      return { promise: Promise.reject(new Error("Setting up fake worker failed: \"Failed to fetch\".")), destroy };
    }
    return {
      promise: bytes[0] === 0x25
        ? Promise.resolve({ numPages: bytes[1] + (bytes[2] ?? 0) * 256 })
        : Promise.reject(new Error("not a pdf")),
      destroy,
    };
  },
}));

afterEach(() => {
  vi.restoreAllMocks();
});

function createUpload(name: string, bytes: number[] = [ 0x25, 1, 0 ], size?: number): File {
  const file = new File([ new Uint8Array(bytes) ], name);
  Object.defineProperty(file, "size", { value: size ?? bytes.length });
  return file;
}

/** An upload the browser recognised, so it carries a MIME type as a real one would. */
function createTypedUpload(name: string, type: string): File {
  const file = new File([ new Uint8Array([ 0x25, 1, 0 ]) ], name, { type });
  Object.defineProperty(file, "size", { value: 3 });
  return file;
}

/** A stand-in PDF of a given length. */
function createPdf(pages: number, size?: number): File {
  return createUpload("proposal.pdf", [ 0x25, pages % 256, Math.floor(pages / 256) ], size);
}

describe("getFileExtension", () => {
  it("reads the extension in lower case", () => {
    expect(getFileExtension("Proposal.PDF")).toBe(".pdf");
  });

  it("has none for a name that carries none", () => {
    expect(getFileExtension("proposal")).toBeUndefined();
    expect(getFileExtension("proposal.")).toBeUndefined();
  });

  it("reads the last one, not the first", () => {
    expect(getFileExtension("v1.2.final.docx")).toBe(".docx");
  });
});

describe("what an upload is refused for", () => {
  it("refuses an empty file", async () => {
    await expect(validateUpload(createUpload("proposal.pdf", [], 0))).resolves.toMatch(/is empty/);
  });

  it("refuses one over the size limit, and says the limit", async () => {
    const big = createPdf(1, MAX_FILE_SIZE + 1);

    await expect(validateUpload(big)).resolves.toMatch(/50 MB/);
  });

  it("allows one exactly at the limit", async () => {
    const edge = createPdf(1, MAX_FILE_SIZE);

    await expect(validateUpload(edge)).resolves.toBeUndefined();
  });

  it("refuses a format the pipeline cannot read", async () => {
    await expect(validateUpload(createUpload("proposal.txt")))
      .resolves.toBe("proposal.txt is not a file of an accepted type: .pdf, .docx, .doc.");
  });

  it("refuses a name with no extension at all", async () => {
    await expect(validateUpload(createUpload("proposal"))).resolves.toMatch(/not a/);
  });

  // The requirement says what it takes; the pipeline's own list is only the fallback
  it("honours the formats the requirement asks for", async () => {
    await expect(validateUpload(createUpload("proposal.pdf"), [ ".docx" ])).resolves.toMatch(/accepted type: .docx\.$/);
  });

  // MIME types are what a server checks and what a browser reports for a file it knows
  it("honours the MIME types the requirement asks for", async () => {
    await expect(validateUpload(createTypedUpload("note.png", "image/png"), [ "image/png" ])).resolves.toBeUndefined();
    await expect(validateUpload(createTypedUpload("note.png", "image/png"), [ "application/pdf" ]))
      .resolves.toMatch(/accepted type: .pdf\.$/);
  });

  it("names a known type by its extension, once, and any other type as given", async () => {
    await expect(validateUpload(createUpload("notes.txt"), [ "application/pdf", ".PDF", "image/png" ]))
      .resolves.toBe("notes.txt is not a file of an accepted type: .pdf, image/png.");
  });

  // A browser that does not know the format reports no type; the server checks again, so let it decide
  it("does not refuse a file the browser could not type", async () => {
    await expect(validateUpload(createUpload("proposal.pdf"), [ "application/pdf" ])).resolves.toBeUndefined();
  });
});

describe("what is inside the file", () => {
  // PDF.js refuses to open anything until told where its worker script is
  it("tells PDF.js where its worker is before opening anything", async () => {
    await validateUpload(createPdf(3));

    expect(GlobalWorkerOptions.workerSrc).toMatch(/pdf\.worker\.min\.mjs$/);
  });

  it("refuses a .pdf that is not a PDF", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    await expect(validateUpload(createUpload("proposal.pdf", [ 0x00, 1, 0 ])))
      .resolves.toMatch(/damaged, or it is not a PDF/);
  });

  // An empty password opens without throwing, so this is only a file that genuinely needs one
  it("refuses a PDF encrypted with a password", async () => {
    await expect(validateUpload(createUpload("proposal.pdf", [ 0x26 ])))
      .resolves.toMatch(/encrypted with a password/);
  });

  it("refuses a PDF with no pages", async () => {
    await expect(validateUpload(createPdf(0))).resolves.toMatch(/has no pages/);
  });

  it("accepts a PDF within the page limit", async () => {
    await expect(validateUpload(createPdf(10))).resolves.toBeUndefined();
  });

  it("refuses a PDF over the page limit, and says how long it is", async () => {
    const long = createPdf(MAX_PDF_PAGES + 1);

    await expect(validateUpload(long)).resolves.toMatch(new RegExp(`${MAX_PDF_PAGES + 1} pages`));
  });

  it("accepts one exactly at the page limit", async () => {
    await expect(validateUpload(createPdf(MAX_PDF_PAGES))).resolves.toBeUndefined();
  });

  it("accepts a .docx that holds what a Word document holds", async () => {
    await expect(validateUpload(await createDocx())).resolves.toBeUndefined();
  });

  // A zip without these parts is some other zip that was renamed
  it("refuses a .docx missing the parts that make it one", async () => {
    await expect(validateUpload(await createDocx(null))).resolves.toMatch(/not a Word document/);
  });

  it("refuses a .docx that is not a zip", async () => {
    await expect(validateUpload(createUpload("proposal.docx", [ 0x25, 1, 0 ]))).resolves.toMatch(/damaged/);
  });

  // The end record is found by searching back from the end, past whatever comment the zip carries
  it("reads a .docx that ends with a comment", async () => {
    await expect(validateUpload(await createDocx(undefined, undefined, "c".repeat(300)))).resolves.toBeUndefined();
  });

  it("refuses a .docx whose directory points at the wrong place", async () => {
    const bytes = await createZipBytes();
    // The directory's offset sits 16 bytes into the end record, which is the last 22 bytes
    new DataView(bytes.buffer).setUint32(bytes.length - 22 + 16, 0, true);

    await expect(validateUpload(new File([ bytes ], "proposal.docx"))).resolves.toMatch(/damaged/);
  });

  it("accepts a .doc that starts the way one does", async () => {
    const doc = createUpload("proposal.doc", [ 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 ]);

    await expect(validateUpload(doc)).resolves.toBeUndefined();
  });

  it("refuses a .doc that does not", async () => {
    await expect(validateUpload(createUpload("proposal.doc", [ 0x50, 0x4B, 3, 4, 0, 0, 0, 0 ])))
      .resolves.toMatch(/not a Word document/);
  });
});

describe("the limits themselves", () => {
  it("are the ones the team agreed", () => {
    expect(MAX_FILE_SIZE).toBe(50 * 1024 * 1024);
    expect(MAX_PDF_PAGES).toBe(500);
    expect(MAX_UNZIPPED_SIZE).toBe(512 * 1024 * 1024);
    expect(ACCEPTED_EXTENSIONS).toEqual([ ".pdf", ".docx", ".doc" ]);
  });

  // Each check opens its own PDF.js worker holding the whole file, which has to go once it is done
  it("lets go of the PDF it opened", async () => {
    const before = destroyed.count;
    await validateUpload(createPdf(3));
    await validateUpload(createUpload("locked.pdf", [ 0x26 ]));
    expect(destroyed.count).toBe(before + 2);
  });

  // The main document's name is not fixed, so the content types are what say it is a Word document
  it("refuses a zip whose content types name no Word document", async () => {
    expect(await validateUpload(await createDocx("spreadsheetml.sheet"))).toMatch(/not a Word document/);
  });

  // A small .docx can unzip to gigabytes, so the size it unzips to is checked before anything is unzipped
  it("refuses a .docx that unzips to more than the limit", async () => {
    const file = await createDocx(undefined, "x".repeat(4000));

    expect(file.size).toBeLessThan(1000);
    expect(await validateUpload(file, [], { maxUnzippedSize: 2000 })).toMatch(/unzips to more than/);
    expect(await validateUpload(file, [], { maxUnzippedSize: 8000 })).toBeUndefined();
  });

  // The marker says the real size is in a ZIP64 record, so without one the size is unknown
  it("refuses a .docx that marks a size as ZIP64 but has no ZIP64 record", async () => {
    const bytes = await createZipBytes();
    new DataView(bytes.buffer).setUint32(findDirectoryEntry(bytes) + 24, 0xFFFFFFFF, true);

    expect(await validateUpload(new File([ bytes ], "proposal.docx"))).toMatch(/damaged/);
  });

  // With no type from the browser, the name says what it is meant to be, and only an accepted kind passes
  it("refuses an untyped file whose name is not an accepted kind", async () => {
    expect(await validateUpload(createUpload("notes.xyz"), [ "application/pdf" ])).toMatch(/accepted type/);
    expect(await validateUpload(createUpload("proposal.pdf"), [ "application/pdf" ])).toBeUndefined();
  });
});

describe("limits set by the caller", () => {
  it("refuse a file over a smaller size limit, and say that limit", async () => {
    const file = createPdf(1, 2 * 1024 * 1024);

    await expect(validateUpload(file, [], { maxFileSize: 1024 * 1024 })).resolves.toMatch(/2 MB.*limit is 1 MB/);
  });

  it("refuse a PDF over a smaller page limit", async () => {
    await expect(validateUpload(createPdf(11), [], { maxPdfPages: 10 }))
      .resolves.toMatch(/11 pages, and the limit is 10/);
    await expect(validateUpload(createPdf(10), [], { maxPdfPages: 10 })).resolves.toBeUndefined();
  });

  it("keep the default for each one left out", async () => {
    await expect(validateUpload(createPdf(MAX_PDF_PAGES + 1), [], { maxFileSize: MAX_FILE_SIZE * 2 }))
      .resolves.toMatch(/pages/);
  });
});

describe("an untyped file with no extension", () => {
  it("is refused where a type is asked for, since nothing says what it is", async () => {
    expect(await validateUpload(createUpload("README"), [ "application/pdf" ])).toMatch(/accepted type/);
  });
});

describe("reading the zip directory", () => {
  // Some writers use ZIP64 for every archive, however small, and the parser reads them
  it("reads a small ZIP64 archive", async () => {
    expect(await validateUpload(createZip64(WORD_PARTS))).toBeUndefined();
  });

  it("refuses a ZIP64 archive that unzips to more than the limit", async () => {
    const [ types, document ] = WORD_PARTS;
    const file = createZip64([ types, { ...document, claimed: 600 * 1024 * 1024 } ]);

    expect(await validateUpload(file)).toMatch(/unzips to more than 512 MB/);
  });

  // The stated sizes can lie, and JSZip unzips past them; deflate unzips at most about a
  // thousand times what is stored, so a small stored part cannot unzip to much
  it("refuses content types stored too large to be real", async () => {
    // Random text does not compress, so it is stored at about its own size
    const noise = Array.from(crypto.getRandomValues(new Uint8Array(50_000)), value =>
      String.fromCharCode(33 + (value % 90))).join("");
    const zip = new JSZip();
    zip.file("[Content_Types].xml", getContentTypes("wordprocessingml.document") + noise);
    const bytes = await zip.generateAsync({ type: "uint8array", compression: "DEFLATE" });

    expect(await validateUpload(new File([ new Uint8Array(bytes) ], "proposal.docx"))).toMatch(/damaged/);
  });
});

describe("when what checks a file cannot load", () => {
  afterEach(() => {
    missing.pdfjs = false;
    missing.jszip = false;
  });

  function spyOnErrors() {
    return vi.spyOn(console, "error").mockImplementation(() => undefined);
  }

  // The server checks again, so a fault in this page must not block good files
  it("lets a PDF through when PDF.js does not load, and says so in the console", async () => {
    const logged = spyOnErrors();
    missing.pdfjs = true;

    expect(await validateUpload(createPdf(3))).toBeUndefined();
    expect(logged).toHaveBeenCalledWith(expect.stringContaining("not checked"), "proposal.pdf", expect.any(Error));
  });

  it("lets a PDF through when the PDF.js worker does not load", async () => {
    const logged = spyOnErrors();

    expect(await validateUpload(createUpload("proposal.pdf", [ 0x27 ]))).toBeUndefined();
    expect(logged).toHaveBeenCalledWith(expect.stringContaining("not checked"), "proposal.pdf", expect.any(Error));
  });

  it("lets a .docx through when JSZip does not load", async () => {
    const logged = spyOnErrors();
    const file = await createDocx();
    missing.jszip = true;

    expect(await validateUpload(file)).toBeUndefined();
    expect(logged).toHaveBeenCalledWith(expect.stringContaining("not checked"), "proposal.docx", expect.any(Error));
  });

  // Reading the zip's list needs no library, so what it finds is still refused
  it("still refuses a .docx whose list cannot be read", async () => {
    missing.jszip = true;

    expect(await validateUpload(createUpload("proposal.docx", [ 0x25, 1, 0 ]))).toMatch(/damaged/);
  });
});
