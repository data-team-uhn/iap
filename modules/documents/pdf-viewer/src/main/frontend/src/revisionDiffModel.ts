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
// Compares two versions of a document word by word, and says where each change is in each version's text so
// the viewer can mark it. It also finds the place in one version that matches a place in the other. No React.
import { diffArrays } from "diff";

import { getPassageText, type MarkSpan, type PageText, type PlacedMark, type TextPoint } from "./marksModel";

// A word, and where it is in its page's text. A word hyphenated across two lines is one word, without the
// hyphen. One hyphenated across a page break ends on the next page, at `next`.
export interface Word {
  page: number;
  start: number;
  end: number;
  text: string;
  next?: { page: number; end: number };
}

// A few words before and after a change.
interface Context {
  before: string;
  after: string;
}

// One change between the versions: the text removed and the text added, either of which may be empty, and
// where it is in each version. A version with none of the change's text has a point where that text was or
// would go.
export interface RevisionChange {
  id: string;
  // Whether only punctuation or letter case changed.
  minor: boolean;
  removed: string;
  added: string;
  older: MarkSpan[];
  newer: MarkSpan[];
  // A few words either side of it in each version, with "…" where the sentence goes on.
  context: Record<"older" | "newer", Context>;
}

export type ChangeType = "added" | "removed" | "replaced";

// Each version's words, and the words the comparison matched, as pairs of indexes with the older version's
// first. The pairs lead from a place in one version to the same place in the other.
export interface Alignment {
  older: Word[];
  newer: Word[];
  pairs: [ number, number ][];
}

// What a comparison found: every change, minor ones included, and how the versions line up. Otherwise, why
// there is nothing to show: a version has no text, or the versions differ past the limits.
export type Comparison =
  | { status: "compared"; changes: RevisionChange[]; alignment: Alignment }
  | { status: "noText"; without: "older" | "newer" | "both" }
  | { status: "tooDifferent" };

// Where a comparison is up to: reading pages (counted on the longer version), comparing, done, or failed.
export type ComparisonState =
  | { status: "reading"; read: number; total: number }
  | { status: "comparing" }
  | { status: "failed" }
  | Comparison;

// How long a comparison may take, in milliseconds, and how many words it may find added or removed. Past
// either, it gives up rather than freeze the page.
export interface DiffLimits {
  timeout: number;
  maxEditLength: number;
}

// The limits a comparison keeps to unless given others.
export const DIFF_LIMITS: DiffLimits = { timeout: 1000, maxEditLength: 20000 };

// The hyphens a word broken across two lines can end with: a hyphen-minus, a soft hyphen and a hyphen.
const HYPHENS = new Set([ "-", "\u00ad", "\u2010" ]);

const isLetter = (ch: string | undefined): boolean => ch !== undefined && /\p{L}/u.test(ch);

// Every word of a version, page by page. A word ends at a space or at the end of a line. A line ending in a
// hyphen between two letters carries its word on into the next line, even on the next page, without the
// hyphen.
export function getWords(pages: PageText[]): Word[] {
  const words: Word[] = [];
  let lastPageEnd = 0;
  pages.forEach(({ text, lineEnds }, index) => {
    const page = index + 1;
    const breaks = [ ...lineEnds.filter(at => at > 0 && at < text.length), text.length ];
    const pattern = /\S+/gu;
    let from = 0;
    for (const to of breaks) {
      const line = text.slice(from, to);
      for (let match = pattern.exec(line); match !== null; match = pattern.exec(line)) {
        const start = from + match.index;
        const end = start + match[0].length;
        const last = words.at(-1);
        const lineStart = match.index === 0 && last !== undefined && last.next === undefined;
        if (lineStart && last.page === page && last.end === from && isBrokenWord(last.text, match[0])) {
          last.text = last.text.slice(0, -1) + match[0];
          last.end = end;
        } else if (lineStart && from === 0 && last.page === page - 1 && last.end === lastPageEnd
          && isBrokenWord(last.text, match[0])) {
          last.text = last.text.slice(0, -1) + match[0];
          last.next = { page, end };
        } else {
          words.push({ page, start, end, text: match[0] });
        }
      }
      from = to;
    }
    lastPageEnd = text.length;
  });
  return words;
}

