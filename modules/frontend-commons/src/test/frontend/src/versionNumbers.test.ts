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

import { nextVersionLabel } from "@iap/frontend-commons/versionNumbers";

describe("nextVersionLabel", () => {
  it("starts at 1.0", () => {
    expect(nextVersionLabel([])).toBe("1.0");
  });

  it("follows the largest number a version is named with, however the name spells it", () => {
    // The largest decides, so the numbers missing below it are never handed out again
    expect(nextVersionLabel([ "v1", "V4", "2.0" ])).toBe("5.0");
  });

  it("reads no number into a name that does not start with one", () => {
    expect(nextVersionLabel([ "importedByHand", "v2" ])).toBe("3.0");
  });
});
