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
  MAX_FILE_SIZE,
  MAX_PDF_PAGES,
  MAX_UNZIPPED_SIZE,
  PIPELINE_TYPES,
  getFileExtension,
  validateUpload,
} from "@iap/frontend-commons/fileValidation";

// PDF.js is loaded on demand, so the tests stand in for it rather than shipping real PDFs.
// A stand-in PDF starts with 0x25 and carries its page count in the next two bytes, low byte first,
// because one byte cannot say 501. One that starts with 0x26 is encrypted and needs a password, and
// one that starts with 0x27 meets a worker that did not load.
// A .docx is a real zip, written below byte by byte so a test can shape it exactly.
const destroyed = vi.hoisted(() => ({ count: 0 }));

// A switch that makes PDF.js fail to load, as a chunk that 404s after a deploy would
const missing = vi.hoisted(() => ({ pdfjs: false }));

// The [Content_Types].xml of an Office zip whose main part is of this kind
function getContentTypes(kind: string): string {
  return `<Types><Override ContentType="application/vnd.openxmlformats-officedocument.${kind}.main+xml"/></Types>`;
}

interface ZipPart {
  name: string;
  text: string;
  // The unzipped size the zip states, when it lies
  claimed?: number;
  // Kept as it is rather than deflated
  stored?: boolean;
}

// jsdom's Blob has no stream(), so the bytes are streamed by hand
function createStream(bytes: Uint8Array<ArrayBuffer>): ReadableStream<Uint8Array<ArrayBuffer>> {
  return new ReadableStream({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    },
  });
}

async function readStream(stream: ReadableStream<Uint8Array>): Promise<Uint8Array<ArrayBuffer>> {
  const chunks: Uint8Array[] = [];
  const reader = stream.getReader();
  for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
    chunks.push(chunk.value);
  }
  return joinBytes(chunks);
}

function joinBytes(parts: Uint8Array[]): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  parts.reduce((at, part) => {
    bytes.set(part, at);
    return at + part.length;
  }, 0);
  return bytes;
}

function createRecord(size: number, fill: (view: DataView, bytes: Uint8Array) => void): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(size);
  fill(new DataView(bytes.buffer), bytes);
  return bytes;
}

/**
 * A zip of these parts. With `zip64`, every size and offset sits in a ZIP64 record, with the marker
 * in the plain fields, the way a writer that always uses ZIP64 leaves them.
 */
