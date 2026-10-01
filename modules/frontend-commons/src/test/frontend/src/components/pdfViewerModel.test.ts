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
import {
  createViewerState,
  getNote,
  reduceViewerState,
  type ViewerAction,
  type ViewerState,
} from "@iap/frontend-commons/components/pdfViewerModel";

import type { PDFDocumentProxy } from "pdfjs-dist";

const DOC = { numPages: 3 } as unknown as PDFDocumentProxy;
const BOX = { left: 10, top: 20, width: 30, height: 12 };

const OPENED: ViewerAction = { type: "opened", doc: DOC, page: 2 };

function apply(...actions: ViewerAction[]): ViewerState {
  return actions.reduce(reduceViewerState, createViewerState(true));
}

function found(page: number): ViewerAction {
  return { type: "searched", search: { status: "found", page } };
}

function notFound(incomplete: boolean): ViewerAction {
  return { type: "searched", search: { status: "notFound", incomplete } };
}

function paged(page: number): ViewerAction {
  return { type: "paged", page };
}

function drawn(page: number, marked: boolean, textFailed = false): ViewerAction {
  return { type: "drawn", page, boxes: marked ? [ BOX ] : [], width: 600, height: 800, textFailed };
}

function drawFailed(page: number): ViewerAction {
  return { type: "drawFailed", page };
}

describe("reduceViewerState", () => {
  it("follows the search to the page the quote is on", () => {
    expect(apply(OPENED, found(3)).page).toBe(3);
  });

  it("stops following the search once the person pages by hand", () => {
    const state = apply(OPENED, paged(1), found(3));

    expect(state.page).toBe(1);
    expect(state.search).toEqual({ status: "found", page: 3 });
  });

  it("keeps what a drawing of the page shown found", () => {
    const state = apply(OPENED, drawn(2, true));

    expect(state.boxes).toEqual([ BOX ]);
    expect(state.size).toEqual({ width: 600, height: 800 });
  });

  it("drops a drawing that lands after the page changed", () => {
    expect(apply(OPENED, found(3), drawn(2, true)).boxes).toEqual([]);
    expect(apply(OPENED, found(3), drawFailed(2)).failed).toBeUndefined();
  });

  it("forgets a page's failure when another page is shown", () => {
    expect(apply(OPENED, drawFailed(2), paged(3)).failed).toBeUndefined();
    expect(apply(OPENED, drawn(2, false, true), found(3)).failed).toBeUndefined();
  });

  it("clears a page's failure once it is drawn", () => {
    expect(apply(OPENED, drawFailed(2), drawn(2, true)).failed).toBeUndefined();
  });

  it("clears the open error and counts the attempt on retry", () => {
    const state = apply({ type: "openFailed", message: "gone" }, { type: "retry" });

    expect(state.openError).toBeUndefined();
    expect(state.attempt).toBe(1);
  });
});

describe("getNote", () => {
  it.each<[string, ViewerAction[], string | undefined]>([
    [ "the document is opening", [], "Opening the document…" ],
    [ "the open failed", [ { type: "openFailed", message: "gone" } ], undefined ],
    [ "the search is running", [ OPENED ], "Looking for the passage…" ],
    [ "the quote is marked", [ OPENED, found(2), drawn(2, true) ], "The passage is marked on this page." ],
    [ "the quote has been found but not drawn yet", [ OPENED, found(2) ], undefined ],
    [
      "the quote is on another page",
      [ OPENED, paged(1), found(3) ],
      "The passage is not on this page. It is on page 3.",
    ],
    [ "the quote is not in the text", [ OPENED, notFound(false) ], "The passage is not in the PDF text, so nothing is marked." ],
    [
      "some pages could not be read",
      [ OPENED, notFound(true) ],
      "The passage was not found, but some pages could not be read.",
    ],
    [
      "the page's text could not be read",
      [ OPENED, drawn(2, false, true) ],
      "The text of this page could not be read, so nothing is marked.",
    ],
    [
      "the page's text could not be read and the quote is elsewhere",
      [ OPENED, paged(1), found(3), drawn(1, false, true) ],
      "The text of this page could not be read, so nothing is marked. The passage is on page 3.",
    ],
    [ "the page could not be drawn", [ OPENED, drawFailed(2) ], "This page could not be drawn." ],
    [
      "the page could not be drawn and the quote is elsewhere",
      [ OPENED, paged(1), found(3), drawFailed(1) ],
      "This page could not be drawn. The passage is on page 3.",
    ],
  ])("says the right thing when %s", (_, actions, note) => {
    expect(getNote(apply(...actions))).toBe(note);
  });

  it("says when the quote is too short to look for", () => {
    const state = reduceViewerState(createViewerState(false), OPENED);

    expect(getNote(state)).toBe("The passage is too short to find reliably, so nothing is marked.");
  });
});
