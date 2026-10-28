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

import { formatDate } from "@iap/frontend-commons/dates";

describe("formatDate", () => {
  it("writes a date the way the reader's locale does", () => {
    const iso = "2026-08-27T09:15:30.500-05:00";

    expect(formatDate(iso)).toBe(new Date(iso).toLocaleString());
  });

  it.each([
    [ "a string that is no date", "pending" ],
    [ "an empty string", "" ],
    [ "something that is not a string", 42 ],
    [ "nothing at all", undefined ],
  ])("writes nothing for %s", (_case, value) => {
    expect(formatDate(value)).toBe("");
  });
});
