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

import { checkDoc } from "@iap/frontend-commons/fileValidation/docCheck";

function createDoc(bytes: number[]): File {
  return new File([ new Uint8Array(bytes) ], "proposal.doc");
}

describe("checkDoc", () => {
  it("accepts a .doc that starts the way one does", async () => {
    expect(await checkDoc(createDoc([ 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 ]))).toEqual({ valid: true });
  });

  it("refuses a .doc that does not", async () => {
    expect(await checkDoc(createDoc([ 0x50, 0x4B, 3, 4, 0, 0, 0, 0 ])))
      .toEqual({ valid: false, error: "It is not a Word document." });
  });
});
