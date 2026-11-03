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
import { describe, expect, it } from "vitest";

import { checkDocx } from "@iap/frontend-commons/fileValidation/docxCheck";

import { WORD_PARTS, createZip, getContentTypes } from "./zip.fixture";

import type { ZipPart } from "./zip.fixture";

const MAX_UNZIPPED_SIZE = 512 * 1024 * 1024;

async function createDocx(parts: ZipPart[] = WORD_PARTS, zip64 = false): Promise<File> {
  return new File([ await createZip(parts, { zip64 }) ], "proposal.docx");
}

async function getError(file: File, maxUnzippedSize = MAX_UNZIPPED_SIZE): Promise<string | undefined> {
  const result = await checkDocx(file, maxUnzippedSize);
  return result.valid ? undefined : result.error;
}

describe("checkDocx", () => {
  it("accepts a .docx that holds what a Word document holds", async () => {
    expect(await getError(await createDocx())).toBeUndefined();
  });

  it("refuses a .docx missing the parts that make it one", async () => {
    const [ , document ] = WORD_PARTS;

    expect(await getError(await createDocx([ document ]))).toBe("It is not a Word document.");
  });

  it("refuses a zip whose content types name no Word document", async () => {
    const [ , document ] = WORD_PARTS;
    const sheet = { name: "[Content_Types].xml", text: getContentTypes("spreadsheetml.sheet") };

    expect(await getError(await createDocx([ sheet, document ]))).toBe("It is not a Word document.");
  });

  it("refuses a .docx that is not a zip", async () => {
    expect(await getError(new File([ new Uint8Array([ 0x25, 1, 0 ]) ], "proposal.docx"))).toMatch(/damaged/);
  });

  it("refuses a .docx that unzips to more than the limit", async () => {
    const [ types ] = WORD_PARTS;
    const file = await createDocx([ types, { name: "word/document.xml", text: "x".repeat(4000) } ]);

    expect(file.size).toBeLessThan(1000);
    expect(await getError(file, 2000)).toBe("It unzips to more than 1.95 KB.");
    expect(await getError(file, 8000)).toBeUndefined();
  });

  it("refuses a ZIP64 archive that unzips to more than the limit", async () => {
    const [ types, document ] = WORD_PARTS;
    const file = await createDocx([ types, { ...document, claimed: 600 * 1024 * 1024 } ], true);

    expect(await getError(file)).toBe("It unzips to more than 512 MB.");
  });

  it("refuses content types that unzip past what a real one could hold", async () => {
    const types = {
      name: "[Content_Types].xml",
      text: getContentTypes("wordprocessingml.document") + " ".repeat(300_000),
      claimed: 1024,
    };

    expect(await getError(await createDocx([ types ]))).toMatch(/damaged/);
  });

  it("stops when the caller gives up", async () => {
    const controller = new AbortController();
    const pending = checkDocx(await createDocx(), MAX_UNZIPPED_SIZE, controller.signal);

    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });
});
