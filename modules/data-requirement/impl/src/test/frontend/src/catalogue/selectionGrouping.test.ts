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

import {
  countFields,
  countPhiFields,
  groupSelection,
} from "@iap/data-requirement/catalogue/selectionGrouping";

import { catalogue, collection, database, sampleCatalogue } from "./fixtures";

describe("gathering a selection under the collections it came from", () => {
  it("has nothing to gather when nothing is chosen", () => {
    expect(groupSelection(sampleCatalogue(), new Set())).toEqual([]);
  });

  it("leaves out a collection nothing was chosen from", () => {
    const groups = groupSelection(sampleCatalogue(), new Set([ "records/Patient/birthDate" ]));

    expect(groups).toHaveLength(1);
    expect(groups[0].collectionIdentifier).toBe("Patient");
    expect(groups[0].databaseIdentifier).toBe("records");
    expect(groups[0].fields.map(each => each.identifier)).toEqual([ "birthDate" ]);
  });

  // The panel lists a selection in the order the tree offered it, because a reader comparing the two
  // is checking their own work
  it("keeps the catalogue's order rather than the order keys were given in", () => {
    const groups = groupSelection(sampleCatalogue(), new Set([
      "registry/Consent/status",
      "records/Patient/gender",
      "records/Patient/birthDate",
    ]));

    expect(groups.map(each => each.collectionIdentifier)).toEqual([ "Patient", "Consent" ]);
    expect(groups[0].fields.map(each => each.identifier)).toEqual([ "birthDate", "gender" ]);
  });

  it("passes over a key the catalogue does not offer", () => {
    const groups = groupSelection(sampleCatalogue(), new Set([
      "records/Patient/birthDate",
      "records/Patient/invented",
    ]));

    expect(countFields(groups)).toBe(1);
  });
});

describe("counting what was chosen", () => {
  it("counts nothing across an empty selection", () => {
    expect(countFields([])).toBe(0);
    expect(countPhiFields([])).toBe(0);
  });

  it("counts across every collection, not just the first", () => {
    const groups = groupSelection(sampleCatalogue(), new Set([
      "records/Patient/birthDate",
      "records/Encounter/period",
      "registry/Consent/status",
    ]));

    expect(countFields(groups)).toBe(3);
  });

  it("counts the fields the catalogue flagged as identifying somebody", () => {
    const flagged = catalogue([
      database("records", [ collection("records", "Patient", [ "birthDate", "gender" ], { phi: true }) ]),
    ]);
    const groups = groupSelection(flagged, new Set([ "records/Patient/birthDate", "records/Patient/gender" ]));

    expect(countPhiFields(groups)).toBe(2);
  });

  // Nobody assessed it, which is not the same as having assessed it and found it clear
  it("does not count a field nobody assessed", () => {
    const groups = groupSelection(sampleCatalogue(), new Set([ "records/Patient/birthDate" ]));

    expect(countPhiFields(groups)).toBe(0);
  });

  it("does not count a field assessed and found clear", () => {
    const assessed = catalogue([
      database("records", [ collection("records", "Patient", [ "gender" ], { phi: false }) ]),
    ]);
    const groups = groupSelection(assessed, new Set([ "records/Patient/gender" ]));

    expect(countPhiFields(groups)).toBe(0);
  });
});
