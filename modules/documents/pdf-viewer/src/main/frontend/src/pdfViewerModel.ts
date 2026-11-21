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
// The words the PDF viewer shows, and where its Comment button goes: names for its marks, the note on each
// passage, and what a screen reader announces. It is plain data, with no React and no requests.

import { PdfWorkerFailure } from "@iap/frontend-commons/pdfjsClient";
import { lastSegmentOf } from "@iap/frontend-commons/serializedNode";
import { capitalize, listed } from "@iap/frontend-commons/words";

import type { MarkPlace } from "./marksModel";

// What a passage's note depends on besides the passage's place.
export interface ViewerStatus {
  // Why the document could not be opened, when it could not.
  openError?: string;
  // The page shown, once the document is open.
  page?: number;
}

// Why PDF.js could not open the document, as a sentence for the person. It covers three failures: a worker
// that never started, a file that is not a readable PDF, and a PDF behind a password. Undefined for any
// other. For the worker, it asks for a reload, since a retry would reuse the same worker.
export function describePdfFailure(error: unknown): string | undefined {
  if (error instanceof PdfWorkerFailure) {
    return "The document viewer could not start. Reload the page to try again.";
  }
  const name = error instanceof Error ? error.name : undefined;
  if (name === "InvalidPDFException") {
    return "This file is not a PDF that can be read. It may be damaged, or be another kind of file.";
  }
  if (name === "PasswordException") {
    return "This PDF is protected by a password, so it cannot be shown here.";
  }
  return undefined;
}

