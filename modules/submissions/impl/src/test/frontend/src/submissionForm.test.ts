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
  QUESTION,
  SECTION,
  type FormItem,
  type FormQuestion,
  fetchForm,
  formatDate,
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
    json: () => Promise.resolve(body),
  } as unknown as Response);
}

describe("isQuestion", () => {
  it("tells a question apart from a section", () => {
    expect(isQuestion({ type: QUESTION } as FormItem)).toBe(true);
    expect(isQuestion({ type: SECTION } as FormItem)).toBe(false);
  });
});

describe("the answer-count pair", () => {
  const counts = (minAnswers: number, maxAnswers: number) =>
    ({ minAnswers, maxAnswers } as FormQuestion);

  // The same readings the server derives, so the two sides cannot disagree about what a count means
  it("reads a positive minimum as required", () => {
    expect(isRequired(counts(1, 1))).toBe(true);
    expect(isRequired(counts(0, 1))).toBe(false);
  });

  it("reads any maximum but one as taking several values", () => {
    expect(isMultiple(counts(0, 1))).toBe(false);
    expect(isMultiple(counts(0, 4))).toBe(true);
    // Zero or negative means no cap at all
    expect(isMultiple(counts(0, 0))).toBe(true);
  });
});

describe("fetchForm", () => {
  it("reads the form projection of a submission", async () => {
    const form = { path: PATH, title: "A long weekend", editable: true, requirements: [] };
    const fetchMock = vi.fn(() => response(form));

    expect(await fetchForm(fetchMock, PATH)).toEqual(form);
    // The projection, not the node: it merges the schema's questions with this submission's answers
    // and leaves out whatever does not currently apply
    expect(fetchMock).toHaveBeenCalledWith(`${PATH}.form.json`);
  });

  it("reports a form that would not load", async () => {
    const fetchMock = vi.fn(() => response({}, { ok: false, status: 404 }));

    await expect(fetchForm(fetchMock, PATH)).rejects.toThrow("HTTP 404");
  });

  it("refuses an HTML page that arrived where the form should be", async () => {
    const fetchMock = vi.fn(() => Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.reject(new SyntaxError("Unexpected token '<'")),
    } as unknown as Response));

    await expect(fetchForm(fetchMock, PATH)).rejects.toMatchObject({ name: "UnreadableResponseError", status: 200 });
  });
});

describe("reviewExtraction", () => {
  it("posts a confirmation as an event on the submission", async () => {
    const fetchMock = vi.fn(() => response({}));

    await reviewExtraction(fetchMock, PATH, "details/startDate", { confirmed: true });

    const [ url, options ] = fetchMock.mock.calls[0] as unknown as
      [ string, { method: string; body: URLSearchParams } ];
    expect(url).toBe(`${PATH}.reviewExtraction.json`);
    expect(options.method).toBe("POST");
    expect(options.body.get("question")).toBe("details/startDate");
    expect(options.body.get("confirmed")).toBe("true");
    expect(options.body.has("evidenceRejected")).toBe(false);
  });

  // The two verdicts mean different things, so a report about the quote must not settle the answer
  it("posts a rejected passage without saying anything about the answer", async () => {
    const fetchMock = vi.fn(() => response({}));

    await reviewExtraction(fetchMock, PATH, "details/startDate", { evidenceRejected: true });

    const [ , options ] = fetchMock.mock.calls[0] as unknown as [ string, { body: URLSearchParams } ];
    expect(options.body.get("evidenceRejected")).toBe("true");
    expect(options.body.has("confirmed")).toBe(false);
  });

  it("posts taking that back", async () => {
    const fetchMock = vi.fn(() => response({}));

    await reviewExtraction(fetchMock, PATH, "details/startDate", { evidenceRejected: false });

    const [ , options ] = fetchMock.mock.calls[0] as unknown as [ string, { body: URLSearchParams } ];
    expect(options.body.get("evidenceRejected")).toBe("false");
  });

  it("reports what the server refused with", async () => {
    const fetchMock = vi.fn(() => response({ error: "Nothing was extracted for that" },
      { ok: false, status: 400 }));

    await expect(reviewExtraction(fetchMock, PATH, "details/startDate", { confirmed: true }))
      .rejects.toThrow("Nothing was extracted for that");
  });

  it("falls back to the status when the refusal says nothing", async () => {
    const fetchMock = vi.fn(() => response({}, { ok: false, status: 500 }));

    await expect(reviewExtraction(fetchMock, PATH, "details/startDate", { confirmed: true }))
      .rejects.toThrow("(500)");
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
    // Which is what the handler reads back as a multi-valued answer
    const fetchMock = vi.fn(() => response({}));

    await saveAnswer(fetchMock, PATH, "details/days", [ "Monday", "Tuesday" ]);

    const [ , options ] = fetchMock.mock.calls[0] as unknown as [ string, { body: URLSearchParams } ];
    expect(options.body.getAll("details/days")).toEqual([ "Monday", "Tuesday" ]);
  });

  it("names the question even when it is being cleared", async () => {
    // The handler walks the questions the payload mentions, so an emptied field that sent nothing at
    // all would leave its old answer in place -- and the request would go on counting as complete
    const fetchMock = vi.fn(() => response({}));

    await saveAnswer(fetchMock, PATH, "details/startDate", []);

    const [ , options ] = fetchMock.mock.calls[0] as unknown as [ string, { body: URLSearchParams } ];
    expect(options.body.getAll("details/startDate")).toEqual([ "" ]);
  });

  it("reports the engine's own reason for refusing", async () => {
    // A refusal carries why: not the submitter's request, or no longer a draft. Repeating that
    // verbatim beats inventing a message over the top of it
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

describe("asking the reading to stop or to run again", () => {
  it("stops a reading, and says why it could not", async () => {
    const doFetch = vi.fn()
      .mockReturnValueOnce(response({}))
      .mockReturnValueOnce(response({ error: "Nothing is being read" }, { ok: false, status: 409 }))
      .mockReturnValueOnce(Promise.resolve(
        { ok: false, status: 500, json: () => Promise.reject(new Error("html")) } as unknown as Response));

    await stopProcessing(doFetch, PATH);
    expect(doFetch).toHaveBeenCalledWith(`${PATH}.stopProcessing.json`, { method: "POST" });
    await expect(stopProcessing(doFetch, PATH)).rejects.toThrow("Nothing is being read");
    await expect(stopProcessing(doFetch, PATH)).rejects.toThrow("The reading could not be aborted (500)");
  });

  it("falls back on its own words when a refused retry says nothing", async () => {
    const doFetch = vi.fn(() => Promise.resolve(
      { ok: false, status: 409, json: () => Promise.reject(new Error("html")) } as unknown as Response));

    await expect(readAgain(doFetch, PATH, false)).rejects.toThrow("The document could not be read again (409)");
  });
});

describe("formatDate", () => {
  it("formats nothing that is not a date", () => {
    expect(formatDate(undefined)).toBe("");
    expect(formatDate("")).toBe("");
    expect(formatDate("2026-10-01T10:00:00.000Z")).not.toBe("");
  });
});