async function createZip(
  parts: ZipPart[],
  options: { zip64?: boolean; comment?: string } = {},
): Promise<Uint8Array<ArrayBuffer>> {
  const encoder = new TextEncoder();
  const zip64 = options.zip64 ?? false;
  const marker = 0xFFFFFFFF;
  const version = zip64 ? 45 : 20;
  const pieces: Uint8Array[] = [];
  const directory: Uint8Array[] = [];
  let offset = 0;
  for (const part of parts) {
    const name = encoder.encode(part.name);
    const raw = encoder.encode(part.text);
    const data = part.stored
      ? raw
      : await readStream(createStream(raw).pipeThrough(new CompressionStream("deflate-raw")));
    const method = part.stored ? 0 : 8;
    const unzipped = part.claimed ?? raw.length;
    const extraSize = zip64 ? 20 : 0;
    pieces.push(createRecord(30 + name.length + extraSize, (view, bytes) => {
      view.setUint32(0, 0x04034B50, true);
      view.setUint16(4, version, true);
      view.setUint16(8, method, true);
      view.setUint32(18, zip64 ? marker : data.length, true);
      view.setUint32(22, zip64 ? marker : unzipped, true);
      view.setUint16(26, name.length, true);
      view.setUint16(28, extraSize, true);
      bytes.set(name, 30);
      if (zip64) {
        view.setUint16(30 + name.length, 1, true);
        view.setUint16(32 + name.length, 16, true);
        view.setBigUint64(34 + name.length, BigInt(unzipped), true);
        view.setBigUint64(42 + name.length, BigInt(data.length), true);
      }
    }), data);
    const entryOffset = offset;
    directory.push(createRecord(46 + name.length + (zip64 ? 28 : 0), (view, bytes) => {
      view.setUint32(0, 0x02014B50, true);
      view.setUint16(4, version, true);
      view.setUint16(6, version, true);
      view.setUint16(10, method, true);
      view.setUint32(20, zip64 ? marker : data.length, true);
      view.setUint32(24, zip64 ? marker : unzipped, true);
      view.setUint16(28, name.length, true);
      view.setUint16(30, zip64 ? 28 : 0, true);
      view.setUint32(42, zip64 ? marker : entryOffset, true);
      bytes.set(name, 46);
      if (zip64) {
        view.setUint16(46 + name.length, 1, true);
        view.setUint16(48 + name.length, 24, true);
        view.setBigUint64(50 + name.length, BigInt(unzipped), true);
        view.setBigUint64(58 + name.length, BigInt(data.length), true);
        view.setBigUint64(66 + name.length, BigInt(entryOffset), true);
      }
    }));
    offset += 30 + name.length + extraSize + data.length;
  }
  const directoryStart = offset;
  const directorySize = directory.reduce((total, entry) => total + entry.length, 0);
  const comment = encoder.encode(options.comment ?? "");
  const ending: Uint8Array[] = [];
  if (zip64) {
    const record = directoryStart + directorySize;
    ending.push(createRecord(56, view => {
      view.setUint32(0, 0x06064B50, true);
      view.setBigUint64(4, BigInt(44), true);
      view.setUint16(12, version, true);
      view.setUint16(14, version, true);
      view.setBigUint64(24, BigInt(parts.length), true);
      view.setBigUint64(32, BigInt(parts.length), true);
      view.setBigUint64(40, BigInt(directorySize), true);
      view.setBigUint64(48, BigInt(directoryStart), true);
    }), createRecord(20, view => {
      view.setUint32(0, 0x07064B50, true);
      view.setBigUint64(8, BigInt(record), true);
      view.setUint32(16, 1, true);
    }));
  }
  ending.push(createRecord(22 + comment.length, (view, bytes) => {
    view.setUint32(0, 0x06054B50, true);
    view.setUint16(8, zip64 ? 0xFFFF : parts.length, true);
    view.setUint16(10, zip64 ? 0xFFFF : parts.length, true);
    view.setUint32(12, zip64 ? marker : directorySize, true);
    view.setUint32(16, zip64 ? marker : directoryStart, true);
    view.setUint16(20, comment.length, true);
    bytes.set(comment, 22);
  }));
  return joinBytes([ ...pieces, ...directory, ...ending ]);
}

const WORD_PARTS: ZipPart[] = [
  { name: "[Content_Types].xml", text: getContentTypes("wordprocessingml.document") },
  { name: "word/document.xml", text: "<w:document/>" },
];

/** The bytes of a zip shaped like a .docx; `kind` null leaves out the content types. */
async function createZipBytes(
  kind: string | null = "wordprocessingml.document",
  body = "<w:document/>",
  comment?: string,
): Promise<Uint8Array<ArrayBuffer>> {
  const types = kind === null ? [] : [ { name: "[Content_Types].xml", text: getContentTypes(kind) } ];
  return createZip([ ...types, { name: "word/document.xml", text: body } ], { comment });
}

async function createDocx(kind?: string | null, body?: string, comment?: string): Promise<File> {
  return new File([ await createZipBytes(kind, body, comment) ], "proposal.docx");
}

async function createZip64(parts: ZipPart[]): Promise<File> {
  return new File([ await createZip(parts, { zip64: true }) ], "proposal.docx");
}

