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

import { listed } from "@iap/frontend-commons/words";

describe("listed", () => {
  it("joins the last word with \"and\" and the others with commas", () => {
    expect(listed([ "added", "removed", "moved" ])).toBe("added, removed and moved");
    expect(listed([ "added", "moved" ])).toBe("added and moved");
  });

  it("gives a single word as it is, and nothing for none", () => {
    expect(listed([ "added" ])).toBe("added");
    expect(listed([])).toBe("");
  });
});
