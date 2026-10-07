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

// Locates a cited quote in a source PDF so the viewer can highlight it.

// One run of text PDF.js read off a page, in PDF user space (origin at the bottom left,
// y on the baseline). The viewer turns the rectangles below into pixels.
export interface PdfTextRun {
  str: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

// A highlight in the same space: y is the bottom of the box.
export interface HighlightRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

// Shorter than this, a quote matches too easily to be worth a mark.
const MIN_QUOTE_LENGTH = 6;

// PDFs use typographic quotes and dashes; a quote typed by a person or a model rarely does.
// A soft hyphen only marks where a word may break, so it maps to nothing.
const PLAIN_CHARS = new Map<string, string>([
  [ "‘", "'" ], [ "’", "'" ], [ "‚", "'" ], [ "‛", "'" ], [ "′", "'" ],
  [ "“", "\"" ], [ "”", "\"" ], [ "„", "\"" ], [ "″", "\"" ],
  [ "‐", "-" ], [ "‑", "-" ], [ "‒", "-" ], [ "–", "-" ], [ "—", "-" ],
  [ "―", "-" ], [ "−", "-" ], [ "­", "" ],
]);

const COMBINING_MARKS = /[̀-ͯ]/g;

// Used when PDF.js gives a run no height.
const DEFAULT_FONT_SIZE = 12;

interface Spot {
  run: number;
  offset: number;
}

// The page the link names, and the path with the fragment taken off so PDF.js fetches the file.
export function parsePdfSource(source: string): { url: string; page?: number } {
  const hash = source.indexOf("#");
  const url = hash < 0 ? source : source.slice(0, hash);
  const fragment = hash < 0 ? "" : source.slice(hash + 1);
  const page = Number(/(?:^|&)page=(\d+)/i.exec(fragment)?.[1]);
  return { url, page: page > 0 ? page : undefined };
}

// Cited page first, then outwards from it: a wrong page number is most often off by a little.
export function getPageSearchOrder(pageCount: number, preferred?: number): number[] {
  const pages = Array.from({ length: pageCount }, (_, index) => index + 1);
  if (preferred === undefined || preferred < 1 || preferred > pageCount) {
    return pages;
  }
  // On a tie the later page goes first: 2, 3, 1, 4 from page 2.
  return pages.sort((a, b) => Math.abs(a - preferred) - Math.abs(b - preferred) || b - a);
}

// The quote in the form findQuoteRects compares, or undefined when it is too short to trust.
export function getSearchText(quote: string): string | undefined {
  const { text } = readText([ { str: quote, x: 0, y: 0, width: 0, height: 0 } ]);
  return text.length < MIN_QUOTE_LENGTH ? undefined : text;
}

// Where the quote sits on this page. Empty when the page's text does not contain it: a scan, or a
// parse that rewrote the line, has nothing honest to draw a box around.
export function findQuoteRects(runs: readonly PdfTextRun[], searchText: string): HighlightRect[] {
  const page = readText(runs);
  const at = page.text.indexOf(searchText);
  return at < 0 ? [] : getRects(runs, page.spots.slice(at, at + searchText.length));
}

// The text with no spaces, case or accents, and no hyphens except a minus before a digit. PDF.js
// guesses spaces and line ends from the layout, and its guesses often differ from the quote.
// Each character keeps the spot in the runs it came from.
function readText(runs: readonly PdfTextRun[]): { text: string; spots: Spot[] } {
  const chars: string[] = [];
  const spots: Spot[] = [];
  runs.forEach((run, runIndex) => {
    for (let offset = 0; offset < run.str.length; offset++) {
      for (const ch of getPlainForm(run.str.charAt(offset))) {
        if (ch > " ") {
          chars.push(ch);
          spots.push({ run: runIndex, offset });
        }
      }
    }
  });
  let text = "";
  const kept: Spot[] = [];
  chars.forEach((ch, index) => {
    if (ch !== "-" || isDigit(chars[index + 1])) {
      text += ch;
      kept.push(spots[index]);
    }
  });
  return { text, spots: kept };
}

// NFKD splits accents off their letters and ligatures into letters, so "é" and "e" + U+0301 match.
// It runs one character at a time, so each part keeps the spot of the character it came from.
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

function getFontSize(run: PdfTextRun): number {
  return run.height > 0 ? run.height : DEFAULT_FONT_SIZE;
}

// One box per run the match passes through.
function getRects(runs: readonly PdfTextRun[], spots: readonly Spot[]): HighlightRect[] {
  const ranges = new Map<number, { start: number; end: number }>();
  for (const { run, offset } of spots) {
    const range = ranges.get(run);
    if (range === undefined) {
      ranges.set(run, { start: offset, end: offset + 1 });
    } else {
      range.end = offset + 1;
    }
  }
  return Array.from(ranges, ([ run, { start, end } ]) => getRect(runs[run], start, end));
}

// PDF.js gives one width per run, so each character counts as an even share of it.
function getRect(run: PdfTextRun, from: number, to: number): HighlightRect {
  const chars = Math.max(run.str.length, 1);
  const font = getFontSize(run);
  return {
    x: run.x + run.width * (from / chars),
    y: run.y - font * 0.2,
    width: run.width * ((to - from) / chars),
    height: font,
  };
}
