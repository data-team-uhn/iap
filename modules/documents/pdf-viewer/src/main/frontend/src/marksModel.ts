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
// The marks the PDF viewer draws over a document's text, and where each one is. A mark is a quote to look
// for, spans whose place is known, or an anchor saved with what it anchors, such as a comment.

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

// Who marked a passage: an AI assistant, or a person annotating the document. PRETOTYPE: a review's evidence,
// issues and discussion are passages of their own kinds.
export const PASSAGE_KINDS = [
  "assistant", "annotation", "evidence", "issue", "otherIssue", "discussion", "draft", "warning",
] as const;
// What a comparison of two versions of a document found: text added or text removed.
export const CHANGE_KINDS = [ "added", "removed" ] as const;
export const MARK_KINDS = [ ...PASSAGE_KINDS, ...CHANGE_KINDS ] as const;

export type MarkKind = typeof MARK_KINDS[number];
export type PassageKind = typeof PASSAGE_KINDS[number];
export type ChangeKind = typeof CHANGE_KINDS[number];

export function isChangeKind(kind: MarkKind): kind is ChangeKind {
  return (CHANGE_KINDS as readonly string[]).includes(kind);
}

// The part of one page a mark covers, as offsets into the page's `PageText`. A passage that runs over
// several pages has one span on each. Equal offsets make a point between two characters. A point is drawn
// after the character before it.
export interface MarkSpan {
  page: number;
  start: number;
  end: number;
  // Draws a point before the character after it instead, as the start of the text that follows. At the end
  // of a line, the two places are a line apart.
  starts?: boolean;
}

interface MarkBase {
  id: string;
  // Who made the mark, or which kind of change it shows. It sets the mark's colour.
  kind: MarkKind;
}

// A passage to look for by its text. The search starts at `page`, or at the first page when there is none,
// and goes round to the pages before it.
export interface QuotedMark extends MarkBase {
  quote: string;
  page?: number;
}

// A passage whose place is known: the spans it covers, one on each page it runs over.
export interface PlacedMark extends MarkBase {
  spans: MarkSpan[];
}

// A point in a page's text, just before the character at offset `char`.
export interface TextPoint {
  page: number;
  char: number;
}

// A passage saved so that it can be found again: where it starts and ends, what it says, and some of the
// text before and after it. It follows the text position and text quote selectors of the W3C's Web
// Annotation model.
export interface TextAnchor {
  start: TextPoint;
  end: TextPoint;
  quote: string;
  before: string;
  after: string;
}

// A passage saved as an anchor, such as one a person selected to comment on.
export interface AnchoredMark extends MarkBase {
  anchor: TextAnchor;
}

export type Mark = QuotedMark | PlacedMark | AnchoredMark;

// No marks: one empty list, the same on every render, for the memos that depend on it.
export const NO_MARKS: Mark[] = [];

// A page's text as PDF.js builds its text layer, and the offsets where its lines end. The text holds no
// character at a line end, since the text layer breaks the line with an element.
export interface PageText {
  text: string;
  lineEnds: number[];
}

// Where a mark is, or why it has no place yet. A found mark runs from `page` to `toPage` and covers `text`.
// `toPage` is left out for a mark on one page. Otherwise the mark is still being looked for, too short to
// look for, not in the document, or in a document with no text.
export type MarkPlace =
  | { status: "searching" | "tooShort" | "notFound" | "noText" }
  | { status: "found"; page: number; toPage?: number; spans: MarkSpan[]; text: string };

// Each page's text in the form quotes are compared in. A page's text is prepared once, however many quotes
// are looked for in it and however often the marks change.
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

function getMatchSpan(page: number, { index, length }: QuoteMatch): MarkSpan {
  return { page, start: index, end: index + length };
}

// Whether a span lies in its page's text: whole offsets, running forwards, and no further than the text.
function fitsPage({ span: { start, end }, page }: SpanOnPage): boolean {
  return Number.isInteger(start) && Number.isInteger(end) && start >= 0 && start <= end && end <= page.text.length;
}

