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
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  MAX_FILE_SIZE,
  MAX_PDF_PAGES,
  MAX_UNZIPPED_SIZE,
  PIPELINE_TYPES,
  getFileExtension,
  validateUpload,
} from "@iap/frontend-commons/fileValidation";

// Each format's own check has tests of its own, so here they only say what they were asked
const checks = vi.hoisted(() => ({
  pdf: vi.fn(),
  docx: vi.fn(),
  doc: vi.fn(),
}));

vi.mock("@iap/frontend-commons/fileValidation/pdfCheck", () => ({ checkPdf: checks.pdf }));
vi.mock("@iap/frontend-commons/fileValidation/docxCheck", () => ({ checkDocx: checks.docx }));
vi.mock("@iap/frontend-commons/fileValidation/docCheck", () => ({ checkDoc: checks.doc }));

beforeEach(() => {
  Object.values(checks).forEach(check => check.mockReset().mockResolvedValue({ valid: true }));
});

afterEach(() => {
  vi.restoreAllMocks();
});

function createUpload(name: string, size = 3): File {
  const file = new File([ new Uint8Array([ 0x25, 1, 0 ]) ], name);
  Object.defineProperty(file, "size", { value: size });
  return file;
}

/** An upload the browser recognised, so it carries a MIME type as a real one would. */
function createTypedUpload(name: string, type: string): File {
  const file = new File([ new Uint8Array([ 0x25, 1, 0 ]) ], name, { type });
  Object.defineProperty(file, "size", { value: 3 });
  return file;
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
    await expect(validateUpload(createUpload("proposal.pdf", 0))).resolves.toBe("proposal.pdf is empty.");
  });

  it("refuses one over the size limit, and says the limit", async () => {
    await expect(validateUpload(createUpload("proposal.pdf", MAX_FILE_SIZE + 1))).resolves.toMatch(/50 MB/);
  });

  it("allows one exactly at the limit", async () => {
    await expect(validateUpload(createUpload("proposal.pdf", MAX_FILE_SIZE))).resolves.toBeUndefined();
  });

  it("takes any type when none is asked for", async () => {
    await expect(validateUpload(createUpload("notes.txt"))).resolves.toBeUndefined();
    await expect(validateUpload(createUpload("README"))).resolves.toBeUndefined();
  });

  it("refuses what the pipeline cannot read, when asked for only that", async () => {
    await expect(validateUpload(createUpload("proposal.txt"), PIPELINE_TYPES))
      .resolves.toBe("proposal.txt is not a file of an accepted type: .pdf, .docx, .doc.");
    await expect(validateUpload(createUpload("proposal"), PIPELINE_TYPES)).resolves.toMatch(/accepted type/);
  });

  it("does not read an entry written as an extension as a type", async () => {
    await expect(validateUpload(createUpload("proposal.pdf"), [ ".pdf" ])).resolves.toMatch(/accepted type: .pdf\.$/);
  });

  it("honours the MIME types the requirement asks for", async () => {
    await expect(validateUpload(createTypedUpload("note.png", "image/png"), [ "image/png" ])).resolves.toBeUndefined();
    await expect(validateUpload(createTypedUpload("note.png", "image/png"), [ "application/pdf" ]))
      .resolves.toMatch(/accepted type: .pdf\.$/);
  });

  it("names a known type by its extension, once, and any other type as given", async () => {
    await expect(validateUpload(createUpload("notes.txt"), [ "application/pdf", ".PDF", "image/png" ]))
      .resolves.toBe("notes.txt is not a file of an accepted type: .pdf, image/png.");
  });

  it("reads the type from the name when the browser could not tell", async () => {
    await expect(validateUpload(createUpload("proposal.pdf"), [ "application/pdf" ])).resolves.toBeUndefined();
    await expect(validateUpload(createTypedUpload("proposal.pdf", "application/octet-stream"), [ "application/pdf" ]))
      .resolves.toBeUndefined();
  });

  it("compares types without case or parameters", async () => {
    await expect(validateUpload(createTypedUpload("proposal.pdf", "application/pdf; x=y"), [ "Application/PDF" ]))
      .resolves.toBeUndefined();
  });

  it("refuses an untyped file whose name is not an accepted kind", async () => {
    expect(await validateUpload(createUpload("notes.xyz"), [ "application/pdf" ])).toMatch(/accepted type/);
  });

  it("refuses an untyped file with no extension where a type is asked for, since nothing says what it is", async () => {
    expect(await validateUpload(createUpload("README"), [ "application/pdf" ])).toMatch(/accepted type/);
  });
});

