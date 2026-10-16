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

import { getPassageText, type MarkSpan, type PageText } from "./marksModel";

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

// How a comparison went: the changes found, with how many more the default comparison leaves out, as
// changes of case or punctuation alone; or why there are none to show.
export type Comparison =
  | { status: "compared"; changes: RevisionChange[]; hidden: number }
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
const NUMBER_START = /^[^\p{L}\p{N}+\-±$€£¥]+/u;
const NUMBER_END = /[^\p{L}\p{N}%‰]+$/u;

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
    ? plain.replace(NUMBER_START, "").replace(NUMBER_END, "")
    : plain.replace(/[^\p{L}\p{M}\p{N}]/gu, "");
  return key === "" ? undefined : key.toLowerCase();
}

export function getChangeType({ removed, added }: RevisionChange): ChangeType {
  if (removed === "") {
    return "added";
  }
  return added === "" ? "removed" : "replaced";
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
      }
      at = { older: at.older + count, newer: at.newer + count };
    }
  }
  const endsWithChange = open !== undefined;
  close();
  const hidden = strict ? 0 : countHidden(before.all, after.all, pairs, endsWithChange);
  return Promise.resolve({ status: "compared", changes, hidden });
}
