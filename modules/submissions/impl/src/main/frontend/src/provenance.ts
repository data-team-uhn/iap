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
  // Where the passage lives, already assembled by the server: it owns how a location reads.
  cite?: string;
  // The document to open to see the passage in place, at its page where the parse found one. Absent
  // when the source has no PDF, which is when there is nothing to open.
  source?: string;
}

export interface QuestionProvenance {
  // What the extraction proposed, as it would be answered. Compared against the live answer to
  // tell an untouched suggestion from one the submitter has since corrected.
  suggested: string[];
  confidence: number;
  reasoning?: string;
  passages: ProvenancePassage[];
  // Set once the submitter has settled this answer: by accepting it, or by saving over it, which is a
  // verdict on the suggestion just as much as accepting it is.
  reviewed: boolean;
  // Set when the submitter said the passage does not support the answer. Recorded separately from
  // `reviewed` on purpose: flagging bad evidence is telemetry, not a step in their job, so it
  // neither settles the answer nor blocks anything.
  evidenceRejected: boolean;
}

// Suggested, accepted (settled and still matching) or changed (settled and differing). There is no
// "rejected" here: rejecting the evidence is a separate verdict from correcting the answer.
export type ProvenanceStatus = "suggested" | "accepted" | "changed";

// Below this a suggestion is called out as one the submitter should look at.
export const LOW_CONFIDENCE_FLOOR = 0.75;

// How many words of a passage stand in for it on the collapsed line.
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
// with. The rest are shown once the lane is opened.
export function leadPassage(provenance: QuestionProvenance): ProvenancePassage | undefined {
  return provenance.passages[0];
}

// What the trigger line says about the passages it does not quote, or undefined when it quotes the
// only one there is. An answer often rests on several, and showing the first as though it were the
// whole of the evidence understates what the extraction actually found.
export function furtherPassagesLabel(provenance: QuestionProvenance): string | undefined {
  const further = provenance.passages.length - 1;
  if (further < 1) {
    return undefined;
  }
  return further === 1 ? "+1 more passage" : `+${further} more passages`;
}

// What the collapsed line quotes: the opening of the passage, elided, so it reads as a fragment
// rather than as the whole passage.
export function triggerLabel(passage: ProvenancePassage): string {
  const words = passage.quote.split(/\s+/).filter(word => word.length > 0);
  return `“${words.slice(0, TRIGGER_WORDS).join(" ")}…”`;
}

// A passage with a location can be opened at that spot; one without can only be searched for. The
// label says which, so the link never promises more than it does.
export function openLabel(passage: ProvenancePassage): string {
  return passage.cite === undefined ? "Find in protocol" : "Open in protocol";
}

// Whether there is a document to open at all. A parse that produced no PDF leaves the passage with
// its quote and nothing to show it in, and offering a link then would lead nowhere.
export function canOpen(passage: ProvenancePassage): boolean {
  return passage.source !== undefined;
}
