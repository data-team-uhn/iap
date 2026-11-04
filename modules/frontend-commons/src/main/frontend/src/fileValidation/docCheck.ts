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

import type { ContentCheck } from "./contentCheck";

// How a .doc starts. A legacy Word file is an OLE compound file. Word also saves RTF under .doc, and
// the parser converts that too.
const SIGNATURES = [
  [ 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 ],
  [ 0x7B, 0x5C, 0x72, 0x74, 0x66 ],
];

const LONGEST_SIGNATURE = Math.max(...SIGNATURES.map(signature => signature.length));

/**
 * Refuses a .doc that starts neither the way an OLE file does nor the way RTF does.
 *
 * @param file the file, already known to be named .doc
 */
export async function checkDoc(file: File): Promise<ContentCheck> {
  try {
    const bytes = new Uint8Array(await file.slice(0, LONGEST_SIGNATURE).arrayBuffer());
    if (!SIGNATURES.some(signature => signature.every((value, index) => bytes[index] === value))) {
      return { valid: false, error: NOT_A_WORD_DOCUMENT };
    }
    return { valid: true };
  } catch {
    return { valid: false, error: "The document is damaged, or it is not a .doc file." };
  }
}