// Whether the last word of a line and the first of the next are one word, split by a hyphen.
function isBrokenWord(end: string, start: string): boolean {
  return HYPHENS.has(end.at(-1) ?? "") && isLetter(end.at(-2)) && isLetter(start.charAt(0));
}

// What a number starts and ends with once the punctuation around it is trimmed, such as a sign or a percent.
const NUMBER_START = /^[\p{L}\p{N}+\-±$€£¥]$/u;
const NUMBER_END = /^[\p{L}\p{N}%‰]$/u;

// The same for any other word.
const WORD_EDGE = /^[\p{L}\p{M}\p{N}]$/u;

// Trims the characters at a word's start that `start` does not match, and those at its end that `end` does
// not match. It goes character by character: a regular expression trimming the end takes quadratic time on a
// long run of punctuation.
function trimEnds(text: string, start: RegExp, end: RegExp): string {
  const chars = Array.from(text);
  let from = 0;
  while (from < chars.length && !start.test(chars[from])) {
    from++;
  }
  let to = chars.length;
  while (to > from && !end.test(chars[to - 1])) {
    to--;
  }
  return chars.slice(from, to).join("");
}

// A word with a ligature read as the letters it joins, without soft hyphens, and with a minus as a hyphen.
// Both keys start from it.
function getPlainWord(text: string): string {
  return text.normalize("NFKC").replaceAll("\u00ad", "").replaceAll("−", "-");
}

// The key two words are compared by strictly: the word as written, or undefined for no word.
export function getStrictKey(text: string): string | undefined {
  const plain = getPlainWord(text);
  return plain === "" ? undefined : plain;
}

