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
  CLASSIFICATION_REQUIREMENT_TYPE,
  DOCUMENT_REQUIREMENT_TYPE,
  QUESTION,
  SECTION,
  type FormItem,
  type FormQuestion,
  answerCap,
  countGuidance,
  describeBlock,
  describeValues,
  fetchForm,
  isBeingRead,
  isFormRequirement,
  isMultiple,
  isQuestion,
  isRequired,
  readAgain,
  reviewExtraction,
  saveAnswer,
  stopProcessing,
} from "@iap/submissions/submissionForm";

const PATH = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

function response(body: unknown, init: { ok?: boolean; status?: number } = {}) {
  return Promise.resolve({
    ok: init.ok ?? true,
    status: init.status ?? 200,
    url: "",
    json: () => Promise.resolve(body),
  } as unknown as Response);
}

describe("isQuestion", () => {
  it("tells a question apart from a section", () => {
    expect(isQuestion({ type: QUESTION } as FormItem)).toBe(true);
    expect(isQuestion({ type: SECTION } as FormItem)).toBe(false);
  });
});

describe("isBeingRead", () => {
  it("holds while a reading is running, and not once it has ended any way", () => {
    expect(isBeingRead("running")).toBe(true);
    expect(isBeingRead("done")).toBe(false);
    expect(isBeingRead("failed")).toBe(false);
    expect(isBeingRead(undefined)).toBe(false);
  });
});

describe("the answer-count pair", () => {
  const counts = (minAnswers: number, maxAnswers: number) =>
    ({ minAnswers, maxAnswers } as FormQuestion);

  it("reads a positive minimum as required", () => {
    expect(isRequired(counts(1, 1))).toBe(true);
    expect(isRequired(counts(0, 1))).toBe(false);
  });

  it("reads any maximum but one as taking several values", () => {
    expect(isMultiple(counts(0, 1))).toBe(false);
    expect(isMultiple(counts(0, 4))).toBe(true);
    expect(isMultiple(counts(0, 0))).toBe(true);
  });

  it("caps an answer only where the maximum is positive", () => {
    expect(answerCap(counts(0, 3))).toBe(3);
    expect(answerCap(counts(0, 0))).toBeUndefined();
  });

  it("puts how many values are wanted into words", () => {
    expect(countGuidance(counts(2, 3), "Choose")).toBe("Choose at least 2. Choose up to 3.");
    expect(countGuidance(counts(0, 0), "Choose")).toBe("");
  });
});

describe("fetchForm", () => {
  it("reads the form projection of a submission", async () => {
    const form = { path: PATH, title: "A long weekend", editable: true, requirements: [] };
    const fetchMock = vi.fn(() => response(form));

    expect(await fetchForm(fetchMock, PATH)).toEqual(form);
    expect(fetchMock).toHaveBeenCalledWith(`${PATH}.form.json`);
  });

  it("reports a form that would not load", async () => {
    const fetchMock = vi.fn(() => response({}, { ok: false, status: 404 }));

    await expect(fetchForm(fetchMock, PATH)).rejects.toThrow(/could not be loaded \(404\)/);
  });
});

