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

// Real zips, written byte by byte so a test can shape them exactly.

export interface ZipPart {
  name: string;
  text: string;
  // The unzipped size the zip states, when it lies
  claimed?: number;
  // Kept as it is rather than deflated
  stored?: boolean;
}

// The [Content_Types].xml of an Office zip whose main part is of this kind
export function getContentTypes(kind: string): string {
  return `<Types><Override ContentType="application/vnd.openxmlformats-officedocument.${kind}.main+xml"/></Types>`;
}

export const WORD_PARTS: ZipPart[] = [
  { name: "[Content_Types].xml", text: getContentTypes("wordprocessingml.document") },
  { name: "word/document.xml", text: "<w:document/>" },
];

// jsdom's Blob has no stream(), so the bytes are streamed by hand
function createStream(bytes: Uint8Array<ArrayBuffer>): ReadableStream<Uint8Array<ArrayBuffer>> {
  return new ReadableStream({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    },
  });
}

function joinBytes(parts: Uint8Array[]): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  parts.reduce((at, part) => {
    bytes.set(part, at);
    return at + part.length;
  }, 0);
  return bytes;
}

async function readStream(stream: ReadableStream<Uint8Array>): Promise<Uint8Array<ArrayBuffer>> {
  const chunks: Uint8Array[] = [];
  const reader = stream.getReader();
  for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
    chunks.push(chunk.value);
  }
  return joinBytes(chunks);
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
export async function createZip(
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

// Where the first central directory entry starts: its signature, low byte first
export function findDirectoryEntry(bytes: Uint8Array): number {
  return bytes.findIndex((value, at) =>
    value === 0x50 && bytes[at + 1] === 0x4B && bytes[at + 2] === 0x01 && bytes[at + 3] === 0x02);
}
