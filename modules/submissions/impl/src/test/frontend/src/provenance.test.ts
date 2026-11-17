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
  LOW_CONFIDENCE_FLOOR,
  type QuestionProvenance,
  canOpen,
  getBadge,
  getFurtherPassagesLabel,
  getLeadPassage,
  getNewerOffer,
  getOpenLabel,
  getStatus,
  getTriggerLabel,
  isLowConfidence,
  isPrefilled,
  isSameValues,
  parsePdfSource,
  showsConfidence,
} from "@iap/submissions/provenance";

function provenance(overrides: Partial<QuestionProvenance> = {}): QuestionProvenance {
  return {
    suggested: [ "drug" ],
    offered: [ "drug" ],
    confidence: 0.9,
    passages: [ { quote: "The study gives participants an approved drug at the usual dose" } ],
    evidenceRejected: false,
    actedOn: false,
    ...overrides,
  };
}

describe("provenance", () => {
  it("compares answers by their values in order", () => {
    expect(isSameValues([ "a", "b" ], [ "a", "b" ])).toBe(true);
    expect(isSameValues([ "a", "b" ], [ "b", "a" ])).toBe(false);
    expect(isSameValues([ "a" ], [ "a", "b" ])).toBe(false);
  });

  it("starts a field on the suggestion only while nothing is answered and something is offered", () => {
    expect(isPrefilled(provenance(), [])).toBe(true);
    expect(isPrefilled(provenance(), [ "none" ])).toBe(false);
    expect(isPrefilled(provenance({ offered: [] }), [])).toBe(false);
    expect(isPrefilled(undefined, [])).toBe(false);
  });

  it("calls an untouched suggestion suggested, and an edit of it changed", () => {
    expect(getStatus(provenance(), [ "drug" ], [])).toBe("suggested");
    expect(getStatus(provenance(), [ "device" ], [])).toBe("changed");
  });

  it("calls a saved answer accepted when it is what the newest run says, and changed otherwise", () => {
    expect(getStatus(provenance(), [ "drug" ], [ "drug" ])).toBe("accepted");
    expect(getStatus(provenance(), [ "none" ], [ "none" ])).toBe("changed");
  });

  it("offers a newer reading only beside a saved answer it differs from", () => {
    expect(getNewerOffer(provenance(), [ "none" ])).toEqual([ "drug" ]);
    expect(getNewerOffer(provenance(), [ "drug" ])).toBeUndefined();
    expect(getNewerOffer(provenance(), [])).toBeUndefined();
    expect(getNewerOffer(provenance({ offered: [] }), [ "none" ])).toBeUndefined();
    expect(getNewerOffer(undefined, [ "none" ])).toBeUndefined();
  });

  it("names each status", () => {
    expect(getBadge("suggested")).toBe("AI found:");
    expect(getBadge("accepted")).toBe("Matches your document");
    expect(getBadge("changed")).toBe("You answered differently");
  });

  it("flags low confidence only while the suggestion is still to take", () => {
    const unsure = provenance({ confidence: LOW_CONFIDENCE_FLOOR - 0.1 });

    expect(isLowConfidence(unsure)).toBe(true);
    expect(showsConfidence(unsure, "suggested")).toBe(true);
    expect(showsConfidence(unsure, "accepted")).toBe(false);
    expect(showsConfidence(provenance(), "suggested")).toBe(false);
  });

  it("leads with the first quote and counts the others", () => {
    const one = provenance();
    const three = provenance({ passages: [ { quote: "a" }, { quote: "b" }, { quote: "c" } ] });

    expect(getLeadPassage(one)?.quote).toMatch(/^The study/);
    expect(getLeadPassage(provenance({ passages: [] }))).toBeUndefined();
    expect(getFurtherPassagesLabel(one)).toBeUndefined();
    expect(getFurtherPassagesLabel(provenance({ passages: [ { quote: "a" }, { quote: "b" } ] })))
      .toBe("+1 more passage");
    expect(getFurtherPassagesLabel(three)).toBe("+2 more passages");
  });

  it("quotes the opening words of a passage", () => {
    expect(getTriggerLabel({ quote: "The study gives participants an approved drug at the usual dose" }))
      .toBe("“The study gives participants an approved…”");
  });

  it("opens a quote with a place, and searches for one without", () => {
    expect(getOpenLabel({ quote: "x", cite: "p. 3" })).toBe("Open in document");
    expect(getOpenLabel({ quote: "x" })).toBe("Find in document");
    expect(canOpen({ quote: "x", source: "/a.pdf" })).toBe(true);
    expect(canOpen({ quote: "x" })).toBe(false);
  });

  it("reads the page off a PDF link", () => {
    expect(parsePdfSource("/a/pdfFile#page=3")).toEqual({ url: "/a/pdfFile", page: 3 });
    expect(parsePdfSource("/a/pdfFile")).toEqual({ url: "/a/pdfFile" });
    expect(parsePdfSource("/a/pdfFile#page=0")).toEqual({ url: "/a/pdfFile" });
    expect(parsePdfSource("/a/pdfFile#zoom=2")).toEqual({ url: "/a/pdfFile" });
  });
});