describe("saveAnswer", () => {
  it("posts the answer to the submission as its save event", async () => {
    const fetchMock = vi.fn(() => response({}));

    await saveAnswer(fetchMock, PATH, "details/startDate", [ "2026-10-06" ]);

    const [ url, options ] = fetchMock.mock.calls[0] as unknown as
      [ string, { method: string; body: URLSearchParams } ];
    expect(url).toBe(`${PATH}.save.json`);
    expect(options.method).toBe("POST");
    expect(options.body.getAll("details/startDate")).toEqual([ "2026-10-06" ]);
  });

  it("repeats a question that holds several values", async () => {
    const fetchMock = vi.fn(() => response({}));

    await saveAnswer(fetchMock, PATH, "details/days", [ "Monday", "Tuesday" ]);

    const [ , options ] = fetchMock.mock.calls[0] as unknown as [ string, { body: URLSearchParams } ];
    expect(options.body.getAll("details/days")).toEqual([ "Monday", "Tuesday" ]);
  });

  it("names the question even when it is being cleared", async () => {
    const fetchMock = vi.fn(() => response({}));

    await saveAnswer(fetchMock, PATH, "details/startDate", []);

    const [ , options ] = fetchMock.mock.calls[0] as unknown as [ string, { body: URLSearchParams } ];
    expect(options.body.getAll("details/startDate")).toEqual([ "" ]);
  });

  it("reports the engine's own reason for refusing", async () => {
    const fetchMock = vi.fn(() => response(
      { error: "This request has been submitted and can no longer be changed" },
      { ok: false, status: 409 }));

    await expect(saveAnswer(fetchMock, PATH, "details/startDate", [ "x" ]))
      .rejects.toThrow("This request has been submitted and can no longer be changed");
  });

  it("falls back to the status when a refusal carries no reason", async () => {
    const fetchMock = vi.fn(() => Promise.resolve({
      ok: false,
      status: 409,
      json: () => Promise.reject(new Error("no body")),
    } as unknown as Response));

    await expect(saveAnswer(fetchMock, PATH, "details/startDate", [ "x" ]))
      .rejects.toThrow(/could not be saved \(409\)/);
  });
});

describe("isFormRequirement", () => {
  it("counts a classification as a form, since it holds a question to answer", () => {
    expect(isFormRequirement({ name: "kind", path: "/k", type: CLASSIFICATION_REQUIREMENT_TYPE, label: "Kind" }))
      .toBe(true);
    expect(isFormRequirement({ name: "note", path: "/n", type: DOCUMENT_REQUIREMENT_TYPE, label: "Note" }))
      .toBe(false);
  });
});

describe("describeBlock", () => {
  it("says why a reading stopped the request, and nothing when it did not", () => {
    expect(describeBlock(undefined)).toBeUndefined();
    expect(describeBlock({ status: "done" })).toBeUndefined();
    expect(describeBlock({ status: "done", blocked: true, message: "Not a research proposal" }))
      .toBe("Not a research proposal");
    expect(describeBlock({ status: "done", blocked: true })).toMatch(/does not let this request go on/);
  });
});

describe("describeValues", () => {
  it("reads picked options by their labels, and anything else as it is", () => {
    const question = {
      name: "kind", type: QUESTION, path: "kind/kind", text: "Kind?", dataType: "text",
      minAnswers: 0, maxAnswers: 1, options: [ { value: "drug", label: "A drug" } ], value: [],
    } as FormQuestion;

    expect(describeValues(question, [ "drug", "other" ])).toEqual([ "A drug", "other" ]);
  });
});

describe("the reading events", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("posts each event to the submission", async () => {
    const fetchMock = vi.fn(() => response({}));
    vi.stubGlobal("fetch", fetchMock);

    await reviewExtraction(fetch, PATH, "details/sponsor", true);
    await stopProcessing(fetch, PATH);
    await readAgain(fetch, PATH, true);
    await readAgain(fetch, PATH, false);

    const urls = fetchMock.mock.calls.map(call => (call as unknown[])[0]);
    expect(urls).toEqual([ `${PATH}.reviewExtraction.json`, `${PATH}.stopProcessing.json`,
      `${PATH}.retryParse.json`, `${PATH}.readAgain.json` ]);
    const body = (fetchMock.mock.calls[0] as unknown[])[1] as { body: URLSearchParams };
    expect(body.body.get("question")).toBe("details/sponsor");
    expect(body.body.get("evidenceRejected")).toBe("true");
  });

  it("reports the server's reason for a refusal, or the status when it gives none", async () => {
    vi.stubGlobal("fetch", vi.fn(() => response({ error: "This request is already being read" },
      { ok: false, status: 409 })));
    await expect(readAgain(fetch, PATH, false)).rejects.toThrow("This request is already being read");

    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
      ok: false, status: 500, url: "", json: () => Promise.reject(new Error("not JSON")),
    } as unknown as Response)));
    await expect(stopProcessing(fetch, PATH)).rejects.toThrow("The reading could not be stopped (500)");
  });
});
