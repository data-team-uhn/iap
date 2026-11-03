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

import type { ContentCheck } from "./contentCheck";

// A legacy .doc is an OLE compound file, which always starts with these eight bytes.
const OLE_MAGIC = [ 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 ];

/**
 * Refuses a .doc that does not start the way every OLE file does.
 *
 * @param file the file, already known to be named .doc
 */
export async function checkDoc(file: File): Promise<ContentCheck> {
  try {
    const bytes = new Uint8Array(await file.slice(0, OLE_MAGIC.length).arrayBuffer());
    if (!OLE_MAGIC.every((value, index) => bytes[index] === value)) {
      return { valid: false, error: "It is not a Word document." };
    }
    return { valid: true };
  } catch {
    return { valid: false, error: "The document is damaged, or it is not a .doc file." };
  }
}
