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
import {
  findQuote,
  findQuoteAcrossPages,
  getComparedText,
  getSearchText,
  prepareText,
  unquote,
  type PreparedText,
  type QuoteMatch,
} from "./quoteMatch";

// Whose mark a passage is, an AI assistant's or a person's, and what a comparison of two versions of a
// document found.
export const PASSAGE_KINDS = [ "assistant", "annotation" ] as const;
export const CHANGE_KINDS = [ "added", "removed" ] as const;
export const MARK_KINDS = [ ...PASSAGE_KINDS, ...CHANGE_KINDS ] as const;

export type MarkKind = typeof MARK_KINDS[number];
export type PassageKind = typeof PASSAGE_KINDS[number];
export type ChangeKind = typeof CHANGE_KINDS[number];

export function isChangeKind(kind: MarkKind): kind is ChangeKind {
  return (CHANGE_KINDS as readonly string[]).includes(kind);
}

// What a mark covers on one page, as offsets into the page's text as PDF.js reads it for its text
// layer: the whole passage, or the part of it on that page. Equal offsets are a point between two
// characters, drawn as a marker. A span may be drawn as a kind other than its mark's, as where a change
// removed text and added other text in its place.
export interface MarkSpan {
  page: number;
  start: number;
  end: number;
  kind?: MarkKind;
}

interface MarkBase {
  id: string;
  // Whose mark it is, or what change. It is coloured accordingly.
  kind: MarkKind;
}

// A passage to find and highlight, looked for from its page onwards: the first when absent.
export interface QuotedMark extends MarkBase {
  quote: string;
  page?: number;
}

// A passage whose place is known, as the spans it covers on each page it runs over.
export interface PlacedMark extends MarkBase {
  spans: MarkSpan[];
}

// A point in a page's text, before the character at an offset.
export interface TextPoint {
  page: number;
  char: number;
}

// A passage kept so that it can be found again, as the W3C's position and quote selectors keep one: where
// it starts and ends, what it says, and some of the text before and after it on its pages.
export interface TextAnchor {
  start: TextPoint;
  end: TextPoint;
  quote: string;
  before: string;
  after: string;
}

// A passage kept as an anchor, such as one a person selected to comment on. It is placed where the anchor
// says while the text there still says its quote, and otherwise where its quote is found with the text
// most like what was around it.
export interface AnchoredMark extends MarkBase {
  anchor: TextAnchor;
}

export type Mark = QuotedMark | PlacedMark | AnchoredMark;

// A page's text as PDF.js reads it for its text layer, and the offsets where its lines end, which the
// text layer breaks with elements of its own rather than with characters.
export interface PageText {
  text: string;
  lineEnds: number[];
}

// Where a passage is, once it is known, from the page it starts on to the one it ends on, and the text
// it covers there.
export type MarkPlace =
  | { status: "searching" | "tooShort" | "notFound" | "noText" }
  | { status: "found"; page: number; toPage?: number; spans: MarkSpan[]; text: string };

// Each page's text in the form quotes are compared in, prepared once, however many quotes are looked for in
// it, and however often the marks change.
const preparedTexts = new WeakMap<PageText, PreparedText>();

function getPrepared(page: PageText): PreparedText {
  const prepared = preparedTexts.get(page) ?? prepareText(page.text);
  preparedTexts.set(page, prepared);
  return prepared;
}

// A span with the text of the page it is on.
interface SpanOnPage {
  span: MarkSpan;
  page: PageText;
}

function toSpan(page: number, { index, length }: QuoteMatch): MarkSpan {
  return { page, start: index, end: index + length };
}

// The text between two offsets of a page as it reads: with a space where a line ends, unless a hyphen
// breaks a word there, and runs of spaces as one.
export function getPassageText({ text, lineEnds }: PageText, start: number, end: number): string {
  let passage = "";
  let from = start;
  for (const lineEnd of lineEnds.filter(offset => offset > start && offset < end)) {
    const line = text.slice(from, lineEnd);
    passage += line.endsWith("-") ? line : `${line} `;
    from = lineEnd;
  }
  return `${passage}${text.slice(from, end)}`.replace(/\s+/g, " ").trim();
}

