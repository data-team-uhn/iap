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

// Where a suggested answer came from, and where the submitter stands with it.
//
// A reading never writes an answer: it writes a run beside it. The server sends the newest run's
// values (`suggested`) and what to offer beside the submitter's own answer (`offered`, worked out by
// `Answer.getOfferedSuggestion`). Everything here only reads those against what is on screen.

// One quote backing a suggestion. Every quote sent was found in the document.
export interface ProvenancePassage {
  quote: string;
  // Where the quote is, such as "p. 3 · Methods"
  cite?: string;
  // The PDF to open at the quote, with its page as a fragment. Absent when there is no PDF.
  source?: string;
}

export interface QuestionProvenance {
  // The newest run's values
  suggested: string[];
  // What to offer beside the answer: the suggestion while nothing is answered, a newer reading that
  // differs from the answer, or nothing
  offered: string[];
  confidence: number;
  reasoning?: string;
  passages: ProvenancePassage[];
  // Whether the submitter said the quotes do not support the suggestion. A note about the evidence only.
  evidenceRejected: boolean;
  // Whether the submitter has saved an answer since this run, so it was measured against it
  actedOn: boolean;
}

// Waiting to be taken, taken as it was, or answered differently
export type ProvenanceStatus = "suggested" | "accepted" | "changed";

// Below this a suggestion is called out as one to look at
export const LOW_CONFIDENCE_FLOOR = 0.75;

// How many words of a quote stand in for it on the collapsed line
const TRIGGER_WORDS = 6;

const BADGES: Record<ProvenanceStatus, string> = {
  suggested: "AI found:",
  accepted: "Matches your document",
  changed: "You answered differently",
};

export function isLowConfidence(provenance: QuestionProvenance): boolean {
  return provenance.confidence < LOW_CONFIDENCE_FLOOR;
}

// Whether two answers hold the same values in the same order
export function isSameValues(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((each, index) => each === right[index]);
}

// Whether the field starts out showing the suggestion: nothing is answered and something is offered
export function isPrefilled(provenance: QuestionProvenance | undefined, saved: string[]): boolean {
  return provenance !== undefined && saved.length === 0 && provenance.offered.length > 0;
}

// Where the answer on screen stands against the suggestion. `saved` is the stored answer: until one
// is stored, a field showing the suggestion is still a suggestion.
export function getStatus(provenance: QuestionProvenance, shown: string[], saved: string[]): ProvenanceStatus {
  if (saved.length === 0) {
    return isSameValues(provenance.suggested, shown) ? "suggested" : "changed";
  }
  return isSameValues(provenance.suggested, saved) ? "accepted" : "changed";
}

// A newer reading to offer beside an answer the submitter already gave, or undefined
export function getNewerOffer(provenance: QuestionProvenance | undefined, saved: string[]): string[] | undefined {
  if (provenance === undefined || saved.length === 0 || provenance.offered.length === 0) {
    return undefined;
  }
  return isSameValues(provenance.offered, saved) ? undefined : provenance.offered;
}

export function getBadge(status: ProvenanceStatus): string {
  return BADGES[status];
}

// Low confidence is worth a chip only while the suggestion is still to be taken
export function showsConfidence(provenance: QuestionProvenance, status: ProvenanceStatus): boolean {
  return status === "suggested" && isLowConfidence(provenance);
}

// The quote the collapsed line names: the first, which the model led with
export function getLeadPassage(provenance: QuestionProvenance): ProvenancePassage | undefined {
  return provenance.passages[0];
}

// What the collapsed line says about the quotes it does not show, or undefined when there is one
export function getFurtherPassagesLabel(provenance: QuestionProvenance): string | undefined {
  const further = provenance.passages.length - 1;
  if (further < 1) {
    return undefined;
  }
  return further === 1 ? "+1 more passage" : `+${further} more passages`;
}

// The opening words of a quote, elided
export function getTriggerLabel(passage: ProvenancePassage): string {
  const words = passage.quote.split(/\s+/).filter(word => word.length > 0);
  return `“${words.slice(0, TRIGGER_WORDS).join(" ")}…”`;
}

// A quote with a place opens there; one without can only be searched for
export function getOpenLabel(passage: ProvenancePassage): string {
  return passage.cite === undefined ? "Find in document" : "Open in document";
}

export function canOpen(passage: ProvenancePassage): boolean {
  return passage.source !== undefined;
}

// The PDF's path without the fragment, and the page the fragment names
export function parsePdfSource(source: string): { url: string; page?: number } {
  const hash = source.indexOf("#");
  const url = hash < 0 ? source : source.slice(0, hash);
  const fragment = hash < 0 ? "" : source.slice(hash + 1);
  const match = /(?:^|&)page=(\d+)/.exec(fragment);
  const page = match === null ? undefined : Number(match[1]);
  return page !== undefined && page > 0 ? { url, page } : { url };
}
