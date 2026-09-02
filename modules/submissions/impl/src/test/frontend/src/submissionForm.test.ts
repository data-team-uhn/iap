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
  isMultiple,
  isQuestion,
  isRequired,
  saveAnswer,
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
    // The projection reports the schema's own resource types rather than a vocabulary of its own,
    // so this is the only place the distinction is made
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
  afterEach(() => vi.unstubAllGlobals());

  it("reads the form projection of a submission", async () => {
    const form = { path: PATH, title: "A long weekend", editable: true, requirements: [] };
    const fetchMock = vi.fn(() => response(form));
    vi.stubGlobal("fetch", fetchMock);

    expect(await fetchForm(PATH)).toEqual(form);
    // The projection, not the node: it merges the schema's questions with this submission's answers
    // and leaves out whatever does not currently apply
    expect(fetchMock).toHaveBeenCalledWith(`${PATH}.form.json`);
  });

  it("reports a form that would not load", async () => {
    vi.stubGlobal("fetch", vi.fn(() => response({}, { ok: false, status: 404 })));

    await expect(fetchForm(PATH)).rejects.toThrow(/could not be loaded \(404\)/);
  });
});

describe("saveAnswer", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("posts the answer to the submission itself", async () => {
    const fetchMock = vi.fn(() => response({}));
    vi.stubGlobal("fetch", fetchMock);

    await saveAnswer(PATH, "details/startDate", [ "2026-10-06" ]);

    // Posted to the submission, not to a CRUD endpoint: it is a `save` event, matched by a system
    // workflow, and the question is named by its path relative to the schema version
    const [ url, options ] = fetchMock.mock.calls[0] as unknown as
      [ string, { method: string; body: URLSearchParams } ];
    expect(url).toBe(PATH);
    expect(options.method).toBe("POST");
    expect(options.body.getAll("details/startDate")).toEqual([ "2026-10-06" ]);
  });

  it("repeats a question that holds several values", async () => {
    // Which is what the handler reads back as a multi-valued answer
    const fetchMock = vi.fn(() => response({}));
    vi.stubGlobal("fetch", fetchMock);

    await saveAnswer(PATH, "details/days", [ "Monday", "Tuesday" ]);

    const [ , options ] = fetchMock.mock.calls[0] as unknown as [ string, { body: URLSearchParams } ];
    expect(options.body.getAll("details/days")).toEqual([ "Monday", "Tuesday" ]);
  });

  it("names the question even when it is being cleared", async () => {
    // The handler walks the questions the payload mentions, so an emptied field that sent nothing at
    // all would leave its old answer in place -- and the request would go on counting as complete
    const fetchMock = vi.fn(() => response({}));
    vi.stubGlobal("fetch", fetchMock);

    await saveAnswer(PATH, "details/startDate", []);

    const [ , options ] = fetchMock.mock.calls[0] as unknown as [ string, { body: URLSearchParams } ];
    expect(options.body.getAll("details/startDate")).toEqual([ "" ]);
  });

  it("reports the engine's own reason for refusing", async () => {
    // A refusal carries why — not the submitter's request, or no longer a draft — and repeating that
    // verbatim beats inventing a message over the top of it
    vi.stubGlobal("fetch", vi.fn(() => response(
      { error: "This request has been submitted and can no longer be changed" },
      { ok: false, status: 403 })));

    await expect(saveAnswer(PATH, "details/startDate", [ "x" ]))
      .rejects.toThrow("This request has been submitted and can no longer be changed");
  });

  it("falls back to the status when a refusal carries no reason", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
      ok: false,
      status: 500,
      json: () => Promise.reject(new Error("no body")),
    } as unknown as Response)));

    await expect(saveAnswer(PATH, "details/startDate", [ "x" ]))
      .rejects.toThrow(/could not be saved \(500\)/);
  });

  it("asks again when the entity moved while the answer was in flight", async () => {
    // 409 is the engine saying somebody committed first — often this same form, autosaving the
    // field before this one. Nothing is wrong with the answer, so it is re-sent against the state
    // that now exists rather than handed back to the person who typed it
    let calls = 0;
    const fetchMock = vi.fn(() => {
      calls += 1;
      return calls === 1
        ? response({ error: "changed at the same time" }, { ok: false, status: 409 })
        : response({});
    });
    vi.stubGlobal("fetch", fetchMock);

    await saveAnswer(PATH, "details/startDate", [ "2026-10-06" ]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("gives up on a conflict that keeps recurring rather than asking forever", async () => {
    // Something that conflicts every time is not a race, and a bounded report beats a loop
    const fetchMock = vi.fn(() =>
      response({ error: "changed at the same time" }, { ok: false, status: 409 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(saveAnswer(PATH, "details/startDate", [ "x" ]))
      .rejects.toThrow("changed at the same time");

    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it("does not ask again when the refusal is about the answer rather than a race", async () => {
    // Re-sending an answer the engine has already judged unacceptable would only be refused again
    const fetchMock = vi.fn(() => response({ error: "no longer a draft" }, { ok: false, status: 403 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(saveAnswer(PATH, "details/startDate", [ "x" ])).rejects.toThrow("no longer a draft");

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
