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

import { dateValue, dayOf } from "@iap/frontend-commons/entityGrid/columns";

describe("dateValue", () => {
  it("reads a stored timestamp, as text or as a number, and nothing else", () => {
    expect(dateValue("2026-10-08T09:30:00.000-04:00")).toEqual(new Date("2026-10-08T09:30:00.000-04:00"));
    expect(dateValue(0)).toEqual(new Date(0));
    expect(dateValue(undefined)).toBeNull();
    expect(dateValue({})).toBeNull();
  });
});

describe("dayOf", () => {
  it("gives the day of a stored timestamp, and nothing for a missing one", () => {
    expect(dayOf("2026-10-08T09:30:00.000")).toBe(new Date("2026-10-08T09:30:00.000").toLocaleDateString());
    expect(dayOf(undefined)).toBeUndefined();
  });
});
