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
import { diffArrays } from "diff";

import { getPassageText, type MarkSpan, type PageText, type PlacedMark, type TextPoint } from "./marksModel";

// What changed between two versions of a document, word by word, as places in each version's text as PDF.js
// reads it for its text layer, so the viewer can mark them. No React.

// A word of a version: where it is, as offsets into its page's text, and what it says, without the hyphen
// of a word broken across two lines.
export interface Word {
  page: number;
  start: number;
  end: number;
  text: string;
}

// A change from the older version to the newer one: the text it removed and the text it added, either
// empty, as they read, and where it is in each version. That is the spans of the text it removed or added
// there, or, where it has none, the point the text went from or would go to.
export interface RevisionChange {
  id: string;
  removed: string;
  added: string;
  older: MarkSpan[];
  newer: MarkSpan[];
}

export type ChangeType = "added" | "removed" | "replaced";

// The words of each version the comparison looked at, and, by their indexes, the pairs of them it took as
// the same, in order. They match a place in one version to the same place in the other.
export interface Alignment {
  older: Word[];
  newer: Word[];
  pairs: [ number, number ][];
}

// How a comparison went: the changes found, with how many more the default comparison leaves out, as
// changes of case or punctuation alone, and how the versions line up; or why there are none to show.
export type Comparison =
  | { status: "compared"; changes: RevisionChange[]; hidden: number; alignment: Alignment }
  | { status: "noText"; without: "older" | "newer" | "both" }
  | { status: "tooDifferent" };

// How much work the diff may do, in time and in words added and removed, before it gives up rather than
// keep the page busy.
export interface DiffLimits {
  timeout: number;
  maxEditLength: number;
}

export const DIFF_LIMITS: DiffLimits = { timeout: 1000, maxEditLength: 20000 };

const HYPHENS = new Set([ "-", "\u00ad", "\u2010" ]);

const isLetter = (ch: string | undefined): boolean => ch !== undefined && /\p{L}/u.test(ch);

// The words of each page in turn. A word ends at a space or at the end of a line, unless the line ends
// with a hyphen inside the word, which then runs on to the next line.
export function getWords(pages: PageText[]): Word[] {
  const words: Word[] = [];
  pages.forEach(({ text, lineEnds }, index) => {
    const page = index + 1;
    const breaks = [ ...lineEnds.filter(at => at > 0 && at < text.length), text.length ];
    const pattern = /\S+/gu;
    let from = 0;
    for (const to of breaks) {
      const line = text.slice(from, to);
      for (let match = pattern.exec(line); match !== null; match = pattern.exec(line)) {
        const start = from + match.index;
        const last = words.at(-1);
        // A word broken by a hyphen at the end of the line before
        if (match.index === 0 && last?.page === page && last.end === from && isBrokenWord(last.text, match[0])) {
          last.text = last.text.slice(0, -1) + match[0];
          last.end = start + match[0].length;
        } else {
          words.push({ page, start, end: start + match[0].length, text: match[0] });
        }
      }
      from = to;
    }
  });
  return words;
}

// Whether a line ending with a word and the next line starting with another break one word in two, as a
// hyphen between two letters does.
function isBrokenWord(end: string, start: string): boolean {
  return HYPHENS.has(end.at(-1) ?? "") && isLetter(end.at(-2)) && isLetter(start.charAt(0));
}

// What a number may carry at its start and its end that changes what it says.
const NUMBER_START = /^[\p{L}\p{N}+\-±$€£¥]$/u;
const NUMBER_END = /^[\p{L}\p{N}%‰]$/u;

// A number without the punctuation around it. Trimmed character by character, as a pattern trimming the end
// takes time growing with the square of a long run of punctuation, which a document can hold.
function trimNumber(text: string): string {
  const chars = Array.from(text);
  let start = 0;
  while (start < chars.length && !NUMBER_START.test(chars[start])) {
    start++;
  }
  let end = chars.length;
  while (end > start && !NUMBER_END.test(chars[end - 1])) {
    end--;
  }
  return chars.slice(start, end).join("");
}

// When two words are the same: when this gives the same for both. Compared strictly, a word is as it
// reads. Otherwise case and punctuation are left out, except in a number, which keeps its separators and
// its sign: "1,000" and "1.000" differ. Nothing for a word the comparison leaves out, as punctuation alone
// is unless strict. Either way, a ligature is the letters it joins.
export function getWordKey(text: string, strict: boolean): string | undefined {
  const plain = text.normalize("NFKC").replaceAll("\u00ad", "").replaceAll("−", "-");
  if (strict) {
    return plain === "" ? undefined : plain;
  }
  const key = /\p{N}/u.test(plain)
    ? trimNumber(plain)
    : plain.replace(/[^\p{L}\p{M}\p{N}]/gu, "");
  return key === "" ? undefined : key.toLowerCase();
}

export function getChangeType({ removed, added }: RevisionChange): ChangeType {
  if (removed === "") {
    return "added";
  }
  return added === "" ? "removed" : "replaced";
}

