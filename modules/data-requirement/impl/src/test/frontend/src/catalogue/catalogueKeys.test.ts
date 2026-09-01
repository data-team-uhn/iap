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

import { describeCardinality } from "@iap/data-requirement/catalogue/cardinality";
import {
  allFields,
  knownFieldKeys,
  missingFrom,
} from "@iap/data-requirement/catalogue/catalogueKeys";
import { EMPTY_CATALOGUE } from "@iap/data-requirement/catalogue/types";

import { sampleCatalogue } from "./fixtures";

describe("reading keys back against a catalogue", () => {
  it("flattens every field out of the tree", () => {
    expect(allFields(sampleCatalogue()).map(each => each.identifier))
      .toEqual([ "birthDate", "gender", "period", "status", "dateGiven" ]);
  });

  it("knows every key the catalogue offers", () => {
    expect(knownFieldKeys(sampleCatalogue()).has("registry/Consent/dateGiven")).toBe(true);
    expect(knownFieldKeys(sampleCatalogue()).size).toBe(5);
  });

  it("has nothing to offer from an empty catalogue", () => {
    expect(allFields(EMPTY_CATALOGUE)).toEqual([]);
    expect(knownFieldKeys(EMPTY_CATALOGUE).size).toBe(0);
  });

  // Information about how much has moved on, not a repair: a selection is read against its own version
  it("names the keys a catalogue does not offer", () => {
    expect(missingFrom(sampleCatalogue(), [ "records/Patient/birthDate", "records/Patient/gone" ]))
      .toEqual([ "records/Patient/gone" ]);
  });

  it("finds nothing missing when the catalogue still offers everything", () => {
    expect(missingFrom(sampleCatalogue(), [ "records/Patient/birthDate" ])).toEqual([]);
  });
});

describe("saying a cardinality in words", () => {
  it("says what each notation means", () => {
    expect(describeCardinality("0..1")?.glyph).toBe("value: optional");
    expect(describeCardinality("1..1")?.required).toBe(true);
    expect(describeCardinality("0..*")?.glyph).toBe("value: optional, many");
    expect(describeCardinality("1..*")?.required).toBe(true);
  });

  // Showing raw notation would be worse than showing nothing
  it("says nothing about a notation it does not recognise", () => {
    expect(describeCardinality("2..7")).toBeUndefined();
    expect(describeCardinality("")).toBeUndefined();
  });

  it("keeps the notation itself for the tooltip", () => {
    expect(describeCardinality("0..1")?.tip).toContain("(0..1)");
  });
});