// The page a mark is on, or looked for from.
export function getMarkPage(mark: Mark): number | undefined {
  if ("anchor" in mark) {
    return mark.anchor.start.page;
  }
  return "spans" in mark ? mark.spans.at(0)?.page : mark.page;
}

// What a mark's passage says: the quote given, or kept with its anchor, or, for a mark placed by its
// spans, the text found there.
export function getMarkText(mark: Mark, place: MarkPlace): string | undefined {
  if ("anchor" in mark) {
    return mark.anchor.quote;
  }
  if ("quote" in mark) {
    return unquote(mark.quote);
  }
  return place.status === "found" ? place.text : undefined;
}

// The pages from one to another, within the document's.
function pagesBetween(from: number, to: number, pageCount: number): number[] {
  const first = Math.max(1, from);
  return Array.from({ length: Math.max(0, Math.min(to, pageCount) - first + 1) }, (_, index) => first + index);
}

// The spans from one point to another, over the pages between them, given their text, or none if they do
// not fit it.
function getSpansBetween(start: TextPoint, end: TextPoint, pages: PageText[]): SpanOnPage[] | undefined {
  if (start.page > end.page || ![ start.page, end.page, start.char, end.char ].every(Number.isInteger)) {
    return undefined;
  }
  const read = pages.map((page, index) => {
    const on = start.page + index;
    const from = on === start.page ? start.char : 0;
    const to = on === end.page ? end.char : page.text.length;
    return { span: { page: on, start: from, end: to }, page };
  });
  const fits = read.every(({ span, page }) => (
    span.start >= 0 && span.start <= span.end && span.end <= page.text.length
  ));
  return fits ? read : undefined;
}

// The text spans cover, as it reads.
function getTextOf(read: SpanOnPage[]): string {
  return read
    .map(({ span: { start, end }, page }) => getPassageText(page, start, end))
    .filter(part => part !== "")
    .join(" ");
}

// Where an anchor's quote occurs, and how much of what was before and after it is still there.
interface Candidate {
  read: SpanOnPage[];
  likeness: number;
}

// How much of what was before and after a passage is still there, in compared characters.
function getContextLikeness(
  { before, after }: TextAnchor,
  textBefore: string,
  textAfter: string,
): number {
  const was = { before: getComparedText(before), after: getComparedText(after) };
  const is = { before: getComparedText(textBefore), after: getComparedText(textAfter) };
  let sameBefore = 0;
  while (sameBefore < was.before.length && was.before.at(-1 - sameBefore) === is.before.at(-1 - sameBefore)) {
    sameBefore++;
  }
  let sameAfter = 0;
  while (sameAfter < was.after.length && was.after[sameAfter] === is.after[sameAfter]) {
    sameAfter++;
  }
  return sameBefore + sameAfter;
}

export class MarkLocator {
  readonly #marks: Mark[];
  readonly #pageCount: number;
  readonly #texts = new Map<number, PageText>();
  readonly #searchTexts = new Map<string, string>();
  // Where each passage is whole on a page, by page: the first time it occurs there.
  readonly #whole = new Map<string, Map<number, SpanOnPage>>();
  // The page breaks each passage fits across, by the page before the break.
  readonly #across = new Map<string, Map<number, [ SpanOnPage, SpanOnPage ]>>();
  #hasText = false;

  // Before the document is open, its page count is not known, and every passage is still looked for.
  constructor(marks: Mark[], pageCount = 0) {
    this.#marks = marks;
    this.#pageCount = pageCount;
    for (const mark of marks) {
      const searchText = "quote" in mark ? getSearchText(mark.quote) : undefined;
      if (searchText !== undefined) {
        this.#searchTexts.set(mark.id, searchText);
        this.#whole.set(mark.id, new Map());
        this.#across.set(mark.id, new Map());
      }
    }
  }