// Where the first central directory entry starts: its signature, low byte first
function findDirectoryEntry(bytes: Uint8Array): number {
  return bytes.findIndex((value, at) =>
    value === 0x50 && bytes[at + 1] === 0x4B && bytes[at + 2] === 0x01 && bytes[at + 3] === 0x02);
}

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

  // As on the server, a requirement that names no type takes any
  it("takes any type when none is asked for", async () => {
    await expect(validateUpload(createUpload("notes.txt"))).resolves.toBeUndefined();
    await expect(validateUpload(createUpload("README"))).resolves.toBeUndefined();
  });

  it("refuses what the pipeline cannot read, when asked for only that", async () => {
    await expect(validateUpload(createUpload("proposal.txt"), PIPELINE_TYPES))
      .resolves.toBe("proposal.txt is not a file of an accepted type: .pdf, .docx, .doc.");
    await expect(validateUpload(createUpload("proposal"), PIPELINE_TYPES)).resolves.toMatch(/accepted type/);
  });

  // The server compares MIME types only, so an entry written as an extension matches nothing there
  it("does not read an entry written as an extension as a type", async () => {
    await expect(validateUpload(createUpload("proposal.pdf"), [ ".pdf" ])).resolves.toMatch(/accepted type: .pdf\.$/);
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

  // A browser that does not know the format reports no type, or the generic one; the name then says
  it("reads the type from the name when the browser could not tell", async () => {
    await expect(validateUpload(createUpload("proposal.pdf"), [ "application/pdf" ])).resolves.toBeUndefined();
    await expect(validateUpload(createTypedUpload("proposal.pdf", "application/octet-stream"), [ "application/pdf" ]))
      .resolves.toBeUndefined();
  });

  it("compares types without case or parameters", async () => {
    await expect(validateUpload(createTypedUpload("proposal.pdf", "application/pdf; x=y"), [ "Application/PDF" ]))
      .resolves.toBeUndefined();
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
    expect(PIPELINE_TYPES).toEqual([
      "application/pdf",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/msword",
    ]);
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
    expect(await validateUpload(await createZip64(WORD_PARTS))).toBeUndefined();
  });

  it("refuses a ZIP64 archive that unzips to more than the limit", async () => {
    const [ types, document ] = WORD_PARTS;
    const file = await createZip64([ types, { ...document, claimed: 600 * 1024 * 1024 } ]);

    expect(await validateUpload(file)).toMatch(/unzips to more than 512 MB/);
  });

  // The stated sizes can lie, so reading stops at a limit whatever the zip says
  it("refuses content types that unzip past what a real one could hold", async () => {
    const types = {
      name: "[Content_Types].xml",
      text: getContentTypes("wordprocessingml.document") + " ".repeat(300_000),
      claimed: 1024,
    };

    expect(await validateUpload(new File([ await createZip([ types ]) ], "proposal.docx"))).toMatch(/damaged/);
  });

  // With two entries of one name, the check and the parser could read different ones
  it("refuses a zip that names an entry twice", async () => {
    const [ types ] = WORD_PARTS;
    const file = new File([ await createZip([ ...WORD_PARTS, types ]) ], "proposal.docx");

    expect(await validateUpload(file)).toMatch(/damaged/);
  });

  it("reads content types stored without compression", async () => {
    const parts = WORD_PARTS.map(part => ({ ...part, stored: true }));

    expect(await validateUpload(new File([ await createZip(parts) ], "proposal.docx"))).toBeUndefined();
  });

  it("refuses content types compressed some other way", async () => {
    const bytes = await createZip(WORD_PARTS);
    // 12 is bzip2, which neither this check nor Word uses
    new DataView(bytes.buffer).setUint16(findDirectoryEntry(bytes) + 10, 12, true);

    expect(await validateUpload(new File([ bytes ], "proposal.docx"))).toMatch(/damaged/);
  });
});

describe("when what checks a file cannot load", () => {
  afterEach(() => {
    missing.pdfjs = false;
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
});
