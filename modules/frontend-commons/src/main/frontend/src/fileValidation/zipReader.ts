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

// Reads a zip's directory and single entries without unzipping the rest, so a zip that unzips to
// gigabytes can be judged without hanging the tab.

// Zip record layouts, from the zip format's APPNOTE
const END_RECORD = 0x06054B50;
const END_RECORD_SIZE = 22;
const ZIP64_LOCATOR = 0x07064B50;
const ZIP64_LOCATOR_SIZE = 20;
const ZIP64_END_RECORD = 0x06064B50;
const ZIP64_END_RECORD_SIZE = 56;
const ZIP64_EXTRA_FIELD = 0x0001;
const DIRECTORY_ENTRY = 0x02014B50;
const DIRECTORY_ENTRY_SIZE = 46;
const LOCAL_HEADER = 0x04034B50;
const LOCAL_HEADER_SIZE = 30;
const MAX_ZIP_COMMENT = 0xFFFF;
const STORED = 0;
const DEFLATED = 8;
// A field holding its largest value has the real one in a ZIP64 record
const ZIP64_COUNT = 0xFFFF;
const ZIP64_MARKER = 0xFFFFFFFF;

export interface ZipEntry {
  name: string;
  method: number;
  // As the directory states them
  storedSize: number;
  unzippedSize: number;
  // Where the entry's local header starts
  offset: number;
}

function readUint64(view: DataView, at: number): number {
  return Number(view.getBigUint64(at, true));
}

// The end record sits last, after a comment of up to 64 KB, so it is searched for backwards.
function findEndRecord(view: DataView): number | undefined {
  const last = view.byteLength - END_RECORD_SIZE;
  for (let at = last; at >= Math.max(0, last - MAX_ZIP_COMMENT); at--) {
    if (view.getUint32(at, true) === END_RECORD) {
      return at;
    }
  }
  return undefined;
}

// Where the directory starts, how many entries it has, and where it has to end
function findDirectory(view: DataView, end: number): { start: number; count: number; limit: number } | undefined {
  const count = view.getUint16(end + 10, true);
  const start = view.getUint32(end + 16, true);
  if (count !== ZIP64_COUNT && start !== ZIP64_MARKER) {
    return { start, count, limit: end };
  }
  const locator = end - ZIP64_LOCATOR_SIZE;
  if (locator < 0 || view.getUint32(locator, true) !== ZIP64_LOCATOR) {
    return undefined;
  }
  const record = readUint64(view, locator + 8);
  if (record + ZIP64_END_RECORD_SIZE > locator || view.getUint32(record, true) !== ZIP64_END_RECORD) {
    return undefined;
  }
  return { start: readUint64(view, record + 48), count: readUint64(view, record + 32), limit: record };
}

// The real values of the fields that hold the marker. The ZIP64 extra field lists only those, in
// this order.
function readZip64Fields(view: DataView, entry: ZipEntry, from: number, to: number): ZipEntry | undefined {
  for (let at = from; at + 4 <= to; at += 4 + view.getUint16(at + 2, true)) {
    if (view.getUint16(at, true) !== ZIP64_EXTRA_FIELD) {
      continue;
    }
    const fieldEnd = Math.min(to, at + 4 + view.getUint16(at + 2, true));
    let next = at + 4;
    const fields = { ...entry };
    for (const key of [ "unzippedSize", "storedSize", "offset" ] as const) {
      if (fields[key] === ZIP64_MARKER) {
        if (next + 8 > fieldEnd) {
          return undefined;
        }
        fields[key] = readUint64(view, next);
        next += 8;
      }
    }
    return fields;
  }
  return undefined;
}

/**
 * A zip's entries as its central directory lists them, read without unzipping anything.
 *
 * @returns the entries, or undefined when the directory cannot be read or names an entry twice
 */
export function readZipDirectory(data: ArrayBuffer): ZipEntry[] | undefined {
  const view = new DataView(data);
  const end = findEndRecord(view);
  const directory = end === undefined ? undefined : findDirectory(view, end);
  if (directory === undefined) {
    return undefined;
  }
  const decoder = new TextDecoder();
  const entries: ZipEntry[] = [];
  const names = new Set<string>();
  let at = directory.start;
  for (let index = 0; index < directory.count; index++) {
    if (at + DIRECTORY_ENTRY_SIZE > directory.limit || view.getUint32(at, true) !== DIRECTORY_ENTRY) {
      return undefined;
    }
    const nameLength = view.getUint16(at + 28, true);
    const extraStart = at + DIRECTORY_ENTRY_SIZE + nameLength;
    const extraEnd = extraStart + view.getUint16(at + 30, true);
    if (extraEnd > directory.limit) {
      return undefined;
    }
    let entry: ZipEntry | undefined = {
      name: decoder.decode(new Uint8Array(data, at + DIRECTORY_ENTRY_SIZE, nameLength)),
      method: view.getUint16(at + 10, true),
      storedSize: view.getUint32(at + 20, true),
      unzippedSize: view.getUint32(at + 24, true),
      offset: view.getUint32(at + 42, true),
    };
    if ([ entry.storedSize, entry.unzippedSize, entry.offset ].includes(ZIP64_MARKER)) {
      entry = readZip64Fields(view, entry, extraStart, extraEnd);
    }
    // With two entries of one name, which one counts is up to the reader, so this check and the
    // parser could read different ones
    if (entry === undefined || names.has(entry.name)) {
      return undefined;
    }
    names.add(entry.name);
    entries.push(entry);
    at = extraEnd + view.getUint16(at + 32, true);
  }
  return entries;
}

// Unzips a deflated entry, giving up once it grows past the limit
async function inflate(
  stored: Uint8Array<ArrayBuffer>,
  maxBytes: number,
  signal?: AbortSignal,
): Promise<Uint8Array | undefined> {
  const source = new ReadableStream<Uint8Array<ArrayBuffer>>({
    start(controller) {
      controller.enqueue(stored);
      controller.close();
    },
  });
  const reader = source.pipeThrough(new DecompressionStream("deflate-raw")).getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
    total += chunk.value.length;
    if (total > maxBytes || signal?.aborted) {
      await reader.cancel();
      signal?.throwIfAborted();
      return undefined;
    }
    chunks.push(chunk.value);
  }
  const bytes = new Uint8Array(total);
  chunks.reduce((at, part) => {
    bytes.set(part, at);
    return at + part.length;
  }, 0);
  return bytes;
}

/**
 * One entry's content, read straight from the zip and capped, whatever size the zip states.
 *
 * @returns the content, or undefined when it cannot be read or grows past the limit
 */
export async function readZipEntry(
  data: ArrayBuffer,
  entry: ZipEntry,
  maxBytes: number,
  signal?: AbortSignal,
): Promise<Uint8Array | undefined> {
  const view = new DataView(data);
  if (entry.offset + LOCAL_HEADER_SIZE > data.byteLength || view.getUint32(entry.offset, true) !== LOCAL_HEADER) {
    return undefined;
  }
  // The local header carries its own name and extra field, whose lengths can differ from the directory's
  const start = entry.offset + LOCAL_HEADER_SIZE + view.getUint16(entry.offset + 26, true)
    + view.getUint16(entry.offset + 28, true);
  if (start + entry.storedSize > data.byteLength) {
    return undefined;
  }
  const stored = new Uint8Array(data, start, entry.storedSize);
  if (entry.method === STORED) {
    return stored.length <= maxBytes ? stored : undefined;
  }
  return entry.method === DEFLATED ? inflate(stored, maxBytes, signal) : undefined;
}
