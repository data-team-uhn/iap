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
import { getPassageText, type MarkSpan, type PageText, type TextAnchor, type TextPoint } from "./marksModel";

// How much of the text before and after a passage its anchor keeps, in characters as the text reads.
const CONTEXT_LENGTH = 32;

// The anchor of a passage, from the spans it covers, in order, and the text of their pages.
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
    before: before.slice(-CONTEXT_LENGTH).trimStart(),
    after: after.slice(0, CONTEXT_LENGTH).trimEnd(),
  };
}

// An anchor as text, to be kept with what it anchors, such as a comment. Its start and end are each
// written as in PDF.js's open parameters and RFC 5147's text fragments ("page=3&char=120"). Its text is
// what it says with the text around it, written as a text fragment directive without its "text="
// ("before-,quote,-after").
export interface EncodedAnchor {
  start: string;
  end: string;
  text: string;
}

// A text fragment directive's parts are percent-encoded, the dash too, since it marks the text before
// and after the quote.
function encodePart(text: string): string {
  return encodeURIComponent(text).replaceAll("-", "%2D");
}

function encodePoint({ page, char }: TextPoint): string {
  return `page=${page}&char=${char}`;
}

export function encodeAnchor({ start, end, quote, before, after }: TextAnchor): EncodedAnchor {
  const parts = [
    ...before === "" ? [] : [ `${encodePart(before)}-` ],
    encodePart(quote),
    ...after === "" ? [] : [ `-${encodePart(after)}` ],
  ];
  return { start: encodePoint(start), end: encodePoint(end), text: parts.join(",") };
}

function decodePoint(stored: string): TextPoint | undefined {
  const params = new URLSearchParams(stored);
  const page = Number(params.get("page") ?? Number.NaN);
  const char = Number(params.get("char") ?? Number.NaN);
  return Number.isInteger(page) && Number.isInteger(char) && page >= 1 && char >= 0 ? { page, char } : undefined;
}

function decodeText(stored: string): Pick<TextAnchor, "quote" | "before" | "after"> | undefined {
  const parts = stored.split(",");
  const last = parts[parts.length - 1];
  const hasBefore = parts.length > 1 && parts[0].endsWith("-");
  const hasAfter = parts.length > (hasBefore ? 2 : 1) && last.startsWith("-");
  const quote = parts.slice(hasBefore ? 1 : 0, hasAfter ? -1 : undefined);
  // A dash not percent-encoded only ever marks the text before or after.
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

// The anchor kept as text, or none when none was kept, or it cannot be read.
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