// The two spans of a quote found across the break after page `before`: the end of that page and the start
// of the next.
function getAcrossSpans(
  before: number,
  found: { first: QuoteMatch; next: QuoteMatch },
  pages: { first: PageText; next: PageText },
): [ SpanOnPage, SpanOnPage ] {
  return [
    { span: getMatchSpan(before, found.first), page: pages.first },
    { span: getMatchSpan(before + 1, found.next), page: pages.next },
  ];
}

// The text between two offsets of a page, as a person reads it. A line end reads as a space, except after
// a hyphen, where the word goes on with no space. Runs of spaces read as one, and none is kept at either
// end.
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

// The page a mark's anchor or first span is on, or the page where the search for its quote starts.
// Undefined for a quote with no page, or a mark with no spans.
export function getMarkPage(mark: Mark): number | undefined {
  if ("anchor" in mark) {
    return mark.anchor.start.page;
  }
  return "spans" in mark ? mark.spans.at(0)?.page : mark.page;
}

// The text a mark covers: its quote without the quotation marks, the quote its anchor kept, or the text
// under its spans. Undefined for a mark placed by its spans until they are found.
export function getMarkText(mark: Mark, place: MarkPlace): string | undefined {
  if ("anchor" in mark) {
    return mark.anchor.quote;
  }
  if ("quote" in mark) {
    return unquote(mark.quote);
  }
  return place.status === "found" ? place.text : undefined;
}

// The page numbers from `from` to `to`, leaving out any outside the document.
function getPagesBetween(from: number, to: number, pageCount: number): number[] {
  const first = Math.max(1, from);
  return Array.from({ length: Math.max(0, Math.min(to, pageCount) - first + 1) }, (_, index) => first + index);
}

// The spans from one point to another, one on each page between them, given those pages' text in order.
// Undefined when the points run backwards, are not whole numbers, or lie outside their page's text.
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
  return read.every(fitsPage) ? read : undefined;
}

// The text a run of spans covers, as a person reads it, with a space between pages.
function getSpansText(read: SpanOnPage[]): string {
  return read
    .map(({ span: { start, end }, page }) => getPassageText(page, start, end))
    .filter(part => part !== "")
    .join(" ");
}

// An occurrence of an anchor's quote, and how much of the anchor's text before and after it is there too.
interface Candidate {
  read: SpanOnPage[];
  likeness: number;
}

// How many characters of an anchor's text before and after its passage are still there, counted outwards
// from the passage in the form quotes are compared in.
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

// Finds where each mark is in a document whose pages are read one at a time, in any order. Add each page's
// text as it is read, then ask for the marks' places.
export class MarkLocator {
  readonly #marks: Mark[];
  readonly #pageCount: number;
  readonly #texts = new Map<number, PageText>();
  readonly #searchTexts = new Map<string, string>();
  // The first occurrence of each quote that lies whole on a page, by mark and then by page.
  readonly #whole = new Map<string, Map<number, SpanOnPage>>();
  // Each quote's occurrence across a page break, by mark and then by the page before the break.
  readonly #across = new Map<string, Map<number, [ SpanOnPage, SpanOnPage ]>>();
  #hasText = false;

  // Leave the page count out while the document is not open. Until it is given, every mark with something
  // to look for is still being looked for.
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

