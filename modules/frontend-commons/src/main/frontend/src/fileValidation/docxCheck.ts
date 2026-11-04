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

import { NOT_A_WORD_DOCUMENT } from "./contentCheck";
import { formatSize } from "./size";
import { readZipDirectory, readZipEntry } from "./zipReader";

import type { ContentCheck } from "./contentCheck";

// A DOCX is a zip whose content types name a Word main document. The part's name is not fixed:
// some producers call it word/document2.xml.
const WORD_MAIN_DOCUMENT = "wordprocessingml.document.main+xml";

const DOCX_DAMAGED = "The document is damaged, or it is not a .docx file.";

const CONTENT_TYPES = "[Content_Types].xml";

// A real one is a few KB. Reading stops past this, whatever size the zip states.
const MAX_CONTENT_TYPES_BYTES = 256 * 1024;

// The format allows UTF-16 as well as UTF-8. A UTF-16 part starts with its byte order mark.
function decodeXmlPart(bytes: Uint8Array): string {
  if (bytes[0] === 0xFF && bytes[1] === 0xFE) {
    return new TextDecoder("utf-16le").decode(bytes);
  }
  if (bytes[0] === 0xFE && bytes[1] === 0xFF) {
    return new TextDecoder("utf-16be").decode(bytes);
  }
  return new TextDecoder().decode(bytes);
}

/**
 * Checks the sizes the directory states before unzipping anything, then unzips only the content
 * types. Unzipping a whole document could hang the tab.
 *
 * @param file the file, already known to be named .docx
 * @param maxUnzippedSize the most it may unzip to, in bytes
 * @param signal stops the check, which then rejects with the signal's reason
 */
export async function checkDocx(file: File, maxUnzippedSize: number, signal?: AbortSignal): Promise<ContentCheck> {
  try {
    const data = await file.arrayBuffer();
    signal?.throwIfAborted();
    const entries = readZipDirectory(data);
    if (entries === undefined) {
      return { valid: false, error: DOCX_DAMAGED };
    }
    if (entries.reduce((total, entry) => total + entry.unzippedSize, 0) > maxUnzippedSize) {
      return { valid: false, error: `It unzips to more than ${formatSize(maxUnzippedSize, Math.floor)}.` };
    }
    const listed = entries.find(entry => entry.name === CONTENT_TYPES);
    if (listed === undefined) {
      return { valid: false, error: NOT_A_WORD_DOCUMENT };
    }
    const types = await readZipEntry(data, listed, MAX_CONTENT_TYPES_BYTES, signal);
    if (types === undefined) {
      return { valid: false, error: DOCX_DAMAGED };
    }
    if (!decodeXmlPart(types).includes(WORD_MAIN_DOCUMENT)) {
      return { valid: false, error: NOT_A_WORD_DOCUMENT };
    }
    return { valid: true };
  } catch {
    signal?.throwIfAborted();
    return { valid: false, error: DOCX_DAMAGED };
  }
}