  addPage(page: number, pageText: PageText): void {
    this.#texts.set(page, pageText);
    this.#hasText ||= pageText.text.trim().length > 0;
    for (const [ id, searchText ] of this.#searchTexts) {
      const match = findQuote(getPrepared(pageText), searchText).at(0);
      if (match !== undefined) {
        this.#whole.get(id)?.set(page, { span: toSpan(page, match), page: pageText });
      }
      for (const before of [ page - 1, page ]) {
        const first = this.#texts.get(before);
        const next = this.#texts.get(before + 1);
        if (first === undefined || next === undefined) {
          continue;
        }
        const found = findQuoteAcrossPages(getPrepared(first), getPrepared(next), searchText);
        if (found !== undefined) {
          this.#across.get(id)?.set(before, [
            { span: toSpan(before, found.first), page: first },
            { span: toSpan(before + 1, found.next), page: next },
          ]);
        }
      }
    }
  }

  getPlaces(): Record<string, MarkPlace> {
    return Object.fromEntries(this.#marks.map(mark => [ mark.id, this.#getPlace(mark) ]));
  }

  #getPlace(mark: Mark): MarkPlace {
    if ("spans" in mark) {
      return this.#getPlaceOf(mark.spans);
    }
    if ("anchor" in mark) {
      return this.#getAnchoredPlace(mark.anchor);
    }
    const searchText = this.#searchTexts.get(mark.id);
    if (searchText === undefined) {
      return { status: "tooShort" };
    }
    if (this.#texts.size === 0) {
      return { status: "searching" };
    }
    const order = this.#searchOrder(mark.page);
    for (const page of order) {
      if (!this.#texts.has(page)) {
        return { status: "searching" };
      }
      const whole = this.#whole.get(mark.id)?.get(page);
      if (whole !== undefined) {
        return this.#found([ whole ]);
      }
    }
    for (const page of order) {
      const across = this.#across.get(mark.id)?.get(page);
      if (across !== undefined) {
        return this.#found(across);
      }
    }
    return { status: this.#hasText ? "notFound" : "noText" };
  }

  // Spans are taken as they are, once their pages are read, unless they are not in the document, or run
  // backwards or past the end of their page's text.
  #getPlaceOf(spans: MarkSpan[]): MarkPlace {
    const outside = this.#pageCount > 0 && spans.some(({ page }) => page < 1 || page > this.#pageCount);
    if (spans.length === 0 || outside) {
      return { status: "notFound" };
    }
    const read = spans.flatMap(span => {
      const page = this.#texts.get(span.page);
      return page === undefined ? [] : [ { span, page } ];
    });
    if (read.length < spans.length) {
      return { status: "searching" };
    }
    const fits = read.every(({ span: { start, end }, page }) => (
      Number.isInteger(start) && Number.isInteger(end) && start >= 0 && start <= end && end <= page.text.length
    ));
    return fits ? this.#found(read) : { status: "notFound" };
  }

  // Where the anchor says, as soon as its pages are read, if the text there still says its quote, or
  // else, once every page is read, the occurrence of its quote with the text around it most like what was
  // around it, whole on a page rather than across a break, and nearest its page. A quote too short to
  // trust needs some of that text to be the same.
  #getAnchoredPlace(anchor: TextAnchor): MarkPlace {
    const { start, end, quote } = anchor;
    const searchText = getComparedText(quote);
    if (searchText === "") {
      return { status: "notFound" };
    }
    const inDocument = start.page >= 1 && end.page <= this.#pageCount;
    const pages = inDocument
      ? pagesBetween(start.page, end.page, this.#pageCount).map(page => this.#texts.get(page))
      : [];
    if (inDocument && pages.every((page): page is PageText => page !== undefined)) {
      const read = getSpansBetween(start, end, pages);
      if (read !== undefined && getComparedText(getTextOf(read)) === searchText) {
        return this.#found(read);
      }
    }
    if (this.#pageCount === 0 || this.#texts.size < this.#pageCount) {
      return { status: "searching" };
    }
    const trusted = getSearchText(quote) !== undefined;
    const distance = ({ read }: Candidate) => Math.abs(read[0].span.page - start.page);
    const best = this.#findAll(searchText, anchor)
      .filter(({ likeness }) => trusted || likeness > 0)
      .sort((one: Candidate, other: Candidate) => (
        other.likeness - one.likeness || one.read.length - other.read.length || distance(one) - distance(other)
      ))
      .at(0);
    if (best !== undefined) {
      return this.#found(best.read);
    }
    return { status: this.#hasText ? "notFound" : "noText" };
  }

  // Every occurrence of a quote, whole on a page or across a page break, in page order, with how like the
  // anchor's the text around it is.
  #findAll(searchText: string, anchor: TextAnchor): Candidate[] {
    const around = (anchor.before.length + anchor.after.length) * 2;
    const before = ({ text }: PageText, at: number) => text.slice(Math.max(0, at - around), at);
    const after = ({ text }: PageText, at: number) => text.slice(at, at + around);
    const pages = [ ...this.#texts ].sort(([ one ], [ other ]) => one - other);
    return pages.flatMap(([ number, page ]) => {
      const whole = findQuote(getPrepared(page), searchText).map(match => {
        const span = toSpan(number, match);
        const likeness = getContextLikeness(anchor, before(page, span.start), after(page, span.end));
        return { read: [ { span, page } ], likeness };
      });
      const next = this.#texts.get(number + 1);
      if (next === undefined) {
        return whole;
      }
      const across = findQuoteAcrossPages(getPrepared(page), getPrepared(next), searchText);
      if (across === undefined) {
        return whole;
      }
      const read = [
        { span: toSpan(number, across.first), page },
        { span: toSpan(number + 1, across.next), page: next },
      ];
      const likeness = getContextLikeness(anchor, before(page, read[0].span.start), after(next, read[1].span.end));
      return [ ...whole, { read, likeness } ];
    });
  }

  #found(read: SpanOnPage[]): MarkPlace {
    const spans = read.map(({ span }) => span);
    const pages = spans.map(({ page }) => page);
    const page = Math.min(...pages);
    const toPage = Math.max(...pages);
    return { status: "found", page, ...(toPage === page ? {} : { toPage }), spans, text: getTextOf(read) };
  }

  #searchOrder(from: number | undefined): number[] {
    const pages = Array.from({ length: this.#pageCount }, (_, index) => index + 1);
    const start = from !== undefined && from >= 1 && from <= this.#pageCount ? from - 1 : 0;
    return [ ...pages.slice(start), ...pages.slice(0, start) ];
  }
}

