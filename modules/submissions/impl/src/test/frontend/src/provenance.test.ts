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
  type ProvenancePassage,
  type QuestionProvenance,
  badgeFor,
  isLowConfidence,
  leadPassage,
  matchesSuggestion,
  openLabel,
  showsConfidence,
  splitQuote,
  statusOf,
  triggerLabel,
} from "@iap/submissions/provenance";

const PASSAGE: ProvenancePassage = {
  quote: "Participants receive pembrolizumab 200 mg IV every three weeks, supplied by the sponsor.",
  span: "pembrolizumab 200 mg IV every three weeks",
  cite: "p. 9 · §5.1 Study treatment",
};

function provenance(overrides: Partial<QuestionProvenance> = {}): QuestionProvenance {
  return {
    suggested: [ "Yes" ],
    confidence: 0.9,
    passages: [ PASSAGE ],
    reviewed: false,
    evidenceRejected: false,
    ...overrides,
  };
}

describe("what state a suggestion is in", () => {
  it("is suggested until the submitter has settled it", () => {
    expect(statusOf(provenance(), [ "Yes" ])).toBe("suggested");
  });

  it("is accepted once settled and still saying what was suggested", () => {
    expect(statusOf(provenance({ reviewed: true }), [ "Yes" ])).toBe("accepted");
  });

  it("is changed once settled and saying something else", () => {
    expect(statusOf(provenance({ reviewed: true }), [ "No" ])).toBe("changed");
  });

  // Rejecting the evidence says the extraction cited badly. It is not a verdict on the answer, so
  // it must not settle one.
  it("is unaffected by the passage being rejected", () => {
    expect(statusOf(provenance({ evidenceRejected: true }), [ "Yes" ])).toBe("suggested");
  });

  it("counts a reordered multi-value answer as a change the submitter made", () => {
    const multi = provenance({ suggested: [ "Drug", "Biologic" ], reviewed: true });
    expect(statusOf(multi, [ "Biologic", "Drug" ])).toBe("changed");
    expect(matchesSuggestion(multi, [ "Drug", "Biologic" ])).toBe(true);
  });

  it("counts an answer with an extra value as a change", () => {
    expect(matchesSuggestion(provenance(), [ "Yes", "No" ])).toBe(false);
  });
});

describe("what each state is called", () => {
  it("names the three states without glyphs", () => {
    expect(badgeFor("suggested")).toBe("AI found:");
    expect(badgeFor("accepted")).toBe("Matches your protocol");
    expect(badgeFor("changed")).toBe("You corrected this");
  });
});

describe("when confidence is worth saying", () => {
  it("calls anything under the floor low", () => {
    expect(isLowConfidence(provenance({ confidence: LOW_CONFIDENCE_FLOOR - 0.01 }))).toBe(true);
    expect(isLowConfidence(provenance({ confidence: LOW_CONFIDENCE_FLOOR }))).toBe(false);
  });

  // An amber chip on every answer teaches a reader to ignore amber, so only doubt is badged
  it("says nothing about a confident suggestion", () => {
    expect(showsConfidence(provenance({ confidence: 0.9 }), [ "Yes" ])).toBe(false);
  });

  it("flags a doubtful one the submitter has not looked at", () => {
    expect(showsConfidence(provenance({ confidence: 0.4 }), [ "Yes" ])).toBe(true);
  });

  // Once it is settled the chip has nothing to tell anyone: the submitter has already judged it
  it("stops flagging one the submitter has settled", () => {
    expect(showsConfidence(provenance({ confidence: 0.4, reviewed: true }), [ "Yes" ])).toBe(false);
  });
});

describe("what the collapsed line quotes", () => {
  it("quotes the words the extraction keyed on", () => {
    expect(triggerLabel(PASSAGE)).toBe("“pembrolizumab 200 mg IV every three weeks…”");
  });

  // Without a span the opening has to stand in, because the row still has to say what the evidence is
  it("falls back to the opening of the passage when no span was named", () => {
    expect(triggerLabel({ quote: "One two three four five six seven eight" }))
      .toBe("“One two three four five six…”");
  });

  it("copes with a passage of fewer words than the fallback takes", () => {
    expect(triggerLabel({ quote: "Two words" })).toBe("“Two words…”");
  });
});

describe("marking the passage", () => {
  it("splits the quote around the words the extraction keyed on", () => {
    expect(splitQuote(PASSAGE)).toEqual({
      before: "Participants receive ",
      span: "pembrolizumab 200 mg IV every three weeks",
      after: ", supplied by the sponsor.",
    });
  });

  it("marks nothing when no span was named", () => {
    expect(splitQuote({ quote: "Plain passage." }))
      .toEqual({ before: "Plain passage.", span: "", after: "" });
  });

  // Guessing at a position would mark the wrong words, which is worse than marking none
  it("marks nothing when the span is not in the quote it claims to come from", () => {
    expect(splitQuote({ quote: "Plain passage.", span: "absent" }))
      .toEqual({ before: "Plain passage.", span: "", after: "" });
  });
});

describe("where a passage says it lives", () => {
  it("offers to open a passage that names its place", () => {
    expect(openLabel(PASSAGE)).toBe("Open in protocol");
  });

  // Some sources carry no usable structure, so the link must not promise a spot it cannot reach
  it("offers only to search for one that does not", () => {
    expect(openLabel({ quote: "Somewhere in here." })).toBe("Find in protocol");
  });
});

describe("which passage leads", () => {
  it("leads with the one the extraction offered first", () => {
    expect(leadPassage(provenance())).toBe(PASSAGE);
  });

  it("has none when the extraction offered none", () => {
    expect(leadPassage(provenance({ passages: [] }))).toBeUndefined();
  });
});