  // Adds the text of a page as it is read. Each quote is looked for in it, whole and across its breaks with
  // the pages either side.
  addPage(page: number, pageText: PageText): void {
    this.#texts.set(page, pageText);
    this.#hasText ||= pageText.text.trim().length > 0;
    for (const [ id, searchText ] of this.#searchTexts) {
      const match = findQuote(getPrepared(pageText), searchText).at(0);
      if (match !== undefined) {
        this.#whole.get(id)?.set(page, { span: getMatchSpan(page, match), page: pageText });
      }
      for (const before of [ page - 1, page ]) {
        const first = this.#texts.get(before);
        const next = this.#texts.get(before + 1);
        if (first === undefined || next === undefined) {
          continue;
        }
        const found = findQuoteAcrossPages(getPrepared(first), getPrepared(next), searchText);
        if (found !== undefined) {
          this.#across.get(id)?.set(before, getAcrossSpans(before, found, { first, next }));
        }
      }
    }
  }

  // Each mark's place, by its id, from the pages added so far.
  getPlaces(): Record<string, MarkPlace> {
    return Object.fromEntries(this.#marks.map(mark => [ mark.id, this.#getPlace(mark) ]));
  }

  // A mark's place. A quote is placed at its first whole occurrence in search order, once every page
  // before it in that order is read. A match across a page break counts only when no page holds the whole
  // quote.
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

  // A mark's spans are taken as they are once their pages are read. The mark is not found when it has no
  // spans, or a span is on a page the document lacks. The same goes for a span that runs backwards, runs
  // past its page's text, or has an offset that is not a whole number.
  #getPlaceOf(spans: MarkSpan[]): MarkPlace {
    const outside = this.#pageCount > 0
      && spans.some(({ page }) => !Number.isInteger(page) || page < 1 || page > this.#pageCount);
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
    return read.every(fitsPage) ? this.#found(read) : { status: "notFound" };
  }

  // An anchored mark is where its anchor says, as soon as those pages are read, if the text there still
  // matches its quote. Otherwise, once every page is read, it is the occurrence of the quote whose
  // surrounding text is most like the anchor's. Ties go first to an occurrence on a single page, then to
  // the one nearest the anchor's page. A quote too short to trust is found only where some of its
  // surrounding text is the same.
  #getAnchoredPlace(anchor: TextAnchor): MarkPlace {
    const { start, end, quote } = anchor;
    const searchText = getComparedText(quote);
    if (searchText === "") {
      return { status: "notFound" };
    }
    const inDocument = start.page >= 1 && end.page <= this.#pageCount;
    const pages = inDocument
      ? getPagesBetween(start.page, end.page, this.#pageCount).map(page => this.#texts.get(page))
      : [];
    if (inDocument && pages.every((page): page is PageText => page !== undefined)) {
      const read = getSpansBetween(start, end, pages);
      if (read !== undefined && getComparedText(getSpansText(read)) === searchText) {
        return this.#found(read);
      }
    }
    if (this.#pageCount === 0 || this.#texts.size < this.#pageCount) {
      return { status: "searching" };
    }
    const trusted = getSearchText(quote) !== undefined;
    const getDistance = ({ read }: Candidate) => Math.abs(read[0].span.page - start.page);
    const best = this.#findAll(searchText, anchor)
      .filter(({ likeness }) => trusted || likeness > 0)
      .sort((one: Candidate, other: Candidate) => (
        other.likeness - one.likeness || one.read.length - other.read.length || getDistance(one) - getDistance(other)
      ))
      .at(0);
    if (best !== undefined) {
      return this.#found(best.read);
    }
    return { status: this.#hasText ? "notFound" : "noText" };
  }

  // Every occurrence of a quote, whole on a page or across a page break, in page order. Each comes with how
  // closely its surrounding text matches the anchor's.
  #findAll(searchText: string, anchor: TextAnchor): Candidate[] {
    const around = (anchor.before.length + anchor.after.length) * 2;
    const getTextBefore = ({ text }: PageText, at: number) => text.slice(Math.max(0, at - around), at);
    const getTextAfter = ({ text }: PageText, at: number) => text.slice(at, at + around);
    const pages = [ ...this.#texts ].sort(([ one ], [ other ]) => one - other);
    return pages.flatMap(([ number, page ]) => {
      const whole = findQuote(getPrepared(page), searchText).map(match => {
        const span = getMatchSpan(number, match);
        const likeness = getContextLikeness(anchor, getTextBefore(page, span.start), getTextAfter(page, span.end));
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
      const read = getAcrossSpans(number, across, { first: page, next });
      const likeness = getContextLikeness(
        anchor,
        getTextBefore(page, read[0].span.start),
        getTextAfter(next, read[1].span.end),
      );
      return [ ...whole, { read, likeness } ];
    });
  }

  // The place of a mark found covering these spans.
  #found(read: SpanOnPage[]): MarkPlace {
    const spans = read.map(({ span }) => span);
    const pages = spans.map(({ page }) => page);
    const page = Math.min(...pages);
    const toPage = Math.max(...pages);
    return { status: "found", page, ...(toPage === page ? {} : { toPage }), spans, text: getSpansText(read) };
  }

  // The pages in the order a quote is looked for: from `from` to the last page, then from the first. It
  // starts at the first page when `from` is missing or outside the document.
  #searchOrder(from: number | undefined): number[] {
    const pages = Array.from({ length: this.#pageCount }, (_, index) => index + 1);
    const start = from !== undefined && from >= 1 && from <= this.#pageCount ? from - 1 : 0;
    return [ ...pages.slice(start), ...pages.slice(0, start) ];
  }
}

// Whether a mark is known to have no place: not found, too short to look for, or in a document with no
// text.
export function isUnmarkable(place: MarkPlace): boolean {
  return [ "notFound", "tooShort", "noText" ].includes(place.status);
}

// Whether any mark is still being looked for.
export function isLooking(places: Record<string, MarkPlace>): boolean {
  return Object.values(places).some(place => place.status === "searching");
}

// The marks in the order given, except that those known to have no place go last.
export function orderMarks(marks: Mark[], places: Record<string, MarkPlace>): Mark[] {
  const isUnmarked = (mark: Mark) => isUnmarkable(places[mark.id]);
  return [ ...marks.filter(mark => !isUnmarked(mark)), ...marks.filter(isUnmarked) ];
}

// A copy of a mark's own fields in a fixed order, leaving out anything else its object carries. Equal
// marks give equal copies.
function getMarkContents(mark: Mark): object {
  const { id, kind } = mark;
  if ("anchor" in mark) {
    const { start, end, quote, before, after } = mark.anchor;
    const copyPoint = ({ page, char }: TextPoint) => ({ page, char });
    return { id, kind, anchor: { start: copyPoint(start), end: copyPoint(end), quote, before, after } };
  }
  return "spans" in mark
    ? { id, kind, spans: mark.spans.map(({ page, start, end, starts }) => ({ page, start, end, starts })) }
    : { id, quote: mark.quote, page: mark.page, kind };
}

// A key for a mark that changes only when the mark's own fields do.
export function getMarkKey(mark: Mark): string {
  return JSON.stringify(getMarkContents(mark));
}

// A key for a list of marks that changes only when their own fields or their order do. Marks written
// inline in a component are new objects on every render, though equal.
export function getMarksKey(marks: Mark[]): string {
  return JSON.stringify(marks.map(getMarkContents));
}

// The pages the marks name, once each and in order: those their spans or anchors cover, and the page each
// quote is looked for from. Pages outside the document are left out. Reading these pages first places a
// mark that is where it says it is, without waiting for the pages before it.
export function getPlacedPages(marks: Mark[], pageCount: number): number[] {
  const pages = new Set(marks.flatMap(mark => {
    if ("anchor" in mark) {
      return getPagesBetween(mark.anchor.start.page, mark.anchor.end.page, pageCount);
    }
    return "spans" in mark ? mark.spans.map(({ page }) => page) : [ mark.page ];
  }));
  return [ ...pages ]
    .filter((page): page is number => page !== undefined && Number.isInteger(page) && page >= 1 && page <= pageCount)
    .sort((one, other) => one - other);
}
