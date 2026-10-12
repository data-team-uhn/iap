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
import { findQuote, findQuoteAcrossPages, getSearchText, unquote, type QuoteMatch } from "./quoteMatch";

export const MARK_KINDS = [ "assistant", "annotation" ] as const;

export type MarkKind = typeof MARK_KINDS[number];

// What a mark covers on one page, as offsets into the page's text as PDF.js reads it for its text
// layer: the whole passage, or the part of it on that page. Equal offsets are a point between two
// characters.
export interface MarkSpan {
  page: number;
  start: number;
  end: number;
}

interface MarkBase {
  id: string;
  // Whose mark it is, an AI assistant's or a person's. It is coloured accordingly.
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

export type Mark = QuotedMark | PlacedMark;

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
  return "spans" in mark ? mark.spans.at(0)?.page : mark.page;
}

// What a mark's passage says: the quote given, or, for a mark placed by its spans, the text found there.
export function getMarkText(mark: Mark, place: MarkPlace): string | undefined {
  if ("quote" in mark) {
    return unquote(mark.quote);
  }
  return place.status === "found" ? place.text : undefined;
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

  addPage(page: number, text: string, lineEnds: number[] = []): void {
    const pageText = { text, lineEnds };
    this.#texts.set(page, pageText);
    this.#hasText ||= text.trim().length > 0;
    for (const [ id, searchText ] of this.#searchTexts) {
      const match = findQuote(text, searchText).at(0);
      if (match !== undefined) {
        this.#whole.get(id)?.set(page, { span: toSpan(page, match), page: pageText });
      }
      for (const before of [ page - 1, page ]) {
        const first = this.#texts.get(before);
        const next = this.#texts.get(before + 1);
        if (first === undefined || next === undefined) {
          continue;
        }
        const found = findQuoteAcrossPages(first.text, next.text, searchText);
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

  #found(read: SpanOnPage[]): MarkPlace {
    const spans = read.map(({ span }) => span);
    const pages = spans.map(({ page }) => page);
    const page = Math.min(...pages);
    const toPage = Math.max(...pages);
    const text = read
      .map(({ span: { start, end }, page: on }) => getPassageText(on, start, end))
      .filter(part => part !== "")
      .join(" ");
    return { status: "found", page, ...(toPage === page ? {} : { toPage }), spans, text };
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

// The marks in the order given, with those whose passage cannot be marked after the others.
export function orderMarks(marks: Mark[], places: Record<string, MarkPlace>): Mark[] {
  const unmarked = (mark: Mark) => cannotMark(places[mark.id]);
  return [ ...marks.filter(mark => !unmarked(mark)), ...marks.filter(unmarked) ];
}

// What the marks are, the same for marks that are equal but other objects, as a caller passing them
// written in place gives on every render.
export function getMarksKey(marks: Mark[]): string {
  return JSON.stringify(marks.map(mark => ("spans" in mark
    ? { id: mark.id, kind: mark.kind, spans: mark.spans.map(({ page, start, end }) => ({ page, start, end })) }
    : { id: mark.id, quote: mark.quote, page: mark.page, kind: mark.kind })));
}

// The pages to read first, as the only ones marks placed by their spans need, in order.
export function getPlacedPages(marks: Mark[]): number[] {
  const pages = new Set(marks.flatMap(mark => ("spans" in mark ? mark.spans.map(({ page }) => page) : [])));
  return [ ...pages ].sort((one, other) => one - other);
}
