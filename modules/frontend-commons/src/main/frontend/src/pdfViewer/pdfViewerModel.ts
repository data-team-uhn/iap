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

// What the passage's note depends on, besides where the passage is: why the document could not be
// opened, if it could not, and the page shown once it is.
export interface ViewerStatus {
  openError?: string;
  page?: number;
}

// Why the document could not be opened, in the person's words. PDF.js names its own failures.
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

// The pages a passage is on, from the one it starts on to the one it ends on, if another.
export function describePages(page: number, toPage?: number): string {
  if (toPage === undefined) {
    return `page ${page}`;
  }
  return toPage === page + 1 ? `pages ${page} and ${toPage}` : `pages ${page} to ${toPage}`;
}

// What follows the passage: how the search for it went, in full and in short for when the passage is
// cut to one line, and whether it failed, or where it was found, with whether one of its pages is the
// one shown. Nothing when the open failed, since the error box says it.
export type PassageNote =
  | { text: string; short: string; failed?: boolean }
  | { foundOnPage: number; toPage?: number; shown: boolean };

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
// naming the passage by its place among several when there are. Nothing when the open failed, since the
// error box says it.
export function getPassageStatus(
  { openError, page }: ViewerStatus,
  place: MarkPlace,
  among?: { index: number; count: number },
): string {
  if (openError !== undefined) {
    return "";
  }
  if (page === undefined) {
    return "Opening the document…";
  }
  const passage = among === undefined ? "The passage" : `Passage ${among.index} of ${among.count}`;
  switch (place.status) {
    case "searching":
      return among === undefined ? "Looking for the passage…" : `Looking for passage ${among.index} of ${among.count}…`;
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

// A search of the document for a word or phrase: what is looked for; how the last search to finish
// went, which stays shown while the next one runs; which match is shown of how many found so far, a count
// that grows as PDF.js reads the pages; and the sentence a screen reader was last given about it.
export interface DocumentSearch {
  query: string;
  outcome: "none" | "found" | "notFound" | "noText";
  current: number;
  total: number;
  announcement: string;
}

export const IDLE_SEARCH: DocumentSearch = { query: "", outcome: "none", current: 0, total: 0, announcement: "" };

// How a search went, as a sentence of its own for a screen reader. Nothing before it has gone anywhere.
export function getSearchStatus({ outcome, current, total }: Omit<DocumentSearch, "query" | "announcement">): string {
  switch (outcome) {
    case "none":
      return "";
    case "found":
      return `Match ${current} of ${total}.`;
    case "notFound":
      return "No matches in this document.";
    case "noText":
      return "This document has no text to search.";
  }
}