// The marks a version shows the changes with: removed text in the older, added text in the newer, and in
// each, the point where text only the other has went or would go. Shown alone, the newer version also
// marks the point removed text went from beside the text added in its place.
export function getChangeMarks(changes: RevisionChange[], version: "older" | "newer", alone: boolean): PlacedMark[] {
  return changes.map(change => {
    const { id, removed, added } = change;
    if (version === "older") {
      return { id, kind: removed === "" ? "added" : "removed", spans: change.older };
    }
    if (added === "") {
      return { id, kind: "removed", spans: change.newer };
    }
    const { page, start } = change.newer[0];
    const point = { page, start, end: start, kind: "removed" as const };
    return { id, kind: "added", spans: alone && removed !== "" ? [ point, ...change.newer ] : change.newer };
  });
}

// Past this, the text a change removed or added is cut short where a screen reader says it.
const SAID_LENGTH = 120;

function quote(text: string): string {
  return `“${text.length > SAID_LENGTH ? `${text.slice(0, SAID_LENGTH).trimEnd()}…` : text}”`;
}

// A change as a sentence of its own for a screen reader: which it is of how many, what it did on which
// page of the newer version, and the text it removed or added.
export function describeChange(change: RevisionChange, { index, count }: { index: number; count: number }): string {
  const { page } = change.newer[0];
  const which = `Change ${index} of ${count}.`;
  switch (getChangeType(change)) {
    case "added":
      return `${which} Added on page ${page}: ${quote(change.added)}.`;
    case "removed":
      return `${which} Removed on page ${page}: ${quote(change.removed)}.`;
    case "replaced":
      return `${which} Replaced on page ${page}: ${quote(change.removed)} with ${quote(change.added)}.`;
  }
}

// How many changes the default comparison leaves out, as a line under the changes found.
export function describeHidden(hidden: number): string {
  return `${hidden} ${hidden === 1 ? "change" : "changes"} to punctuation or case hidden`;
}

interface KeyedWord extends Word {
  // Where it is among all the words of its version.
  index: number;
  key: string;
  strictKey: string | undefined;
}

// A document uses few words many times, so each is keyed once.
function getKeyedWords(pages: PageText[], strict: boolean): { all: KeyedWord[]; compared: KeyedWord[] } {
  const keys = new Map<string, { key: string; strictKey: string | undefined }>();
  const all = getWords(pages).map((word, index) => {
    let keyed = keys.get(word.text);
    if (keyed === undefined) {
      keyed = { key: getWordKey(word.text, strict) ?? "", strictKey: getWordKey(word.text, true) };
      keys.set(word.text, keyed);
    }
    return Object.assign(word, { index }, keyed);
  });
  return { all, compared: all.filter(word => word.key !== "") };
}

// The spans words cover, one per page they are on.
function getSpans(words: Word[]): MarkSpan[] {
  const spans: MarkSpan[] = [];
  for (const { page, start, end } of words) {
    const last = spans.at(-1);
    if (last?.page === page) {
      last.end = end;
    } else {
      spans.push({ page, start, end });
    }
  }
  return spans;
}

// The point before the word at an index: after the word before it, or else before it, at the start.
function getPointBefore(words: Word[], index: number): MarkSpan {
  const before = words.at(index - 1);
  if (index > 0 && before !== undefined) {
    return { page: before.page, start: before.end, end: before.end };
  }
  const after = words.at(index);
  if (after === undefined) {
    return { page: 1, start: 0, end: 0 };
  }
  return { page: after.page, start: after.start, end: after.start };
}

function getSpansText(spans: MarkSpan[], pages: PageText[]): string {
  return spans
    .map(({ page, start, end }) => getPassageText(pages[page - 1], start, end))
    .filter(text => text !== "")
    .join(" ");
}

// How many changes the default comparison leaves out. Two words it pairs as the same may differ strictly,
// and so may the punctuation between two pairs with no change between them. Each run of such differences is
// one change.
function countHidden(older: KeyedWord[], newer: KeyedWord[], pairs: WordPair[], endsWithChange: boolean): number {
  const getStrictText = (words: KeyedWord[], from: number, to: number) => (from >= to
    ? ""
    : words.slice(from, to).map(word => word.strictKey ?? "").join(" "));
  const differsBetween = (from: WordPair | undefined, to: { older: number; newer: number }) => (
    getStrictText(older, (from?.older.index ?? -1) + 1, to.older)
      !== getStrictText(newer, (from?.newer.index ?? -1) + 1, to.newer)
  );
  let hidden = 0;
  let inRun = false;
  let last: WordPair | undefined;
  for (const pair of pairs) {
    const to = { older: pair.older.index, newer: pair.newer.index };
    inRun &&= !pair.afterChange;
    const differs = (!pair.afterChange && differsBetween(last, to)) || pair.older.strictKey !== pair.newer.strictKey;
    hidden += differs && !inRun ? 1 : 0;
    inRun = differs;
    last = pair;
  }
  const trailing = !endsWithChange && differsBetween(last, { older: older.length, newer: newer.length });
  return hidden + (trailing && !inRun ? 1 : 0);
}

