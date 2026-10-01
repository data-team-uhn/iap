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
import type { PDFDocumentProxy } from "pdfjs-dist";

// The state behind PdfViewer, kept apart from the component so it can be tested on its own.

export interface CssBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

// How far the search for the quote has got.
export type SearchState =
  | { status: "searching" }
  | { status: "tooShort" }
  | { status: "found"; page: number }
  // Some pages may have been unreadable, so the quote may still be on one of them.
  | { status: "notFound"; incomplete: boolean };

export interface ViewerState {
  doc?: PDFDocumentProxy;
  // Why the document could not be opened, in the user's words. Cleared by a retry.
  openError?: string;
  // Counts the retries, so the open effect runs again.
  attempt: number;
  page?: number;
  // What went wrong with the page shown.
  failed?: "text" | "draw";
  search: SearchState;
  // Whether the search may still move the page. Off once the person pages by hand.
  following: boolean;
  boxes: CssBox[];
  size: { width: number; height: number };
}

// A page drawn on screen, and the marks drawn over it. It names its page, so a drawing that lands
// after the page changed is dropped.
export interface DrawnPage {
  page: number;
  boxes: CssBox[];
  width: number;
  height: number;
  textFailed: boolean;
}

export type ViewerAction =
  | { type: "opened"; doc: PDFDocumentProxy; page: number }
  | { type: "openFailed"; message: string }
  | { type: "retry" }
  | { type: "searched"; search: SearchState }
  | { type: "paged"; page: number }
  | ({ type: "drawn" } & DrawnPage)
  | { type: "drawFailed"; page: number };

export function createViewerState(canSearch: boolean): ViewerState {
  return {
    attempt: 0,
    search: canSearch ? { status: "searching" } : { status: "tooShort" },
    following: true,
    boxes: [],
    size: { width: 0, height: 0 },
  };
}

export function reduceViewerState(state: ViewerState, action: ViewerAction): ViewerState {
  switch (action.type) {
    case "opened":
      return { ...state, doc: action.doc, page: action.page };
    case "openFailed":
      return { ...state, openError: action.message };
    case "retry":
      return { ...state, openError: undefined, attempt: state.attempt + 1 };
    case "searched": {
      const { search } = action;
      if (search.status !== "found" || !state.following || search.page === state.page) {
        return { ...state, search };
      }
      return { ...state, search, page: search.page, boxes: [], failed: undefined };
    }
    case "paged":
      return { ...state, page: action.page, following: false, boxes: [], failed: undefined };
    case "drawn":
      if (action.page !== state.page) {
        return state;
      }
      return {
        ...state,
        boxes: action.boxes,
        size: { width: action.width, height: action.height },
        failed: action.textFailed ? "text" : undefined,
      };
    case "drawFailed":
      return action.page === state.page ? { ...state, failed: "draw", boxes: [] } : state;
  }
}

// What the status line says. Nothing when the open failed, since the error box says it.
export function getNote(state: ViewerState): string | undefined {
  const { page, search, failed } = state;
  if (state.openError !== undefined) {
    return undefined;
  }
  if (page === undefined) {
    return "Opening the document…";
  }
  const foundElsewhere = search.status === "found" && search.page !== page;
  if (failed !== undefined) {
    const elsewhere = foundElsewhere ? ` The passage is on page ${search.page}.` : "";
    return failed === "text"
      ? `The text of this page could not be read, so nothing is marked.${elsewhere}`
      : `This page could not be drawn.${elsewhere}`;
  }
  // Said as well as shown, for someone who cannot see the page.
  if (state.boxes.length > 0) {
    return "The passage is marked on this page.";
  }
  switch (search.status) {
    case "searching":
      return "Looking for the passage…";
    case "tooShort":
      return "The passage is too short to find reliably, so nothing is marked.";
    case "notFound":
      return search.incomplete
        ? "The passage was not found, but some pages could not be read."
        : "The passage is not in the PDF text, so nothing is marked.";
    case "found":
      return foundElsewhere ? `The passage is not on this page. It is on page ${search.page}.` : undefined;
  }
}