describe("what is inside the file", () => {
  it("is checked by the check for its extension, with the default limits", async () => {
    await validateUpload(createUpload("a.PDF"));
    await validateUpload(createUpload("b.docx"));
    await validateUpload(createUpload("c.doc"));

    expect(checks.pdf).toHaveBeenCalledWith(expect.objectContaining({ name: "a.PDF" }), MAX_PDF_PAGES, undefined);
    expect(checks.docx).toHaveBeenCalledWith(expect.objectContaining({ name: "b.docx" }), MAX_UNZIPPED_SIZE, undefined);
    expect(checks.doc).toHaveBeenCalledWith(expect.objectContaining({ name: "c.doc" }));
  });

  it("is not looked at for any other extension", async () => {
    await validateUpload(createUpload("notes.txt"));
    await validateUpload(createUpload("README"));

    Object.values(checks).forEach(check => expect(check).not.toHaveBeenCalled());
  });

  it("is refused with the file name before what its check found", async () => {
    checks.docx.mockResolvedValue({ valid: false, error: "It is not a Word document." });

    expect(await validateUpload(createUpload("proposal.docx"))).toBe("proposal.docx: It is not a Word document.");
  });

  it("is not looked at when the file was already refused", async () => {
    await validateUpload(createUpload("proposal.pdf", 0));
    await validateUpload(createUpload("proposal.pdf"), [ "image/png" ]);

    expect(checks.pdf).not.toHaveBeenCalled();
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
});

describe("how sizes read", () => {
  it("never shows a file just over the limit at the limit's size", async () => {
    expect(await validateUpload(createUpload("proposal.pdf", 10_000_001), [], { maxFileSize: 10_000_000 }))
      .toBe("proposal.pdf is 9.54 MB, and the limit is 9.53 MB.");
  });

  it("gives a size under a megabyte in KB", async () => {
    expect(await validateUpload(createUpload("proposal.pdf", 20 * 1024), [], { maxFileSize: 10 * 1024 }))
      .toBe("proposal.pdf is 20 KB, and the limit is 10 KB.");
  });
});

describe("limits set by the caller", () => {
  it("refuse a file over a smaller size limit, and say that limit", async () => {
    await expect(validateUpload(createUpload("proposal.pdf", 2 * 1024 * 1024), [], { maxFileSize: 1024 * 1024 }))
      .resolves.toMatch(/2 MB.*limit is 1 MB/);
  });

  it("reach the check for the file's content", async () => {
    const controller = new AbortController();

    await validateUpload(createUpload("a.pdf"), [], { maxPdfPages: 10 }, controller.signal);
    await validateUpload(createUpload("b.docx"), [], { maxUnzippedSize: 2000 }, controller.signal);

    expect(checks.pdf).toHaveBeenCalledWith(expect.any(File), 10, controller.signal);
    expect(checks.docx).toHaveBeenCalledWith(expect.any(File), 2000, controller.signal);
  });

  it("keep the default for each one left out", async () => {
    await validateUpload(createUpload("proposal.pdf"), [], { maxFileSize: MAX_FILE_SIZE * 2 });

    expect(checks.pdf).toHaveBeenCalledWith(expect.any(File), MAX_PDF_PAGES, undefined);
  });
});

describe("stopping a check", () => {
  it("does not start one the caller has already given up on", async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(validateUpload(createUpload("proposal.pdf"), [], {}, controller.signal))
      .rejects.toMatchObject({ name: "AbortError" });
    expect(checks.pdf).not.toHaveBeenCalled();
  });
});
