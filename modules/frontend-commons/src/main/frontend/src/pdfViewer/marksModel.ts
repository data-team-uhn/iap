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
import { findQuote, findQuoteAcrossPages, getSearchText, type QuoteMatch } from "./quoteMatch";

export const MARK_KINDS = [ "assistant", "annotation" ] as const;

export type MarkKind = typeof MARK_KINDS[number];

export interface Mark {
  id: string;
  // The passage to find and highlight.
  quote: string;
  // The page to start looking for it from: the first when absent.
  page?: number;
  // Whose mark it is, an AI assistant's or a person's. It is coloured accordingly.
  kind: MarkKind;
}

// What a mark covers on one page, as offsets into the page's text as PDF.js reads it for its text
// layer: the whole passage, or the start or the rest of one broken across two pages.
export interface MarkSpan {
  page: number;
  start: number;
  end: number;
}

// Where a passage is, once it is known. A passage broken across two pages ends on the next one.
export type MarkPlace =
  | { status: "searching" | "tooShort" | "notFound" | "noText" }
  | { status: "found"; page: number; toPage?: number; spans: MarkSpan[] };

function toSpan(page: number, { index, length }: QuoteMatch): MarkSpan {
  return { page, start: index, end: index + length };
}

export class MarkLocator {
  readonly #marks: Mark[];
  readonly #pageCount: number;
  readonly #texts = new Map<number, string>();
  readonly #searchTexts = new Map<string, string>();
  // Where each passage is whole on a page, by page: the first time it occurs there.
  readonly #whole = new Map<string, Map<number, MarkSpan>>();
  // The page breaks each passage fits across, by the page before the break.
  readonly #across = new Map<string, Map<number, [ MarkSpan, MarkSpan ]>>();
  #hasText = false;

  // Before the document is open, its page count is not known, and every passage is still looked for.
  constructor(marks: Mark[], pageCount = 0) {
    this.#marks = marks;
    this.#pageCount = pageCount;
    for (const mark of marks) {
      const searchText = getSearchText(mark.quote);
      if (searchText !== undefined) {
        this.#searchTexts.set(mark.id, searchText);
        this.#whole.set(mark.id, new Map());
        this.#across.set(mark.id, new Map());
      }
    }
  }

  addPage(page: number, text: string): void {
    this.#texts.set(page, text);
    this.#hasText ||= text.trim().length > 0;
    for (const [ id, searchText ] of this.#searchTexts) {
      const match = findQuote(text, searchText).at(0);
      if (match !== undefined) {
        this.#whole.get(id)?.set(page, toSpan(page, match));
      }
      for (const before of [ page - 1, page ]) {
        const first = this.#texts.get(before);
        const next = this.#texts.get(before + 1);
        const found = first === undefined || next === undefined
          ? undefined
          : findQuoteAcrossPages(first, next, searchText);
        if (found !== undefined) {
          this.#across.get(id)?.set(before, [ toSpan(before, found.first), toSpan(before + 1, found.next) ]);
        }
      }
    }
  }

  getPlaces(): Record<string, MarkPlace> {
    return Object.fromEntries(this.#marks.map(mark => [ mark.id, this.#getPlace(mark) ]));
  }

  #getPlace(mark: Mark): MarkPlace {
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
      const span = this.#whole.get(mark.id)?.get(page);
      if (span !== undefined) {
        return { status: "found", page, spans: [ span ] };
      }
    }
    for (const page of order) {
      const spans = this.#across.get(mark.id)?.get(page);
      if (spans !== undefined) {
        return { status: "found", page, toPage: page + 1, spans };
      }
    }
    return { status: this.#hasText ? "notFound" : "noText" };
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
  return JSON.stringify(marks.map(({ id, quote, page, kind }) => ({ id, quote, page, kind })));
}
