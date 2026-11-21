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
// Finds a quote in a page's text, however PDF.js spaced, hyphenated or punctuated the page. The quote and
// the page are both brought to a compared form first, which keeps the offset each character came from.

// A quote shorter than this, in compared characters, matches too easily to trust.
const MIN_QUOTE_LENGTH = 6;

// How far each part of a quote broken across two pages may lie from the page break, in compared
// characters. This leaves room for a footer, a page number and a header between them.
const PAGE_EDGE = 300;
// Each part of a quote broken across two pages is at least this long, in compared characters. A shorter
// part matches too easily to trust.
const MIN_PART_LENGTH = 12;

// Typographic quotation marks, apostrophes, primes and dashes, each with the plain character a person or a
// model types instead. A soft hyphen maps to nothing, since it only marks where a word may break.
const PLAIN_CHARS = new Map<string, string>([
  [ "‘", "'" ], [ "’", "'" ], [ "‚", "'" ], [ "‛", "'" ], [ "′", "'" ],
  [ "“", "\"" ], [ "”", "\"" ], [ "„", "\"" ], [ "″", "\"" ],
  [ "‐", "-" ], [ "‑", "-" ], [ "‒", "-" ], [ "–", "-" ], [ "—", "-" ],
  [ "―", "-" ], [ "−", "-" ], [ "\u00ad", "" ],
]);

// The accents NFKD splits off their letters.
const COMBINING_MARKS = /[\u0300-\u036f]/g;

// Quotation marks and spaces at either end of a quote.
const SURROUNDING_QUOTES = /^["'“”‘’„‚«»\s]+|["'“”‘’„‚«»\s]+$/g;

// Where a quote occurs in a page's text, as an offset into it and a length.
export interface QuoteMatch {
  index: number;
  length: number;
}

// The quote without the quotation marks and spaces around it, which are not part of the passage.
export function unquote(quote: string): string {
  return quote.replace(SURROUNDING_QUOTES, "");
}

// A text in the form quotes are compared in. For each of its characters, `offsets` holds the offset in the
// source text that it came from.
export interface PreparedText {
  text: string;
  offsets: number[];
}

// The text in the form quotes are compared in.
export function getComparedText(text: string): string {
  return prepareText(text).text;
}

// The quote without the quotation marks around it, in the form quotes are compared in. Undefined when that
// is too short to trust.
export function getSearchText(quote: string): string | undefined {
  const text = getComparedText(unquote(quote));
  return text.length < MIN_QUOTE_LENGTH ? undefined : text;
}

// Every place a quote occurs in a page's text, as offsets into the page's source text. `searchText` is the
// quote in the compared form `getSearchText` gives.
export function findQuote(page: PreparedText, searchText: string): QuoteMatch[] {
  const matches: QuoteMatch[] = [];
  for (let at = page.text.indexOf(searchText); at >= 0; at = page.text.indexOf(searchText, at + searchText.length)) {
    matches.push(createQuoteMatch(page, at, searchText.length));
  }
  return matches;
}

// Where a quote broken across two pages sits: its start near the end of the first page, and its rest near
// the start of the next. Undefined when no split of the quote fits. Only the pages' ends are searched, so
// a long page costs no more than a short one.
export function findQuoteAcrossPages(
  first: PreparedText,
  next: PreparedText,
  searchText: string,
): { first: QuoteMatch; next: QuoteMatch } | undefined {
  const reach = PAGE_EDGE + searchText.length;
  const endFrom = Math.max(0, first.text.length - reach);
  const end = first.text.slice(endFrom);
  const start = next.text.slice(0, reach);
  for (let split = searchText.length - MIN_PART_LENGTH; split >= MIN_PART_LENGTH; split--) {
    const head = searchText.slice(0, split);
    const rest = searchText.slice(split);
    const headAt = endFrom + end.lastIndexOf(head);
    const restAt = start.indexOf(rest);
    const headFits = headAt >= endFrom && headAt + head.length >= first.text.length - PAGE_EDGE;
    if (headFits && restAt >= 0 && restAt <= PAGE_EDGE) {
      return { first: createQuoteMatch(first, headAt, head.length), next: createQuoteMatch(next, restAt, rest.length) };
    }
  }
  return undefined;
}

// The match covering `length` compared characters from `at`, as an offset and a length in the source text.
function createQuoteMatch(page: { offsets: number[] }, at: number, length: number): QuoteMatch {
  const start = page.offsets[at];
  return { index: start, length: page.offsets[at + length - 1] + 1 - start };
}

// The text in the form quotes are compared in. Quotation marks and dashes are plain, and letters lower case
// without accents. Spaces go, since PDF.js guesses them from the layout and its guesses often differ from
// the quote. Hyphens and dashes go too, so a word broken at a line end still matches. One before a digit
// stays, as the minus of a negative number.
export function prepareText(source: string): PreparedText {
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

// A character in its plain form, which is several letters for a ligature. NFKD splits accents off their
// letters and ligatures into letters, so "é", "e" + U+0301 and "e" all match.
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
