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
import { recordValues, sameRecord, sameValues, settled, textValues } from "@iap/workflows/parameterValues";

describe("parameter values", () => {
  it("settles text and records at every level, dropping what holds nothing", () => {
    expect(settled([ " a ", " ", { name: [ " x " ], empty: [ "" ], unset: undefined }, { blank: [ " " ] } ]))
      .toEqual([ "a", { name: [ "x" ] } ]);
  });

  it("compares records field by field, a field left out holding nothing", () => {
    expect(sameRecord({ name: [ "x" ] }, { name: [ "x" ], label: [] })).toBe(true);
    expect(sameRecord({ label: [] }, { name: [ "x" ] })).toBe(false);
    expect(sameValues([ { name: [ "x" ] } ], [ "x" ])).toBe(false);
    expect(sameValues([ "x" ], [ "x", "y" ])).toBe(false);
  });

  it("separates the text from the records", () => {
    expect(textValues([ "a", { name: [] } ])).toEqual([ "a" ]);
    expect(recordValues([ "a", { name: [] } ])).toEqual([ { name: [] } ]);
  });
});
