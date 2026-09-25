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
  hasEOL: boolean;
}

// A highlight in the same space: y is the bottom of the box.
export interface HighlightRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

// Shorter than this, a quote matches too easily to be worth a mark. Same as MIN_QUOTE_LENGTH in
// QuoteVerifier.java, which never stores a shorter quote; change both together.
const MIN_QUOTE_LENGTH = 6;

// A half of a quote shorter than this is too common a phrase to stand for the whole passage.
const MIN_HALF_LENGTH = 20;

// Typographic quotes and dashes, as the parse writes them: the server checked the quote against
// Docling's text, which straightens them, while the PDF keeps what the author typed.
const FOLDED: Partial<Record<string, string>> = {
  "\u2018": "'", "\u2019": "'", "\u201a": "'", "\u201b": "'",
  "\u201c": "\"", "\u201d": "\"", "\u201e": "\"", "\u201f": "\"",
  "\u2010": "-", "\u2011": "-", "\u2012": "-", "\u2013": "-", "\u2014": "-",
};

interface Spot {
  run: number;
  offset: number;
}

// The page the link names, and the path with the fragment taken off so PDF.js fetches the file.
export function parsePdfSource(source: string): { url: string; page?: number } {
  const hash = source.indexOf("#");
  const url = hash < 0 ? source : source.slice(0, hash);
  const fragment = hash < 0 ? "" : source.slice(hash + 1);
  const match = /(?:^|&)page=(\d+)/.exec(fragment);
  const page = match === null ? undefined : Number(match[1]);
  return page !== undefined && page > 0 ? { url, page } : { url };
}

// Cited page first, then the rest. A quote the model paged wrong still turns up, and the page the
// link named is the one a person sees while that search runs.
export function pageSearchOrder(pageCount: number, preferred?: number): number[] {
  const order: number[] = [];
  if (preferred !== undefined && preferred >= 1 && preferred <= pageCount) {
    order.push(preferred);
  }
  for (let page = 1; page <= pageCount; page++) {
    if (page !== preferred) {
      order.push(page);
    }
  }
  return order;
}

// Where the quote sits on this page. Empty when the page's text does not contain it: a scan, or a
// parse that rewrote the line, has nothing honest to draw a box around.
//
// The server accepts a quote that is close rather than exact, and one that runs over a page break, so
// when the whole quote is not on the page the half of it that is gets the mark.
export function findQuoteRects(runs: readonly PdfTextRun[], quote: string): HighlightRect[] {
  const wanted = normalizeQuote(quote);
  if (wanted.length < MIN_QUOTE_LENGTH) {
    return [];
  }
  // A dash at the end of a line is usually a word broken across it, but not always
  const pages = [ readPage(runs, false), readPage(runs, true) ];
  for (const part of [ wanted, ...getHalves(wanted) ]) {
    for (const page of pages) {
      const at = page.text.indexOf(part);
      if (at >= 0) {
        return rectsFor(runs, page.spots, at, at + part.length);
      }
    }
  }
  return [];
}

function normalizeQuote(quote: string): string {
  return readPage([ { str: quote, x: 0, y: 0, width: 0, height: 0, hasEOL: false } ], false).text;
}

// The quote cut in two at the space nearest its middle, keeping only halves long enough to mean something.
function getHalves(wanted: string): string[] {
  const middle = wanted.indexOf(" ", Math.floor(wanted.length / 2));
  if (middle < 0) {
    return [];
  }
  return [ wanted.slice(0, middle), wanted.slice(middle + 1) ].filter(half => half.length >= MIN_HALF_LENGTH);
}

// One string for the page, and for each of its characters the run it came from.
//
// A hyphen or soft hyphen at the end of a line is taken as the word broken across the line, so it is
// dropped and the next line continues it, unless `keepHyphens` asks for the text as printed. Any other
// line break becomes a space. PDF.js already puts spaces inside a run, and collapsing whitespace
// afterwards makes a double space harmless.
function readPage(
  runs: readonly PdfTextRun[],
  keepHyphens: boolean,
): { text: string; spots: (Spot | undefined)[] } {
  let text = "";
  const spots: (Spot | undefined)[] = [];
  let pendingSpace = false;

  const pushSpace = () => {
    if (text.length > 0) {
      pendingSpace = true;
    }
  };
  const pushChar = (ch: string, spot: Spot) => {
    if (pendingSpace) {
      text += " ";
      spots.push(undefined);
      pendingSpace = false;
    }
    text += (FOLDED[ch] ?? ch).toLowerCase();
    spots.push(spot);
  };

  runs.forEach((run, runIndex) => {
    const hasNextRun = runIndex + 1 < runs.length;
    const endsLine = run.hasEOL || (hasNextRun && isNewLine(run, runs[runIndex + 1]));
    const hyphenBreak = endsLine && (run.str.endsWith("­") || (!keepHyphens && run.str.endsWith("-")));
    const end = hyphenBreak ? run.str.length - 1 : run.str.length;
    for (let index = 0; index < end; index++) {
      const ch = run.str.charAt(index);
      if (ch === "\u00ad") {
        continue;
      }
      if (/\s/.test(ch)) {
        pushSpace();
      } else {
        pushChar(ch, { run: runIndex, offset: index });
      }
    }
    if (endsLine && !hyphenBreak) {
      pushSpace();
    }
  });
  return { text, spots };
}

// Some PDF producers never set hasEOL at all, on any line. A line's own baseline (y) hardly ever
// lines up with the next line's, so a real vertical jump is a more trustworthy sign that a new
// line - and therefore a word space - follows, even when PDF.js says otherwise.
function isNewLine(run: PdfTextRun, next: PdfTextRun): boolean {
  const font = run.height > 0 ? run.height : 12;
  return Math.abs(next.y - run.y) > font * 0.4;
}

function rectsFor(
  runs: readonly PdfTextRun[],
  spots: readonly (Spot | undefined)[],
  from: number,
  to: number,
): HighlightRect[] {
  const rects: HighlightRect[] = [];
  let runIndex = -1;
  let start = 0;
  let end = 0;

  const flush = () => {
    if (runIndex < 0) {
      return;
    }
    const run = runs[runIndex];
    rects.push(rectFor(run, start, end));
    runIndex = -1;
  };

  for (let index = from; index < to; index++) {
    const spot = spots[index];
    if (spot === undefined) {
      continue;
    }
    // A space we skipped leaves a gap in the offsets. It is still the same line, so it stays one box.
    if (spot.run !== runIndex) {
      flush();
      runIndex = spot.run;
      start = spot.offset;
    }
    end = spot.offset + 1;
  }
  flush();
  return rects;
}

function rectFor(run: PdfTextRun, from: number, to: number): HighlightRect {
  const chars = Math.max(run.str.length, 1);
  const font = run.height > 0 ? run.height : 12;
  return {
    x: run.x + run.width * (from / chars),
    y: run.y - font * 0.2,
    width: run.width * ((to - from) / chars),
    height: font,
  };
}
