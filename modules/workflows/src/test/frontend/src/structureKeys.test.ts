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

import { keyOf, keyProblems } from "@iap/workflows/structureKeys";

import { parameter } from "./vocabulary.fixture";

const VALUE = parameter("value");
const CHOICES = parameter("choices", {
  label: "Choices", dataType: "structure", maxValues: 0, keyParameter: "value", parameters: [ VALUE ],
});
const FIELDS = parameter("fields", {
  label: "Fields", dataType: "structure", maxValues: 0, keyParameter: "name", parameters: [ parameter("name"), CHOICES ],
});

const record = (name: string, ...choices: string[]) =>
  ({ name: [ name ], choices: choices.map(value => ({ value: [ value ] })) });

describe("keyProblems", () => {
  it("flags a later record of a key, comparing keys trimmed, and a record with content but no key", () => {
    const records = [ record("a"), record(" a "), { name: [ " " ], choices: [ { value: [ "x" ] } ] }, record("b") ];

    expect(keyProblems(FIELDS, records)).toEqual([ undefined, "repeated", "blank", undefined ]);
  });

  it("leaves a record with nothing in it alone, as a draft", () => {
    expect(keyProblems(FIELDS, [ {}, { name: [ "" ] } ])).toEqual([ undefined, undefined ]);
  });

  it("flags nothing in a structure with no key", () => {
    expect(keyProblems({ ...FIELDS, keyParameter: undefined }, [ record("a"), record("a") ]))
      .toEqual([ undefined, undefined ]);
    expect(keyOf({ ...FIELDS, keyParameter: undefined }, record("a"))).toBe("");
  });

  it("flags nothing in a structure whose key names no field it has", () => {
    expect(keyProblems({ ...FIELDS, keyParameter: "missing" }, [ record("a"), record("a") ]))
      .toEqual([ undefined, undefined ]);
  });

  it("flags nothing in a structure whose key names a field holding a list", () => {
    const listed = { ...FIELDS, parameters: [ parameter("name", { maxValues: 0 }), CHOICES ] };

    expect(keyProblems(listed, [ record("a"), record("a") ])).toEqual([ undefined, undefined ]);
  });

  it("flags nothing in a structure whose key names a structure", () => {
    expect(keyProblems({ ...FIELDS, keyParameter: "choices" }, [ record("a", "x"), record("b", "x") ]))
      .toEqual([ undefined, undefined ]);
  });
});
