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
import type { MarkPlace } from "@iap/frontend-commons/pdfViewer/marksModel";
import {
  describeOpenFailure,
  getFileName,
  getPassageNote,
  getPassageStatus,
} from "@iap/frontend-commons/pdfViewer/pdfViewerModel";
import { NotAuthenticatedError } from "@iap/frontend-commons/reLogin";
import { RequestError } from "@iap/frontend-commons/requestFailure";

describe("describeOpenFailure", () => {
  function pdfjsError(name: string, message: string): Error {
    const error = new Error(message);
    error.name = name;
    return error;
  }

  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("asks the person to sign in again when the session has ended", () => {
    expect(describeOpenFailure(new NotAuthenticatedError("gone"))).toBe(
      "You are no longer signed in. Sign in and try again.",
    );
  });

  it("says when the file is not a PDF that can be read", () => {
    expect(describeOpenFailure(pdfjsError("InvalidPDFException", "Invalid PDF structure."))).toBe(
      "This file is not a PDF that can be read. It may be damaged, or be another kind of file.",
    );
  });

  it("says when the PDF is protected by a password", () => {
    expect(describeOpenFailure(pdfjsError("PasswordException", "No password given"))).toBe(
      "This PDF is protected by a password, so it cannot be shown here.",
    );
  });

  it("describes any other failure as a request failure", () => {
    expect(describeOpenFailure(new RequestError(404))).toMatch(/could not be found on the server/);
    expect(describeOpenFailure("odd")).toBe("Something went wrong: odd");
  });
});

describe("getFileName", () => {
  it("is the last segment of the path", () => {
    expect(getFileName("/content/pdfs/prc-memo.pdf")).toBe("prc-memo.pdf");
  });

  it("leaves out the query, the fragment and a trailing slash", () => {
    expect(getFileName("/files/memo.pdf?version=2#page=3")).toBe("memo.pdf");
    expect(getFileName("/files/memo.pdf/")).toBe("memo.pdf");
  });

  it("decodes the name", () => {
    expect(getFileName("/files/Lunch%20permission.pdf")).toBe("Lunch permission.pdf");
  });

  it("keeps a name that does not decode as it is", () => {
    expect(getFileName("/files/100%.pdf")).toBe("100%.pdf");
  });

  it("is the URL itself when the path has no name", () => {
    expect(getFileName("/")).toBe("/");
  });
});

const SEARCHING: MarkPlace = { status: "searching" };

function found(page: number, toPage?: number): MarkPlace {
  return { status: "found", page, toPage, spans: [] };
}

describe("getPassageNote", () => {
  const onPage2 = { page: 2 };

  it("says the document is opening", () => {
    expect(getPassageNote({}, SEARCHING)).toEqual({ text: "opening the document…", short: "…" });
  });

  it("says nothing when the document could not be opened", () => {
    expect(getPassageNote({ openError: "Gone." }, SEARCHING)).toBeUndefined();
  });

  it("says the passage is being looked for", () => {
    expect(getPassageNote(onPage2, SEARCHING)).toEqual({ text: "looking for it…", short: "…" });
  });

  it("says when the passage is too short to look for", () => {
    expect(getPassageNote(onPage2, { status: "tooShort" }))
      .toEqual({ text: "too short to look for", short: "too short" });
  });

  it("says when the passage is not in the document", () => {
    expect(getPassageNote(onPage2, { status: "notFound" }))
      .toEqual({ text: "not found in this document", short: "not found", failed: true });
  });

  it("says when the document has no text to search", () => {
    expect(getPassageNote(onPage2, { status: "noText" }))
      .toEqual({ text: "this document has no text to search", short: "no text" });
  });

  it("says which pages a passage broken across two was found on, and whether one is the one shown", () => {
    expect(getPassageNote(onPage2, found(2, 3))).toEqual({ foundOnPage: 2, toPage: 3, shown: true });
    expect(getPassageNote({ page: 3 }, found(2, 3)))
      .toEqual({ foundOnPage: 2, toPage: 3, shown: true });
    expect(getPassageNote({ page: 1 }, found(2, 3)))
      .toEqual({ foundOnPage: 2, toPage: 3, shown: false });
  });

  it("says which page the passage was found on, and whether it is the one shown", () => {
    expect(getPassageNote(onPage2, found(2))).toEqual({ foundOnPage: 2, shown: true });
    expect(getPassageNote({ page: 3 }, found(2)))
      .toEqual({ foundOnPage: 2, shown: false });
  });
});

describe("getPassageStatus", () => {
  it("says what is happening in sentences of its own", () => {
    const onPage2 = { page: 2 };
    expect(getPassageStatus({}, SEARCHING)).toBe("Opening the document…");
    expect(getPassageStatus(onPage2, SEARCHING)).toBe("Looking for the passage…");
    expect(getPassageStatus(onPage2, { status: "tooShort" })).toBe("The passage is too short to look for.");
    expect(getPassageStatus(onPage2, { status: "notFound" })).toBe("The passage is not in this document.");
    expect(getPassageStatus(onPage2, { status: "noText" })).toBe("This document has no text to search.");
    expect(getPassageStatus(onPage2, found(3))).toBe("The passage is marked on page 3.");
    expect(getPassageStatus(onPage2, found(2, 3))).toBe("The passage is marked on pages 2 and 3.");
  });

  it("says nothing when the document could not be opened", () => {
    expect(getPassageStatus({ openError: "Gone." }, SEARCHING)).toBe("");
  });

  it("says the document is opening even for a passage too short to look for", () => {
    expect(getPassageStatus({}, { status: "tooShort" })).toBe("Opening the document…");
  });
});