// Whether a passage is known to be one that cannot be marked: not found, too short to look for, or in a
// document without text.
export function cannotMark(place: MarkPlace): boolean {
  return [ "notFound", "tooShort", "noText" ].includes(place.status);
}

// Whether some passage is still being looked for.
export function isLooking(places: Record<string, MarkPlace>): boolean {
  return Object.values(places).some(place => place.status === "searching");
}

// The marks in the order given, with those whose passage cannot be marked after the others.
export function orderMarks(marks: Mark[], places: Record<string, MarkPlace>): Mark[] {
  const unmarked = (mark: Mark) => cannotMark(places[mark.id]);
  return [ ...marks.filter(mark => !unmarked(mark)), ...marks.filter(unmarked) ];
}

// What the marks are, the same for marks that are equal but other objects, as a caller passing them
// written in place gives on every render.
export function getMarksKey(marks: Mark[]): string {
  return JSON.stringify(marks.map(mark => {
    const { id, kind } = mark;
    if ("anchor" in mark) {
      const { start, end, quote, before, after } = mark.anchor;
      const point = ({ page, char }: TextPoint) => ({ page, char });
      return { id, kind, anchor: { start: point(start), end: point(end), quote, before, after } };
    }
    if ("spans" in mark) {
      const spans = mark.spans.map(span => ({ page: span.page, start: span.start, end: span.end, kind: span.kind }));
      return { id, kind, spans };
    }
    return { id, quote: mark.quote, page: mark.page, kind };
  }));
}

// The pages of the document to read first, as the only ones marks placed by their spans or kept as
// anchors need, in order.
export function getPlacedPages(marks: Mark[], pageCount: number): number[] {
  const pages = new Set(marks.flatMap(mark => {
    if ("anchor" in mark) {
      return pagesBetween(mark.anchor.start.page, mark.anchor.end.page, pageCount);
    }
    return "spans" in mark ? mark.spans.map(({ page }) => page).filter(page => page >= 1 && page <= pageCount) : [];
  }));
  return [ ...pages ].sort((one, other) => one - other);
}
