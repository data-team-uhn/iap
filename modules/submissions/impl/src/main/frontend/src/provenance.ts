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
  // Set once the submitter has confirmed this answer. Saving over it does not set it: an edit is told
  // apart by comparing the live answer with `suggested`.
  reviewed: boolean;
  // Set when the submitter said the passage does not support the answer. Recorded separately from
  // `reviewed` on purpose: flagging bad evidence is telemetry, not a step in their job, so it
  // neither settles the answer nor blocks anything.
  evidenceRejected: boolean;
  // What the answer held before a reading of a newer version of its document replaced it. Absent
  // when the reading replaced nothing.
  replaced?: string[];
}

// What the switch between a replaced answer and the newer reading offers: "undo" offers the old
// answer back, "redo" offers the newer reading again.
export interface ReplacementOffer {
  direction: "undo" | "redo";
  values: string[];
}

// Suggested, accepted (confirmed and still matching) or changed (differing). There is no
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
  return isSameValues(provenance.suggested, value);
}

function isSameValues(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((each, index) => each === right[index]);
}

// What the switch offers for the answer as it stands, or undefined when a newer version replaced
// nothing or the submitter has since given an answer that is neither.
export function replacementOffer(provenance: QuestionProvenance, value: string[]): ReplacementOffer | undefined {
  const replaced = provenance.replaced;
  if (replaced === undefined) {
    return undefined;
  }
  if (matchesSuggestion(provenance, value)) {
    return { direction: "undo", values: replaced };
  }
  return isSameValues(replaced, value) ? { direction: "redo", values: provenance.suggested } : undefined;
}

export function statusOf(provenance: QuestionProvenance, value: string[]): ProvenanceStatus {
  // Saving over a suggestion is a verdict on it, whether or not it was confirmed first
  if (!matchesSuggestion(provenance, value)) {
    return "changed";
  }
  return provenance.reviewed ? "accepted" : "suggested";
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

// The page a passage link names, and the path with the fragment taken off so PDF.js fetches the file.
export function parsePdfSource(source: string): { url: string; page?: number } {
  const hash = source.indexOf("#");
  const url = hash < 0 ? source : source.slice(0, hash);
  const fragment = hash < 0 ? "" : source.slice(hash + 1);
  const match = /(?:^|&)page=(\d+)/.exec(fragment);
  const page = match === null ? undefined : Number(match[1]);
  return page !== undefined && page > 0 ? { url, page } : { url };
}
