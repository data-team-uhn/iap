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

import { resolveDisplayConfig, type DisplayOverride } from "@iap/data-requirement/catalogue/display";
import {
  collectionLabel,
  fieldLabel,
  humaniseIdentifier,
  sentenceCaseLabel,
  toSentenceCase,
} from "@iap/data-requirement/catalogue/labels";

const config = (override?: DisplayOverride) => resolveDisplayConfig(override);

describe("turning a source's own name into something readable", () => {
  it("splits a camel-cased identifier into words", () => {
    expect(toSentenceCase("birthDate")).toBe("Birth date");
    expect(toSentenceCase("deceasedDateTime")).toBe("Deceased date time");
  });

  it("leaves a name that is already words alone but for its capital", () => {
    expect(toSentenceCase("birth date")).toBe("Birth date");
  });

  it("has nothing to say about an empty name", () => {
    expect(toSentenceCase("   ")).toBe("");
  });

  it("prefers a configured spelling for an identifier that humanises badly", () => {
    expect(humaniseIdentifier("id", config().labelOverrides)).toBe("ID");
  });

  it("humanises an identifier nobody wrote an override for", () => {
    expect(humaniseIdentifier("birthDate", config().labelOverrides)).toBe("Birth date");
  });

  it("has nothing to say about an empty identifier", () => {
    expect(humaniseIdentifier("  ", config().labelOverrides)).toBe("");
  });
});

describe("lowering curated Title Case to a sentence", () => {
  it("keeps the first word capitalised and lowers the rest", () => {
    expect(sentenceCaseLabel("Date of Birth", [])).toBe("Date of birth");
    expect(sentenceCaseLabel("Duration (Minutes)", [])).toBe("Duration (minutes)");
  });

  // Safe by their shape, so no list has to name them
  it("leaves acronyms and type names as they are", () => {
    expect(sentenceCaseLabel("UHN Master Patient Identifier (MRN)", []))
      .toBe("UHN master patient identifier (MRN)");
    expect(sentenceCaseLabel("Related DocumentReference", [])).toBe("Related DocumentReference");
    expect(sentenceCaseLabel("Code ICD10", [])).toBe("Code ICD10");
  });

  // The ones no shape can tell from an ordinary word: a deployment names them
  it("keeps a word a deployment said to keep, however it was written", () => {
    expect(sentenceCaseLabel("Referred by Medly", [ "Medly" ])).toBe("Referred by Medly");
    expect(sentenceCaseLabel("Referred by medly", [ "Medly" ])).toBe("Referred by Medly");
  });

  // Longest first, so a phrase beats a word inside it
  it("prefers the longest phrase a deployment named", () => {
    expect(sentenceCaseLabel("The Master Patient Identifier", [ "Master", "Master Patient Identifier" ]))
      .toBe("The Master Patient Identifier");
  });

  it("passes a label through when nothing is preserved and nothing is Title Case", () => {
    expect(sentenceCaseLabel("date of birth", [])).toBe("date of birth");
  });
});

describe("what a field is shown as", () => {
  it("sentence-cases a curated label", () => {
    expect(fieldLabel("Date of Birth", "birthDate", config()))
      .toEqual({ label: "Date of birth", labelIsFallback: false });
  });

  it("shows a curated label as written where a deployment turned the lowering off", () => {
    expect(fieldLabel("Date of Birth", "birthDate", config({ tree: { field: { sentenceCaseLabels: false } } })))
      .toEqual({ label: "Date of Birth", labelIsFallback: false });
  });

  it("falls back on the identifier, and says that is what happened", () => {
    expect(fieldLabel(undefined, "birthDate", config()))
      .toEqual({ label: "Birth date", labelIsFallback: true });
  });

  it("treats a blank curated label as none at all", () => {
    expect(fieldLabel("   ", "birthDate", config()))
      .toEqual({ label: "Birth date", labelIsFallback: true });
  });

  it("shows the identifier raw where a deployment turned humanising off", () => {
    expect(fieldLabel(undefined, "birthDate",
      config({ tree: { field: { humaniseIdentifierWhenNoCommonName: false } } })))
      .toEqual({ label: "birthDate", labelIsFallback: true });
  });
});

describe("what a collection is shown as", () => {
  it("reads a bare FHIR-style name as English", () => {
    expect(collectionLabel(undefined, "AllergyIntolerance", config())).toBe("Allergy intolerance");
  });

  // A catalogue is authored content, so a curated label is somebody's deliberate wording
  it("prefers a curated label", () => {
    expect(collectionLabel("Patient Records", "Patient", config())).toBe("Patient records");
  });

  it("shows a curated label as written where the lowering is off", () => {
    expect(collectionLabel("Patient Records", "Patient",
      config({ tree: { field: { sentenceCaseLabels: false } } }))).toBe("Patient Records");
  });

  // The model falls back to the identifier when nothing curated a label, so the two arrive equal
  it("reads the identifier when the label is only a copy of it", () => {
    expect(collectionLabel("AllergyIntolerance", "AllergyIntolerance", config()))
      .toBe("Allergy intolerance");
  });
});
