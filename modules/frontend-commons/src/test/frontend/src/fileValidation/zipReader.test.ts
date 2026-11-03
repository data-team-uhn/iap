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

import { readZipDirectory, readZipEntry } from "@iap/frontend-commons/fileValidation/zipReader";
import type { ZipEntry } from "@iap/frontend-commons/fileValidation/zipReader";

import { WORD_PARTS, createZip, findDirectoryEntry } from "./zip.fixture";

function readEntries(bytes: Uint8Array<ArrayBuffer>): ZipEntry[] | undefined {
  return readZipDirectory(bytes.buffer);
}

async function readText(bytes: Uint8Array<ArrayBuffer>, entry: ZipEntry, maxBytes = 1024): Promise<string | undefined> {
  const content = await readZipEntry(bytes.buffer, entry, maxBytes);
  return content === undefined ? undefined : new TextDecoder().decode(content);
}

describe("readZipDirectory", () => {
  it("lists each entry with the sizes the directory states", async () => {
    const entries = readEntries(await createZip(WORD_PARTS));

    expect(entries?.map(entry => entry.name)).toEqual([ "[Content_Types].xml", "word/document.xml" ]);
    expect(entries?.[1].unzippedSize).toBe("<w:document/>".length);
  });

  it("reads a zip that ends with a comment", async () => {
    expect(readEntries(await createZip(WORD_PARTS, { comment: "c".repeat(300) }))).toHaveLength(2);
  });

  // Some writers use ZIP64 for every archive, however small
  it("reads a small ZIP64 archive", async () => {
    const [ types, document ] = WORD_PARTS;
    const parts = [ types, { ...document, claimed: 600 * 1024 * 1024 } ];
    const entries = readEntries(await createZip(parts, { zip64: true }));

    expect(entries?.[1].unzippedSize).toBe(600 * 1024 * 1024);
  });

  it("reads nothing from what is not a zip", () => {
    expect(readEntries(new Uint8Array([ 0x25, 1, 0 ]))).toBeUndefined();
  });

  it("reads nothing when the directory points at the wrong place", async () => {
    const bytes = await createZip(WORD_PARTS);
    // The directory's offset sits 16 bytes into the end record, which is the last 22 bytes
    new DataView(bytes.buffer).setUint32(bytes.length - 22 + 16, 0, true);

    expect(readEntries(bytes)).toBeUndefined();
  });

  it("reads nothing when a size is marked ZIP64 but there is no ZIP64 record", async () => {
    const bytes = await createZip(WORD_PARTS);
    new DataView(bytes.buffer).setUint32(findDirectoryEntry(bytes) + 24, 0xFFFFFFFF, true);

    expect(readEntries(bytes)).toBeUndefined();
  });

  it("reads nothing from a zip that names an entry twice", async () => {
    const [ types ] = WORD_PARTS;

    expect(readEntries(await createZip([ ...WORD_PARTS, types ]))).toBeUndefined();
  });
});

describe("readZipEntry", () => {
  it("unzips a deflated entry", async () => {
    const bytes = await createZip(WORD_PARTS);
    const [ , document ] = readEntries(bytes) ?? [];

    expect(await readText(bytes, document)).toBe("<w:document/>");
  });

  it("reads an entry stored without compression", async () => {
    const bytes = await createZip(WORD_PARTS.map(part => ({ ...part, stored: true })));
    const [ , document ] = readEntries(bytes) ?? [];

    expect(await readText(bytes, document)).toBe("<w:document/>");
  });

  it("stops once the content grows past the limit, whatever size the zip states", async () => {
    const parts = [ { name: "big.xml", text: " ".repeat(2000), claimed: 10 } ];
    const deflated = await createZip(parts);
    const stored = await createZip(parts.map(part => ({ ...part, stored: true })));

    expect(await readText(deflated, (readEntries(deflated) ?? [])[0], 1000)).toBeUndefined();
    expect(await readText(stored, (readEntries(stored) ?? [])[0], 1000)).toBeUndefined();
  });

  it("reads nothing compressed some other way", async () => {
    const bytes = await createZip(WORD_PARTS);
    const [ types ] = readEntries(bytes) ?? [];

    // 12 is bzip2, which neither this reader nor Word uses
    expect(await readText(bytes, { ...types, method: 12 })).toBeUndefined();
  });
});
