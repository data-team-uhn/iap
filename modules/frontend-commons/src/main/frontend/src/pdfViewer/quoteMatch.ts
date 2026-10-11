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

// Shorter than this, a quote matches too easily to be worth a mark.
const MIN_QUOTE_LENGTH = 6;

// A quote broken across two pages: how far from the end of the first page its start may end, and from
// the start of the next its rest may begin, in compared characters, leaving room for a footer, a page
// number and a header; and how short either part may be before it matches too easily to trust.
const PAGE_EDGE = 300;
const MIN_PART_LENGTH = 12;

// PDFs use typographic quotes and dashes; a quote typed by a person or a model rarely does.
// A soft hyphen only marks where a word may break, so it maps to nothing.
const PLAIN_CHARS = new Map<string, string>([
  [ "‘", "'" ], [ "’", "'" ], [ "‚", "'" ], [ "‛", "'" ], [ "′", "'" ],
  [ "“", "\"" ], [ "”", "\"" ], [ "„", "\"" ], [ "″", "\"" ],
  [ "‐", "-" ], [ "‑", "-" ], [ "‒", "-" ], [ "–", "-" ], [ "—", "-" ],
  [ "―", "-" ], [ "−", "-" ], [ "\u00ad", "" ],
]);

const COMBINING_MARKS = /[\u0300-\u036f]/g;

const SURROUNDING_QUOTES = /^["'“”‘’„‚«»\s]+|["'“”‘’„‚«»\s]+$/g;

export interface QuoteMatch {
  index: number;
  length: number;
}

// The quote without the quotation marks and spaces around it, which are not part of the passage.
export function unquote(quote: string): string {
  return quote.replace(SURROUNDING_QUOTES, "");
}

// The quote in the form findQuote compares, or undefined when it is too short to trust.
export function getSearchText(quote: string): string | undefined {
  const { text } = readText(unquote(quote));
  return text.length < MIN_QUOTE_LENGTH ? undefined : text;
}

// Every place the quote occurs in a page's text, as offsets into that text.
export function findQuote(pageText: string, searchText: string): QuoteMatch[] {
  const page = readText(pageText);
  const matches: QuoteMatch[] = [];
  for (let at = page.text.indexOf(searchText); at >= 0; at = page.text.indexOf(searchText, at + searchText.length)) {
    matches.push(toMatch(page, at, searchText.length));
  }
  return matches;
}

// Where a quote broken across two pages sits: its start near the end of the first page, and its rest
// near the start of the next. Undefined when no break fits.
export function findQuoteAcrossPages(
  firstPage: string,
  nextPage: string,
  searchText: string,
): { first: QuoteMatch; next: QuoteMatch } | undefined {
  const first = readText(firstPage);
  const next = readText(nextPage);
  for (let split = searchText.length - MIN_PART_LENGTH; split >= MIN_PART_LENGTH; split--) {
    const start = searchText.slice(0, split);
    const rest = searchText.slice(split);
    const startAt = first.text.lastIndexOf(start);
    const restAt = next.text.indexOf(rest);
    if (startAt >= 0 && startAt + start.length >= first.text.length - PAGE_EDGE && restAt >= 0 && restAt <= PAGE_EDGE) {
      return { first: toMatch(first, startAt, start.length), next: toMatch(next, restAt, rest.length) };
    }
  }
  return undefined;
}

function toMatch(page: { offsets: number[] }, at: number, length: number): QuoteMatch {
  const start = page.offsets[at];
  return { index: start, length: page.offsets[at + length - 1] + 1 - start };
}

// The text with no spaces, case or accents, and no hyphens except a minus before a digit. PDF.js
// guesses spaces and line ends from the layout, and its guesses often differ from the quote.
// Each character keeps the offset in the source it came from.
function readText(source: string): { text: string; offsets: number[] } {
  const chars: string[] = [];
  const sourceOffsets: number[] = [];
  for (let offset = 0; offset < source.length; offset++) {
    for (const ch of getPlainForm(source.charAt(offset))) {
      if (ch > " ") {
        chars.push(ch);
        sourceOffsets.push(offset);
      }
    }
  }
  let text = "";
  const offsets: number[] = [];
  chars.forEach((ch, index) => {
    if (ch !== "-" || isDigit(chars[index + 1])) {
      text += ch;
      offsets.push(sourceOffsets[index]);
    }
  });
  return { text, offsets };
}

// NFKD splits accents off their letters and ligatures into letters, so "é" and "e" + U+0301 match.
function getPlainForm(ch: string): string {
  const plain = PLAIN_CHARS.get(ch) ?? ch;
  if (plain < "\u0080") {
    return plain.toLowerCase();
  }
  return plain.normalize("NFKD").replace(COMBINING_MARKS, "").toLowerCase();
}

function isDigit(ch: string | undefined): boolean {
  return ch !== undefined && ch >= "0" && ch <= "9";
}