// The file name at the end of a URL's path, decoded, without the query, the fragment or a trailing slash.
// A name that does not decode is kept as it is. When the path has no name, it is the whole URL.
export function getFileName(url: string): string {
  const path = url.replace(/[?#].*$/, "").replace(/\/+$/, "");
  const name = lastSegmentOf(path);
  if (name.length === 0) {
    return url;
  }
  try {
    return decodeURIComponent(name);
  } catch {
    return name;
  }
}

// What marks are called, for one and for several, in lower case so it fits in the middle of a sentence.
export interface MarkName {
  one: string;
  many: string;
}

export const PASSAGES: MarkName = { one: "passage", many: "passages" };

export const CHANGES: MarkName = { one: "change", many: "changes" };

// What `count` marks are called: the singular name for one, and the plural otherwise.
export function getNameFor(name: MarkName, count: number): string {
  return count === 1 ? name.one : name.many;
}

// The direction of a step through a list, such as a search's matches or the marks listed.
export type StepDirection = "previous" | "next";

// Names a mark by its position among several, as in "Passage 3 of 12".
export function describeAmong(name: MarkName, { index, count }: { index: number; count: number }): string {
  return `${capitalize(name.one)} ${index} of ${count}`;
}

// What a mark is called, its position among the marks of its sort counting from 1, and how many of that
// sort there are.
export interface MarkNaming {
  name: MarkName;
  index: number;
  count: number;
}

// Names each mark by its sort, such as passage or change, and numbers it among the marks of that sort in
// the order given. Marks are of one sort when `nameOf` gives them the same `MarkName` object. The result
// is keyed by mark id.
export function nameMarks<T extends { id: string }>(
  marks: T[],
  nameOf: (mark: T) => MarkName,
): Map<string, MarkNaming> {
  const counts = new Map<MarkName, number>();
  const named = marks.map(mark => {
    const name = nameOf(mark);
    const index = (counts.get(name) ?? 0) + 1;
    counts.set(name, index);
    return [ mark.id, name, index ] as const;
  });
  return new Map(named.map(([ id, name, index ]) => [ id, { name, index, count: counts.get(name) ?? index } ]));
}

// A name for a list of the marks, from their sorts in the order they first appear, as in "Passages and
// changes".
export function describeSorts(namings: Iterable<MarkNaming>): string {
  return capitalize(listed([ ...new Set(Array.from(namings, naming => naming.name.many)) ]));
}

// The names of the marks before and after the one at `position`, counting from 1, for the buttons that
// step to them. Past either end, it is the nearest mark's name, and `fallback` when there are no marks.
export function getStepNames(
  marks: { id: string }[],
  naming: Map<string, MarkNaming>,
  position: number,
  fallback: MarkName,
): { previous: string; next: string } {
  const getNameAt = (at: number) => naming.get(marks[Math.min(Math.max(at, 0), marks.length - 1)]?.id ?? "")?.name;
  return { previous: (getNameAt(position - 2) ?? fallback).one, next: (getNameAt(position) ?? fallback).one };
}

// How many marks of each sort there are, as in "5 comments" or "3 notes and 2 changes".
export function countMarks(namings: Iterable<MarkNaming>): string {
  const counts = new Map(Array.from(namings, naming => [ naming.name, naming.count ] as const));
  return listed(Array.from(counts, ([ name, count ]) => `${count} ${getNameFor(name, count)}`));
}

// The heading of the last mark of each sort, as in "Passage 12 of 12": the widest heading that sort can have.
export function getWidestHeadings(namings: Iterable<MarkNaming>): string[] {
  const last = new Map(Array.from(namings, naming => [ naming.name, naming.count ] as const));
  return Array.from(last, ([ name, count ]) => describeAmong(name, { index: count, count }));
}

// The page a passage is on, or the pages it runs over, as in "page 3", "pages 3 and 4" or "pages 3 to 5".
export function describePages(page: number, toPage?: number): string {
  if (toPage === undefined) {
    return `page ${page}`;
  }
  return toPage === page + 1 ? `pages ${page} and ${toPage}` : `pages ${page} to ${toPage}`;
}

// The note shown after a passage. Until the passage is found, the note is text saying how the search
// went, in full and in short for a passage cut to one line. `failed` marks a passage not in the document.
// Once the passage is found, the note gives its pages and whether the page shown is one of them. It also
// says whether the passage was searched for, or placed where it was known to be.
export type PassageNote =
  | { text: string; short: string; failed?: boolean }
  | { foundOnPage: number; toPage?: number; shown: boolean; searched: boolean };

// The note on a passage at `place`. `searched` is false for a mark placed where it was known to be.
// Undefined when the document could not be opened, since the error box says so.
export function getPassageNote(
  { openError, page }: ViewerStatus,
  place: MarkPlace,
  searched = true,
): PassageNote | undefined {
  if (openError !== undefined) {
    return undefined;
  }
  if (page === undefined) {
    return { text: "opening the document…", short: "…" };
  }
  switch (place.status) {
    case "searching":
      return { text: "looking for it…", short: "…" };
    case "tooShort":
      return { text: "too short to look for", short: "too short" };
    case "notFound":
      return { text: "not found in this document", short: "not found", failed: true };
    case "noText":
      return { text: "this document has no text to search", short: "no text" };
    case "found": {
      const { page: from, toPage } = place;
      return { foundOnPage: from, toPage, shown: page >= from && page <= (toPage ?? from), searched };
    }
  }
}

// The passage's note as a sentence for a screen reader to announce on its own. With `among`, it names the
// passage by its position, as in "Passage 3 of 12". `name` is what the marks are called. Empty when the
// document could not be opened.
export function getPassageStatus(
  { openError, page }: ViewerStatus,
  place: MarkPlace,
  among?: { index: number; count: number },
  name = PASSAGES,
): string {
  if (openError !== undefined) {
    return "";
  }
  if (page === undefined) {
    return "Opening the document…";
  }
  const passage = among === undefined ? `The ${name.one}` : describeAmong(name, among);
  switch (place.status) {
    case "searching":
      return among === undefined
        ? `Looking for the ${name.one}…`
        : `Looking for ${name.one} ${among.index} of ${among.count}…`;
    case "tooShort":
      return `${passage} is too short to look for.`;
    case "notFound":
      return `${passage} is not in this document.`;
    case "noText":
      return "This document has no text to search.";
    case "found":
      return `${passage} is marked on ${describePages(place.page, place.toPage)}.`;
  }
}

// A search of the document for a word or phrase, and how it went.
export interface DocumentSearch {
  query: string;
  // How the last search to finish went. It stays shown while the next one runs.
  outcome: "none" | "found" | "notFound" | "noText";
  // The match shown, counting from 1, and how many are found so far. The total grows as PDF.js reads the
  // pages.
  current: number;
  total: number;
  // Whether PDF.js is still reading the pages for matches.
  counting: boolean;
  // The sentence a screen reader was last given about the search.
  announcement: string;
}

// The state of the search before anything is searched for.
export const IDLE_SEARCH: DocumentSearch = {
  query: "",
  outcome: "none",
  current: 0,
  total: 0,
  counting: false,
  announcement: "",
};

// How a search went, as a sentence for a screen reader to announce. Empty when there is no search, or
// while the match shown has not been counted yet.
export function getSearchStatus({ outcome, current, total }: Pick<DocumentSearch, "outcome" | "current" | "total">): string {
  switch (outcome) {
    case "none":
      return "";
    case "found":
      return current > 0 ? `Match ${current} of ${total}.` : "";
    case "notFound":
      return "No matches in this document.";
    case "noText":
      return "This document has no text to search.";
  }
}

// The Comment button's height in pixels, the same on every screen.
export const COMMENT_BUTTON_HEIGHT = 40;
// How close the button comes to the frame's edges.
const EDGE = 8;
// How far below the selection the button sits.
const GAP = 4;
// How far below a selection made by touch the button sits, clear of the selection handles.
const UNDER_HANDLES = 28;

// Where the Comment button goes, in the frame's coordinates. Only one of `left` and `right` is set.
export interface CommentButtonPlace {
  top: number;
  left?: number;
  right?: number;
}

// Places the Comment button under the end of the selection, inside the frame. The button runs towards the
// side with more room, so its width need not be known.
export function getCommentButtonPlace(
  end: { x: number; bottom: number },
  frame: { width: number; height: number },
  // The pointer the selection was made with, as a PointerEvent gives it.
  pointerType: string,
): CommentButtonPlace {
  const below = end.bottom + (pointerType === "touch" ? UNDER_HANDLES : GAP);
  const top = Math.max(EDGE, Math.min(below, frame.height - COMMENT_BUTTON_HEIGHT - EDGE));
  return end.x <= frame.width / 2
    ? { top, left: Math.max(EDGE, end.x) }
    : { top, right: Math.max(EDGE, frame.width - end.x) };
}