// Two words the comparison takes as the same, and whether a change comes just before them.
interface WordPair {
  older: KeyedWord;
  newer: KeyedWord;
  afterChange: boolean;
}

// The words a change removed and added, from where it starts in each version's compared words.
interface OpenChange {
  from: { older: number; newer: number };
  removed: KeyedWord[];
  added: KeyedWord[];
}

// Compares two versions of a document, given each page's text, word by word: strictly, or leaving case and
// punctuation out as getWordKey says. A version without text cannot be compared, and two that differ past
// the limits are not.
export function compareRevisions(
  older: PageText[],
  newer: PageText[],
  { strict, limits = DIFF_LIMITS }: { strict: boolean; limits?: DiffLimits },
): Promise<Comparison> {
  const hasText = (pages: PageText[]) => pages.some(page => page.text.trim() !== "");
  if (!hasText(older) || !hasText(newer)) {
    const without = hasText(older) ? "newer" : hasText(newer) ? "older" : "both";
    return Promise.resolve({ status: "noText", without });
  }
  const before = getKeyedWords(older, strict);
  const after = getKeyedWords(newer, strict);
  const parts = diffArrays(before.compared.map(word => word.key), after.compared.map(word => word.key), limits);
  if (parts === undefined) {
    return Promise.resolve({ status: "tooDifferent" });
  }
  const changes: RevisionChange[] = [];
  const pairs: WordPair[] = [];
  const aligned: [ number, number ][] = [];
  let at = { older: 0, newer: 0 };
  let open: OpenChange | undefined;
  const close = () => {
    if (open === undefined) {
      return;
    }
    const { from, removed, added } = open;
    const olderSpans = removed.length > 0 ? getSpans(removed) : [ getPointBefore(before.compared, from.older) ];
    const newerSpans = added.length > 0 ? getSpans(added) : [ getPointBefore(after.compared, from.newer) ];
    changes.push({
      id: `change-${changes.length + 1}`,
      removed: removed.length > 0 ? getSpansText(olderSpans, older) : "",
      added: added.length > 0 ? getSpansText(newerSpans, newer) : "",
      older: olderSpans,
      newer: newerSpans,
    });
    open = undefined;
  };
  for (const { added, removed, count } of parts) {
    if (removed) {
      open ??= { from: at, removed: [], added: [] };
      open.removed.push(...before.compared.slice(at.older, at.older + count));
      at = { ...at, older: at.older + count };
    } else if (added) {
      open ??= { from: at, removed: [], added: [] };
      open.added.push(...after.compared.slice(at.newer, at.newer + count));
      at = { ...at, newer: at.newer + count };
    } else {
      const afterChange = open !== undefined;
      close();
      for (let index = 0; index < count; index++) {
        const pair = { older: before.compared[at.older + index], newer: after.compared[at.newer + index] };
        pairs.push({ ...pair, afterChange: afterChange && index === 0 });
        aligned.push([ at.older + index, at.newer + index ]);
      }
      at = { older: at.older + count, newer: at.newer + count };
    }
  }
  const endsWithChange = open !== undefined;
  close();
  const hidden = strict ? 0 : countHidden(before.all, after.all, pairs, endsWithChange);
  const alignment = { older: before.compared, newer: after.compared, pairs: aligned };
  return Promise.resolve({ status: "compared", changes, hidden, alignment });
}

// How many of the items, in order, come before a place, given which do: all of those that do come first.
function countBefore<T>(items: T[], isBefore: (item: T) => boolean): number {
  let low = 0;
  let high = items.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (isBefore(items[middle])) {
      low = middle + 1;
    } else {
      high = middle;
    }
  }
  return low;
}

// The place in the other version matching a place in one: the start of the same word, where the comparison
// took the word there as the same in both, or else where the change holding it sits in the other version.
export function mapPoint(alignment: Alignment, from: "older" | "newer", { page, char }: TextPoint): TextPoint {
  const [ side, otherSide ] = from === "older" ? [ 0, 1 ] as const : [ 1, 0 ] as const;
  const others = alignment[from === "older" ? "newer" : "older"];
  const isAtOrBefore = (word: Word) => word.page < page || (word.page === page && word.start <= char);
  const index = countBefore(alignment[from], isAtOrBefore) - 1;
  const pairs = countBefore(alignment.pairs, each => each[side] <= index);
  const pair = pairs > 0 ? alignment.pairs[pairs - 1] : undefined;
  const matching = pair === undefined ? 0 : pair[otherSide] + (pair[side] === index ? 0 : 1);
  const word = others.at(Math.min(matching, others.length - 1));
  return word === undefined ? { page: 1, char: 0 } : { page: word.page, char: word.start };
}