// The key two words are compared by by default, or undefined if the word has none. It ignores case, the
// punctuation around the word, and the hyphens inside it, which a line break may add or drop. It keeps
// apostrophes, so "patient's" and "patients" differ. A word with a digit keeps its separators, hyphens and
// sign: "1,000" and "1.000" differ, as do "IL-6" and "IL6". Punctuation on its own has no key.
export function getLenientKey(text: string): string | undefined {
  const plain = getPlainWord(text);
  const key = /\p{N}/u.test(plain)
    ? trimEnds(plain, NUMBER_START, NUMBER_END).replace(/\p{Pd}/gu, "-")
    : trimEnds(plain, WORD_EDGE, WORD_EDGE).replace(/\p{Pd}/gu, "");
  return key === "" ? undefined : key.replace(/[‘’ʼ′`]/gu, "'").toLowerCase();
}

// Whether a change added text, removed it, or replaced it with other text.
export function getChangeType({ removed, added }: RevisionChange): ChangeType {
  if (removed === "") {
    return "added";
  }
  return added === "" ? "removed" : "replaced";
}

// How many words of context to show either side of a change.
const CONTEXT_WORDS = 3;

// The mark at the end of a word ending a sentence, with any closing quotes or brackets after it.
const SENTENCE_END = /[.!?…]["'”’)\]]*$/u;

// The words just before and after the change covering the words from `from` to `to`. Each side has up to
// CONTEXT_WORDS words, and stops at the end of a sentence or at the bounds of `within`, where the changes
// either side are. "…" shows where the sentence goes on.
function getContext(words: Word[], from: number, to: number, within: { start: number; end: number }): Context {
  const isSentenceEndAt = (index: number) => SENTENCE_END.test(words[index].text);
  let start = from;
  while (start > within.start && from - start < CONTEXT_WORDS && !isSentenceEndAt(start - 1)) {
    start--;
  }
  let end = to;
  if (to === from || !isSentenceEndAt(to - 1)) {
    while (end < within.end && end - to < CONTEXT_WORDS && (end === to || !isSentenceEndAt(end - 1))) {
      end++;
    }
  }
  const before = words.slice(start, from).map(word => word.text).join(" ");
  const after = words.slice(to, end).map(word => word.text).join(" ");
  return {
    before: before !== "" && start > 0 && !isSentenceEndAt(start - 1) ? `…${before}` : before,
    after: after !== "" && end < words.length && !isSentenceEndAt(end - 1) ? `${after}…` : after,
  };
}

// A change with the words around it, to read as one passage. A change that only removed text takes them from
// the older version, where that text was. Any other change takes them from the newer.
export function getChangeInContext({ removed, added, context }: RevisionChange): Context & {
  removed: string;
  added: string;
} {
  const { before, after } = added === "" ? context.older : context.newer;
  return { before, removed, added, after };
}

// The marks of the changes on one version. The older version marks removed text, and the newer marks added
// text. Each also marks a point where text only the other version has was or would go. Text that replaced
// other text also gets a point at its start, so it reads apart from text simply added.
export function getChangeMarks(changes: RevisionChange[], version: "older" | "newer"): PlacedMark[] {
  return changes.map(({ id, removed, added, older, newer }): PlacedMark => {
    if (version === "older") {
      return { id, kind: removed === "" ? "added" : "removed", spans: older };
    }
    if (removed === "" || added === "") {
      return { id, kind: added === "" ? "removed" : "added", spans: newer };
    }
    const { page, start } = newer[0];
    return { id, kind: "added", spans: [ { page, start, end: start, starts: true }, ...newer ] };
  });
}

// The changes to show: all of them when strict, otherwise only those that aren't minor.
export function getShownChanges(changes: RevisionChange[], strict: boolean): RevisionChange[] {
  return strict ? changes : changes.filter(change => !change.minor);
}

// The change to select in place of the change `id` when fewer changes are shown, such as once minor changes
// are left out. It is that change if it is still shown, or else the next one shown after it, or else the last
// one shown before it. Undefined if `id` is not among `changes`, or none is shown.
export function getNearestShown(
  changes: RevisionChange[],
  shown: RevisionChange[],
  id: string,
): RevisionChange | undefined {
  const at = changes.findIndex(change => change.id === id);
  if (at < 0) {
    return undefined;
  }
  const shownIds = new Set(shown.map(change => change.id));
  const isShown = (change: RevisionChange) => shownIds.has(change.id);
  return changes.slice(at).find(isShown) ?? changes.slice(0, at).findLast(isShown);
}

export function countMinorChanges(changes: RevisionChange[]): number {
  return changes.filter(change => change.minor).length;
}

interface KeyedWord extends Word {
  // The word's keys from getStrictKey and getLenientKey, and `key`, the one the diff compares: the lenient key, or the strict one
  // for punctuation on its own.
  lenientKey: string | undefined;
  key: string;
  strictKey: string;
}

// A version's words with their keys, worked out once per distinct word. A word with no key, such as a lone
// soft hyphen, is left out.
function getKeyedWords(pages: PageText[]): KeyedWord[] {
  const keys = new Map<string, Pick<KeyedWord, "lenientKey" | "key" | "strictKey"> | undefined>();
  return getWords(pages).flatMap(word => {
    if (!keys.has(word.text)) {
      const strictKey = getStrictKey(word.text);
      const lenientKey = getLenientKey(word.text);
      const keyed = strictKey === undefined ? undefined : { lenientKey, key: lenientKey ?? strictKey, strictKey };
      keys.set(word.text, keyed);
    }
    const keyed = keys.get(word.text);
    return keyed === undefined ? [] : [ Object.assign(word, keyed) ];
  });
}

// The spans a run of words covers, one per page.
function getSpans(words: Word[]): MarkSpan[] {
  const spans: MarkSpan[] = [];
  const add = (page: number, start: number, end: number) => {
    const last = spans.at(-1);
    if (last?.page === page) {
      last.end = end;
    } else {
      spans.push({ page, start, end });
    }
  };
  for (const { page, start, end, next } of words) {
    add(page, start, end);
    if (next !== undefined) {
      add(next.page, 0, next.end);
    }
  }
  return spans;
}

// The point just before the word at `index`, which may be one past the last word. A point that `starts` the
// text after it sits at the start of that word, if there is one. Any other point sits at the end of the word
// before, or at the start of the first word if there is none.
function getPointBefore(words: Word[], index: number, starts: boolean): MarkSpan {
  const before = words.at(index - 1);
  const after = words.at(index);
  if (starts && after !== undefined) {
    return { page: after.page, start: after.start, end: after.start, starts };
  }
  if (index > 0 && before !== undefined) {
    const { page, end } = before.next ?? before;
    return { page, start: end, end };
  }
  if (after === undefined) {
    return { page: 1, start: 0, end: 0 };
  }
  return { page: after.page, start: after.start, end: after.start };
}

// The text the spans cover, read across their pages. A word hyphenated across a page break is joined without
// a space, as across a line.
function getChangeText(spans: MarkSpan[], pages: PageText[]): string {
  return spans
    .map(({ page, start, end }) => getPassageText(pages[page - 1], start, end))
    .filter(text => text !== "")
    .reduce((text, next) => (text === "" ? next : `${text}${isBrokenWord(text, next) ? "" : " "}${next}`), "");
}

// A change while it is collected: where it starts in each version's words, and the words removed and added.
interface OpenChange {
  from: { older: number; newer: number };
  removed: KeyedWord[];
  added: KeyedWord[];
}

// Walks a diff's parts in order. It calls `onChange` with each change, and `onSame` with each run of shared
// words, giving where the run starts in each version and how many words it has.
function walkDiff(
  parts: DiffPart[],
  older: KeyedWord[],
  newer: KeyedWord[],
  { onChange, onSame }: {
    onChange: (change: OpenChange) => void;
    onSame?: (from: { older: number; newer: number }, count: number) => void;
  },
): void {
  let at = { older: 0, newer: 0 };
  let open: OpenChange | undefined;
  const close = () => {
    if (open !== undefined) {
      onChange(open);
      open = undefined;
    }
  };
  for (const { added, removed, count } of parts) {
    if (removed === true) {
      open ??= { from: at, removed: [], added: [] };
      open.removed = open.removed.concat(older.slice(at.older, at.older + count));
      at = { ...at, older: at.older + count };
    } else if (added === true) {
      open ??= { from: at, removed: [], added: [] };
      open.added = open.added.concat(newer.slice(at.newer, at.newer + count));
      at = { ...at, newer: at.newer + count };
    } else {
      close();
      onSame?.(at, count);
      at = { older: at.older + count, newer: at.newer + count };
    }
  }
  close();
}

// Whether a change only touches punctuation or letter case.
function isMinor({ removed, added }: OpenChange): boolean {
  const getLenientText = (words: KeyedWord[]) => words.flatMap(word => word.lenientKey ?? []).join(" ");
  return getLenientText(removed) === getLenientText(added);
}

// A part of a diff: words only the older version has, only the newer, or both.
interface DiffPart {
  added?: boolean;
  removed?: boolean;
  count: number;
}

// A run of shared words, or a change with how many words it removed and added.
type Segment = { same: true; count: number } | { same: false; removed: number; added: number };

// Merges two changes and the shared words between them into one change, when there are no more than half as
// many shared words as each change has. A change has as many words as the larger of its removed and added
// text. Without this, a rewritten passage splits into several changes at the common words it happens to
// keep, such as "the" or a recurring term.
export function joinChanges(parts: DiffPart[]): DiffPart[] {
  const segments: Segment[] = [];
  for (const { added, removed, count } of parts) {
    const last = segments.at(-1);
    if (added !== true && removed !== true) {
      segments.push({ same: true, count });
    } else if (last?.same === false) {
      last.removed += removed === true ? count : 0;
      last.added += added === true ? count : 0;
    } else {
      segments.push({ same: false, removed: removed === true ? count : 0, added: added === true ? count : 0 });
    }
  }
  const joined: Segment[] = [];
  for (const segment of segments) {
    let current = segment;
    while (!current.same && joined.length >= 2) {
      const [ before, shared ] = joined.slice(-2);
      const getSize = (change: Segment) => (change.same ? 0 : Math.max(change.removed, change.added));
      if (before.same || !shared.same || 2 * shared.count > getSize(before) || 2 * shared.count > getSize(current)) {
        break;
      }
      joined.splice(-2);
      current = {
        same: false,
        removed: before.removed + shared.count + current.removed,
        added: before.added + shared.count + current.added,
      };
    }
    joined.push(current);
  }
  return joined.flatMap((segment): DiffPart[] => (segment.same
    ? [ { count: segment.count } ]
    : [
      ...segment.removed > 0 ? [ { removed: true, count: segment.removed } ] : [],
      ...segment.added > 0 ? [ { added: true, count: segment.added } ] : [],
    ]));
}

// A version's sentences, as ranges of word indexes.
function getSentences(words: Word[]): { from: number; to: number }[] {
  const sentences: { from: number; to: number }[] = [];
  let from = 0;
  words.forEach((word, index) => {
    if (SENTENCE_END.test(word.text) || index === words.length - 1) {
      sentences.push({ from, to: index + 1 });
      from = index + 1;
    }
  });
  return sentences;
}

// Diffs the versions' words in two passes: whole sentences first, then the words inside the sentences that
// changed. A common word can then match only within the run of changed sentences it is in. Undefined if the
// passes together go over the limits.
function diffWords(older: KeyedWord[], newer: KeyedWord[], limits: DiffLimits): DiffPart[] | undefined {
  const deadline = performance.now() + limits.timeout;
  let edits = 0;
  // A diff within whatever is left of the limits.
  const diff = (before: string[], after: string[]) => {
    const timeout = deadline - performance.now();
    const maxEditLength = limits.maxEditLength - edits;
    return timeout <= 0 ? undefined : diffArrays(before, after, { timeout, maxEditLength });
  };
  const [ olderSentences, newerSentences ] = [ getSentences(older), getSentences(newer) ];
  // A sentence as the first pass compares it: its words' keys, joined.
  const sayOf = (words: KeyedWord[]) => ({ from, to }: { from: number; to: number }) => words
    .slice(from, to).map(word => word.key).join(" ");
  const sentenceParts = diff(olderSentences.map(sayOf(older)), newerSentences.map(sayOf(newer)));
  if (sentenceParts === undefined) {
    return undefined;
  }
  const parts: DiffPart[] = [];
  const at = { older: 0, newer: 0 };
  const changed = { older: 0, newer: 0 };
  // How many words `count` sentences from `start` hold.
  const countWords = (sentences: { from: number; to: number }[], start: number, count: number) => (count === 0
    ? 0
    : sentences[start + count - 1].to - sentences[start].from);
  // Diffs the words of the sentences changed since the last shared one, and adds the result to `parts`. False
  // once over the limits.
  const flush = (): boolean => {
    const removed = countWords(olderSentences, at.older - changed.older, changed.older);
    const added = countWords(newerSentences, at.newer - changed.newer, changed.newer);
    let within: DiffPart[] | undefined = [];
    if (removed > 0 && added > 0) {
      const olderFrom = olderSentences[at.older - changed.older].from;
      const newerFrom = newerSentences[at.newer - changed.newer].from;
      within = diff(
        older.slice(olderFrom, olderFrom + removed).map(word => word.key),
        newer.slice(newerFrom, newerFrom + added).map(word => word.key),
      );
    } else if (removed > 0) {
      within = [ { removed: true, count: removed } ];
    } else if (added > 0) {
      within = [ { added: true, count: added } ];
    }
    changed.older = 0;
    changed.newer = 0;
    if (within === undefined) {
      return false;
    }
    edits += within.reduce((sum, part) => sum + (part.added === true || part.removed === true ? part.count : 0), 0);
    parts.push(...joinChanges(within));
    return edits <= limits.maxEditLength;
  };
  for (const { added, removed, count } of sentenceParts) {
    if (removed) {
      at.older += count;
      changed.older += count;
    } else if (added) {
      at.newer += count;
      changed.newer += count;
    } else {
      if (!flush()) {
        return undefined;
      }
      parts.push({ count: countWords(olderSentences, at.older, count) });
      at.older += count;
      at.newer += count;
    }
  }
  return flush() ? parts : undefined;
}

// Takes out of a run of minor changes a word whose difference only moved into the change next to it. From
// "is due." to "is due and payable.", the full stop moved to the end of the added text. From "Fees apply." to
// "Late fees apply.", the capital moved to its start. Neither word was edited.
function leaveOutMoved(run: OpenChange, previous: OpenChange | undefined, next: OpenChange | undefined): OpenChange[] {
  let { from, removed, added } = run;
  const runEnd = { older: from.older + removed.length, newer: from.newer + added.length };
  const facesNext = next?.from.older === runEnd.older && next.from.newer === runEnd.newer;
  if (facesNext && isMovedEnd(removed[removed.length - 1], added[added.length - 1], next)) {
    [ removed, added ] = [ removed.slice(0, -1), added.slice(0, -1) ];
  }
  const facesPrevious = previous !== undefined && removed.length > 0
    && previous.from.older + previous.removed.length === from.older
    && previous.from.newer + previous.added.length === from.newer;
  if (facesPrevious && isMovedStart(removed[0], added[0], previous)) {
    [ removed, added ] = [ removed.slice(1), added.slice(1) ];
    from = { older: from.older + 1, newer: from.newer + 1 };
  }
  return removed.length === 0 ? [] : [ { from, removed, added } ];
}

const isSentenceEnd = (word: KeyedWord | undefined) => word !== undefined && SENTENCE_END.test(word.strictKey);
const isCapitalised = (word: KeyedWord | undefined) => word !== undefined && /^\p{Lu}/u.test(word.strictKey);

// Whether matched words differ only in a mark ending a sentence, and the change after them ends with that
// mark in the version whose word lacks it.
function isMovedEnd(older: KeyedWord, newer: KeyedWord, change: OpenChange): boolean {
  const [ one, other ] = [ older.strictKey, newer.strictKey ];
  if (one.replace(SENTENCE_END, "") !== other.replace(SENTENCE_END, "")) {
    return false;
  }
  return isSentenceEnd(older) ? isSentenceEnd(change.added.at(-1)) : isSentenceEnd(change.removed.at(-1));
}

// Whether matched words differ only in the case of their first letter, and the change before them starts
// with a capital in the version whose word lacks it.
function isMovedStart(older: KeyedWord, newer: KeyedWord, change: OpenChange): boolean {
  const [ one, other ] = [ older.strictKey, newer.strictKey ];
  if (one.slice(1) !== other.slice(1) || one.charAt(0).toLowerCase() !== other.charAt(0).toLowerCase()) {
    return false;
  }
  return isCapitalised(older) ? isCapitalised(change.added[0]) : isCapitalised(change.removed[0]);
}

// Compares two versions of a document from their pages' text. Words are matched ignoring case and the
// punctuation around them, as getLenientKey says. A change of case or punctuation alone is minor, and a viewer
// may leave it out. The changes are numbered in document order, from `<idPrefix>-1`. A version with no text
// cannot be compared, and two that differ past the limits are not. An unexpected error rejects the promise
// rather than throwing.
export function compareRevisions(
  older: PageText[],
  newer: PageText[],
  { limits = DIFF_LIMITS, idPrefix = "change" }: { limits?: DiffLimits; idPrefix?: string } = {},
): Promise<Comparison> {
  return Promise.resolve().then(() => compareNow(older, newer, limits, idPrefix));
}

// Compares the versions at once, throwing on an unexpected error.
function compareNow(older: PageText[], newer: PageText[], limits: DiffLimits, idPrefix: string): Comparison {
  const hasText = (pages: PageText[]) => pages.some(page => page.text.trim() !== "");
  if (!hasText(older) || !hasText(newer)) {
    const without = hasText(older) ? "newer" : hasText(newer) ? "older" : "both";
    return { status: "noText", without };
  }
  const before = getKeyedWords(older);
  const after = getKeyedWords(newer);
  const parts = diffWords(before, after, limits);
  if (parts === undefined) {
    return { status: "tooDifferent" };
  }
  const walked: (OpenChange & { pairs?: true })[] = [];
  const aligned: [ number, number ][] = [];
  // A run of matched words that differ in case or punctuation is one change. A word the same in both versions,
  // or another change, ends the run.
  let open: (OpenChange & { pairs?: true }) | undefined;
  walkDiff(parts, before, after, {
    onChange: change => {
      open = undefined;
      walked.push(change);
    },
    onSame: (from, count) => {
      for (let index = 0; index < count; index++) {
        const [ older, newer ] = [ from.older + index, from.newer + index ];
        aligned.push([ older, newer ]);
        if (before[older].strictKey === after[newer].strictKey) {
          open = undefined;
        } else if (open === undefined) {
          open = { from: { older, newer }, removed: [ before[older] ], added: [ after[newer] ], pairs: true };
          walked.push(open);
        } else {
          open.removed.push(before[older]);
          open.added.push(after[newer]);
        }
      }
    },
  });
  const found = walked.flatMap((change, index) => (change.pairs === true
    ? leaveOutMoved(change, walked[index - 1], walked[index + 1])
    : [ change ]));
  const minor = found.map(isMinor);
  // A change's context stops at the nearest changes shown with it. A minor change is only shown with all the
  // others, so any change stops its context. The context of any other change stops only at changes that are
  // not minor.
  const ends = { all: [] as (OpenChange | undefined)[], major: [] as (OpenChange | undefined)[] };
  const starts = { all: [] as (OpenChange | undefined)[], major: [] as (OpenChange | undefined)[] };
  let last: OpenChange | undefined;
  let lastMajor: OpenChange | undefined;
  found.forEach((change, index) => {
    ends.all.push(last);
    ends.major.push(lastMajor);
    last = change;
    lastMajor = minor[index] ? lastMajor : change;
  });
  last = undefined;
  lastMajor = undefined;
  for (let index = found.length - 1; index >= 0; index--) {
    starts.all[index] = last;
    starts.major[index] = lastMajor;
    last = found[index];
    lastMajor = minor[index] ? lastMajor : found[index];
  }
  const changes = found.map((change, index): RevisionChange => {
    const { from, removed, added } = change;
    const previous = minor[index] ? ends.all[index] : ends.major[index];
    const next = minor[index] ? starts.all[index] : starts.major[index];
    const getBounds = (version: "older" | "newer", words: KeyedWord[]) => ({
      start: previous === undefined
        ? 0
        : previous.from[version] + (version === "older" ? previous.removed : previous.added).length,
      end: next?.from[version] ?? words.length,
    });
    // In the version without the change's text, its point is after the words before it when that text ends
    // a sentence. Otherwise the point is at the start of the words after it.
    const olderSpans = removed.length > 0
      ? getSpans(removed)
      : [ getPointBefore(before, from.older, !isSentenceEnd(added.at(-1))) ];
    const newerSpans = added.length > 0
      ? getSpans(added)
      : [ getPointBefore(after, from.newer, !isSentenceEnd(removed.at(-1))) ];
    return {
      id: `${idPrefix}-${index + 1}`,
      minor: minor[index],
      removed: removed.length > 0 ? getChangeText(olderSpans, older) : "",
      added: added.length > 0 ? getChangeText(newerSpans, newer) : "",
      older: olderSpans,
      newer: newerSpans,
      context: {
        older: getContext(before, from.older, from.older + removed.length, getBounds("older", before)),
        newer: getContext(after, from.newer, from.newer + added.length, getBounds("newer", after)),
      },
    };
  });
  return { status: "compared", changes, alignment: { older: before, newer: after, pairs: aligned } };
}

// How many items `isBefore` accepts, by binary search. Those items must all come first.
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

// A point mapped into the other version, and whether it is on a word the two versions share.
export interface MappedPoint {
  point: TextPoint;
  shared: boolean;
}

// The place in the other version matching a point in the version `from`. A word the versions share maps to
// the start of its match. A word only one version has maps to where its change is in the other.
export function mapPoint(alignment: Alignment, from: "older" | "newer", { page, char }: TextPoint): MappedPoint {
  const [ side, otherSide ] = from === "older" ? [ 0, 1 ] as const : [ 1, 0 ] as const;
  const others = alignment[from === "older" ? "newer" : "older"];
  const isAtOrBefore = (word: Word) => word.page < page || (word.page === page && word.start <= char);
  const index = countBefore(alignment[from], isAtOrBefore) - 1;
  const pairs = countBefore(alignment.pairs, each => each[side] <= index);
  const pair = pairs > 0 ? alignment.pairs[pairs - 1] : undefined;
  const shared = pair?.[side] === index;
  const matching = pair === undefined ? 0 : pair[otherSide] + (shared ? 0 : 1);
  const word = others.at(Math.min(matching, others.length - 1));
  return { point: word === undefined ? { page: 1, char: 0 } : { page: word.page, char: word.start }, shared };
}
