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

// Shorter than this, a quote matches too easily to be worth a mark.
const MIN_QUOTE_LENGTH = 6;

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
export function findQuoteRects(runs: readonly PdfTextRun[], quote: string): HighlightRect[] {
  const wanted = normalizeQuote(quote);
  if (wanted.length < MIN_QUOTE_LENGTH) {
    return [];
  }
  const page = readPage(runs);
  const at = page.text.indexOf(wanted);
  if (at < 0) {
    return [];
  }
  return rectsFor(runs, page.spots, at, at + wanted.length);
}

function normalizeQuote(quote: string): string {
  return readPage([ { str: quote, x: 0, y: 0, width: 0, height: 0, hasEOL: false } ]).text;
}

// One string for the page, and for each of its characters the run it came from.
//
// A hyphen at the end of a line is the word broken across the line, not a hyphen in the word, so it
// is dropped and the next line continues it. Any other line break becomes a space. PDF.js already
// puts spaces inside a run, and collapsing whitespace afterwards makes a double space harmless.
function readPage(runs: readonly PdfTextRun[]): { text: string; spots: (Spot | undefined)[] } {
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
    text += ch.toLowerCase();
    spots.push(spot);
  };

  runs.forEach((run, runIndex) => {
    const hyphenBreak = run.hasEOL && run.str.endsWith("-");
    const end = hyphenBreak ? run.str.length - 1 : run.str.length;
    for (let index = 0; index < end; index++) {
      const ch = run.str[index] ?? "";
      if (ch === "\u00ad") {
        continue;
      }
      if (/\s/.test(ch)) {
        pushSpace();
      } else if (ch.length > 0) {
        pushChar(ch, { run: runIndex, offset: index });
      }
    }
    if (run.hasEOL && !hyphenBreak) {
      pushSpace();
    }
  });
  return { text, spots };
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
