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

// Where a pre-filled answer came from, and how far the submitter has got with it.
//
// Everything here is a pure reading of what the server said plus what the submitter has done. The
// component renders it; nothing decides anything twice.

// One passage backing a pre-filled answer.
//
// Every passage here was found in the document. A quote the extraction could not find is dropped
// server-side rather than sent: it is not evidence, and showing it would have somebody read a
// sentence that is not in their protocol. The answer still shows the lower confidence it cost.
export interface ProvenancePassage {
  quote: string;
  // The words inside the quote the extraction actually keyed on, when it named them. The trigger
  // shows these rather than the first few words, which often land on a lead-in clause.
  span?: string;
  // Where the passage lives, already assembled by the server: it owns how a location reads.
  cite?: string;
}

export interface QuestionProvenance {
  // What the extraction proposed, as it would be answered. Compared against the live answer to
  // tell an untouched suggestion from one the submitter has since corrected.
  suggested: string[];
  confidence: number;
  reasoning?: string;
  passages: ProvenancePassage[];
  // Set once the submitter has settled this answer, whether by accepting it or by changing it.
  reviewed: boolean;
  // Set when the submitter said the passage does not support the answer. Recorded separately from
  // `reviewed` on purpose: flagging bad evidence is telemetry, not a step in their job, so it
  // neither settles the answer nor blocks anything.
  evidenceRejected: boolean;
}

// Suggested, accepted (settled and still matching) or changed (settled and differing). There is no
// "rejected" here: rejecting the evidence is a separate verdict from correcting the answer.
export type ProvenanceStatus = "suggested" | "accepted" | "changed";

// Below this a suggestion is called out as one the submitter should look at. It is the same floor
// the extraction uses to decide a field needs a second look, so the form and the pipeline agree on
// what "not sure" means.
export const LOW_CONFIDENCE_FLOOR = 0.75;

// How many words stand in for a passage when the extraction named no span.
const TRIGGER_WORDS = 6;

const BADGES: Record<ProvenanceStatus, string> = {
  suggested: "AI found:",
  // No glyphs. A tick reads as "approved", which is wrong for a correction, and the alternatives
  // all read as an edit affordance or need a legend. Settled versus pending is already carried by
  // the colour and by the accept button being gone, so the words can stand alone.
  accepted: "Matches your protocol",
  changed: "You corrected this",
};

export function isLowConfidence(provenance: QuestionProvenance): boolean {
  return provenance.confidence < LOW_CONFIDENCE_FLOOR;
}

// Whether the answer still says what the extraction proposed. Order matters, because a multi-value
// answer the submitter reordered is a change they made deliberately.
export function matchesSuggestion(provenance: QuestionProvenance, value: string[]): boolean {
  return provenance.suggested.length === value.length
    && provenance.suggested.every((suggested, index) => suggested === value[index]);
}

export function statusOf(provenance: QuestionProvenance, value: string[]): ProvenanceStatus {
  if (!provenance.reviewed) {
    return "suggested";
  }
  return matchesSuggestion(provenance, value) ? "accepted" : "changed";
}

export function badgeFor(status: ProvenanceStatus): string {
  return BADGES[status];
}

// The confidence chip is worth showing only while the answer is unsettled, and only when it is low:
// an amber chip on everything teaches a reader to ignore amber. "High confidence" on a settled
// answer tells nobody anything they can act on.
export function showsConfidence(provenance: QuestionProvenance, value: string[]): boolean {
  return statusOf(provenance, value) === "suggested" && isLowConfidence(provenance);
}

// The passage the trigger line names: the first one offered, which is the one the extraction led
// with.
export function leadPassage(provenance: QuestionProvenance): ProvenancePassage | undefined {
  return provenance.passages[0];
}

// What the collapsed line quotes: the matched span where there is one, else the opening of the
// passage. Either way it is elided, so it reads as a fragment rather than as the whole passage.
export function triggerLabel(passage: ProvenancePassage): string {
  const words = passage.quote.split(/\s+/).filter(word => word.length > 0);
  const lead = passage.span ?? words.slice(0, TRIGGER_WORDS).join(" ");
  return `“${lead}…”`;
}

// A passage split around the words the extraction keyed on, so the trigger and the expanded quote
// visibly refer to the same words. A span the quote does not contain marks nothing rather than
// guessing at a position.
export interface SplitQuote {
  before: string;
  span: string;
  after: string;
}

export function splitQuote(passage: ProvenancePassage): SplitQuote {
  const at = passage.span === undefined ? -1 : passage.quote.indexOf(passage.span);
  if (passage.span === undefined || at < 0) {
    return { before: passage.quote, span: "", after: "" };
  }
  return {
    before: passage.quote.slice(0, at),
    span: passage.span,
    after: passage.quote.slice(at + passage.span.length),
  };
}

// A passage with a location can be opened at that spot; one without can only be searched for. The
// label says which, so the link never promises more than it does.
export function openLabel(passage: ProvenancePassage): string {
  return passage.cite === undefined ? "Find in protocol" : "Open in protocol";
}
