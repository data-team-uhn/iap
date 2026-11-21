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
// Anchors that let a passage of a PDF be found again: making one from a passage's spans, and turning it into
// text and back for saving.

import { getPassageText, type MarkSpan, type PageText, type TextAnchor, type TextPoint } from "./marksModel";

// How many characters of the text before and after a passage its anchor keeps, counted as
// `getPassageText` reads the page.
const CONTEXT_LENGTH = 32;

// The anchor of a passage, from the spans it covers in page order and a way to get each page's text.
// `spans` holds at least one span.
export function getTextAnchor(spans: MarkSpan[], getPage: (page: number) => PageText): TextAnchor {
  const first = spans[0];
  const last = spans[spans.length - 1];
  const firstPage = getPage(first.page);
  const lastPage = getPage(last.page);
  const quote = spans
    .map(({ page, start, end }) => getPassageText(getPage(page), start, end))
    .filter(part => part !== "")
    .join(" ");
  const before = getPassageText(firstPage, Math.max(0, first.start - 2 * CONTEXT_LENGTH), first.start);
  const after = getPassageText(lastPage, last.end, last.end + 2 * CONTEXT_LENGTH);
  return {
    start: { page: first.page, char: first.start },
    end: { page: last.page, char: last.end },
    quote,
    before: Array.from(before).slice(-CONTEXT_LENGTH).join("").trimStart(),
    after: Array.from(after).slice(0, CONTEXT_LENGTH).join("").trimEnd(),
  };
}

// An anchor as text, to be saved with what it anchors, such as a comment.
export interface EncodedAnchor {
  // Where the passage starts and ends, as a page in the form of PDF.js's open parameters and a character
  // offset in the form of RFC 5147's text fragments: "page=3&char=120".
  start: string;
  end: string;
  // The passage and the text around it, as a text fragment directive without its "text=":
  // "before-,quote,-after".
  text: string;
}

// Percent-encodes a part of a text fragment directive. The dash is encoded too, since a bare dash marks
// the text before or after the quote.
function encodePart(text: string): string {
  return encodeURIComponent(text).replaceAll("-", "%2D");
}

function encodePoint({ page, char }: TextPoint): string {
  return `page=${page}&char=${char}`;
}

// Writes an anchor as text. Empty text before or after the passage is left out of the directive.
export function encodeAnchor({ start, end, quote, before, after }: TextAnchor): EncodedAnchor {
  const parts = [
    ...before === "" ? [] : [ `${encodePart(before)}-` ],
    encodePart(quote),
    ...after === "" ? [] : [ `-${encodePart(after)}` ],
  ];
  return { start: encodePoint(start), end: encodePoint(end), text: parts.join(",") };
}

// A point read back from "page=3&char=120". Undefined unless the page is a whole number of at least 1 and
// the offset a whole number of at least 0.
function decodePoint(stored: string): TextPoint | undefined {
  const params = new URLSearchParams(stored);
  const [ page, char ] = [ params.get("page"), params.get("char") ].map(value => (
    value !== null && /^\d+$/.test(value) ? Number(value) : Number.NaN
  ));
  return page >= 1 && char >= 0 ? { page, char } : undefined;
}

// The quote and the text around it, read back from a text fragment directive. Undefined unless it holds
// exactly one quote that is not empty, and every part decodes.
function decodeText(stored: string): Pick<TextAnchor, "quote" | "before" | "after"> | undefined {
  const parts = stored.split(",");
  const last = parts[parts.length - 1];
  const hasBefore = parts.length > 1 && parts[0].endsWith("-");
  const hasAfter = parts.length > (hasBefore ? 2 : 1) && last.startsWith("-");
  const quote = parts.slice(hasBefore ? 1 : 0, hasAfter ? -1 : undefined);
  // A bare dash only ever marks the text before or after the quote.
  if (quote.length !== 1 || quote[0] === "" || quote[0].includes("-")) {
    return undefined;
  }
  try {
    return {
      quote: decodeURIComponent(quote[0]),
      before: hasBefore ? decodeURIComponent(parts[0].slice(0, -1)) : "",
      after: hasAfter ? decodeURIComponent(last.slice(1)) : "",
    };
  } catch {
    return undefined;
  }
}

// Reads back an anchor written by `encodeAnchor`. Undefined when a part is missing or cannot be read, or
// when the anchor ends before it starts.
export function decodeAnchor(encoded: Partial<EncodedAnchor>): TextAnchor | undefined {
  if (encoded.start === undefined || encoded.end === undefined || encoded.text === undefined) {
    return undefined;
  }
  const start = decodePoint(encoded.start);
  const end = decodePoint(encoded.end);
  const text = decodeText(encoded.text);
  const ordered = start !== undefined && end !== undefined
    && (start.page < end.page || (start.page === end.page && start.char <= end.char));
  return ordered && text !== undefined ? { start, end, ...text } : undefined;
}
