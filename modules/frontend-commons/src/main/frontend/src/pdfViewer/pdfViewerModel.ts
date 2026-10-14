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

import { isNotAuthenticated, SIGNED_OUT_MESSAGE } from "../reLogin";
import { describeRequestFailure } from "../requestFailure";

import type { MarkPlace } from "./marksModel";

// What a passage's note depends on besides the passage's place: why the document could not be opened,
// and the page shown once it is open.
export interface ViewerStatus {
  openError?: string;
  page?: number;
}

// Why the document could not be opened, in the person's words.
export function describeOpenFailure(error: unknown): string {
  if (isNotAuthenticated(error)) {
    return SIGNED_OUT_MESSAGE;
  }
  const name = error instanceof Error ? error.name : undefined;
  if (name === "InvalidPDFException") {
    return "This file is not a PDF that can be read. It may be damaged, or be another kind of file.";
  }
  if (name === "PasswordException") {
    return "This PDF is protected by a password, so it cannot be shown here.";
  }
  return describeRequestFailure(error);
}

// The last segment of the URL's path, decoded, or the URL itself when it has none.
export function getFileName(url: string): string {
  const path = url.replace(/[?#].*$/, "").replace(/\/+$/, "");
  const name = path.slice(path.lastIndexOf("/") + 1);
  if (name.length === 0) {
    return url;
  }
  try {
    return decodeURIComponent(name);
  } catch {
    return name;
  }
}

// What the marks are called where the viewer counts them and steps through them, as one and as several, in
// lower case, as they go in the middle of a sentence too.
export interface MarkName {
  one: string;
  many: string;
}

export const PASSAGES: MarkName = { one: "passage", many: "passages" };

// A word as it starts a sentence or a label.
export function capitalize(word: string): string {
  return word.charAt(0).toUpperCase() + word.slice(1);
}

// A mark by its place among several, as in "Passage 3 of 12".
export function describeAmong(name: MarkName, { index, count }: { index: number; count: number }): string {
  return `${capitalize(name.one)} ${index} of ${count}`;
}

// The pages a passage is on, from the one it starts on to the one it ends on, if another.
export function describePages(page: number, toPage?: number): string {
  if (toPage === undefined) {
    return `page ${page}`;
  }
  return toPage === page + 1 ? `pages ${page} and ${toPage}` : `pages ${page} to ${toPage}`;
}

// The note shown after the passage. Until the passage is found, it says how the search went, in full
// and in short for a passage cut to one line. Once found, it gives the pages, and whether one of them
// is the page shown.
export type PassageNote =
  | { text: string; short: string; failed?: boolean }
  | { foundOnPage: number; toPage?: number; shown: boolean };

// Nothing when the open failed, since the error box says it.
export function getPassageNote({ openError, page }: ViewerStatus, place: MarkPlace): PassageNote | undefined {
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
      return { foundOnPage: from, toPage, shown: page >= from && page <= (toPage ?? from) };
    }
  }
}

// The same, as a sentence of its own, for a screen reader, which announces it apart from the passage,
// naming the passage by its place among several when there are, by what the marks are called. Nothing when
// the open failed, since the error box says it.
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
  // Which match is shown, of how many found so far. The count grows as PDF.js reads the pages.
  current: number;
  total: number;
  // Whether PDF.js is still reading the pages.
  counting: boolean;
  // The sentence a screen reader was last given about the search.
  announcement: string;
}

export const IDLE_SEARCH: DocumentSearch = {
  query: "",
  outcome: "none",
  current: 0,
  total: 0,
  counting: false,
  announcement: "",
};

// How a search went, as a sentence for a screen reader to announce. Empty when there is no search, or
// before the match found has been counted.
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

// The Comment button's height, the same on every screen, and its distances from the frame's edges and
// from the selection. It sits further below a selection made by touch, clear of the phone's handles.
export const COMMENT_BUTTON_HEIGHT = 40;
const EDGE = 8;
const GAP = 4;
const UNDER_HANDLES = 28;

// Where the Comment button goes, in the frame's coordinates: under the end of the selection, inside the
// frame. It runs towards the side with more room, so its width need not be known.
export interface CommentButtonPlace {
  top: number;
  left?: number;
  right?: number;
}

export function getCommentButtonPlace(
  end: { x: number; bottom: number },
  frame: { width: number; height: number },
  byTouch: boolean,
): CommentButtonPlace {
  const below = end.bottom + (byTouch ? UNDER_HANDLES : GAP);
  const top = Math.max(EDGE, Math.min(below, frame.height - COMMENT_BUTTON_HEIGHT - EDGE));
  return end.x <= frame.width / 2
    ? { top, left: Math.max(EDGE, end.x) }
    : { top, right: Math.max(EDGE, frame.width - end.x) };
}
